package com.yuanbao.earbuds;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import com.bumptech.glide.Glide;

/**
 * 弹窗渲染（悬浮窗引擎与系统级 Activity 引擎共用）。
 *
 * 排版（类小米 / realme）：
 *   - 媒体区铺满整张卡片，不预留空白条
 *   - 详情区叠在卡片底部（overlay），不占位
 *   - 四角圆角由 RoundedCardLayout 统一合成（顶部两角也是圆角，抗锯齿）
 *
 * 动画节奏：
 *   第 1 段：整张卡片从下往上入场（340ms）
 *   第 2 段：1 秒后，把详情区【所在那一块】做模糊，再淡入文字（420ms）
 *           —— 详情区不参与布局占位，所以从头到尾不触发重新测量，
 *              GIF 全程连续播放，既不中断也不重置。
 */
public final class PopupRenderer {

    public static final int ANIM_SCALE = 0;
    public static final int ANIM_BOTTOM = 1;
    public static final int ANIM_TOP = 2;

    /** 详情区延迟出现时间 */
    private static final long DETAIL_DELAY_MS = 1000L;
    private static final long DETAIL_RISE_MS = 420L;

    private static final Handler main = new Handler(Looper.getMainLooper());

    public interface OnClose {
        void close();
    }

    private PopupRenderer() {
    }

