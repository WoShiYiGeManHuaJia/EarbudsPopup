package com.yuanbao.earbuds;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 三方电量缓存：按蓝牙 MAC 地址存储。
 *
 * GATT 连接通常需要 1~5 秒，来不及在弹窗出现的瞬间拿到；
 * 所以第一次连接时先显示 --，探测结果存入缓存，
 * 之后每次连接就能立刻显示上次读到的值。
 */
public final class BatteryStore {

    private static final String SP = "earbuds_battery";
    private static final long TTL = 6 * 60 * 60 * 1000L; // 6 小时

    private final SharedPreferences sp;

    public BatteryStore(Context c) {
        sp = c.getSharedPreferences(SP, Context.MODE_PRIVATE);
    }

    private static String key(String addr, String f) {
        return addr + "_" + f;
    }

    public void save(String addr, BatteryLevels b) {
        if (addr == null || addr.isEmpty()) return;
        SharedPreferences.Editor e = sp.edit();
        e.putInt(key(addr, "l"), b.left);
        e.putInt(key(addr, "r"), b.right);
        e.putInt(key(addr, "c"), b.caseBox);
        e.putInt(key(addr, "o"), b.overall);
        e.putLong(key(addr, "t"), b.timestamp);
        e.putString(key(addr, "s"), b.source);
        e.apply();
    }

    /** 读缓存；超过 TTL 的整体值仍返回，但左右耳视为已过期 */
    public BatteryLevels load(String addr) {
        BatteryLevels b = new BatteryLevels();
        if (addr == null || addr.isEmpty()) return b;
        b.left = sp.getInt(key(addr, "l"), -1);
        b.right = sp.getInt(key(addr, "r"), -1);
        b.caseBox = sp.getInt(key(addr, "c"), -1);
        b.overall = sp.getInt(key(addr, "o"), -1);
        b.timestamp = sp.getLong(key(addr, "t"), 0L);
        b.source = sp.getString(key(addr, "s"), "cache");
        if (b.timestamp > 0 && System.currentTimeMillis() - b.timestamp > TTL) {
            // 太久没更新，左右耳不可信，只保留整机值
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
}
