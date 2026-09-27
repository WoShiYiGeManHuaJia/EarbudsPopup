package com.yuanbao.earbuds;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.bumptech.glide.Glide;

/**
 * 弹窗内容渲染：悬浮窗引擎和系统级 Activity 引擎共用同一套渲染逻辑。
 */
public final class PopupRenderer {

    public interface OnClose {
        void close();
    }

    private PopupRenderer() {
    }

    public static void bind(Context c, View root, String rawName, int battery,
                            Prefs prefs, OnClose onClose) {
        View card = root.findViewById(R.id.card);
        ImageView img = root.findViewById(R.id.popupImage);
        TextView title = root.findViewById(R.id.popupTitle);
        TextView sub = root.findViewById(R.id.popupSub);
        TextView bat = root.findViewById(R.id.popupBattery);
        TextView hint = root.findViewById(R.id.popupHint);
        View accentBar = root.findViewById(R.id.accentBar);
        LinearLayout batteryRow = root.findViewById(R.id.batteryRow);
        ProgressBar batteryBar = root.findViewById(R.id.batteryBar);

        // 强调色
        int accent;
        try {
            accent = Color.parseColor(prefs.accentColor());
        } catch (IllegalArgumentException e) {
            accent = 0xFF00E5A0;
        }
        accentBar.setBackgroundColor(accent);

        // 卡片背景：圆角 + 颜色
        GradientDrawable gd = new GradientDrawable();
        gd.setShape(GradientDrawable.RECTANGLE);
        gd.setCornerRadius(dp(c, prefs.radiusDp()));
        try {
            gd.setColor(Color.parseColor(prefs.bgColor()));
        } catch (IllegalArgumentException e) {
            gd.setColor(0xF2141620);
        }
        if (card != null) {
            card.setBackground(gd);
            card.setElevation(dp(c, 12));
        }

        int textColor;
        try {
            textColor = Color.parseColor(prefs.textColor());
        } catch (IllegalArgumentException e) {
            textColor = Color.WHITE;
        }
        title.setTextColor(textColor);
        sub.setTextColor(textColor);
        bat.setTextColor(textColor);
        hint.setTextColor(textColor);

        title.setText(prefs.titleText());
        String subRaw = prefs.subText();
        String pretty = prettyName(rawName);
        sub.setText(subRaw.contains("%s") ? String.format(subRaw, pretty) : subRaw);
        sub.setVisibility(subRaw.isEmpty() ? View.GONE : View.VISIBLE);

        if (battery >= 0 && battery <= 100) {
            bat.setText(battery + "%");
            batteryBar.setProgress(battery);
            batteryRow.setVisibility(View.VISIBLE);
        } else {
            batteryRow.setVisibility(View.GONE);
        }

        // 自定义图片 / GIF（GIF 由 Glide 直接播放）
        ViewGroup.LayoutParams lp = img.getLayoutParams();
        lp.height = (int) dp(c, prefs.imageHeightDp());
        img.setLayoutParams(lp);
        img.setVisibility(View.VISIBLE);
        String uri = prefs.imageUri();
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
        }

        root.setOnClickListener(v -> {
            if (onClose != null) onClose.close();
        });
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

    private static float dp(Context c, float v) {
        return v * c.getResources().getDisplayMetrics().density;
    }
}
