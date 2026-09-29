package com.earpopupx;

import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.content.Context;
import android.graphics.Color;
import android.graphics.ImageDecoder;
import android.graphics.drawable.AnimatedImageDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.Outline;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 悬浮窗弹窗。
 *
 * 结构：全屏透明窗口 → 全屏压暗层（点击关闭）→ 居中的圆角卡片。
 * 全屏窗口是为了让"压暗"和"系统实时模糊"覆盖整屏，
 * 这样圆角之外不会出现任何直角矩形（旧的直角黑边就是窗口矩形边界露出来的）。
 */
public final class EarPopupWindow {
    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private WindowManager wm;
    private View root;
    private boolean dismissing = false;

    private TextView nameView, valLeft, valRight, valCase, valAggregate, sourceView;

    public EarPopupWindow(Context c) { context = c.getApplicationContext(); }

    public boolean isShowing() { return root != null && !dismissing; }

    /** 显示弹窗。已显示时只刷新内容，不重建（避免闪烁）。 */
    public void show(String name, BatteryState state) {
        if (!Settings.canDrawOverlays(context)) return;
        if (root != null && !dismissing) {
            bind(name, state);
            scheduleDismiss();
            return;
        }
        try {
            wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
            if (wm == null) return;

            LayoutInflater inf = LayoutInflater.from(context);
            root = inf.inflate(R.layout.popup_card, null);

            nameView     = root.findViewById(R.id.name);
            valLeft      = root.findViewById(R.id.valLeft);
            valRight     = root.findViewById(R.id.valRight);
            valCase      = root.findViewById(R.id.valCase);
            valAggregate = root.findViewById(R.id.valAggregate);
            sourceView   = root.findViewById(R.id.source);

            View card = root.findViewById(R.id.card);
            View dim  = root.findViewById(R.id.dimLayer);
            TextView close = root.findViewById(R.id.close);
            ImageView media = root.findViewById(R.id.media);

            applyCardStyle(card);
            applyMediaHeight(media);
            loadMedia(media);

            dim.setAlpha(AppPrefs.dimPct(context) / 100f);
            dim.setOnClickListener(v -> dismiss());

            close.setOnClickListener(v -> dismiss());

            bind(name, state);

            WindowManager.LayoutParams lp = new WindowManager.LayoutParams();
            lp.width  = ViewGroup.LayoutParams.MATCH_PARENT;
            lp.height = ViewGroup.LayoutParams.MATCH_PARENT;
            lp.gravity = Gravity.CENTER;
            lp.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
            lp.format = android.graphics.PixelFormat.TRANSLUCENT;
            lp.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                     | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                     | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
            if (AppPrefs.blur(context) && Build.VERSION.SDK_INT >= 31) {
                try {
                    lp.flags |= WindowManager.LayoutParams.FLAG_BLUR_BEHIND;
                    lp.setBlurBehindRadius(dp(30));
                } catch (Throwable ignored) {}
            }
            lp.windowAnimations = android.R.style.Animation_Dialog;

            wm.addView(root, lp);
            dismissing = false;
            animateIn(card);
            scheduleDismiss();
        } catch (Throwable t) {
            root = null;
        }
    }

    /** 原地刷新电量文字，不重建窗口 */
    public void updateState(BatteryState state) {
        if (root == null || dismissing) return;
        handler.post(() -> bind(null, state));
    }

    private void bind(String name, BatteryState s) {
        if (root == null) return;
        if (name != null && nameView != null) nameView.setText(name);
        if (s == null) return;

        if (valLeft != null)  valLeft.setText(fmt(s.left));
        if (valRight != null) valRight.setText(fmt(s.right));
        if (valCase != null)  valCase.setText(fmt(s.caseLevel));

        if (valAggregate != null) {
            if (s.hasDetail()) {
                valAggregate.setVisibility(View.GONE);
            } else {
                valAggregate.setVisibility(View.VISIBLE);
                valAggregate.setText(s.aggregate >= 0 ? ("整机 " + s.aggregate + "%") : "整机 未知");
            }
        }
        if (sourceView != null) sourceView.setText("数据来源：" + s.source);
    }

