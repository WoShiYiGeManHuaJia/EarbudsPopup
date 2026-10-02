package com.woshiyigemanhuajia.btpopup.battery

import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.util.Log

/**
 * 解析 HFP 厂商私有事件中的电量上报。
 *
 * ── 小米 / Redmi TWS（Buds 3~7 全系，Redmi Buds 6 实测兼容）──
 * 通过公司 ID 0x038F(911) 的厂商事件上报，事件参数为 7 个整数：
 *   args[3] = 左耳电量字节, args[4] = 右耳电量字节, args[5] = 充电仓电量字节
 * 每个字节：低 7 位 = 电量百分比（1..100 有效），最高位(bit7) = 是否充电中。
 * 协议实现参考开源项目 packages_apps_XiaomiTWS（Apache 2.0）。
 * 注意：该事件广播自带 category "android.bluetooth.headset.company_id.911"，
 * 接收器 IntentFilter 不加这个 category 就永远收不到（分体电量的头号隐形坑）。
 *
 * ── AirPods 及兼容芯片（络达 / 恒玄 / 杰理 / 瑞昱等）──
 * 通过 AT+IPHONEACCEV 上报：AT+IPHONEACCEV=<count>,<key1>,<val1>,...
 * 电量 key 的两种主流约定都会兼容：
 *   - 位掩码约定（苹果配件协议）：key=1 左耳，key=2 右耳，key=4 充电仓
 *   - 顺序约定（部分国产固件）：  key=1 左耳，key=2 右耳，key=3 充电仓
 * 值为电量档位 0..9，换算为 (val+1)*10 %；部分固件直接上报 0..100，直读。
 * 只出现 key=1 一个电量项时，是老式单电池耳机（如 Beats 头戴），按整体电量处理。
 *
 * ── 其他厂商（XEVENT / BATT 等自定义格式）──
 * 宽松解析，尽量把能识别的电量抠出来。
 */
object HfpBatteryParser {

    private const val TAG = "HfpBattery"

    private const val EXTRA_CMD = "android.bluetooth.headset.extra.VENDOR_SPECIFIC_HEADSET_EVENT_CMD"
    private const val EXTRA_ARGS = "android.bluetooth.headset.extra.VENDOR_SPECIFIC_HEADSET_EVENT_ARGS"

    /** AOSP 常量 BluetoothHeadset.VENDOR_SPECIFIC_HEADSET_EVENT_COMPANY_ID_CATEGORY */
    const val COMPANY_ID_CATEGORY = "android.bluetooth.headset.company_id"

    /** 小米公司 ID（0x038F）：小米 / Redmi TWS 电量事件的 category 后缀 */
    const val COMPANY_ID_XIAOMI = 911

    /** 事件命令名 -> 参数 */
    fun onVendorEvent(intent: Intent): Pair<BluetoothDevice, BatteryInfo>? {
        val device: BluetoothDevice = try {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE) as? BluetoothDevice
        } catch (t: Throwable) {
            null
        } ?: return null

        val cmd = intent.getStringExtra(EXTRA_CMD) ?: ""
        val args: Array<Any>? = try {
            @Suppress("DEPRECATION")
            intent.getSerializableExtra(EXTRA_ARGS) as? Array<Any>
        } catch (t: Throwable) {
            null
        }

        val cmdUpper = cmd.uppercase()
        Log.d(TAG, "vendor event cmd=" + cmd + " args=" + (args?.joinToString() ?: "null"))

        val xiaomiCategory = try {
            intent.hasCategory("$COMPANY_ID_CATEGORY.$COMPANY_ID_XIAOMI")
        } catch (t: Throwable) {
            false
        }

