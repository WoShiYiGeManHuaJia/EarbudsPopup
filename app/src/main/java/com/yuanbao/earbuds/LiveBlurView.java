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

    // ---- stackBlur 的缓冲区：全部缓存复用 ----
    // 之前每帧都在 stackBlur 里 new int[w*h] / int[256*divsum] 等，
    // 单次约 500KB，逐帧执行会疯狂 GC，直接把 App 撑崩（测试弹窗闪退）。
    // 现在改为尺寸变化时才重建。
    private int[] bufPix;
    private int[] bufR, bufG, bufB, bufA;
    private int[] bufVmin;
    private int[] bufDv;
    private int[][] bufStack;
    private int bufW = -1, bufH = -1;

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
            // 方向修正（之前正好写反了，所以看不到过渡）：
            //   本 View 覆盖的是【底部文字区】，它盖在清晰动画之上。
            //   y=0（上边缘）→ alpha=255 全擦除 → 透明 → 露出清晰的动画
            //   y=fh（往下） → alpha=0   不擦除  → 保留模糊层
            // 于是顶边柔和地融进动画，越往下越模糊。
            fadeGradient = new android.graphics.LinearGradient(
                    0f, 0f, 0f, fh,
                    0xFF000000, 0x00000000, Shader.TileMode.CLAMP);
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

    /** 最小重绘间隔（ms）：把模糊重算限制在 ~30fps，降低 CPU 占用 */
    private static final long MIN_REDRAW_MS = 33L;
    private long lastDrawAt = 0L;

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
                long now = android.os.SystemClock.uptimeMillis();
                if (now - lastDrawAt >= MIN_REDRAW_MS) {
                    lastDrawAt = now;
                    invalidate();
                }
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
        // 兜底：模糊属于视觉效果，任何异常都只能退化成纯色，
        // 绝不能向上抛出把弹窗 / 整个 App 带崩（之前就是这里闪退）。
        try {
            drawBlur(canvas);
        } catch (Throwable t) {
            // 退化：画一层半透明底色，保证文字仍可读
            try {
                canvas.drawColor(dimColor);
            } catch (Throwable ignored) {
            }
        }
    }

    private void drawBlur(Canvas canvas) {
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

        // 始终自行模糊（原因见 applyEffect 注释）

        if (hwBlurEnabled) {
            // API 31+：直接画源内容，RenderEffect 在合成阶段逐帧做 GPU 模糊
            drawSource(canvas, dr, sw, sh, vh);
        } else {
            drawManualBlur(canvas, dr, sw, sh, vw, vh);
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
                // 只做擦除，不画内容。
                //
                // 关键修正：之前在图层里又画了一遍「清晰原图」，
                // 但 RenderEffect 会模糊整个 View 的渲染输出，
                // 那层原图同样被糊掉 —— 于是顶部并不清晰，过渡失效。
                //
                // 正确做法：本 View 只负责提供「模糊层」，
                // 顶部用 DST_OUT 擦成透明，直接露出【下面那张清晰的 ImageView】。
                // 这样顶部=清晰原图，底部=模糊层，中间渐变过渡。
                int saved = canvas.saveLayer(0f, 0f, vw, fh, null);
                canvas.drawRect(0f, 0f, vw, fh, fadePaint);
                canvas.restoreToCount(saved);
            }
        }
    }

    /** 按需分配（并复用）stackBlur 所需的缓冲区 */
    private void ensureBlurBuffers(int w, int h) {
        if (bufW == w && bufH == h && bufPix != null) return;
        bufW = w;
        bufH = h;
        int wh = w * h;
        bufPix = new int[wh];
        bufR = new int[wh];
        bufG = new int[wh];
        bufB = new int[wh];
        bufA = new int[wh];
        bufVmin = new int[Math.max(w, h)];
    }

    /**
     * StackBlur（Mario Klingemann 算法）：接近高斯模糊，速度远快于逐像素卷积。
     * 在小图上执行，开销很低，适合逐帧调用。
     */
    private void stackBlur(Bitmap bmp, int radius) {
        int w = bmp.getWidth();
        int h = bmp.getHeight();
        if (w <= 0 || h <= 0 || radius <= 0) return;

        ensureBlurBuffers(w, h);
        int[] pix = bufPix;
        bmp.getPixels(pix, 0, w, 0, 0, w, h);

        int wm = w - 1;
        int hm = h - 1;
        int wh = w * h;
        int div = radius + radius + 1;

        int[] r = bufR, g = bufG, b = bufB, a = bufA;
        int rsum, gsum, bsum, asum, x, y, i, p, yp, yi, yw;

        int[] vmin = bufVmin;
        int divsum = (div + 1) >> 1;
        divsum *= divsum;
        int[] dv = bufDv;
        if (dv.length < 256 * divsum) {
            dv = new int[256 * divsum];
            bufDv = dv;
        }
        for (i = 0; i < 256 * divsum; i++) dv[i] = (i / divsum);

        yw = yi = 0;

        int[][] stack = bufStack;
        if (stack == null || stack.length < div) {
            stack = new int[Math.max(div, 8)][4];
            bufStack = stack;
        }
        int stackpointer;
        int stackstart;
        int[] sir;
        int rbs;
        int r1 = radius + 1;
        int routsum, goutsum, boutsum, aoutsum;
        int rinsum, ginsum, binsum, ainsum;

        for (y = 0; y < h; y++) {
            rinsum = ginsum = binsum = ainsum = 0;
            routsum = goutsum = boutsum = aoutsum = 0;
            rsum = gsum = bsum = asum = 0;
            for (i = -radius; i <= radius; i++) {
                p = pix[yi + Math.min(wm, Math.max(i, 0))];
                sir = stack[i + radius];
                sir[0] = (p & 0xff0000) >> 16;
                sir[1] = (p & 0x00ff00) >> 8;
                sir[2] = (p & 0x0000ff);
                sir[3] = (p & 0xff000000) >>> 24;
                rbs = r1 - Math.abs(i);
                rsum += sir[0] * rbs;
                gsum += sir[1] * rbs;
                bsum += sir[2] * rbs;
                asum += sir[3] * rbs;
                if (i > 0) {
                    rinsum += sir[0];
                    ginsum += sir[1];
                    binsum += sir[2];
                    ainsum += sir[3];
                } else {
                    routsum += sir[0];
                    goutsum += sir[1];
                    boutsum += sir[2];
                    aoutsum += sir[3];
                }
            }
            stackpointer = radius;
            for (x = 0; x < w; x++) {
                r[yi] = dv[rsum];
                g[yi] = dv[gsum];
                b[yi] = dv[bsum];
                a[yi] = dv[asum];

                rsum -= routsum;
                gsum -= goutsum;
                bsum -= boutsum;
                asum -= aoutsum;

                stackstart = stackpointer - radius + div;
                sir = stack[stackstart % div];

                routsum -= sir[0];
                goutsum -= sir[1];
                boutsum -= sir[2];
                aoutsum -= sir[3];

                if (x == 0) vmin[y] = Math.min(y + r1, hm) * w;
                p = pix[yw + vmin[y]];

                sir[0] = (p & 0xff0000) >> 16;
                sir[1] = (p & 0x00ff00) >> 8;
                sir[2] = (p & 0x0000ff);
                sir[3] = (p & 0xff000000) >>> 24;

                rinsum += sir[0];
                ginsum += sir[1];
                binsum += sir[2];
                ainsum += sir[3];

                rsum += rinsum;
                gsum += ginsum;
                bsum += binsum;
                asum += ainsum;

                stackpointer = (stackpointer + 1) % div;
                sir = stack[(stackpointer) % div];

                routsum += sir[0];
                goutsum += sir[1];
                boutsum += sir[2];
                aoutsum += sir[3];

                rinsum -= sir[0];
                ginsum -= sir[1];
                binsum -= sir[2];
                ainsum -= sir[3];

                yi++;
            }
            yw += w;
        }

        for (x = 0; x < w; x++) {
            rinsum = ginsum = binsum = ainsum = 0;
            routsum = goutsum = boutsum = aoutsum = 0;
            rsum = gsum = bsum = asum = 0;
            yp = -radius * w;
            for (i = -radius; i <= radius; i++) {
                yi = Math.max(0, yp) + x;
                sir = stack[i + radius];
                sir[0] = r[yi];
                sir[1] = g[yi];
                sir[2] = b[yi];
                sir[3] = a[yi];
                rbs = r1 - Math.abs(i);
                rsum += r[yi] * rbs;
                gsum += g[yi] * rbs;
                bsum += b[yi] * rbs;
                asum += a[yi] * rbs;
                if (i > 0) {
                    rinsum += sir[0];
                    ginsum += sir[1];
                    binsum += sir[2];
                    ainsum += sir[3];
                } else {
                    routsum += sir[0];
                    goutsum += sir[1];
                    boutsum += sir[2];
                    aoutsum += sir[3];
                }
                if (i < hm) yp += w;
            }
            yi = x;
            stackpointer = radius;
            for (y = 0; y < h; y++) {
                pix[yi] = (dv[asum] << 24) | (dv[rsum] << 16) | (dv[gsum] << 8) | dv[bsum];

                rsum -= routsum;
                gsum -= goutsum;
                bsum -= boutsum;
                asum -= aoutsum;

                stackstart = stackpointer - radius + div;
                sir = stack[stackstart % div];

                routsum -= sir[0];
                goutsum -= sir[1];
                boutsum -= sir[2];
                aoutsum -= sir[3];

                if (x == 0) vmin[y] = Math.min(y + r1, hm) * w;

                p = x + vmin[y];
                sir[0] = r[p];
                sir[1] = g[p];
                sir[2] = b[p];
                sir[3] = a[p];

                rinsum += sir[0];
                ginsum += sir[1];
                binsum += sir[2];
                ainsum += sir[3];

                rsum += rinsum;
                gsum += ginsum;
                bsum += binsum;
                asum += ainsum;

                stackpointer = (stackpointer + 1) % div;
                sir = stack[stackpointer % div];

                routsum += sir[0];
                goutsum += sir[1];
                boutsum += sir[2];
                aoutsum += sir[3];

                rinsum -= sir[0];
                ginsum -= sir[1];
                binsum -= sir[2];
                ainsum -= sir[3];

                yi += w;
            }
        }
        bmp.setPixels(pix, 0, w, 0, 0, w, h);
    }

    /**
     * 手动模糊（RenderEffect 不可用时的兜底）：
     * 降采样 → StackBlur → 放大。小图像素少，逐帧开销可控。
     */
    private void drawManualBlur(Canvas canvas, Drawable dr, int sw, int sh,
                                int vw, int vh) {
        int factor = 4;
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

        int radius = Math.max(4, Math.min(16, Math.round(blurRadiusPx / 5f)));
        try {
            stackBlur(smallBuf, radius);
        } catch (Throwable t) {
            // StackBlur 出错也不能让弹窗崩，退化成纯降采样
        }

        canvas.save();
        canvas.scale(factor, factor);
        canvas.drawBitmap(smallBuf, 0, 0, upscalePaint);
        canvas.restore();
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
