package com.woshiyigemanhuajia.btpopup.receiver

import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.woshiyigemanhuajia.btpopup.battery.BatteryUpdateBridge
import com.woshiyigemanhuajia.btpopup.bluetooth.BluetoothPopupTrigger
import com.woshiyigemanhuajia.btpopup.service.BluetoothMonitorService
import com.woshiyigemanhuajia.btpopup.util.Prefs

/**
 * 静态注册的兜底接收器：进程被杀（监听服务不在前台）时，蓝牙连接事件仍要能弹出弹窗。
 *
 * 关键改动：弹窗不再依赖"把前台服务拉起来"。
 * Android 12+ 从后台 startForegroundService 会抛 ForegroundServiceStartNotAllowedException
 * （ACL_CONNECTED 不在系统豁免名单），原先这条路径失败就等于永远不弹窗。
 * 现在改为在这里直接用 applicationContext 添加悬浮窗（addView 不受后台启动服务限制），
 * 启动服务降级为"附带尝试"，失败不影响弹窗。
 * 同时把监听动作扩展到 A2DP / HEADSET 的 CONNECTION_STATE_CHANGED，弥补部分机型不发 ACL_CONNECTED。
 *
 * 电量：连接广播把进程唤醒后，立即注册进程级电量桥（BatteryUpdateBridge），
 * 连接后几秒内的「系统真实电量 + 厂商分体电量」广播由桥刷新到弹窗 ——
 * 这是"连接瞬间显示真实电量"的关键保障，不依赖前台服务是否起得来。
 */
class BluetoothEventReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        try {
            Prefs.init(context)
            val app = context.applicationContext

            // 进程已被唤醒：先挂上进程级电量监听（幂等，只注册一次）
            BatteryUpdateBridge.ensureRegistered(app)

            // 电量广播直接交给桥处理（少数 ROM 会把这两条广播派发给静态接收器）
            if (action == BatteryUpdateBridge.ACTION_BATTERY_LEVEL_CHANGED ||
                action == BatteryUpdateBridge.ACTION_VENDOR_EVENT
            ) {
                BatteryUpdateBridge.handle(app, intent)
                return
            }

            // 断开事件优先处理：无论弹窗开关状态如何，断开都必须收掉弹窗
            if (isDisconnectAction(intent)) {
                BluetoothPopupTrigger.handleDisconnected(app)
                return
            }

            if (!isConnectAction(intent)) return
            if (!Prefs.autoPopup) return

            // 1) 直接弹窗：不依赖前台服务能否启动（后台广播里 startForegroundService 很可能被拒）
            val device = BluetoothPopupTrigger.deviceFrom(intent)
            BluetoothPopupTrigger.showConnectedPopup(app, device)

            // 2) 附带尝试把常驻监听服务拉起来，失败也不影响上一步已经弹出的弹窗
            if (Prefs.monitorEnabled && !BluetoothMonitorService.running) {
                Log.i(TAG, "蓝牙事件兜底拉起服务（附带尝试）: " + action)
                BluetoothMonitorService.startFromBluetoothEvent(app)
            }
        } catch (t: Throwable) {
            // 广播回调里绝不能外抛异常：异常逃出 onReceive 会导致系统回收监听，从此彻底不弹窗
            Log.e(TAG, "兜底接收器处理失败（已捕获）: " + action + " " + t.message, t)
        }
    }

    /** 连接类事件：ACL 连接 + A2DP / HEADSET 连接状态变化（部分机型只发后者） */
    private fun isConnectAction(intent: Intent): Boolean = when (intent.action) {
        BluetoothDevice.ACTION_ACL_CONNECTED -> true
        BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED ->
            intent.getIntExtra(BluetoothA2dp.EXTRA_STATE, -1) == BluetoothA2dp.STATE_CONNECTED
        BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED ->
            intent.getIntExtra(BluetoothHeadset.EXTRA_STATE, -1) == BluetoothHeadset.STATE_CONNECTED
        else -> false
    }

    /** 断开类事件：ACL 断开 + A2DP / HEADSET 的断开状态 */
    private fun isDisconnectAction(intent: Intent): Boolean = when (intent.action) {
        BluetoothDevice.ACTION_ACL_DISCONNECTED -> true
        BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED ->
            intent.getIntExtra(BluetoothA2dp.EXTRA_STATE, -1) == BluetoothA2dp.STATE_DISCONNECTED
        BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED ->
            intent.getIntExtra(BluetoothHeadset.EXTRA_STATE, -1) == BluetoothHeadset.STATE_DISCONNECTED
        else -> false
    }

    private companion object {
        const val TAG = "BtEventReceiver"
    }
}