        return when {
            // 小米 / Redmi TWS：7 整数电量帧（category 911 或命令名 +XIAOMI）
            (xiaomiCategory || cmdUpper.contains("XIAOMI")) ->
                parseXiaomiBattery(device, args) ?: parseGeneric(device, args)
            cmdUpper.contains("IPHONEACCEV") -> parseIphoneAccEv(device, args)
            cmdUpper.contains("XEVENT") -> parseGeneric(device, args)
            cmdUpper.contains("BATT") -> parseGeneric(device, args)
            else -> null
        }
    }

    private fun toInt(o: Any?): Int = when (o) {
        is Int -> o
        is Long -> o.toInt()
        is Short -> o.toInt()
        is String -> o.toIntOrNull() ?: -1
        else -> -1
    }

    /** 档位值转百分比：0..9 档 → (v+1)*10；已是 10..100 的直读；其余无效 */
    private fun pct(v: Int): Int = when {
        v in 0..9 -> (v + 1) * 10
        v in 10..100 -> v
        else -> -1
    }

    /**
     * 小米 / Redmi TWS 电量帧：args 为 7 个整数，[3]/[4]/[5] 分别是左 / 右 / 仓。
     * 每个字节低 7 位是电量（1..100 有效），最高位是充电标志。
     */
    private fun parseXiaomiBattery(device: BluetoothDevice, args: Array<Any>?): Pair<BluetoothDevice, BatteryInfo>? {
        if (args == null || args.size != 7) return null
        val nums = IntArray(7) { toInt(args[it]) }
        if (nums.any { it < 0 || it > 0xFFFF }) return null

        fun decode(raw: Int): Pair<Int, Boolean>? {
            val b = raw and 0xFF
            val level = b and 0x7F
            val charging = (b and 0x80) != 0
            return if (level in 1..100) level to charging else null
        }

        val left = decode(nums[3])
        val right = decode(nums[4])
        val case = decode(nums[5])
        if (left == null && right == null && case == null) return null

        val name = BatteryRepository.safeName(device)
        val address = BatteryRepository.addressOf(device) ?: return null
        val info = BatteryRepository.update(address, name) { cur ->
            cur.copy(
                left = left?.first ?: cur.left,
                right = right?.first ?: cur.right,
                case = case?.first ?: cur.case,
                charging = listOfNotNull(left, right, case).any { it.second },
                source = "小米协议上报",
                updatedAt = System.currentTimeMillis()
            )
        }
        return device to info
    }

    private fun parseIphoneAccEv(device: BluetoothDevice, args: Array<Any>?): Pair<BluetoothDevice, BatteryInfo>? {
        if (args == null || args.size < 3) return null
        val count = toInt(args[0])
        if (count <= 0) return null

        var left = -1
        var right = -1
        var case = -1
        var splitKeys = 0
        var charging = false
        var i = 1
        while (i + 1 < args.size) {
            val k = toInt(args[i])
            val v = toInt(args[i + 1])
            val p = pct(v)
            when (k) {
                1 -> if (p >= 0) { left = p; splitKeys++ }
                2 -> if (p >= 0) { right = p; splitKeys++ }
                // 位掩码约定用 4、顺序约定用 3 表示充电仓，两者都接受
                3, 4 -> if (p >= 0) { case = p; splitKeys++ }
                //
                // 充电状态位（值 1 = 对应部件充电中）。
                // 原实现把 16 / 32 也算进来，但那两个更像是位掩码而非布尔标志，
                // 把任意 v==1 当成"充电中"正是"永远显示充电中"的主要来源 —— 已去掉。
                //
                5, 6, 7, 8 -> if (v == 1) charging = true
            }
            i += 2
        }

        val name = BatteryRepository.safeName(device)
        val address = BatteryRepository.addressOf(device) ?: return null
        val now = System.currentTimeMillis()

        return when {
            // 出现两个及以上电量项 → 真分体电量（左 / 右 / 仓）
            splitKeys >= 2 -> {
                val info = BatteryRepository.update(address, name) { cur ->
                    cur.copy(
                        left = left,
                        right = right,
                        case = case,
                        charging = charging,
                        source = "HFP 厂商上报",
                        updatedAt = now
                    )
                }
                device to info
            }
            // 只有 key=1 一项 → 老式单电池耳机，按整体电量处理，不误显成"左耳"
            left >= 0 -> {
                val info = BatteryRepository.update(address, name) { cur ->
                    cur.copy(
                        overall = left,
                        charging = charging,
                        source = if (cur.hasSplit) cur.source else "HFP 厂商上报",
                        updatedAt = now
                    )
                }
                device to info
            }
            else -> null
        }
    }

    /**
     * 宽松解析类似 "+XEVENT=BATTERY,1,85" 这种结构，
     * 不同厂商格式差异较大，只提取第一个 0..100 的数值。
     */
    private fun parseGeneric(device: BluetoothDevice, args: Array<Any>?): Pair<BluetoothDevice, BatteryInfo>? {
        if (args == null || args.isEmpty()) return null
        val nums = args.map { toInt(it) }
        val level = nums.firstOrNull { it in 0..100 } ?: return null
        val name = BatteryRepository.safeName(device)
        val address = BatteryRepository.addressOf(device) ?: return null
        val info = BatteryRepository.update(address, name) { cur ->
            cur.copy(overall = level, source = "HFP 厂商上报", updatedAt = System.currentTimeMillis())
        }
        return device to info
    }
}
