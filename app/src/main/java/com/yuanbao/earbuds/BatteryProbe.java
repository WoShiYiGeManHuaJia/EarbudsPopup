package com.yuanbao.earbuds;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.SparseArray;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 三方电量探测。
 *
 * 三条路径按优先级尝试：
 *  A. BLE GATT Battery Service（0x180F / 0x2A19）
 *     —— Bluetooth SIG 规范允许一个设备声明多个 Battery Service 实例，
 *        多实例时每个 Battery Level 特征应带 0x2904 Presentation Format 描述符，
 *        其 Description 用于区分语义（左耳 / 右耳 / 充电盒）。
 *        这是第三方 App 唯一「既有公开语义又能访问」的入口。
 *  B. MiBeacon 厂商广播（小米 company ID = 0x038F）
 *     —— 可无连接快速读取，但 TWS 电量的 Object ID 与字节布局未公开，
 *        只能做候选字段扫描，最终需要抓包确认。
 *  C. 隐藏方法 BluetoothDevice.getBatteryLevel()
 *     —— 只有整机单值，作为兜底与对照。
 */
public final class BatteryProbe {

    // Bluetooth SIG 标准
    private static final UUID UUID_BATTERY_SERVICE = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb");
    private static final UUID UUID_BATTERY_LEVEL = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb");
    private static final UUID UUID_PRESENTATION_FORMAT = UUID.fromString("00002904-0000-1000-8000-00805f9b34fb");

    // 小米 Bluetooth Company Identifier
    private static final int XIAOMI_COMPANY_ID = 0x038F;

    public interface Callback {
        void onResult(BatteryLevels levels, String diagnostic);
    }

    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final StringBuilder log = new StringBuilder();

    private BluetoothGatt gatt;
    private final AtomicBoolean done = new AtomicBoolean(false);
    private volatile BatteryLevels metaLevels;

    public BatteryProbe(Context c) {
        ctx = c.getApplicationContext();
    }

    private void log(String s) {
        log.append(s).append('\n');
    }

    @SuppressLint("MissingPermission")
    public void probe(String address, BluetoothDevice device, Callback cb) {
        log("========== 电量探测 ==========");
        log("地址: " + address);
        log("设备名: " + safeName(device));

        // C. 先读系统隐藏单值作为对照
        int sys = readSystemBattery(device);
        log("系统隐藏 getBatteryLevel() = " + sys);
        final int overall = sys;

        // D. 尝试反射读系统三方电量元数据（Android 13+ 存的就是左/右/盒）
        final BatteryLevels meta = readSystemMetadata(device);
        if (meta != null) {
            log("系统元数据: L=" + meta.left + " R=" + meta.right + " Case=" + meta.caseBox);
        } else {
            log("系统元数据不可用（SystemApi，通常被隐藏 API 限制拦住）");
        }
        metaLevels = meta;

        // A + B 并行：先扫广播拿 MiBeacon，再连 GATT
        scanThenConnect(address, device, overall, cb);
    }

    private String safeName(BluetoothDevice d) {
        try {
            String n = d.getName();
            return n == null ? "(null)" : n;
        } catch (SecurityException e) {
            return "(需要 BLUETOOTH_CONNECT)";
        }
    }