    public static void bind(Context c, View root, String rawName,
                            BatteryLevels levels,
                            Prefs prefs, OnClose onClose) {
        final RoundedCardLayout card = root.findViewById(R.id.card);
        final FrameLayout gifWrap = root.findViewById(R.id.gifWrap);
        final RoundedImageView img = root.findViewById(R.id.popupImage);
        View shimmer = root.findViewById(R.id.shimmer);
        View gradientFade = root.findViewById(R.id.gradientFade);
        final View detailArea = root.findViewById(R.id.detailArea);
        final LiveBlurView detailBlurBg = root.findViewById(R.id.detailBlurBg);
        TextView tvDeviceName = root.findViewById(R.id.tvDeviceName);
        TextView tvBattery = root.findViewById(R.id.tvBattery);
        TextView tipText = root.findViewById(R.id.tipText);

        float d = c.getResources().getDisplayMetrics().density;

        // ---------- 卡片尺寸 ----------
        int widthPx = (int) (prefs.widthDp() * d);
        // 比例依据：小米官方自定义弹窗背景时建议图片比例 16:9（横向宽扁）。
        // 之前 cardRatio 约 1.28（竖高），所以看起来「长不长圆不圆方不方」。
        // 这里改成 0.58~0.76 的横向区间，默认约 0.72：接近 16:9 稍高一点，
        // 好容纳底部叠加的详情区。
        float ratio = Math.max(0.40f, Math.min(0.94f, prefs.imageRatio()));
        float cardRatio = 0.45f + ratio * 0.33f;
        int heightPx = (int) (widthPx * cardRatio);

        int cardBg = parseColor(
                prefs.autoColor() ? prefs.autoBgColor() : prefs.bgColor(), 0x1FFFFFFF);

        if (card != null) {
            ViewGroup.LayoutParams lp = card.getLayoutParams();
            lp.width = widthPx;
            lp.height = heightPx;
            card.setLayoutParams(lp);
            // 关键：由卡片自己画底色 + 统一圆角合成，四角（含顶部）都是抗锯齿圆角
            card.setCardStyle(prefs.radiusDp() * d, cardBg, 0x33FFFFFF, Math.max(1f, d));
            card.setElevation(18 * d);
        }

        // ---------- 底部渐变层：停用 ----------
        // 这个 96dp 的渐变层（cardBg → 透明）叠在媒体区底部，
        // 而 LiveBlurView 现在自己就带渐隐过渡，两者叠加会在底部
        // 形成一条很宽的「灰色带」—— 这就是一直被吐槽的「加了一层灰」。
        // 过渡交给 LiveBlurView 的 fadeRatio 就够了，这里直接隐藏。
        if (gradientFade != null) gradientFade.setVisibility(View.GONE);

        int textColor = parseColor(prefs.textColor(), Color.WHITE);

        String deviceName = prettyName(rawName);
        String head = fill(prefs.titleText(), deviceName);
        // 文字分档配色：没单独设过就退回通用文字色
        int titleC = resolveColor(prefs.titleColor(), textColor);
        int statusC = resolveColor(prefs.statusColor(), textColor);
        int battC = resolveColor(prefs.batteryColor(), textColor);

        if (tvDeviceName != null) {
            tvDeviceName.setText(head.isEmpty() ? deviceName : head);
            tvDeviceName.setTextColor(titleC);
        }
        if (tipText != null) tipText.setTextColor(applyAlpha(statusC, 0.6f));

        // ---------- 电量（带图标） ----------
        if (levels != null) levels.sanitize();
        int sub = applyAlpha(battC, 0.95f);
        // 读不到真实电量的一侧，不再摆一个「--%」占位文字，
        // 改成让对应耳塞图标轻微上下浮动，表示「还在等真实读数」。
        // 原则不变：绝不拿猜出来的数字冒充真实电量。
        boolean waitL = levels == null || !BatteryLevels.valid(levels.left);
        boolean waitR = levels == null || !BatteryLevels.valid(levels.right);

        if (tvBattery != null) {
            tvBattery.setVisibility(waitL ? View.GONE : View.VISIBLE);
            if (!waitL) {
                tvBattery.setText(batteryText(levels));
                tvBattery.setTextColor(sub);
            }
        }
        TextView tvBatteryRight = root.findViewById(R.id.tvBatteryRight);
        if (tvBatteryRight != null) {
            tvBatteryRight.setVisibility(waitR ? View.GONE : View.VISIBLE);
            if (!waitR) {
                tvBatteryRight.setText(batteryTextRight(levels));
                tvBatteryRight.setTextColor(sub);
            }
        }
        // 充电盒槽位已从布局里【整个移除】：
        // 系统蓝牙栈 untethered_case_battery=null，该值根本取不到，
        // 留着只会一直显示 --% 很难看。
        ImageView icEarbuds = root.findViewById(R.id.icEarbuds);
        if (icEarbuds != null) {
            icEarbuds.setColorFilter(sub);
            if (waitL) startFloat(icEarbuds);
            else stopFloat(icEarbuds);
        }
        ImageView icEarbudsRight = root.findViewById(R.id.icEarbudsRight);
        if (icEarbudsRight != null) {
            icEarbudsRight.setColorFilter(sub);
            if (waitR) startFloat(icEarbudsRight);
            else stopFloat(icEarbudsRight);
        }
        // 左右耳塞图标：右耳镜像翻转，形成「一对」
        // 左右图标是从系统状态栏截图里分别提取的，本身形态就不同，
        // 不需要再镜像翻转（之前的 vector 是同一个图翻转的假「一对」）

        // ---------- 媒体区 ----------
        if (gifWrap != null) gifWrap.setBackground(null);
        String uri = prefs.imageUri();
        if (img != null) {
            img.setScaleType(ImageView.ScaleType.MATRIX);
            // 半径与卡片完全一致：图片自身的抗锯齿圆角正好落在卡片裁剪边界上，
            // 既不内缩露直角，也不外溢。
            img.setRadius(prefs.radiusDp() * d);
            if (!uri.isEmpty()) {
                try {
                    Glide.with(c.getApplicationContext())
                            .load(Uri.parse(uri))
                            .dontTransform()
                            .into(new com.bumptech.glide.request.target.CustomViewTarget<ImageView,
                                    Drawable>(img) {
                                @Override
                                public void onResourceReady(
                                        Drawable resource,
                                        com.bumptech.glide.request.transition.Transition<? super
                                                Drawable> transition) {
                                    img.setImageDrawable(resource);
                                    applyImageMatrix(img);
                                    restartGif(resource);
                                }

                                @Override
                                public void onLoadFailed(Drawable errorDrawable) {
                                    img.setImageResource(R.drawable.ic_headphone);
                                    applyImageMatrix(img);
                                }

                                @Override
                                protected void onResourceCleared(Drawable placeholder) {
                                }
                            });
                } catch (Exception e) {
                    img.setImageResource(R.drawable.ic_headphone);
                    applyImageMatrix(img);
                }
            } else {
                img.setImageResource(R.drawable.ic_headphone);
                applyImageMatrix(img);
            }
        }

        if (shimmer != null && card != null) {
            startShimmer(c, shimmer, widthPx);
        }

        // ---------- 点击任意位置关闭 ----------
        if (card != null) {
            card.setOnClickListener(v -> {
                if (onClose != null) onClose.close();
            });
        }
        root.setOnClickListener(v -> {
            if (onClose != null) onClose.close();
        });

        SoundPlayer.play(c, prefs);

        // ---------- 入场：从下往上 ----------
        if (card != null) {
            applyEnter(card, prefs.animStyle(),
                    prefs == null ? 340 : prefs.enterAnimMs());
        }

        // ---------- 第 2 段：1 秒后，模糊 + 文字【一起】淡入上移 ----------
        //
        // 之前：模糊层在 1 秒那一刻直接 setVisibility(VISIBLE) 瞬间出现，
        //       只有文字做淡入动画 —— 用户看到的就是「模糊一直在，文字才弹出来」。
        // 现在：模糊层与文字用【完全相同】的 alpha + translationY 动画，
        //       两者作为一个整体同时浮现。
        if (detailArea != null && card != null) {
            detailArea.animate().cancel();
            detailArea.setAlpha(0f);
            detailArea.setTranslationY(18 * d);

            // 模糊层同样先藏起来（VISIBLE 但透明 + 下移）
            if (detailBlurBg != null) {
                detailBlurBg.animate().cancel();
                detailBlurBg.setAlpha(0f);
                detailBlurBg.setTranslationY(18 * d);
            }

            main.postDelayed(() -> {
                // 模糊失败也必须让文字出现，否则用户看到的是"没有文字"
                try {
                    setupLiveBlur(detailBlurBg, img, cardBg, prefs.radiusDp(), detailArea);
                } catch (Throwable ignored) {
                }
                // 绑定后重新归零：setupLiveBlur 只改内容，不改动画属性，
                // 但保险起见再设一次，避免中途被别处改动。
                if (detailBlurBg != null) {
                    detailBlurBg.setAlpha(0f);
                    detailBlurBg.setTranslationY(18 * d);
                }

                DecelerateInterpolator interpol = new DecelerateInterpolator();

                // 文字
                detailArea.animate()
                        .alpha(1f)
                        .translationY(0f)
                        .setDuration(DETAIL_RISE_MS)
                        .setInterpolator(interpol)
                        .start();

                // 模糊：同一时长、同一插值器、同一位移 → 与文字同步浮现
                if (detailBlurBg != null) {
                    detailBlurBg.animate()
                            .alpha(1f)
                            .translationY(0f)
                            .setDuration(DETAIL_RISE_MS)
                            .setInterpolator(interpol)
                            .start();
                }
            }, DETAIL_DELAY_MS);
        }
    }

