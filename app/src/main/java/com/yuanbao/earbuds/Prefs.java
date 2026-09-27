package com.yuanbao.earbuds;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashSet;
import java.util.Set;

/**
 * 极简配置存储：所有弹窗参数都放在一个 SharedPreferences 里。
 */
public class Prefs {

    private static final String NAME = "earbuds_popup";

    private final SharedPreferences sp;

    public Prefs(Context c) {
        sp = c.getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    // ---------- 开关 ----------
    public boolean masterEnabled() {
        return sp.getBoolean("master", true);
    }

    public void setMasterEnabled(boolean v) {
        sp.edit().putBoolean("master", v).apply();
    }

    public boolean wiredEnabled() {
        return sp.getBoolean("wired", true);
    }

    public void setWiredEnabled(boolean v) {
        sp.edit().putBoolean("wired", v).apply();
    }

    public boolean autoStart() {
        return sp.getBoolean("auto_start", true);
    }

    public void setAutoStart(boolean v) {
        sp.edit().putBoolean("auto_start", v).apply();
    }

    public boolean showBattery() {
        return sp.getBoolean("battery", true);
    }

    public void setShowBattery(boolean v) {
        sp.edit().putBoolean("battery", v).apply();
    }

    // ---------- 外观 ----------
    public String imageUri() {
        return sp.getString("image_uri", "");
    }

    public void setImageUri(String s) {
        sp.edit().putString("image_uri", s == null ? "" : s).apply();
    }

    public String titleText() {
        return sp.getString("title", "Buds 5 Pro 电竞版");
    }

    public void setTitleText(String s) {
        sp.edit().putString("title", s).apply();
    }

    public String subText() {
        return sp.getString("sub", "%s 已连接");
    }

    public void setSubText(String s) {
        sp.edit().putString("sub", s).apply();
    }

    public String bgColor() {
        return sp.getString("bg", "#F2141620");
    }

    public void setBgColor(String s) {
        sp.edit().putString("bg", s).apply();
    }

    public String textColor() {
        return sp.getString("text_color", "#FFFFFFFF");
    }

    /** 电竞风强调色（电量条 / 顶部高亮条） */
    public String accentColor() {
        return sp.getString("accent", "#FF00E5A0");
    }

    public void setAccentColor(String s) {
        sp.edit().putString("accent", s).apply();
    }

    public void setTextColor(String s) {
        sp.edit().putString("text_color", s).apply();
    }

    public int radiusDp() {
        return sp.getInt("radius", 28);
    }

    public void setRadiusDp(int v) {
        sp.edit().putInt("radius", v).apply();
    }

    public int widthDp() {
        return sp.getInt("width", 320);
    }

    public void setWidthDp(int v) {
        sp.edit().putInt("width", v).apply();
    }

    public int imageHeightDp() {
        return sp.getInt("img_h", 170);
    }

    public void setImageHeightDp(int v) {
        sp.edit().putInt("img_h", v).apply();
    }

    /** 0=居中缩放淡入 1=底部上滑 2=顶部下滑 */
    public int animStyle() {
        return sp.getInt("anim", 0);
    }

    public void setAnimStyle(int v) {
        sp.edit().putInt("anim", v).apply();
    }

    /** 0=顶部 1=居中 2=底部（距底部留 120dp） */
    public int position() {
        return sp.getInt("pos", 1);
    }

    public void setPosition(int v) {
        sp.edit().putInt("pos", v).apply();
    }

    public int durationMs() {
        return sp.getInt("duration", 4500);
    }

    public void setDurationMs(int v) {
        sp.edit().putInt("duration", v).apply();
    }

    // ---------- 设备白名单 ----------
    /** 返回 null 或空集表示“所有设备都弹” */
    public Set<String> allowedDevices() {
        return sp.getStringSet("allowed", new HashSet<>());
    }

    public void setAllowedDevices(Set<String> s) {
        sp.edit().putStringSet("allowed", new HashSet<>(s)).apply();
    }

    public boolean isAllowed(String address) {
        if (address == null) return true;
        Set<String> s = allowedDevices();
        return s.isEmpty() || s.contains(address);
    }
}
