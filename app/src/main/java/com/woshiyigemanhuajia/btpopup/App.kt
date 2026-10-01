package com.woshiyigemanhuajia.btpopup

import android.app.Application
import android.os.Build
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import com.woshiyigemanhuajia.btpopup.battery.BatteryUpdateBridge
import com.woshiyigemanhuajia.btpopup.util.Prefs

class App : Application(), ImageLoaderFactory {

    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        installCrashLogger()
        // 进程级电量广播桥：进程活着就能收到「系统真实电量 + HFP 分体电量」，
        // 不依赖前台监听服务是否拉得起来
        BatteryUpdateBridge.ensureRegistered(this)
    }

    /**
     * 崩溃日志捕获。
     *
     * 连续多个版本反复闪退却始终定位不到原因 —— 因为此前根本没有任何崩溃记录，
     * 只能靠现象猜。这里把未捕获异常完整写进本机文件（含机型 / 系统版本 / 完整堆栈），
     * 设置页可直接查看与导出。有了堆栈就能精确到行号，不再靠猜。
     */
    private fun installCrashLogger() {
        val default = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sb = StringBuilder()
                sb.append("========== 崩溃报告 ==========\n")
                sb.append("时间: ").append(java.text.SimpleDateFormat(
                    "yyyy-MM-dd HH:mm:ss", java.util.Locale.CHINA
                ).format(java.util.Date())).append('\n')
                sb.append("机型: ").append(Build.MODEL).append(" / ").append(Build.DEVICE).append('\n')
                sb.append("系统: Android ").append(Build.VERSION.RELEASE)
                    .append(" (SDK ").append(Build.VERSION.SDK_INT).append(")\n")
                sb.append("线程: ").append(thread.name).append('\n')
                sb.append('\n').append(throwable.toString()).append('\n')
                throwable.stackTrace.forEach { sb.append("    at ").append(it.toString()).append('\n') }
                throwable.cause?.let { c ->
                    sb.append("\nCaused by: ").append(c.toString()).append('\n')
                    c.stackTrace.take(30).forEach { sb.append("    at ").append(it.toString()).append('\n') }
                }
                sb.append("========== 报告结束 ==========\n\n")
                val dir = java.io.File(filesDir, "crash")
                if (!dir.exists()) dir.mkdirs()
                val f = java.io.File(dir, "crash.log")
                f.appendText(sb.toString())
                // 只保留最近 200KB，避免日志本身把存储写满
                if (f.length() > 200_000) {
                    val keep = f.readText().takeLast(120_000)
                    f.writeText(keep)
                }
            } catch (ignored: Throwable) {
            }
            default?.uncaughtException(thread, throwable)
        }
    }

    /** 让 Coil 支持 GIF / 动图 */
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .components {
            if (Build.VERSION.SDK_INT >= 28) {
                add(ImageDecoderDecoder.Factory())
            } else {
                add(GifDecoder.Factory())
            }
        }
        .crossfade(true)
        .build()
}
