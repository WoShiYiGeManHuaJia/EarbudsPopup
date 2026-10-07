package com.woshiyigemanhuajia.btpopup.island

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.woshiyigemanhuajia.btpopup.R
import com.woshiyigemanhuajia.btpopup.util.Prefs

/**
 * 自绘「耳机岛」—— 完全不依赖小米焦点通知 / miui.focus.param。
 *
 * 【为什么放弃系统焦点通知】
 * 从 1.7.0 到 1.7.8，我把课表的 miui.focus.param 逐字抄了一遍：
 * 6 个 miui.focus 字符串、protocol、templateNo、pics 的 4 个 key、
 * Shizuku 断网绕过的时序，全都对齐；还试过改包名、改用课表签名。
 * 系统在扫描结果里给出的答案是：
 *     focus_notifs=[]        updatable_focus_notifs=[]
 * 白名单是空的，而且断网绕过也确实执行成功了（uid=10186 -> true）——
 * 也就是说这条路在这台机器上就是不向第三方开放，改任何参数都无效。
 *
 * 【换个思路：自己画】
 * 本 App 本来就有 SYSTEM_ALERT_WINDOW 权限，悬浮弹窗一直稳定工作。
 * 岛本质上就是屏幕顶部一个胶囊形的黑色悬浮窗 —— 我们自己画一个即可：
 *   左：耳机图标    右：「已连接」
 * 位置、大小、圆角、停留时长全部可调，不依赖任何厂商私有协议。
 *
 * 这不是"真的系统岛"（不会跟系统挖孔联动、不能被系统收起），
 * 但视觉上就是你要的那个样子，而且一定能显示出来。
 */
object IslandOverlay {

    private const val TAG = "IslandOverlay"

    private var windowManager: WindowManager? = null
    private var rootView: FrameLayout? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private var dismissRunnable: Runnable? = null
    private var enterAnimator: ValueAnimator? = null

    @Volatile
    var showing: Boolean = false
        private set

    private fun dp(context: Context, v: Float): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, v,
            context.resources.displayMetrics
        ).toInt()

    fun show(context: Context, deviceName: String) {
        if (!Prefs.islandOverlayEnabled) return
        mainHandler.post {
            try {
                dismissInternal()
                val ctx = context.applicationContext
                val view = buildView(ctx, deviceName)
                val lp = buildLayoutParams(ctx)

                val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                wm.addView(view, lp)

                windowManager = wm
                rootView = view
                layoutParams = lp
                showing = true

                playEnter(ctx)

                val stay = Prefs.islandOverlayStayMs.coerceAtLeast(1000).toLong()
                val r = Runnable { dismiss() }
                dismissRunnable = r
                mainHandler.postDelayed(r, stay)
                Log.i(TAG, "耳机岛已显示，停留 ${stay}ms")
            } catch (t: Throwable) {
                Log.e(TAG, "显示耳机岛失败: " + t.message)
                showing = false
            }
        }
    }

    fun dismiss() {
        mainHandler.post { dismissInternal() }
    }

    private fun dismissInternal() {
        dismissRunnable?.let { mainHandler.removeCallbacks(it) }
        dismissRunnable = null
        enterAnimator?.cancel()
        enterAnimator = null
        try {
            rootView?.let { windowManager?.removeView(it) }
        } catch (t: Throwable) {
            Log.w(TAG, "移除耳机岛失败: " + t.message)
        }
        rootView = null
        windowManager = null
        layoutParams = null
        showing = false
    }

    private fun buildLayoutParams(ctx: Context): WindowManager.LayoutParams {
        val h = dp(ctx, Prefs.islandOverlayHeightDp.toFloat())
        val w = if (Prefs.islandOverlayFullWidth) {
            WindowManager.LayoutParams.MATCH_PARENT
        } else {
            dp(ctx, Prefs.islandOverlayWidthDp.toFloat())
        }
        val type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED

        val lp = WindowManager.LayoutParams(w, h, type, flags, PixelFormat.TRANSLUCENT)
        lp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        // 顶部偏移：默认贴在状态栏下方一点，接近系统岛的位置
        lp.y = dp(ctx, Prefs.islandOverlayTopDp.toFloat())
        return lp
    }

    private fun buildView(ctx: Context, deviceName: String): FrameLayout {
        val root = FrameLayout(ctx)

        // 胶囊底色：接近系统岛的纯黑，圆角由高度一半封顶 -> 两端自然全圆
        val bg = GradientDrawable().apply {
            setColor(Prefs.islandOverlayBgColor)
            cornerRadius = dp(ctx, Prefs.islandOverlayHeightDp.toFloat()) / 2f
        }
        root.background = bg

        val padH = dp(ctx, 12f)
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(padH, 0, padH, 0)
        }

        // 左：耳机图标
        val iconSize = dp(ctx, Prefs.islandOverlayHeightDp.toFloat() * 0.5f)
        val icon = ImageView(ctx).apply {
            setImageResource(R.drawable.ic_island_headset)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        row.addView(icon, LinearLayout.LayoutParams(iconSize, iconSize).apply {
            marginEnd = dp(ctx, 8f)
        })

        // 右：「已连接」
        val text = TextView(ctx).apply {
            this.text = Prefs.islandOverlayText.ifBlank { "已连接" }
            setTextColor(0xFFFFFFFF.toInt())
            textSize = Prefs.islandOverlayTextSp.toFloat()
            includeFontPadding = false
        }
        row.addView(text)

        // 可选：在文字后面再补设备名（默认关，保持简洁）
        if (Prefs.islandOverlayShowName && deviceName.isNotBlank()) {
            val name = TextView(ctx).apply {
                this.text = deviceName
                setTextColor(0xCCFFFFFF.toInt())
                textSize = Prefs.islandOverlayTextSp.toFloat() - 1f
                includeFontPadding = false
            }
            row.addView(name, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(ctx, 6f) })
        }

        root.addView(row, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
            android.view.Gravity.CENTER
        ))
        return root
    }

    /** 一个简单的缩放+淡入，模仿系统岛的展开 */
    private fun playEnter(ctx: Context) {
        val v = rootView ?: return
        enterAnimator?.cancel()
        v.scaleX = 0.85f
        v.scaleY = 0.85f
        v.alpha = 0f
        val anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 220
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val f = it.animatedFraction
                v.scaleX = 0.85f + 0.15f * f
                v.scaleY = 0.85f + 0.15f * f
                v.alpha = f
            }
        }
        enterAnimator = anim
        anim.start()
    }

    /** 已经显示了就更新尺寸/位置，避免每次都重建 */
    fun applyLayout(ctx: Context) {
        if (!showing) return
        mainHandler.post {
            val wm = windowManager ?: return@post
            val v = rootView ?: return@post
            try {
                val lp = buildLayoutParams(ctx)
                layoutParams = lp
                wm.updateViewLayout(v, lp)
            } catch (t: Throwable) {
                Log.w(TAG, "更新岛布局失败: " + t.message)
            }
        }
    }
}
