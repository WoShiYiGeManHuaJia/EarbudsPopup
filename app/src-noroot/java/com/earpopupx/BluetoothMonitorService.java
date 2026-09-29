package com.earpopupx;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** 常驻前台服务：监听耳机连接，读电量，弹窗。 */
public final class BluetoothMonitorService extends Service {
    private static final String CHANNEL = "earpopupx_monitor";
    private static final int NOTIFY_ID = 7;
    private static final long DISCONNECT_SUPPRESS_MS = 8000L;  // 断开后这段时间内忽略"已连接"补发
    private static final long SCAN_INTERVAL_MS = 5000L;        // 兜底轮询间隔

    private BluetoothBatteryReader reader;
    private EarPopupWindow popup;
    private BroadcastReceiver receiver;
    private final Set<String> shown = new HashSet<>();
    private final Map<String, Long> disconnectAt = new HashMap<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private String lastDevice = null;

    @Override public void onCreate() {
        super.onCreate();
        Diag.log("服务 onCreate");
        createChannel();
        reader = new BluetoothBatteryReader(this);
        popup  = new EarPopupWindow(this);
        register();
        startAsForeground();
        handler.post(scanRunnable);
    }

    @Override public int onStartCommand(Intent i, int flags, int id) {
        startAsForeground();
        return START_STICKY;
    }

    private void startAsForeground() {
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            b = new Notification.Builder(this, CHANNEL);
        } else {
            b = new Notification.Builder(this);
        }
        Notification n = b.setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle("EarPopup X")
                .setContentText(lastDevice == null ? "正在监听蓝牙耳机连接" : ("已监听：" + lastDevice))
                .setOngoing(true)
                .build();
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFY_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            } else {
                startForeground(NOTIFY_ID, n);
            }
        } catch (Throwable t) {
            try { startForeground(NOTIFY_ID, n); } catch (Throwable ignored) {}
        }
    }

    private void register() {
        receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                String a = i.getAction();
                if (a == null) return;
                BluetoothDevice d = getDevice(i);
                if (d == null) return;
                if (BluetoothDevice.ACTION_ACL_CONNECTED.equals(a)) {
                    Diag.log("收到 ACL_CONNECTED " + safeName(d));
                    handle(d);
                } else if (BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(a)) {
                    Diag.log("收到 ACL_DISCONNECTED " + safeName(d));
                    String addr = safeAddr(d);
                    shown.remove(addr);
                    disconnectAt.put(addr, System.currentTimeMillis());
                    if (addr.equals(lastDevice)) lastDevice = null;
                    popup.dismiss();
                } else if ("android.bluetooth.device.action.BATTERY_LEVEL_CHANGED".equals(a)) {
                    int v = i.getIntExtra("android.bluetooth.device.extra.BATTERY_LEVEL", -1);
                    if (v >= 0 && v <= 100 && popup.isShowing()) {
                        popup.updateState(new BatteryState(v, -1, -1, -1, false, false, false, "系统电量广播"));
                    }
                }
            }
        };
        IntentFilter f = new IntentFilter();
        f.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
        f.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        f.addAction("android.bluetooth.device.action.BATTERY_LEVEL_CHANGED");
        try {
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED);
            else registerReceiver(receiver, f);
            Diag.log("广播接收器已注册");
        } catch (Throwable t) { Diag.log("注册失败: " + t); }
    }

    /** 兜底轮询：广播收不到时也能发现已连接的耳机 */
    private final Runnable scanRunnable = new Runnable() {
        @Override public void run() {
            try { scanConnected(); } catch (Throwable ignored) {}
            handler.postDelayed(this, SCAN_INTERVAL_MS);
        }
    };

    private void scanConnected() {
        BluetoothAdapter ad = BluetoothAdapter.getDefaultAdapter();
        if (ad == null) return;
        if (Build.VERSION.SDK_INT >= 31 &&
                checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return;
        Set<BluetoothDevice> bonded;
        try { bonded = ad.getBondedDevices(); } catch (Throwable t) { return; }
        if (bonded == null) return;
        for (BluetoothDevice d : bonded) {
            if (!isConnected(d)) continue;
            String addr = safeAddr(d);
            if (addr == null || shown.contains(addr)) continue;
            Long off = disconnectAt.get(addr);
            if (off != null && System.currentTimeMillis() - off < DISCONNECT_SUPPRESS_MS) continue;
            Diag.log("轮询发现已连接设备 " + safeName(d));
            handle(d);
        }
    }

    private static boolean isConnected(BluetoothDevice d) {
        try {
            Method m = BluetoothDevice.class.getMethod("isConnected");
            Object r = m.invoke(d);
            if (r instanceof Boolean) return (Boolean) r;
        } catch (Throwable ignored) {}
        return false;
    }

    private void handle(BluetoothDevice d) {
        if (Build.VERSION.SDK_INT >= 31 &&
                checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            Diag.log("缺少 BLUETOOTH_CONNECT 权限，跳过");
            return;
        }
        if (!Settings.canDrawOverlays(this)) {
            Diag.log("缺少悬浮窗权限，无法弹窗");
            return;
        }
        final String addr = safeAddr(d);
        if (addr == null) return;
        final String nm = safeName(d);
        if (shown.contains(addr)) return;
        shown.add(addr);
        disconnectAt.remove(addr);
        lastDevice = nm;
        Diag.log("开始读取电量: " + nm);
        reader.read(d, (s, provisional) -> {
            Diag.log("电量回调 [" + nm + "] " + s.describe() + " (" + s.source + ")"
                    + (provisional ? " 中间值" : " 稳定值"));
            if (!AppPrefs.enabled(BluetoothMonitorService.this)) return;
            if (popup.isShowing()) popup.updateState(s);
            else popup.show(nm, s);
            startAsForeground();
        });
    }

    private static BluetoothDevice getDevice(Intent i) {
        try {
            if (Build.VERSION.SDK_INT >= 33) return i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class);
            return (BluetoothDevice) i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
        } catch (Throwable t) { return null; }
    }

    private static String safeAddr(BluetoothDevice d) {
        try {
            if (Build.VERSION.SDK_INT >= 31) { /* 需权限，交由 handle 前判断 */ }
            return d.getAddress();
        } catch (Throwable t) { return null; }
    }

    private static String safeName(BluetoothDevice d) {
        try { String n = d.getName(); return n == null ? "蓝牙耳机" : n; } catch (Throwable t) { return "蓝牙耳机"; }
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm == null) return;
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "耳机连接监控", NotificationManager.IMPORTANCE_LOW));
        }
    }

    @Override public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
        Diag.log("任务被移除，尝试自启");
        try {
            Intent i = new Intent(this, BluetoothMonitorService.class);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
        } catch (Throwable ignored) {}
    }

    @Override public void onDestroy() {
        Diag.log("服务 onDestroy");
        try { unregisterReceiver(receiver); } catch (Throwable ignored) {}
        handler.removeCallbacks(scanRunnable);
        if (popup != null) popup.dismiss();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent i) { return null; }
}
