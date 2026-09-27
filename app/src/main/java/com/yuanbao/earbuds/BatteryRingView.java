package com.yuanbao.earbuds;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

/**
 * 电量圆环：外圈进度弧 + 中心百分比。
 * 参照华为 / 小米弹窗里耳机、充电盒各自的独立电量指示。
 */
public class BatteryRingView extends View {

    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint progPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval = new RectF();

    private int progress = 78;
    private int ringColor = 0xFF00E5A0;
    private int trackColor = 0x33FFFFFF;
    private float strokePx;
    private float textSizePx;

    public BatteryRingView(Context c) {
        super(c);
        init(c);
    }

    public BatteryRingView(Context c, @Nullable AttributeSet a) {
        super(c, a);
        init(c);
    }

    public BatteryRingView(Context c, @Nullable AttributeSet a, int style) {
        super(c, a, style);
        init(c);
    }

    private void init(Context c) {
        float d = c.getResources().getDisplayMetrics().density;
        strokePx = 5.5f * d;
        textSizePx = 14f * d;

        trackPaint.setStyle(Paint.Style.STROKE);
        trackPaint.setStrokeWidth(strokePx);
        trackPaint.setColor(trackColor);
        trackPaint.setStrokeCap(Paint.Cap.ROUND);

        progPaint.setStyle(Paint.Style.STROKE);
        progPaint.setStrokeWidth(strokePx);
        progPaint.setColor(ringColor);
        progPaint.setStrokeCap(Paint.Cap.ROUND);

        textPaint.setColor(0xFFFFFFFF);
        textPaint.setTextSize(textSizePx);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setFakeBoldText(true);
    }

    public void setProgress(int p) {
        progress = Math.max(0, Math.min(100, p));
        invalidate();
    }

    public void setRingColor(int c) {
        ringColor = c;
        progPaint.setColor(c);
        invalidate();
    }

    public void setTrackColor(int c) {
        trackColor = c;
        trackPaint.setColor(c);
        invalidate();
    }

    public void setTextColor(int c) {
        textPaint.setColor(c);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;

        float inset = strokePx / 2f + 1f;
        oval.set(inset, inset, w - inset, h - inset);

        canvas.drawArc(oval, 0, 360, false, trackPaint);
        if (progress > 0) {
            // 从 12 点方向起、顺时针
            canvas.drawArc(oval, -90, 360f * progress / 100f, false, progPaint);
        }

        String txt = progress + "%";
        Paint.FontMetrics fm = textPaint.getFontMetrics();
        float base = h / 2f - (fm.ascent + fm.descent) / 2f;
        canvas.drawText(txt, w / 2f, base, textPaint);
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        // 默认 56dp 见方，可被 layout_width/height 覆盖
        int def = (int) (56 * getResources().getDisplayMetrics().density);
        int w = resolveSize(def, wSpec);
        int h = resolveSize(def, hSpec);
        int size = Math.min(w, h);
        setMeasuredDimension(size, size);
    }
}
