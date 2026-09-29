package com.yuanbao.earbuds;

import android.net.Uri;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * HCI 日志精简（v2.1）。
 *
 * 背景：小米工程日志（bugreport 压缩包里的 btsnoop_hci.log）动辄几十 MB，
 * 根本传不动。但里面 99% 的包跟目标耳机无关 —— 手机同时在扫各种 BLE 设备、
 * 连着手表/音箱/手环，全都记在里面。
 *
 * 本类做两件事：
 *   ① 从 zip 里直接取出 btsnoop_hci.log（用户不用先解压）
 *   ② 按 MAC 过滤：先找出目标耳机对应的 connection handle，
 *      再把与该 handle 无关的 HCI 包全部丢掉
 *
 * 输出仍是【标准 btsnoop 文件】，可以直接丢进 Wireshark 分析，
 * 体积通常能压到原来的百分之几。
 *
 * 全程本地处理，不联网、不上传。
 */
public final class HciFilter {

    /** btsnoop 文件头标识 */
    private static final byte[] MAGIC = {
            'b', 't', 's', 'n', 'o', 'o', 'p', 0
    };

    public static final class Result {
        public boolean ok;
        public String message;
        public File out;
        public long inBytes;
        public long outBytes;
        public int totalRecords;
        public int keptRecords;
        /** 找到的 handle 列表（16 进制字符串），便于人工核对 */
        public List<String> handles = new ArrayList<>();
        /** 日志里出现过的所有设备 MAC，便于确认目标是否真的被抓到 */
        public List<String> seenMacs = new ArrayList<>();
    }

    private HciFilter() {
    }

    /**
     * 精简日志。
     *
     * @param ctx   用于打开 Uri
     * @param uri   用户选中的文件（btsnoop_hci.log 或 bugreport*.zip）
     * @param mac   目标耳机 MAC，如 48:73:CB:63:8E:3A
     * @param outDir 输出目录
     */
    public static Result filter(android.content.Context ctx, Uri uri, String mac, File outDir) {
        Result r = new Result();
        try {
            if (!outDir.exists() && !outDir.mkdirs()) {
                r.message = "无法创建输出目录";
                return r;
            }
            File tmp = new File(outDir, "btsnoop_full.tmp");
            long inBytes = 0L;

            // 1) 拿到原始 btsnoop 文件：可能是 zip，也可能是直接的 log
            InputStream raw = ctx.getContentResolver().openInputStream(uri);
            if (raw == null) {
                r.message = "打不开选中的文件";
                return r;
            }
            String name = "";
            try {
                android.database.Cursor c = ctx.getContentResolver()
                        .query(uri, null, null, null, null);
                if (c != null) {
                    int i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                    if (i >= 0 && c.moveToFirst()) name = c.getString(i);
                    c.close();
                }
            } catch (Throwable ignored) {
            }

            if (name.toLowerCase().endsWith(".zip")) {
                inBytes = extractBtsnoop(raw, tmp, r);
                if (inBytes <= 0) {
                    r.message = "压缩包里没找到 btsnoop_hci.log";
                    return r;
                }
            } else {
                inBytes = copy(raw, tmp);
            }
            raw.close();
            r.inBytes = inBytes;

            // 2) 解析 + 过滤
            File out = new File(outDir, "btsnoop_filtered.log");
            parse(tmp, out, mac == null ? "" : mac.trim().toUpperCase(Locale.US), r);
            tmp.delete();
            r.out = out;
            r.outBytes = out.length();
            r.ok = true;
            if (r.message == null || r.message.isEmpty()) {
                r.message = "完成";
            }
            return r;
        } catch (Throwable e) {
            r.ok = false;
            r.message = "处理失败：" + e.getMessage();
            return r;
        }
    }

