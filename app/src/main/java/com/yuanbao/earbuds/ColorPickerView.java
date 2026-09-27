package com.yuanbao.earbuds;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ComposeShader;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/**
 * 直观色彩盘（HSV）。
 *
 * 三段结构：
 *   ① 上方大方块：饱和度（横向）× 明度（纵向）平面，底色随当前色相变化
 *   ② 中间横条：色相 H（0~360）
 *   ③ 下方横条：透明度 A（0~255）
 *
 * 用户要求「用色彩盘而不是填颜色代码」，所以这里所有操作都是触摸取色，
 * 代码值（#AARRGGBB）在内部换算后仍按原样存进 Prefs，不影响其它逻辑。
 *
 * 性能：SV 平面用 Bitmap 缓存，只在色相变化时重绘，
 * 手指拖动时直接复用，避免每帧重算渐变。
 */
public class ColorPickerView extends View {

    public interface OnColorChanged {
        void onColorChanged(int color);
    }

    private static final float BAR_H_DP = 22f;
    private static final float GAP_DP = 12f;

    /** 当前 HSV（h[0]=hue h[1]=sat h[2]=val） */
    private final float[] hsv = new float[]{0f, 1f, 1f};
    private int alpha = 255;

    private Bitmap svBitmap;
    private final Paint svPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint knobPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF svRect = new RectF();

    private float d = 1f;
    private OnColorChanged listener;

    public ColorPickerView(Context c) {
        super(c);
        init(c);
    }

    public ColorPickerView(Context c, AttributeSet a) {
        super(c, a);
        init(c);
    }

    public ColorPickerView(Context c, AttributeSet a, int s) {
        super(c, a, s);
        init(c);
    }

    private void init(Context c) {
        d = c.getResources().getDisplayMetrics().density;
        knobPaint.setStyle(Paint.Style.STROKE);
        knobPaint.setStrokeWidth(2 * d);
        knobPaint.setColor(0xFFFFFFFF);
        knobPaint.setShadowLayer(3 * d, 0, 0, 0x99000000);
    }

    public void setOnColorChangedListener(OnColorChanged l) {
        listener = l;
    }

    /** 用 #AARRGGBB 设置初始颜色 */
    public void setColor(int color) {
        alpha = (color >>> 24) & 0xFF;
        int rgb = color | 0xFF000000;
        Color.colorToHSV(rgb, hsv);
        svBitmap = null;   // 色相变了，缓存作废
        invalidate();
    }

    /** 取当前颜色（含 alpha） */
    public int getColor() {
        return Color.HSVToColor(alpha, hsv);
    }

    public String getHex() {
        return String.format("#%08X", getColor());
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        svBitmap = null;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float barH = BAR_H_DP * d;
        float gap = GAP_DP * d;

        float left = getPaddingLeft();
        float right = getWidth() - getPaddingRight();
        float top = getPaddingTop();

        float alphaTop = getHeight() - getPaddingBottom() - barH;
        float hueTop = alphaTop - gap - barH;
        float svBottom = hueTop - gap;

        svRect.set(left, top, right, svBottom);
        float sw = svRect.width();
        float sh = svRect.height();
        if (sw <= 0 || sh <= 0) return;

        // ① SV 平面（Bitmap 缓存）
        if (svBitmap == null
                || svBitmap.getWidth() != (int) sw
                || svBitmap.getHeight() != (int) sh) {
            svBitmap = Bitmap.createBitmap((int) sw, (int) sh, Bitmap.Config.ARGB_8888);
            Canvas bc = new Canvas(svBitmap);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

            // 横向：白 → 当前色相纯色（饱和度）
            Shader sat = new LinearGradient(0, 0, sw, 0,
                    0xFFFFFFFF, Color.HSVToColor(new float[]{hsv[0], 1f, 1f}),
                    Shader.TileMode.CLAMP);
            // 纵向：透明 → 黑（明度）
            Shader val = new LinearGradient(0, 0, 0, sh,
                    0x00000000, 0xFF000000, Shader.TileMode.CLAMP);
            p.setShader(new ComposeShader(sat, val, PorterDuff.Mode.MULTIPLY));
            bc.drawRect(0, 0, sw, sh, p);
        }
        canvas.drawBitmap(svBitmap, svRect.left, svRect.top, svPaint);

        // ② 色相条
        drawHueBar(canvas, left, hueTop, right, hueTop + barH);
        // ③ 透明度条
        drawAlphaBar(canvas, left, alphaTop, right, alphaTop + barH);

        // 选取点
        float kx = svRect.left + hsv[1] * sw;
        float ky = svRect.top + (1f - hsv[2]) * sh;
        drawKnob(canvas, kx, ky);

        float hx = left + (hsv[0] / 360f) * (right - left);
        drawKnob(canvas, hx, hueTop + barH / 2f);

        float ax = left + (alpha / 255f) * (right - left);
        drawKnob(canvas, ax, alphaTop + barH / 2f);
    }

    private void drawHueBar(Canvas c, float l, float t, float r, float b) {
        barPaint.setShader(null);
        int[] colors = new int[]{
                0xFFFF0000, 0xFFFFFF00, 0xFF00FF00,
                0xFF00FFFF, 0xFF0000FF, 0xFFFF00FF, 0xFFFF0000};
        Shader s = new LinearGradient(l, 0, r, 0, colors, null, Shader.TileMode.CLAMP);
        barPaint.setShader(s);
        c.drawRoundRect(l, t, r, b, 4 * d, 4 * d, barPaint);
    }

    private void drawAlphaBar(Canvas c, float l, float t, float r, float b) {
        int solid = Color.HSVToColor(hsv);
        Shader s = new LinearGradient(l, 0, r, 0, 0x00000000, solid, Shader.TileMode.CLAMP);
        barPaint.setShader(s);
        c.drawRoundRect(l, t, r, b, 4 * d, 4 * d, barPaint);
    }

    private void drawKnob(Canvas c, float x, float y) {
        knobPaint.setStyle(Paint.Style.STROKE);
        c.drawCircle(x, y, 7 * d, knobPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        float barH = BAR_H_DP * d;
        float gap = GAP_DP * d;
        float left = getPaddingLeft();
        float right = getWidth() - getPaddingRight();
        float top = getPaddingTop();
        float alphaTop = getHeight() - getPaddingBottom() - barH;
        float hueTop = alphaTop - gap - barH;
        float svBottom = hueTop - gap;

        float x = clamp(e.getX(), left, right);
        float y = e.getY();

        if (y <= svBottom) {
            // SV 平面
            hsv[1] = (x - left) / Math.max(1f, right - left);
            hsv[2] = 1f - clamp((y - top) / Math.max(1f, svBottom - top), 0f, 1f);
        } else if (y <= hueTop + barH) {
            // 色相
            hsv[0] = clamp((x - left) / Math.max(1f, right - left), 0f, 1f) * 360f;
            svBitmap = null;   // 色相影响 SV 底色，缓存作废
        } else {
            // 透明度
            alpha = Math.round(clamp((x - left) / Math.max(1f, right - left), 0f, 1f) * 255f);
        }

        invalidate();
        if (listener != null) listener.onColorChanged(getColor());

        if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
            getParent().requestDisallowInterceptTouchEvent(true);
        }
        return true;
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
