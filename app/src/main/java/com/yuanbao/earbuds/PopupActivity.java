package com.yuanbao.earbuds;

import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

/**
 * 系统级弹窗引擎：一个透明的全屏 Activity。
 *
 * 为什么它比悬浮窗「高级」：
 *  - 走 Activity 窗口，能显示在锁屏之上（showWhenLocked / turnScreenOn）
 *  - 可用系统窗口转场动画（windowAnimationStyle），观感等同系统弹窗
 *  - 支持窗口背景模糊（Android 12+）与背景压暗（FLAG_DIM_BEHIND）
 *  - 启动它合法：已授予 SYSTEM_ALERT_WINDOW 的应用，不受 Android 10+ 后台启动 Activity 限制
 *  - 不在最近任务里出现（excludeFromRecents），不打断当前应用（FLAG_NOT_TOUCH_MODAL）
 */
public class PopupActivity extends AppCompatActivity {

    /** 最近一次成功显示的时刻，供服务判断 Activity 是否真的起来了 */
    public static volatile long lastShownAt = 0L;

    private final Handler main = new Handler(Looper.getMainLooper());
    private Prefs prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 若 3 秒内刚显示过悬浮窗，说明智能模式已经降级过一次，
        // 这个 Activity 是迟到启动的，会与悬浮窗叠成「闪两下」，直接结束自己。
        if (System.currentTimeMillis() - PopupService.lastOverlayShownAt < 3000L) {
            finish();
            overridePendingTransition(0, 0);
            return;
        }
        // 必须先初始化 PrefsHolder：
        // 图片的缩放/偏移存在这里，若 Activity 先于 Service 启动而没初始化，
        // 渲染时拿到 null 会回退成默认 1 倍，用户设的缩放就丢了。
        PopupRenderer.PrefsHolder.init(this);
        prefs = new Prefs(this);
        lastShownAt = System.currentTimeMillis();