    /**
     * 让详情区背景变成【实时动态模糊】。
     *
     * 之前是 card.draw() 截一帧再降采样 —— 那是静态截图，
     * GIF 在动而背景不动，所以看起来很假。
     *
     * 现在把模糊层直接绑到源 ImageView 的同一个 Drawable 上：
     *   源每前进一帧 → 回调转发 → 模糊层 invalidate → 重新绘制并模糊。
     * 背景与动画始终同步，是真正的动态模糊。
     */
    private static void setupLiveBlur(LiveBlurView blur, ImageView img,
                                       int cardBg, float radiusDp,
                                       View detailArea) {
        if (blur == null || img == null) return;
        final float d = blur.getResources().getDisplayMetrics().density;

        //
        // 模糊层高度必须【跟着文字区实测高度】走，不能写死。
        //
        // 布局里写死 52dp，而 detailArea 是 wrap_content，实测约 59dp
        // （paddingBottom 11 + 电量行 18 + marginTop 5 + 设备名 21 + paddingTop 4）。
        // 两者都贴底对齐 → 模糊层比文字矮约 7dp → 设备名顶部那几 dp
        // 落在模糊区之外，压在清晰画面上，所以「上半截看不清」。
        //
        // 为什么必须 post：此时 detailArea 还没走完 layout，
        // getHeight() 直接取会是 0。
        //
        final int extraPx = (int) (18f * d);   // 文字区之上额外留的渐隐过渡带
        if (detailArea != null) {
            detailArea.post(() -> {
                int h = detailArea.getHeight();
                if (h <= 0) return;
                int total = h + extraPx;
                ViewGroup.LayoutParams lp = blur.getLayoutParams();
                if (lp != null && lp.height != total) {
                    lp.height = total;
                    blur.setLayoutParams(lp);
                }
                // 渐隐只占「额外那一段」，保证文字区全域是完全模糊。
                // 之前写死 0.50，加高后渐隐过长会反噬到文字区。
                blur.setFadeRatio((float) extraPx / (float) total);
            });
        } else {
            blur.setFadeRatio(0.25f);
        }

        blur.setBlurRadius(26f * d);
        // 之前 dim 给到 0.45，几乎把模糊层盖成一层灰 —— 用户看到的
        // 「直接加一层灰」就是它。降到 0.20，只做轻微压暗保证文字可读，
        // 让模糊本身成为主体。
        // 压暗色：之前用 applyAlpha(cardBg, 0.20)，cardBg 在自动取色下
        // 可能是很淡甚至接近透明的色，叠上去等于没叠，
        // 加上模糊区本身内容偏淡，整体看着就「几乎透明」。
        // 改成固定的深色，只做适度压暗，保证文字可读又不会盖住模糊。
        // 30%（原 40%）：压暗只为保证文字可读，太重会把模糊盖成黑块
        blur.setDim(0x4D000000);
        // 底部两角跟随卡片圆角
        blur.setBottomCornerRadius(radiusDp * d);
        blur.setSource(img);
        // 布局里是 gone + 76dp，这里才显示。
        // 高度由布局写死，不再依赖测量，避免撑高父容器把整卡糊掉。
        blur.setVisibility(View.VISIBLE);
    }

