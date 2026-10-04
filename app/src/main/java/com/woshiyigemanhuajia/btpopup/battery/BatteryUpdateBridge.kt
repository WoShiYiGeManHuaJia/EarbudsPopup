package com.woshiyigemanhuajia.btpopup.battery

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.woshiyigemanhuajia.btpopup.overlay.PopupOverlayManager

/**
 * 进程级「真实电量」广播桥。
 *
 * 为什么需要它：
 *  1. 监听服务（BluetoothMonitorService）在 Android 12+ 后台场景下大概率拉不起来，
 *     只靠服务里的动态接收器，进程被回收后再连接耳机就永远收不到电量广播；
 *  2. BATTERY_LEVEL_CHANGED / VENDOR_SPECIFIC_HEADSET_EVENT 不在隐式广播豁免名单里，
 *     静态（Manifest）接收器在多数 ROM 上收不到；
 *  3. 但 ACL_CONNECTED 能把进程唤醒 —— 进程活着的这段时间里，把这两个电量广播
 *     用动态接收器挂在 applicationContext 上，就能稳定收到连接后几秒内的真实电量上报。
 *
 * 【厂商事件的 category 陷阱】
 * 厂商电量事件（AirPods 的 IPHONEACCEV、小米/Redmi TWS 的电量帧等）的广播 intent
 * 自带一个 category："android.bluetooth.headset.company_id.<厂商ID>"。
 * Android 的广播匹配要求：**intent 携带的所有 category 必须都出现在 IntentFilter 里**，
 * 否则根本派发不到。所以必须按厂商 ID 注册多条 filter —— 少一条就少一类耳机
 * （早前版本既写错了 action 又没加 category，分体电量永远为空）。
 *
 * 因此：弹窗触发链路的每个入口（静态接收器 / 触发器 / 监听服务 / Application）
 * 都调用一次 [ensureRegistered]，进程级只注册一次，收到广播后统一更新缓存并刷新弹窗。
 */
object BatteryUpdateBridge {

    private const val TAG = "BtBatteryBridge"

    /** 系统真实电量广播（隐藏 API 字符串，AOSP 常量 BluetoothDevice.ACTION_BATTERY_LEVEL_CHANGED） */
    const val ACTION_BATTERY_LEVEL_CHANGED = "android.bluetooth.device.action.BATTERY_LEVEL_CHANGED"

    /** 电量值 extra（AOSP 常量 BluetoothDevice.EXTRA_BATTERY_LEVEL），0..100，-1 表示未知 */
    const val EXTRA_BATTERY_LEVEL = "android.bluetooth.device.extra.BATTERY_LEVEL"

    /** HFP 厂商事件广播（AOSP 常量 BluetoothHeadset.ACTION_VENDOR_SPECIFIC_HEADSET_EVENT） */
    const val ACTION_VENDOR_EVENT = BluetoothHeadset.ACTION_VENDOR_SPECIFIC_HEADSET_EVENT

    /**
     * 需要覆盖的蓝牙公司 ID（Bluetooth Assigned Numbers）。
     *  76 Apple（AirPods 及绝大多数兼容芯片）、911 Xiaomi（小米 / Redmi TWS），
     * 其余为常见音频厂商，多注册几条没有副作用，只为不漏。
     */
    private val COMPANY_IDS = intArrayOf(
        76,   // Apple
        911,  // Xiaomi (0x038F) —— Redmi Buds 6 等小米/Redmi TWS 的分体电量
        117,  // Samsung
        224,  // Google
        157,  // Sony
        247,  // Huawei
        158,  // Bose
        61,   // Plantronics
        6     // Microsoft
    )

