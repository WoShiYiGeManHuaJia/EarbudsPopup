package com.yuanbao.earbuds;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Animation;
import android.view.animation.TranslateAnimation;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.bumptech.glide.Glide;

/**
 * 弹窗渲染：悬浮窗引擎与系统级 Activity 引擎共用。
 *
 * 卡片为单张液态玻璃，纵向三区：
 *   ① GIF 主视觉区  76%   —— fitCenter 自适应任意比例，不拉伸
 *   ② 设备信息窄条  16%   —— 名称 · 状态 | L:R:Case 电量
 *   ③ 操作提示区    8%    —— 轻触关闭弹窗
 * 无关闭按钮、无标题栏，点击卡片任意位置关闭。
 */
public final class PopupRenderer {

    /** 动画样式索引，与 Prefs.animStyle() 一致 */
    public static final int ANIM_SCALE = 0;   // 缩放淡入
    public static final int ANIM_BOTTOM = 1;  // 底部上滑
    public static final int ANIM_TOP = 2;     // 顶部下滑

    /** 卡片总高 = 宽度 × 该比例；三区再按 76 / 16 / 8 分配 */
    private static final float CARD_H_RATIO = 1.15f;
    /** 图片区圆角：卡片圆角 24dp 减去 8dp 内边距 */
    private static final float GIF_CORNER = 16f;

    public interface OnClose {
        void close();
    }

    private PopupRenderer() {
    }

