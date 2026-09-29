package com.earpopupx;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import java.util.HashSet;
import java.util.Set;

public final class BluetoothMonitorService extends Service {

    private static final String CHANNEL = "earpopupx_monitor";
    private static final String ACTION_BATTERY = "android.bluetooth.device.action.BATTERY_LEVEL_CHANGED";
    private static final String EXTRA_LEVEL = "android.bluetooth.device.extra.BATTERY_LEVEL";

    private BluetoothBatteryReader reader;
    private EarPopupWindow popup;
    private BroadcastReceiver receiver;
    private final Set<String> shown = new HashSet<>();
    private String current;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        reader = new BluetoothBatteryReader(this);
        popup = new EarPopupWindow(this);
        register();
    }

    @Override
    public int onStartCommand(Intent i, int flags, int id) {
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
                .setContentText("正在监听耳机连接")
                .setOngoing(true)
                .build();
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(7, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            } else {
                startForeground(7, n);
            }
        } catch (Throwable t) {
            try {
                startForeground(7, n);
            } catch (Throwable ignored) {
            }
        }
    }

    private void register() {
        receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                if (i == null) return;
                String a = i.getAction();
                if (BluetoothDevice.ACTION_ACL_CONNECTED.equals(a)) {
                    BluetoothDevice d = getDevice(i);
                    if (d != null) handleConnected(d);
                } else if (BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(a)) {
                    BluetoothDevice d = getDevice(i);
                    if (d != null) handleDisconnected(d);
                } else if (ACTION_BATTERY.equals(a)) {
                    BluetoothDevice d = getDevice(i);
                    if (d == null) return;
                    int v = i.getIntExtra(EXTRA_LEVEL, -1);
                    if (v < 0 || v > 100) return;
                    popup.updateIfVisible(safeName(d), BatteryState.aggregateOnly(v, "系统蓝牙广播"));
                }
            }
        };
        IntentFilter f = new IntentFilter();
        f.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
        f.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        f.addAction(ACTION_BATTERY);
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(receiver, f);
            }
        } catch (Throwable ignored) {
        }
    }

    private void handleConnected(final BluetoothDevice d) {
        if (Build.VERSION.SDK_INT >= 31
                && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        if (!AppPrefs.enabled(this)) return;
        final String addr;
        try {
            addr = d.getAddress();
        } catch (Throwable t) {
            return;
        }
        if (addr == null) return;
        if (shown.contains(addr)) return;
        shown.add(addr);
        current = addr;
        reader.read(d, new BluetoothBatteryReader.Callback() {
            @Override
            public void onState(BatteryState state) {
                if (!AppPrefs.enabled(BluetoothMonitorService.this)) return;
                if (!addr.equals(current)) return;
                popup.show(safeName(d), state);
            }
        });
    }

    private void handleDisconnected(BluetoothDevice d) {
        String addr;
        try {
            addr = d.getAddress();
        } catch (Throwable t) {
            return;
        }
        if (addr == null) return;
        shown.remove(addr);
        if (addr.equals(current)) {
            current = null;
            popup.dismiss();
        }
    }

    private BluetoothDevice getDevice(Intent i) {
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                return i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class);
            }
            return i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
        } catch (Throwable t) {
            return null;
        }
    }

    private String safeName(BluetoothDevice d) {
        try {
            String n = d.getName();
            return (n == null || n.length() == 0) ? "蓝牙耳机" : n;
        } catch (Throwable t) {
            return "蓝牙耳机";
        }
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm == null) return;
            NotificationChannel ch = new NotificationChannel(CHANNEL, "耳机连接监控",
                    NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(ch);
        }
    }

    @Override
    public void onDestroy() {
        try {
            unregisterReceiver(receiver);
        } catch (Throwable ignored) {
        }
        if (popup != null) popup.dismiss();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent i) {
        return null;
    }
}
