package com.woshiyigemanhuajia.btpopup.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.woshiyigemanhuajia.btpopup.util.Prefs

/**
 * 无障碍保活服务。
 *
 * 作用：被系统开启后，本应用会被 Android 视为「无障碍服务宿主进程」，
 * 属于系统长期保留进程，被回收的概率大幅降低；即使监听服务被系统杀掉，
 * 这里也会在收到窗口事件时顺手把它重新拉起。
 *
 * 隐私边界：只接收「窗口切换」这一种事件用于判断系统仍在运行，
 * 不读取窗口内容（配置里 canRetrieveWindowContent=false），
 * 不做点击、代填、截屏、数据收集等任何操作，收到事件直接返回。
 */
class KeepAliveAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "BtA11y"

        /** 服务是否已连接（进程内标记，供设置页展示状态） */
        @Volatile
        var connected: Boolean = false
            private set

        /** 打开系统无障碍设置页，让用户手动启用本服务 */
        fun openSettings(context: Context) {
            try {
                context.startActivity(
                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (t: Throwable) {
                Log.w(TAG, "无法打开无障碍设置页: " + t.message)
            }
        }

        /** 查询系统里本服务是否处于「已启用」状态 */
        fun isEnabled(context: Context): Boolean {
            val expected = ComponentName(context, KeepAliveAccessibilityService::class.java)
            return try {
                val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE)
                    as? android.view.accessibility.AccessibilityManager ?: return false
                am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                    .any { info ->
                        val si = info.resolveInfo?.serviceInfo ?: return@any false
                        val cn = ComponentName(si.packageName, si.name)
                        cn == expected || TextUtils.equals(cn.flattenToString(), expected.flattenToString()) ||
                            cn.flattenToShortString() == expected.flattenToShortString()
                    }
            } catch (t: Throwable) {
                Log.w(TAG, "查询无障碍状态失败: " + t.message)
                false
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        connected = true
        Prefs.init(this)
        try {
            val info = serviceInfo
            if (info != null) {
                // 只关心窗口切换，且不拉取窗口内容
                info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
                info.notificationTimeout = 500
                if (Build.VERSION.SDK_INT >= 16) {
                    info.flags = info.flags and AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS.inv()
                }
                serviceInfo = info
            }
        } catch (t: Throwable) {
            Log.w(TAG, "配置无障碍服务参数失败: " + t.message)
        }
        Log.i(TAG, "无障碍保活服务已连接")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 不做任何窗口内容处理：这里只借助系统发送的事件，确认自身存活并顺手拉起监听服务
        if (!Prefs.monitorEnabled || BluetoothMonitorService.running) return
        try {
            Log.i(TAG, "监听到系统事件，监听服务不在运行，重新拉起")
            BluetoothMonitorService.start(this)
        } catch (t: Throwable) {
            Log.w(TAG, "拉起监听服务失败: " + t.message)
        }
    }

    override fun onInterrupt() {
        // 无需处理
    }

    override fun onDestroy() {
        connected = false
        Log.i(TAG, "无障碍保活服务已断开")
        super.onDestroy()
    }
}
