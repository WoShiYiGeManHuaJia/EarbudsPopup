package com.earpopupx;

import android.Manifest;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.util.SparseArray;

import java.util.ArrayList;
import java.util.List;

/**
 * Vendor battery sniffer.
 *
 * Two channels that ordinary apps can legally use, and which the previous
 * implementation never touched:
 *
 *  1) HFP vendor specific AT commands. Headsets push their battery over
 *     AT+IPHONEACCEV / AT+XEVENT right after the service level connection is
 *     established - i.e. exactly at the moment the case is opened. Android's
 *     own bluetooth stack uses this broadcast to update the cached battery
 *     level, and it is a normal documented broadcast (API 11+).
 *
 *  2) BLE advertisement manufacturer specific data. This is how OpenPods reads
 *     AirPods left/right/case without a GATT connection at all.
 *
 * Nothing here invents a number. Every byte that arrives is also exposed as a
 * hex string so it can be inspected in diagnostics.
 */
public final class VendorBatterySniffer {

    public interface Sink {
        void onBattery(BatteryState state);
        void onRaw(String line);
    }

    private static final String ACTION_VENDOR =
            "android.bluetooth.headset.action.VENDOR_SPECIFIC_HEADSET_EVENT";
    private static final String EXTRA_CMD =
            "android.bluetooth.headset.extra.VENDOR_SPECIFIC_HEADSET_EVENT_CMD";
    private static final String EXTRA_CMD_TYPE =
            "android.bluetooth.headset.extra.VENDOR_SPECIFIC_HEADSET_EVENT_CMD_TYPE";
    private static final String EXTRA_ARGS =
            "android.bluetooth.headset.extra.VENDOR_SPECIFIC_HEADSET_EVENT_ARGS";
    private static final String CATEGORY_PREFIX =
            "android.bluetooth.headset.intent.category.companyid.";

    // Vendor IDs worth listening to. The headset reports under its own company
    // id, so a single registration would miss most devices.
    private static final int[] COMPANY_IDS = {
            76,   // Apple      (+IPHONEACCEV, +XAPL)
            63,   // Plantronics(+XEVENT)
            224,  // Google     (+ANDROID)
            117,  // Samsung
            343,  // Xiaomi
            29,   // Sony
            2652, // Bose
            2249, // Qualcomm / Airoha based designs
    };

    private final Context app;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<BroadcastReceiver> receivers = new ArrayList<>();
    private Sink sink;
    private String targetAddress;
    private BluetoothLeScanner scanner;
    private ScanCallback scanCallback;
    private boolean running;

    public VendorBatterySniffer(Context c) {
        app = c.getApplicationContext();
    }

    public void setSink(Sink s) {
        sink = s;
    }

    // ------------------------------------------------------------ dumpsys