    /**
     * D. 反射读 Android 13+ 的三方电量元数据。
     * 系统把左/右/盒电量就存在 BluetoothDevice 的 METADATA_UNTETHERED_* 里，
     * 小米自己的弹窗也是读这里。对普通 App 是 SystemApi，多数情况下会被隐藏 API 限制拦住，
     * 但值得一试——一旦通了就是最准的数据源。
     */
    private BatteryLevels readSystemMetadata(BluetoothDevice dev) {
        if (dev == null) return null;
        try {
            java.lang.reflect.Method m = dev.getClass().getMethod("getMetadata", int.class);
            int kl = staticInt(dev, "METADATA_UNTETHERED_LEFT_BATTERY", -1);
            int kr = staticInt(dev, "METADATA_UNTETHERED_RIGHT_BATTERY", -1);
            int kc = staticInt(dev, "METADATA_UNTETHERED_CASE_BATTERY", -1);
            log("元数据字段 key: LEFT=" + kl + " RIGHT=" + kr + " CASE=" + kc);
            BatteryLevels b = new BatteryLevels();
            boolean any = false;
            if (kl >= 0) {
                b.left = metaByte(m, dev, kl);
                if (b.left >= 0) any = true;
            }
            if (kr >= 0) {
                b.right = metaByte(m, dev, kr);
                if (b.right >= 0) any = true;
            }
            if (kc >= 0) {
                b.caseBox = metaByte(m, dev, kc);
                if (b.caseBox >= 0) any = true;
            }
            return any ? b : null;
        } catch (Throwable t) {
            log("getMetadata 不可用: " + t);
            return null;
        }
    }

    private int metaByte(java.lang.reflect.Method m, BluetoothDevice dev, int key) {
        try {
            Object r = m.invoke(dev, key);
            if (r instanceof byte[]) {
                byte[] arr = (byte[]) r;
                if (arr.length > 0) return arr[0] & 0xFF;
            }
        } catch (Throwable ignored) {
        }
        return -1;
    }

    private int staticInt(BluetoothDevice dev, String field, int def) {
        try {
            java.lang.reflect.Field f = dev.getClass().getDeclaredField(field);
            f.setAccessible(true);
            return f.getInt(null);
        } catch (Throwable t) {
            try {
                java.lang.reflect.Field f = BluetoothDevice.class.getDeclaredField(field);
                f.setAccessible(true);
                return f.getInt(null);
            } catch (Throwable ignored) {
                return def;
            }
        }
    }

    /** C. 反射读隐藏单值 */
    private int readSystemBattery(BluetoothDevice dev) {
        if (dev == null) return -1;
        try {
            java.lang.reflect.Method m = dev.getClass().getMethod("getBatteryLevel");
            Object r = m.invoke(dev);
            if (r instanceof Integer) return (Integer) r;
        } catch (Throwable ignored) {
        }
        return -1;
    }

    // ---------------------------------------------------------------
    //  B. BLE 扫描：抓广播，解析 MiBeacon
    // ---------------------------------------------------------------

