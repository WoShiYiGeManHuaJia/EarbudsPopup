package com.earpopupx;

import android.animation.*;
import android.content.*;
import android.graphics.*;
import android.graphics.drawable.*;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;

/**
 * 系统级悬浮弹窗。
 *
 * 设计约束（来自用户实测反馈）：
 * 1. 绝不使用 FLAG_DIM_BEHIND —— 那层压暗是窗口级直角矩形，
 *    会在卡片圆角外侧露出两块半透明黑色直角边。"背景压暗"改为卡片背景自身
 *    的 alpha，永远被裁在圆角形状内。
 * 2. 圆角半径必须走 dp() 换算，背景圆角与 outline 圆角必须一致，
 *    否则背景直角矩形会从裁剪圆角外露出来。
 * 3. 电量未取到时显示"正在读取电量…"，绝不填充猜测值。
 */
public final class EarPopupWindow {

    private static EarPopupWindow singleton;

    public static synchronized EarPopupWindow shared(Context c) {
        if (singleton == null) singleton = new EarPopupWindow(c);
        return singleton;
    }

    private final Context c;
    private final Handler h = new Handler(Looper.getMainLooper());
    private WindowManager wm;
    private View root;
    private WindowManager.LayoutParams lp;
    private TextView title, battery, source;
    private ImageView media;
    private float dx, dy;
    private int sx, sy;
    private boolean dragging;
    private final Runnable autoDismiss = this::dismiss;

    private EarPopupWindow(Context x) { c = x.getApplicationContext(); }

    public void show(String n, BatteryState s) {
        if (Looper.myLooper() != Looper.getMainLooper()) { h.post(() -> show(n, s)); return; }
        if (!Settings.canDrawOverlays(c)) return;
        if (root == null) build(n, s); else update(n, s);
        apply();
        syncDrag();
        root.setVisibility(View.VISIBLE);
        animateIn();
        schedule();
    }

    public void update(String n, BatteryState s) {
        if (Looper.myLooper() != Looper.getMainLooper()) { h.post(() -> update(n, s)); return; }
        if (root == null) { show(n, s); return; }
        title.setText(n);
        battery.setText(format(s));
        source.setText("数据来源 · " + s.source);
    }

    private void build(String n, BatteryState s) {
        wm = (WindowManager) c.getSystemService(Context.WINDOW_SERVICE);

        FrameLayout card = new FrameLayout(c);
        card.setElevation(dp(22));
        card.setClipToOutline(true);

        LinearLayout content = new LinearLayout(c);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(14), dp(16), dp(14));

        int mediaRadius = dp(Math.max(18, AppPrefs.radiusDp(c) - 8));
        FrameLayout mf = new FrameLayout(c);
        mf.setBackground(round(Color.argb(70, 255, 255, 255), mediaRadius));
        mf.setClipToOutline(true);
        mf.setOutlineProvider(new ROutline(mediaRadius));
        media = new ImageView(c);
        media.setScaleType(ImageView.ScaleType.CENTER_CROP);
        loadMedia();
        mf.addView(media, new FrameLayout.LayoutParams(-1, dp(250)));
        content.addView(mf, new LinearLayout.LayoutParams(-1, dp(250)));

        FrameLayout row = new FrameLayout(c);
        title = txt(n, 22, true);
        title.setTextColor(Color.rgb(18, 20, 24));
        title.setMaxLines(2);
        title.setGravity(Gravity.CENTER_VERTICAL);
        FrameLayout.LayoutParams t = new FrameLayout.LayoutParams(-1, dp(58));
        t.leftMargin = dp(6);
        t.rightMargin = dp(52);
        row.addView(title, t);

        TextView close = txt("×", 30, false);
        close.setTextColor(Color.rgb(35, 36, 40));
        close.setGravity(Gravity.CENTER);
        close.setBackground(round(Color.argb(55, 0, 0, 0), dp(22)));
        close.setContentDescription("关闭");
        close.setOnClickListener(v -> dismiss());
        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(dp(44), dp(44), Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        cp.rightMargin = dp(2);
        row.addView(close, cp);
        content.addView(row, new LinearLayout.LayoutParams(-1, dp(60)));

        battery = txt(format(s), 18, true);
        battery.setTextColor(Color.rgb(28, 30, 34));
        battery.setGravity(Gravity.CENTER);
        content.addView(battery, new LinearLayout.LayoutParams(-1, dp(62)));

        source = txt("数据来源 · " + s.source, 11, false);
        source.setTextColor(Color.rgb(100, 103, 110));
        source.setGravity(Gravity.CENTER);
        content.addView(source, new LinearLayout.LayoutParams(-1, dp(26)));

        card.addView(content, new FrameLayout.LayoutParams(-1, -1));
        root = card;

        lp = new WindowManager.LayoutParams();
        lp.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        lp.format = PixelFormat.TRANSLUCENT;
        lp.gravity = Gravity.CENTER;
        // 关键：不使用 FLAG_DIM_BEHIND，避免窗口级压暗在圆角外露出直角黑边
        lp.flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        lp.dimAmount = 0f;

        refreshCardBackground();
        applyBlur();
        try { wm.addView(root, lp); } catch (Throwable e) { root = null; lp = null; }
    }

