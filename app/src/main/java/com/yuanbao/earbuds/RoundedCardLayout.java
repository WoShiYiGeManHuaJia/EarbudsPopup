package com.yuanbao.earbuds;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.widget.FrameLayout;

/**
 * 圆角卡片容器（离屏合成，抗锯齿）。
 *
 * 必须是 FrameLayout，不能用 LinearLayout：
 *   布局里媒体区是 match_parent 铺满、详情区用 layout_gravity="bottom" 叠加。
 *   LinearLayout 是顺序排列，第一个子 View 占满后，后面的子 View 会被挤出可视区域
 *   —— 这正是「文字不见了、弹窗比例变得长不长圆不圆」的原因。
 *
 * 圆角做法：在 dispatchDraw 里把【所有子内容】画进离屏层，再用 DST_IN
 * 画一个抗锯齿圆角矩形。子 View 只绘制这一次，不会被画两遍盖掉圆角。
 */
public class RoundedCardLayout extends FrameLayout {

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
        // 关闭「不绘制」优化，保证 dispatchDraw 一定被调用
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
    protected void dispatchDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0 || radiusPx <= 0f) {
            super.dispatchDraw(canvas);
            return;
        }

        rect.set(0f, 0f, w, h);
        roundPath.rewind();
        roundPath.addRoundRect(rect, radiusPx, radiusPx, Path.Direction.CW);

        // ① 底色（Canvas 绘制，天然抗锯齿）
        bgPaint.setStyle(Paint.Style.FILL);
        bgPaint.setColor(bgColor);
        canvas.drawPath(roundPath, bgPaint);

        // ② 离屏层：子内容画进去
        int saved = canvas.saveLayer(0f, 0f, w, h, null);
        super.dispatchDraw(canvas);

        // ③ DST_IN 圆角遮罩：四角外变透明，边缘抗锯齿
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
}
