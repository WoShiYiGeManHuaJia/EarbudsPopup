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
 * 电量读取。三条通道：
 *   1) 系统 BluetoothDevice.getBatteryLevel()（隐藏 API，反射）— 同步，立即可用
 *   2) 系统 TWS metadata（key 10/11/12 电量，13/14/15 充电）— 仅有部分 ROM 暴露
 *   3) 标准 BLE Battery Service 0x180F / 0x2A19
 *
 * 绝不伪造：拿不到就是 -1。
 *
 * 关键设计：系统值在刚连上时常常还是上一台设备的缓存，所以不是"读一次就用"，
 * 而是轮询到连续两次一致才认定稳定；每轮结果都回调出去，UI 原地刷新文字（不重建窗口），
 * 用户看到的是数字在 1~2 秒内收敛到真值，而不是闪一下错的再跳。
 */
public final class BluetoothBatteryReader {
    public interface Callback {
        /** provisional=true 表示这是中间值，后续还会刷新 */
        void onState(BatteryState state, boolean provisional);
    }

    private static final UUID BATTERY_SERVICE = UUID.fromString("0000180F-0000-1000-8000-00805F9B34FB");
    private static final UUID BATTERY_LEVEL  = UUID.fromString("00002A19-0000-1000-8000-00805F9B34FB");

    private static final int MAX_ROUNDS = 10;      // 最多 10 轮
    private static final long ROUND_MS  = 700L;    // 每轮间隔
    private static final int STABLE_NEED = 2;      // 连续几次一致算稳定

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());

    public BluetoothBatteryReader(Context c) { context = c.getApplicationContext(); }

    /** 开始读取。回调一定在主线程，且至少回调一次。 */
    public void read(BluetoothDevice device, Callback cb) {
        if (device == null) { cb.onState(BatteryState.unknown("设备为空"), true); return; }
        final AtomicBoolean finished = new AtomicBoolean(false);

        // ---- 通道 1+2：系统缓存轮询 ----
        Thread t = new Thread(() -> {
            int prev = -1, stable = 0;
            for (int i = 0; i < MAX_ROUNDS; i++) {
                if (finished.get()) return;
                int v = getSystemLevel(device);
                int l = getMetaLevel(device, 10);
                int r = getMetaLevel(device, 11);
                int c = getMetaLevel(device, 12);
                boolean lc = getMetaFlag(device, 13);
                boolean rc = getMetaFlag(device, 14);
                boolean cc = getMetaFlag(device, 15);

                String src = v >= 0 ? "系统蓝牙" : "未取到";
                if (l >= 0 || r >= 0 || c >= 0) src = "系统 TWS metadata";

                final BatteryState st = new BatteryState(v, l, r, c, lc, rc, cc, src);

                boolean nowStable = false;
                if (v >= 0 && v == prev) {
                    stable++;
                    if (stable >= STABLE_NEED) nowStable = true;
                } else {
                    stable = 0;
                }
                prev = v;

                final boolean provisional = !nowStable && i < MAX_ROUNDS - 1;
                main.post(() -> { if (!finished.get()) cb.onState(st, provisional); });
                if (nowStable) return;

                try { Thread.sleep(ROUND_MS); } catch (InterruptedException e) { return; }
            }
        }, "epx-battery");
        t.setDaemon(true);
        t.start();

        // ---- 通道 3：BLE Battery Service（交叉验证，读到立即覆盖） ----
        startGatt(device, cb, finished);

        // ---- 兜底：12 秒还没有任何回调就给个明确结果 ----
        main.postDelayed(() -> {
            if (!finished.get()) {
                int v = getSystemLevel(device);
                cb.onState(v >= 0
                        ? new BatteryState(v, -1, -1, -1, false, false, false, "系统蓝牙(兜底)")
                        : BatteryState.unknown("读取超时"), true);
            }
        }, 12000L);
    }

    private void startGatt(BluetoothDevice device, Callback cb, AtomicBoolean finished) {
        if (Build.VERSION.SDK_INT >= 31 &&
                context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        try {
            device.connectGatt(context, false, new BluetoothGattCallback() {
                @Override public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        try { g.discoverServices(); } catch (Throwable ignored) { close(g); }
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED) { close(g); }
                }
                @Override public void onServicesDiscovered(BluetoothGatt g, int status) {
                    try {
                        BluetoothGattService s = g.getService(BATTERY_SERVICE);
                        BluetoothGattCharacteristic c = s == null ? null : s.getCharacteristic(BATTERY_LEVEL);
                        if (c != null && g.readCharacteristic(c)) return;
                    } catch (Throwable ignored) {}
                    close(g);
                }
                @Override public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
                    if (status == BluetoothGatt.GATT_SUCCESS && BATTERY_LEVEL.equals(c.getUuid())) {
                        byte[] raw = c.getValue();
                        int v = (raw == null || raw.length == 0) ? -1 : (raw[0] & 0xff);
                        if (v >= 0 && v <= 100) {
                            main.post(() -> cb.onState(
                                    new BatteryState(v, -1, -1, -1, false, false, false, "BLE Battery Service"), false));
                        }
                    }
                    close(g);
                }
            });
        } catch (Throwable ignored) {}
    }

    private static void close(BluetoothGatt g) {
        try { g.disconnect(); } catch (Throwable ignored) {}
        try { g.close(); } catch (Throwable ignored) {}
    }

    /** 系统隐藏 API：BluetoothDevice.getBatteryLevel()，返回 -1 表示系统也没有 */
    private static int getSystemLevel(BluetoothDevice d) {
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

    /** 系统 TWS metadata 电量（key 10=左 11=右 12=盒）；多数 ROM 对第三方返回 null */
    private static int getMetaLevel(BluetoothDevice d, int key) {
        try {
            Method m = BluetoothDevice.class.getMethod("getMetadata", int.class);
            Object r = m.invoke(d, key);
            if (r instanceof byte[]) {
                byte[] b = (byte[]) r;
                if (b.length > 0) {
                    int v = b[0] & 0xff;
                    if (v <= 100) return v;
                }
            } else if (r instanceof Integer) {
                int v = (Integer) r;
                if (v >= 0 && v <= 100) return v;
            }
        } catch (Throwable ignored) {}
        return -1;
    }

    private static boolean getMetaFlag(BluetoothDevice d, int key) {
        try {
            Method m = BluetoothDevice.class.getMethod("getMetadata", int.class);
            Object r = m.invoke(d, key);
            if (r instanceof byte[]) {
                byte[] b = (byte[]) r;
                if (b.length > 0) return b[0] != 0;
            } else if (r instanceof Integer) {
                return ((Integer) r) != 0;
            }
        } catch (Throwable ignored) {}
        return false;
    }
}
