package com.woshiyigemanhuajia.btpopup.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import rikka.shizuku.Shizuku
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Shizuku 特权服务绑定管理 —— 照搬 NexioSchedule 的 ShizukuManager 中与上岛相关的部分。
 *
 * 只保留两件事：
 *   1. bindUserService 拉起 PrivilegedServiceImpl（以 adb / root 身份运行）
 *   2. setXmsfNetworkingEnabled 切断 / 恢复 com.xiaomi.xmsf 的网络
 *
 * 去掉了课表里与本 App 无关的部分（静默安装、shell exec、binder 死亡监听等）。
 */
object IslandPrivilege {

    private const val TAG = "IslandPrivilege"
    private const val XMSF_PACKAGE = "com.xiaomi.xmsf"

    @Volatile
    private var privilegedService: IPrivilegedService? = null

    @Volatile
    private var serviceConnected = false

    @Volatile
    private var bindLatch = CountDownLatch(1)

    /** 最后一次操作的详细过程，供诊断显示 */
    @Volatile
    var lastReport: String = "尚未尝试"
        private set

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (binder != null && binder.pingBinder()) {
                privilegedService = IPrivilegedService.Stub.asInterface(binder)
                serviceConnected = true
                bindLatch.countDown()
                Log.d(TAG, "Privileged service connected")
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            privilegedService = null
            serviceConnected = false
            bindLatch = CountDownLatch(1)
            Log.d(TAG, "Privileged service disconnected")
        }
    }

    fun isShizukuRunning(): Boolean = try {
        Shizuku.pingBinder()
    } catch (e: Exception) {
        false
    }

    fun checkSelfPermission(): Boolean = try {
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (e: Exception) {
        false
    }

    /** 取特权服务：已绑定直接返回，否则发起绑定并最多等 3 秒 */
    private fun getPrivilegedService(): IPrivilegedService? {
        if (privilegedService != null && serviceConnected) return privilegedService
        return try {
            bindLatch = CountDownLatch(1)
            val args = Shizuku.UserServiceArgs(
                // 必须与 applicationId 一致，否则 Stellar 找不到服务组件
                ComponentName(
                    context.packageName,
                    PrivilegedServiceImpl::class.java.name
                )
            )
                .daemon(false)
                .processNameSuffix("privileged")
                .debuggable(false)
                .version(1)
            Shizuku.bindUserService(args, serviceConnection)
            val connected = bindLatch.await(3, TimeUnit.SECONDS)
            Log.d(TAG, "bindUserService -> connected=$connected")
            if (!connected) lastReport = "特权服务绑定超时（3 秒）"
            privilegedService
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bind privileged service", e)
            lastReport = "绑定特权服务异常: " + (e.message ?: e.toString())
            null
        }
    }

    private fun xmsfUid(context: Context): Int? = try {
        context.packageManager.getPackageUid(XMSF_PACKAGE, 0)
    } catch (e: Exception) {
        Log.w(TAG, "getPackageUid 失败: " + e.message)
        null
    }

    /**
     * 切换 xmsf 联网状态。
     *
     * 与课表完全一致的路径：Shizuku → 特权服务 → IConnectivityManager。
     * 不再走 shell 命令（实测在这台机器上命令返回失败）。
     */
    fun setXmsfNetworkingEnabled(context: Context, enabled: Boolean): Boolean {
        if (!isShizukuRunning()) {
            lastReport = "Shizuku 未运行"
            return false
        }
        if (!checkSelfPermission()) {
            lastReport = "Shizuku 未授权"
            return false
        }
        val uid = xmsfUid(context)
        if (uid == null) {
            lastReport = "取不到 $XMSF_PACKAGE 的 uid"
            return false
        }
        val service = getPrivilegedService()
        if (service == null) {
            if (!lastReport.startsWith("特权服务") && !lastReport.startsWith("绑定")) {
                lastReport = "特权服务不可用"
            }
            return false
        }
        return try {
            val ok = service.setPackageNetworkingEnabled(uid, enabled)
            lastReport = "uid=$uid enabled=$enabled -> $ok"
            Log.d(TAG, lastReport)
            ok
        } catch (e: Exception) {
            Log.e(TAG, "setPackageNetworkingEnabled 失败", e)
            lastReport = "调用特权服务异常: " + (e.message ?: e.toString())
            false
        }
    }
}
