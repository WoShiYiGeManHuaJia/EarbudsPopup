package com.yuanbao.earbuds;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.View;
import android.widget.ImageView;

/**
 * 实时动态模糊层。
 *
 * 【为什么之前的做法不对】
 *   上一版用 card.draw() 截取一帧 → 降采样放大 → 铺成静态 Bitmap。
 *   那是【截图】，不是模糊：GIF 继续在动，背景却停在那一帧，
 *   看起来就是「贴了一张模糊过的死图」，很假。
 *
 * 【现在的做法】
 *   这个 View 直接引用源 ImageView 的同一个 Drawable，
 *   在 onDraw 里把它按偏移画出来（只画底部对应区域），
 *   再叠加实时模糊：
 *     - API 31+：RenderEffect.createBlurEffect()
 *                作用于渲染节点，【每一帧重新计算】，是真正的动态模糊
 *     - API < 31：每帧先画进 1/8 尺寸的 Bitmap 再放大回来
 *                （双线性插值即模糊），也是每帧重算，同样动态
 *
 * 【动画同步】
 *   源 GIF 每帧变化会回调 Drawable.Callback.invalidateDrawable()。
 *   这里接管回调并【转发给原 callback（源 ImageView）】，
 *   同时 invalidate 自己 —— 于是 GIF 每前进一帧，模糊层就跟着重算一次。
 *   直接 setCallback 会顶掉 ImageView 自己的回调导致源不刷新，
 *   所以必须保存原 callback 并转发。
 */
public class LiveBlurView extends View {

    private ImageView src;
    private float blurRadiusPx = 20f;
    private int dimColor = 0x8C000000;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** API 31+ 且 RenderEffect 设置成功的标记（避免直接调高版本方法） */
    private boolean hwBlurEnabled = false;
    private Drawable.Callback originalCallback;
    private Drawable attached;

    /** 顶部渐隐遮罩：让模糊层上沿与上方清晰画面自然过渡，避免一刀切 */
    private final Paint fadePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private android.graphics.LinearGradient fadeGradient;
    /** 渐隐带高度占本 View 高度的比例 */
    private float fadeRatio = 0.45f;

    /** API < 31 时的逐帧降采样模糊缓冲 */
    private Bitmap smallBuf;
    private Canvas smallCanvas;
    private final Paint upscalePaint = new Paint(Paint.FILTER_BITMAP_FLAG);

    public LiveBlurView(Context c) {
        super(c);
    }

    public LiveBlurView(Context c, AttributeSet a) {
        super(c, a);
    }

    public LiveBlurView(Context c, AttributeSet a, int s) {
        super(c, a, s);
    }

    /** 绑定源 ImageView（弹窗里那张图 / GIF） */
    public void setSource(ImageView source) {
        detachCallback();
        this.src = source;
        if (source == null) return;

        Drawable dr = source.getDrawable();
        if (dr != null) attachCallback(dr);

        applyEffect();
        invalidate();
    }

    public void setBlurRadius(float px) {
        this.blurRadiusPx = Math.max(0f, px);
        applyEffect();
        invalidate();
    }

    /** 叠加的半透明色，保证文字可读；argb */
    public void setDim(int argb) {
        this.dimColor = argb;
        invalidate();
    }

    /** 设置顶部渐隐带高度（0 = 不做渐隐，即硬边） */
    public void setFadeRatio(float r) {
        this.fadeRatio = Math.max(0f, Math.min(1f, r));
        fadeGradient = null;
        invalidate();
    }

    /** 按需构建渐变：顶部全透明 → 向下逐渐不透明 */
    private void ensureGradient(int w, int h) {
        if (w <= 0 || h <= 0) return;
        float fh = h * fadeRatio;
        if (fh <= 0f) {
            fadeGradient = null;
            return;
        }
        if (fadeGradient == null) {
            // y=0 处 alpha=0 → 不擦除（保持清晰原图）
            // y=fh 处 alpha=255 → 全部擦除（露出底层的模糊内容）
            fadeGradient = new android.graphics.LinearGradient(
                    0f, 0f, 0f, fh,
                    0x00000000, 0xFF000000, Shader.TileMode.CLAMP);
            fadePaint.setShader(fadeGradient);
            fadePaint.setXfermode(new android.graphics.PorterDuffXfermode(
                    android.graphics.PorterDuff.Mode.DST_OUT));
        }
    }