    public static void bind(Context c, View root, String rawName,
                            BatteryLevels levels,
                            Prefs prefs, OnClose onClose) {
        View card = root.findViewById(R.id.card);
        FrameLayout gifWrap = root.findViewById(R.id.gifWrap);
        ImageView img = root.findViewById(R.id.popupImage);
        View shimmer = root.findViewById(R.id.shimmer);
        TextView infoBar = root.findViewById(R.id.infoBar);
        TextView tipText = root.findViewById(R.id.tipText);

        float d = c.getResources().getDisplayMetrics().density;

        // ---------- 卡片尺寸 ----------
        int widthPx = (int) (prefs.widthDp() * d);
        // 卡片总高由「图片占弹窗高度」换算：值越大卡片越高，
        // 而文字区是固定紧凑高度，所以多出来的高度全部归 GIF 区
        float ratio = Math.max(0.40f, Math.min(0.94f, prefs.imageRatio()));
        float cardRatio = 0.42f + ratio * 1.05f;   // 0.82 -> 1.28
        int heightPx = (int) (widthPx * cardRatio);
        // 卡片底色：自动取色优先，否则用用户手填的颜色
        int cardBg = parseColor(
                prefs.autoColor() ? prefs.autoBgColor() : prefs.bgColor(), 0x1FFFFFFF);
        if (card != null) {
            ViewGroup.LayoutParams lp = card.getLayoutParams();
            lp.width = widthPx;
            lp.height = heightPx;   // 固定高度，三区 weight 才能生效
            card.setLayoutParams(lp);

            // ---------- 液态玻璃外观 ----------
            GradientDrawable gd = new GradientDrawable();
            gd.setShape(GradientDrawable.RECTANGLE);
            gd.setCornerRadius(prefs.radiusDp() * d);   // 用户可调
            gd.setColor(cardBg);
            gd.setStroke(Math.max(1, (int) d), 0x33FFFFFF);
            card.setBackground(gd);
            card.setElevation(18 * d);
            card.setClipToOutline(true);        // 让内容与玻璃边框一起被圆角裁切
        }

        // ---------- 三区：GIF 区吃掉全部剩余空间，文字区保持紧凑 ----------
        // 布局里 gifWrap 已是 weight=1，infoBar/tipText 是 wrap_content，
        // 这里只在旧布局残留 weight 时兜底校正一次
        forceWeight(gifWrap, 1f);

        // GIF 区不再铺任何半透明衬底：之前那层 6% 白会让 GIF 显得发灰、不清晰。
        // 圆角由 ImageView 自己裁切，容器保持完全透明。
        if (gifWrap != null) {
            gifWrap.setBackground(null);
        }

        // 圆角裁切必须作用在 ImageView 本身：
        // 之前裁的是外层容器 gifWrap，而图片在容器的 padding 内部，
        // 容器边界上的圆角根本碰不到图片的四角，所以看起来还是直角。
        // 用 ViewOutlineProvider 由 GPU 合成时裁切，不会像 Glide Transformation
        // 那样逐帧重算，GIF 帧率不受影响。
        if (img != null) {
            final float r = GIF_CORNER * d;
            img.setOutlineProvider(new android.view.ViewOutlineProvider() {
                @Override
                public void getOutline(android.view.View view, android.graphics.Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), r);
                }
            });
            img.setClipToOutline(true);
        }

        // ---------- 文字色 ----------
        int textColor = parseColor(prefs.textColor(), Color.WHITE);
        infoBar.setTextColor(applyAlpha(textColor, 0.82f));
        tipText.setTextColor(applyAlpha(textColor, 0.55f));

        // ---------- ② 信息窄条：名称 · 状态 | L / R / Case ----------
        infoBar.setText(buildInfo(prettyName(rawName), prefs, levels));

        // ---------- ① GIF / 图片 ----------
        // 每次弹窗都让 GIF 从第一帧开始播：Glide 会缓存已解码的 GifDrawable，
        // 第二次直接复用同一个实例，动画状态停在上次的位置，看起来就是「续播」。
        // 这里加载完成后显式重启动画，保证每次都是从第 0 帧开始。
        String uri = prefs.imageUri();
        if (!uri.isEmpty()) {
            try {
                Glide.with(c.getApplicationContext())
                        .load(Uri.parse(uri))
                        .dontTransform()
                        .into(new com.bumptech.glide.request.target.CustomViewTarget<ImageView,
                                android.graphics.drawable.Drawable>(img) {
                            @Override
                            public void onResourceReady(
                                    android.graphics.drawable.Drawable resource,
                                    com.bumptech.glide.request.transition.Transition<? super
                                            android.graphics.drawable.Drawable> transition) {
                                img.setImageDrawable(resource);
                                restartGif(resource);
                            }

                            @Override
                            public void onLoadFailed(
                                    android.graphics.drawable.Drawable errorDrawable) {
                                img.setImageResource(R.drawable.ic_headphone);
                            }

                            @Override
                            protected void onResourceCleared(
                                    android.graphics.drawable.Drawable placeholder) {
                            }
                        });
            } catch (Exception e) {
                img.setImageResource(R.drawable.ic_headphone);
            }
        } else {
            img.setImageResource(R.drawable.ic_headphone);
        }

        // ---------- 流光扫过 ----------
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

        // 入场动画（显式对卡片执行，不依赖系统窗口动画）
        if (card != null) applyEnter(card, prefs.animStyle());
    }

    /**
     * 让 GIF 从第 0 帧重新开始播放。
     * Glide 缓存的 GifDrawable 会被复用，第二次弹窗时动画状态停在上次结束处，
     * 表现为「续播」。这里先 stop() 再 startFromFirstFrame()，强制回到开头。
     */
    private static void restartGif(android.graphics.drawable.Drawable d) {
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
            android.graphics.drawable.Animatable a = (android.graphics.drawable.Animatable) d;
            try {
                a.stop();
                a.start();
            } catch (Exception ignored) {
            }
        }
    }

    /** 把子项 height 设为 0dp 并指定 weight，让它吃掉剩余空间 */
    private static void forceWeight(View v, float weight) {
        if (v == null) return;
        ViewGroup.LayoutParams lp = v.getLayoutParams();
        if (lp instanceof LinearLayout.LayoutParams) {
            LinearLayout.LayoutParams llp = (LinearLayout.LayoutParams) lp;
            llp.height = 0;
            llp.weight = weight;
            v.setLayoutParams(llp);
        }
    }

    /**
     * 入场动画：按用户选择的样式执行。
     * 之前三种样式被写死成同一种（从上方滑入），且系统级引擎依赖 windowAnimationStyle，
     * 从后台启动时系统经常直接跳过窗口动画，所以表现为「直接变出来」。
     * 现在改为对卡片 View 显式执行，两个引擎表现一致。
     */
    public static void applyEnter(View card, int style) {
        if (card == null) return;
        float d = card.getResources().getDisplayMetrics().density;
        card.animate().cancel();

        switch (style) {
            case ANIM_BOTTOM:   // 底部上滑
                card.setTranslationY(120 * d);
                card.setScaleX(1f);
                card.setScaleY(1f);
                card.setAlpha(0.2f);
                card.animate()
                        .translationY(0)
                        .alpha(1f)
                        .setDuration(340)
                        .setInterpolator(new android.view.animation.DecelerateInterpolator())
                        .start();
                break;

            case ANIM_TOP:      // 顶部下滑
                card.setTranslationY(-120 * d);
                card.setScaleX(1f);
                card.setScaleY(1f);
                card.setAlpha(0.2f);
                card.animate()
                        .translationY(0)
                        .alpha(1f)
                        .setDuration(340)
                        .setInterpolator(new android.view.animation.DecelerateInterpolator())
                        .start();
                break;

            default:            // 缩放淡入
                card.setTranslationY(-50 * d);
                card.setScaleX(0.93f);
                card.setScaleY(0.93f);
                card.setAlpha(0.3f);
                card.animate()
                        .translationY(0)
                        .scaleX(1f)
                        .scaleY(1f)
                        .alpha(1f)
                        .setDuration(340)
                        .setInterpolator(new android.view.animation.DecelerateInterpolator())
                        .start();
                break;
        }
    }

    /** 退场动画：反向收回 */
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
                .alpha(0f);

        if (style == ANIM_BOTTOM) {
            a.translationY(120 * d);
        } else {
            a.translationY(-50 * d).scaleX(0.93f).scaleY(0.93f);
        }
        if (after != null) a.withEndAction(after);
        a.start();
    }

    /**
     * 组装信息窄条。
     *
     * 修掉两个问题：
     *  1. 之前主标题写死用设备名，用户在「外观 → 主标题」填的内容根本没被使用；
     *  2. 副标题默认 "%s 已连接"，%s 又被替换成设备名，
     *     结果显示成 "Redmi Buds 5 Pro · Redmi Buds 5 Pro 已连接"，设备名重复两遍。
     *
     * 现在的规则：
     *  - 主标题取 prefs.titleText()，支持 %s（替换成设备名）；为空则退回设备名
     *  - 副标题同理；若副标题去掉 %s 后与主标题相同，则不再重复拼接
     */
    private static String buildInfo(String deviceName, Prefs prefs, BatteryLevels b) {
        String dev = deviceName == null ? "" : deviceName.trim();
        if (dev.isEmpty()) dev = "耳机";

        String title = "";
        String sub = "";
        if (prefs != null) {
            title = fill(prefs.titleText(), dev);
            sub = fill(prefs.subText(), dev);
        }
        // 主标题为空就用设备名
        String head = title.isEmpty() ? dev : title;

        StringBuilder sb = new StringBuilder(head);
        // 副标题非空、且与主标题不同才拼，避免重复
        if (!sub.isEmpty() && !sub.equals(head)) {
            sb.append(" · ").append(sub);
        }

        if (b == null) return sb.toString();
        // 只有存在有效电量（1~100）时才显示这一段；全未知就整段省略
        if (b.anyKnown()) {
            sb.append("  |  ");
            sb.append("L:").append(BatteryLevels.fmt(b.left));
            sb.append("  R:").append(BatteryLevels.fmt(b.right));
            sb.append("  Case:").append(BatteryLevels.fmt(b.caseBox));
        }
        return sb.toString();
    }

    /** 把模板里的 %s 替换成设备名 */
    private static String fill(String tpl, String dev) {
        if (tpl == null) return "";
        String v = tpl.trim();
        if (v.isEmpty()) return "";
        return v.contains("%s") ? v.replace("%s", dev) : v;
    }

    /**
     * 只刷新信息窄条，用于电量探测异步返回后原地更新弹窗，
     * 避免重建整个弹窗导致 GIF 重新播放。
     */
    public static void updateInfo(View root, String rawName, BatteryLevels levels, Prefs prefs) {
        if (root == null || levels == null) return;
        TextView infoBar = root.findViewById(R.id.infoBar);
        if (infoBar == null) return;
        infoBar.setText(buildInfo(prettyName(rawName), prefs, levels));
    }

    /** 流光：一条窄斜向高光带扫过，播完立即隐藏，绝不残留 */
    private static void startShimmer(Context c, View shimmer, int cardWidth) {
        shimmer.setBackgroundResource(R.drawable.bg_shimmer);
        float band = Math.max(24f, cardWidth * 0.22f);
        ViewGroup.LayoutParams lp = shimmer.getLayoutParams();
        if (lp != null) {
            lp.width = (int) band;
            shimmer.setLayoutParams(lp);
        }
        shimmer.setVisibility(View.VISIBLE);
        TranslateAnimation a = new TranslateAnimation(
                Animation.RELATIVE_TO_PARENT, -0.4f,
                Animation.RELATIVE_TO_PARENT, 1.0f,
                Animation.RELATIVE_TO_SELF, 0f,
                Animation.RELATIVE_TO_SELF, 0f);
        a.setDuration(760);
        a.setStartOffset(120);
        a.setAnimationListener(new Animation.AnimationListener() {
            public void onAnimationStart(Animation an) {
            }

            public void onAnimationEnd(Animation an) {
                shimmer.setVisibility(View.GONE);
                shimmer.setBackground(null);
            }

            public void onAnimationRepeat(Animation an) {
            }
        });
        shimmer.startAnimation(a);
    }

    private static int applyAlpha(int color, float alpha) {
        return (Math.round(255 * alpha) << 24) | (color & 0x00FFFFFF);
    }

    private static int parseColor(String v, int fallback) {
        try {
            return Color.parseColor(v);
        } catch (Exception e) {
            return fallback;
        }
    }

    /** 把蓝牙广播名换成更好看的显示名 */
    /**
     * 显示名：以用户在系统蓝牙里改的名字为准，绝不再做关键词替换。
     * 之前把 "buds 5 pro" 强行换成官方名，导致用户自定义的名字被覆盖。
     * 只有名字为空或明显是裸 MAC 时才兜底。
     */
    public static String prettyName(String raw) {
        if (raw == null) return "耳机";
        String n = raw.trim();
        if (n.isEmpty()) return "耳机";
        if (n.matches("^[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}$")) return "耳机";
        return n;
    }
}
