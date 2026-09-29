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
import android.os.Handler;
import android.os.Looper;

import java.lang.reflect.Method;
import java.util.UUID;

/**
 * 只读 Android / 耳机真实提供的值，绝不编造。
 *
 * 通道一：BluetoothDevice.getBatteryLevel()（系统蓝牙栈，反射）
 * 通道二：标准 BLE Battery Service 0x180F / 0x2A19
 *
 * 系统栈在刚连上时可能还没填好电量，所以会连续探测若干次，
 * 数值一变化就回调（弹窗原地更新，不会重建、不会闪）。
 */
public final class BluetoothBatteryReader {

    public interface Callback {
        void onState(BatteryState state);
    }

    private static final UUID BATTERY_SERVICE = UUID.fromString("0000180F-0000-1000-8000-00805F9B34FB");
    private static final UUID BATTERY_LEVEL = UUID.fromString("00002A19-0000-1000-8000-00805F9B34FB");

    private static final String SRC_SYSTEM = "Android 蓝牙";
    private static final String SRC_BLE = "BLE Battery Service";
    private static final String SRC_NONE = "未提供电量";

    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());

    public BluetoothBatteryReader(Context c) {
        context = c.getApplicationContext();
    }

    public void read(final BluetoothDevice d, final Callback cb) {
        if (d == null || cb == null) return;

        final int[] last = new int[]{-1};
        final boolean[] gattTried = new boolean[]{false};

        final Runnable probe = new Runnable() {
            @Override
            public void run() {
                int v = getSystemLevel(d);
                if (v >= 0 && v != last[0]) {
                    last[0] = v;
                    cb.onState(BatteryState.aggregateOnly(v, SRC_SYSTEM));
                }
            }
        };

        probe.run();
        for (int i = 1; i <= 6; i++) {
            handler.postDelayed(probe, i * 700L);
        }

        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (last[0] < 0 && !gattTried[0]) {
                    gattTried[0] = true;
                    readGatt(d, cb);
                }
            }
        }, 1500L);
    }

    private void readGatt(final BluetoothDevice d, final Callback cb) {
        if (android.os.Build.VERSION.SDK_INT >= 31
                && context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            cb.onState(BatteryState.unknown("无 BLUETOOTH_CONNECT 权限"));
            return;
        }
        try {
            d.connectGatt(context, false, new BluetoothGattCallback() {
                @Override
                public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        try {
                            g.discoverServices();
                        } catch (Throwable ignored) {
                            close(g);
                        }
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        close(g);
                    }
                }

                @Override
                public void onServicesDiscovered(BluetoothGatt g, int status) {
                    try {
                        BluetoothGattService s = g.getService(BATTERY_SERVICE);
                        BluetoothGattCharacteristic c = s == null ? null : s.getCharacteristic(BATTERY_LEVEL);
                        if (c != null && g.readCharacteristic(c)) return;
                    } catch (Throwable ignored) {
                    }
                    cb.onState(BatteryState.unknown(SRC_NONE));
                    close(g);
                }

                @Override
                public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
                    if (status == BluetoothGatt.GATT_SUCCESS && BATTERY_LEVEL.equals(c.getUuid())) {
                        byte[] raw = c.getValue();
                        int v = (raw == null || raw.length == 0) ? -1 : (raw[0] & 0xff);
                        if (v >= 0 && v <= 100) {
                            cb.onState(BatteryState.aggregateOnly(v, SRC_BLE));
                        } else {
                            cb.onState(BatteryState.unknown(SRC_NONE));
                        }
                    }
                    close(g);
                }

                private void close(BluetoothGatt g) {
                    try {
                        g.disconnect();
                    } catch (Throwable ignored) {
                    }
                    try {
                        g.close();
                    } catch (Throwable ignored) {
                    }
                }
            }, BluetoothDevice.TRANSPORT_LE);
        } catch (Throwable t) {
            cb.onState(BatteryState.unknown("蓝牙不可用"));
        }
    }

    private int getSystemLevel(BluetoothDevice d) {
        try {
            Method m = BluetoothDevice.class.getMethod("getBatteryLevel");
            Object r = m.invoke(d);
            if (r instanceof Integer) {
                int v = (Integer) r;
                if (v >= 0 && v <= 100) return v;
            }
        } catch (Throwable ignored) {
        }
        return -1;
    }
}
