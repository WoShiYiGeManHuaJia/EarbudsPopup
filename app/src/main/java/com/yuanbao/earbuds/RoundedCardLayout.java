package com.yuanbao.earbuds;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Outline;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;

/**
 * 圆角卡片容器。
 *
 * 【为什么彻底放弃离屏合成】
 *   之前几版都在 dispatchDraw 里用 canvas.saveLayer() + DST_IN 画圆角遮罩。
 *   但该 View 跑在硬件加速下：ViewGroup.dispatchDraw 时，子 View 各自拥有
 *   独立的 RenderNode，绘制会绕过外层 saveLayer 建立的图层，
 *   导致 DST_IN 遮罩完全不生效 —— 这就是「圆角一直没有」的真正原因。
 *   同一个思路无论重写多少遍都不会生效。
 *
 * 【现在的做法：框架级裁剪】
 *   ViewOutlineProvider + setClipToOutline(true)。裁剪发生在渲染管线内部，
 *   由系统在合成所有子 View 之后统一执行，硬件加速下必然生效。
 *
 * 【抗锯齿】
 *   裁剪本身是几何的（边缘可能有硬边），所以不依赖它做视觉圆角：
 *     - 卡片底色用 GradientDrawable 画圆角（Canvas 绘制，带抗锯齿）
 *     - 图片由 RoundedImageView 用离屏合成自带抗锯齿圆角（单 View 的
 *       onDraw 里 saveLayer 是可靠的，与 ViewGroup 不同）
 *   两者半径相同、边缘重合，看到的是平滑圆角；裁剪只作为「不溢出」的兜底。
 */
public class RoundedCardLayout extends FrameLayout {

    private float radiusPx = 0f;
    private int bgColor = 0;
    private int strokeColor = 0;
    private float strokeWidth = 0f;

    public RoundedCardLayout(Context c) {
        super(c);
    }

    public RoundedCardLayout(Context c, AttributeSet a) {
        super(c, a);
    }

    public RoundedCardLayout(Context c, AttributeSet a, int s) {
        super(c, a, s);
    }

    /** 设置圆角（px）、底色、描边 */
    public void setCardStyle(float radiusPx, int bgColor, int strokeColor, float strokeWidth) {
        this.radiusPx = Math.max(0f, radiusPx);
        this.bgColor = bgColor;
        this.strokeColor = strokeColor;
        this.strokeWidth = strokeWidth;

        // ① 背景：Canvas 绘制的圆角，天然抗锯齿
        GradientDrawable gd = new GradientDrawable();
        gd.setShape(GradientDrawable.RECTANGLE);
        gd.setCornerRadius(this.radiusPx);
        gd.setColor(bgColor);
        if (strokeWidth > 0f && strokeColor != 0) {
            gd.setStroke(Math.max(1, (int) strokeWidth), strokeColor);
        }
        setBackground(gd);

        // ② 框架级裁剪：保证任何子 View（含铺满的图片）都不溢出圆角
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(),
                            RoundedCardLayout.this.radiusPx);
                }
            });
            setClipToOutline(true);
        }
        invalidate();
    }

    public float getRadius() {
        return radiusPx;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        // 尺寸变化后 outline 需要重算
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && radiusPx > 0f) {
            invalidateOutline();
        }
    }
}
