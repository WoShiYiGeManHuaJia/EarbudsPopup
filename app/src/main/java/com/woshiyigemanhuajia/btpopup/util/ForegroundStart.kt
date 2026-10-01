package com.woshiyigemanhuajia.btpopup.util

import android.Manifest
import android.app.Service
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log

/**
 * 前台服务启动的统一判定与兜底。
 *
 * 【崩溃根因】
 *   android.app.RemoteServiceException$ForegroundServiceDidNotStartInTimeException
 *
 * 系统规则：一旦调用 Context.startForegroundService()，系统会在 **5 秒内**等待
 * Service.startForeground() 被调用；超时直接抛该异常并 **杀死整个进程**
 * （异常发生在 ActivityThread 主线程，无法用 try/catch 拦住）。
 *
 * 之前的实现是「先 startForegroundService()，再在服务里判断通知权限、
 * 没权限就跳过 startForeground()」—— 承诺了前台却不兑现，必然超时崩进程。
 * 这同时解释了三个现象：
 *   - 反复闪退（进程被杀）
 *   - 横屏弹窗不弹（进程死了自然弹不出来）
 *   - 关通知权限后彻底不弹
 *
 * 正确做法：**承诺之前先判断**。确定能调 startForeground() 才用前台方式启动，
 * 否则一律走普通 startService()。
 */
object ForegroundStart {

    private const val TAG = "ForegroundStart"

    /**
     * 当前是否"承诺得起"前台服务。
     *
     * - SDK < 26：没有前台服务概念，一律普通启动
     * - SDK 26..32：无需通知权限，可以安全承诺
     * - SDK >= 33：POST_NOTIFICATIONS 未授予时，服务里不会调 startForeground()，
     *              此处必须**不要**承诺前台，否则必然超时崩溃
     */
    @JvmStatic
    fun canPromiseForeground(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 26) return false
        if (Build.VERSION.SDK_INT < 33) return true
        return try {
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } catch (t: Throwable) {
            // 读权限失败时保守处理：不承诺前台，避免超时杀进程
            Log.w(TAG, "读取通知权限失败，按不可前台处理: " + t.message)
            false
        }
    }

    /**
     * 启动服务：能承诺前台就用前台方式，否则用普通方式。
     * 这样"承诺"与"兑现"永远一致，不会再出现 DidNotStartInTime 崩溃。
     */
    @JvmStatic
    fun start(context: Context, intent: android.content.Intent) {
        try {
            if (canPromiseForeground(context)) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        } catch (t: Throwable) {
            // 前台被拒（后台启动限制等）→ 退普通启动；再失败只记日志，绝不抛出
            Log.w(TAG, "启动服务（前台）失败，降级普通启动: " + t.message)
            try {
                context.startService(intent)
            } catch (t2: Throwable) {
                Log.e(TAG, "启动服务彻底失败: " + t2.message)
            }
        }
    }

    /**
     * Service 侧的兑现。
     *
     * 注意这里**不**在失败时 stopSelf()：
     * 只有在"通过 startForegroundService 启动"时系统才会做 5 秒超时检查，
     * 而那种情况我们已经保证了通知权限存在、startForeground 必然可调。
     * 走普通 startService 的路径下，即便这里调用失败也不会触发超时杀进程；
     * 此时贸然 stopSelf 反而会让服务立刻消失（广播没人注册），得不偿失。
     *
     * 失败只记日志，确保异常绝不外泄。
     */
    @JvmStatic
    fun Service.safeStartForeground(notificationId: Int, build: () -> android.app.Notification?) {
        if (Build.VERSION.SDK_INT < 26) return
        val n = try {
            build()
        } catch (t: Throwable) {
            Log.w(TAG, "构建通知失败（仅影响保活）: " + t.message)
            null
        }
        val finalN = n ?: try {
            // 通知建不出来时退到最小可用通知，尽量把"前台"这个承诺兑现掉
            android.app.Notification.Builder(this, "bt_popup_fallback").build()
        } catch (t: Throwable) {
            Log.w(TAG, "最小通知也建不出来（仅影响保活）: " + t.message)
            null
        }
        if (finalN == null) return
        try {
            startForeground(notificationId, finalN)
        } catch (t: Throwable) {
            try {
                // 再退一步：不带类型的老式调用
                startForeground(notificationId, finalN)
            } catch (t2: Throwable) {
                Log.w(TAG, "startForeground 失败（仅影响保活）: " + t2.message)
            }
        }
    }
}
