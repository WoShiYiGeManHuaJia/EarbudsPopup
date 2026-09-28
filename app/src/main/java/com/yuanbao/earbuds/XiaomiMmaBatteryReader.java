package com.yuanbao.earbuds;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
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
 * Xiaomi / Redmi TWS 电量读取器（MMA 协议，只读）。
 *
 * 协议来源（不是猜的，是照抄开源实现）：
 *   web1n/android_packages_apps_XiaomiTWS
 *   - EarbudsConstants.kt   定义 SPP UUID 与 opcode
 *   - MMADevice.kt          定义 RFCOMM 建链与帧格式
 *   - DeviceInfoRequestBuilder.kt  定义电量请求
 *   - DeviceBattery.kt      定义 [LL RR CC] 解析
 *
 * 与上一版（一直连不上）相比修掉的关键错误：
 *  1. 用 createInsecureRfcommSocketToServiceRecord，而不是 secure 版本。
 *     参考实现就是 insecure；Android 12+ 上 secure 建链经常被拒。
 *  2. 建链前必须 cancelDiscovery()。扫描中建 RFCOMM 会失败/挂死 ——
 *     用户日志里 "socket might closed or timeout, read ret: -1" 正是这个。
 *  3. 帧类型字节是 0xC0（Request 0x80 | needReply 0x40），上一版写成 0xC4，
 *     多了 0x04 三个位，耳机直接不认，所以从没回过包。
 *  4. 候选通道大幅扩充：FD2D / XiaoAI 定制 UUID / 标准 SPP，
 *     再退到反射 createRfcommSocket 逐个信道试。
 */
public final class XiaomiMmaBatteryReader {
    private static final String TAG = "XiaomiMmaBattery";

    /** 小米 Fast Connect */
    private static final UUID UUID_FAST_CONNECT =
            UUID.fromString("0000FD2D-0000-1000-8000-00805F9B34FB");
    /** 小米小爱定制 SPP（注意自定义 base，不是标准 0805F9B34FB） */
    private static final UUID UUID_XIAOAI =
            UUID.fromString("00001101-0000-1000-8000-008584D01810");
    /** 标准 SPP，部分小米固件走这个 */
    private static final UUID UUID_SPP_STD =
            UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    private static final byte[] HEADER = {(byte) 0xFE, (byte) 0xDC, (byte) 0xBA};
    private static final byte FOOTER = (byte) 0xEF;

    /** GET_DEVICE_INFO */
    private static final byte OPCODE_GET_DEVICE_INFO = 0x02;
    /** NOTIFY_DEVICE_INFO（耳机主动推送，TLV 里带电量） */
    private static final byte OPCODE_NOTIFY_DEVICE_INFO = 0x0E;
    /** 电量在 TLV 里的 tag */
    private static final int TLV_TYPE_BATTERY = 0x00;

    /** batteryInfo() 的请求数据：[0,0,0, 1<<7] */
    private static final byte[] BATTERY_REQUEST_DATA =
            {0x00, 0x00, 0x00, (byte) 0x80};

    private static final int CONNECT_TIMEOUT_MS = 6000;
    private static final int READ_TIMEOUT_MS = 4000;

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

                // 建链前停掉扫描：扫描中 RFCOMM 会失败/挂死
                try {
                    BluetoothAdapter ba = BluetoothAdapter.getDefaultAdapter();
                    if (ba != null && ba.isDiscovering()) {
                        ba.cancelDiscovery();
                        log.append("MMA: cancelDiscovery()\n");
                    }
                } catch (Throwable ignored) {
                }

                socket = openSocket(device, log);
                if (socket == null) {
                    log.append("MMA: 所有 RFCOMM 通道均失败\n");
                    finish(cb, null, log.toString());
                    return;
                }

                // 发电量请求：FE DC BA C0 02 00 05 SN 00 00 00 80 EF
                byte sn = (byte) (System.currentTimeMillis() & 0x7F);
                byte[] req = buildRequest(OPCODE_GET_DEVICE_INFO, BATTERY_REQUEST_DATA, sn);
                OutputStream out = socket.getOutputStream();
                out.write(req);
                out.flush();
                log.append("MMA: 发送请求 ").append(hex(req)).append('\n');

                // 收包：可能直接回 response，也可能先来 notify
                byte[] raw = drain(socket.getInputStream(), READ_TIMEOUT_MS);
                log.append("MMA: 收到 ").append(raw == null ? 0 : raw.length)
                        .append(" 字节 ").append(raw == null ? "" : hex(raw)).append('\n');

