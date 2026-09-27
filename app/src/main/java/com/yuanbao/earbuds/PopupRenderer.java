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

    /** 卡片总高 = 宽度 × 该比例；三区再按 76 / 16 / 8 分配 */
    private static final float CARD_H_RATIO = 1.15f;

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
        int heightPx = (int) (widthPx * CARD_H_RATIO);
        if (card != null) {
            ViewGroup.LayoutParams lp = card.getLayoutParams();
            lp.width = widthPx;
            lp.height = heightPx;   // 固定高度，三区 weight 才能生效
            card.setLayoutParams(lp);

            // ---------- 液态玻璃外观 ----------
            GradientDrawable gd = new GradientDrawable();
            gd.setShape(GradientDrawable.RECTANGLE);
            gd.setCornerRadius(24 * d);
            gd.setColor(0x1FFFFFFF);            // rgba(255,255,255,0.12)
            gd.setStroke(Math.max(1, (int) d), 0x33FFFFFF);
            card.setBackground(gd);
            card.setElevation(18 * d);
            card.setClipToOutline(true);        // 让内容与玻璃边框一起被圆角裁切
        }

        // ---------- GIF 区：容器衬底，防浅色发白 / 深色糊成一团 ----------
        if (gifWrap != null) {
            GradientDrawable wrapBg = new GradientDrawable();
            wrapBg.setShape(GradientDrawable.RECTANGLE);
            wrapBg.setCornerRadius(18 * d);
            wrapBg.setColor(0x0FFFFFFF);        // rgba(255,255,255,0.06)
            gifWrap.setBackground(wrapBg);
        }

        // ---------- 文字色 ----------
        int textColor = parseColor(prefs.textColor(), Color.WHITE);
        infoBar.setTextColor(applyAlpha(textColor, 0.82f));
        tipText.setTextColor(applyAlpha(textColor, 0.55f));

        // ---------- ② 信息窄条：名称 · 状态 | L / R / Case ----------
        infoBar.setText(buildInfo(prettyName(rawName), prefs.subText(), levels));

        // ---------- ① GIF / 图片 ----------
        String uri = prefs.imageUri();
        if (!uri.isEmpty()) {
            try {
                Glide.with(c.getApplicationContext())
                        .load(Uri.parse(uri))
                        .fitCenter()            // 等价 object-fit: contain，不拉伸变形
                        .into(img);
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
    }

    /** 组装信息窄条：菠萝耳机 5pro · 已连接  |  L:68%  R:66%  Case:59% */
    private static String buildInfo(String name, String sub, BatteryLevels b) {
        StringBuilder sb = new StringBuilder();
        sb.append(name);
        String s = sub == null ? "" : sub.trim();
        if (!s.isEmpty()) {
            sb.append(" · ").append(s.contains("%s") ? String.format(s, name) : s);
        }
        if (b == null) return sb.toString();
        if (b.left >= 0 || b.right >= 0 || b.caseBox >= 0 || b.overall >= 0) {
            sb.append("  |  ");
            sb.append("L:").append(BatteryLevels.fmt(b.left));
            sb.append("  R:").append(BatteryLevels.fmt(b.right));
            sb.append("  Case:").append(BatteryLevels.fmt(b.caseBox));
        }
        return sb.toString();
    }

    /**
     * 只刷新信息窄条，用于电量探测异步返回后原地更新弹窗，
     * 避免重建整个弹窗导致 GIF 重新播放。
     */
    public static void updateInfo(View root, String rawName, BatteryLevels levels, Prefs prefs) {
        if (root == null || levels == null) return;
        TextView infoBar = root.findViewById(R.id.infoBar);
        if (infoBar == null) return;
        infoBar.setText(buildInfo(prettyName(rawName),
                prefs == null ? "" : prefs.subText(), levels));
    }

    /** 流光：一条斜向高光带从左扫到右 */
    private static void startShimmer(Context c, View shimmer, int cardWidth) {
        shimmer.setBackgroundResource(R.drawable.bg_shimmer);
        shimmer.setVisibility(View.VISIBLE);
        float band = cardWidth * 0.55f;
        ViewGroup.LayoutParams lp = shimmer.getLayoutParams();
        if (lp != null) {
            lp.width = (int) band;
            shimmer.setLayoutParams(lp);
        }
        TranslateAnimation a = new TranslateAnimation(
                Animation.RELATIVE_TO_PARENT, -0.6f,
                Animation.RELATIVE_TO_PARENT, 1.2f,
                Animation.RELATIVE_TO_SELF, 0f,
                Animation.RELATIVE_TO_SELF, 0f);
        a.setDuration(900);
        a.setStartOffset(180);
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
    public static String prettyName(String raw) {
        if (raw == null) return "耳机";
        String n = raw.trim();
        String low = n.toLowerCase();
        if (low.contains("buds 5 pro") || low.contains("buds5pro")) {
            return n.contains("电竞") ? "Redmi Buds 5 Pro 电竞版" : "Redmi Buds 5 Pro";
        }
        if (low.contains("buds 5")) return "Redmi Buds 5";
        if (low.contains("buds 4 pro")) return "Xiaomi Buds 4 Pro";
        if (low.contains("airpods")) return "AirPods";
        return n;
    }
}
