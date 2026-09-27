package com.yuanbao.earbuds;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHeadset;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;

import com.bumptech.glide.Glide;

/**
 * 常驻前台服务：监听蓝牙 / 有线耳机的连接事件，弹出自定义悬浮窗动画。
 */
public class PopupService extends Service {

    public static final String ACTION_SHOW = "com.yuanbao.earbuds.ACTION_SHOW";
    public static final String ACTION_RESTART = "com.yuanbao.earbuds.ACTION_RESTART";
    public static final String EXTRA_NAME = "name";
    public static final String EXTRA_BATTERY = "battery";
    public static final String EXTRA_CASE = "case_battery";
    public static final String EXTRA_ADDRESS = "address";
    public static final String EXTRA_WIRED = "wired";

    private static final String CHANNEL_ID = "popup_service";
    private static final String CHANNEL_SILENT = "popup_service_silent";
    private static final int NOTI_ID = 1001;

    private WindowManager wm;
    private View current;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Prefs prefs;
    private volatile boolean screenOn = true;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            handle(i);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = new Prefs(this);
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        startForeground(NOTI_ID, buildNotification());
        refreshNotificationVisibility();

        IntentFilter f = new IntentFilter();
        f.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
        f.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        f.addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED);
        f.addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED);
        f.addAction(Intent.ACTION_HEADSET_PLUG);
        f.addAction(ACTION_SHOW);
        f.addAction(ACTION_RESTART);
        // 省电：监听屏幕开关，熄屏时按设置决定是否弹窗，避免无谓唤醒
        if (prefs.powerSave()) {
            f.addAction(Intent.ACTION_SCREEN_ON);
            f.addAction(Intent.ACTION_SCREEN_OFF);
        }
        registerReceiver(receiver, f);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_SHOW.equals(intent.getAction())) {
            String name = intent.getStringExtra(EXTRA_NAME);
            String addr = intent.getStringExtra(EXTRA_ADDRESS);
            int battery = intent.getIntExtra(EXTRA_BATTERY, -1);
            int cb = intent.getIntExtra(EXTRA_CASE, -1);
            show(name == null ? "耳机" : name, addr, battery, cb);
        }
        return START_STICKY;
    }

    private Notification buildNotification() {
        boolean hide = prefs.hideNotification();
        String useChannel = hide && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? CHANNEL_SILENT : CHANNEL_ID;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                if (hide) {
                    NotificationChannel ch = new NotificationChannel(
                            CHANNEL_SILENT, "后台服务（静默）",
                            NotificationManager.IMPORTANCE_MIN);
                    ch.setDescription("不显示通知，仅保持服务运行");
                    ch.setShowBadge(false);
                    ch.enableLights(false);
                    ch.enableVibration(false);
                    ch.setSound(null, null);
                    nm.createNotificationChannel(ch);
                } else {
                    NotificationChannel ch = new NotificationChannel(
                            CHANNEL_ID, "耳机弹窗服务", NotificationManager.IMPORTANCE_LOW);
                    ch.setDescription("保持弹窗服务在后台运行");
                    ch.setShowBadge(false);
                    nm.createNotificationChannel(ch);
                }
            }
        }
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        android.app.PendingIntent pi = android.app.PendingIntent.getActivity(
                this, 1, open,
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                        ? android.app.PendingIntent.FLAG_IMMUTABLE
                        : 0);
        return new NotificationCompat.Builder(this, useChannel)
                .setContentTitle("耳机弹窗已就绪")
                .setContentText("连接耳机时会显示自定义弹窗")
                .setSmallIcon(R.drawable.ic_stat_notify)
                .setContentIntent(pi)
                .setOngoing(!hide)
                .setSilent(true)
                .setPriority(hide ? NotificationCompat.PRIORITY_MIN
                        : NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .build();
    }

    private void handle(Intent i) {
        if (i == null || i.getAction() == null) return;

        // 省电：熄屏时跳过弹窗，避免唤醒屏幕白白耗电
        if (prefs.powerSave() && Intent.ACTION_SCREEN_OFF.equals(i.getAction())) {
            screenOn = false;
            return;
        }
        if (Intent.ACTION_SCREEN_ON.equals(i.getAction())) {
            screenOn = true;
            return;
        }
        if (prefs.powerSave() && !screenOn
                && !ACTION_SHOW.equals(i.getAction())
                && !ACTION_RESTART.equals(i.getAction())) {
            return;
        }

        // 重新加载设置并应用（通知可见性 / 省电策略变了）
        if (ACTION_RESTART.equals(i.getAction())) {
            prefs = new Prefs(this);
            refreshNotificationVisibility();
            startForeground(NOTI_ID, buildNotification());
            return;
        }
        if (!prefs.masterEnabled()) return;

        String action = i.getAction();

        if (ACTION_SHOW.equals(action)) {
            show(i.getStringExtra(EXTRA_NAME), i.getStringExtra(EXTRA_ADDRESS),
                    i.getIntExtra(EXTRA_BATTERY, -1), i.getIntExtra(EXTRA_CASE, -1));
            return;
        }

        if (Intent.ACTION_HEADSET_PLUG.equals(action)) {
            int state = i.getIntExtra("state", 0);
            if (state == 1 && prefs.wiredEnabled()) {
                String name = i.getStringExtra("name");
                show(name == null || name.isEmpty() ? "有线耳机" : name, null, -1, -1);
            }
            return;
        }

        BluetoothDevice dev = i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
        if (dev == null) return;

        boolean connected;
        if (BluetoothDevice.ACTION_ACL_CONNECTED.equals(action)) {
            connected = true;
        } else if (BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(action)) {
            connected = false;
        } else {
            int st = i.getIntExtra(BluetoothA2dp.EXTRA_STATE, -1);
            connected = (st == BluetoothA2dp.STATE_CONNECTED
                    || st == BluetoothHeadset.STATE_CONNECTED);
        }
        if (!connected) return;

        String name = safeName(dev);
        String addr = dev.getAddress();
        if (!prefs.isAllowed(addr)) return;
        show(name, addr, prefs.showBattery() ? readBattery(dev) : -1, -1);
    }

    private String safeName(BluetoothDevice dev) {
        try {
            String n = dev.getName();
            return (n == null || n.isEmpty()) ? "蓝牙耳机" : n;
        } catch (SecurityException e) {
            return "蓝牙耳机";
        }
    }

    /** 把蓝牙广播名换成更好看的显示名 */
    private String prettyName(String raw) {
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

    /**
     * 后台通知可见性。
     * Android 8+ 系统强制前台服务必须带通知，App 无法凭空取消；
     * 但可以把渠道重要性降到最低、并引导用户在系统设置里彻底关闭，
     * 关掉后通知栏不再显示，服务照常运行。
     */
    private void refreshNotificationVisibility() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        boolean hide = prefs.hideNotification();
        if (hide) {
            NotificationChannel c = new NotificationChannel(
                    CHANNEL_SILENT, "后台服务（已隐藏）",
                    NotificationManager.IMPORTANCE_MIN);
            c.setShowBadge(false);
            c.setSound(null, null);
            c.enableVibration(false);
            c.enableLights(false);
            nm.createNotificationChannel(c);
        }
    }

    /** 省电：把服务降级为普通后台服务并撤销前台通知（会略微降低优先级） */
    private void applyPowerSave() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && prefs.hideNotification()) {
            try {
                stopForeground(STOP_FOREGROUND_DETACH);
            } catch (Throwable ignored) {
            }
        }
    }

    /** 安卓没有公开的耳机电量 API，用反射读隐藏方法；失败返回 -1（不显示电量） */
    private int readBattery(BluetoothDevice dev) {
        try {
            java.lang.reflect.Method m = dev.getClass().getMethod("getBatteryLevel");
            Object r = m.invoke(dev);
            if (r instanceof Integer) return (Integer) r;
        } catch (Throwable ignored) {
        }
        return -1;
    }

    // ---------------- 弹窗渲染 ----------------

    private void show(String name, String address, int battery, int caseBattery) {
        main.post(() -> launch(name, address, battery, caseBattery));
    }

    /**
     * 按引擎分发：系统级透明 Activity / 悬浮窗 / 智能降级。
     * 之所以能从后台启动 Activity：已授予 SYSTEM_ALERT_WINDOW 的应用
     * 属于 Android 10+ 后台启动 Activity 限制的官方例外之一。
     */
    private void launch(String name, String address, int battery, int caseBattery) {
        int engine = prefs.engine();
        boolean canOverlay = android.provider.Settings.canDrawOverlays(this);

        if (engine == 1) {
            showOverlay(name, address, battery, caseBattery);
            return;
        }

        if (engine == 0) {
            try {
                startActivity(PopupActivity.makeIntent(this, name, battery, caseBattery));
            } catch (Exception e) {
                if (canOverlay) showOverlay(name, address, battery, caseBattery);
            }
            return;
        }

        // 智能模式：先试系统级 Activity，800ms 后确认没起来就降级悬浮窗
        PopupActivity.lastShownAt = 0L;
        try {
            startActivity(PopupActivity.makeIntent(this, name, battery, caseBattery));
        } catch (Exception e) {
            if (canOverlay) showOverlay(name, address, battery, caseBattery);
            return;
        }
        main.postDelayed(() -> {
            if (PopupActivity.lastShownAt <= 0L && canOverlay) {
                showOverlay(name, address, battery, caseBattery);
            }
        }, 800);
    }

    private void showOverlay(String name, String address, int battery, int caseBattery) {
        dismiss();
        if (!android.provider.Settings.canDrawOverlays(this)) return;

        View v = LayoutInflater.from(this).inflate(R.layout.popup_card, null);
        PopupRenderer.bind(this, v, name, battery, caseBattery, prefs, this::dismiss);

        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                (int) dp(prefs.widthDp()),
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);

        int pos = prefs.position();
        if (pos == 0) {
            p.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            p.y = (int) dp(72);
        } else if (pos == 2) {
            p.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
            p.y = (int) dp(120);
        } else {
            p.gravity = Gravity.CENTER;
            p.y = 0;
        }

        try {
            wm.addView(v, p);
        } catch (Exception e) {
            Toast.makeText(this, "弹窗显示失败，请检查悬浮窗权限", Toast.LENGTH_SHORT).show();
            return;
        }
        current = v;

        // 入场动画
        int style = prefs.animStyle();
        if (style == 1) {
            v.setTranslationY(dp(220));
            v.setAlpha(0f);
            v.animate().translationY(0).alpha(1f).setDuration(340)
                    .setInterpolator(new DecelerateInterpolator()).start();
        } else if (style == 2) {
            v.setTranslationY(-dp(220));
            v.setAlpha(0f);
            v.animate().translationY(0).alpha(1f).setDuration(340)
                    .setInterpolator(new DecelerateInterpolator()).start();
        } else {
            v.setScaleX(0.86f);
            v.setScaleY(0.86f);
            v.setAlpha(0f);
            v.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(300)
                    .setInterpolator(new DecelerateInterpolator()).start();
        }

        v.postDelayed(this::dismiss, prefs.durationMs());
    }

    private void dismiss() {
        if (current != null) {
            final View v = current;
            current = null;
            v.animate().cancel();
            v.animate().alpha(0f).translationY(-dp(24)).setDuration(200)
                    .withEndAction(() -> {
                        try {
                            wm.removeView(v);
                        } catch (Exception ignored) {
                        }
                    }).start();
        }
    }

    private float dp(float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    @Override
    public void onDestroy() {
        dismiss();
        try {
            unregisterReceiver(receiver);
        } catch (Exception ignored) {
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
