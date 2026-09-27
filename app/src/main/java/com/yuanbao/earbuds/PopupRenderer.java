package com.yuanbao.earbuds;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Matrix;
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
import android.widget.LinearLayout;
import android.widget.TextView;

import com.bumptech.glide.Glide;

/**
 * 弹窗渲染：悬浮窗引擎与系统级 Activity 引擎共用。
 *
 * 排版（类 realme）：
 *   ① 媒体区（图片 / GIF）占据主要空间，默认填满不留白
 *   ② 底部渐变，让媒体与详情自然过渡
 *   ③ 详情区：设备名 + 电量 + 提示
 *
 * 动画节奏（分两阶段，互不打断）：
 *   第 1 段：整个卡片从下往上入场（340ms）
 *   第 2 段：0.8 秒后，详情区缓慢升起（420ms）
 *   —— 详情区是「始终占位 + 平移淡入」，不触发重新测量，
 *      所以 GIF 全程连续播放，既不中断也不重置。
 *
 * 圆角：媒体区由 RoundedImageView 用离屏 DST_IN 合成，边缘抗锯齿无锯齿。
 */
public final class PopupRenderer {

    /** 动画样式索引，与 Prefs.animStyle() 一致 */
    public static final int ANIM_SCALE = 0;
    public static final int ANIM_BOTTOM = 1;
    public static final int ANIM_TOP = 2;

    /** 详情区延迟升起的时间 */
    private static final long DETAIL_DELAY_MS = 800L;
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
        final View card = root.findViewById(R.id.card);
        final FrameLayout gifWrap = root.findViewById(R.id.gifWrap);
        final RoundedImageView img = root.findViewById(R.id.popupImage);
        View shimmer = root.findViewById(R.id.shimmer);
        View gradientFade = root.findViewById(R.id.gradientFade);
        final View detailArea = root.findViewById(R.id.detailArea);
        TextView tvDeviceName = root.findViewById(R.id.tvDeviceName);
        TextView infoBar = root.findViewById(R.id.infoBar);
        TextView tipText = root.findViewById(R.id.tipText);

        float d = c.getResources().getDisplayMetrics().density;

        // ---------- 卡片尺寸 ----------
        int widthPx = (int) (prefs.widthDp() * d);
        float ratio = Math.max(0.40f, Math.min(0.94f, prefs.imageRatio()));
        float cardRatio = 0.42f + ratio * 1.05f;
        int heightPx = (int) (widthPx * cardRatio);

        int cardBg = parseColor(
                prefs.autoColor() ? prefs.autoBgColor() : prefs.bgColor(), 0x1FFFFFFF);

        if (card != null) {
            ViewGroup.LayoutParams lp = card.getLayoutParams();
            lp.width = widthPx;
            lp.height = heightPx;
            card.setLayoutParams(lp);

            GradientDrawable gd = new GradientDrawable();
            gd.setShape(GradientDrawable.RECTANGLE);
            gd.setCornerRadius(prefs.radiusDp() * d);
            gd.setColor(cardBg);
            gd.setStroke(Math.max(1, (int) d), 0x33FFFFFF);
            card.setBackground(gd);
            card.setElevation(18 * d);
        }

        // ---------- ② 底部渐变：透明 → 卡片底色 ----------
        if (gradientFade != null) {
            GradientDrawable fade = new GradientDrawable(
                    GradientDrawable.Orientation.BOTTOM_TOP,
                    new int[]{cardBg, Color.TRANSPARENT});
            gradientFade.setBackground(fade);
        }

        // ---------- ① 媒体区圆角（抗锯齿） ----------
        if (img != null) {
            img.setRadius(Math.max(0f, prefs.radiusDp() - 2) * d);
        }

        // ---------- 文字色 ----------
        int textColor = parseColor(prefs.textColor(), Color.WHITE);
        int subColor = applyAlpha(textColor, 0.85f);
        int tipColor = applyAlpha(textColor, 0.55f);

        String deviceName = prettyName(rawName);
        String head = fill(prefs.titleText(), deviceName);
        if (tvDeviceName != null) {
            tvDeviceName.setText(head.isEmpty() ? deviceName : head);
            tvDeviceName.setTextColor(textColor);
        }
        if (infoBar != null) {
            infoBar.setText(buildDetail(prefs, deviceName, levels));
            infoBar.setTextColor(subColor);
        }
        if (tipText != null) {
            tipText.setTextColor(tipColor);
        }

