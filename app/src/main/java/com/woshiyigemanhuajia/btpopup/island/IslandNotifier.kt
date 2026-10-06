package com.woshiyigemanhuajia.btpopup.island

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.Uri
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
 * 原理（小米官方《超级岛开发指南》）：
 *   notification.extras.putString("miui.focus.param", json)   // 岛的形态与内容
 *   notification.extras.putString("miui.focus.ticker", text)  // 状态栏胶囊文字
 *   notification.extras.putBundle("miui.focus.pics", 图标)     // 岛上用到的图
 *
 * 【这次修正的三个关键点 —— 上一版只出普通通知、不上岛的根因】
 *
 * 1. 漏了独立的 `miui.focus.ticker` 字段。
 *    它不在 param JSON 里，是要**单独**往 extras 里 putString 的，
 *    系统靠它把这条通知识别为焦点通知。只写 param 的话系统当普通通知处理。
 * 2. protocol 写成 1，而 HyperOS 3 的超级岛要求 protocol = 3。
 * 3. param_v2 里缺 ticker / islandPriority 字段。
 *
 * 参照：小米官方开发指南 + D4vidDf/HyperIsland-ToolKit（第三方上岛库）的字段定义。
 *
 * 关于 Stellar / Shizuku 绕过：
 * 小米对第三方上岛有白名单校验，校验时 com.xiaomi.xmsf 需要联网。
 * 思路是提权**临时断掉 xmsf 网络**再发通知，发完立刻恢复。
 * 这里用本 App 已有的 AdbShell（shell 身份）执行，不需要额外特权服务。
 */
object IslandNotifier {

    private const val TAG = "IslandNotifier"

    private const val CHANNEL_ID = "bt_popup_island"
    private const val CHANNEL_NAME = "耳机上岛"

    /** 岛通知固定 ID：断开时要能精确取消 */
    const val ISLAND_NOTIFICATION_ID = 1003

    /** 业务标识：稳定字符串即可，系统只用来区分来源 */
    private const val BUSINESS_TAG = "bt_earbuds"

    private const val XMSF_PACKAGE = "com.xiaomi.xmsf"

    /** OEM 防火墙链：与系统 setFirewallChainEnabled 的 OEM_DENY 一致 */
    private const val FIREWALL_CHAIN_OEM_DENY = 9

    /** HyperOS 3 的超级岛要求 protocol = 3；OS2 的旧焦点通知是 1~2 */
    private const val PROTOCOL_SUPER_ISLAND = 3

    private val sequence = AtomicInteger(0)

    private val bypassLock = Any()

