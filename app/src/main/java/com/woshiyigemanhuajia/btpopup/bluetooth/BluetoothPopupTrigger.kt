package com.woshiyigemanhuajia.btpopup.bluetooth

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.woshiyigemanhuajia.btpopup.battery.BatteryInfo
import com.woshiyigemanhuajia.btpopup.battery.BatteryRepository
import com.woshiyigemanhuajia.btpopup.battery.BatteryUpdateBridge
import com.woshiyigemanhuajia.btpopup.battery.GattBatteryReader
import com.woshiyigemanhuajia.btpopup.overlay.PopupOverlayManager
import com.woshiyigemanhuajia.btpopup.util.PermissionGuard
import com.woshiyigemanhuajia.btpopup.util.Prefs

/**
 * 蓝牙连接事件 → 弹窗的统一通道（服务内动态接收器与静态兜底接收器共用）。
 *
 * 三条确定性缺陷的防线都收敛在这里：
 *  1) 「取不到设备对象就静默 return」——deviceFrom() 先走 Android 13/14 的
 *     getParcelableExtra(name, Class) 新 API，再走旧 API，全失败时
 *     resolveConnectedAudioDevice() 用已配对设备 + A2DP/HEADSET 连接状态反查，
 *     任何情况都返回"可继续弹窗"的结果，并把失败原因写进日志。
 *  2) 「后台 startForegroundService 被拒 → 服务起不来 → 不弹窗」——这里直接
 *     addView 悬浮窗，完全不依赖前台服务是否可启动。
 *  3) 「权限静默失败」——未授权与已授权失败分别给出可读原因。
 */
object BluetoothPopupTrigger {

    private const val TAG = "BtTrigger"

    /** 读不到蓝牙地址时的占位 key：宁可少一次去重，也不能因为读地址失败而不弹窗 */
    const val FALLBACK_ADDRESS = "00:00:00:00:00:00"

    private const val DEDUP_WINDOW_MS = 3000L

    private val KEYWORDS = listOf(
        "headphone", "headset", "earbud", "earbuds", "airpod", "buds", "freebuds",
        "wh-", "wf-", "powerbeats", "soundcore", "enco", "耳机", "beats", "jbl", "tws"
    )

    @Volatile
    private var lastAddress: String? = null

    @Volatile
    private var lastTriggerAt = 0L

    // ---------------------------------------------------------------- 设备对象获取