    /**
     * 耳机（左/右）电量文本。
     * 用户要求左右耳分开显示，所以即便两值相同也照实分列，
     * 读不到就显示 --%，不再合并成一个数字。
     */
    /**
     * 耳塞图标浮动：表示「这一侧还没拿到真实电量」。
     * 用 tag 去重，避免多次刷新把动画叠成一团。
     */
    private static void startFloat(final View v) {
        if (v == null) return;
        if (v.getTag(R.id.tag_float_anim) != null) return;   // 已经在浮动
        v.setTag(R.id.tag_float_anim, Boolean.TRUE);
        v.animate().cancel();
        v.setAlpha(1f);
        v.setTranslationY(0f);
        final float dy = -4f * v.getResources().getDisplayMetrics().density;
        final android.view.animation.AccelerateDecelerateInterpolator interp =
                new android.view.animation.AccelerateDecelerateInterpolator();
        v.animate().translationY(dy).alpha(0.62f).setDuration(720).setInterpolator(interp)
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        v.animate().translationY(0f).alpha(1f).setDuration(720)
                                .setInterpolator(interp)
                                .withEndAction(new Runnable() {
                                    @Override
                                    public void run() {
                                        // 仍然处于等待态就继续下一轮
                                        if (v.getTag(R.id.tag_float_anim) != null) startFloat(v);
                                        else stopFloat(v);
                                    }
                                }).start();
                    }
                }).start();
    }

    /** 停止浮动并把图标复位（拿到真实电量时调用） */
    private static void stopFloat(View v) {
        if (v == null) return;
        v.setTag(R.id.tag_float_anim, null);
        v.animate().cancel();
        v.setTranslationY(0f);
        v.setAlpha(1f);
    }

    /**
     * 耳机电量文本。
     *
     * 不能带 "L"/"R" 字样：布局里左右两侧各有【一个耳塞图标】，
     * 图标本身已经区分了左右，再写字母既多余又丑（用户明确要求去掉）。
     * 这里只返回纯数字。
     */
    private static String batteryText(BatteryLevels b) {
        if (b == null) return "--%";
        return BatteryLevels.valid(b.left) ? b.left + "%" : "--%";
    }

    /** 右耳电量文本（左耳用 batteryText） */
    private static String batteryTextRight(BatteryLevels b) {
        if (b == null) return "--%";
        return BatteryLevels.valid(b.right) ? b.right + "%" : "--%";
    }

    public static void applyImageMatrix(ImageView img) {
        if (img == null) return;
        Drawable dr = img.getDrawable();
        int vw = img.getWidth();
        int vh = img.getHeight();
        if (dr == null || vw <= 0 || vh <= 0) {
            Integer tries = (Integer) img.getTag(R.id.tag_matrix_retry);
            int t = (tries == null) ? 0 : tries;
            if (t >= 8) return;
            img.setTag(R.id.tag_matrix_retry, t + 1);
            img.post(() -> {
                if (img.getWidth() > 0 && img.getHeight() > 0) {
                    img.setTag(R.id.tag_matrix_retry, 0);
                }
                applyImageMatrix(img);
            });
            return;
        }
        img.setTag(R.id.tag_matrix_retry, 0);

        int dw = dr.getIntrinsicWidth();
        int dh = dr.getIntrinsicHeight();
        if (dw <= 0 || dh <= 0) dw = dh = 1;

        Prefs prefs = PrefsHolder.get();
        float userScale = prefs == null ? 1f : prefs.imageScale();
        float dx = prefs == null ? 0f : prefs.imageOffsetX();
        float dy = prefs == null ? 0f : prefs.imageOffsetY();
        float rot = prefs == null ? 0f : prefs.imageRotation();
        float density = img.getResources().getDisplayMetrics().density;

        android.graphics.Matrix m = new android.graphics.Matrix();
        float scale = Math.max((float) vw / dw, (float) vh / dh) * userScale;
        m.postScale(scale, scale);
        if (rot != 0f) m.postRotate(rot, dw / 2f, dh / 2f);
        float tx = (vw - dw * scale) / 2f + dx * density;
        float ty = (vh - dh * scale) / 2f + dy * density;
        m.postTranslate(tx, ty);
        img.setImageMatrix(m);
    }

    public static final class PrefsHolder {
        private static volatile Prefs instance;

        public static void init(Context c) {
            if (instance == null) instance = new Prefs(c.getApplicationContext());
        }

        public static Prefs get() {
            return instance;
        }
    }

    private static void restartGif(Drawable d) {
        if (d instanceof com.bumptech.glide.load.resource.gif.GifDrawable) {
            com.bumptech.glide.load.resource.gif.GifDrawable gif =
                    (com.bumptech.glide.load.resource.gif.GifDrawable) d;
            try {
                gif.stop();
                gif.startFromFirstFrame();
                gif.start();
            } catch (Exception ignored) {
            }
        } else if (d instanceof android.graphics.drawable.Animatable) {
            try {
                ((android.graphics.drawable.Animatable) d).stop();
                ((android.graphics.drawable.Animatable) d).start();
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * 卡片当前的目标缩放（横屏缩小弹窗用，竖屏恒为 1）。
     *
     * 入场 / 出场动画里原本写死把 scale 拉回 1f —— 横屏缩放下会把用户设的
     * 缩放系数直接抹掉，表现就是「调了缩放比例没反应」。
     * 动画必须收在这个目标值上，而不是 1。
     */
    public static volatile float cardScale = 1f;

    public static void applyEnter(View card, int style) {
        applyEnter(card, style, 340);
    }

    /**
     * @param ms 入场动画时长。默认 340ms 偏慢，用户反馈「弹窗太慢」，
     *           现在可由设置项控制，默认 180ms —— 卡片更快到位，
     *           电量数字该什么时候出现还是什么时候出现，不受动画影响。
     */
    public static void applyEnter(View card, int style, int ms) {
        if (card == null) return;
        float d = card.getResources().getDisplayMetrics().density;
        card.animate().cancel();
        // 动画时长越短，起始位移也该越小，否则短时间里位移过大看着像闪现
        float shift = 140 * d * (ms / 340f);
        card.setTranslationY(shift);
        card.setAlpha(0.15f);
        // 收在 cardScale（横屏缩小值）上，而不是写死的 1f
        float ts = cardScale;
        if (style == ANIM_SCALE) {
            card.setScaleX(ts * 0.94f);
            card.setScaleY(ts * 0.94f);
            card.animate().translationY(0).scaleX(ts).scaleY(ts).alpha(1f)
                    .setDuration(ms)
                    .setInterpolator(new DecelerateInterpolator())
                    .start();
        } else {
            card.setScaleX(ts);
            card.setScaleY(ts);
            card.animate().translationY(0).alpha(1f)
                    .setDuration(ms)
                    .setInterpolator(new DecelerateInterpolator())
                    .start();
        }
    }

    public static void applyExit(View card, int style, Runnable after) {
        if (card == null) {
            if (after != null) after.run();
            return;
        }
        float d = card.getResources().getDisplayMetrics().density;
        card.animate().cancel();
        android.view.ViewPropertyAnimator a = card.animate()
                .setDuration(260)
                .setInterpolator(new android.view.animation.AccelerateInterpolator())
                .alpha(0f)
                .translationY(80 * d);
        if (style == ANIM_SCALE) a.scaleX(cardScale * 0.94f).scaleY(cardScale * 0.94f);
        if (after != null) a.withEndAction(after);
        a.start();
    }

    /** 只刷新电量文本，不重建弹窗、不打断 GIF */
    public static void updateInfo(View root, String rawName, BatteryLevels levels, Prefs prefs) {
        if (root == null || levels == null) return;
        TextView tvBattery = root.findViewById(R.id.tvBattery);
        TextView tvBatteryRight = root.findViewById(R.id.tvBatteryRight);
        levels.sanitize();
        boolean waitL = !BatteryLevels.valid(levels.left);
        boolean waitR = !BatteryLevels.valid(levels.right);
        if (tvBattery != null) {
            tvBattery.setVisibility(waitL ? View.GONE : View.VISIBLE);
            if (!waitL) tvBattery.setText(batteryText(levels));
        }
        if (tvBatteryRight != null) {
            tvBatteryRight.setVisibility(waitR ? View.GONE : View.VISIBLE);
            if (!waitR) tvBatteryRight.setText(batteryTextRight(levels));
        }
        ImageView icL = root.findViewById(R.id.icEarbuds);
        if (icL != null) {
            if (waitL) startFloat(icL);
            else stopFloat(icL);
        }
        ImageView icR = root.findViewById(R.id.icEarbudsRight);
        if (icR != null) {
            if (waitR) startFloat(icR);
            else stopFloat(icR);
        }
    }

    /** 分档色没设过（空串/解析失败）时退回通用文字色 */
    private static int resolveColor(String hex, int fallback) {
        if (hex == null || hex.trim().isEmpty()) return fallback;
        try {
            return Color.parseColor(hex.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    /**
     * 标题模板填充。
     *
     * 【重要行为变更】设备名必须跟着实际连接的蓝牙设备走。
     *
     * 之前：用户一旦在 APP 里填了名字（比如「菠萝耳机 5 Pro」），
     * 无论连哪个设备都显示这一个名字 —— 换耳机也不变，这就是
     * 「弹窗弹出来还是上一个设备的名字」的根因。
     *
     * 现在：
     *   模板为空           → 直接用蓝牙真实名
     *   模板含 {name}/%s   → 替换成蓝牙真实名
     *   模板不含变量       → 视为固定文字，但用户明确要求名字跟设备走，
     *                        所以这种情况也返回蓝牙真实名
     *
     * 也就是说，除非模板里写了变量，否则永远显示蓝牙名。
     */
    private static String fill(String tpl, String dev) {
        String name = (dev == null || dev.trim().isEmpty()) ? "耳机" : dev.trim();
        if (tpl == null) return name;
        String v = tpl.trim();
        if (v.isEmpty()) return name;
        boolean hasVar = v.contains("{name}") || v.contains("%s");
        if (!hasVar) return name;
        return v.replace("{name}", name).replace("%s", name);
    }

    private static void startShimmer(Context c, View shimmer, int cardWidth) {
        shimmer.setBackgroundResource(R.drawable.bg_shimmer);
        float band = Math.max(24f, cardWidth * 0.22f);
        ViewGroup.LayoutParams lp = shimmer.getLayoutParams();
        if (lp != null) {
            lp.width = (int) band;
            shimmer.setLayoutParams(lp);
        }
        shimmer.setVisibility(View.VISIBLE);
        android.view.animation.TranslateAnimation a =
                new android.view.animation.TranslateAnimation(
                        android.view.animation.Animation.RELATIVE_TO_PARENT, -0.4f,
                        android.view.animation.Animation.RELATIVE_TO_PARENT, 1.0f,
                        android.view.animation.Animation.RELATIVE_TO_SELF, 0f,
                        android.view.animation.Animation.RELATIVE_TO_SELF, 0f);
        a.setDuration(760);
        a.setStartOffset(120);
        a.setAnimationListener(new android.view.animation.Animation.AnimationListener() {
            public void onAnimationStart(android.view.animation.Animation an) {
            }

            public void onAnimationEnd(android.view.animation.Animation an) {
                shimmer.setVisibility(View.GONE);
                shimmer.setBackground(null);
            }

            public void onAnimationRepeat(android.view.animation.Animation an) {
            }
        });
        shimmer.startAnimation(a);
    }

    public static String prettyName(String raw) {
        if (raw == null) return "耳机";
        String n = raw.trim();
        if (n.isEmpty()) return "耳机";
        if (n.matches("^[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}$")) return "耳机";
        return n;
    }

    private static int parseColor(String v, int fallback) {
        try {
            return Color.parseColor(v);
        } catch (Exception e) {
            return fallback;
        }
    }

    private static int applyAlpha(int color, float alpha) {
        int a = Math.round(255 * alpha);
        return (a << 24) | (color & 0x00FFFFFF);
    }
}