        setupWindow();
        if (useMini()) {
            setContentView(R.layout.popup_mini);
            applyMini(getIntent());
        } else {
            setContentView(R.layout.activity_popup);
            applyIntent(getIntent());
        }
        // setContentView 才触发 PhoneWindow.generateLayout()，
        // 会在那里按主题重新加回 FLAG_DIM_BEHIND，所以必须在之后再清一次。
        clearDimBehind();
    }

    /** 横屏 + 用户选了迷你模式 */
    private boolean useMini() {
        return prefs != null && prefs.landscapeMode() == 2 && isLandscape();
    }

    private boolean isLandscape() {
        return getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_LANDSCAPE;
    }

    /**
     * 彻底清掉窗口压暗。
     * 用户反复反馈的「四角黑色直角边」有两条来源：
     *   ① 主题 backgroundDimEnabled（已改为 false）
     *   ② generateLayout() 在 setContentView 时重新加回的 FLAG_DIM_BEHIND
     * 这里两道都堵住，并把 decorView 背景显式设为透明。
     */
    private void clearDimBehind() {
        Window w = getWindow();
        if (w == null) return;
        w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        w.setDimAmount(0f);
        View decor = w.getDecorView();
        if (decor != null) decor.setBackgroundColor(Color.TRANSPARENT);
    }

    /** 迷你窗渲染：只有设备名 + 一个真实电量，读不到就 --% */
    private void applyMini(Intent it) {
        if (it == null) return;
        String name = it.getStringExtra(PopupService.EXTRA_NAME);
        int overall = it.getIntExtra(PopupService.EXTRA_OVERALL, -1);
        int left = it.getIntExtra(PopupService.EXTRA_LEFT, -1);
        int right = it.getIntExtra(PopupService.EXTRA_BATTERY, -1);

        TextView tvName = findViewById(R.id.miniName);
        TextView tvBat = findViewById(R.id.miniBattery);
        if (tvName != null) {
            tvName.setText((name == null || name.isEmpty()) ? "耳机已连接" : name);
        }
        if (tvBat != null) {
            int v = BatteryLevels.valid(left) ? left
                    : (BatteryLevels.valid(right) ? right : overall);
            tvBat.setText(BatteryLevels.valid(v) ? (v + "%") : "--%");
        }

        View mini = findViewById(R.id.miniCard);
        if (mini != null) {
            float dens = getResources().getDisplayMetrics().density;
            int h = (int) (40 * dens);
            int screenH = getResources().getDisplayMetrics().heightPixels;
            android.widget.FrameLayout.LayoutParams lp =
                    new android.widget.FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            lp.topMargin = PopupService.topOffsetForVPos(
                    prefs.verticalPos(), h, screenH, dens, prefs.bottomPadDp());
            mini.setLayoutParams(lp);
            mini.setAlpha(0f);
            mini.animate().alpha(1f).setDuration(180).start();
        }

        main.postDelayed(this::close, prefs.durationMs());
    }

    /**
     * 点击弹窗外部立即关闭。
     *
     * 之前「弹出后点外面没反应，只能等它自己消失」：
     * 窗口是全屏 + FLAG_NOT_TOUCH_MODAL，外部触摸被直接派发给下层应用，
     * 本窗口根本收不到事件。必须加 FLAG_WATCH_OUTSIDE_TOUCH，
     * 系统才会额外补发一个 ACTION_OUTSIDE 给本窗口。
     */
    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (prefs != null && prefs.touchOutsideClose() && ev != null) {
            int action = ev.getActionMasked();
            if (action == MotionEvent.ACTION_OUTSIDE) {
                close();
                return true;
            }
            if (action == MotionEvent.ACTION_DOWN) {
                View target = findViewById(R.id.card);
                if (target == null) target = findViewById(R.id.miniCard);
                if (target != null && target.getWidth() > 0) {
                    int[] loc = new int[2];
                    target.getLocationOnScreen(loc);
                    float x = ev.getRawX();
                    float y = ev.getRawY();
                    boolean outside = x < loc[0] || x > loc[0] + target.getWidth()
                            || y < loc[1] || y > loc[1] + target.getHeight();
                    if (outside) {
                        close();
                        return true;
                    }
                }
            }
        }
        return super.dispatchTouchEvent(ev);
    }

    /**
     * 某些 ROM 会在 onResume 之后再次按主题刷新窗口属性，
     * 这里兜底再清一次压暗，确保任何时刻都不会出现四角暗块。
     */
    @Override
    protected void onResume() {
        super.onResume();
        clearDimBehind();
    }

    /**
     * Activity 复用时必须走这里。
     * manifest 里 launchMode="singleInstance"，且启动 Intent 带了
     * SINGLE_TOP / CLEAR_TOP：当上一个弹窗还没消失就来新弹窗时，
     * 系统不会重新 onCreate，而是回调 onNewIntent。
     * 之前没重写 onNewIntent，结果第二次弹窗什么都不做——
     * 内容不更新、计时不重置、GIF 从上次的进度继续播（就是用户说的「续播」）。
     */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        lastShownAt = System.currentTimeMillis();
        // 先取消上一次的自动关闭计时，再重新走一遍绑定
        main.removeCallbacksAndMessages(null);
        boolean wantMini = useMini();
        boolean miniNow = findViewById(R.id.miniCard) != null;
        if (wantMini != miniNow) {
            setContentView(wantMini ? R.layout.popup_mini : R.layout.activity_popup);
        }
        if (wantMini) applyMini(intent);
        else applyIntent(intent);
        clearDimBehind();
    }

    private void applyIntent(Intent it) {
        if (it == null) return;
        String name = it.getStringExtra(PopupService.EXTRA_NAME);
        BatteryLevels levels = new BatteryLevels();
        levels.left = it.getIntExtra(PopupService.EXTRA_LEFT, -1);
        levels.right = it.getIntExtra(PopupService.EXTRA_BATTERY, -1);
        levels.caseBox = it.getIntExtra(PopupService.EXTRA_CASE, -1);
        // 整机值要单独取，之前把 EXTRA_BATTERY（其实是右耳）当成 overall 了
        levels.overall = it.getIntExtra(PopupService.EXTRA_OVERALL, -1);
        if (!BatteryLevels.valid(levels.overall)) {
            levels.overall = levels.right;
        }
        levels.sanitize();
        levels.fillFromOverall();
        levels.timestamp = System.currentTimeMillis();

        View card = findViewById(R.id.card);
        if (card != null) {
            float dens = getResources().getDisplayMetrics().density;
            int w = (int) (prefs.widthDp() * dens);
            // 必须用固定高度：媒体区子 View 是 match_parent，
            // 若这里给 WRAP_CONTENT，卡片高度会失控（表现为比例奇怪）。
            // 高度算法与 PopupRenderer 保持一致。
            float r = Math.max(0.40f, Math.min(0.94f, prefs.imageRatio()));
            int h = (int) (w * (0.45f + r * 0.33f));
            android.widget.FrameLayout.LayoutParams lp =
                    new android.widget.FrameLayout.LayoutParams(w, h);
            // 与悬浮窗引擎一致：连续垂直位置（0=贴顶 100=贴底）
            lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            int screenH = getResources().getDisplayMetrics().heightPixels;
            lp.topMargin = PopupService.topOffsetForVPos(
                    prefs.verticalPos(), h, screenH, dens, prefs.bottomPadDp());
            lp.bottomMargin = 0;
            card.setLayoutParams(lp);
            // 复用时残留的位移/透明度/缩放要清掉，否则第二次弹窗位置会飘
            card.setTranslationX(0);
            card.setTranslationY(0);
            card.setScaleX(1f);
            card.setScaleY(1f);
            card.setAlpha(1f);
            card.animate().cancel();
        }

        PopupRenderer.bind(this, findViewById(R.id.popupRoot), name, levels,
                prefs, this::close);

        main.postDelayed(this::close, prefs.durationMs());
    }

    private int gravityByPos(int pos) {
        if (pos == 0) return Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        if (pos == 2) return Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        return Gravity.CENTER;
    }

    private int marginForPos(int pos) {
        float d = getResources().getDisplayMetrics().density;
        if (pos == 0) return (int) (72 * d);
        // 沉底：与悬浮窗引擎保持一致
        if (pos == 2) return (int) (PopupService.BOTTOM_MARGIN_DP * d);
        return 0;
    }

    private void setupWindow() {
        Window w = getWindow();
        w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        w.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL);  // 弹窗外区域点击穿透到下层应用
        w.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
        // 背景压暗【已移除】。
        // FLAG_DIM_BEHIND + setDimAmount 会把整个窗口后面压一层黑。
        // 而卡片是圆角的 —— 圆角之外的四个角落没有被卡片覆盖，
        // 露出来的正是这层压暗，于是看到「圆角还在，但四角多出黑色直角边」。
        // 小米原生弹窗也是不压暗的：卡片直接浮在当前应用之上。
        // 需要压暗的话用卡片自身的 bgColor / LiveBlurView 的 dim 就够了。
        w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        w.setDimAmount(0f);
        if (prefs.notFocusable()) {
            w.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE); // 打游戏/输入时不夺取焦点
        }
        // 窗口级背景模糊改为【默认关闭】。
        // 它会在整个窗口后面渲染一层磨砂，在 HyperOS 上会吞掉弹窗外部的点击，
        // 也就是「全局变模糊、点外面没反应」的直接来源。
        // 想要柔化请用卡片自身的 LiveBlurView（只糊卡片底部，不影响触摸）。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            int r = prefs.windowBlur() ? prefs.blurRadius() : 0;
            try {
                w.setBackgroundBlurRadius(r);
            } catch (Throwable ignored) {
            }
        }
        // 点了弹窗外面要能立刻关掉：不加这个 flag，外部触摸会被直接
        // 派发给下层应用，本窗口收不到任何事件（见 dispatchTouchEvent）。
        if (prefs.touchOutsideClose()) {
            w.addFlags(WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH);
        }
        // 状态栏/导航栏透明
        w.setStatusBarColor(Color.TRANSPARENT);
        w.setNavigationBarColor(Color.TRANSPARENT);

        // 锁屏之上显示
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1 && prefs.showOnLock()) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
            KeyguardManager km = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
            if (km != null && km.isKeyguardLocked()) {
                km.requestDismissKeyguard(this, null);
            }
        }
    }

    @Override
    public void onBackPressed() {
        close();
    }

    private void close() {
        main.removeCallbacksAndMessages(null);
        if (isFinishing()) return;
        View card = findViewById(R.id.card);
        if (card != null) {
            // 先播完退场动画再 finish，让用户真的看见动画
            PopupRenderer.applyExit(card, prefs.animStyle(), () -> {
                finish();
                overridePendingTransition(0, 0);
            });
        } else {
            finish();
            overridePendingTransition(0, 0);
        }
    }

    @Override
    protected void onDestroy() {
        main.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    /** 供服务调用：构造启动 Intent */
    static Intent makeIntent(Context c, String name, BatteryLevels levels) {
        Intent i = new Intent(c, PopupActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_SINGLE_TOP
                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_NO_USER_ACTION);
        i.putExtra(PopupService.EXTRA_NAME, name);
        if (levels != null) {
            i.putExtra(PopupService.EXTRA_LEFT, levels.left);
            i.putExtra(PopupService.EXTRA_BATTERY, levels.right);
            i.putExtra(PopupService.EXTRA_CASE, levels.caseBox);
        }
        return i;
    }
}
