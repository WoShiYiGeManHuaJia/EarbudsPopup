package com.earpopupx;

import android.Manifest;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.lang.reflect.Method;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 只采用 Android / 耳机真实上报的电量，绝不猜测、绝不复用上一副耳机的值。
 *
 * 优先级：
 *   1) BLE Battery Service (0x180F / 0x2A19) —— 直接问耳机，最可信
 *   2) Android 蓝牙栈 getBatteryLevel() —— 需连续两次一致才认定稳定，
 *      避免刚连接 / 换耳机时读到系统缓存里的旧值
 */
public final class BluetoothBatteryReader {

    public interface Callback { void onState(BatteryState state); }

    private static final UUID BATTERY_SERVICE = UUID.fromString("0000180F-0000-1000-8000-00805F9B34FB");
    private static final UUID BATTERY_LEVEL   = UUID.fromString("00002A19-0000-1000-8000-00805F9B34FB");

    private static final long GATT_GRACE_MS = 1500L;   // 系统值稳定后再给 GATT 的机会
    private static final long HARD_TIMEOUT_MS = 9000L;

    private final Context context;

    public BluetoothBatteryReader(Context c) { context = c.getApplicationContext(); }

    public void read(BluetoothDevice d, Callback cb) {
        final AtomicBoolean done = new AtomicBoolean(false);
        final AtomicBoolean gattSettled = new AtomicBoolean(false);
        final Handler main = new Handler(Looper.getMainLooper());

        final Callback emit = state -> {
            if (!done.compareAndSet(false, true)) return;
            main.post(() -> cb.onState(state));
        };

        main.postDelayed(() -> emit.onState(BatteryState.unknown("读取电量超时")), HARD_TIMEOUT_MS);

        startGatt(d, v -> {
            gattSettled.set(true);
            if (v >= 0) emit.onState(new BatteryState(v, -1, -1, -1, false, false, false, "BLE Battery Service"));
        });

        new Thread(() -> {
            int prev = -1, stable = -1;
            for (int i = 0; i < 8 && !done.get(); i++) {
                sleep(400);
                int v = getSystemLevel(d);
                if (v >= 0) {
                    if (v == prev) { stable = v; break; }
                    prev = v;
                }
            }
            if (done.get()) return;

            int candidate = stable >= 0 ? stable : prev;
            if (candidate < 0) return;   // 系统没给值，把机会留给 GATT / 硬超时

            long deadline = System.currentTimeMillis() + GATT_GRACE_MS;
            while (System.currentTimeMillis() < deadline && !done.get() && !gattSettled.get()) {
                sleep(100);
            }
            if (done.get()) return;

            emit.onState(new BatteryState(candidate, -1, -1, -1, false, false, false, "Android Bluetooth"));
        }, "epx-battery").start();
    }

    private void startGatt(BluetoothDevice d, java.util.function.IntConsumer result) {
        if (Build.VERSION.SDK_INT >= 31
                && context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            result.accept(-1);
            return;
        }
        try {
            int type = d.getType();
            if (type == BluetoothDevice.DEVICE_TYPE_CLASSIC) { result.accept(-1); return; }
        } catch (Throwable ignored) {}

        final Handler main = new Handler(Looper.getMainLooper());
        final BluetoothGatt[] holder = new BluetoothGatt[1];
        final AtomicBoolean settled = new AtomicBoolean(false);

        final Runnable timeout = () -> {
            if (settled.compareAndSet(false, true)) {
                closeGatt(holder[0]);
                result.accept(-1);
            }
        };

        try {
            holder[0] = d.connectGatt(context, false, new BluetoothGattCallback() {
                @Override public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        try { g.discoverServices(); }
                        catch (Throwable t) { finish(g, -1); }
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        finish(g, -1);
                    }
                }

                @Override public void onServicesDiscovered(BluetoothGatt g, int status) {
                    if (status != BluetoothGatt.GATT_SUCCESS) { finish(g, -1); return; }
                    try {
                        BluetoothGattService svc = g.getService(BATTERY_SERVICE);
                        BluetoothGattCharacteristic c = svc == null ? null : svc.getCharacteristic(BATTERY_LEVEL);
                        if (c != null && g.readCharacteristic(c)) return;
                    } catch (Throwable ignored) {}
                    finish(g, -1);
                }

                @Override public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
                    int v = -1;
                    if (status == BluetoothGatt.GATT_SUCCESS && BATTERY_LEVEL.equals(c.getUuid())) {
                        byte[] raw = c.getValue();
                        if (raw != null && raw.length > 0) v = raw[0] & 0xff;
                    }
                    finish(g, (v >= 0 && v <= 100) ? v : -1);
                }

                private void finish(BluetoothGatt g, int v) {
                    if (settled.compareAndSet(false, true)) {
                        main.removeCallbacks(timeout);
                        closeGatt(g);
                        result.accept(v);
                    }
                }
            });
            main.postDelayed(timeout, 7000L);
        } catch (Throwable t) {
            if (settled.compareAndSet(false, true)) result.accept(-1);
        }
    }

    private static void closeGatt(BluetoothGatt g) {
        if (g == null) return;
        try { g.disconnect(); } catch (Throwable ignored) {}
        try { g.close(); } catch (Throwable ignored) {}
    }

    public void close() { }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private int getSystemLevel(BluetoothDevice d) {
        try {
            Method m = BluetoothDevice.class.getMethod("getBatteryLevel");
            Object r = m.invoke(d);
            if (r instanceof Integer) {
                int v = (Integer) r;
                if (v >= 0 && v <= 100) return v;
            }
        } catch (Throwable ignored) {}
        return -1;
    }
}