    private void applyBlur() {
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                if (wm.isCrossWindowBlurEnabled() && AppPrefs.blurDp(c) > 0) {
                    lp.setBlurBehindRadius(dp(AppPrefs.blurDp(c)));
                    lp.flags |= WindowManager.LayoutParams.FLAG_BLUR_BEHIND;
                }
            } catch (Throwable ignored) {}
        }
    }

    /** 卡片背景与裁剪圆角始终一致；"背景压暗"只改卡片自身 alpha，不会溢出圆角。 */
    private void refreshCardBackground() {
        if (root == null) return;
        int dim = AppPrefs.dimPercent(c);
        int alpha = 218 + Math.round((dim / 40f) * 37f);
        if (alpha > 255) alpha = 255;
        int r = dp(AppPrefs.radiusDp(c));
        root.setBackground(round(Color.argb(alpha, 248, 250, 252), r));
        root.setOutlineProvider(new ROutline(r));
        root.setClipToOutline(true);
    }

    private void syncDrag() {
        if (root == null) return;
        boolean want = AppPrefs.dragMode(c);
        if (want && !dragging) {
            root.setOnTouchListener((v, e) -> {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        dx = e.getRawX(); dy = e.getRawY(); sx = lp.x; sy = lp.y; return true;
                    case MotionEvent.ACTION_MOVE:
                        lp.x = sx + Math.round(e.getRawX() - dx);
                        lp.y = sy + Math.round(e.getRawY() - dy);
                        try { wm.updateViewLayout(root, lp); } catch (Throwable ignored) {}
                        return true;
                    case MotionEvent.ACTION_UP:
                        AppPrefs.setX(c, lp.x); AppPrefs.setY(c, lp.y); return true;
                }
                return true;
            });
            dragging = true;
        } else if (!want && dragging) {
            root.setOnTouchListener(null);
            dragging = false;
        }
    }

    public void refreshLayout() {
        if (Looper.myLooper() != Looper.getMainLooper()) { h.post(this::refreshLayout); return; }
        if (root == null) return;
        apply();
        syncDrag();
    }

    private void apply() {
        if (lp == null || root == null) return;
        lp.width = (int) (ResourcesHelper.width(c) * (AppPrefs.widthPercent(c) / 100f));
        lp.height = dp(AppPrefs.heightDp(c));
        lp.x = AppPrefs.x(c);
        lp.y = AppPrefs.y(c);
        lp.dimAmount = 0f;
        refreshCardBackground();
        try { wm.updateViewLayout(root, lp); } catch (Throwable ignored) {}
    }

    private void schedule() {
        h.removeCallbacks(autoDismiss);
        h.postDelayed(autoDismiss, AppPrefs.durationSec(c) * 1000L);
    }

    private void animateIn() {
        root.setAlpha(0f);
        root.setScaleX(.93f);
        root.setScaleY(.93f);
        AnimatorSet a = new AnimatorSet();
        a.playTogether(
                ObjectAnimator.ofFloat(root, "alpha", 0, 1),
                ObjectAnimator.ofFloat(root, "scaleX", .93f, 1),
                ObjectAnimator.ofFloat(root, "scaleY", .93f, 1));
        a.setDuration(240);
        a.setInterpolator(new DecelerateInterpolator());
        a.start();
    }

    public void dismiss() {
        if (Looper.myLooper() != Looper.getMainLooper()) { h.post(this::dismiss); return; }
        h.removeCallbacks(autoDismiss);
        if (wm == null || root == null) return;
        final View v = root;
        root = null;
        lp = null;
        dragging = false;
        // 淡出后再移除，避免突然消失造成的闪烁
        v.animate().alpha(0f).scaleX(.96f).scaleY(.96f).setDuration(160)
                .setListener(new AnimatorListenerAdapter() {
                    @Override public void onAnimationEnd(Animator animation) {
                        try { wm.removeView(v); } catch (Throwable ignored) {}
                    }
                }).start();
    }

    private void loadMedia() {
        String raw = AppPrefs.media(c);
        if (raw == null) return;
        try {
            Uri u = Uri.parse(raw);
            if (Build.VERSION.SDK_INT >= 28) {
                ImageDecoder.Source src = ImageDecoder.createSource(c.getContentResolver(), u);
                Drawable d = ImageDecoder.decodeDrawable(src);
                media.setImageDrawable(d);
                if (d instanceof AnimatedImageDrawable) ((AnimatedImageDrawable) d).start();
            } else {
                media.setImageURI(u);
            }
        } catch (Throwable ignored) {}
    }

    /** 三个槽位恒为 左耳 / 右耳 / 盒子；读不到的一侧显示 —，绝不编造数字。 */
    private String format(BatteryState s) {
        if (s.left >= 0 || s.right >= 0 || s.caseLevel >= 0) {
            return (s.left >= 0 ? "左耳 " + s.left + "%" : "左耳 —") + "   "
                    + (s.right >= 0 ? "右耳 " + s.right + "%" : "右耳 —") + "   "
                    + (s.caseLevel >= 0 ? "盒子 " + s.caseLevel + "%" : "盒子 —");
        }
        return s.aggregate >= 0 ? "电量  " + s.aggregate + "%" : "正在读取电量…";
    }

    private TextView txt(String s, float z, boolean b) {
        TextView v = new TextView(c);
        v.setText(s);
        v.setTextSize(z);
        v.setTypeface(null, b ? 1 : 0);
        return v;
    }

    private GradientDrawable round(int color, int r) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(r);
        return g;
    }

    private int dp(int n) {
        return (int) (n * c.getResources().getDisplayMetrics().density + .5f);
    }

    private static final class ROutline extends ViewOutlineProvider {
        final int r;
        ROutline(int x) { r = x; }
        @Override public void getOutline(View v, Outline o) {
            o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), r);
        }
    }

    private static final class ResourcesHelper {
        static int width(Context c) { return c.getResources().getDisplayMetrics().widthPixels; }
    }
}
