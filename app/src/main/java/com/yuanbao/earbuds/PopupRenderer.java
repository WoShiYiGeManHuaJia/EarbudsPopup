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
        TextView tvCase = root.findViewById(R.id.tvCase);
        TextView tipText = root.findViewById(R.id.tipText);
        ImageView icCase = root.findViewById(R.id.icCase);

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

        // ---------- 底部渐变：透明 → 卡片底色 ----------
        if (gradientFade != null) {
            GradientDrawable fade = new GradientDrawable(
                    GradientDrawable.Orientation.BOTTOM_TOP,
                    new int[]{cardBg, Color.TRANSPARENT});
            gradientFade.setBackground(fade);
        }

        int textColor = parseColor(prefs.textColor(), Color.WHITE);

        String deviceName = prettyName(rawName);
        String head = fill(prefs.titleText(), deviceName);
        if (tvDeviceName != null) {
            tvDeviceName.setText(head.isEmpty() ? deviceName : head);
            tvDeviceName.setTextColor(textColor);
        }
        if (tipText != null) tipText.setTextColor(applyAlpha(textColor, 0.6f));

        // ---------- 电量（带图标） ----------
        if (levels != null) levels.sanitize();
        int sub = applyAlpha(textColor, 0.9f);
        if (tvBattery != null) {
            tvBattery.setText(batteryText(levels));
            tvBattery.setTextColor(sub);
        }
        if (tvCase != null) {
            // 用户要求始终显示充电盒槽位，读不到就显示 --%
            tvCase.setText(levels != null && BatteryLevels.valid(levels.caseBox)
                    ? BatteryLevels.fmt(levels.caseBox) : "--%");
            tvCase.setTextColor(sub);
            tvCase.setVisibility(View.VISIBLE);
        }
        if (icCase != null) icCase.setVisibility(View.VISIBLE);
        if (icCase != null) icCase.setColorFilter(sub);
        ImageView icEarbuds = root.findViewById(R.id.icEarbuds);
        if (icEarbuds != null) icEarbuds.setColorFilter(sub);

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
        if (card != null) applyEnter(card, prefs.animStyle());

        // ---------- 第 2 段：1 秒后模糊 + 详情淡入 ----------
        if (detailArea != null && card != null) {
            detailArea.animate().cancel();
            detailArea.setAlpha(0f);
            detailArea.setTranslationY(18 * d);
            final RoundedCardLayout fCard = card;
            main.postDelayed(() -> {
                setupLiveBlur(detailBlurBg, img, cardBg);
                detailArea.animate()
                        .alpha(1f)
                        .translationY(0f)
                        .setDuration(DETAIL_RISE_MS)
                        .setInterpolator(new DecelerateInterpolator())
                        .start();
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
    private static void setupLiveBlur(LiveBlurView blur, ImageView img, int cardBg) {
        if (blur == null || img == null) return;
        float d = blur.getResources().getDisplayMetrics().density;
        blur.setBlurRadius(22f * d);
        // 叠一层半透明卡片色，保证文字可读
        blur.setDim(applyAlpha(cardBg, 0.45f));
        // 顶部 55% 高度做渐隐，与上方清晰画面平滑过渡（不再一刀切）
        blur.setFadeRatio(0.55f);
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
    private static String batteryText(BatteryLevels b) {
        if (b == null) return "L --%  R --%";
        String l = BatteryLevels.valid(b.left) ? b.left + "%" : "--%";
        String r = BatteryLevels.valid(b.right) ? b.right + "%" : "--%";
        return "L " + l + "  R " + r;
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

    public static void applyEnter(View card, int style) {
        if (card == null) return;
        float d = card.getResources().getDisplayMetrics().density;
        card.animate().cancel();
        card.setTranslationY(140 * d);
        card.setAlpha(0.15f);
        if (style == ANIM_SCALE) {
            card.setScaleX(0.94f);
            card.setScaleY(0.94f);
            card.animate().translationY(0).scaleX(1f).scaleY(1f).alpha(1f)
                    .setDuration(340)
                    .setInterpolator(new DecelerateInterpolator())
                    .start();
        } else {
            card.setScaleX(1f);
            card.setScaleY(1f);
            card.animate().translationY(0).alpha(1f)
                    .setDuration(340)
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
        if (style == ANIM_SCALE) a.scaleX(0.94f).scaleY(0.94f);
        if (after != null) a.withEndAction(after);
        a.start();
    }

    /** 只刷新电量文本，不重建弹窗、不打断 GIF */
    public static void updateInfo(View root, String rawName, BatteryLevels levels, Prefs prefs) {
        if (root == null || levels == null) return;
        TextView tvBattery = root.findViewById(R.id.tvBattery);
        TextView tvCase = root.findViewById(R.id.tvCase);
        ImageView icCase = root.findViewById(R.id.icCase);
        levels.sanitize();
        if (tvBattery != null) tvBattery.setText(batteryText(levels));
        if (tvCase != null) {
            tvCase.setText(BatteryLevels.valid(levels.caseBox)
                    ? BatteryLevels.fmt(levels.caseBox) : "--%");
            tvCase.setVisibility(View.VISIBLE);
        }
        if (icCase != null) icCase.setVisibility(View.VISIBLE);
    }

    private static String fill(String tpl, String dev) {
        if (tpl == null) return "";
        String v = tpl.trim();
        if (v.isEmpty()) return "";
        return v.contains("%s") ? v.replace("%s", dev) : v;
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
