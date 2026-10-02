package com.woshiyigemanhuajia.btpopup.widget

import android.content.Context
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout

/**
 * 圆角卡片容器（参考外观 RoundedCardLayout 的 Kotlin 移植）。
 *
 * 与"给根布局设一个圆角背景"的区别：
 * 1. 圆角背景只负责画，子 View（图片）铺满时仍会把四个角盖成直角；
 *    这里同时用 [ViewOutlineProvider] + clipToOutline 做「框架级裁剪」，
 *    子 View 超出圆角的部分会被直接裁掉。
 * 2. 裁剪层本身带抗锯齿，图片铺满卡片时四个角也是平滑的。
 *
 * 主要用于横屏弹窗：图片铺满整张卡片、详情信息叠在底部，圆角必须由容器保证。
 */
class RoundedCardLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private var cardRadius = 0f
    private var strokeWidth = 0f
    private var strokeColor = 0

    /**
     * 一次性设置卡片外观：圆角、底色、可选描边。
     * 可反复调用（改圆角 / 改透明度 / 改配色时即时生效）。
     */
    private var cardColor = 0

    /**
     * 一次性设置卡片外观：圆角、底色、可选描边。
     * 可反复调用（改圆角 / 改透明度 / 改配色时即时生效）。
     *
     * 【圆角上限】卡片很扁时（横屏）大圆角会把两端拉成半圆，非常难看。
     * 这里统一按「短边的一半」封顶 —— 无论传入多大，形态都不会失控。
     * 横屏卡片矮，圆角会自动收到与高度相称的大小。
     */
    fun setCardStyle(radiusPx: Float, color: Int, outlineColor: Int = 0, outlineWidthPx: Float = 0f) {
        cardRadius = radiusPx.coerceAtLeast(0f)
        cardColor = color
        strokeColor = outlineColor
        strokeWidth = outlineWidthPx.coerceAtLeast(0f)
        rebuildBackground()
        clipToOutline = true
        invalidateOutline()
        invalidate()
    }

    /** 实际生效半径：按当前尺寸封顶到短边的一半 */
    private fun effectiveRadius(): Float {
        val half = minOf(width, height) / 2f
        return if (half <= 0f) cardRadius else minOf(cardRadius, half)
    }

    private fun rebuildBackground() {
        val r = effectiveRadius()
        val bg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = r
            setColor(cardColor)
            if (strokeWidth > 0f && strokeColor != 0) {
                setStroke(strokeWidth.toInt().coerceAtLeast(1), strokeColor)
            }
        }
        background = bg
    }

    init {
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                val r = effectiveRadius()
                if (r <= 0f) {
                    outline.setRect(0, 0, view.width, view.height)
                } else {
                    outline.setRoundRect(0, 0, view.width, view.height, r)
                }
            }
        }
        clipToOutline = true
    }

    fun getCardRadius(): Float = cardRadius

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // 尺寸变了（含旋转 / 横屏切换）：按新短边重算封顶后的圆角
        rebuildBackground()
        invalidateOutline()
    }
}
