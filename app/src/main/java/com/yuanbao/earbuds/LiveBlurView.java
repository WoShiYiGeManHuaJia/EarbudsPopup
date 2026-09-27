package com.yuanbao.earbuds;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Outline;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewOutlineProvider;
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

    /** 底部两角圆角半径（px）。模糊层贴在卡片底部，两角必须跟卡片一致 */
    private float bottomRadiusPx = 0f;
    private final Paint cornerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private android.graphics.Path cornerPath;

    /** 降采样缓冲（小图，做 StackBlur 用） */
    private Bitmap smallBuf;
    private Canvas smallCanvas;
    /** 放大回原尺寸的模糊位图，供 BitmapShader 填充圆角形状 */
    private Bitmap outBuf;
    private Canvas outCanvas;
    private final Paint upscalePaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    /** 填充圆角形状的画笔（带 BitmapShader） */
    private final Paint shaderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** 压暗画笔，保证文字可读 */
    private final Paint dimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

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
        dimPaint.setColor(argb);
        invalidate();
    }

    /** 设置底部两角圆角半径（px），与卡片圆角保持一致 */
    public void setBottomCornerRadius(float px) {
        this.bottomRadiusPx = Math.max(0f, px);
        cornerPath = null;   // 半径变了要重建路径
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        // 关键：setBottomCornerRadius 在布局前被调用，那时 getWidth()==0，
        // outline 是空的 —— 裁剪形同虚设，这就是圆角一直不生效的原因。
        // 尺寸确定后必须重算 outline。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP
                && bottomRadiusPx > 0f) {
            invalidateOutline();
        }
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        if (changed && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP
                && bottomRadiusPx > 0f) {
            invalidateOutline();
        }
    }

    /** 构建底部两角外侧需要擦除的路径（方块减圆的差集） */
    private void ensureCornerPath(int w, int h) {
        if (bottomRadiusPx <= 0f || w <= 0 || h <= 0) {
            cornerPath = null;
            return;
        }
        if (cornerPath != null) return;
        float r = Math.min(bottomRadiusPx, Math.min(w, h) / 2f);

        // 底部两角圆角、顶部两角直角的矩形路径。
        // 配合 DST_IN：圆角外的角落内容被裁掉。
        android.graphics.Path p = new android.graphics.Path();
        p.moveTo(0f, 0f);
        p.lineTo(w, 0f);
        p.lineTo(w, h - r);
        p.arcTo(new android.graphics.RectF(w - 2 * r, h - 2 * r, w, h), 0f, 90f);
        p.lineTo(r, h);
        p.arcTo(new android.graphics.RectF(0f, h - 2 * r, 2 * r, h), 90f, 90f);
        p.close();

        cornerPath = p;
        cornerPaint.setXfermode(new android.graphics.PorterDuffXfermode(
                android.graphics.PorterDuff.Mode.DST_IN));
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
        // 彻底禁用 RenderEffect。
        //
        // 原因：RenderEffect 与 clipToOutline 在【alpha 动画期间】互相干扰。
        // 本 View 要做 alpha 0→1 淡入，Android 为带 alpha 的 View 走独立
        // layer 渲染路径，此时 outline 裁剪时有时无 ——
        // 表现为「一瞬间有圆角，下一瞬间又没了」。
        //
        // 关掉后走手动 StackBlur（真高斯，不是降采样马赛克），
        // 圆角改在 onDraw 里用 Xfermode 裁，稳定可控。
        hwBlurEnabled = false;
        try {
            setRenderEffect(null);
        } catch (Throwable ignored) {
        }
        setClipToOutline(false);
    }

    /**
     * 模糊层【自身】的底部两角圆角。
     *
     * 之前一直想让父容器 RoundedCardLayout 的 clipToOutline 来裁，
     * 但设了 RenderEffect 的 View 走独立渲染节点，会绕过父容器的裁剪。
     *
     * 所以改成 View 自己裁剪自己的输出：
     *   outline 用一个【向上延伸】的圆角矩形 ——
     *   顶部两角的圆弧落在 View 边界之外（y<0），因此顶边保持直角；
     *   底部两角正常圆角。
     * View 自身的 clipToOutline 作用于它自己的渲染结果，
     * 包含 RenderEffect 的产物，所以圆角不会被糊平。
     */
    private void applyCornerClip() {
        // outline 裁剪已弃用（与 alpha 动画/RenderEffect 冲突），
        // 圆角改由 onDraw 内的 Xfermode 处理，这里不再开启裁剪。
        if (false && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            setClipToOutline(true);
            setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    float r = bottomRadiusPx;
                    if (r <= 0f) {
                        outline.setRect(0, 0, view.getWidth(), view.getHeight());
                        return;
                    }
                    // 向上延伸 r：顶边成直角，只保留底部两角
                    outline.setRoundRect(0, (int) -r, view.getWidth(),
                            view.getHeight(), r);
                }
            });
            invalidateOutline();
        }
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

        // ===== 圆角方案：形状填充（不再用 Xfermode） =====
        //
        // 之前几版都是「先画满内容 → 再用 DST_IN / DST_OUT 擦出圆角」。
        // 但 Xfermode 在硬件加速 + saveLayer 下不可靠（RenderNode、
        // alpha layer、RenderEffect 都会干扰），所以圆角时有时无。
        //
        // 现在反过来：先生成模糊位图 → 构造圆角形状 → 用 BitmapShader
        // 把位图填充进形状。形状自带圆角，drawPath 带抗锯齿，
        // 圆角必然生效、边缘平滑，不依赖任何合成模式。
        Bitmap blurred = renderBlurredBitmap(dr, sw, sh, vw, vh);
        if (blurred == null) {
            canvas.drawColor(dimColor);
            return;
        }

        ensureCornerPath(vw, vh);
        shaderPaint.setShader(new android.graphics.BitmapShader(
                blurred, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
        shaderPaint.setAntiAlias(true);
        canvas.drawPath(cornerPath, shaderPaint);

        // 半透明压暗，保证文字可读
        canvas.drawPath(cornerPath, dimPaint);

        // ---- 顶部渐隐：擦掉上沿，露出下面清晰的动画 ----
        if (fadeRatio > 0f) {
            float fh = vh * fadeRatio;
            ensureGradient(vw, vh);
            if (fadeGradient != null && fh > 0f) {
                int saved = canvas.saveLayer(0f, 0f, vw, fh, null);
                canvas.drawRect(0f, 0f, vw, fh, fadePaint);
                canvas.restoreToCount(saved);
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
                    h.postDelayed(what, when - android.os.SystemClock.uptimeMillis());
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
    /**
     * 生成模糊位图（与 View 同尺寸，已模糊）。
     *
     * 降采样倍数用 2（不是 4）：4 倍会把像素块拉得太大，
     * 放大回来就是明显的马赛克；2 倍保留更多细节。
     */
    private Bitmap renderBlurredBitmap(Drawable dr, int sw, int sh,
                                       int vw, int vh) {
        int factor = 2;
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

        // 半径按小图尺寸动态算：固定上限在高分屏上糊得不够，
        // 固定下限又成了马赛克，所以取「不小于 6、不超过短边 1/3」。
        int radius = Math.round(blurRadiusPx / 2f);
        radius = Math.max(6, Math.min(Math.min(bw, bh) / 3, radius));
        try {
            stackBlur(smallBuf, radius);
        } catch (Throwable t) {
            try {
                stackBlur(smallBuf, 6);
            } catch (Throwable ignored) {
                // 都不行就保留降采样结果，至少不是纯色
            }
        }

        if (outBuf == null || outBuf.getWidth() != vw
                || outBuf.getHeight() != vh) {
            outBuf = Bitmap.createBitmap(vw, vh, Bitmap.Config.ARGB_8888);
            outCanvas = new Canvas(outBuf);
        }
        outBuf.eraseColor(Color.TRANSPARENT);
        outCanvas.save();
        outCanvas.scale(factor, factor);
        outCanvas.drawBitmap(smallBuf, 0, 0, upscalePaint);
        outCanvas.restore();
        return outBuf;
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

        // 半径足够大才是真模糊；太小就成了马赛克。
        int radius = Math.max(8, Math.min(28, Math.round(blurRadiusPx / 4f)));
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
