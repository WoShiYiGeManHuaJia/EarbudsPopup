package com.woshiyigemanhuajia.btpopup.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import com.woshiyigemanhuajia.btpopup.R
import com.woshiyigemanhuajia.btpopup.battery.BatteryInfo
import com.woshiyigemanhuajia.btpopup.overlay.PopupOverlayManager
import com.woshiyigemanhuajia.btpopup.util.Prefs

/**
 * 外观设置页里的「实时预览舞台」。
 *
 * 实现方式：在舞台内搭一个缩小版手机屏幕，再把「与真实悬浮窗完全同一套布局、数据绑定
 * 与样式逻辑」生成的弹窗视图，按真实 dp 尺寸摆进去，最后整体等比缩小显示。
 * 因此预览里的位置、大小、圆角、透明度、配色与真机悬浮窗完全一致（所见即所得）。
 *
 * 支持竖屏 / 横屏两种形态：横屏模式下把真实屏幕尺寸横过来当作「虚拟横屏屏幕」，
 * 这样在竖屏持机时也能看到横屏弹窗的真实比例与位置。
 */
class PopupPreviewStage(private val context: Context, private val stage: FrameLayout) {

    private val main = Handler(Looper.getMainLooper())
    private val renderTask = Runnable { renderNow() }

    private var info: BatteryInfo? = null
    private var landscape = false
    private var phone: FrameLayout? = null
    private var screen: FrameLayout? = null
    private var popupView: View? = null

    /** 数据变化（真实设备接入 / 电量刷新）时更新：数值一致则只重套样式，避免重复解码图片 */
    fun setInfo(value: BatteryInfo) {
        if (value == info) return
        info = value
        popupView = null
        requestRender()
    }

    /**
     * 强制重建预览视图。
     *
     * 换图片 / GIF 后必须调用：ensurePopup 默认复用同一个 View 只重套样式，
     * 而复用路径下的图片加载有「URI 未变就跳过」的判断，换图后若 URI 恰好相同
     * （见 MainActivity.copyMediaToLocal 的说明）就会一直显示旧图。
     * 这里直接丢弃旧视图，下次渲染必然走 createPreviewView 重新加载。
     */
    fun invalidate() {
        popupView = null
        screen?.removeAllViews()
        requestRender()
    }

    fun isLandscape(): Boolean = landscape

    /** 竖屏 / 横屏预览切换（横竖屏是两套不同布局，必须重建视图） */
    fun setLandscape(value: Boolean) {
        if (value == landscape) return
        landscape = value
        popupView = null
        requestRender()
    }

    /**
     * 外观参数（尺寸 / 位置 / 圆角 / 透明度 / 配色 / 图片）变化后调用，立即重绘预览。
     *
     * 防抖必须走**同一个 Handler**：旧写法 `main.removeCallbacks` 移除的是 main 这个
     * Handler 的消息，而任务是通过 `stage.post()` 投递到 ViewRootImpl 的 Handler 的，
     * 两个 Handler 实例不同，removeCallbacks 根本移除不掉 —— 防抖完全失效，
     * 滑块每动一帧就触发一次完整重建（丢图 / 卡顿 / 崩溃的放大器）。
     */
    fun requestRender() {
        main.removeCallbacks(renderTask)
        main.postDelayed(renderTask, 48L)
    }

    fun destroy() {
        main.removeCallbacksAndMessages(null)
        // stage 队列里可能还挂着旧任务（destroy 前经 stage.post 投递的），一并清掉
        stage.removeCallbacks(renderTask)
        stage.removeAllViews()
        phone = null
        screen = null
        popupView = null
    }

    // ------------------------------------------------------------------