    /**
     * 从广播 Intent 中取设备对象。
     *
     * Android 13(API 33)+ 走 getParcelableExtra(name, Class) 新 API（Android 14 上的推荐路径），
     * 返回值异常或为空时退回旧 API；仍然拿不到则返回 null，由调用方继续走"反查已连接音频设备"兜底。
     * 每一次失败都写日志，保证"为什么不弹窗"可观测。
     */
    fun deviceFrom(intent: Intent): BluetoothDevice? {
        val action = intent.action
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                val d = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                if (d != null) return d
                Log.w(TAG, "EXTRA_DEVICE 新 API 返回空，改用兼容 API 重试（action=$action）")
            } catch (t: Throwable) {
                Log.w(TAG, "EXTRA_DEVICE 新 API 取值失败: " + t.javaClass.simpleName + " " + t.message + "（action=$action）")
            }
        }
        try {
            @Suppress("DEPRECATION")
            val d = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
            if (d != null) return d
            Log.w(TAG, "EXTRA_DEVICE 兼容 API 返回空，转入已连接音频设备反查（action=$action）")
        } catch (t: Throwable) {
            Log.w(TAG, "EXTRA_DEVICE 兼容 API 取值失败: " + t.javaClass.simpleName + " " + t.message + "（action=$action）")
        }
        return null
    }

    fun adapter(context: Context): BluetoothAdapter? = try {
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    } catch (t: Throwable) {
        Log.w(TAG, "获取 BluetoothAdapter 失败: " + t.message)
        null
    }

    /**
     * 兜底反查：遍历 BluetoothAdapter.getBondedDevices + 查询 A2DP / HEADSET 的
     * getProfileConnectionState 状态，反推出"刚连接的音频设备"。
     *
     * 场景：广播里读不到 EXTRA_DEVICE（缺 BLUETOOTH_CONNECT / ROM 差异），
     * 或服务启动晚于本次连接事件。拿不到清单时返回 null，绝不抛异常。
     */
    fun resolveConnectedAudioDevice(context: Context): BluetoothDevice? {
        val adapter = adapter(context) ?: run {
            Log.e(TAG, "反查失败：BluetoothAdapter 不可用（蓝牙未开启或未授权）")
            return null
        }

        val profileConnected = try {
            adapter.getProfileConnectionState(BluetoothProfile.A2DP) == BluetoothProfile.STATE_CONNECTED ||
                adapter.getProfileConnectionState(BluetoothProfile.HEADSET) == BluetoothProfile.STATE_CONNECTED
        } catch (t: Throwable) {
            Log.w(TAG, "查询 A2DP/HEADSET 连接状态失败: " + t.message)
            false
        }

        val bonded = try {
            adapter.bondedDevices?.toList().orEmpty()
        } catch (t: Throwable) {
            Log.w(TAG, "读取已配对设备失败（多半是缺少 BLUETOOTH_CONNECT）: " + t.message)
            emptyList()
        }
        if (bonded.isEmpty()) {
            Log.e(TAG, "反查失败：拿不到已配对设备列表，" + PermissionGuard.describe(context))
            return null
        }

        val audio = bonded.filter { isAudioLike(it) }
        val candidates = audio.ifEmpty { bonded }
        val picked = candidates.firstOrNull { isBondedConnected(it) } ?: candidates.first()
        Log.i(
            TAG,
            "反查已连接音频设备: " + safeName(picked) + "（A2DP/HEADSET 已连接=" + profileConnected +
                " 候选数=" + candidates.size + "）"
        )
        return picked
    }

    /** 隐藏 API isConnected()：部分 ROM 上它比 profile 状态更贴合"设备真的连着" */
    private fun isBondedConnected(device: BluetoothDevice): Boolean = try {
        val m = BluetoothDevice::class.java.getDeclaredMethod("isConnected")
        m.isAccessible = true
        (m.invoke(device) as? Boolean) == true
    } catch (t: Throwable) {
        false
    }

    /** Android 12+ 读 device.address 需要 BLUETOOTH_CONNECT；读不到也绝不能因此把弹窗丢掉 */
    fun addressOf(device: BluetoothDevice?): String? {
        if (device == null) return null
        return try {
            device.address
        } catch (t: Throwable) {
            Log.w(TAG, "读取蓝牙地址失败（可能缺少 BLUETOOTH_CONNECT）: " + t.message)
            null
        }
    }

    fun safeName(device: BluetoothDevice?): String {
        if (device == null) return ""
        return try {
            device.name ?: ""
        } catch (t: Throwable) {
            Log.w(TAG, "读取设备名失败（可能缺少 BLUETOOTH_CONNECT）: " + t.message)
            ""
        }
    }

    // ---------------------------------------------------------------- 音频设备判定

    /**
     * 是否音频类设备。
     *
     * 原则：宁可多弹一次，也绝不漏弹——"怎么都不弹窗"正是用户反馈的核心问题。
     * 因此只有在能明确判定为「非音频大类」（电脑 / 手机 / 外设 / 影像 / 网络）时才排除；
     * 类型读不到、名称也匹配不上时，一律按音频设备处理并记日志。
     */
    fun isAudioLike(device: BluetoothDevice?): Boolean {
        if (device == null) return true

        val cls = try {
            device.bluetoothClass
        } catch (t: Throwable) {
            Log.w(TAG, "读取设备类型失败（按音频设备处理以免漏弹窗）: " + t.message)
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
            when (cls.majorDeviceClass) {
                BluetoothClass.Device.Major.COMPUTER,
                BluetoothClass.Device.Major.PHONE,
                BluetoothClass.Device.Major.PERIPHERAL,
                BluetoothClass.Device.Major.IMAGING,
                BluetoothClass.Device.Major.NETWORKING -> return false
            }
        }

        val n = safeName(device).lowercase()
        if (KEYWORDS.any { n.contains(it) }) return true
        Log.w(TAG, "设备类型与名称均无法判定，按音频设备处理以免漏弹窗: " + n)
        return true
    }

    // ---------------------------------------------------------------- 弹窗

    /**
     * 连接事件的统一出口：无论 device 是否为空、进程是否在前台、前台服务能否被拉起，
     * 都尽力把弹窗显示出来。
     *
     * @return true 表示已提交显示（或已提示缺少权限），false 表示本次按策略跳过。
     */
    fun showConnectedPopup(context: Context, device: BluetoothDevice?): Boolean {
        Prefs.init(context)
        if (!Prefs.autoPopup) {
            Log.i(TAG, "自动弹窗已被用户关闭，跳过")
            return false
        }

        // 进程已被蓝牙广播唤醒：立刻挂上进程级电量监听，
        // 连接后几秒内的「系统真实电量 / 厂商分体电量」广播都能收到并刷新弹窗。
        // 不依赖前台服务能否拉起（Android 12+ 后台 startForegroundService 大概率被拒）。
        BatteryUpdateBridge.ensureRegistered(context)

        val resolved = device ?: resolveConnectedAudioDevice(context)
        if (device != null && !isAudioLike(device)) {
            Log.i(TAG, "判定为非音频设备，不弹窗: " + safeName(device))
            return false
        }

        val address = addressOf(resolved) ?: FALLBACK_ADDRESS
        val now = System.currentTimeMillis()
        if (address == lastAddress && now - lastTriggerAt < DEDUP_WINDOW_MS) {
            Log.d(TAG, "短时间重复事件，忽略: " + address)
            return false
        }
        lastAddress = address
        lastTriggerAt = now

        // 名称 / 电量只是锦上添花，任何读取失败都不允许挡住弹窗本身
        var name = ""
        var info: BatteryInfo? = null
        if (resolved != null) {
            try {
                name = BatteryRepository.safeName(resolved)
                info = BatteryRepository.query(context, resolved, name)
            } catch (t: Throwable) {
                Log.e(TAG, "读取耳机信息失败（不影响弹窗）: " + t.message)
            }
        } else {
            Log.w(TAG, "设备对象与反查均无结果，仍按占位信息弹窗（取不到设备不作为不弹窗的理由）")
        }
        val payload = info ?: BatteryRepository.update(address, name) { it }

        val status = PermissionGuard.check(context)
        if (!status.overlay) {
            Log.e(TAG, "弹窗失败原因=未授权：缺少悬浮窗权限（SYSTEM_ALERT_WINDOW），请在首页点「一键授权」")
            return false
        }
        Log.i(
            TAG,
            "弹窗失败原因=无：已授权，直接添加悬浮窗（不依赖前台服务）: " + name.ifBlank { address }
        )
        val shown = PopupOverlayManager.show(context, payload, Prefs.imageUri)
        if (shown && resolved != null) {
            scheduleBatteryRefresh(context.applicationContext, resolved, address, name)
        }
        return shown
    }

    // ---------------------------------------------------------------- 连接瞬间电量补偿

    private val main = Handler(Looper.getMainLooper())
    private val refreshTasks = mutableListOf<Runnable>()

    /**
     * 连接瞬间系统往往还没把电量上报上来（弹窗第一帧读到的是空），
     * 这里做几次异步补偿刷新：读系统缓存值并刷新弹窗；
     * 中途到达的 BATTERY_LEVEL_CHANGED / 厂商事件由 BatteryUpdateBridge 直接刷新，
     * 与本补偿互不冲突（写同一缓存，后到的真实值覆盖）。
     * 用户只要求"连接那一刻显示真实电量"，8 秒内补齐即达标，无需常驻轮询。
     */
    private fun scheduleBatteryRefresh(context: Context, device: BluetoothDevice, address: String, name: String) {
        synchronized(refreshTasks) {
            refreshTasks.forEach { main.removeCallbacks(it) }
            refreshTasks.clear()
            val delays = longArrayOf(600L, 1500L, 3000L, 5000L, 8000L)
            delays.forEach { delay ->
                val task = Runnable {
                    try {
                        val info = BatteryRepository.query(context, device, name)
                        PopupOverlayManager.update(info)
                        if (delay == 3000L) {
                            // BLE 侧暴露 Battery Service 的耳机，GATT 再兜底读一次
                            GattBatteryReader.read(context, device, address) { level ->
                                val updated = BatteryRepository.get(address) ?: return@read
                                main.post { PopupOverlayManager.update(updated) }
                            }
                        }
                    } catch (t: Throwable) {
                        Log.w(TAG, "电量补偿刷新失败（已捕获）: " + t.message)
                    }
                }
                refreshTasks += task
                main.postDelayed(task, delay)
            }
        }
    }
}
