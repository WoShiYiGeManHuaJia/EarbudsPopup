package com.woshiyigemanhuajia.btpopup.battery

import android.bluetooth.BluetoothDevice
import android.content.Context
import android.os.Build
import android.util.Log
import com.woshiyigemanhuajia.btpopup.util.Prefs
import java.util.concurrent.ConcurrentHashMap

/**
 * 耳机电量的统一入口。
 *
 * 数据来源优先级（全部为系统真实数据，不做任何估算）：
 *  1. Android 14+ 官方 BluetoothDevice.getBatteryLevel()
 *  2. 反射调用隐藏实现 getBatteryLevel()（老版本系统）
 *  3. 系统隐藏广播 BATTERY_LEVEL_CHANGED 缓存值
 *  4. HFP 厂商事件 AT+IPHONEACCEV / +XEVENT 解析值（可拿到部分耳机的分体电量）
 *  5. BLE GATT Battery Service 读取值
 */
object BatteryRepository {

    private const val TAG = "BtBattery"

    private val cache = ConcurrentHashMap<String, BatteryInfo>()

    fun key(address: String?) = (address ?: "").uppercase()

    fun get(address: String?): BatteryInfo? = address?.let { cache[key(it)] }

    fun all(): List<BatteryInfo> = cache.values.toList()

    fun put(info: BatteryInfo) {
        cache[key(info.address)] = info
    }

    fun update(address: String, name: String?, block: (BatteryInfo) -> BatteryInfo): BatteryInfo {
        val k = key(address)
        val base = cache[k] ?: BatteryInfo(name ?: BatteryInfo.UNKNOWN_NAME, address)
        // 【修复】用户在系统蓝牙设置里给耳机改名后，弹窗仍显示旧名。
        // 旧逻辑是「只有缓存名为空时才写入新名」，于是第一次拿到的名字被永久锁死，
        // 之后再改名永远显示旧的。改成：只要拿到新名字就覆盖。
        // 自定义名优先级最高：用户在本 App 里填过名就以那个为准，覆盖系统名。
        val custom = Prefs.customName(address)
        val effective = custom ?: name
        val withName =
            if (!effective.isNullOrBlank() && effective != base.name) base.copy(name = effective)
            else base
        val next = block(withName)
        cache[k] = next
        return next
    }

    fun remove(address: String?) {
        if (address != null) cache.remove(key(address))
    }

    /** 主动拉取一次最新值（系统 API + 缓存合并） */
    fun query(context: Context, device: BluetoothDevice, fallbackName: String?): BatteryInfo {
        val address = addressOf(device) ?: "00:00:00:00:00:00"
        val sysName = fallbackName?.takeIf { it.isNotBlank() } ?: safeName(device)
        // 自定义名优先：用户在本 App 里填过就以那个为准
        val name = Prefs.customName(address) ?: sysName
        var info = cache[key(address)] ?: BatteryInfo(name, address)
        // 同上：名字以本次读到的为准，改了名就必须跟着变
        if (name.isNotBlank() && name != info.name) info = info.copy(name = name)

        val sys = readSystemLevel(device)
        if (sys in 0..100 && sys != info.overall) {
            info = info.copy(overall = sys, updatedAt = System.currentTimeMillis())
        }
        if (sys in 0..100 && info.source.isBlank()) {
            info = info.copy(source = "系统蓝牙服务")
        }
        cache[key(address)] = info
        return info
    }

    /**
     * 系统真实电量，读不到返回 -1。
     * BluetoothDevice#getBatteryLevel() 在不同版本上均为隐藏 API，
     * 只能反射调用；调用失败时由广播 / GATT / HFP 等通路兜底。
     */
    fun readSystemLevel(device: BluetoothDevice): Int {
        try {
            val m = BluetoothDevice::class.java.getDeclaredMethod("getBatteryLevel")
            m.isAccessible = true
            val v = (m.invoke(device) as? Int) ?: -1
            if (v in 0..100) return v
        } catch (t: Throwable) {
            Log.w(TAG, "反射 getBatteryLevel 失败: " + t.message)
        }
        return -1
    }

    /** 读设备地址（Android 12+ 需要 BLUETOOTH_CONNECT）；任何异常都返回 null，绝不外抛 */
    fun addressOf(d: BluetoothDevice): String? = try {
        d.address
    } catch (t: Throwable) {
        null
    }

    /**
     * 设备显示名。
     *
     * 优先级：**用户在 App 里设置的自定义名 > 系统蓝牙名 > MAC 地址**。
     *
     * 系统蓝牙设置里改名后 getName() 未必同步给第三方 App
     * （部分 ROM 需重启蓝牙 / 重新配对，有的干脆不同步），
     * 所以自定义名必须排在系统名前面 —— 用户填了就以用户填的为准。
     */
    fun safeName(d: BluetoothDevice): String = try {
        val addr = addressOf(d)
        Prefs.customName(addr)
            ?: (d.name ?: "").takeIf { it.isNotBlank() }
            ?: addr
            ?: BatteryInfo.UNKNOWN_NAME
    } catch (t: Throwable) {
        // 兜底分支也必须安全：旧写法在 catch 里再读一次 address，权限缺失时会二次抛异常，
        // 异常逃出蓝牙广播的 onReceive 后系统会连带回收监听服务 —— 这正是"怎么都不弹窗"的根因之一
        addressOf(d) ?: BatteryInfo.UNKNOWN_NAME
    }
}
