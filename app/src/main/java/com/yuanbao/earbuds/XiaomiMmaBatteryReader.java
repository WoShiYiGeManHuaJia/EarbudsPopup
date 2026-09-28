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
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Xiaomi / Redmi TWS 电量读取器（MMA 协议，只读）。
 *
 * 协议照抄 Gadgetbridge 的 Redmi Buds 5 Pro 实现，不是猜的：
 *   service/devices/redmibuds5pro/RedmiBuds5ProProtocol.java
 *   service/devices/redmibuds5pro/protocol/{Message,Opcode,MessageType}.java
 *   devices/xiaomi/redmibuds5pro/RedmiBuds5ProCoordinator.java
 *
 * 之前几版"连上了却没数据"的真正原因：
 *   这个控制通道必须先完成 SAFER+ 双向挑战应答，之后耳机才肯回电量。
 *   直接发 GET_DEVICE_INFO 过去，耳机静默。鉴权见 XiaomiSaferAuth。
 *
 * 其他修正：
 *   - 帧类型：手机请求是 0xC4(PHONE_REQUEST)，不是 0xC0。
 *   - GET_DEVICE_INFO 的 payload 是 {FF,FF,FF,FF}（要全部信息）。
 *   - 电量在 TLV 里 index=0x07，顺序 [左, 右, 盒]；每字节低 7 位是电量、
 *     bit7 是充电中、0xFF 表示未知。
 *   - 建链前必须 cancelDiscovery()，否则 RFCOMM 会挂死。
 *   - 每次 connect() 加超时，否则一个卡住的候选能把整个探测拖到 25 秒超时。
 */
public final class XiaomiMmaBatteryReader {
    private static final String TAG = "XiaomiMmaBattery";

    /** 小米 Fast Connect —— Gadgetbridge 的 UUID_DEVICE_CTRL */
    private static final UUID UUID_FAST_CONNECT =
            UUID.fromString("0000FD2D-0000-1000-8000-00805F9B34FB");
    private static final UUID UUID_XIAOAI =
            UUID.fromString("00001101-0000-1000-8000-008584D01810");
    private static final UUID UUID_SPP_STD =
            UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    private static final byte[] HEADER = {(byte) 0xFE, (byte) 0xDC, (byte) 0xBA};
    private static final byte FOOTER = (byte) 0xEF;

    /** MessageType */
    private static final int TYPE_PHONE_REQUEST = 0xC4;
    private static final int TYPE_RESPONSE = 0x04;

    /** Opcode */
    private static final int OP_GET_DEVICE_INFO = 0x02;
    private static final int OP_AUTH_CHALLENGE = 0x50;
    private static final int OP_AUTH_CONFIRM = 0x51;

    /** 电量在设备信息 TLV 里的 index */
    private static final int TLV_INDEX_BATTERY = 0x07;

