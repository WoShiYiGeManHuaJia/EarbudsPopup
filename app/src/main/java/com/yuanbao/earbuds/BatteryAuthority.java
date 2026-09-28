package com.yuanbao.earbuds;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

/**
 * 电量“权威层”。原则：宁可显示 --%，也不把猜出来的数字冒充真实电量。
 * 优先级：Android Bluetooth metadata（左右/盒） > getBatteryLevel（整机） > 未知。
 * Bluetooth metadata 的 10/11/12 分别是左/右/盒电量，13/14/15 是充电状态。
 */
public final class BatteryAuthority {
    private static final int LEFT = 10;
    private static final int RIGHT = 11;
    private static final int CASE = 12;
    private static final int LEFT_CHARGING = 13;
    private static final int RIGHT_CHARGING = 14;
    private static final int CASE_CHARGING = 15;

    private BatteryAuthority() {}

    @SuppressLint("MissingPermission")
    public static BatteryLevels read(BluetoothDevice d) {
        BatteryLevels out = new BatteryLevels();
        if (d == null) return out;
        boolean metadataAny = false;
        try {
            Method getMetadata = d.getClass().getMethod("getMetadata", int.class);
            int l = readInt(getMetadata, d, LEFT);
            int r = readInt(getMetadata, d, RIGHT);
            int c = readInt(getMetadata, d, CASE);
            if (BatteryLevels.valid(l)) { out.left = l; metadataAny = true; }
            if (BatteryLevels.valid(r)) { out.right = r; metadataAny = true; }
            if (BatteryLevels.valid(c)) { out.caseBox = c; metadataAny = true; }
            out.leftCharging = readBool(getMetadata, d, LEFT_CHARGING);
            out.rightCharging = readBool(getMetadata, d, RIGHT_CHARGING);
            out.caseCharging = readBool(getMetadata, d, CASE_CHARGING);
            if (metadataAny) out.source = "android-metadata";
        } catch (Throwable ignored) {
            // SystemApi 对普通 App 可能被权限/隐藏 API 策略拦截；继续尝试公开/隐藏单值。
        }
        try {
            Method m = d.getClass().getMethod("getBatteryLevel");
            Object r = m.invoke(d);
            if (r instanceof Integer && BatteryLevels.valid((Integer) r)) {
                out.overall = (Integer) r;
                if (!metadataAny) out.source = "android-battery-level";
            }
        } catch (Throwable ignored) {}
        out.keepOverallOnly();
        out.sanitize();
        out.timestamp = System.currentTimeMillis();
        return out;
    }

    private static int readInt(Method m, BluetoothDevice d, int key) {
        try {
            Object raw = m.invoke(d, key);
            if (raw instanceof byte[]) {
                byte[] b = (byte[]) raw;
                if (b.length == 0) return -1;
                String text = new String(b, StandardCharsets.UTF_8).trim();
                try {
                    int n = Integer.parseInt(text);
                    return BatteryLevels.valid(n) ? n : -1;
                } catch (Exception ignored) {}
                int n = b[0] & 0xFF;
                return BatteryLevels.valid(n) ? n : -1;
            }
            if (raw instanceof String) {
                try { int n = Integer.parseInt((String) raw); return BatteryLevels.valid(n) ? n : -1; }
                catch (Exception ignored) {}
            }
        } catch (Throwable ignored) {}
        return -1;
    }

    private static boolean readBool(Method m, BluetoothDevice d, int key) {
        try {
            Object raw = m.invoke(d, key);
            if (raw instanceof byte[]) {
                byte[] b = (byte[]) raw;
                if (b.length == 0) return false;
                String s = new String(b, StandardCharsets.UTF_8).trim();
                return "1".equals(s) || "true".equalsIgnoreCase(s) || (b.length == 1 && b[0] == 1);
            }
            if (raw instanceof String) return "1".equals(raw) || "true".equalsIgnoreCase((String) raw);
        } catch (Throwable ignored) {}
        return false;
    }
}
