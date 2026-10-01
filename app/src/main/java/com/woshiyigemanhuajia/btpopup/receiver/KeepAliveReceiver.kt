package com.woshiyigemanhuajia.btpopup.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.woshiyigemanhuajia.btpopup.battery.BatteryUpdateBridge
import com.woshiyigemanhuajia.btpopup.service.BluetoothMonitorService
import com.woshiyigemanhuajia.btpopup.service.GuardService
import com.woshiyigemanhuajia.btpopup.service.KeepAliveScheduler
import com.woshiyigemanhuajia.btpopup.util.Prefs

class KeepAliveReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        Log.i("BtKeepAlive", "心跳触发，检查监听服务")
        Prefs.init(context)
        if (!Prefs.monitorEnabled) return
        if (!BluetoothMonitorService.running) {
            BluetoothMonitorService.start(context)
        }
        // 守护服务也要一起补拉：它负责轮询发现"监听服务已死"，
        // 之前心跳只补拉监听服务，守护进程一旦被杀就再也回不来，
        // 后续服务被回收时没人兜底 —— 后台弹窗概率就一路走低。
        if (Prefs.foregroundGuard) GuardService.start(context)
        // 电量广播桥同样要补挂：进程被广播唤醒时它是收到真实电量的唯一通道
        BatteryUpdateBridge.ensureRegistered(context)
        KeepAliveScheduler.schedule(context)
    }
}
