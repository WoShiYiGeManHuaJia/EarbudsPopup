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
import com.woshiyigemanhuajia.btpopup.shizuku.IslandPrivilege
import com.woshiyigemanhuajia.btpopup.ui.MainActivity
import com.woshiyigemanhuajia.btpopup.util.Prefs
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger

/**
 * 小米「超级岛 / 焦点通知」上岛（耳机版）。
 *
 * 【这一版改为逐字照搬 NexioSchedule（软大课表）已验证可用的那份实现】
 *
 * 前几版我按自己的理解重写了参数，结果每次都只出普通通知。
 * 课表那份是在同类 HyperOS 3 机器上实打实能上岛的，所以这次的做法是：
 *   **原样保留它的 JSON 骨架，只替换内容。**
 *
 * 与课表原实现保持一致的关键点（这些正是我之前改错的地方）：
 *   1. protocol = 1（不是 3）
 *   2. bigIslandArea 里带 templateNo = 2
 *   3. 带完整的 hintInfo（含 timerInfo），缺了它系统可能不认
 *   4. **不**往 extras 里额外塞 miui.focus.ticker —— 课表没有这一步
 *   5. 通知 setAutoCancel(true)、带 picInfo（type=1, pic=""）
 *   6. 图标通过 builder.addExtras( Bundle{ putBundle("miui.focus.pics", ...) } ) 注入
 *
 * 只改动的部分：
 *   - 文案：左区图标 / 耳机名，右区「已连接」
 *   - 图标：耳机图标（浅底 + 深底两版）
 *   - 触发时机：蓝牙耳机连接时（由 BluetoothPopupTrigger 调用）
 *   - 去掉倒计时、按钮、展开态等课程专属内容
 */
object IslandNotifier {

    private const val TAG = "IslandNotifier"

    /** 与课表一致：通知渠道高优先级 + 免打扰放行 */
    private const val CHANNEL_ID = "bt_popup_island"
    private const val CHANNEL_NAME = "耳机上岛"

    const val ISLAND_NOTIFICATION_ID = 1003

    /** 业务标识：课表用 "course_reminder"，这里换成耳机专用 */
    private const val BUSINESS_TAG = "bt_earbuds"

    private const val XMSF_PACKAGE = "com.xiaomi.xmsf"

    /** OEM 防火墙链（OEM_DENY） */
    private const val FIREWALL_CHAIN_OEM_DENY = 9

    private val sequence = AtomicInteger(0)

    private val bypassLock = Any()

