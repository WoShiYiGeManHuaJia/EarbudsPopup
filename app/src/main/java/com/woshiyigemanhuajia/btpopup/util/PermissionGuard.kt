package com.woshiyigemanhuajia.btpopup.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.woshiyigemanhuajia.btpopup.R

/**
 * 三项关键权限的统一定义与自检。
 *
 * 为什么要独立出来：此前"权限静默失败"——用户以为已经开启，实际没授权，
 * 现象就是"连接耳机不弹窗、也不报错"。这里把「未授权」与「已授权」两种
 * 失败原因显式区分出来，首页、通知栏文案、日志共用同一份判定。
 *
 *  - 悬浮窗（SYSTEM_ALERT_WINDOW）：缺少 → 弹窗根本没有窗口可加
 *  - 通知（POST_NOTIFICATIONS，Android 13+）：缺少 → 前台服务通知不可见
 *  - 蓝牙连接（BLUETOOTH_CONNECT，Android 12+）：缺少 → 读设备地址/名称/已配对列表均抛异常
 */
object PermissionGuard {

    /** Android 12（API 31）起读蓝牙设备信息需要 BLUETOOTH_CONNECT */
    const val MIN_SDK_BT_CONNECT = 31

    /** Android 13（API 33）起需要 POST_NOTIFICATIONS 才能显示通知 */
    const val MIN_SDK_NOTIFICATION = 33

    data class Status(
        val overlay: Boolean,
        val notifications: Boolean,
        val bluetoothConnect: Boolean
    ) {
        val allGranted: Boolean get() = overlay && notifications && bluetoothConnect
        val missingCount: Int get() = listOf(overlay, notifications, bluetoothConnect).count { !it }
    }

    fun check(context: Context): Status = Status(
        overlay = canDrawOverlay(context),
        notifications = granted(context, Manifest.permission.POST_NOTIFICATIONS, MIN_SDK_NOTIFICATION),
        bluetoothConnect = granted(context, Manifest.permission.BLUETOOTH_CONNECT, MIN_SDK_BT_CONNECT)
    )

    fun canDrawOverlay(context: Context): Boolean = try {
        Settings.canDrawOverlays(context)
    } catch (t: Throwable) {
        false
    }

    private fun granted(context: Context, permission: String, minSdk: Int): Boolean {
        if (Build.VERSION.SDK_INT < minSdk) return true
        return try {
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        } catch (t: Throwable) {
            false
        }
    }

    /** 缺失项的可读文案，顺序即建议的授权顺序：悬浮窗 → 蓝牙连接 → 通知 */
    fun missingLabels(context: Context): List<String> {
        val status = check(context)
        val labels = mutableListOf<String>()
        if (!status.overlay) labels += context.getString(R.string.perm_label_overlay)
        if (!status.bluetoothConnect) labels += context.getString(R.string.perm_label_bt)
        if (!status.notifications) labels += context.getString(R.string.perm_label_notif)
        return labels
    }

    /** 还缺哪些运行时权限（交给系统弹窗申请的那部分） */
    fun missingRuntimePermissions(context: Context): List<String> {
        val status = check(context)
        val perms = mutableListOf<String>()
        if (!status.bluetoothConnect) perms += Manifest.permission.BLUETOOTH_CONNECT
        if (!status.notifications) perms += Manifest.permission.POST_NOTIFICATIONS
        return perms
    }

    /**
     * 自检结论：明确区分「已授权」与「未授权」，未授权时直接指出缺哪一项。
     * 服务启动日志、通知栏文案共用。
     */
    fun describe(context: Context): String {
        val status = check(context)
        if (status.allGranted) return context.getString(R.string.perm_state_ready)
        val missing = missingLabels(context).joinToString("、")
        return context.getString(R.string.perm_state_missing, missing)
    }

    fun grantedText(granted: Boolean): String = if (granted) "已授权" else "未授权"
}