                BatteryLevels b = raw == null ? null : extract(raw, log);
                if (b == null) {
                    log.append("MMA: 未解析出电量三元组\n");
                    finish(cb, null, log.toString());
                    return;
                }
                b.source = "xiaomi-mma-rfcomm";
                b.timestamp = System.currentTimeMillis();
                log.append("MMA 电量: L=").append(b.left)
                        .append(" R=").append(b.right)
                        .append(" Case=").append(b.caseBox).append('\n');
                finish(cb, b, log.toString());
            } catch (Throwable t) {
                log.append("MMA 异常: ").append(shortErr(t)).append('\n');
                finish(cb, null, log.toString());
            } finally {
                close(socket);
            }
        });
    }

    /** 逐个候选通道建链，返回第一个成功的 */
    @SuppressLint("MissingPermission")
    private static BluetoothSocket openSocket(BluetoothDevice device, StringBuilder log) {
        UUID[] uuids = {UUID_FAST_CONNECT, UUID_XIAOAI, UUID_SPP_STD};
        String[] names = {"FD2D(FastConnect)", "XiaoAI(自定义)", "SPP(标准)"};

        // 先 insecure（参考实现用的就是它），再 secure
        for (int i = 0; i < uuids.length; i++) {
            for (int pass = 0; pass < 2; pass++) {
                boolean insecure = (pass == 0);
                String tag = names[i] + (insecure ? "-insecure" : "-secure");
                BluetoothSocket s = null;
                try {
                    s = insecure
                            ? device.createInsecureRfcommSocketToServiceRecord(uuids[i])
                            : device.createRfcommSocketToServiceRecord(uuids[i]);
                    s.connect();
                    log.append("MMA: 已连接 ").append(tag).append('\n');
                    return s;
                } catch (Throwable t) {
                    log.append("MMA: ").append(tag).append(" 失败: ")
                            .append(shortErr(t)).append('\n');
                    close(s);
                }
            }
        }

        // 最后退到反射：按信道直连（部分固件 SDP 里不注册 UUID）
        int[] channels = {6, 4, 5, 1, 2, 3, 7, 8, 10, 12};
        for (int ch : channels) {
            BluetoothSocket s = null;
            try {
                java.lang.reflect.Method m =
                        device.getClass().getMethod("createRfcommSocket", int.class);
                m.setAccessible(true);
                s = (BluetoothSocket) m.invoke(device, ch);
                s.connect();
                log.append("MMA: 已连接 反射信道 ").append(ch).append('\n');
                return s;
            } catch (Throwable t) {
                close(s);
            }
        }
        log.append("MMA: 反射信道 6/4/5/1/2/3/7/8/10/12 全部失败\n");
        return null;
    }

    /**
     * 组帧：FE DC BA [type] [opcode] [lenHi] [lenLo] [SN] [data...] EF
     * type = 0x80(Request) | 0x40(needReply) = 0xC0
     * len  = data.length + 1（SN 占 1 字节）
     */
    private static byte[] buildRequest(byte opcode, byte[] data, byte sn) {
        int len = data.length + 1;
        byte[] f = new byte[3 + 1 + 1 + 2 + 1 + data.length + 1];
        int i = 0;
        f[i++] = HEADER[0]; f[i++] = HEADER[1]; f[i++] = HEADER[2];
        f[i++] = (byte) 0xC0;
        f[i++] = opcode;
        f[i++] = (byte) ((len >> 8) & 0xFF);
        f[i++] = (byte) (len & 0xFF);
        f[i++] = sn;
        System.arraycopy(data, 0, f, i, data.length);
        i += data.length;
        f[i] = FOOTER;
        return f;
    }

    /** 读到超时为止，不做帧同步假设，全部原样带回 */
    private static byte[] drain(InputStream in, int timeoutMs) {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        long deadline = System.currentTimeMillis() + timeoutMs;
        byte[] tmp = new byte[512];
        try {
            while (System.currentTimeMillis() < deadline) {
                int available = in.available();
                if (available <= 0) {
                    if (buf.size() >= 8) break;   // 已有内容且暂时无新数据
                    Thread.sleep(40);
                    continue;
                }
                int n = in.read(tmp, 0, Math.min(tmp.length, available));
                if (n < 0) break;
                buf.write(tmp, 0, n);
                Thread.sleep(30);
            }
        } catch (Throwable ignored) {
        }
        return buf.size() == 0 ? null : buf.toByteArray();
    }

    /** 从原始字节流里找电量：先试 response(0x02)，再试 notify(0x0E) 的 TLV */
    private static BatteryLevels extract(byte[] raw, StringBuilder log) {
        for (int start = 0; start + 8 <= raw.length; start++) {
            if (raw[start] != HEADER[0] || raw[start + 1] != HEADER[1]
                    || raw[start + 2] != HEADER[2]) continue;

            int type = raw[start + 3] & 0xFF;
            int opcode = raw[start + 4] & 0xFF;
            int len = ((raw[start + 5] & 0xFF) << 8) | (raw[start + 6] & 0xFF);
            if (len <= 0 || start + 7 + len >= raw.length + 1) continue;

            int body = start + 7;   // 参数段起点
            int dataStart;
            int dataLen;
            if ((type & 0x80) != 0) {
                // Request/Notify：SN(1) + data
                dataStart = body + 1;
                dataLen = len - 1;
            } else {
                // Response：status(1) + SN(1) + data
                int status = raw[body] & 0xFF;
                if (status != 0x00) {
                    log.append("MMA: 响应 status=").append(status).append(" 非 0\n");
                    continue;
                }
                dataStart = body + 2;
                dataLen = len - 2;
            }
            if (dataLen < 5 || dataStart + dataLen > raw.length) continue;

            // 形态 A：GET_DEVICE_INFO 响应，data = [len=4, mask=7, LL, RR, CC]
            if (opcode == (OPCODE_GET_DEVICE_INFO & 0xFF)) {
                int declared = raw[dataStart] & 0xFF;
                int mask = raw[dataStart + 1] & 0xFF;
                if (declared == 4 && mask == 7) {
                    BatteryLevels b = triple(
                            raw[dataStart + 2] & 0xFF,
                            raw[dataStart + 3] & 0xFF,
                            raw[dataStart + 4] & 0xFF);
                    if (b != null) return b;
                }
            }

            // 形态 B：NOTIFY_DEVICE_INFO，TLV: [len][tag][LL RR CC]
            if (opcode == (OPCODE_NOTIFY_DEVICE_INFO & 0xFF)) {
                BatteryLevels b = fromTlv(raw, dataStart, dataLen);
                if (b != null) return b;
            }
        }
        return null;
    }

    private static BatteryLevels fromTlv(byte[] raw, int off, int len) {
        int i = off;
        int end = off + len;
        while (i + 1 < end) {
            int segLen = raw[i] & 0xFF;
            if (segLen <= 0 || i + 1 + segLen > end) break;
            int tag = raw[i + 1] & 0xFF;
            if (tag == TLV_TYPE_BATTERY && segLen >= 4) {
                BatteryLevels b = triple(raw[i + 2] & 0xFF,
                        raw[i + 3] & 0xFF, raw[i + 4] & 0xFF);
                if (b != null) return b;
            }
            i += 1 + segLen;
        }
        return null;
    }

    private static BatteryLevels triple(int l, int r, int c) {
        BatteryLevels b = new BatteryLevels();
        b.left = decode(l);
        b.right = decode(r);
        b.caseBox = decode(c);
        b.leftCharging = (l & 0x80) != 0;
        b.rightCharging = (r & 0x80) != 0;
        b.caseCharging = (c & 0x80) != 0;
        if (BatteryLevels.valid(b.left) && BatteryLevels.valid(b.right)) {
            b.overall = Math.min(b.left, b.right);
        }
        b.sanitize();
        return b.anyKnown() ? b : null;
    }

    /** 电量 = 低 7 位，1~100 才有效（与 DeviceBattery.fromByte 一致） */
    private static int decode(int raw) {
        int p = raw & 0x7F;
        return (p >= 1 && p <= 100) ? p : -1;
    }

    private static void finish(Callback cb, BatteryLevels b, String diag) {
        MAIN.post(() -> cb.onResult(b, diag));
    }

    private static void close(BluetoothSocket s) {
        if (s == null) return;
        try { s.close(); } catch (Throwable ignored) {}
    }

    private static String hex(byte[] a) {
        if (a == null) return "";
        StringBuilder sb = new StringBuilder();
        for (byte b : a) sb.append(String.format("%02X ", b));
        return sb.toString().trim();
    }

    private static String shortErr(Throwable t) {
        if (t == null) return "null";
        String m = t.getMessage();
        return m == null ? t.getClass().getSimpleName() : m;
    }
}
