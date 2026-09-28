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

    /** 私有特征 NOTIFY 推送上来的数据包（hex），用于找左右耳/充电盒 */
    private final java.util.List<String> notifyPackets =
            java.util.Collections.synchronizedList(new java.util.ArrayList<String>());

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
    /** 保存每个特征读到的真实值，避免后续被覆盖 */
    private final java.util.Map<BluetoothGattCharacteristic, Integer> readValues =
            new java.util.HashMap<>();

    public BatteryProbe(Context c) {
        ctx = c.getApplicationContext();
    }

    /**
     * 轻量读取：只连 GATT 读标准 Battery Level（0x2A19），不做全量服务树 dump。
     * 供每次耳机连接时自动调用——比完整探测快得多（通常 1~3 秒），
     * 拿到值就写缓存，下次弹窗立刻显示。
     * 回调一定会被调用（成功/失败/超时都会），调用方不必担心卡死。
     */
    @SuppressLint("MissingPermission")
    public void quickRead(String address, BluetoothDevice device,
                          java.util.function.Consumer<Integer> cb) {
        BluetoothDevice target = device;
        if (target == null) {
            BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter();
            if (a == null) {
                if (cb != null) cb.accept(-1);
                return;
            }
            try {
                target = a.getRemoteDevice(address);
            } catch (Exception e) {
                if (cb != null) cb.accept(-1);
                return;
            }
        }

        final boolean[] doneOnce = {false};
        final BluetoothDevice t = target;

        BluetoothGattCallback gc = new BluetoothGattCallback() {
            @Override
            public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    try {
                        g.discoverServices();
                    } catch (SecurityException e) {
                        finishQuick(g, doneOnce, cb, -1);
                    }
                } else {
                    finishQuick(g, doneOnce, cb, -1);
                }
            }

            @Override
            public void onServicesDiscovered(BluetoothGatt g, int status) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    finishQuick(g, doneOnce, cb, -1);
                    return;
                }
                BluetoothGattCharacteristic ch = null;
                for (BluetoothGattService svc : g.getServices()) {
                    if (UUID_BATTERY_SERVICE.equals(svc.getUuid())) {
                        for (BluetoothGattCharacteristic c : svc.getCharacteristics()) {
                            if (UUID_BATTERY_LEVEL.equals(c.getUuid())) {
                                ch = c;
                                break;
                            }
                        }
                    }
                    if (ch != null) break;
                }
                if (ch == null) {
                    finishQuick(g, doneOnce, cb, -1);
                    return;
                }
                final BluetoothGattCharacteristic targetCh = ch;

                //
                // 【新增】先订阅通知（第三方弹窗 App 的标准做法）
                //
                // 标准 Battery Service (0x180F) 的 Battery Level (0x2A19)
                // 支持 Notify。绝大多数耳机连上后【会主动推一次】电量，
                // 之后电量变化也会推。订阅后 1 秒内就能拿到，不必等
                // 主动 read 的完整往返。
                //
                // 之前只做 readCharacteristic，没开 CCCD，所以只能靠
                // 「手动点探测」——这就是本 App 比别家麻烦的根因。
                //
                try {
                    g.setCharacteristicNotification(targetCh, true);
                    BluetoothGattDescriptor cccd = targetCh.getDescriptor(
                            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"));
                    if (cccd != null) {
                        cccd.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                        g.writeDescriptor(cccd);
                    }
                } catch (Throwable ignored) {
                }

                try {
                    boolean ok = g.readCharacteristic(targetCh);
                    if (!ok) finishQuick(g, doneOnce, cb, -1);
                } catch (SecurityException e) {
                    finishQuick(g, doneOnce, cb, -1);
                }
            }

            @Override
            public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic ch,
                                             int status) {
                // 绝不能 ch.setValue()，会把刚读到的真实值覆盖掉
                Integer v = decodeLevel(ch);
                finishQuick(g, doneOnce, cb, v == null ? -1 : v);
            }

            @Override
            public void onCharacteristicChanged(BluetoothGatt g,
                                                BluetoothGattCharacteristic ch) {
                // 耳机主动推送的电量：这是最实时、也是第三方 App 主要依赖的通道
                if (!UUID_BATTERY_LEVEL.equals(ch.getUuid())) return;
                Integer v = decodeLevel(ch);
                finishQuick(g, doneOnce, cb, v == null ? -1 : v);
            }
        };

        BluetoothGatt g;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                g = t.connectGatt(ctx, false, gc, BluetoothDevice.TRANSPORT_LE);
            } else {
                g = t.connectGatt(ctx, false, gc);
            }
        } catch (SecurityException e) {
            if (cb != null) cb.accept(-1);
            return;
        }
        if (g == null) {
            if (cb != null) cb.accept(-1);
            return;
        }
        // 超时兜底：12 秒还没结果就断掉并回调 -1
        new Handler(Looper.getMainLooper()).postDelayed(
                () -> finishQuick(g, doneOnce, cb, -1), 12000);
    }

    private void finishQuick(BluetoothGatt g, boolean[] doneOnce,
                             java.util.function.Consumer<Integer> cb, int value) {
        if (doneOnce[0]) return;
        doneOnce[0] = true;
        try {
            g.disconnect();
            g.close();
        } catch (Exception ignored) {
        }
        if (cb != null) cb.accept(value);
    }

    private void log(String s) {
        log.append(s).append('\n');
    }

    /** 取当前累积日志（即使探测还没结束也能取，用于超时兜底） */
    public String currentLog() {
        return log.toString();
    }

    @SuppressLint("MissingPermission")
    public void probe(String address, BluetoothDevice device, Callback cb) {
        // 记录地址，供预览读取缓存电量
        try {
            new Prefs(ctx).setLastAddress(address);
        } catch (Throwable ignored) {
        }
        log("========== 电量探测 ==========");
        log("地址: " + address);
        log("设备名: " + safeName(device));

        // C. 先读系统隐藏单值作为对照
        int sys = readSystemBattery(device);
        // 全文搜索：按 MAC 定位命中的往往是 BLE 扫描统计，不含电量
        try {
            if (ShizukuHelper.hasPermission()) {
                String g1 = BatterySysQuery.grepBattery("bluetooth_manager");
                log("---- battery 关键字(bluetooth_manager) ----");
                log(g1.isEmpty() ? "(无命中)" : g1);
                String g2 = BatterySysQuery.grepBattery("bluetooth");
                log("---- battery 关键字(bluetooth) ----");
                log(g2.isEmpty() ? "(无命中)" : g2);
            }
        } catch (Throwable t) {
            log("全文搜索失败: " + t.getMessage());
        }
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
                // 注意：这里绝不能调用 ch.setValue()，否则会把刚读到的真实值覆盖掉。
                // 之前多写了 ch.setValue(new byte[]{0})，导致读到 100 却记成 0。
                Integer v = decodeLevel(ch);
                readValues.put(ch, v);
                log("读取 " + ch.getUuid() + " = " + (v == null ? "null" : v)
                        + " status=" + status
                        + (v != null ? "  raw=" + hex(ch.getValue()) : ""));
                int idx = indexOf(levels, ch);
                if (idx >= 0) {
                    // 标准电量特征：继续读下一个
                    readNext(g, levels, idx + 1, overall, cb);
                }
                // 关键修复：私有特征的读取由 dumpNext() 自己的 postDelayed 链推进。
                // 之前这里写的是 else { closeAndFinish(...) }，
                // 读到的第一个私有特征就会把整条链掐断，
                // finalizeLevels() 永远执行不到 —— 这就是「日志里有 32，
                // 但最终结果却是 Case=--% 且没有任何推断日志」的原因。
                // 现在不对私有特征做任何流程控制，交给 dumpNext 收尾。
            }

            @Override
            public void onCharacteristicChanged(BluetoothGatt g,
                                                BluetoothGattCharacteristic ch) {
                // 耳机主动推送的数据包 —— 充电盒电量很可能只在推送里出现，
                // 单纯 read 是拿不到的（之前就是只读不订阅，所以 Case 永远 --%）
                byte[] v = ch.getValue();
                if (v != null && v.length > 0) {
                    String hex = toHex(v);
                    notifyPackets.add(hex);
                    log("NOTIFY " + ch.getUuid().toString().substring(0, 8)
                            + " len=" + v.length + " hex=" + hex);
                }
            }

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

        // 总超时 28 秒（订阅 + 写命令试探 + 等待推送）
        main.postDelayed(() -> {
            if (!done.get()) {
                log("探测总超时(28s)，结束");
                closeAndFinish(gatt, overall, cb);
            }
        }, 28000);
    }

    /**
     * 遍历所有私有（非 SIG 标准）服务里具备 READ 属性的特征，逐个读取并 dump 原始字节。
     * 目的是找到左/右/盒电量到底藏在哪个特征的第几个字节。
     */
    @SuppressLint("MissingPermission")
    private void dumpPrivateChars(BluetoothGatt g, int overall,
                                  List<BluetoothGattCharacteristic> levels, Callback cb) {
        List<BluetoothGattCharacteristic> readable = new ArrayList<>();
        for (BluetoothGattService svc : g.getServices()) {
            String u = svc.getUuid().toString().toLowerCase(Locale.ROOT);
            // 只关心私有服务：标准服务的 UUID 都是 0000xxxx-0000-1000-8000-00805f9b34fb
            boolean isSig = u.startsWith("0000") && u.endsWith("-0000-1000-8000-00805f9b34fb");
            if (isSig) continue;
            for (BluetoothGattCharacteristic ch : svc.getCharacteristics()) {
                if ((ch.getProperties() & BluetoothGattCharacteristic.PROPERTY_READ) != 0) {
                    readable.add(ch);
                }
            }
        }
        log("私有可读特征数 = " + readable.size());

        // 除了读，还要订阅 NOTIFY：
        // Airoha 私有服务下 CHAR-0A/1A 带 NOTIFY 属性，左右耳与充电盒电量
        // 大概率是耳机【主动推送】的，只读一次根本拿不到（这就是 Case 一直 --% 的原因）。
        subscribeNotify(g, readable, overall, levels, cb, () ->
                dumpNext(g, readable, 0, overall, levels, cb));
    }

    /**
     * 对所有带 NOTIFY 的私有特征开启通知，等待一段时间后继续。
     * 期间收到的包会由 onCharacteristicChanged 记录。
     */
    @SuppressLint("MissingPermission")
    private void subscribeNotify(BluetoothGatt g,
                                 List<BluetoothGattCharacteristic> chars,
                                 int overall,
                                 List<BluetoothGattCharacteristic> levels,
                                 Callback cb, Runnable then) {
        List<BluetoothGattCharacteristic> notifiable = new ArrayList<>();
        for (BluetoothGattService svc : g.getServices()) {
            String u = svc.getUuid().toString().toLowerCase(Locale.ROOT);
            boolean isSig = u.startsWith("0000") && u.endsWith("-0000-1000-8000-00805f9b34fb");
            if (isSig) continue;
            for (BluetoothGattCharacteristic ch : svc.getCharacteristics()) {
                if ((ch.getProperties() & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) {
                    notifiable.add(ch);
                }
            }
        }
        log("私有可订阅(NOTIFY)特征数 = " + notifiable.size());
        for (BluetoothGattCharacteristic ch : notifiable) {
            try {
                g.setCharacteristicNotification(ch, true);
                BluetoothGattDescriptor ccc =
                        ch.getDescriptor(java.util.UUID.fromString(
                                "00002902-0000-1000-8000-00805f9b34fb"));
                if (ccc != null) {
                    ccc.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                    g.writeDescriptor(ccc);
                }
                log("已订阅 " + ch.getUuid().toString().substring(0, 8));
            } catch (Exception e) {
                log("订阅失败 " + ch.getUuid().toString().substring(0, 8) + " : " + e.getMessage());
            }
        }
        // 给耳机 6 秒时间推送数据
        main.postDelayed(() -> {
            // 不再向未知厂商私有特征盲写 opcode —— 未知写命令可能改变耳机状态。
            // 仅依赖标准 GATT、系统 metadata 和厂商主动 NOTIFY。
            main.postDelayed(then, 2500);
        }, notifiable.isEmpty() ? 0 : 6000);
    }

    /**
     * 往可写特征发送候选查询命令，试图让耳机回复含电量的状态包。
     * 协议未公开，所以这里用一组常见的 opcode 逐个试探，
     * 每条命令后的 NOTIFY 回应都会被 onCharacteristicChanged 记录。
     */
    @SuppressLint("MissingPermission")
    private void writeQueries(BluetoothGatt g, int overall,
                              List<BluetoothGattCharacteristic> levels, Callback cb) {
        List<BluetoothGattCharacteristic> writable = new ArrayList<>();
        for (BluetoothGattService svc : g.getServices()) {
            String u = svc.getUuid().toString().toLowerCase(Locale.ROOT);
            boolean isSig = u.startsWith("0000") && u.endsWith("-0000-1000-8000-00805f9b34fb");
            if (isSig) continue;
            for (BluetoothGattCharacteristic ch : svc.getCharacteristics()) {
                int pr = ch.getProperties();
                if ((pr & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0
                        || (pr & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) {
                    writable.add(ch);
                }
            }
        }
        log("私有可写特征数 = " + writable.size());
        if (writable.isEmpty()) return;

        // 常见查询 opcode（协议未公开，逐个试探）
        byte[][] cmds = {
                {0x00}, {0x03}, {0x04}, {0x05}, {0x06},
                {0x0A}, {(byte) 0xAA}, {(byte) 0xAB},
                {0x05, 0x5A, 0x00, 0x00, 0x00},
                {(byte) 0xAA, 0x00, 0x01, 0x01},
        };
        int delay = 0;
        for (byte[] cmd : cmds) {
            final byte[] c = cmd;
            main.postDelayed(() -> {
                for (BluetoothGattCharacteristic ch : writable) {
                    try {
                        ch.setValue(c);
                        ch.setWriteType(
                                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
                        g.writeCharacteristic(ch);
                        log("写入查询 " + toHex(c) + " -> "
                                + ch.getUuid().toString().substring(0, 8));
                    } catch (Exception e) {
                        log("写入失败: " + e.getMessage());
                    }
                }
            }, delay);
            delay += 600;
        }
    }

    @SuppressLint("MissingPermission")
    private void dumpNext(BluetoothGatt g, List<BluetoothGattCharacteristic> list, int i,
                          int overall, List<BluetoothGattCharacteristic> levels, Callback cb) {
        if (i >= list.size()) {
            finalizeLevels(levels, overall, cb);
            closeAndFinish(g, overall, cb);
            return;
        }
        BluetoothGattCharacteristic ch = list.get(i);
        final int next = i + 1;
        try {
            g.readCharacteristic(ch);
            // 读到的值在 onCharacteristicRead 里统一 log（含 raw hex）
            main.postDelayed(() -> dumpNext(g, list, next, overall, levels, cb), 350);
        } catch (SecurityException e) {
            log("读私有特征缺权限: " + e.getMessage());
            finalizeLevels(levels, overall, cb);
            closeAndFinish(g, overall, cb);
        }
    }

    @SuppressLint("MissingPermission")
    private void readNext(BluetoothGatt g, List<BluetoothGattCharacteristic> list,
                          int i, int overall, Callback cb) {
        if (i >= list.size()) {
            // 标准电量读完后，再把私有服务里所有可读特征读一遍并 dump。
            // Redmi Buds 5 Pro 用的是 Airoha 私有服务（UUID 前 4 字节是 ASCII "PRIM"），
            // 左右耳电量很可能就在里面；标准 0x180F 只有单实例，拿不到分离值。
            dumpPrivateChars(g, overall, list, cb);
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

    /**
     * 从 NOTIFY 推送的数据包里找电量。
     * 思路：电量字节通常是连续的 1~100 三个值（L / R / Case）。
     * 这里把所有包里的连续三元组候选都记下来，便于人工对照确认。
     */
    private void tryParseNotify(BatteryLevels b) {
        if (notifyPackets.isEmpty()) {
            log("未收到任何 NOTIFY 推送包");
            return;
        }
        log("收到 NOTIFY 包 " + notifyPackets.size() + " 条，逐条列出：");
        for (String hex : notifyPackets) log("  " + hex);

        // 找连续三个落在 1..100 的字节
        for (String hex : notifyPackets) {
            String[] parts = hex.split(" ");
            byte[] v = new byte[parts.length];
            for (int i = 0; i < parts.length; i++) {
                try {
                    v[i] = (byte) Integer.parseInt(parts[i], 16);
                } catch (Exception e) {
                    v[i] = 0;
                }
            }
            for (int i = 0; i + 2 < v.length; i++) {
                int a = v[i] & 0xFF, c = v[i + 1] & 0xFF, d = v[i + 2] & 0xFF;
                if (a >= 1 && a <= 100 && c >= 1 && c <= 100 && d >= 1 && d <= 100) {
                    log("候选三元组 @字节" + i + ": L=" + a + " R=" + c + " Case=" + d);
                    if (!BatteryLevels.valid(b.left)) b.left = a;
                    if (!BatteryLevels.valid(b.right)) b.right = c;
                    if (!BatteryLevels.valid(b.caseBox)) {
                        b.caseBox = d;
                        b.source = "notify-triplet";
                    }
                    return;
                }
            }
        }
        log("推送包中未找到连续三元组（可能不是电量数据或格式不同）");
    }

    private void finalizeLevels(List<BluetoothGattCharacteristic> list, int overall,
                                Callback cb) {
        BatteryLevels b = new BatteryLevels();
        b.overall = overall;

        // 系统元数据语义最准，优先采用
        if (metaLevels != null && (BatteryLevels.valid(metaLevels.left)
                || BatteryLevels.valid(metaLevels.right)
                || BatteryLevels.valid(metaLevels.caseBox))) {
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
            Integer v = readValues.get(ch);
            if (v == null) v = decodeLevel(ch);
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
            // 多实例但没有 0x2904 语义描述时，不猜实例顺序。
            // 不同厂商/固件的实例顺序没有统一保证。
            b.source = "gatt-3instances-unlabeled";
        } else if (BatteryLevels.valid(b.left) || BatteryLevels.valid(b.right)
                || BatteryLevels.valid(b.caseBox)) {
            // 注意：这里【不能】用 b.anyKnown()。
            // anyKnown() 把 overall 也算进去，而 overall 在开头就被赋成 100，
            // 于是这个分支永远为真，下面的 gatt-single 分支永远执行不到，
            // left / right 一直是 -1 —— 这就是「L:--% R:--% 但整机明明是 100」的原因。
            b.source = "gatt-labeled";
        } else if (vals.size() >= 1 && vals.get(0) >= 0) {
            // 只有标准电量实例：这是整机值，不是左右耳分别的读数
            int v = vals.get(0);
            b.overall = v;
            // 0 通常是「未上报」而不是真的没电，按未知处理
            b.left = v > 0 ? v : -1;
            b.right = v > 0 ? v : -1;
            b.source = "gatt-single";
        } else {
            b.fillFromOverall();
            b.source = overall >= 0 ? "system-single" : "none";
        }

        // 统一兜底：无论走哪个分支，左右耳缺失且整机值有效时用整机值补齐
        b.fillFromOverall();

        b.timestamp = System.currentTimeMillis();
        lastResult = b;
        // 严格模式：不再把「任意私有特征里的 1~100」猜成充电盒电量。
        // 充电盒只有在系统 metadata、明确语义的 GATT 实例或已确认的厂商协议中出现时才显示。

        // 标准/私有读取都没拿到完整三值时，再从 NOTIFY 推送包里试一次
        if (!BatteryLevels.valid(b.caseBox) || !BatteryLevels.valid(b.left)
                || !BatteryLevels.valid(b.right)) {
            tryParseNotify(b);
        }
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

    private static String toHex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02X", x & 0xFF)).append(" ");
        return sb.toString().trim();
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