    /** 执行 shell：先用本进程 Runtime 直跑（普通 App 也能跑 dumpsys，只是输出可能被裁剪），
     *  拿不到再退到 Shizuku/Stellar 的 adb 权限。两条路都不依赖 Root。 */
    public static String runShell(String cmd) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"sh", "-c", cmd});
            final java.io.InputStream in = p.getInputStream();
            StringBuilder sb = new StringBuilder();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) sb.append(new String(buf, 0, n, "UTF-8"));
            try { in.close(); } catch (Throwable ignored) {}
            try { p.destroy(); } catch (Throwable ignored) {}
            String s = sb.toString();
            if (s != null && s.length() > 20) return s;
        } catch (Throwable ignored) {}
        try {
            if (ShizukuHelper.isServiceRunning() && ShizukuHelper.hasPermission()) {
                String s = ShizukuHelper.run(cmd);
                if (s != null && s.length() > 20) return s;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /** 读 dumpsys bluetooth_manager，按 MAC 定位那一段，取左右耳与充电盒电量。
     *  这是能看到 untethered_left/right/case_battery 的唯一途径。 */
    public static BatteryState fromDumpsys(String address, StringBuilder log) {
        String out = runShell("dumpsys bluetooth_manager");
        if (out == null) out = runShell("dumpsys bluetooth");
        if (out == null) {
            if (log != null) log.append("dumpsys: 执行失败\n");
            return null;
        }
        return parseDumpsys(out, address, log);
    }

    static BatteryState parseDumpsys(String text, String address, StringBuilder log) {
        String[] lines = text.split("\n");
        String block = null;
        if (address != null && address.length() > 6) {
            for (String l : lines) {
                if (l.contains(address)) { block = l; break; }
            }
        }
        if (block == null) {
            for (String l : lines) {
                if (l.indexOf("untethered_left_battery") < 0) continue;
                String v = field(l, "untethered_left_battery");
                if (v != null && !"null".equalsIgnoreCase(v)) { block = l; break; }
            }
        }
        if (block == null) {
            if (log != null) log.append("dumpsys: 未定位到设备段（MAC 可能被脱敏）\n");
            return null;
        }
        int left = num(field(block, "untethered_left_battery"));
        int right = num(field(block, "untethered_right_battery"));
        int caseL = num(field(block, "untethered_case_battery"));
        int main = num(field(block, "main_battery"));
        boolean lc = on(field(block, "untethered_left_charging"));
        boolean rc = on(field(block, "untethered_right_charging"));
        boolean cc = on(field(block, "untethered_case_charging"));
        if (log != null) {
            log.append("dumpsys L=").append(left).append(" R=").append(right)
               .append(" C=").append(caseL).append(" main=").append(main).append("\n");
        }
        if (left < 0 && right < 0 && caseL < 0 && main < 0) return null;
        return new BatteryState(main, left, right, caseL, lc, rc, cc, "dumpsys 左右耳");
    }

    private static String field(String line, String key) {
        int i = line.indexOf(key + "=");
        if (i < 0) return null;
        int s = i + key.length() + 1;
        int e = s;
        while (e < line.length()) {
            char ch = line.charAt(e);
            if (ch == '|' || ch == ',' || ch == ')' || ch == '}' || ch == ' ' || ch == '\t') break;
            e++;
        }
        return line.substring(s, e);
    }

    private static int num(String v) {
        if (v == null) return -1;
        String t = v.trim();
        if (t.isEmpty() || "null".equalsIgnoreCase(t)) return -1;
        try {
            int n = Integer.parseInt(t);
            return (n >= 0 && n <= 100) ? n : -1;
        } catch (Throwable e) { return -1; }
    }

    private static boolean on(String v) {
        if (v == null) return false;
        String t = v.trim();
        return "true".equalsIgnoreCase(t) || "1".equals(t);
    }

    public void start(String address) {
        targetAddress = address;
        registerVendorEvents();
        startBleScan();
        running = true;
    }

    public void stop() {
        running = false;
        for (BroadcastReceiver r : receivers) {
            try {
                app.unregisterReceiver(r);
            } catch (Throwable ignored) {
            }
        }
        receivers.clear();
        stopBleScan();
    }

    // ---------------------------------------------------------------- HFP AT

    private void registerVendorEvents() {
        for (int id : COMPANY_IDS) {
            BroadcastReceiver r = new BroadcastReceiver() {
                @Override
                public void onReceive(Context c, Intent i) {
                    handleVendorIntent(i);
                }
            };
            IntentFilter f = new IntentFilter(ACTION_VENDOR);
            f.addCategory(CATEGORY_PREFIX + id);
            try {
                app.registerReceiver(r, f);
                receivers.add(r);
            } catch (Throwable ignored) {
            }
        }
    }

    private void handleVendorIntent(Intent i) {
        if (i == null) {
            return;
        }
        String cmd = i.getStringExtra(EXTRA_CMD);
        int type = i.getIntExtra(EXTRA_CMD_TYPE, -1);
        Object raw = i.getExtras() == null ? null : i.getExtras().get(EXTRA_ARGS);
        Object[] args = (raw instanceof Object[]) ? (Object[]) raw : null;

        StringBuilder dump = new StringBuilder();
        dump.append("AT ").append(cmd == null ? "?" : cmd).append(" type=").append(type);
        if (args != null) {
            for (Object a : args) {
                dump.append(" | ").append(a == null ? "null" : a.toString());
            }
        }
        push("HFP " + dump);

        if (cmd == null || args == null) {
            return;
        }

        // AT+IPHONEACCEV=[n],key1,val1,...  key 1 = battery, value 0..9 -> tens
        if ("+IPHONEACCEV".equalsIgnoreCase(cmd)) {
            int level = parseIphoneAccev(args);
            if (level >= 0) {
                emit(new BatteryState(level, -1, -1, -1, false, false, false, "HFP AT+IPHONEACCEV"));
            }
            return;
        }

        // AT+XEVENT=BATTERY,[level],[numOfLevel],[minutesOfTalk],[isCharging]
        if ("+XEVENT".equalsIgnoreCase(cmd)) {
            int level = parseXevent(args);
            if (level >= 0) {
                emit(new BatteryState(level, -1, -1, -1, false, false, false, "HFP AT+XEVENT"));
            }
            return;
        }

        // Vendor commands we do not know are still recorded: if a headset pushes
        // left/right/case as three small values this is where it becomes visible.
        if ("+XAPL".equalsIgnoreCase(cmd)) {
            return;
        }
        int[] triple = guessTriple(args);
        if (triple != null) {
            emit(new BatteryState(triple[0], triple[1], triple[2], -1,
                    false, false, false, "HFP " + cmd));
        }
    }

    private static int parseIphoneAccev(Object[] args) {
        if (args.length == 0 || !(args[0] instanceof Integer)) {
            return -1;
        }
        int pairs = (Integer) args[0];
        if (args.length < pairs * 2 + 1) {
            return -1;
        }
        for (int k = 0; k < pairs; k++) {
            Object key = args[2 * k + 1];
            Object val = args[2 * k + 2];
            if (key instanceof Integer && val instanceof Integer && (Integer) key == 1) {
                int v = (Integer) val;
                if (v >= 0 && v <= 9) {
                    return (v + 1) * 10;
                }
            }
        }
        return -1;
    }

    private static int parseXevent(Object[] args) {
        if (args.length < 3 || !(args[0] instanceof String)) {
            return -1;
        }
        if (!"BATTERY".equalsIgnoreCase((String) args[0])) {
            return -1;
        }
        if (args[1] instanceof Integer) {
            int v = (Integer) args[1];
            if (v >= 0 && v <= 100) {
                return v;
            }
        }
        return -1;
    }

    /**
     * Only accepts a 3-value payload that looks unmistakably like a TWS triple:
     * three consecutive integers inside 0..100 with at least one non-zero.
     * Anything else is ignored rather than displayed.
     */
    private static int[] guessTriple(Object[] args) {
        if (args.length < 3) {
            return null;
        }
        for (int s = 0; s + 2 < args.length; s++) {
            Integer a = asInt(args[s]);
            Integer b = asInt(args[s + 1]);
            Integer c = asInt(args[s + 2]);
            if (a == null || b == null || c == null) {
                continue;
            }
            if (a == 0 && b == 0 && c == 0) {
                continue;
            }
            if (a >= 0 && a <= 100 && b >= 0 && b <= 100 && c >= 0 && c <= 100) {
                return new int[]{a, b, c};
            }
        }
        return null;
    }

    private static Integer asInt(Object o) {
        return o instanceof Integer ? (Integer) o : null;
    }

    // ---------------------------------------------------------------- BLE adv

    private void startBleScan() {
        if (android.os.Build.VERSION.SDK_INT < 21) {
            return;
        }
        if (android.os.Build.VERSION.SDK_INT >= 31
                && app.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)
                != PackageManager.PERMISSION_GRANTED) {
            push("BLE 缺少 BLUETOOTH_SCAN 权限");
            return;
        }
        try {
            android.bluetooth.BluetoothAdapter ad =
                    android.bluetooth.BluetoothAdapter.getDefaultAdapter();
            if (ad == null || !ad.isEnabled()) {
                return;
            }
            scanner = ad.getBluetoothLeScanner();
            if (scanner == null) {
                return;
            }
            ScanSettings st = new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .setReportDelay(0L)
                    .build();
            List<ScanFilter> filters = new ArrayList<>();
            if (targetAddress != null) {
                filters.add(new ScanFilter.Builder().setDeviceAddress(targetAddress).build());
            }
            scanCallback = new ScanCallback() {
                @Override
                public void onScanResult(int callbackType, ScanResult result) {
                    handleAdv(result);
                }

                @Override
                public void onScanFailed(int errorCode) {
                    push("BLE 扫描失败 " + errorCode);
                }
            };
            scanner.startScan(filters, st, scanCallback);
            main.postDelayed(this::stopBleScan, 12000L);
        } catch (Throwable t) {
            push("BLE 扫描异常 " + t.getClass().getSimpleName());
        }
    }

    private void stopBleScan() {
        try {
            if (scanner != null && scanCallback != null) {
                scanner.stopScan(scanCallback);
            }
        } catch (Throwable ignored) {
        }
        scanner = null;
        scanCallback = null;
    }

    private void handleAdv(ScanResult result) {
        if (result == null) {
            return;
        }
        BluetoothDevice d = result.getDevice();
        String addr = null;
        try {
            addr = d.getAddress();
        } catch (Throwable ignored) {
        }
        if (targetAddress != null && addr != null && !targetAddress.equals(addr)) {
            return;
        }
        ScanRecord rec = result.getScanRecord();
        if (rec == null) {
            return;
        }
        SparseArray<byte[]> mf = rec.getManufacturerSpecificData();
        if (mf == null || mf.size() == 0) {
            return;
        }
        for (int i = 0; i < mf.size(); i++) {
            int id = mf.keyAt(i);
            byte[] data = mf.valueAt(i);
            if (data == null || data.length == 0) {
                continue;
            }
            push("ADV id=" + id + " " + hex(data));
            int[] triple = parseAdvTriple(id, data);
            if (triple != null) {
                emit(new BatteryState(triple[0], triple[1], triple[2], -1,
                        false, false, false, "BLE 广播 id=" + id));
            }
        }
    }

    /**
     * Known layouts only. AirPods style advertisement: Apple company id 76,
     * payload starting with 0x07 0x19, battery nibbles follow. Anything that
     * does not match a known shape is left alone.
     */
    private static int[] parseAdvTriple(int companyId, byte[] d) {
        if (companyId == 76 && d.length >= 8 && (d[0] & 0xff) == 0x07
                && (d[1] & 0xff) == 0x19) {
            int left = (d[4] & 0x0f);
            int right = ((d[4] & 0xf0) >> 4);
            int caseL = (d[5] & 0x0f);
            left = clampTens(left);
            right = clampTens(right);
            caseL = clampTens(caseL);
            if (left >= 0 && right >= 0 && caseL >= 0) {
                return new int[]{left, right, caseL};
            }
        }
        return null;
    }

    private static int clampTens(int nibble) {
        if (nibble < 0 || nibble > 10) {
            return -1;
        }
        return nibble * 10;
    }

    private static String hex(byte[] b) {
        StringBuilder s = new StringBuilder();
        for (byte v : b) {
            String h = Integer.toHexString(v & 0xff);
            if (h.length() == 1) {
                s.append('0');
            }
            s.append(h).append(' ');
        }
        return s.toString().trim();
    }

    // ---------------------------------------------------------------- output

    private void emit(BatteryState s) {
        if (!running || sink == null) {
            return;
        }
        main.post(() -> {
            if (running && sink != null) {
                sink.onBattery(s);
            }
        });
    }

    private void push(String line) {
        if (!running || sink == null) {
            return;
        }
        main.post(() -> {
            if (running && sink != null) {
                sink.onRaw(line);
            }
        });
    }
}