    private static String fmt(int v) { return v >= 0 ? (v + "%") : "--"; }

    private void applyCardStyle(View card) {
        if (card == null) return;
        int r = dp(AppPrefs.cornerDp(context));
        int w = (int) (screenWidth() * AppPrefs.widthPct(context) / 100f);

        GradientDrawable g = new GradientDrawable();
        g.setColor(Color.parseColor("#F7F7F9"));
        g.setCornerRadius(r);
        card.setBackground(g);

        // 关键：把圆角做成真实裁剪，顶部图片会被裁出同样的圆角
        card.setClipToOutline(true);
        card.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), r);
            }
        });

        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) card.getLayoutParams();
        if (lp == null) lp = new FrameLayout.LayoutParams(w, FrameLayout.LayoutParams.WRAP_CONTENT);
        lp.width = w;
        lp.height = FrameLayout.LayoutParams.WRAP_CONTENT;
        lp.gravity = Gravity.CENTER;
        lp.topMargin = dp(AppPrefs.offsetDp(context));   // 负 = 上移
        card.setLayoutParams(lp);
    }

    private void applyMediaHeight(ImageView media) {
        if (media == null) return;
        ViewGroup.LayoutParams lp = media.getLayoutParams();
        if (lp == null) lp = new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(240));
        lp.height = dp(AppPrefs.mediaHDp(context));
        media.setLayoutParams(lp);
        View frame = root == null ? null : root.findViewById(R.id.mediaFrame);
        if (frame != null) {
            ViewGroup.LayoutParams fl = frame.getLayoutParams();
            if (fl != null) { fl.height = lp.height; frame.setLayoutParams(fl); }
        }
    }

    private void loadMedia(ImageView v) {
        if (v == null) return;
        String raw = AppPrefs.media(context);
        if (raw == null) return;
        try {
            Uri u = Uri.parse(raw);
            ImageDecoder.Source src = ImageDecoder.createSource(context.getContentResolver(), u);
            Drawable d = ImageDecoder.decodeDrawable(src);
            v.setImageDrawable(d);
            if (d instanceof AnimatedImageDrawable) ((AnimatedImageDrawable) d).start();
        } catch (Throwable ignored) {}
    }

    private void animateIn(View card) {
        if (card == null) return;
        card.setAlpha(0f);
        card.setScaleX(0.94f);
        card.setScaleY(0.94f);
        AnimatorSet s = new AnimatorSet();
        s.playTogether(
                ObjectAnimator.ofFloat(card, "alpha", 0f, 1f),
                ObjectAnimator.ofFloat(card, "scaleX", 0.94f, 1f),
                ObjectAnimator.ofFloat(card, "scaleY", 0.94f, 1f));
        s.setDuration(240);
        s.start();

        View dim = root == null ? null : root.findViewById(R.id.dimLayer);
        if (dim != null) {
            dim.setAlpha(0f);
            dim.animate().alpha(AppPrefs.dimPct(context) / 100f).setDuration(240).start();
        }
    }

    private void scheduleDismiss() {
        handler.removeCallbacks(dismissRunnable);
        handler.postDelayed(dismissRunnable, AppPrefs.durationMs(context));
    }

    private final Runnable dismissRunnable = new Runnable() { @Override public void run() { dismiss(); } };

    public void dismiss() {
        if (root == null || dismissing) return;
        dismissing = true;
        handler.removeCallbacks(dismissRunnable);
        final View r = root;
        final WindowManager w = wm;
        root = null;
        if (r != null) {
            View card = r.findViewById(R.id.card);
            if (card != null) {
                card.animate().alpha(0f).scaleX(0.96f).scaleY(0.96f).setDuration(140)
                        .withEndAction(() -> removeNow(w, r)).start();
            } else {
                removeNow(w, r);
            }
        }
        dismissing = false;
    }

    private static void removeNow(WindowManager w, View r) {
        if (w == null) return;
        try { w.removeView(r); } catch (Throwable ignored) {}
    }

    private int dp(int n) {
        return (int) (n * context.getResources().getDisplayMetrics().density + 0.5f);
    }

    private int screenWidth() {
        return context.getResources().getDisplayMetrics().widthPixels;
    }
}
