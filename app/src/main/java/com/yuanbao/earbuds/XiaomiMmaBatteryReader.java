package com.yuanbao.earbuds;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Xiaomi/Redmi TWS battery reader.
 *
 * This is intentionally READ-ONLY.  It uses Xiaomi's classic Bluetooth
 * RFCOMM/MMA device-info query instead of guessing battery bytes from Airoha
 * GATT characteristics.  For Redmi Buds 5 Pro-class devices this is the
 * important distinction: 0x180F/0x2A19 is an aggregate value, while the
 * Xiaomi control channel can return L/R/Case as a semantic triple.
 *
 * Protocol reference: Xiaomi/Redmi TWS MMA protocol reverse-engineering,
 * including GET_DEVICE_INFO opcode 0x02 and battery response [LL RR CC].
 */
public final class XiaomiMmaBatteryReader {
    private static final String TAG = "XiaomiMmaBattery";
    private static final UUID FAST_CONNECT_UUID = UUID.fromString(
            "0000FD2D-0000-1000-8000-00805F9B34FB");
    private static final UUID XIAOAI_UUID = UUID.fromString(
            "00001101-0000-1000-8000-008584D01810");

    private static final byte[] HEADER = {(byte) 0xFE, (byte) 0xDC, (byte) 0xBA};
    private static final byte FOOTER = (byte) 0xEF;
    private static final int TIMEOUT_MS = 5500;
    private static final ExecutorService IO = Executors.newCachedThreadPool();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    public interface Callback {
        void onResult(BatteryLevels levels, String diagnostic);
    }

    private XiaomiMmaBatteryReader() {}

    public static boolean likelyXiaomiRedmi(BluetoothDevice device) {
        if (device == null) return false;
        try {
            String name = device.getName();
            if (name != null) {
                String n = name.toLowerCase();
                if (n.contains("redmi") || n.contains("xiaomi") || n.contains("buds")) return true;
            }
            String a = device.getAddress();
            // Redmi Buds 5 Pro / Gaming uses the 48:73:CB OUI family.
            return a != null && a.toUpperCase().startsWith("48:73:CB:");
        } catch (Throwable ignored) {
            return false;
        }
    }

    @SuppressLint("MissingPermission")
    public static void read(BluetoothDevice device, Callback cb) {
        if (device == null || cb == null || !likelyXiaomiRedmi(device)) {
            if (cb != null) cb.onResult(null, "MMA skipped: non-Xiaomi/Redmi target");
            return;
        }
        IO.execute(() -> {
            StringBuilder log = new StringBuilder();
            BluetoothSocket socket = null;
            try {
                log.append("MMA: target=").append(device.getAddress()).append('\n');
                // First use the advertised Xiaomi Fast Connect UUID.
                try {
                    socket = device.createRfcommSocketToServiceRecord(FAST_CONNECT_UUID);
                    socket.connect();
                    log.append("MMA: RFCOMM connected via FD2D\n");
                } catch (Throwable first) {
                    close(socket);
                    socket = null;
                    log.append("MMA: FD2D connect failed: ").append(shortErr(first)).append('\n');
                    // Redmi/Xiaomi clients commonly fall back to RFCOMM channel 6.
                    try {
                        java.lang.reflect.Method m = device.getClass()
                                .getMethod("createRfcommSocket", int.class);
                        m.setAccessible(true);
                        socket = (BluetoothSocket) m.invoke(device, 6);
                        socket.connect();
                        log.append("MMA: RFCOMM connected via channel 6\n");
                    } catch (Throwable second) {
                        close(socket);
                        socket = null;
                        log.append("MMA: channel 6 failed: ").append(shortErr(second)).append('\n');
                        // Last harmless transport attempt: the standard SPP UUID used by some
                        // Xiaomi builds. No writes occur until a socket is connected.
                        try {
                            socket = device.createRfcommSocketToServiceRecord(XIAOAI_UUID);
                            socket.connect();
                            log.append("MMA: RFCOMM connected via XiaoAI/SPP\n");
                        } catch (Throwable third) {
                            close(socket);
                            socket = null;
                            log.append("MMA: all RFCOMM transports failed\n");
                            finish(cb, null, log.toString());
                            return;
                        }
                    }
                }

                socket.getOutputStream().write(buildBatteryQuery(0x5A));
                socket.getOutputStream().flush();
                log.append("MMA: sent GET_DEVICE_INFO battery query\n");

                byte[] response = readFrame(socket.getInputStream(), TIMEOUT_MS);
                if (response == null) {
                    log.append("MMA: no response (device may require its control-channel authentication)\n");
                    finish(cb, null, log.toString());
                    return;
                }
                log.append("MMA: response=").append(hex(response)).append('\n');

                BatteryLevels b = parseBatteryResponse(response);
                if (b == null) {
                    log.append("MMA: response was received but did not match battery layout\n");
                    finish(cb, null, log.toString());
                    return;
                }
                b.source = "xiaomi-mma-rfcomm";
                b.timestamp = System.currentTimeMillis();
                log.append("MMA BATTERY: L=").append(b.left)
                        .append(" R=").append(b.right)
                        .append(" Case=").append(b.caseBox).append('\n');
                finish(cb, b, log.toString());
            } catch (Throwable t) {
                log.append("MMA exception: ").append(shortErr(t)).append('\n');
                finish(cb, null, log.toString());
            } finally {
                close(socket);
            }
        });
    }