    private val bypassExec: java.util.concurrent.ExecutorService =
        java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "island-bypass").apply { isDaemon = true }
        }

    /** 上一次绕过尝试的结果，供诊断显示 */
    @Volatile
    var lastBypassReport: String = "尚未尝试"
        private set

    // ------------------------------------------------------------------ 权限

    /** Android 16 新增的「推广通知」权限：小米超级岛走的就是这条通道 */
    private const val PERM_PROMOTED = "android.permission.POST_PROMOTED_NOTIFICATIONS"

    fun isPromotedGranted(context: Context): Boolean {
        return try {
            context.checkSelfPermission(PERM_PROMOTED) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } catch (t: Throwable) {
            false
        }
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

    fun focusProtocol(context: Context): Int {
        return try {
            android.provider.Settings.System.getInt(
                context.contentResolver, "notification_focus_protocol", 0
            )
        } catch (t: Throwable) {
            0
        }
    }

    fun canShowFocus(context: Context): String {
        return try {
            val uri = Uri.parse("content://miui.statusbar.notification.public")
            val r = context.contentResolver.call(uri, "canShowFocus", null, null)
            if (r == null) "接口返回 null（未知）"
            else r.keySet().joinToString(",") { "$it=${r.get(it)}" }.ifBlank { "返回空 Bundle" }
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
                setShowBadge(true)
                setBypassDnd(true)
            }
            manager.createNotificationChannel(channel)
        } catch (t: Throwable) {
            Log.w(TAG, "创建岛通知渠道失败: " + t.message)
        }
    }

    // ------------------------------------------------------------------ 岛参数（照搬课表骨架）

    /**
     * 构造 miui.focus.param。
     *
     * 骨架与课表 buildIslandParamsJson 完全一致，只把课程内容换成耳机内容：
     *   - 左区 imageTextInfoLeft：耳机图标（或耳机名，可在设置里选）
     *   - 右区 textInfo.title：「已连接」
     *   - hintInfo / timerInfo：按课表写法保留（非倒计时场景用 timerType=0）
     */
    private fun buildParams(context: Context, deviceName: String, batteryText: String?): String {
        val json = JSONObject()
        val now = System.currentTimeMillis()

        val paramV2 = JSONObject().apply {
            put("business", BUSINESS_TAG)
            // 课表原值，不要改成 3
            put("protocol", 1)
            // 耳机连接是瞬时事件，不弹悬浮窗，只走岛
            put("islandFirstFloat", true)
            put("enableFloat", false)
            put("updatable", true)
            put("outEffectSrc", "")
            put("reopen", "reopen")
            put("sequence", sequence.incrementAndGet())
            put("aodTitle", deviceName.ifBlank { "耳机" })

            // 通知卡片本体（下拉通知栏里看到的那条）
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

            // 课表里同样带 hintInfo，保留骨架（这里没有倒计时，timerType=0）
            put("hintInfo", JSONObject().apply {
                put("type", 2)
                put("content", "现在")
                put("title", "已连接")
                put("timerInfo", JSONObject().apply {
                    put("timerType", 0)
                    put("timerWhen", 0)
                    put("timerTotal", 0)
                    put("timerSystemCurrent", 0)
                })
                put("subContent", "")
                put("subTitle", deviceName.ifBlank { "" })
                put("colorContent", "#666666")
                put("colorContentDark", "#aaaaaa")
                put("colorTitle", "#222222")
                put("colorTitleDark", "#eeeeee")
                put("colorSubContent", "#666666")
                put("colorSubContentDark", "#aaaaaa")
                put("colorSubTitle", "#222222")
                put("colorSubTitleDark", "#eeeeee")
            })

            put("param_island", JSONObject().apply {
                put("islandProperty", 1)
                put("islandTimeout", 3600)

                put("bigIslandArea", JSONObject().apply {
                    // 课表原值
                    put("templateNo", 2)

                    //
                    // 课表成品里 imageTextInfoLeft 只用 textInfo（type=1，文字），
                    // 不用图标。所以默认走文字模式。
                    // 图标模式保留，但 pic 只能引用 pics 里真实存在的 key（pic_small），
                    // 否则系统取不到图。
                    //
                    if (Prefs.islandLeftIcon) {
                        put("imageTextInfoLeft", JSONObject().apply {
                            put("type", 0)
                            put("picInfo", JSONObject().apply {
                                put("type", 1)
                                put("pic", "miui.focus.pic_small")
                                put("picDark", "miui.focus.pic_small_dark")
                            })
                        })
                    } else {
                        put("imageTextInfoLeft", JSONObject().apply {
                            put("type", 1)
                            put("textInfo", JSONObject().apply {
                                put("title", deviceName.ifBlank { "耳机" })
                                put("content", "")
                                put("showHighlightColor", false)
                                put("narrowFont", false)
                            })
                        })
                    }

                    // 右：「已连接」
                    put("textInfo", JSONObject().apply {
                        put("frontTitle", "")
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
        val content = buildString {
            append("已连接")
            if (!batteryText.isNullOrBlank()) append(" · ").append(batteryText)
        }

        val contentIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context, ISLAND_NOTIFICATION_ID, contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // 与课表一致
        val builder = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_island_headset)
            .setContentTitle(name)
            .setContentText(content)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)

        //
        // 【与课表成品 APK 逐字对齐】
        // 反编译课表 APK 后确认：它 dex 里出现的 miui.focus 字符串一共只有 6 个，
        // 其中 pics 里只放这 4 个 key：pic_app_icon / pic_app_icon_dark / pic_small / pic_small_dark。
        // 我前几版额外放的 pic_imageText / pic_imageText_dark 课表根本没有，
        // 这里删掉 —— 多出来的 key 可能让系统解析异常。
        //
        val picsBundle = Bundle().apply {
            val headset = Icon.createWithResource(context, R.drawable.ic_island_headset)
            val headsetDark = Icon.createWithResource(context, R.drawable.ic_island_headset_dark)
            val launcher = Icon.createWithResource(context, R.mipmap.ic_launcher)
            putParcelable("miui.focus.pic_app_icon", launcher)
            putParcelable("miui.focus.pic_app_icon_dark", launcher)
            putParcelable("miui.focus.pic_small", headset)
            putParcelable("miui.focus.pic_small_dark", headsetDark)
        }
        builder.addExtras(Bundle().apply {
            putBundle("miui.focus.pics", picsBundle)
        })

        val notification = builder.build()
        // 课表就是这一行，不再额外塞别的字段
        notification.extras.putString("miui.focus.param", buildParams(context, name, batteryText))

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

    // ------------------------------------------------------------------ 白名单绕过

    /**
     * 断网 xmsf → 发通知 → 恢复网络。
     *
     * 【这一版改回课表的路径】
     * 之前我用 shell 命令（cmd connectivity ...）去断网，诊断显示"断网命令失败"，
     * 等于根本没绕过校验，自然只出普通通知。
     * 现在与课表一致：Shizuku → 特权服务 → 反射调 IConnectivityManager。
     */
    private fun withBypass(context: Context, notification: Notification) {
        val canShell = try {
            IslandPrivilege.isShizukuRunning() && IslandPrivilege.checkSelfPermission()
        } catch (t: Throwable) {
            false
        }
        if (!canShell) {
            lastBypassReport = "无 Stellar/Shizuku 权限，未绕过，直接发送"
            sendDirect(context, notification)
            return
        }
        synchronized(bypassLock) {
            var disabled = false
            try {
                disabled = IslandPrivilege.setXmsfNetworkingEnabled(context, false)
                lastBypassReport = "断网: " + IslandPrivilege.lastReport
                sendDirect(context, notification)
                try {
                    Thread.sleep(150)
                } catch (ignored: InterruptedException) {
                }
            } finally {
                try {
                    IslandPrivilege.setXmsfNetworkingEnabled(context, true)
                    lastBypassReport += "\n恢复: " + IslandPrivilege.lastReport
                } catch (t: Throwable) {
                    Log.e(TAG, "恢复 xmsf 网络失败（重要）: " + t.message)
                }
            }
            if (!disabled) lastBypassReport += "\n（断网失败，本次未按绕过方式发送）"
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

    /** 手动跑一次断网 / 恢复，把特权服务的真实结果返回出来 */
    fun testBypass(context: Context): String {
        val sb = StringBuilder()
        val running = try { IslandPrivilege.isShizukuRunning() } catch (t: Throwable) { false }
        val perm = try { IslandPrivilege.checkSelfPermission() } catch (t: Throwable) { false }
        sb.append("Shizuku 存活=$running  已授权=$perm\n")
        if (!running || !perm) return sb.append("无法执行绕过\n").toString()
        sb.append("--- 断网 ---\n")
        val off = IslandPrivilege.setXmsfNetworkingEnabled(context, false)
        sb.append("返回=$off  ").append(IslandPrivilege.lastReport).append('\n')
        sb.append("--- 恢复 ---\n")
        val on = IslandPrivilege.setXmsfNetworkingEnabled(context, true)
        sb.append("返回=$on  ").append(IslandPrivilege.lastReport).append('\n')
        return sb.toString()
    }

    // ------------------------------------------------------------------ 系统级扫描

    /**
     * 用 Shizuku 直接读系统当前所有通知，找出真正带 miui.focus.param 的那些。
     *
     * 这是判断"到底是参数错了，还是系统根本不放行第三方"的决定性证据：
     *   - 如果系统里只有系统应用（com.android.*, com.miui.*）带 focus param
     *     → 说明这台机器上第三方上岛被挡，改任何参数都没用
     *   - 如果有第三方应用带 → 把它真实的 param 抓出来照抄
     *
     * 之前几个版本我一直在猜参数，这次直接从系统里取真值。
     */
    fun scanSystemIsland(context: Context): String {
        val sb = StringBuilder()
        sb.append("========== 系统岛通知扫描 ==========\n\n")
        if (!AdbShell.binderAlive() || !AdbShell.hasPermission()) {
            return sb.append("需要 Stellar / Shizuku 权限才能扫描\n").toString()
        }

        fun run(cmd: String, limit: Int = 4000): String {
            return try {
                val r = AdbShell.exec(cmd)
                val t = if (r.out.isNotBlank()) r.out else r.err
                if (t.isBlank()) "(空)" else t.take(limit)
            } catch (t: Throwable) {
                "执行异常: ${t.message}"
            }
        }

        // 1. 系统里有多少条通知带 focus param
        sb.append("【1】当前通知中 miui.focus.param 出现次数\n")
        sb.append(run("dumpsys notification --noredact 2>/dev/null | grep -c 'miui.focus.param'", 200))
        sb.append("\n\n")

        // 2. 哪些包名发出了带 focus param 的通知
        sb.append("【2】带 focus param 的通知来自哪些包名\n")
        sb.append(run("dumpsys notification --noredact 2>/dev/null | grep -oE 'pkg=[a-zA-Z0-9._]+' | sort | uniq -c | sort -rn | head -30", 1500))
        sb.append("\n\n")

        // 3. 抓一段真实的 focus param 内容
        sb.append("【3】真实 miui.focus.param 片段\n")
        val raw = run("dumpsys notification --noredact 2>/dev/null | grep -m1 -o 'miui.focus.param=[^ ]*' | head -c 1500", 1800)
        sb.append(raw).append("\n\n")

        // 4. island / focus 相关系统属性
        sb.append("【4】系统属性（island / focus）\n")
        sb.append(run("getprop 2>/dev/null | grep -iE 'island|focus'", 1500))
        sb.append("\n\n")

        // 5. 设置项
        sb.append("【5】设置项（focus / island）\n")
        sb.append(run("settings list global 2>/dev/null | grep -iE 'focus|island'; settings list secure 2>/dev/null | grep -iE 'focus|island'; settings list system 2>/dev/null | grep -iE 'focus|island'", 1500))
        sb.append("\n\n")

        // 6. 本机上岛相关包是否安装
        sb.append("【6】小米服务框架状态\n")
        sb.append(run("pm list packages 2>/dev/null | grep -iE 'xmsf|miui.statusbar|focus'", 800))
        sb.append("\n\n========== 扫描结束 ==========\n")
        return sb.toString()
    }

    // ------------------------------------------------------------------ 诊断

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
        sb.append("【协议版本】notification_focus_protocol = $protocol\n")
        sb.append("【焦点权限】canShowFocus = ${canShowFocus(context)}\n")
        sb.append("【上岛开关】island_enabled = ${Prefs.islandEnabled}\n")
        sb.append("【推广通知权限】POST_PROMOTED_NOTIFICATIONS = ${if (isPromotedGranted(context)) "已授予" else "未授予（上不了岛的主因）"}\n")
        sb.append("【左区模式】${if (Prefs.islandLeftIcon) "耳机图标(type=0)" else "耳机名文字(type=1)"}\n")

        val shellOk = try {
            IslandPrivilege.isShizukuRunning() && IslandPrivilege.checkSelfPermission()
        } catch (t: Throwable) {
            false
        }
        sb.append("【Stellar/Shizuku】${if (shellOk) "已授权" else "未授权或不可用"}\n")
        sb.append("【上次绕过】$lastBypassReport\n")

        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val ch = nm.getNotificationChannel(CHANNEL_ID)
            sb.append("【通知渠道】${if (ch == null) "未创建" else "已创建 importance=" + ch.importance}\n")
            sb.append("【通知权限】${if (nm.areNotificationsEnabled()) "已开启" else "已关闭"}\n")
        } catch (t: Throwable) {
            sb.append("【通知状态】读取失败: ${t.message}\n")
        }

        sb.append("\n---------------- 判定 ----------------\n")
        val reasons = mutableListOf<String>()
        if (!supported) reasons += "系统属性 persist.sys.feature.island 为 false"
        if (!isPromotedGranted(context)) reasons += "缺少 POST_PROMOTED_NOTIFICATIONS 权限，去设置页点「一键 ADB 授权」"
        if (!shellOk) reasons += "无 Stellar/Shizuku 授权，无法绕过白名单校验"
        sb.append(
            if (reasons.isEmpty()) "未发现明显阻塞项。若仍只出普通通知，多为系统白名单限制。\n"
            else reasons.joinToString("\n") { "- $it" } + "\n"
        )
        sb.append("========== 诊断结束 ==========\n")
        return sb.toString()
    }
}
