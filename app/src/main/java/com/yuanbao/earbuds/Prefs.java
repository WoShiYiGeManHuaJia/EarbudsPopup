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
        return sp.getInt("radius", 26);
    }

    public void setRadiusDp(int v) {
        sp.edit().putInt("radius", v).apply();
    }

    public int widthDp() {
        return sp.getInt("width", 296);
    }

    public void setWidthDp(int v) {
        sp.edit().putInt("width", v).apply();
    }

    public int imageHeightDp() {
        return sp.getInt("img_h", 165);
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



    // ---------- 自动取色 ----------
    /** 是否根据上传图片自动决定卡片底色 */
    public boolean autoColor() {
        return sp.getBoolean("auto_color", true);
    }

    public void setAutoColor(boolean v) {
        sp.edit().putBoolean("auto_color", v).apply();
    }

    /** 从图片提取到的底色（Palette 生成后缓存，避免每次弹窗都算） */
    public String autoBgColor() {
        return sp.getString("auto_bg", "#F2141620");
    }

    public void setAutoBgColor(String c) {
        sp.edit().putString("auto_bg", c).apply();
    }

    /** 提取到的强调色 */
    public String autoAccentColor() {
        return sp.getString("auto_accent", "#FF00E5A0");
    }

    public void setAutoAccentColor(String c) {
        sp.edit().putString("auto_accent", c).apply();
    }

    /** 图片区高度 = 弹窗宽度 × 该比例（华为规范约 0.70，整体宽高比 1:1.24） */
    public float imageRatio() {
        return sp.getFloat("img_ratio", 0.70f);
    }

    public void setImageRatio(float v) {
        sp.edit().putFloat("img_ratio", v).apply();
    }

    /** 是否显示充电盒电量圆环（拿不到时会自动隐藏） */
    public boolean showCaseBattery() {
        return sp.getBoolean("show_case", true);
    }

    public void setShowCaseBattery(boolean v) {
        sp.edit().putBoolean("show_case", v).apply();
    }

    // ---------- 系统级弹窗引擎 ----------
    /** 0=系统级Activity 1=悬浮窗 2=智能降级 */
    public int engine() {
        return sp.getInt("engine", 2);
    }

    public void setEngine(int v) {
        sp.edit().putInt("engine", v).apply();
    }

    /** 背景压暗程度 0~1 */
    public float dimAmount() {
        return sp.getFloat("dim", 0.30f);
    }

    public void setDimAmount(float v) {
        sp.edit().putFloat("dim", v).apply();
    }

    /** 窗口背景模糊半径 dp，0=关闭 */
    public int blurRadius() {
        return sp.getInt("blur", 24);
    }

    public void setBlurRadius(int v) {
        sp.edit().putInt("blur", v).apply();
    }

    /** 锁屏之上也弹（需系统「锁屏显示」权限） */
    public boolean showOnLock() {
        return sp.getBoolean("lock", true);
    }

    public void setShowOnLock(boolean v) {
        sp.edit().putBoolean("lock", v).apply();
    }

    /** 不夺取焦点：打游戏/输入时弹窗不打断 */
    public boolean notFocusable() {
        return sp.getBoolean("no_focus", false);
    }

    public void setNotFocusable(boolean v) {
        sp.edit().putBoolean("no_focus", v).apply();
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
