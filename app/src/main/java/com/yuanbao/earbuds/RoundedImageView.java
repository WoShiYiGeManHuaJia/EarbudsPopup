package com.yuanbao.earbuds;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.util.AttributeSet;

import androidx.appcompat.widget.AppCompatImageView;

/**
 * 抗锯齿圆角 ImageView：专门给 GIF / 图片用。
 *
 * 为什么不用系统的 setClipToOutline：
 *   系统提供的圆角裁切（ViewOutlineProvider + setClipToOutline）本质是几何裁剪，
 *   在多数 Android 版本上不做边缘抗锯齿，图片和 GIF 的四个圆角会出现明显锯齿——
 *   这就是用户看到的「圆角有锯齿」。
 *
 * 这里的做法（离屏合成）：
 *   1. canvas.saveLayer 开一层离屏缓冲
 *   2. 正常绘制内容（GIF 当前帧 / 静态图）
 *   3. 用 DST_IN 混合模式画一个抗锯齿的圆角矩形路径
 *      —— 圆角外变成透明，边缘的半透明过渡像素由 drawPath 的抗锯齿产生，
 *        所以圆角是平滑的，不会有锯齿
 *
 * 只在圆角 > 0 时走这条路；圆角为 0 时直接走系统绘制，不额外开销。
 */
public class RoundedImageView extends AppCompatImageView {

    private final Path roundPath = new Path();
    private final RectF rect = new RectF();
    private final Paint maskPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final PorterDuffXfermode dstIn =
            new PorterDuffXfermode(PorterDuff.Mode.DST_IN);

    private float radiusPx = 0f;

    public RoundedImageView(Context c) {
        super(c);
    }

    public RoundedImageView(Context c, AttributeSet attrs) {
        super(c, attrs);
    }

    public RoundedImageView(Context c, AttributeSet attrs, int defStyle) {
        super(c, attrs, defStyle);
    }

    /** 设置圆角半径（单位 px） */
    public void setRadius(float px) {
        float v = Math.max(0f, px);
        if (Math.abs(v - radiusPx) < 0.5f) return;
        radiusPx = v;
        invalidate();
    }

    public float getRadius() {
        return radiusPx;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (radiusPx <= 0f || w <= 0 || h <= 0) {
            super.onDraw(canvas);
            return;
        }

        rect.set(0f, 0f, w, h);
        roundPath.rewind();
        roundPath.addRoundRect(rect, radiusPx, radiusPx, Path.Direction.CW);

        // 开离屏层：Xfermode 必须在独立图层上才能正确作用于已绘制内容
        int saved = canvas.saveLayer(0f, 0f, w, h, null);
        super.onDraw(canvas);                 // 画 GIF 当前帧 / 图片
        maskPaint.setXfermode(dstIn);
        canvas.drawPath(roundPath, maskPaint); // 抗锯齿圆角遮罩
        maskPaint.setXfermode(null);
        canvas.restoreToCount(saved);
    }
}