    /** 从 bugreport zip 中抽出 btsnoop_hci.log */
    private static long extractBtsnoop(InputStream in, File dst, Result r) throws Exception {
        ZipInputStream zis = new ZipInputStream(in);
        ZipEntry e;
        long size = 0L;
        OutputStream os = null;
        try {
            while ((e = zis.getNextEntry()) != null) {
                String n = e.getName().toLowerCase();
                if (!e.isDirectory() && n.endsWith("btsnoop_hci.log")) {
                    os = new BufferedOutputStream(new FileOutputStream(dst));
                    byte[] buf = new byte[65536];
                    int len;
                    while ((len = zis.read(buf)) > 0) {
                        os.write(buf, 0, len);
                        size += len;
                    }
                    os.close();
                    os = null;
                    break;
                }
                zis.closeEntry();
            }
        } finally {
            if (os != null) try { os.close(); } catch (Throwable ignored) { }
            try { zis.close(); } catch (Throwable ignored) { }
        }
        return size;
    }

    private static long copy(InputStream in, File dst) throws Exception {
        long size = 0L;
        OutputStream os = new BufferedOutputStream(new FileOutputStream(dst));
        byte[] buf = new byte[65536];
        int len;
        while ((len = in.read(buf)) > 0) {
            os.write(buf, 0, len);
            size += len;
        }
        os.close();
        return size;
    }

    /** btsnoop 解析 + 按 handle 过滤 */
    private static void parse(File src, File dst, String mac, Result r) throws Exception {
        byte[] all = readAll(src);
        if (all.length < 16 || !startsWith(all, MAGIC)) {
            throw new IllegalStateException("不是有效的 btsnoop 文件（缺少文件头）");
        }

        // ---- 第一遍：建立 handle -> MAC 的映射 ----
        // 记录格式：orig_len(4) incl_len(4) flags(4) drops(4) ts(8) + data
        Set<Integer> target = new HashSet<>();
        Set<String> seen = new LinkedHashSetCompat();

        int p = 16;
        while (p + 24 <= all.length) {
            int incl = be32(all, p + 4);
            if (incl < 0 || incl > 0x100000) break;   // 异常长度，停止解析
            if (p + 24 + incl > all.length) break;
            int off = p + 24;
            scanForMac(all, off, incl, mac, target, seen);
            p += 24 + incl;
        }
        r.seenMacs.addAll(seen);
        for (Integer h : target) r.handles.add("0x" + Integer.toHexString(h));

        // 一个 MAC 都没匹配到时，退回"保留全部"至少不会丢数据，
        // 但明确告知用户，避免误以为过滤成功
        boolean matched = !target.isEmpty();

        // ---- 第二遍：写出 ----
        FileOutputStream fos = new FileOutputStream(dst);
        BufferedOutputStream bos = new BufferedOutputStream(fos, 1 << 20);
        bos.write(MAGIC);
        bos.write(all, 8, 8);            // version + datalink type 原样保留

        int total = 0, kept = 0;
        p = 16;
        while (p + 24 <= all.length) {
            int incl = be32(all, p + 4);
            if (incl < 0 || incl > 0x100000) break;
            if (p + 24 + incl > all.length) break;
            int off = p + 24;
            total++;
            boolean keep;
            if (!matched) {
                keep = true;             // 没匹配到：全留，交由人工判断
            } else {
                keep = belongsTo(all, off, incl, target);
            }
            if (keep) {
                bos.write(all, p, 24 + incl);
                kept++;
            }
            p += 24 + incl;
        }
        bos.close();
        r.totalRecords = total;
        r.keptRecords = kept;
        if (!matched) {
            r.message = "日志里没找到该 MAC 的连接记录（可能是抓包时没连上耳机）。"
                    + "已原样保留全部数据包。";
        }
    }

