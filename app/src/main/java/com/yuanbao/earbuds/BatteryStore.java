package com.yuanbao.earbuds;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 三方电量缓存：按蓝牙 MAC 地址存储。
 *
 * GATT 连接通常要 1~5 秒，来不及在弹窗出现的瞬间拿到；
 * 所以第一次连接时先显示缓存值，探测结果回来后写入缓存，
 * 下一次连接就能立刻显示。
 *
 * SCHEMA 版本：电量判定规则改过（0 不再当有效值），
 * 旧缓存里可能存着错误的 0，版本一变就整体作废，逼系统重新探测。
 */
public final class BatteryStore {

    private static final String SP = "earbuds_battery";
    /** 判定规则变更时递增，旧数据全部作废 */
    private static final String KEY_SCHEMA = "__schema";
    private static final int SCHEMA = 2;

    private static final long TTL = 6 * 60 * 60 * 1000L; // 6 小时

    private final SharedPreferences sp;

    public BatteryStore(Context c) {
        sp = c.getSharedPreferences(SP, Context.MODE_PRIVATE);
        int cur = sp.getInt(KEY_SCHEMA, 0);
        if (cur != SCHEMA) {
            // 规则变了，清空所有旧缓存
            sp.edit().clear().putInt(KEY_SCHEMA, SCHEMA).apply();
        }
    }

    private static String key(String addr, String f) {
        return addr + "_" + f;
    }

    public void save(String addr, BatteryLevels b) {
        if (addr == null || addr.isEmpty() || b == null) return;
        b.sanitize();
        // 全是未知就不写，免得把 -- 固化进缓存
        if (!b.anyKnown()) return;
        SharedPreferences.Editor e = sp.edit();
        e.putInt(key(addr, "l"), b.left);
        e.putInt(key(addr, "r"), b.right);
        e.putInt(key(addr, "c"), b.caseBox);
        e.putInt(key(addr, "o"), b.overall);
        e.putLong(key(addr, "t"), b.timestamp);
        e.putString(key(addr, "s"), b.source);
        e.apply();
    }

    /** 读缓存；过期返回空对象。所有值都经过 sanitize，不会有 0 */
    public BatteryLevels load(String addr) {
        BatteryLevels b = new BatteryLevels();
        if (addr == null || addr.isEmpty()) return b;
        b.left = BatteryLevels.norm(sp.getInt(key(addr, "l"), -1));
        b.right = BatteryLevels.norm(sp.getInt(key(addr, "r"), -1));
        b.caseBox = BatteryLevels.norm(sp.getInt(key(addr, "c"), -1));
        b.overall = BatteryLevels.norm(sp.getInt(key(addr, "o"), -1));
        b.timestamp = sp.getLong(key(addr, "t"), 0L);
        b.source = sp.getString(key(addr, "s"), "cache");

        if (b.timestamp <= 0L) return new BatteryLevels();
        if (System.currentTimeMillis() - b.timestamp > TTL) {
            // 太久没更新：整机值保留，左右耳与充电盒视为过期
            b.left = -1;
            b.right = -1;
            b.caseBox = -1;
        }
        return b;
    }

    public void clear(String addr) {
        if (addr == null || addr.isEmpty()) return;
        SharedPreferences.Editor e = sp.edit();
        for (String f : new String[]{"l", "r", "c", "o", "t", "s"}) {
            e.remove(key(addr, f));
        }
        e.apply();
    }

    /** 清空全部缓存（设置页「重置电量缓存」用） */
    public void clearAll() {
        sp.edit().clear().putInt(KEY_SCHEMA, SCHEMA).apply();
    }
}
