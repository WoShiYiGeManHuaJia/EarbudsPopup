package com.earpopupx;

import android.Manifest;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import java.lang.reflect.Method;
import java.util.UUID;

/** Reads only values actually supplied by Android/vendor Bluetooth. Never invents battery percentages. */
public final class BluetoothBatteryReader {
    public interface Callback { void onState(BatteryState state); }
    private static final UUID BATTERY_SERVICE = UUID.fromString("0000180F-0000-1000-8000-00805F9B34FB");
    private static final UUID BATTERY_LEVEL = UUID.fromString("00002A19-0000-1000-8000-00805F9B34FB");
    private final Context context;
    private volatile BluetoothGatt lastGatt;
    public BluetoothBatteryReader(Context c) { context=c.getApplicationContext(); }

    public void read(BluetoothDevice d, Callback cb) {
        final java.util.concurrent.atomic.AtomicBoolean finished = new java.util.concurrent.atomic.AtomicBoolean(false);
        Callback safe = state -> {
            if (!finished.compareAndSet(false, true)) return;
            new Handler(Looper.getMainLooper()).post(() -> cb.onState(state));
        };

        int level = getSystemLevel(d);
        if (level >= 0) { safe.onState(new BatteryState(level,-1,-1,-1,false,false,false,"Android Bluetooth")); return; }
        if (android.os.Build.VERSION.SDK_INT >= 31 && context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            safe.onState(BatteryState.unknown("无 BLUETOOTH_CONNECT 权限")); return;
        }

        // Standard Battery Service is a GATT/BLE service. Do not open a GATT connection
        // against classic-only headsets; many earbuds expose their battery through the
        // Bluetooth stack or a vendor protocol instead.
        try {
            int type = d.getType();
            if (type == BluetoothDevice.DEVICE_TYPE_CLASSIC) {
                safe.onState(BatteryState.unknown("未提供标准 BLE 电量"));
                return;
            }
        } catch (Throwable ignored) {}

        final BluetoothGatt[] holder = new BluetoothGatt[1];
        final Handler timeoutHandler = new Handler(Looper.getMainLooper());
        final Runnable timeout = () -> {
            safe.onState(BatteryState.unknown("读取电量超时"));
            BluetoothGatt g = holder[0];
            if (g != null) { try { g.disconnect(); } catch (Throwable ignored) {} try { g.close(); } catch (Throwable ignored) {} }
        };
        try {
            holder[0] = d.connectGatt(context, false, new BluetoothGattCallback() {
                private void finishGatt() {
                    timeoutHandler.removeCallbacks(timeout);
                    BluetoothGatt g = holder[0];
                    if (g != null) { try { g.disconnect(); } catch (Throwable ignored) {} try { g.close(); } catch (Throwable ignored) {} }
                }
                @Override public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        try { g.discoverServices(); } catch (Throwable t) { safe.onState(BatteryState.unknown("发现服务失败")); finishGatt(); }
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        safe.onState(BatteryState.unknown("BLE 连接失败")); finishGatt();
                    }
                }
                @Override public void onServicesDiscovered(BluetoothGatt g, int status) {
                    if (status != BluetoothGatt.GATT_SUCCESS) { safe.onState(BatteryState.unknown("发现电量服务失败")); finishGatt(); return; }
                    try {
                        android.bluetooth.BluetoothGattService svc = g.getService(BATTERY_SERVICE);
                        BluetoothGattCharacteristic c = svc == null ? null : svc.getCharacteristic(BATTERY_LEVEL);
                        if (c != null && g.readCharacteristic(c)) return;
                    } catch (Throwable ignored) {}
                    safe.onState(BatteryState.unknown("Bluetooth Battery Service unavailable"));
                    finishGatt();
                }
                @Override public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
                    if (status == BluetoothGatt.GATT_SUCCESS && BATTERY_LEVEL.equals(c.getUuid())) {
                        int v = c.getValue() == null || c.getValue().length == 0 ? -1 : (c.getValue()[0] & 0xff);
                        safe.onState(v >= 0 && v <= 100 ? new BatteryState(v,-1,-1,-1,false,false,false,"BLE Battery Service") : BatteryState.unknown("BLE 电量值无效"));
                    } else safe.onState(BatteryState.unknown("BLE read failed"));
                    finishGatt();
                }
            });
            lastGatt = holder[0];
            timeoutHandler.postDelayed(timeout, 9000L);
        } catch (Throwable t) {
            safe.onState(BatteryState.unknown("Bluetooth read unavailable"));
        }
    }

    /** Releases any GATT connection still held, so the monitor service can shut down cleanly. */
    public void close() {
        BluetoothGatt g = lastGatt;
        lastGatt = null;
        if (g != null) {
            try { g.disconnect(); } catch (Throwable ignored) {}
            try { g.close(); } catch (Throwable ignored) {}
        }
    }

    private int getSystemLevel(BluetoothDevice d) {
        try {
            Method m = BluetoothDevice.class.getMethod("getBatteryLevel");
            Object r = m.invoke(d);
            if (r instanceof Integer) { int v=(Integer)r; if (v>=0 && v<=100) return v; }
        } catch (Throwable ignored) {}
        return -1;
    }
}