    private val bypassExec: java.util.concurrent.ExecutorService =
        java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "island-bypass").apply { isDaemon = true }
        }

    // ------------------------------------------------------------------ 能力判定

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

    /** 焦点通知协议版本：2 以下没有超级岛 */
    fun focusProtocol(context: Context): Int {
        return try {
            android.provider.Settings.System.getInt(
                context.contentResolver, "notification_focus_protocol", 0
            )
        } catch (t: Throwable) {
            0
        }
    }

    /**
     * 焦点通知权限是否开启。
     *
     * 官方给出的判定接口：content://miui.statusbar.notification.public 的 canShowFocus。
     * 这个开关没开的话，即使参数完全正确也上不了岛 —— 这是最常见的"只出普通通知"原因。
     */
    fun canShowFocus(context: Context): String {
        return try {
            val uri = Uri.parse("content://miui.statusbar.notification.public")
            val r = context.contentResolver.call(uri, "canShowFocus", null, null)
            if (r == null) "接口返回 null"
            else {
                val keys = r.keySet().joinToString(",") { "$it=${r.get(it)}" }
                keys.ifBlank { "返回空 Bundle" }
            }
        } catch (t: Throwable) {
            "查询失败: " + (t.message ?: t.toString())
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
     * 大岛用图文模板：左耳机图标 + 右「已连接」。
     * 同时给出 ticker（状态栏胶囊文字），它是 param 里的必填字段。
     */
    private fun buildParams(ticker: String, deviceName: String, batteryText: String?): String {
        val json = JSONObject()
        val paramV2 = JSONObject().apply {
            put("business", BUSINESS_TAG)
            // HyperOS 3 的超级岛必须是 3
            put("protocol", PROTOCOL_SUPER_ISLAND)
            // 状态栏胶囊文字：param 里的必填字段
            put("ticker", ticker)
            put("updatable", true)
            put("isShowNotification", true)
            put("enableFloat", false)
            put("islandFirstFloat", true)
            put("outEffectSrc", "")
            put("reopen", "reopen")
            put("sequence", sequence.incrementAndGet())
            put("aodTitle", deviceName.ifBlank { "耳机" })
            // 状态栏胶囊图标（走 pics 里的 key）
            put("tickerPic", "miui.focus.pic_ticker")
            put("tickerPicDark", "miui.focus.pic_ticker_dark")

            put("baseInfo", JSONObject().apply {
                put("type", 2)
                put("title", deviceName.ifBlank { "蓝牙耳机" })
                put("content", buildString {
                    append("已连接")
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
                put("islandProperty", 1)
                put("islandPriority", 2)
                put("islandTimeout", 3600)

                put("bigIslandArea", JSONObject().apply {
                    // 左：耳机图标
                    put("imageTextInfoLeft", JSONObject().apply {
                        put("type", 1)
                        put("picInfo", JSONObject().apply {
                            put("type", 1)
                            put("pic", "miui.focus.pic_imageText")
                            put("picDark", "miui.focus.pic_imageText_dark")
                        })
                    })

                    // 右：「已连接」
                    put("textInfo", JSONObject().apply {
                        put("title", "已连接")
                        put("content", "")
                        put("showHighlightColor", false)
                        put("narrowFont", false)
                    })
                })

                // 小岛 / 胶囊：只显示图标
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

    fun show(context: Context, deviceName: String, batteryText: String? = null) {
        if (!Prefs.islandEnabled) return
        if (!isSupported()) {
            Log.i(TAG, "设备不支持岛，跳过上岛")
            return
        }
        ensureChannel(context)

        val name = deviceName.ifBlank { "蓝牙耳机" }
        val ticker = "已连接"
        val params = buildParams(ticker, name, batteryText)

        val contentIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context, ISLAND_NOTIFICATION_ID, contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_island_headset)
            .setContentTitle(name)
            .setContentText(ticker)
            .setContentIntent(pendingIntent)
            .setAutoCancel(false)
            // 焦点通知本质是"进行中的服务"，按官方示例用 TickerText 再补一道
            .setTicker(ticker)

        val pics = Bundle().apply {
            val headset = Icon.createWithResource(context, R.drawable.ic_island_headset)
            val headsetDark = Icon.createWithResource(context, R.drawable.ic_island_headset_dark)
            val launcher = Icon.createWithResource(context, R.mipmap.ic_launcher)
            // 状态栏胶囊图标
            putParcelable("miui.focus.pic_ticker", headset)
            putParcelable("miui.focus.pic_ticker_dark", headsetDark)
            // 大岛左侧图文组件
            putParcelable("miui.focus.pic_imageText", headset)
            putParcelable("miui.focus.pic_imageText_dark", headsetDark)
            // 小岛
            putParcelable("miui.focus.pic_small", headset)
            putParcelable("miui.focus.pic_small_dark", headsetDark)
            // 应用图标
            putParcelable("miui.focus.pic_app_icon", launcher)
            putParcelable("miui.focus.pic_app_icon_dark", launcher)
        }

        val notification = builder.build()

        //
        // 三个字段缺一不可：
        //   miui.focus.param  岛的形态（JSON）
        //   miui.focus.pics   岛上用到的图标
        //   miui.focus.ticker 状态栏胶囊文字（独立字段，不在 JSON 里）
        //
        notification.extras.putString("miui.focus.param", params)
        notification.extras.putBundle("miui.focus.pics", pics)
        notification.extras.putString("miui.focus.ticker", ticker)

        val app = context.applicationContext
        bypassExec.execute { withBypass(app, notification) }
    }

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

    private fun withBypass(context: Context, notification: Notification) {
        val canShell = try {
            AdbShell.binderAlive() && AdbShell.hasPermission()
        } catch (t: Throwable) {
            false
        }
        if (!canShell) {
            Log.i(TAG, "无 Stellar / Shizuku 权限，按普通方式发送（大概率上不了岛）")
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
                try {
                    Thread.sleep(150)
                } catch (ignored: InterruptedException) {
                }
            } finally {
                try {
                    setXmsfNetworking(uid, true)
                } catch (t: Throwable) {
                    Log.e(TAG, "恢复 xmsf 网络失败（重要）: " + t.message)
                }
            }
            if (!disabled) Log.w(TAG, "未能断开 xmsf 网络")
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

    private fun setXmsfNetworking(uid: Int, enabled: Boolean): Boolean {
        val rule = if (enabled) "0" else "2" // 0=ALLOW 2=DENY
        val r1 = AdbShell.exec("cmd connectivity set-firewall-chain-enabled $FIREWALL_CHAIN_OEM_DENY true")
        val r2 = AdbShell.exec("cmd connectivity set-uid-firewall-rule $FIREWALL_CHAIN_OEM_DENY $uid $rule")
        Log.d(TAG, "xmsf 联网 enabled=$enabled -> chain=${r1.ok} rule=${r2.ok} ${r2.brief}")
        return r2.ok
    }

    // ------------------------------------------------------------------ 诊断

    /**
     * 把所有影响上岛的系统状态读出来。
     *
     * 之前几个版本我一直在猜参数，结果每次都只是"多一条普通通知"。
     * 与其继续猜，不如把这些值直接显示给用户，让真机数据说话。
     */
    fun diagnose(context: Context): String {
        val sb = StringBuilder()
        sb.append("========== 上岛诊断 ==========\n\n")

        fun prop(key: String): String = try {
            val c = Class.forName("android.os.SystemProperties")
            val m = c.getMethod("get", String::class.java, String::class.java)
            (m.invoke(null, key, "") as String).ifBlank { "(空)" }
        } catch (t: Throwable) {
            "读取失败: ${t.message}"
        }

        sb.append("机型: ${android.os.Build.MODEL} / ${android.os.Build.DEVICE}\n")
        sb.append("系统: Android ${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT})\n")
        sb.append("MIUI/HyperOS: ${prop("ro.miui.ui.version.name")}\n")
        sb.append("版本增量: ${prop("ro.build.version.incremental")}\n\n")

        val supported = isSupported()
        val protocol = focusProtocol(context)
        sb.append("【岛能力】persist.sys.feature.island = $supported\n")
        sb.append("【协议版本】notification_focus_protocol = $protocol  （需 ≥3 才有超级岛）\n")
        sb.append("【焦点权限】canShowFocus = ${canShowFocus(context)}\n")
        sb.append("【上岛开关】island_enabled = ${Prefs.islandEnabled}\n")

        val shellOk = try {
            AdbShell.binderAlive() && AdbShell.hasPermission()
        } catch (t: Throwable) {
            false
        }
        sb.append("【Stellar/Shizuku】${if (shellOk) "已授权（可绕过白名单）" else "未授权或不可用"}\n")

        // 通知渠道与权限
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val ch = nm.getNotificationChannel(CHANNEL_ID)
            sb.append("【通知渠道】${if (ch == null) "未创建" else "已创建 importance=" + ch.importance}\n")
            sb.append("【通知权限】${if (nm.areNotificationsEnabled()) "已开启" else "已关闭（必上不了岛）"}\n")
        } catch (t: Throwable) {
            sb.append("【通知状态】读取失败: ${t.message}\n")
        }

        sb.append("\n---------------- 判定 ----------------\n")
        val reasons = mutableListOf<String>()
        if (!supported) reasons += "系统属性 persist.sys.feature.island 为 false"
        if (protocol < 3) reasons += "焦点协议版本 $protocol < 3，不支持超级岛"
        if (!shellOk) reasons += "无 Stellar/Shizuku 授权，白名单校验可能拦截第三方上岛"
        sb.append(
            if (reasons.isEmpty()) "未发现明显阻塞项。若仍只出普通通知，则多为系统白名单限制。\n"
            else reasons.joinToString("\n") { "- $it" } + "\n"
        )
        sb.append("========== 诊断结束 ==========\n")
        return sb.toString()
    }
}