    private void applyEffect() {
        hwBlurEnabled = false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                setRenderEffect(android.graphics.RenderEffect.createBlurEffect(
                        blurRadiusPx, blurRadiusPx, Shader.TileMode.CLAMP));
                hwBlurEnabled = true;
            } catch (Throwable ignored) {
                // 设备不支持就退回逐帧降采样
            }
        }
    }

    private void attachCallback(Drawable dr) {
        attached = dr;
        originalCallback = dr.getCallback();
        final ImageView source = src;
        final Handler h = new Handler(Looper.getMainLooper());

        dr.setCallback(new Drawable.Callback() {
            @Override
            public void invalidateDrawable(Drawable who) {
                // 关键：先让源继续刷新（否则 GIF 会停），再刷新自己
                if (originalCallback != null) originalCallback.invalidateDrawable(who);
                invalidate();
            }

            @Override
            public void scheduleDrawable(Drawable who, Runnable what, long when) {
                if (originalCallback != null) {
                    originalCallback.scheduleDrawable(who, what, when);
                } else if (source != null) {
                    h.postDelayed(what, when - SystemClock_uptimeMillis());
                }
            }

            @Override
            public void unscheduleDrawable(Drawable who, Runnable what) {
                if (originalCallback != null) {
                    originalCallback.unscheduleDrawable(who, what);
                } else if (source != null) {
                    source.removeCallbacks(what);
                }
            }
        });
    }

    private static long SystemClock_uptimeMillis() {
        return android.os.SystemClock.uptimeMillis();
    }

    private void detachCallback() {
        if (attached != null && originalCallback != null) {
            try {
                attached.setCallback(originalCallback);
            } catch (Exception ignored) {
            }
        }
        attached = null;
        originalCallback = null;
    }

    @Override
    protected void onDetachedFromWindow() {
        detachCallback();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (src == null) return;
        Drawable dr = src.getDrawable();
        if (dr == null) return;

        int sw = src.getWidth();
        int sh = src.getHeight();
        int vw = getWidth();
        int vh = getHeight();
        if (sw <= 0 || sh <= 0 || vw <= 0 || vh <= 0) return;

        // 若绑定后源换了 Drawable（换图/GIF 加载完成），要重新接管回调
        if (dr != attached) {
            detachCallback();
            attachCallback(dr);
        }

        // 用标记判断，不直接调 getRenderEffect()（那是 API 31 方法，
        // 在 minSdk 26 的工程里直接引用会导致编译期找不到符号）
        boolean hwBlur = hwBlurEnabled;

        if (hwBlur) {
            // API 31+：直接画，RenderEffect 会在合成时逐帧模糊
            drawSource(canvas, dr, sw, sh, vh);
        } else {
            // 旧版本：画进小 Bitmap 再放大回来，逐帧执行 → 同样动态
            int factor = 8;
            int bw = Math.max(1, vw / factor);
            int bh = Math.max(1, vh / factor);
            if (smallBuf == null || smallBuf.getWidth() != bw
                    || smallBuf.getHeight() != bh) {
                smallBuf = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
                smallCanvas = new Canvas(smallBuf);
            }
            smallBuf.eraseColor(Color.TRANSPARENT);
            smallCanvas.save();
            smallCanvas.scale(1f / factor, 1f / factor);
            drawSource(smallCanvas, dr, sw, sh, vh);
            smallCanvas.restore();

            canvas.save();
            canvas.scale(factor, factor);
            canvas.drawBitmap(smallBuf, 0, 0, upscalePaint);
            canvas.restore();
        }

        // 叠一层半透明色，保证文字可读
        canvas.drawColor(dimColor);

        // ---- 清晰 → 模糊 的平滑过渡 ----
        //
        // 上一版错在两处：
        //   1) saveLayer 在内容画完之后调用 —— saveLayer 创建的是【空白】离屏层，
        //      已画的内容不在里面，DST_OUT 擦了个寂寞，完全无效。
        //   2) 渐变方向反了（顶部不擦/底部全擦），即便生效也会把底部抹掉。
        //
        // 正确做法：在顶部 fh 区域【先】saveLayer，然后在图层内画一遍清晰原图，
        // 再用 DST_OUT 渐变从上（不擦=保持清晰）到下（全擦=露出底层模糊）。
        // 于是顶部是清晰画面，向下平滑过渡到模糊，没有硬边。
        if (fadeRatio > 0f) {
            float fh = vh * fadeRatio;
            ensureGradient(vw, vh);
            if (fadeGradient != null && fh > 0f) {
                int saved = canvas.saveLayer(0f, 0f, vw, fh, null);
                // 图层内：画清晰源内容（不加模糊）
                drawSource(canvas, dr, sw, sh, vh);
                // 顶部不擦(清晰) → 底部全擦(露出模糊)
                canvas.drawRect(0f, 0f, vw, fh, fadePaint);
                canvas.restoreToCount(saved);
            }
        }
    }

    /**
     * 把源内容画出来，只显示其底部 vh 高度的那一段。
     * 平移 -(sh - vh) 让源的底部对齐到本 View 的位置。
     */
    private void drawSource(Canvas canvas, Drawable dr, int sw, int sh, int vh) {
        canvas.save();
        canvas.translate(0, -(sh - vh));
        try {
            canvas.concat(src.getImageMatrix());
        } catch (Exception ignored) {
        }
        int iw = dr.getIntrinsicWidth();
        int ih = dr.getIntrinsicHeight();
        if (iw <= 0 || ih <= 0) {
            iw = sw;
            ih = sh;
        }
        dr.setBounds(0, 0, iw, ih);
        dr.draw(canvas);
        canvas.restore();
    }
}
