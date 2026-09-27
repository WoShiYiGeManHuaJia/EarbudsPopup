package com.yuanbao.earbuds;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.widget.LinearLayout;

/**
 * 圆角卡片容器（离屏合成，抗锯齿）。
 *
 * 为什么需要它：
 *   之前把卡片的 setClipToOutline(true) 去掉了（系统几何裁剪有锯齿），
 *   但 LinearLayout 不会裁剪子 View，结果铺满卡片的图片和渐变层
 *   把卡片自身的四个圆角盖成了直角 —— 用户看到「顶部没有圆角」。
 *
 * 这里改成：整个卡片连同所有子内容一起走一次 DST_IN 圆角合成，
 * 四角（含顶部两角）一律是抗锯齿圆角。
 */
public class RoundedCardLayout extends LinearLayout {

    private final Path roundPath = new Path();
    private final RectF rect = new RectF();
    private final Paint maskPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final PorterDuffXfermode dstIn =
            new PorterDuffXfermode(PorterDuff.Mode.DST_IN);
    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private float radiusPx = 0f;
    private int bgColor = 0;
    private int strokeColor = 0;
    private float strokeWidth = 0f;

    public RoundedCardLayout(Context c) {
        super(c);
        init();
    }

    public RoundedCardLayout(Context c, AttributeSet a) {
        super(c, a);
        init();
    }

    public RoundedCardLayout(Context c, AttributeSet a, int s) {
        super(c, a, s);
        init();
    }

    private void init() {
        setWillNotDraw(false);
    }

    /** 设置圆角（px）、底色、描边 */
    public void setCardStyle(float radiusPx, int bgColor, int strokeColor, float strokeWidth) {
        this.radiusPx = Math.max(0f, radiusPx);
        this.bgColor = bgColor;
        this.strokeColor = strokeColor;
        this.strokeWidth = strokeWidth;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        rect.set(0f, 0f, w, h);
        roundPath.rewind();
        roundPath.addRoundRect(rect, radiusPx, radiusPx, Path.Direction.CW);

        if (radiusPx <= 0f) {
            super.onDraw(canvas);
            return;
        }

        // ① 自己画底色和描边（Canvas 绘制，天然抗锯齿，不再依赖 GradientDrawable）
        bgPaint.setStyle(Paint.Style.FILL);
        bgPaint.setColor(bgColor);
        canvas.drawPath(roundPath, bgPaint);

        int saved = canvas.saveLayer(0f, 0f, w, h, null);

        // ② 画所有子内容（图片 / 渐变 / 文字）
        //    直接调用默认的绘制流程，避免 super.onDraw 与子 View 顺序混乱
        drawChildren(canvas);

        // ③ DST_IN 圆角遮罩：把四角外的内容变成透明，边缘带抗锯齿过渡
        maskPaint.setXfermode(dstIn);
        canvas.drawPath(roundPath, maskPaint);
        maskPaint.setXfermode(null);

        canvas.restoreToCount(saved);

        // ④ 描边画在最上层
        if (strokeWidth > 0f && strokeColor != 0) {
            bgPaint.setStyle(Paint.Style.STROKE);
            bgPaint.setColor(strokeColor);
            bgPaint.setStrokeWidth(strokeWidth);
            canvas.drawPath(roundPath, bgPaint);
        }
    }

    private void drawChildren(Canvas canvas) {
        for (int i = 0; i < getChildCount(); i++) {
            android.view.View child = getChildAt(i);
            if (child.getVisibility() != VISIBLE) continue;
            drawChild(canvas, child, getDrawingTime());
        }
    }
}
