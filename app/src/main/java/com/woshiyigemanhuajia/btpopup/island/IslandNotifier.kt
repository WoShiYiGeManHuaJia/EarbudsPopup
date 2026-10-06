package com.woshiyigemanhuajia.btpopup.island

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Bundle
import android.util.Log
import com.woshiyigemanhuajia.btpopup.R
import com.woshiyigemanhuajia.btpopup.adb.AdbShell
import com.woshiyigemanhuajia.btpopup.ui.MainActivity
import com.woshiyigemanhuajia.btpopup.util.Prefs
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger

/**
 * 小米「超级岛 / 焦点通知」上岛（耳机版）。
 *
 * 原理来自小米官方《超级岛开发指南》：
 *   notification.extras.putString("miui.focus.param", islandParamsJson)
 *  —— 系统通知栏读到这个字段，就把普通通知渲染成「岛」的形态。
 *
 * 本实现把 NexioSchedule（软大课表）里那套上岛逻辑精简成耳机场景：
 * **左边耳机图标 + 右边「已连接」**，不做倒计时、不做按钮、不做展开大卡片。
 *
 * 关于 Shizuku / Stellar 绕过：
 * 小米对第三方上岛有白名单校验，校验过程需要 com.xiaomi.xmsf（小米服务框架）联网。
 * 课表那套做法是用 Shizuku 提权临时**断掉 xmsf 的网络**再发通知，发完立刻恢复
 * —— 断网期间校验失败，系统放行第三方通知上岛。
 * 这里沿用同一思路，但改用本 App 已有的 AdbShell（shell 身份）执行，
 * 不再需要额外的 AIDL 特权服务。
 */
object IslandNotifier {

    private const val TAG = "IslandNotifier"

    private const val CHANNEL_ID = "bt_popup_island"
    private const val CHANNEL_NAME = "耳机上岛"

    /** 岛通知固定 ID：连接后要能精确取消 */
    const val ISLAND_NOTIFICATION_ID = 1003

    /** 业务标识：随便一个稳定字符串即可，系统只用来区分来源 */
    private const val BUSINESS_TAG = "bt_earbuds"

    /** 小米服务框架：上岛校验时需要临时断网的目标 */
    private const val XMSF_PACKAGE = "com.xiaomi.xmsf"

    /** OEM 防火墙链：与系统 setFirewallChainEnabled 的 OEM_DENY 常量一致 */
    private const val FIREWALL_CHAIN_OEM_DENY = 9

    private val sequence = AtomicInteger(0)

    /** 串行化「断网 → 发通知 → 恢复网络」，避免并发时网络状态错乱 */
    private val bypassLock = Any()