        // ---------- ① 图片 / GIF ----------
        if (gifWrap != null) gifWrap.setBackground(null);
        String uri = prefs.imageUri();
        if (img != null) {
            img.setScaleType(ImageView.ScaleType.MATRIX);
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
                                    // 每次弹窗都从第 0 帧开始
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

        // ---------- 流光 ----------
        if (shimmer != null && card != null) {
            startShimmer(c, shimmer, card.getWidth() > 0 ? card.getWidth() : widthPx);
        }

        // ---------- 点击卡片任意位置关闭 ----------
        if (card != null) {
            card.setOnClickListener(v -> {
                if (onClose != null) onClose.close();
            });
        }
        root.setOnClickListener(v -> {
            if (onClose != null) onClose.close();
        });

        // ---------- 音效 ----------
        SoundPlayer.play(c, prefs);

        // ---------- 入场：整个卡片从下往上 ----------
        if (card != null) applyEnter(card, prefs.animStyle());

        // ---------- 第 2 段：0.8 秒后详情区缓慢升起 ----------
        if (detailArea != null) {
            detailArea.animate().cancel();
            detailArea.setAlpha(0f);
            detailArea.setTranslationY(24 * d);
            detailArea.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setStartDelay(DETAIL_DELAY_MS)
                    .setDuration(DETAIL_RISE_MS)
                    .setInterpolator(new DecelerateInterpolator())
                    .start();
        }
    }

    /**
     * 应用图片变换矩阵：基础填满（centerCrop） + 用户双指缩放/拖动/旋转。
     * 用 MATRIX 而非 FIT_CENTER：FIT_CENTER 会留白，且无法叠加用户手势。
     */
    public static void applyImageMatrix(ImageView img) {
        if (img == null) return;
        Drawable dr = img.getDrawable();
        int vw = img.getWidth();
        int vh = img.getHeight();
        if (dr == null || vw <= 0 || vh <= 0) {
            // 尺寸还没测出来，下一帧再试
            img.post(() -> applyImageMatrix(img));
            return;
        }
        int dw = dr.getIntrinsicWidth();
        int dh = dr.getIntrinsicHeight();
        if (dw <= 0 || dh <= 0) dw = dh = 1;

        Prefs prefs = PrefsHolder.get();
        float userScale = prefs == null ? 1f : prefs.imageScale();
        float dx = prefs == null ? 0f : prefs.imageOffsetX();
        float dy = prefs == null ? 0f : prefs.imageOffsetY();
        float rot = prefs == null ? 0f : prefs.imageRotation();
        float density = img.getResources().getDisplayMetrics().density;

        Matrix m = new Matrix();

        // 基础：centerCrop（等比放大到铺满，不留白）
        float scale = Math.max((float) vw / dw, (float) vh / dh);
        // 叠加用户缩放
        scale *= userScale;

        m.postScale(scale, scale);

        // 用户旋转（绕图片中心）
        float px = dw / 2f, py = dh / 2f;
        if (rot != 0f) m.postRotate(rot, px, py);

        // 居中
        float tx = (vw - dw * scale) / 2f;
        float ty = (vh - dh * scale) / 2f;

        // 叠加用户拖动（dp → px）
        tx += dx * density;
        ty += dy * density;

        m.postTranslate(tx, ty);
        img.setImageMatrix(m);
    }

    /** Prefs 的轻量全局持有，避免在静态渲染方法里反复 new */
    public static final class PrefsHolder {
        private static volatile Prefs instance;

        public static void init(Context c) {
            if (instance == null) instance = new Prefs(c.getApplicationContext());
        }

        public static Prefs get() {
            return instance;
        }
    }

    /** 让 GIF 从第 0 帧重新开始（Glide 会复用缓存实例，否则会续播） */
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
     * 入场动画：默认从下往上。
     * 用户明确要求「弹窗从下往上升起」，所以三种样式统一为底部上滑的变体，
     * 保证任何设置下都是自下而上出现。
     */
    public static void applyEnter(View card, int style) {
        if (card == null) return;
        float d = card.getResources().getDisplayMetrics().density;
        card.animate().cancel();

        // 统一：从下往上 + 淡入
        card.setTranslationY(140 * d);
        card.setTranslationX(0);
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

    /** 退场：向下收回 */
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

    /** 只刷新电量等信息条，不重建弹窗、不打断 GIF */
    public static void updateInfo(View root, String rawName, BatteryLevels levels, Prefs prefs) {
        if (root == null || levels == null || prefs == null) return;
        TextView infoBar = root.findViewById(R.id.infoBar);
        if (infoBar == null) return;
        levels.sanitize();
        infoBar.setText(buildDetail(prefs, prettyName(rawName), levels));
    }

    /** 详情区第二行：副标题 · 电量 */
    private static String buildDetail(Prefs prefs, String deviceName, BatteryLevels b) {
        StringBuilder sb = new StringBuilder();
        String sub = fill(prefs.subText(), deviceName);
        if (!sub.isEmpty()) sb.append(sub);
        if (b != null && b.anyKnown()) {
            if (sb.length() > 0) sb.append("  ·  ");
            sb.append("L:").append(BatteryLevels.fmt(b.left))
                    .append("  R:").append(BatteryLevels.fmt(b.right));
            if (BatteryLevels.valid(b.caseBox)) {
                sb.append("  Case:").append(BatteryLevels.fmt(b.caseBox));
            }
        }
        return sb.toString();
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
