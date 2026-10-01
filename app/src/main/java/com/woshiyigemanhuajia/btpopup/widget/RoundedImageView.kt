package com.woshiyigemanhuajia.btpopup.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.util.AttributeSet
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.ImageView
import kotlin.math.abs
import kotlin.math.min

/**
 * 圆角图片（参考外观 RoundedImageView 的 Kotlin 移植）。
 *
 * 按"源是不是动画"自动切换两套裁剪策略：
 *  1. 静态图片：离屏图层 + DST_IN 圆角遮罩，边缘抗锯齿最好（原实现，画质优先）；
 *  2. 动画（GIF / 逐帧）：改用 ViewOutlineProvider + clipToOutline，由 GPU 直接做圆角裁剪。
 *     GIF 每一帧都会重绘，若每帧都开一次离屏图层再合成遮罩，主线程会持续掉帧、
 *     掉帧又反过来拖慢解码回调 —— 这是"GIF 播放很卡"的主要来源之一，动画必须走便宜的那条路。
 */
class RoundedImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ImageView(context, attrs, defStyleAttr) {

    private val roundRect = RectF()
    private val roundPath = Path()
    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val maskMode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)

    private var radiusPx = 0f

    /** 当前源是否为动画（GIF / 逐帧）：决定走 outline 裁剪还是离屏遮罩 */
    private var animatedSrc = false

    private val roundOutlineProvider = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            val r = min(radiusPx, min(view.width, view.height) / 2f)
            if (r <= 0f) {
                outline.setRect(0, 0, view.width, view.height)
            } else {
                outline.setRoundRect(0, 0, view.width, view.height, r)
            }
        }
    }

    /** 设置圆角半径（px），传入 0 表示不裁剪 */
    fun setRadius(value: Float) {
        val v = value.coerceAtLeast(0f)
        if (abs(v - radiusPx) < 0.5f) {
            syncClipStrategy()
            return
        }
        radiusPx = v
        syncClipStrategy()
        invalidateOutline()
        invalidate()
    }

    fun getRadius(): Float = radiusPx

    override fun setImageDrawable(drawable: Drawable?) {
        super.setImageDrawable(drawable)
        onSourceChanged(drawable)
    }

    override fun setImageBitmap(bm: android.graphics.Bitmap?) {
        super.setImageBitmap(bm)
        onSourceChanged(drawable)
    }

    override fun setImageURI(uri: Uri?) {
        super.setImageURI(uri)
        onSourceChanged(drawable)
    }

    private fun onSourceChanged(drawable: Drawable?) {
        // 动画源（GIF / AnimatedImageDrawable / 逐帧）一律走 GPU 圆角裁剪
        animatedSrc = drawable is Animatable
        syncClipStrategy()
        invalidate()
    }

    /**
     * 把 clipToOutline 调整到与当前源匹配的状态。
     * 静态图关掉 outline 裁剪（交给离屏遮罩，抗锯齿更好）；
     * 动画打开 outline 裁剪（GPU 直接裁，零额外图层）。
     */
    private fun syncClipStrategy() {
        val wantOutline = animatedSrc && radiusPx > 0f
        if (wantOutline) {
            if (outlineProvider !== roundOutlineProvider) outlineProvider = roundOutlineProvider
            if (!clipToOutline) clipToOutline = true
        } else {
            if (clipToOutline) clipToOutline = false
        }
    }

    override fun onDraw(canvas: Canvas) {
        val w = width
        val h = height
        if (radiusPx <= 0f || w <= 0 || h <= 0) {
            super.onDraw(canvas)
            return
        }

        // 兜底：万一源被别的代码路径换掉（没走上面几个 setter），这里同步一次状态
        val animated = drawable is Animatable
        if (animated != animatedSrc) {
            animatedSrc = animated
            syncClipStrategy()
        }

        // 动画：已由 clipToOutline 在 GPU 侧裁好圆角，这里直接画，不再开离屏图层
        if (animatedSrc && clipToOutline) {
            super.onDraw(canvas)
            return
        }

        roundRect.set(0f, 0f, w.toFloat(), h.toFloat())
        roundPath.rewind()
        roundPath.addRoundRect(roundRect, radiusPx, radiusPx, Path.Direction.CW)

        val layer = canvas.saveLayer(0f, 0f, w.toFloat(), h.toFloat(), null)
        super.onDraw(canvas)
        maskPaint.xfermode = maskMode
        canvas.drawPath(roundPath, maskPaint)
        maskPaint.xfermode = null
        canvas.restoreToCount(layer)
    }
}
