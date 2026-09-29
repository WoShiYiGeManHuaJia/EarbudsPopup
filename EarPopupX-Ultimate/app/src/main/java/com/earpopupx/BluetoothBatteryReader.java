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
 * Reads only values the headset actually reports. Never invents a percentage.
 *
 * Two channels race each other:
 *
 *   1) BLE Battery Service 0x180F/0x2A19 - read straight off the headset.
 *      This is the ONLY channel the user's own logs proved to return a real,
 *      changing value (100 and 90 at two different moments).
 *   2) Android bluetooth stack cache - getBatteryLevel(). It is what the stack
 *      filled in from the headset's HFP report, so it is a real value too, but
 *      it can still hold the previous headset's number right after a switch.
 *
 * The GATT value outranks the cached one, so a stale cache can never win.
 */
public final class BluetoothBatteryReader {

    public interface Callback { void onState(BatteryState state); }

    private static final UUID BATTERY_SERVICE = UUID.fromString("0000180F-0000-1000-8000-00805F9B34FB");
    private static final UUID BATTERY_LEVEL   = UUID.fromString("00002A19-0000-1000-8000-00805F9B34FB");

    private static final int T_SYS  = 1;
    private static final int T_GATT = 2;

    private final Context context;
    private volatile BluetoothGatt lastGatt;

    public BluetoothBatteryReader(Context c) { context = c.getApplicationContext(); }

    public void read(BluetoothDevice d, Callback cb) {
        final Hub hub = new Hub(cb);

        int sys0 = systemLevel(d);
        if (sys0 >= 0) {
            hub.emit(new BatteryState(sys0, -1, -1, -1, false, false, false, "系统蓝牙缓存"), T_SYS);
        }

        startGatt(d, hub);
        pollSystem(d, hub);
    }

    private void startGatt(BluetoothDevice d, Hub hub) {
        if (android.os.Build.VERSION.SDK_INT >= 31
                && context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            hub.gattDone();
            return;
        }
        final BluetoothGatt[] holder = new BluetoothGatt[1];
        final Handler h = new Handler(Looper.getMainLooper());
        final Runnable timeout = () -> { hub.gattDone(); closeGatt(holder[0]); };
        try {
            holder[0] = d.connectGatt(context, false, new BluetoothGattCallback() {
                @Override public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        try {
                            g.discoverServices();
                            return;
                        } catch (Throwable t) {
                            h.removeCallbacks(timeout);
                            hub.gattDone();
                            closeGatt(g);
                        }
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        h.removeCallbacks(timeout);
                        hub.gattDone();
                        closeGatt(g);
                    }
                }
                @Override public void onServicesDiscovered(BluetoothGatt g, int status) {
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        h.removeCallbacks(timeout);
                        hub.gattDone();
                        closeGatt(g);
                        return;
                    }
                    try {
                        BluetoothGattService svc = g.getService(BATTERY_SERVICE);
                        BluetoothGattCharacteristic c = svc == null ? null : svc.getCharacteristic(BATTERY_LEVEL);
                        if (c != null && g.readCharacteristic(c)) return;
                    } catch (Throwable ignored) {}
                    h.removeCallbacks(timeout);
                    hub.gattDone();
                    closeGatt(g);
                }
                @Override public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
                    h.removeCallbacks(timeout);
                    if (status == BluetoothGatt.GATT_SUCCESS && BATTERY_LEVEL.equals(c.getUuid())) {
                        byte[] v = c.getValue();
                        if (v != null && v.length > 0) {
                            int lv = v[0] & 0xff;
                            if (lv <= 100) {
                                hub.emit(new BatteryState(lv, -1, -1, -1, false, false, false, "BLE 直读耳机"), T_GATT);
                            }
                        }
                    }
                    hub.gattDone();
                    closeGatt(g);
                }
            }, BluetoothDevice.TRANSPORT_LE);
            lastGatt = holder[0];
            h.postDelayed(timeout, 7000L);
        } catch (Throwable t) {
            hub.gattDone();
        }
    }

    /** The stack cache is refreshed when the headset reports over HFP, so keep
     *  reading it for a short window and push every change to the popup. */
    private void pollSystem(BluetoothDevice d, Hub hub) {
        final Handler h = new Handler(Looper.getMainLooper());
        final int[] last = { systemLevel(d) };
        final int[] same = { 0 };
        final int[] tries = { 0 };
        final Runnable poll = new Runnable() {
            @Override public void run() {
                int v = systemLevel(d);
                if (v >= 0) {
                    if (v == last[0]) same[0]++;
                    else { last[0] = v; same[0] = 0; }
                    hub.emit(new BatteryState(last[0], -1, -1, -1, false, false, false, "系统蓝牙"), T_SYS);
                }
                tries[0]++;
                if (same[0] >= 2 || tries[0] >= 10) return;
                h.postDelayed(this, 250L);
            }
        };
        h.postDelayed(poll, 250L);
    }

    private void closeGatt(BluetoothGatt g) {
        if (g == null) return;
        try { g.disconnect(); } catch (Throwable ignored) {}
        try { g.close(); } catch (Throwable ignored) {}
    }

    public void close() {
        BluetoothGatt g = lastGatt;
        lastGatt = null;
        closeGatt(g);
    }

    private int systemLevel(BluetoothDevice d) {
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

    /** Keeps the most trustworthy value seen so far; a lower-trust channel can
     *  never overwrite a higher-trust one. */
    private static final class Hub {
        private final Callback cb;
        private final Handler h = new Handler(Looper.getMainLooper());
        private int trust = -1;

        Hub(Callback cb) { this.cb = cb; }

        synchronized void emit(BatteryState s, int t) {
            if (t < trust) return;
            trust = t;
            h.post(() -> cb.onState(s));
        }

        synchronized void gattDone() { }
    }
}