    @Volatile
    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            try {
                handle(context, intent)
            } catch (t: Throwable) {
                // 广播回调里绝不外抛异常，避免系统连带回收监听
                Log.e(TAG, "电量广播处理失败（已捕获）: " + intent.action + " " + t.message)
            }
        }
    }

    /** 幂等注册：进程生命周期内只注册一次，任何入口调用都安全 */
    //
    // 【修复「后台挂几小时后，开盖要 8~9 秒才弹窗」】
    //
    // 原实现是**同步**注册 1 条系统电量广播 + 9 条厂商电量广播，共 10 次
    // registerReceiver —— 每次都是跨进程 Binder 调用到 AMS。
    // 而它就在 App.onCreate() 里执行：进程被蓝牙广播冷启动唤醒时，
    // Application.onCreate 会**先于** BroadcastReceiver.onReceive 跑完，
    // 于是这 10 次 Binder 调用把弹窗活活堵在后面。
    // 冷启动时 AMS 本身繁忙（正在派发蓝牙连接风暴），单次调用可达数百毫秒，
    // 10 次就是几秒 —— 这正是"开盖后要等 8~9 秒"的主要来源。
    //
    // 改法：注册动作整体搬到后台线程，主线程立即返回。
    // 弹窗不依赖这些广播也能显示（电量由后续异步补偿刷新补齐），
    // 因此"先弹窗、再补电量监听"是安全的顺序。
    //
    private val registerExec: java.util.concurrent.ExecutorService =
        java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "batt-bridge-reg").apply { isDaemon = true }
        }

    @Volatile
    private var registerStarted = false

    fun ensureRegistered(context: Context) {
        if (registerStarted) return
        synchronized(this) {
            if (registerStarted) return
            registerStarted = true
        }
        val app = context.applicationContext
        registerExec.execute {
            try {
                // 系统真实电量广播
                ContextCompat.registerReceiver(
                    app, receiver,
                    IntentFilter(ACTION_BATTERY_LEVEL_CHANGED),
                    ContextCompat.RECEIVER_EXPORTED
                )
                // 厂商电量事件：每个公司 ID 一条 filter（intent 的 category 必须与 filter 完全匹配）
                for (id in COMPANY_IDS) {
                    val filter = IntentFilter(ACTION_VENDOR_EVENT).apply {
                        addCategory(BluetoothHeadset.VENDOR_SPECIFIC_HEADSET_EVENT_COMPANY_ID_CATEGORY + "." + id)
                    }
                    ContextCompat.registerReceiver(app, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
                }
                registered = true
                Log.i(TAG, "进程级电量广播监听已注册（厂商 category " + COMPANY_IDS.size + " 条）")
            } catch (t: Throwable) {
                Log.e(TAG, "注册电量广播失败: " + t.message)
                // 注册失败允许重试：进程还活着，下次事件再试一次
                synchronized(this) { registerStarted = false }
            }
        }
    }

    /** 统一处理两条电量广播；静态接收器（Manifest 路径）也复用这套逻辑 */
    fun handle(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_BATTERY_LEVEL_CHANGED -> onBatteryLevelChanged(intent)
            ACTION_VENDOR_EVENT -> onVendorEvent(intent)
        }
    }

    /**
     * 系统真实电量：广播里直接带着 0..100 的值（EXTRA_BATTERY_LEVEL），
     * 这才是第三方电量软件使用的数据源；反射 getBatteryLevel() 只作兜底
     * （Android 13+ 隐藏 API 封锁后反射基本必败）。
     */
    private fun onBatteryLevelChanged(intent: Intent) {
        val device = deviceFrom(intent) ?: return
        var level = intent.getIntExtra(EXTRA_BATTERY_LEVEL, -1)
        if (level !in 0..100) {
            level = BatteryRepository.readSystemLevel(device)
        }
        if (level !in 0..100) return
        val name = BatteryRepository.safeName(device)
        val address = BatteryRepository.addressOf(device) ?: return
        val info = BatteryRepository.update(address, name) { cur ->
            cur.copy(
                overall = level,
                // 小米协议的分体电量比系统整体值更精确，有分体时不覆盖 left/right/case
                source = if (cur.hasSplit) cur.source else "系统蓝牙服务",
                updatedAt = System.currentTimeMillis()
            )
        }
        Log.i(TAG, "系统电量广播: " + name + " = " + level + "%")
        PopupOverlayManager.update(info)
    }

    /** HFP 厂商事件：TWS 左耳 / 右耳 / 充电仓分体电量的来源（小米协议 / 苹果协议等） */
    private fun onVendorEvent(intent: Intent) {
        val parsed = HfpBatteryParser.onVendorEvent(intent) ?: return
        Log.i(
            TAG,
            "厂商电量上报: " + parsed.second.name +
                " L=" + parsed.second.left + " R=" + parsed.second.right +
                " C=" + parsed.second.case + " overall=" + parsed.second.overall
        )
        PopupOverlayManager.update(parsed.second)
    }

    private fun deviceFrom(intent: Intent): BluetoothDevice? {
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                val d = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                if (d != null) return d
            } catch (t: Throwable) {
                Log.w(TAG, "新 API 取 EXTRA_DEVICE 失败: " + t.message)
            }
        }
        return try {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        } catch (t: Throwable) {
            Log.w(TAG, "兼容 API 取 EXTRA_DEVICE 失败: " + t.message)
            null
        }
    }
}
