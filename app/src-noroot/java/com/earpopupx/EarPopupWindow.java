package com.earpopupx;

import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.content.Context;
import android.graphics.Color;
import android.graphics.ImageDecoder;
import android.graphics.PixelFormat;
import android.graphics.drawable.AnimatedImageDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 悬浮窗弹窗。
 * 窗口尺寸 = 卡片尺寸，卡片本身就是圆角背景，
 * 不使用 FLAG_DIM_BEHIND，因此不存在「圆角外露出黑色直角」的问题。
 */
public final class EarPopupWindow {

    private static final long AUTO_DISMISS_MS = 6500L;

    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable dismissRun = new Runnable() {
        @Override
        public void run() {
            dismiss();
        }
    };

    private WindowManager wm;
    private View root;
    private String shownName;
    private TextView tvLeft, tvRight, tvCase, tvAggregate, tvSource;

    public EarPopupWindow(Context c) {
        context = c.getApplicationContext();
    }

    public synchronized void show(String name, BatteryState state) {
        if (!Settings.canDrawOverlays(context)) return;
        if (root != null && name != null && name.equals(shownName)) {
            update(state);
            return;
        }
        dismiss();
        shownName = name;

        wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        if (wm == null) return;

        root = buildCard(name);

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams();
        lp.width = (int) (context.getResources().getDisplayMetrics().widthPixels * 0.94f);
        lp.height = WindowManager.LayoutParams.WRAP_CONTENT;
        lp.gravity = Gravity.CENTER;
        lp.format = PixelFormat.TRANSLUCENT;
        lp.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        lp.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
        lp.windowAnimations = android.R.style.Animation_Dialog;
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                lp.setBlurBehindRadius(dp(30));
                lp.flags |= WindowManager.LayoutParams.FLAG_BLUR_BEHIND;
            } catch (Throwable ignored) {
            }
        }

        try {
            wm.addView(root, lp);
        } catch (Throwable t) {
            root = null;
            return;
        }
        animateIn(root);
        update(state);
        postDismiss();
    }

    /** 弹窗已经显示且设备未变时，原地更新数值，不重建、不闪烁。 */
    public synchronized void updateIfVisible(String name, BatteryState state) {
        if (root == null) return;
        if (name != null && shownName != null && !name.equals(shownName)) return;
        update(state);
        postDismiss();
    }

    public synchronized void dismiss() {
        handler.removeCallbacks(dismissRun);
        if (wm != null && root != null) {
            try {
                wm.removeView(root);
            } catch (Throwable ignored) {
            }
        }
        root = null;
        shownName = null;
    }

    private void postDismiss() {
        handler.removeCallbacks(dismissRun);
        handler.postDelayed(dismissRun, AUTO_DISMISS_MS);
    }

    private View buildCard(String name) {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = dp(20);
        card.setPadding(pad, pad, pad, dp(16));

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.argb(232, 250, 250, 252));
        bg.setCornerRadius(dp(30));
        card.setBackground(bg);

        // 关键：把所有子 View（尤其是顶部图片）裁进圆角里，
        // 否则图片的方角会盖住卡片的圆角，看起来就是「直角边」。
        final float radius = dp(30);
        card.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(View view, android.graphics.Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        });
        card.setClipToOutline(true);

        ImageView media = new ImageView(context);
        media.setScaleType(ImageView.ScaleType.CENTER_CROP);
        boolean hasMedia = loadMedia(media);
        if (!hasMedia) {
            media.setVisibility(View.GONE);
        } else {
            card.addView(media, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(240)));
        }

        TextView title = new TextView(context);
        title.setText(name == null ? "蓝牙耳机" : name);
        title.setTextSize(20f);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setTextColor(Color.rgb(15, 15, 15));
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, dp(14), 0, dp(4));
        card.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setWeightSum(3f);
        row.setPadding(0, dp(6), 0, dp(6));
        row.addView(slot("左耳", true));
        row.addView(slot("右耳", false));
        row.addView(slot("充电盒", false));
        card.addView(row, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        tvAggregate = new TextView(context);
        tvAggregate.setTextSize(15f);
        tvAggregate.setTextColor(Color.rgb(70, 70, 70));
        tvAggregate.setGravity(Gravity.CENTER);
        tvAggregate.setVisibility(View.GONE);
        card.addView(tvAggregate, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        tvSource = new TextView(context);
        tvSource.setTextSize(11f);
        tvSource.setTextColor(Color.rgb(140, 140, 140));
        tvSource.setGravity(Gravity.CENTER);
        tvSource.setPadding(0, dp(6), 0, 0);
        card.addView(tvSource, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        return card;
    }

    private LinearLayout slot(String label, boolean isLeft) {
        LinearLayout col = new LinearLayout(context);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView value = new TextView(context);
        value.setText("--");
        value.setTextSize(23f);
        value.setTypeface(null, android.graphics.Typeface.BOLD);
        value.setTextColor(Color.rgb(20, 20, 20));
        value.setGravity(Gravity.CENTER);
        col.addView(value);

        TextView lab = new TextView(context);
        lab.setText(label);
        lab.setTextSize(12f);
        lab.setTextColor(Color.rgb(120, 120, 120));
        lab.setGravity(Gravity.CENTER);
        lab.setPadding(0, dp(2), 0, 0);
        col.addView(lab);

        if (isLeft) {
            tvLeft = value;
        } else if ("右耳".equals(label)) {
            tvRight = value;
        } else {
            tvCase = value;
        }
        return col;
    }

    private void update(BatteryState s) {
        if (root == null || s == null) return;
        if (tvLeft != null) tvLeft.setText(s.left >= 0 ? s.left + "%" : "--");
        if (tvRight != null) tvRight.setText(s.right >= 0 ? s.right + "%" : "--");
        if (tvCase != null) tvCase.setText(s.caseLevel >= 0 ? s.caseLevel + "%" : "--");

        if (tvAggregate != null) {
            if (!s.hasParts() && s.aggregate >= 0) {
                tvAggregate.setText("整机 " + s.aggregate + "%");
                tvAggregate.setVisibility(View.VISIBLE);
            } else {
                tvAggregate.setVisibility(View.GONE);
            }
        }
        if (tvSource != null) {
            tvSource.setText(s.source == null ? "" : ("数据来源：" + s.source));
        }
    }

    private boolean loadMedia(ImageView v) {
        String raw = AppPrefs.media(context);
        if (raw == null) return false;
        try {
            Uri u = Uri.parse(raw);
            ImageDecoder.Source src = ImageDecoder.createSource(context.getContentResolver(), u);
            Drawable d = ImageDecoder.decodeDrawable(src);
            v.setImageDrawable(d);
            if (d instanceof AnimatedImageDrawable) {
                ((AnimatedImageDrawable) d).start();
            }
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void animateIn(View v) {
        v.setAlpha(0f);
        v.setScaleX(0.94f);
        v.setScaleY(0.94f);
        AnimatorSet set = new AnimatorSet();
        set.playTogether(
                ObjectAnimator.ofFloat(v, "alpha", 0f, 1f),
                ObjectAnimator.ofFloat(v, "scaleX", 0.94f, 1f),
                ObjectAnimator.ofFloat(v, "scaleY", 0.94f, 1f));
        set.setDuration(240);
        set.start();
    }

    private int dp(int n) {
        return (int) (n * context.getResources().getDisplayMetrics().density + 0.5f);
    }
}