    /** 绕过专用单线程：shell 命令必须串行且不能占主线程 */
    private val bypassExec: java.util.concurrent.ExecutorService =
        java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "island-bypass").apply { isDaemon = true }
        }

    // ------------------------------------------------------------------ 能力判定

    /**
     * 设备是否支持岛。
     *
     * 官方判定方式：读系统属性 persist.sys.feature.island。
     * 读不到 / 反射失败一律按不支持处理，绝不因此影响弹窗本身。
     */
    fun isSupported(): Boolean {
        return try {
            val clazz = Class.forName("android.os.SystemProperties")
            val m = clazz.getMethod("getBoolean", String::class.java, Boolean::class.javaPrimitiveType)
            m.invoke(null, "persist.sys.feature.island", false) as Boolean
        } catch (t: Throwable) {
            Log.w(TAG, "读取岛能力属性失败: " + t.message)
            false
        }
    }

    /** 焦点通知协议版本：HyperOS 3 才支持超级岛，OS2 只有旧焦点通知 */
    fun focusProtocol(context: Context): Int {
        return try {
            android.provider.Settings.System.getInt(
                context.contentResolver, "notification_focus_protocol", 0
            )
        } catch (t: Throwable) {
            0
        }
    }

    private fun ensureChannel(context: Context) {
        try {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "耳机连接状态超级岛"
                setShowBadge(false)
                // 不放行的话，开勿扰后岛会被一并屏蔽
                setBypassDnd(true)
            }
            manager.createNotificationChannel(channel)
        } catch (t: Throwable) {
            Log.w(TAG, "创建岛通知渠道失败: " + t.message)
        }
    }

    // ------------------------------------------------------------------ 岛参数

    /**
     * 构造 miui.focus.param。
     *
     * 结构对齐官方 param_v2：
     *   param_v2.baseInfo      通知卡片（下拉通知栏里看到的那条）
     *   param_v2.param_island  岛的形态
     *     .bigIslandArea       展开态 / 大岛：左图标 + 右文字
     *     .smallIslandArea     摘要态 / 小岛（胶囊）：只放图标
     *
     * 用户要的效果就是「左边耳机图标，右边『已连接』」，
     * 因此 bigIslandArea 用图文模板（templateNo=2）：
     *   imageTextInfoLeft → 图片（耳机图标，type=0）
     *   textInfo          → 文字（已连接）
     */
    private fun buildParams(deviceName: String, stateText: String, batteryText: String?): String {
        val json = JSONObject()
        val paramV2 = JSONObject().apply {
            put("business", BUSINESS_TAG)
            put("protocol", 1)
            // 耳机连接是瞬时事件，不需要悬浮窗，只上岛
            put("enableFloat", false)
            put("islandFirstFloat", true)
            put("updatable", true)
            put("outEffectSrc", "")
            put("reopen", "reopen")
            put("sequence", sequence.incrementAndGet())
            put("aodTitle", deviceName.ifBlank { "耳机" })

            // 通知卡片本体
            put("baseInfo", JSONObject().apply {
                put("type", 2)
                put("title", deviceName.ifBlank { "蓝牙耳机" })
                put("content", buildString {
                    append(stateText)
                    if (!batteryText.isNullOrBlank()) append(" · ").append(batteryText)
                })
                put("subTitle", "")
                put("extraTitle", "")
                put("specialTitle", "")
                put("subContent", "")
                put("picFunction", "")
                put("showDivider", true)
                put("showContentDivider", false)
                put("colorTitle", "#111111")
                put("colorTitleDark", "#ffffff")
                put("colorContent", "#333333")
                put("colorContentDark", "#cccccc")
            })

            put("picInfo", JSONObject().apply {
                put("type", 1)
                put("pic", "")
            })

            put("param_island", JSONObject().apply {
                // islandProperty=1：常驻型岛；islandTimeout 秒级超时
                put("islandProperty", 1)
                put("islandTimeout", 3600)

                put("bigIslandArea", JSONObject().apply {
                    put("templateNo", 2)

                    // 左：耳机图标
                    put("imageTextInfoLeft", JSONObject().apply {
                        put("type", 0)
                        put("picInfo", JSONObject().apply {
                            put("pic", "miui.focus.pic_imageText")
                            put("picDark", "miui.focus.pic_imageText_dark")
                        })
                    })

                    // 右：「已连接」
                    put("textInfo", JSONObject().apply {
                        put("frontTitle", "")
                        put("title", stateText)
                        put("content", "")
                        put("showHighlightColor", false)
                        put("narrowFont", false)
                    })
                })

                // 小岛（胶囊）：只显示图标
                put("smallIslandArea", JSONObject().apply {
                    put("picInfo", JSONObject().apply {
                        put("type", 1)
                        put("pic", "miui.focus.pic_small")
                        put("picDark", "miui.focus.pic_small_dark")
                    })
                })
            })
        }
        json.put("param_v2", paramV2)
        return json.toString()
    }

    // ------------------------------------------------------------------ 发送 / 取消

    /**
     * 上岛：显示「耳机图标 + 已连接」。
     *
     * @param deviceName 耳机名（岛的标题与卡片标题）
     * @param batteryText 电量文字，可为空（只显示状态）
     */
    fun show(context: Context, deviceName: String, batteryText: String? = null) {
        if (!Prefs.islandEnabled) return
        if (!isSupported()) {
            Log.i(TAG, "设备不支持岛，跳过上岛")
            return
        }
        ensureChannel(context)

        val stateText = "已连接"
        val params = buildParams(deviceName, stateText, batteryText)

        val contentIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context, ISLAND_NOTIFICATION_ID, contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_island_headset)
            .setContentTitle(deviceName.ifBlank { "蓝牙耳机" })
            .setContentText(stateText)
            .setContentIntent(pendingIntent)
            .setAutoCancel(false)
            .setOngoing(false)

        //
        // 图片资源：系统按 key 名去 extras 里取 Icon。
        // 这里把可能用到的 key 一次性塞满，避免某个模板取不到图而显示空白。
        //
        val pics = Bundle().apply {
            // 岛上的图标要能看清：浅色底用深色图标，深色底用白色图标
            val headset = Icon.createWithResource(context, R.drawable.ic_island_headset)
            val headsetDark = Icon.createWithResource(context, R.drawable.ic_island_headset_dark)
            val launcher = Icon.createWithResource(context, R.mipmap.ic_launcher)
            // 大岛左侧图文组件的图（耳机图标）
            putParcelable("miui.focus.pic_imageText", headset)
            putParcelable("miui.focus.pic_imageText_dark", headsetDark)
            // 小岛 / 胶囊
            putParcelable("miui.focus.pic_small", headset)
            putParcelable("miui.focus.pic_small_dark", headsetDark)
            // 应用图标
            putParcelable("miui.focus.pic_app_icon", launcher)
            putParcelable("miui.focus.pic_app_icon_dark", launcher)
        }
        builder.addExtras(Bundle().apply { putBundle("miui.focus.pics", pics) })

        val notification = builder.build()
        notification.extras.putString("miui.focus.param", params)

        // 绕过校验要在子线程做（含 shell 命令），绝不能占主线程
        val app = context.applicationContext
        bypassExec.execute { withBypass(app, notification) }
    }

    /** 取消岛通知（耳机断开 / 用户关开关时调用） */
    fun cancel(context: Context) {
        try {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.cancel(ISLAND_NOTIFICATION_ID)
            Log.i(TAG, "已取消岛通知")
        } catch (t: Throwable) {
            Log.w(TAG, "取消岛通知失败: " + t.message)
        }
    }

    // ------------------------------------------------------------------ 上岛校验绕过

    /**
     * 断网 xmsf → 发通知 → 恢复网络。
     *
     * 小米的上岛白名单校验依赖 xmsf 联网，断网期间校验失败会放行第三方通知。
     * 任何一步失败都直接按普通方式发，绝不因为绕过失败而不发通知；
     * finally 里保证网络一定恢复，避免把 xmsf 永久断网。
     */
    private fun withBypass(context: Context, notification: Notification) {
        val canShell = try {
            AdbShell.binderAlive() && AdbShell.hasPermission()
        } catch (t: Throwable) {
            false
        }
        if (!canShell) {
            Log.i(TAG, "无 Stellar / Shizuku 权限，按普通方式发送（可能上不了岛）")
            sendDirect(context, notification)
            return
        }
        synchronized(bypassLock) {
            val uid = xmsfUid(context)
            if (uid == null) {
                sendDirect(context, notification)
                return
            }
            var disabled = false
            try {
                disabled = setXmsfNetworking(uid, false)
                sendDirect(context, notification)
                // 给系统一点时间去读这条通知并渲染成岛，再恢复网络
                try {
                    Thread.sleep(120)
                } catch (ignored: InterruptedException) {
                }
            } finally {
                try {
                    setXmsfNetworking(uid, true)
                } catch (t: Throwable) {
                    Log.e(TAG, "恢复 xmsf 网络失败（重要）: " + t.message)
                }
            }
            if (!disabled) Log.w(TAG, "未能断开 xmsf 网络，已按普通方式发送通知")
        }
    }

    private fun sendDirect(context: Context, notification: Notification) {
        try {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(ISLAND_NOTIFICATION_ID, notification)
            Log.i(TAG, "岛通知已发送")
        } catch (t: Throwable) {
            Log.w(TAG, "发送岛通知失败: " + t.message)
        }
    }

    private fun xmsfUid(context: Context): Int? {
        return try {
            context.packageManager.getPackageUid(XMSF_PACKAGE, 0)
        } catch (t: Throwable) {
            Log.w(TAG, "获取 xmsf uid 失败: " + t.message)
            null
        }
    }

    /**
     * 用 shell 身份切换 xmsf 的联网状态。
     *
     * 与课表那套 AIDL 特权服务做的是同一件事：
     *   setFirewallChainEnabled(OEM_DENY, true)
     *   setUidFirewallRule(OEM_DENY, uid, ALLOW/DENY)
     * 这里走 `cmd connectivity` 命令，等价且不需要额外服务。
     */
    private fun setXmsfNetworking(uid: Int, enabled: Boolean): Boolean {
        val rule = if (enabled) "0" else "2" // 0=ALLOW 2=DENY
        val r1 = AdbShell.exec("cmd connectivity set-firewall-chain-enabled $FIREWALL_CHAIN_OEM_DENY true")
        val r2 = AdbShell.exec("cmd connectivity set-uid-firewall-rule $FIREWALL_CHAIN_OEM_DENY $uid $rule")
        val ok = r2.ok
        Log.d(TAG, "xmsf 联网 enabled=$enabled -> chain=${r1.ok} rule=${r2.ok} ${r2.brief}")
        return ok
    }
}