    private fun renderNow() {
        val stageW = stage.width
        val stageH = stage.height
        if (stageW <= 0 || stageH <= 0) {
            // 舞台还没量好尺寸，稍后重试一次
            main.postDelayed(renderTask, 120L)
            return
        }
        val data = info ?: return

        val dm = context.resources.displayMetrics
        val density = dm.density
        // 竖屏状态预览横屏：把真实屏幕尺寸横过来当作虚拟屏幕
        val realWpx = if (landscape) dm.heightPixels else dm.widthPixels
        val realHpx = if (landscape) dm.widthPixels else dm.heightPixels

        // 缩放系数必须严格保证「虚拟手机框」能完整放进舞台：
        // 早期把下限锁在 0.2f，屏幕很高时 0.2 倍仍高于舞台（212dp），
        // 手机框溢出到舞台外，盖住下方「实时预览」说明与滑块，表现为文字与滑动条重叠。
        val scale = minOf(
            (stageW - 10f) / realWpx,
            (stageH - 10f) / realHpx
        ).coerceIn(0.05f, 1f)

        val phoneBox = ensurePhone()
        phoneBox.layoutParams = FrameLayout.LayoutParams(
            (realWpx * scale).toInt(),
            (realHpx * scale).toInt()
        ).apply { gravity = Gravity.CENTER }

        val realScreen = ensureScreen()
        realScreen.scaleX = scale
        realScreen.scaleY = scale
        realScreen.pivotX = 0f
        realScreen.pivotY = 0f
        realScreen.layoutParams = FrameLayout.LayoutParams(realWpx, realHpx)

        val popup = ensurePopup(data) ?: return

        val marginPx = if (landscape) {
            (Prefs.landMarginDp * density).toInt()
        } else {
            (8 * density).toInt()
        }
        val ratio = if (landscape) Prefs.landWidthPercent else Prefs.widthPercent
        // 横屏整体等比缩小系数：与真机悬浮窗共用同一套算法，保证预览所见即所得
        val landScale = if (landscape) PopupOverlayManager.landScale() else 1f
        val minW = (200 * density * landScale).toInt().coerceAtMost(realWpx)
        val maxW = (realWpx - marginPx * 2).coerceAtLeast(minW)
        val widthPx = (realWpx * ratio / 100f * landScale).toInt().coerceIn(minW, maxW)

        // 横屏卡片有确定高度（宽度 × 扁平比例）；
        // 竖屏高度完全由内容决定 —— 即「整体等比缩放后的自然高度」，
        // 不再按固定 dp 精确测量（那会把多出来的高度变成文字下方的空白）。
        val exactH = if (landscape) {
            PopupOverlayManager.landscapeAutoHeightPx(context, widthPx)
                .coerceAtMost((realHpx - marginPx * 2).coerceAtLeast(minW / 2))
        } else {
            null
        }
        popup.measure(
            View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
            if (exactH != null) {
                View.MeasureSpec.makeMeasureSpec(exactH, View.MeasureSpec.EXACTLY)
            } else {
                View.MeasureSpec.makeMeasureSpec(realHpx, View.MeasureSpec.AT_MOST)
            }
        )
        val popupH = exactH ?: popup.measuredHeight.coerceIn(1, realHpx)

        // 位置映射：把进度条 0-100 映射到「可摆放的空白区间」，
        // 保证滑块全程都真实生效（超宽卡片也不会出现"拖了没反应"）
        val freeX = (realWpx - widthPx - marginPx * 2).coerceAtLeast(0)
        val freeY = (realHpx - popupH - marginPx * 2).coerceAtLeast(0)
        val x = marginPx + (freeX * Prefs.posXPercent.coerceIn(0, 100) / 100f).toInt()
        val y = marginPx + (freeY * Prefs.posYPercent.coerceIn(0, 100) / 100f).toInt()

        val lp = popup.layoutParams as? FrameLayout.LayoutParams
            ?: FrameLayout.LayoutParams(widthPx, popupH)
        lp.width = widthPx
        lp.height = popupH
        lp.leftMargin = x
        lp.topMargin = y
        popup.layoutParams = lp

        if (popup.parent == null) realScreen.addView(popup)
        realScreen.requestLayout()
    }

    private fun ensurePhone(): FrameLayout {
        phone?.let { return it }
        val created = FrameLayout(context).apply {
            background = ContextCompat.getDrawable(context, R.drawable.bg_preview_screen)
        }
        stage.addView(created, FrameLayout.LayoutParams(0, 0).apply { gravity = Gravity.CENTER })
        phone = created
        return created
    }

    private fun ensureScreen(): FrameLayout {
        screen?.let { return it }
        val created = FrameLayout(context)
        ensurePhone().addView(created, FrameLayout.LayoutParams(1, 1))
        screen = created
        return created
    }

    private fun ensurePopup(data: BatteryInfo): View? {
        popupView?.let { existing ->
            // 同一个视图复用：只重套外观（圆角 / 透明度 / 配色 / 尺寸），不重新解码图片
            PopupOverlayManager.restylePreviewView(context, existing, landscape)
            return existing
        }
        screen?.removeAllViews()
        val created = PopupOverlayManager.createPreviewView(context, landscape, data, Prefs.imageUri)
        popupView = created
        return created
    }
}
