package com.yuanbao.earbuds;

import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;

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
        prefs = new Prefs(this);
        lastShownAt = System.currentTimeMillis();

        setupWindow();
        setContentView(R.layout.activity_popup);

        String name = getIntent().getStringExtra(PopupService.EXTRA_NAME);
        int battery = getIntent().getIntExtra(PopupService.EXTRA_BATTERY, -1);
        int caseBattery = getIntent().getIntExtra(PopupService.EXTRA_CASE, -1);

        View card = findViewById(R.id.card);
        if (card != null) {
            int w = (int) (prefs.widthDp() * getResources().getDisplayMetrics().density);
            android.widget.FrameLayout.LayoutParams lp =
                    new android.widget.FrameLayout.LayoutParams(
                            w, android.widget.FrameLayout.LayoutParams.WRAP_CONTENT);
            lp.gravity = gravityByPos(prefs.position());
            int m = marginForPos(prefs.position());
            lp.topMargin = (prefs.position() == 0) ? m : 0;
            lp.bottomMargin = (prefs.position() == 2) ? m : 0;
            card.setLayoutParams(lp);
        }

        PopupRenderer.bind(this, findViewById(R.id.popupRoot), name, battery, caseBattery,
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
        if (pos == 2) return (int) (120 * d);
        return 0;
    }

    private void setupWindow() {
        Window w = getWindow();
        w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        w.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL);  // 弹窗外区域点击穿透到下层应用
        w.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
        w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);       // 系统弹窗标志性的背景压暗
        w.setDimAmount(prefs.dimAmount());
        if (prefs.notFocusable()) {
            w.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE); // 打游戏/输入时不夺取焦点
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            int r = prefs.blurRadius();
            if (r > 0) {
                try {
                    w.setBackgroundBlurRadius(r);  // Android 12+ 窗口背景模糊
                } catch (Throwable ignored) {
                }
            }
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
        if (!isFinishing()) {
            finish();
            overridePendingTransition(0, R.anim.popup_out);
        }
    }

    @Override
    protected void onDestroy() {
        main.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    /** 供服务调用：构造启动 Intent */
    static Intent makeIntent(Context c, String name, int battery, int caseBattery) {
        Intent i = new Intent(c, PopupActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_SINGLE_TOP
                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_NO_USER_ACTION);
        i.putExtra(PopupService.EXTRA_NAME, name);
        i.putExtra(PopupService.EXTRA_BATTERY, battery);
        i.putExtra(PopupService.EXTRA_CASE, caseBattery);
        return i;
    }
}
