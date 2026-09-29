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

    private SharedPreferences sp;

    /** 尺寸默认值的版本标记；数值变更时递增，用于一次性迁移旧配置 */
    private static final int SIZE_SCHEMA = 3;

    public Prefs(Context c) {
        sp = c.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        migrateSizeDefaults();
    }

    /**
     * 迁移：把旧的尺寸默认值强制更新。
     *
     * 为什么必须有：SharedPreferences 的 getInt(key, 默认值) 只在 key
     * 【不存在】时才返回默认值。用户设备上早就存过 width=356，
     * 所以单纯改代码里的默认值对已安装用户完全无效 —— 这就是
     * 「我明明把宽度调大了，用户看到的却没变」的原因。
     * 这里按版本标记做一次性强制写入。
     */
    private void migrateSizeDefaults() {
        if (sp.getInt("size_schema", 0) >= SIZE_SCHEMA) return;
        sp.edit()
                .putInt("width", 390)          // 整体放大：占屏宽约 95%
                .putInt("radius", 28)
                .putFloat("img_ratio", 0.70f)  // 配合放大后的高度
                .putInt("size_schema", SIZE_SCHEMA)
                .apply();
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

    // ---------- 文字颜色（标题 / 电量 / 状态 三档分开） ----------
    // 之前只有一个统一文字色，无法像参考图那样给不同行配不同颜色。
    public String titleColor() {
        return sp.getString("color_title", "");
    }

    public void setTitleColor(String s) {
        sp.edit().putString("color_title", s == null ? "" : s).apply();
    }

    public String batteryColor() {
        return sp.getString("color_battery", "");
    }

    public void setBatteryColor(String s) {
        sp.edit().putString("color_battery", s == null ? "" : s).apply();
    }

    public String statusColor() {
        return sp.getString("color_status", "");
    }

    public void setStatusColor(String s) {
        sp.edit().putString("color_status", s == null ? "" : s).apply();
    }

    // ---------- 图片 / GIF 历史 ----------
    // 存最近用过的 12 个 URI，方便快速切回以前上传过的图。
    private static final String KEY_IMG_HISTORY = "img_history";
    private static final int HISTORY_MAX = 12;

    public java.util.List<String> imageHistory() {
        java.util.List<String> out = new java.util.ArrayList<>();
        String raw = sp.getString(KEY_IMG_HISTORY, "");
        if (raw.isEmpty()) return out;
        String[] parts = raw.split("\n");
        for (String u : parts) {
            if (u != null && !u.trim().isEmpty()) out.add(u.trim());
        }
        return out;
    }

    /** 记录一次使用；已存在则提到最前面，超出上限丢弃最旧的 */
    public void addImageHistory(String uri) {
        if (uri == null || uri.trim().isEmpty()) return;
        java.util.List<String> list = imageHistory();
        list.remove(uri);
        list.add(0, uri);
        while (list.size() > HISTORY_MAX) {
            list.remove(list.size() - 1);
        }
        sp.edit().putString(KEY_IMG_HISTORY, android.text.TextUtils.join("\n", list)).apply();
    }

    public void removeImageHistory(String uri) {
        java.util.List<String> list = imageHistory();
        list.remove(uri);
        sp.edit().putString(KEY_IMG_HISTORY, android.text.TextUtils.join("\n", list)).apply();
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
        return sp.getString("bg", "#1FFFFFFF");   // 液态玻璃半透白
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
        // 小米官方弹窗几乎占满屏宽（左右各留约 24dp）。
        // K70 Pro 屏幕 411dp，默认 360dp 约占 88%，更接近官方观感。
        return sp.getInt("width", 360);
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
        return sp.getInt("anim", 1);   // 默认底部上滑
    }

    public void setAnimStyle(int v) {
        sp.edit().putInt("anim", v).apply();
    }

    /** 0=顶部 1=居中 2=底部（距底部留 120dp） */
    /**
     * 连续垂直位置：0 = 贴顶，100 = 贴底，中间线性插值。
     * 用户要求能自由调整弹窗上下位置，而不是只有上/中/下三档。
     */
    public int verticalPos() {
        return sp.getInt("vpos", 100);
    }

    public void setVerticalPos(int v) {
        sp.edit().putInt("vpos", Math.max(0, Math.min(100, v))).apply();
    }

    /**
     * 是否用「私有特征值」推断充电盒电量。
     * 默认【关闭】—— 且已【证伪】：
     *   该私有特征恒为 32（0x20），多次探测都是 32，
     *   而用户实测充电盒实际是 90%。恒定不变的值不可能是电量。
     * 显示一个确定错误的数字比显示 --% 更糟，所以默认关闭。
     */
    public boolean privateCaseEnabled() {
        // 已证伪并【永久关闭】：该私有特征恒为 32（0x20），多次探测都是 32，
        // 而用户实测充电盒实际是 90%。恒定值不可能是电量。
        // 保留字段只为兼容旧配置，实际永远返回 false。
        sp.edit().putBoolean("private_case", false).apply();
        return false;
    }

    public void setPrivateCaseEnabled(boolean v) {
        sp.edit().putBoolean("private_case", v).apply();
    }

    /** 最近一次弹窗/探测用到的设备 MAC，供预览读取缓存电量 */
    public String lastAddress() {
        return sp.getString("last_addr", "");
    }

    public void setLastAddress(String a) {
        sp.edit().putString("last_addr", a == null ? "" : a).apply();
    }

    public int position() {
        return sp.getInt("pos", 2);   // 默认沉底（类小米官方弹窗）
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
        return sp.getString("auto_bg", "#1FFFFFFF");
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
        return sp.getFloat("img_ratio", 0.82f);
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


    // ---------- 后台功耗 & 通知 ----------
    /** 省电模式：熄屏时不弹窗、不唤醒，显著减少待机耗电 */
    public boolean powerSave() {
        return sp.getBoolean("power_save", true);
    }

    public void setPowerSave(boolean v) {
        sp.edit().putBoolean("power_save", v).apply();
    }

    /** 隐藏后台通知：渠道降到最低重要性，并引导用户在系统设置彻底关闭 */
    public boolean hideNotification() {
        return sp.getBoolean("hide_noti", false);
    }

    public void setHideNotification(boolean v) {
        sp.edit().putBoolean("hide_noti", v).apply();
    }

    /** 用户是否已在系统设置里关闭了本 App 的通知（App 侧记录，用于提示） */
    public boolean notiDismissed() {
        return sp.getBoolean("noti_dismissed", false);
    }

    public void setNotiDismissed(boolean v) {
        sp.edit().putBoolean("noti_dismissed", v).apply();
    }

    /** 是否在最近任务（多任务列表）里隐藏本 App */
    public boolean hideFromRecents() {
        return sp.getBoolean("hide_recents", true);
    }

    public void setHideFromRecents(boolean v) {
        sp.edit().putBoolean("hide_recents", v).apply();
    }

    // ---------- 设备显示名 ----------

    /** 用户为该设备指定的弹窗显示名；为空表示用系统蓝牙名 */
    public String deviceName(String addr) {
        if (addr == null || addr.isEmpty()) return "";
        return sp.getString("dname_" + addr, "");
    }

    public void setDeviceName(String addr, String name) {
        if (addr == null || addr.isEmpty()) return;
        sp.edit().putString("dname_" + addr, name == null ? "" : name).apply();
    }

    public void clearDeviceName(String addr) {
        if (addr == null || addr.isEmpty()) return;
        sp.edit().remove("dname_" + addr).apply();
    }


    // ---------- 图片变换（双指缩放 / 拖动 / 旋转） ----------

    /** 用户缩放倍数，1 = 原始 fitCenter 大小 */
    public float imageScale() {
        return sp.getFloat("img_scale", 1.0f);
    }

    public void setImageScale(float v) {
        sp.edit().putFloat("img_scale", Math.max(0.2f, Math.min(8f, v))).apply();
    }

    /** 用户拖动偏移，单位 dp */
    public float imageOffsetX() {
        return sp.getFloat("img_dx", 0f);
    }

    public void setImageOffsetX(float v) {
        sp.edit().putFloat("img_dx", v).apply();
    }

    public float imageOffsetY() {
        return sp.getFloat("img_dy", 0f);
    }

    public void setImageOffsetY(float v) {
        sp.edit().putFloat("img_dy", v).apply();
    }

    /** 旋转角度（度） */
    public float imageRotation() {
        return sp.getFloat("img_rot", 0f);
    }

    public void setImageRotation(float v) {
        sp.edit().putFloat("img_rot", v).apply();
    }

    /** 重置图片变换 */
    public void resetImageTransform() {
        sp.edit()
                .putFloat("img_scale", 1.0f)
                .putFloat("img_dx", 0f)
                .putFloat("img_dy", 0f)
                .putFloat("img_rot", 0f)
                .apply();
    }

    // ---------- 弹窗音效 ----------

    /** 弹窗时是否播放声音 */
    public boolean soundEnabled() {
        return sp.getBoolean("sound_on", false);
    }

    public void setSoundEnabled(boolean v) {
        sp.edit().putBoolean("sound_on", v).apply();
    }

    /** 自定义音效的 Uri 字符串；空表示用系统默认提示音 */
    public String soundUri() {
        return sp.getString("sound_uri", "");
    }

    public void setSoundUri(String s) {
        sp.edit().putString("sound_uri", s == null ? "" : s).apply();
    }

    /** 音量 0~1 */
    public float soundVolume() {
        return sp.getFloat("sound_vol", 0.8f);
    }

    public void setSoundVolume(float v) {
        sp.edit().putFloat("sound_vol", Math.max(0f, Math.min(1f, v))).apply();
    }

    // ---------- 本次补齐的设置项 ----------

    /**
     * 窗口级背景模糊（Android 12+ setBackgroundBlurRadius）。
     * 默认【关闭】：它会在整个窗口后面渲染一层磨砂，
     * 在 HyperOS 上会吞掉弹窗外部的点击，且圆角外可能显出暗块。
     * 需要柔化效果请用卡片自身的 LiveBlurView，那个不影响触摸。
     */
    public boolean windowBlur() {
        return sp.getBoolean("win_blur", false);
    }

    public void setWindowBlur(boolean v) {
        sp.edit().putBoolean("win_blur", v).apply();
    }

    /** 点击弹窗外部区域时立即关闭弹窗，而不是干等自动消失 */
    public boolean touchOutsideClose() {
        return sp.getBoolean("touch_outside_close", true);
    }

    public void setTouchOutsideClose(boolean v) {
        sp.edit().putBoolean("touch_outside_close", v).apply();
    }

    /** 横屏模式：0=照常弹大窗 1=横屏完全不弹 2=横屏显示迷你小窗 */
    public int landscapeMode() {
        return sp.getInt("landscape_mode", 2);
    }

    public void setLandscapeMode(int v) {
        sp.edit().putInt("landscape_mode", Math.max(0, Math.min(2, v))).apply();
    }

    /** 横屏时是否完全不弹（landscapeMode==1 的快捷读写） */
    public boolean landscapeSkip() {
        return landscapeMode() == 1;
    }

    public void setLandscapeSkip(boolean v) {
        if (v) setLandscapeMode(1);
        else if (landscapeMode() == 1) setLandscapeMode(2);
    }

    /**
     * 贴底时卡片距屏幕底部的距离（dp）。
     * 旧版写死 24dp，在手势导航的全面屏上看起来「离底边还很远」。
     */
    public int bottomPadDp() {
        return sp.getInt("bottom_pad_dp", 8);
    }

    public void setBottomPadDp(int v) {
        sp.edit().putInt("bottom_pad_dp", Math.max(0, Math.min(80, v))).apply();
    }

    // ---------- 设备白名单 ----------
    /** 返回 null 或空集表示“所有设备都弹” */
    public Set<String> allowedDevices() {
        // 必须返回拷贝：SharedPreferences.getStringSet 返回的是内部实例引用，
        // 调用方一旦修改它，再存回去时会出现「改了但不生效」的诡异现象。
        Set<String> v = sp.getStringSet("allowed", null);
        return v == null ? new HashSet<>() : new HashSet<>(v);
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
