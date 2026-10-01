package com.woshiyigemanhuajia.btpopup.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.woshiyigemanhuajia.btpopup.App
import com.woshiyigemanhuajia.btpopup.R
import com.woshiyigemanhuajia.btpopup.battery.BatteryInfo
import com.woshiyigemanhuajia.btpopup.battery.BatteryRepository
import com.woshiyigemanhuajia.btpopup.battery.BatteryUpdateBridge
import com.woshiyigemanhuajia.btpopup.bluetooth.BluetoothPopupTrigger
import com.woshiyigemanhuajia.btpopup.overlay.PopupOverlayManager
import com.woshiyigemanhuajia.btpopup.ui.MainActivity
import com.woshiyigemanhuajia.btpopup.util.PermissionGuard
import com.woshiyigemanhuajia.btpopup.util.Prefs

class BluetoothMonitorService : Service() {

    companion object {
        private const val TAG = "BtMonitor"
        private const val CHANNEL_ID = "bt_popup_monitor"
        private const val NOTIF_ID = 1001

        const val ACTION_START = "com.woshiyigemanhuajia.btpopup.START_MONITOR"
        const val ACTION_STOP = "com.woshiyigemanhuajia.btpopup.STOP_MONITOR"

        /** 读不到蓝牙地址时的占位 key：宁可少一次去重，也不能因为读地址失败而不弹窗 */
        private const val FALLBACK_ADDRESS = "00:00:00:00:00:00"

        fun start(context: Context) {
            val i = Intent(context, BluetoothMonitorService::class.java).setAction(ACTION_START)
            try {
                if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
            } catch (t: Throwable) {
                //
                // 修复「关掉通知栏权限，直接不弹窗了」：
                // Android 13+ 关掉通知权限后，startForegroundService 会被系统拒绝
                // （或启动后因无法显示通知被立刻回收），监听服务整个没起来、
                // 蓝牙广播压根没人注册 —— 表现就是彻底不弹窗。
                //
                // 这里退回普通 startService：服务照常创建、照常注册广播、照常弹窗，
                // 只是没有常驻通知。通知只是保活手段，不该成为弹窗的前提。
                Log.w(TAG, "startForegroundService 被拒，降级为普通后台服务: " + t.message)
                try {
                    context.startService(i)
                } catch (t2: Throwable) {
                    Log.e(TAG, "启动监听服务失败: " + t2.message)
                }
            }
        }

        /**
         * 由蓝牙广播兜底拉起服务。
         *
         * 进程不在前台时 startForegroundService 在 Android 12+ 会抛
         * ForegroundServiceStartNotAllowedException（ACL_CONNECTED 不在系统豁免名单），
         * 所以这里失败只记日志、绝不再抛异常：弹窗本身已由接收器直接 addView 完成。
         */
        fun startFromBluetoothEvent(context: Context) {
            val i = Intent(context, BluetoothMonitorService::class.java).setAction(ACTION_START)
            try {
                if (Build.VERSION.SDK_INT >= 26) {
                    context.startForegroundService(i)
                } else {
                    context.startService(i)
                }
            } catch (t: Throwable) {
                // 同上：前台拉不起就退普通服务，保证蓝牙广播有人接收
                Log.w(TAG, "蓝牙事件兜底拉起服务被系统拒绝，降级为普通后台服务: " + t.message)
                try {
                    context.startService(i)
                } catch (t2: Throwable) {
                    Log.w(TAG, "启动监听服务失败（不影响已弹出的弹窗）: " + t2.message)
                }
            }
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, BluetoothMonitorService::class.java))
            } catch (t: Throwable) {
                Log.w(TAG, "停止监听服务失败: " + t.message)
            }
        }

        @Volatile
        var running = false
            private set
    }

    private val main = Handler(Looper.getMainLooper())
    private var registered = false
    /** 仅用于断开时判定"当前弹窗是不是这台设备的"，弹窗去重统一在 BluetoothPopupTrigger */
    private var lastDeviceAddress: String? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action ?: return
            Log.d(TAG, "onReceive: " + action)
            when (action) {
                BluetoothDevice.ACTION_ACL_CONNECTED -> {
                    // 取不到设备对象绝不等于"不弹窗"：d 为 null 时交给反查兜底
                    val d = device(intent)
                    Log.i(
                        TAG,
                        "ACL_CONNECTED 解析设备=" + (d?.let { addressOf(it) } ?: "未取到（已记日志，改走已连音频设备反查）")
                    )
                    if (d == null || isAudioLike(d)) onConnected(d)
                }
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    val d = device(intent) ?: return
                    onDisconnected(d)
                }
                BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED -> {
                    val state = intent.getIntExtra(BluetoothA2dp.EXTRA_STATE, -1)
                    val d = device(intent)
                    Log.i(
                        TAG,
                        "A2DP 状态=" + state + " 解析设备=" + (d?.let { addressOf(it) } ?: "未取到（改为反查）")
                    )
                    if (state == BluetoothA2dp.STATE_CONNECTED) onConnected(d)
                }
                BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED -> {
                    val state = intent.getIntExtra(BluetoothHeadset.EXTRA_STATE, -1)
                    val d = device(intent)
                    Log.i(
                        TAG,
                        "HEADSET 状态=" + state + " 解析设备=" + (d?.let { addressOf(it) } ?: "未取到（改为反查）")
                    )
                    if (state == BluetoothHeadset.STATE_CONNECTED) onConnected(d)
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        running = true
        // 权限自检：把「未授权」与「已授权」两种失败原因分开写日志，避免用户"以为已经开启"的静默失败
        permissionGuardCheck()
        createChannel()
        startForegroundCompat()
        registerAll()
        // 电量广播（系统真实电量 + HFP 分体电量）统一由进程级桥处理：
        // 服务被回收、由静态接收器兜底弹窗时，桥依然在进程内生效
        BatteryUpdateBridge.ensureRegistered(this)
        KeepAliveScheduler.schedule(this)
        if (Prefs.foregroundGuard) GuardService.start(this)
        Log.i(TAG, "监听服务已启动")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForegroundCompat()
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        unregisterAll()
        val shouldRestart = Prefs.monitorEnabled
        super.onDestroy()
        if (shouldRestart) {
            KeepAliveScheduler.schedule(this, 3000L)
            if (Prefs.foregroundGuard) GuardService.start(this)
        }
    }

    // ------------------------------------------------------------------ 通知

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.app_name),
            NotificationManager.IMPORTANCE_MIN
        ).apply {
            setShowBadge(false)
            enableLights(false)
            enableVibration(false)
        }
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            else PendingIntent.FLAG_UPDATE_CURRENT
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(notificationText())
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pi)
            .setOngoing(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_MIN)

        builder.setSilent(true)
        if (Build.VERSION.SDK_INT >= 26) {
            builder.setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_SUMMARY)
        }
        return builder.build()
    }

    private fun startForegroundCompat() {
        val notification = buildNotification()
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(
                    NOTIF_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIF_ID, notification)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "startForeground 失败，降级: " + t.message)
            try {
                startForeground(NOTIF_ID, notification)
            } catch (t2: Throwable) {
                Log.e(TAG, "startForeground 彻底失败: " + t2.message)
            }
        }
    }

    // ------------------------------------------------------------------ 广播注册

    private fun registerAll() {
        if (registered) return
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
            // 电量两条广播不在此注册：统一走 BatteryUpdateBridge（进程级、服务死了也生效）。
            // 旧版这里把厂商事件 action 误写成 "...headset.profile.action..."，
            // 正确的 AOSP action 是 "...headset.action..."，已由桥使用。
        }
        try {
            ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            registered = true
        } catch (t: Throwable) {
            Log.e(TAG, "注册蓝牙广播失败: " + t.message)
        }
    }

    private fun unregisterAll() {
        if (!registered) return
        try {
            unregisterReceiver(receiver)
        } catch (t: Throwable) {
            Log.w(TAG, "反注册失败: " + t.message)
        }
        registered = false
    }

    // ------------------------------------------------------------------ 事件处理

    /**
     * 从广播里取出 BluetoothDevice。
     *
     * Android 13(API 33)+ 旧 API getParcelableExtra(String) 已被标记废弃，
     * Android 14(API 34)+ 必须走 getParcelableExtra(name, Class)；Android 12+
     * 未授予 BLUETOOTH_CONNECT 时任何一条路径都会抛 SecurityException。
     * 因此：优先新 API，失败回退旧 API（用户需要时按新 API 取），注释补充"新 API（Android14）"，
     * 再失败返回 null —— 由调用方用"已连音频设备反查"兜底，绝不让"取不到设备"成为不弹窗的理由。
     */
    private fun device(intent: Intent): BluetoothDevice? {
        // 路径一（Android 14 新 API）：getParcelableExtra(name, Class)
        if (Build.VERSION.SDK_INT >= 34) {
            try {
                return intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            } catch (t: Throwable) {
                Log.w(TAG, "新 API 取 EXTRA_DEVICE 失败: " + t::class.java.simpleName + " " + t.message)
            }
        }
        // 路径二（兼容旧系统）
        try {
            @Suppress("DEPRECATION")
            return intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        } catch (t: Throwable) {
            Log.e(
                TAG,
                "EXTRA_DEVICE 提取失败（可观测日志）: " + t::class.java.simpleName + " " + t.message +
                    " → 将改为遍历 bondedDevices + 已连接 A2DP/HEADSET 反推设备"
            )
        }
        return null
    }

    /** Android 12+ 读 device.address 需要 BLUETOOTH_CONNECT；读不到也绝不能因此把弹窗丢掉 */
    private fun addressOf(device: BluetoothDevice): String? = try {
        device.address
    } catch (t: Throwable) {
        Log.w(TAG, "读取蓝牙地址失败: " + t.message)
        null
    }

    private fun onConnected(device: BluetoothDevice?) {
        if (!Prefs.autoPopup) return

        // 兜底核心：广播里取不到设备对象时，遍历已配对设备 + 已连接的 A2DP/HEADSET 状态反推音频设备。
        // 任何情况下"取不到设备对象"都不允许成为不弹窗的理由。
        val target = device ?: BluetoothPopupTrigger.resolveConnectedAudioDevice(this)
        if (target == null) {
            Log.w(TAG, "广播未取到设备且未反查到已连接的音频设备，本次无法弹窗")
            return
        }

        // 记录本次连接的地址，供断开时判定是否要收起弹窗
        lastDeviceAddress = addressOf(target) ?: FALLBACK_ADDRESS

        // 【闪两下修复】不再走服务自己那套弹窗逻辑与去重。
        // 同一次连接会同时触发 ACL / A2DP / HEADSET 三条广播，且静态接收器
        // （BluetoothEventReceiver）与服务的动态接收器都会收到 —— 之前两套
        // 去重计数器互不相认，于是一次连接弹出两次、入场动画播两遍，看起来就是"闪两下"。
        // 现在统一由 BluetoothPopupTrigger 单一出口 + 单一去重处理。
        BluetoothPopupTrigger.showConnectedPopup(this, device)
    }

    private fun onDisconnected(device: BluetoothDevice) {
        val address = addressOf(device) ?: FALLBACK_ADDRESS
        BatteryRepository.remove(address)
        if (address.equals(lastDeviceAddress, true)) {
            PopupOverlayManager.dismiss()
            lastDeviceAddress = null
        }
    }

    // 电量补偿刷新已统一由 BluetoothPopupTrigger.scheduleBatteryRefresh 负责
    // （弹窗后 0.6/1.5/3/5/8 秒刷新 + 3 秒时 BLE GATT 兜底），服务不再单独维护一套。

    // ------------------------------------------------------------------ 设备判定

    private fun isAudioLike(device: BluetoothDevice): Boolean {
        val cls = try {
            device.bluetoothClass
        } catch (t: Throwable) {
            null
        }
        if (cls != null) {
            if (cls.majorDeviceClass == BluetoothClass.Device.Major.AUDIO_VIDEO) return true
            when (cls.deviceClass) {
                BluetoothClass.Device.AUDIO_VIDEO_WEARABLE_HEADSET,
                BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE,
                BluetoothClass.Device.AUDIO_VIDEO_HEADPHONES,
                BluetoothClass.Device.AUDIO_VIDEO_LOUDSPEAKER,
                BluetoothClass.Device.AUDIO_VIDEO_PORTABLE_AUDIO,
                BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO -> return true
            }
        }
        val n = try {
            BatteryRepository.safeName(device).lowercase()
        } catch (t: Throwable) {
            ""
        }
        if (KEYWORDS.any { n.contains(it) }) return true
        // 放宽判定：类信息拿不到（未授权 / 机型差异）或名称不含关键字时，不再判为"非音频设备"。
        // 宁可多弹一次（用户可在首页关闭自动弹窗），也不能因为判定过严而该弹不弹。
        Log.i(TAG, "设备类与名称均未命中音频特征，仍按音频设备处理: " + n)
        return true
    }

    /** 服务启动时的权限自检：把「未授权」与「已授权」两种失败原因分开写日志，便于用户定位 */
    private fun permissionGuardCheck() {
        val missing = PermissionGuard.missingLabels(this)
        if (missing.isEmpty()) {
            Log.i(TAG, "权限自检通过（已授权）：悬浮窗 / 通知 / 蓝牙连接均已就绪")
        } else {
            Log.e(
                TAG,
                "权限自检未通过（未授权）：缺少 " + missing.joinToString("、") + "，未授权时连接耳机不会弹窗"
            )
        }
    }

    /** 通知文案：未授权时把缺失项直接写进通知，避免用户"以为已经开启" */
    private fun notificationText(): String {
        val missing = PermissionGuard.missingLabels(this)
        return if (missing.isEmpty()) {
            getString(R.string.notif_text)
        } else {
            getString(R.string.notif_text_perm_missing, missing.joinToString("、"))
        }
    }

    private val KEYWORDS = listOf(
        "headphone", "headset", "earbud", "earbuds", "airpod", "buds", "freebuds",
        "wh-", "wf-", "powerbeats", "soundcore", "enco", "耳机", "beats", "jbl"
    )
}