    @SuppressLint("MissingPermission")
    private void scanThenConnect(String address, BluetoothDevice device,
                                 int overall, Callback cb) {
        BluetoothManager bm = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = bm != null ? bm.getAdapter() : BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) {
            log("蓝牙适配器不可用");
            finish(overall, cb);
            return;
        }
        BluetoothLeScanner scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) {
            log("BLE 扫描器不可用，直接走 GATT");
            connectGatt(address, device, overall, new ArrayList<>(), cb);
            return;
        }

        final List<byte[]> advertRecords = new ArrayList<>();
        ScanFilter f = new ScanFilter.Builder().setDeviceAddress(address).build();
        ScanSettings s = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();

        ScanCallback sc = new ScanCallback() {
            @Override
            public void onScanResult(int callbackType, ScanResult result) {
                ScanRecord rec = result.getScanRecord();
                if (rec == null) return;
                SparseArray<byte[]> md = rec.getManufacturerSpecificData();
                for (int i = 0; i < md.size(); i++) {
                    int id = md.keyAt(i);
                    byte[] data = md.valueAt(i);
                    advertRecords.add(concatCompanyId(id, data));
                    log("广播 manufacturer id=0x" + String.format("%04X", id)
                            + " len=" + data.length);
                }
                byte[] svc = rec.getServiceData(android.os.ParcelUuid.fromString("0000fe95-0000-1000-8000-00805f9b34fb"));
                if (svc != null) {
                    log("Mi Service 0xFE95 data len=" + svc.length + " hex=" + hex(svc));
                }
            }

            @Override
            public void onScanFailed(int errorCode) {
                log("BLE 扫描失败 code=" + errorCode);
            }
        };

        try {
            scanner.startScan(Collections.singletonList(f), s, sc);
        } catch (SecurityException e) {
            log("扫描缺少权限: " + e.getMessage());
            connectGatt(address, device, overall, advertRecords, cb);
            return;
        } catch (Exception e) {
            log("扫描异常: " + e.getMessage());
        }

        main.postDelayed(() -> {
            try {
                scanner.stopScan(sc);
            } catch (Exception ignored) {
            }
            log("扫描结束，抓到 " + advertRecords.size() + " 条厂商数据");
            parseMiBeacon(advertRecords);
            connectGatt(address, device, overall, advertRecords, cb);
        }, 4000);
    }

    private byte[] concatCompanyId(int id, byte[] data) {
        byte[] out = new byte[data.length + 2];
        out[0] = (byte) (id & 0xFF);
        out[1] = (byte) ((id >> 8) & 0xFF);
        System.arraycopy(data, 0, out, 2, data.length);
        return out;
    }

    /** 解析 MiBeacon 框架，并记录可用于逆向的原始字节 */
    private void parseMiBeacon(List<byte[]> records) {
        for (byte[] raw : records) {
            if (raw.length < 7) continue;
            int companyId = (raw[0] & 0xFF) | ((raw[1] & 0xFF) << 8);
            if (companyId != XIAOMI_COMPANY_ID) continue;

            int frameControl = (raw[2] & 0xFF) | ((raw[3] & 0xFF) << 8);
            int productId = (raw[4] & 0xFF) | ((raw[5] & 0xFF) << 8);
            int frameCounter = raw[6] & 0xFF;

            log("MiBeacon: productId=0x" + String.format("%04X", productId)
                    + " frameControl=0x" + String.format("%04X", frameControl)
                    + " counter=" + frameCounter);
            log("  payload hex=" + hex(raw));
            log("  完整原始(含 companyId)=" + hex(raw));
        }
    }

    // ---------------------------------------------------------------
    //  A. GATT：枚举全部 0x180F 实例
    // ---------------------------------------------------------------

    @SuppressLint("MissingPermission")
    private void connectGatt(String address, BluetoothDevice device, int overall,
                             List<byte[]> advertRecords, Callback cb) {
        BluetoothDevice target = device;
        if (target == null) {
            BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter();
            if (a == null) {
                finish(overall, cb);
                return;
            }
            try {
                target = a.getRemoteDevice(address);
            } catch (Exception e) {
                log("无法取得远端设备: " + e.getMessage());
                finish(overall, cb);
                return;
            }
        }

        List<BluetoothGattService> batteryServices = new ArrayList<>();
        List<BluetoothGattCharacteristic> levels = new ArrayList<>();

        BluetoothGattCallback gc = new BluetoothGattCallback() {

            @Override
            public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
                log("GATT 连接状态 status=" + status + " state=" + newState);
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    try {
                        g.discoverServices();
                    } catch (SecurityException e) {
                        log("discoverServices 缺权限: " + e.getMessage());
                        closeAndFinish(g, overall, cb);
                    }
                } else {
                    closeAndFinish(g, overall, cb);
                }
            }

            @Override
            public void onServicesDiscovered(BluetoothGatt g, int status) {
                log("服务发现 status=" + status);
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    closeAndFinish(g, overall, cb);
                    return;
                }

                // 完整 GATT 树 dump，供逆向分析
                log("---- GATT 服务树 ----");
                for (BluetoothGattService svc : g.getServices()) {
                    log("服务 " + svc.getUuid() + " type=" + svc.getType());
                    for (BluetoothGattCharacteristic ch : svc.getCharacteristics()) {
                        log("  特征 " + ch.getUuid() + " props=" + ch.getProperties());
                        for (BluetoothGattDescriptor d : ch.getDescriptors()) {
                            log("    描述符 " + d.getUuid());
                        }
                    }
                }

                // 收集所有 Battery Service 实例（不能用 getService() 只取首个）
                for (BluetoothGattService svc : g.getServices()) {
                    if (UUID_BATTERY_SERVICE.equals(svc.getUuid())) {
                        batteryServices.add(svc);
                    }
                }
                log("找到 0x180F 实例数 = " + batteryServices.size());

                for (BluetoothGattService svc : batteryServices) {
                    for (BluetoothGattCharacteristic ch : svc.getCharacteristics()) {
                        if (UUID_BATTERY_LEVEL.equals(ch.getUuid())) {
                            levels.add(ch);
                        }
                    }
                }

                if (levels.isEmpty()) {
                    log("没有 0x2A19 特征");
                    closeAndFinish(g, overall, cb);
                    return;
                }

                readNext(g, levels, 0, overall, cb);
            }

            @Override
            public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic ch,
                                             int status) {
                // 异步读取由 readNext 串行驱动，这里通过共享列表收值
                synchronized (levels) {
                    Integer v = decodeLevel(ch);
                    ch.setValue(new byte[]{0});
                    log("读取 " + ch.getUuid() + " = " + (v == null ? "null" : v)
                            + " status=" + status);
                }
                int idx = indexOf(levels, ch);
                if (idx >= 0) {
                    readNext(g, levels, idx + 1, overall, cb);
                } else {
                    closeAndFinish(g, overall, cb);
                }
            }

            @Override
            public void onDescriptorRead(BluetoothGatt g, BluetoothGattDescriptor d, int status) {
                try {
                    byte[] v = d.getValue();
                    log("描述符 " + d.getUuid() + " = " + (v == null ? "null" : hex(v))
                            + " 文本=[" + (v == null ? "" : asText(v)) + "]");
                } catch (Exception ignored) {
                }
            }
        };

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                gatt = target.connectGatt(ctx, false, gc, BluetoothDevice.TRANSPORT_LE);
            } else {
                gatt = target.connectGatt(ctx, false, gc);
            }
        } catch (SecurityException e) {
            log("connectGatt 缺权限: " + e.getMessage());
            finish(overall, cb);
            return;
        }
        if (gatt == null) {
            log("connectGatt 返回 null");
            finish(overall, cb);
            return;
        }

        // 总超时 15 秒
        main.postDelayed(() -> {
            if (!done.get()) {
                log("探测总超时(15s)，结束");
                closeAndFinish(gatt, overall, cb);
            }
        }, 15000);
    }

    @SuppressLint("MissingPermission")
    private void readNext(BluetoothGatt g, List<BluetoothGattCharacteristic> list,
                          int i, int overall, Callback cb) {
        if (i >= list.size()) {
            // 全部读完，汇总
            finalizeLevels(list, overall, cb);
            closeAndFinish(g, overall, cb);
            return;
        }
        BluetoothGattCharacteristic ch = list.get(i);
        try {
            // 先读描述符，拿语义标签
            BluetoothGattDescriptor d = ch.getDescriptor(UUID_PRESENTATION_FORMAT);
            if (d != null) {
                try {
                    g.readDescriptor(d);
                } catch (Exception ignored) {
                }
            }
            g.readCharacteristic(ch);
        } catch (SecurityException e) {
            log("读取缺权限: " + e.getMessage());
            closeAndFinish(g, overall, cb);
        }
    }

    private void finalizeLevels(List<BluetoothGattCharacteristic> list, int overall,
                                Callback cb) {
        BatteryLevels b = new BatteryLevels();
        b.overall = overall;

        // 系统元数据语义最准，优先采用
        if (metaLevels != null && (metaLevels.left >= 0 || metaLevels.right >= 0
                || metaLevels.caseBox >= 0)) {
            b.left = metaLevels.left;
            b.right = metaLevels.right;
            b.caseBox = metaLevels.caseBox;
            b.fillFromOverall();
            b.source = "system-metadata";
            b.timestamp = System.currentTimeMillis();
            lastResult = b;
            log("采用系统元数据 L=" + b.left + " R=" + b.right + " Case=" + b.caseBox);
            return;
        }

        List<Integer> vals = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        for (BluetoothGattCharacteristic ch : list) {
            Integer v = decodeLevel(ch);
            vals.add(v == null ? -1 : v);
            labels.add(semantic(ch));
        }
        log("电量值: " + vals + " 语义: " + labels);

        // 按语义标签归类
        for (int i = 0; i < vals.size(); i++) {
            int v = vals.get(i);
            if (v < 0) continue;
            String l = labels.get(i).toLowerCase(Locale.ROOT);
            if (l.contains("left") || l.contains("l") || l.contains("左")) {
                b.left = v;
            } else if (l.contains("right") || l.contains("r") || l.contains("右")) {
                b.right = v;
            } else if (l.contains("case") || l.contains("box") || l.contains("charge")
                    || l.contains("盒")) {
                b.caseBox = v;
            }
        }

        // 没有语义标签时，按实例数量推断
        if (b.left < 0 && b.right < 0 && b.caseBox < 0 && vals.size() >= 3) {
            b.left = vals.get(0);
            b.right = vals.get(1);
            b.caseBox = vals.get(2);
            b.source = "gatt-3instances";
        } else if (b.anyKnown()) {
            b.source = "gatt-labeled";
        } else if (vals.size() == 1 && vals.get(0) >= 0) {
            b.left = b.right = vals.get(0);
            b.source = "gatt-single";
        } else {
            b.fillFromOverall();
            b.source = overall >= 0 ? "system-single" : "none";
        }

        b.timestamp = System.currentTimeMillis();
        lastResult = b;
        log("最终结果 L=" + b.left + " R=" + b.right + " Case=" + b.caseBox
                + " 来源=" + b.source);
    }

    private volatile BatteryLevels lastResult;

    /** 从 0x2904 Presentation Format 描述符取语义 */
    private String semantic(BluetoothGattCharacteristic ch) {
        BluetoothGattDescriptor d = ch.getDescriptor(UUID_PRESENTATION_FORMAT);
        if (d == null) return "";
        byte[] v = d.getValue();
        if (v == null || v.length < 7) return "";
        // 0x2904: format(1) exponent(1) unit(2) namespace(1) description(2)
        return asText(java.util.Arrays.copyOfRange(v, 5, v.length));
    }

    private String asText(byte[] b) {
        try {
            return new String(b, StandardCharsets.UTF_8).trim();
        } catch (Exception e) {
            return "";
        }
    }

    private Integer decodeLevel(BluetoothGattCharacteristic ch) {
        byte[] v = ch.getValue();
        if (v == null || v.length == 0) return null;
        return v[0] & 0xFF;
    }

    private int indexOf(List<BluetoothGattCharacteristic> list,
                        BluetoothGattCharacteristic ch) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i) == ch || list.get(i).getUuid().equals(ch.getUuid())
                    && list.get(i).getInstanceId() == ch.getInstanceId()) {
                return i;
            }
        }
        return -1;
    }

    private void closeAndFinish(BluetoothGatt g, int overall, Callback cb) {
        try {
            if (g != null) {
                g.disconnect();
                g.close();
            }
        } catch (Exception ignored) {
        }
        finish(overall, cb);
    }

    private void finish(int overall, Callback cb) {
        if (!done.compareAndSet(false, true)) return;
        BatteryLevels b = lastResult;
        if (b == null) {
            b = new BatteryLevels();
            b.overall = overall;
            b.fillFromOverall();
            b.source = overall >= 0 ? "system-single" : "none";
            b.timestamp = System.currentTimeMillis();
        }
        log("========== 探测结束 ==========");
        if (cb != null) cb.onResult(b, log.toString());
    }

    private static String hex(byte[] b) {
        if (b == null) return "";
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02X", x));
        return sb.toString();
    }
}
