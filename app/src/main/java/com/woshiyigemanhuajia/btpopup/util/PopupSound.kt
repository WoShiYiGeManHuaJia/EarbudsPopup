package com.woshiyigemanhuajia.btpopup.util

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * 弹窗提示音。
 *
 * 支持：
 *  - 自定义上传的音频（soundUri）
 *  - 弹窗出现后的延迟播放（soundDelayMs）
 *  - 播放时长上限（soundDurationMs，0 = 播完整段）
 *  - 音量（soundVolume，0-100）
 *
 * 全程异常不外泄：音频源损坏、格式不支持、焦点被抢等情况都只记日志，
 * 绝不能因为放个声音把弹窗 / 进程带崩 —— 这是本项目反复踩过的坑。
 */
object PopupSound {

    private const val TAG = "PopupSound"

    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var player: MediaPlayer? = null

    private var stopTask: Runnable? = null

    /** 弹窗显示后调用：按配置播放提示音 */
    fun play(context: Context) {
        if (!Prefs.soundEnabled) return
        val uriStr = Prefs.soundUri
        if (uriStr.isNullOrBlank()) return

        val delay = Prefs.soundDelayMs.toLong()
        if (delay <= 0) {
            playNow(context)
        } else {
            main.postDelayed({ playNow(context) }, delay)
        }
    }

    private fun playNow(context: Context) {
        val uriStr = Prefs.soundUri ?: return
        val uri = try {
            Uri.parse(uriStr)
        } catch (t: Throwable) {
            Log.w(TAG, "提示音 URI 解析失败: " + t.message)
            return
        }
        stop()
        try {
            val mp = MediaPlayer()
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            mp.setDataSource(context.applicationContext, uri)
            val vol = Prefs.soundVolume / 100f
            mp.setVolume(vol, vol)
            mp.setOnPreparedListener { p ->
                try {
                    p.start()
                } catch (t: Throwable) {
                    Log.w(TAG, "提示音 start 失败: " + t.message)
                }
            }
            mp.setOnErrorListener { _, what, extra ->
                Log.w(TAG, "提示音播放错误 what=$what extra=$extra")
                release()
                true
            }
            mp.setOnCompletionListener { release() }
            // 异步准备：不阻塞弹窗主线程
            mp.prepareAsync()
            player = mp

            val dur = Prefs.soundDurationMs
            if (dur > 0) {
                val task = Runnable {
                    try {
                        if (player?.isPlaying == true) player?.stop()
                    } catch (ignored: Throwable) {
                    }
                    release()
                }
                stopTask = task
                main.postDelayed(task, dur.toLong())
            }
        } catch (t: Throwable) {
            Log.w(TAG, "提示音初始化失败: " + t.message)
            release()
        }
    }

    /** 停止并释放当前播放（弹窗关闭 / 配置变更时调用） */
    fun stop() {
        stopTask?.let { main.removeCallbacks(it) }
        stopTask = null
        release()
    }

    private fun release() {
        try {
            player?.let {
                if (it.isPlaying) it.stop()
                it.release()
            }
        } catch (ignored: Throwable) {
        }
        player = null
    }
}