    private static final int CONNECT_TIMEOUT_MS = 2500;
    private static final int READ_TIMEOUT_MS = 2500;
    private static final int HANDSHAKE_ROUNDS = 12;

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
        if (device == null || cb == null) {
            if (cb != null) cb.onResult(null, "MMA skipped: null");
            return;
        }
        if (!likelyXiaomiRedmi(device)) {
            cb.onResult(null, "MMA skipped: non-Xiaomi/Redmi target");
            return;
        }
        IO.execute(() -> {
            StringBuilder log = new StringBuilder();
            BluetoothSocket socket = null;
            try {
                log.append("MMA: target=").append(device.getAddress()).append('\n');

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

                BatteryLevels b = handshake(socket, log);
                if (b == null) {
                    log.append("MMA: 握手完成但未解析出电量\n");
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

    /**
     * SAFER+ 鉴权握手 + 取设备信息。
     *
     * 手机发挑战 → 耳机发挑战 → 手机应答 → 手机发确认 → 耳机确认
     * → 手机发 GET_DEVICE_INFO → 耳机回 TLV（index 0x07 是电量）
     */
    private static BatteryLevels handshake(BluetoothSocket socket, StringBuilder log)
            throws Exception {
        InputStream in = socket.getInputStream();
        OutputStream out = socket.getOutputStream();
        XiaomiSaferAuth auth = new XiaomiSaferAuth();
        int sn = 1;

        // 1) 手机先发挑战
        byte[] rnd = XiaomiSaferAuth.getRandomChallenge();
        byte[] p1 = new byte[17];
        p1[0] = 0x01;
        System.arraycopy(rnd, 0, p1, 1, 16);
        send(out, TYPE_PHONE_REQUEST, OP_AUTH_CHALLENGE, sn++, p1);
        log.append("[AUTH] 发送挑战\n");

        BatteryLevels result = null;
        boolean gotChallengeResponse = false;
        boolean sentDeviceInfo = false;

        for (int round = 0; round < HANDSHAKE_ROUNDS; round++) {
            byte[] raw = readSome(in, READ_TIMEOUT_MS);
            if (raw == null || raw.length == 0) {
                if (sentDeviceInfo) break;   // 已问过电量，没数据就算了
                continue;
            }
            log.append("[RX] ").append(hex(raw)).append('\n');

            for (Msg m : splitMessages(raw)) {
                if (m.opcode == OP_AUTH_CHALLENGE) {
                    if (m.type == TYPE_RESPONSE) {
                        // 我们的挑战被应答了 → 发确认
                        if (!gotChallengeResponse) {
                            gotChallengeResponse = true;
                            send(out, TYPE_PHONE_REQUEST, OP_AUTH_CONFIRM, sn++,
                                    new byte[]{0x01, 0x00});
                            log.append("[AUTH] 挑战已应答，发送确认\n");
                        }
                    } else if (m.payload != null && m.payload.length >= 17) {
                        // 耳机发来挑战 → 算应答回过去
                        byte[] challenge = new byte[16];
                        System.arraycopy(m.payload, 1, challenge, 0, 16);
                        byte[] resp = auth.computeChallengeResponse(challenge);
                        byte[] p = new byte[17];
                        p[0] = 0x01;
                        System.arraycopy(resp, 0, p, 1, 16);
                        send(out, TYPE_RESPONSE, OP_AUTH_CHALLENGE, m.sn, p);
                        log.append("[AUTH] 应答耳机挑战 ").append(hex(resp)).append('\n');
                    }
                } else if (m.opcode == OP_AUTH_CONFIRM) {
                    if (m.type == TYPE_RESPONSE) {
                        log.append("[AUTH] 第一步确认完成\n");
                    } else {
                        // 耳机确认请求 → 回一个 RESPONSE，然后马上要设备信息
                        send(out, TYPE_RESPONSE, OP_AUTH_CONFIRM, m.sn, new byte[]{0x01});
                        log.append("[AUTH] 已确认，请求设备信息\n");
                        if (!sentDeviceInfo) {
                            sentDeviceInfo = true;
                            send(out, TYPE_PHONE_REQUEST, OP_GET_DEVICE_INFO, sn++,
                                    new byte[]{(byte) 0xFF, (byte) 0xFF,
                                            (byte) 0xFF, (byte) 0xFF});
                        }
                    }
                } else if (m.opcode == OP_GET_DEVICE_INFO) {
                    BatteryLevels parsed = parseDeviceInfo(m.payload, log);
                    if (parsed != null) {
                        result = parsed;
                    }
                }
            }
            if (result != null) break;
        }
        return result;
    }

    /**
     * TLV: [len][index][data...]
     * index 0x07 → 电量三元组 [左, 右, 盒]
     * 每字节: &127 = 电量, &128 = 充电中, 0xFF = 未知
     */
    private static BatteryLevels parseDeviceInfo(byte[] payload, StringBuilder log) {
        if (payload == null) return null;
        byte[] bat = null;
        int i = 0;
        while (i + 1 < payload.length) {
            int len = payload[i] & 0xFF;
            if (len <= 0 || i + 1 + len > payload.length) break;
            int index = payload[i + 1] & 0xFF;
            if (index == TLV_INDEX_BATTERY && len >= 4) {
                bat = new byte[3];
                System.arraycopy(payload, i + 2, bat, 0, 3);
                break;
            }
            i += len + 1;
        }
        if (bat == null) return null;

        BatteryLevels b = new BatteryLevels();
        b.left = decode(bat[0] & 0xFF);
        b.right = decode(bat[1] & 0xFF);
        b.caseBox = decode(bat[2] & 0xFF);
        b.leftCharging = (bat[0] & 0x80) != 0;
        b.rightCharging = (bat[1] & 0x80) != 0;
        b.caseCharging = (bat[2] & 0x80) != 0;
        if (BatteryLevels.valid(b.left) && BatteryLevels.valid(b.right)) {
            b.overall = Math.min(b.left, b.right);
        }
        b.sanitize();
        log.append("[电量] raw=").append(hex(bat))
                .append(" → L=").append(b.left)
                .append(" R=").append(b.right)
                .append(" Case=").append(b.caseBox).append('\n');
        return b.anyKnown() ? b : null;
    }

    /** 0xFF 表示未知；低 7 位是电量，1~100 才有效 */
    private static int decode(int raw) {
        if ((raw & 0x7F) == 0x7F) return -1;
        int p = raw & 0x7F;
        return (p >= 1 && p <= 100) ? p : -1;
    }

    // ---------------- 帧编解码 ----------------

    private static final class Msg {
        int type;
        int opcode;
        int sn;
        byte[] payload;
    }

    /**
     * 组帧：FE DC BA [type][opcode][lenHi][lenLo]([00] if 非请求)[sn][payload] EF
     * len = payload.length + (isRequest ? 1 : 2)
     */
    private static byte[] encode(int type, int opcode, int sn, byte[] payload) {
        boolean isRequest = (type & 0x40) != 0;
        int size = isRequest ? 1 : 2;
        int payloadLength = payload.length + size;
        ByteBuffer buf = ByteBuffer.allocate(payload.length + 8 + size);
        buf.order(ByteOrder.BIG_ENDIAN);
        buf.put(HEADER);
        buf.put((byte) type);
        buf.put((byte) opcode);
        buf.putShort((short) payloadLength);
        if (!isRequest) buf.put((byte) 0x00);
        buf.put((byte) sn);
        buf.put(payload);
        buf.put(FOOTER);
        return buf.array();
    }

    private static void send(OutputStream out, int type, int opcode, int sn, byte[] payload)
            throws Exception {
        byte[] f = encode(type, opcode, sn, payload);
        out.write(f);
        out.flush();
        Log.d(TAG, "TX " + hex(f));
    }

    /** 按 FE DC BA 切分，可能一包里粘了多帧 */
    private static List<Msg> splitMessages(byte[] input) {
        List<Msg> out = new ArrayList<>();
        if (input == null || input.length < 8) return out;
        List<Integer> starts = new ArrayList<>();
        for (int i = 0; i + 2 < input.length; i++) {
            if ((input[i] & 0xFF) == 0xFE && (input[i + 1] & 0xFF) == 0xDC
                    && (input[i + 2] & 0xFF) == 0xBA) {
                starts.add(i);
            }
        }
        for (int k = 0; k < starts.size(); k++) {
            int s = starts.get(k);
            int e = (k == starts.size() - 1) ? input.length : starts.get(k + 1);
            Msg m = decodeOne(input, s, e);
            if (m != null) out.add(m);
        }
        return out;
    }

    private static Msg decodeOne(byte[] a, int start, int end) {
        if (end - start < 8) return null;
        int type = a[start + 3] & 0xFF;
        int opcode = a[start + 4] & 0xFF;
        boolean isRequest = (type & 0x40) != 0;
        int payloadOffset = start + 3 + (isRequest ? 5 : 6);
        if (payloadOffset > end || payloadOffset - 1 < start) return null;
        int sn = a[payloadOffset - 1] & 0xFF;
        int dataLen = end - payloadOffset - 1;   // 去掉 trailer
        if (dataLen < 0) dataLen = 0;
        byte[] payload = new byte[dataLen];
        System.arraycopy(a, payloadOffset, payload, 0, dataLen);

        Msg m = new Msg();
        m.type = type;
        m.opcode = opcode;
        m.sn = sn;
        m.payload = payload;
        return m;
    }

    /** 读到超时为止，原样带回全部字节 */
    private static byte[] readSome(InputStream in, int timeoutMs) {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        long deadline = System.currentTimeMillis() + timeoutMs;
        byte[] tmp = new byte[512];
        try {
            while (System.currentTimeMillis() < deadline) {
                int available = in.available();
                if (available <= 0) {
                    if (buf.size() >= 8) break;
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

    // ---------------- 建链 ----------------

    @SuppressLint("MissingPermission")
    private static BluetoothSocket openSocket(BluetoothDevice device, StringBuilder log) {
        UUID[] uuids = {UUID_FAST_CONNECT, UUID_XIAOAI, UUID_SPP_STD};
        String[] names = {"FD2D(FastConnect)", "XiaoAI(自定义)", "SPP(标准)"};

        for (int i = 0; i < uuids.length; i++) {
            for (int pass = 0; pass < 2; pass++) {
                boolean insecure = (pass == 0);
                String tag = names[i] + (insecure ? "-insecure" : "-secure");
                BluetoothSocket s = null;
                try {
                    s = insecure
                            ? device.createInsecureRfcommSocketToServiceRecord(uuids[i])
                            : device.createRfcommSocketToServiceRecord(uuids[i]);
                    if (connectWithTimeout(s, CONNECT_TIMEOUT_MS)) {
                        log.append("MMA: 已连接 ").append(tag).append('\n');
                        return s;
                    }
                    log.append("MMA: ").append(tag).append(" 超时\n");
                } catch (Throwable t) {
                    log.append("MMA: ").append(tag).append(" 失败: ")
                            .append(shortErr(t)).append('\n');
                }
                close(s);
            }
        }

        int[] channels = {6, 4, 5, 12};
        for (int ch : channels) {
            BluetoothSocket s = null;
            try {
                java.lang.reflect.Method m =
                        device.getClass().getMethod("createRfcommSocket", int.class);
                m.setAccessible(true);
                s = (BluetoothSocket) m.invoke(device, ch);
                if (connectWithTimeout(s, CONNECT_TIMEOUT_MS)) {
                    log.append("MMA: 已连接 反射信道 ").append(ch).append('\n');
                    return s;
                }
            } catch (Throwable ignored) {
            }
            close(s);
        }
        log.append("MMA: 反射信道 6/4/5/12 全部失败\n");
        return null;
    }

    /** BluetoothSocket.connect() 没有超时参数，卡住会拖垮整个探测 */
    private static boolean connectWithTimeout(final BluetoothSocket s, int ms) {
        final boolean[] ok = {false};
        Thread t = new Thread(() -> {
            try {
                s.connect();
                ok[0] = true;
            } catch (Throwable ignored) {
            }
        });
        t.setDaemon(true);
        t.start();
        try {
            t.join(ms);
        } catch (InterruptedException ignored) {
        }
        if (!ok[0]) {
            try { s.close(); } catch (Throwable ignored) {}
        }
        return ok[0];
    }

    // ---------------- 工具 ----------------

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