    private static byte[] buildBatteryQuery(int sn) {
        // FE DC BA C4 02 00 05 SN 00 00 00 80 EF
        return new byte[] {
                HEADER[0], HEADER[1], HEADER[2], (byte) 0xC4, 0x02,
                0x00, 0x05, (byte) sn,
                0x00, 0x00, 0x00, (byte) 0x80, FOOTER
        };
    }

    private static BatteryLevels parseBatteryResponse(byte[] frame) {
        // Response: FE DC BA 00 02 len status SN [04 07 LL RR CC] EF
        if (frame == null || frame.length < 14) return null;
        if (frame[0] != HEADER[0] || frame[1] != HEADER[1] || frame[2] != HEADER[2]) return null;
        if ((frame[3] & 0x80) != 0) return null; // response, not request
        if ((frame[4] & 0xFF) != 0x02) return null;
        int len = ((frame[5] & 0xFF) << 8) | (frame[6] & 0xFF);
        if (len < 5 || frame.length < 7 + len + 1) return null;
        if (frame[7] != 0x00) return null; // status
        if (frame[7 + len] != FOOTER) return null;
        int dataStart = 9; // header(7) + status(1) + SN(1)
        int dataLen = len - 2;
        if (dataLen < 5 || dataStart + 5 > frame.length) return null;
        int declaredLen = frame[dataStart] & 0xFF;
        int mask = frame[dataStart + 1] & 0xFF;
        if (declaredLen != 4 || mask != 7) return null;
        int l = frame[dataStart + 2] & 0xFF;
        int r = frame[dataStart + 3] & 0xFF;
        int c = frame[dataStart + 4] & 0xFF;
        BatteryLevels out = new BatteryLevels();
        out.left = decodeMmaBattery(l);
        out.right = decodeMmaBattery(r);
        out.caseBox = decodeMmaBattery(c);
        out.overall = BatteryLevels.valid(out.left) && BatteryLevels.valid(out.right)
                ? Math.min(out.left, out.right) : -1;
        if (!out.anyKnown()) return null;
        return out;
    }

    private static int decodeMmaBattery(int raw) {
        int p = raw & 0x7F;
        return (p >= 1 && p <= 100) ? p : -1;
    }

    private static byte[] readFrame(InputStream in, int timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] tmp = new byte[256];
        while (System.currentTimeMillis() < deadline) {
            int available = in.available();
            if (available <= 0) {
                Thread.sleep(35);
                continue;
            }
            int n = in.read(tmp, 0, Math.min(tmp.length, available));
            if (n < 0) break;
            buf.write(tmp, 0, n);
            byte[] all = buf.toByteArray();
            int start = indexOf(all, HEADER);
            if (start < 0) {
                if (all.length > 2) {
                    buf.reset();
                    buf.write(all, all.length - 2, 2);
                }
                continue;
            }
            if (all.length < start + 8) continue;
            int len = ((all[start + 5] & 0xFF) << 8) | (all[start + 6] & 0xFF);
            int total = 8 + len;
            if (all.length < start + total) continue;
            byte[] frame = new byte[total];
            System.arraycopy(all, start, frame, 0, total);
            return frame;
        }
        return null;
    }

    private static int indexOf(byte[] a, byte[] needle) {
        outer: for (int i = 0; i <= a.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (a[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    private static void finish(Callback cb, BatteryLevels b, String diag) {
        MAIN.post(() -> cb.onResult(b, diag));
    }

    private static void close(BluetoothSocket s) {
        if (s == null) return;
        try { s.close(); } catch (Throwable ignored) {}
    }

    private static String shortErr(Throwable t) {
        String s = t == null ? "unknown" : String.valueOf(t.getMessage());
        return s == null || s.isEmpty() ? t.getClass().getSimpleName() : s;
    }

    private static String hex(byte[] a) {
        StringBuilder b = new StringBuilder();
        for (byte x : a) b.append(String.format("%02X ", x & 0xFF));
        return b.toString().trim();
    }
}
