package com.yuanbao.earbuds;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.bumptech.glide.Glide;

/**
 * 弹窗渲染：悬浮窗引擎与系统级 Activity 引擎共用。
 *
 * 设计参照华为官方《耳机弹窗主题设计指导》：
 * 弹窗 1440×1792（宽高比 ≈ 1 : 1.24），圆角 128px ≈ 宽度的 8.9%。
 * 产品图铺满卡片顶部并做渐变融合，底部为型号名 + 耳机/充电盒双电量圆环。
 */
public final class PopupRenderer {

    public interface OnClose {
        void close();
    }

    private PopupRenderer() {
    }

    public static void bind(Context c, View root, String rawName,
                            int battery, int caseBattery,
                            Prefs prefs, OnClose onClose) {
        View card = root.findViewById(R.id.card);
        FrameLayout imageArea = root.findViewById(R.id.imageArea);
        ImageView img = root.findViewById(R.id.popupImage);
        View imageFade = root.findViewById(R.id.imageFade);
        TextView title = root.findViewById(R.id.popupTitle);
        TextView sub = root.findViewById(R.id.popupSub);
        LinearLayout batteryRow = root.findViewById(R.id.batteryRow);
        LinearLayout boxGroup = root.findViewById(R.id.boxGroup);
        BatteryRingView ringEarbud = root.findViewById(R.id.ringEarbud);
        BatteryRingView ringBox = root.findViewById(R.id.ringBox);
        TextView labelEarbud = root.findViewById(R.id.labelEarbud);
        TextView labelBox = root.findViewById(R.id.labelBox);

        float d = c.getResources().getDisplayMetrics().density;

        // ---------- 卡片底色：自动取色优先 ----------
        int cardColor;
        try {
            cardColor = Color.parseColor(
                    prefs.autoColor() ? prefs.autoBgColor() : prefs.bgColor());
        } catch (Exception e) {
            cardColor = 0xF2141620;
        }

        // ---------- 卡片宽高与圆角（按华为规范的比例） ----------
        int widthPx = (int) (prefs.widthDp() * d);
        if (card != null && card.getLayoutParams() != null) {
            ViewGroup.LayoutParams lp = card.getLayoutParams();
            lp.width = widthPx;
            card.setLayoutParams(lp);
        }

        float radiusPx = prefs.radiusDp() * d;
        GradientDrawable gd = new GradientDrawable();
        gd.setShape(GradientDrawable.RECTANGLE);
        gd.setCornerRadius(radiusPx);
        gd.setColor(cardColor);
        if (card != null) {
            card.setBackground(gd);
            card.setElevation(14 * d);
            // 关键：让图片与卡片一起被圆角裁切，实现"融合"而非"挖孔"
            card.setClipToOutline(true);
        }

        // ---------- 图片区：高度按宽度的比例算 ----------
        int imgHPx = (int) (widthPx * prefs.imageRatio());
        if (imageArea != null) {
            ViewGroup.LayoutParams lp = imageArea.getLayoutParams();
            lp.height = imgHPx;
            imageArea.setLayoutParams(lp);
        }

        // ---------- 渐变遮罩：图片底部渐隐到卡片底色 ----------
        if (imageFade != null) {
            GradientDrawable fade = new GradientDrawable(
                    GradientDrawable.Orientation.BOTTOM_TOP,
                    new int[]{cardColor, Color.TRANSPARENT});
            fade.setShape(GradientDrawable.RECTANGLE);
            imageFade.setBackground(fade);
        }

        // ---------- 文字 ----------
        int textColor;
        try {
            textColor = Color.parseColor(prefs.textColor());
        } catch (Exception e) {
            textColor = Color.WHITE;
        }
        title.setTextColor(textColor);
        sub.setTextColor(textColor);
        labelEarbud.setTextColor(textColor);
        labelBox.setTextColor(textColor);

        title.setText(prefs.titleText());
        String subRaw = prefs.subText();
        String pretty = prettyName(rawName);
        sub.setText(subRaw.contains("%s") ? String.format(subRaw, pretty) : subRaw);
        sub.setVisibility(subRaw.isEmpty() ? View.GONE : View.VISIBLE);

        // ---------- 电量圆环 ----------
        int accent;
        try {
            accent = Color.parseColor(prefs.accentColor());
        } catch (Exception e) {
            accent = 0xFF00E5A0;
        }

        boolean showEarbud = battery >= 0 && battery <= 100;
        boolean showBox = prefs.showCaseBattery() && caseBattery >= 0 && caseBattery <= 100;

        if (!showEarbud && !showBox) {
            batteryRow.setVisibility(View.GONE);
        } else {
            batteryRow.setVisibility(View.VISIBLE);

            if (showEarbud) {
                ringEarbud.setVisibility(View.VISIBLE);
                labelEarbud.setVisibility(View.VISIBLE);
                ringEarbud.setProgress(battery);
                ringEarbud.setRingColor(accent);
                ringEarbud.setTrackColor(adjustAlpha(textColor, 0.22f));
                ringEarbud.setTextColor(textColor);
            } else {
                ringEarbud.setVisibility(View.GONE);
                labelEarbud.setVisibility(View.GONE);
            }

            if (showBox) {
                boxGroup.setVisibility(View.VISIBLE);
                ringBox.setProgress(caseBattery);
                ringBox.setRingColor(accent);
                ringBox.setTrackColor(adjustAlpha(textColor, 0.22f));
                ringBox.setTextColor(textColor);
            } else {
                boxGroup.setVisibility(View.GONE);
            }
        }

        // ---------- 图片 / GIF ----------
        String uri = prefs.imageUri();
        if (imageArea != null) imageArea.setVisibility(View.VISIBLE);
        if (!uri.isEmpty()) {
            try {
                Glide.with(c.getApplicationContext())
                        .load(Uri.parse(uri))
                        .centerCrop()
                        .into(img);
            } catch (Exception e) {
                img.setImageResource(R.drawable.ic_headphone);
            }
        } else {
            img.setImageResource(R.drawable.ic_headphone);
            img.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        }

        root.setOnClickListener(v -> {
            if (onClose != null) onClose.close();
        });
    }

    /** 给颜色加上透明度 */
    private static int adjustAlpha(int color, float alpha) {
        int a = Math.round(255 * alpha);
        return (a << 24) | (color & 0x00FFFFFF);
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