    /** 从 HCI 包里找 Connection Complete 事件，提取 MAC 与 handle */
    private static void scanForMac(byte[] a, int off, int len, String mac,
                                   Set<Integer> target, Set<String> seen) {
        if (len < 2 || a[off] != 0x04) return;      // 只看 HCI Event
        int code = a[off + 1] & 0xFF;
        try {
            if (code == 0x03 && len >= 11) {        // Connection Complete
                int handle = le16(a, off + 3) & 0x0FFF;
                String m = macFromBdAddr(a, off + 5);
                if (m != null) seen.add(m);
                if (m != null && m.equals(mac)) target.add(handle);
            } else if (code == 0x3E && len >= 15 && (a[off + 3] & 0xFF) == 0x01) {
                // LE Meta: LE Connection Complete
                int handle = le16(a, off + 6) & 0x0FFF;
                String m = macFromBdAddr(a, off + 9);
                if (m != null) seen.add(m);
                if (m != null && m.equals(mac)) target.add(handle);
            }
        } catch (Throwable ignored) {
        }
    }

    /** 判断该包是否属于目标连接 */
    private static boolean belongsTo(byte[] a, int off, int len, Set<Integer> handles) {
        if (len < 1) return false;
        int type = a[off] & 0xFF;
        if (type == 0x02 && len >= 4) {             // ACL Data
            int h = le16(a, off + 1) & 0x0FFF;
            return handles.contains(h);
        }
        // HCI Event：只保留与连接相关的少量事件（断连/连接完成），其余丢弃
        if (type == 0x04 && len >= 3) {
            int code = a[off + 1] & 0xFF;
            if (code == 0x03 && len >= 5) {         // Connection Complete
                return handles.contains(le16(a, off + 3) & 0x0FFF);
            }
            if (code == 0x05 && len >= 4) {         // Disconnection Complete
                return handles.contains(le16(a, off + 3) & 0x0FFF);
            }
            if (code == 0x3E && len >= 7 && (a[off + 3] & 0xFF) == 0x01) {
                return handles.contains(le16(a, off + 6) & 0x0FFF);
            }
            return false;
        }
        // HCI Command 也保留：里面常有读电量/发指令的记录，量不大且很关键
        return type == 0x01;
    }

    private static String macFromBdAddr(byte[] a, int off) {
        if (off + 6 > a.length) return null;
        StringBuilder sb = new StringBuilder();
        for (int i = 5; i >= 0; i--) {              // BD_ADDR 是反序存的
            if (i < 5) sb.append(':');
            sb.append(String.format(Locale.US, "%02X", a[off + i] & 0xFF));
        }
        return sb.toString();
    }

    private static byte[] readAll(File f) throws Exception {
        long n = f.length();
        if (n > 512L * 1024 * 1024) throw new IllegalStateException("文件过大（>512MB）");
        byte[] b = new byte[(int) n];
        java.io.FileInputStream in = new java.io.FileInputStream(f);
        int read = 0;
        while (read < b.length) {
            int k = in.read(b, read, b.length - read);
            if (k < 0) break;
            read += k;
        }
        in.close();
        if (read != b.length) {
            byte[] t = new byte[read];
            System.arraycopy(b, 0, t, 0, read);
            return t;
        }
        return b;
    }

    private static int be32(byte[] a, int o) {
        return ((a[o] & 0xFF) << 24) | ((a[o + 1] & 0xFF) << 16)
                | ((a[o + 2] & 0xFF) << 8) | (a[o + 3] & 0xFF);
    }

    private static int le16(byte[] a, int o) {
        return (a[o] & 0xFF) | ((a[o + 1] & 0xFF) << 8);
    }

    private static boolean startsWith(byte[] a, byte[] m) {
        if (a.length < m.length) return false;
        for (int i = 0; i < m.length; i++) {
            if (a[i] != m[i]) return false;
        }
        return true;
    }

    /** 保持插入顺序的 Set（避免引入额外依赖，同时让 MAC 列表稳定） */
    private static final class LinkedHashSetCompat extends java.util.LinkedHashSet<String> {
    }
}
