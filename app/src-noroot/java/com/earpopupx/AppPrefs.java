package com.earpopupx;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

/** 全部外观 / 位置 / 时长设置。所有键都有默认值，首次安装开箱即用。 */
public final class AppPrefs {
    private static final String P = "earpopupx";

    private static final String KEY_MEDIA     = "media";
    private static final String KEY_ENABLED   = "enabled";
    private static final String KEY_WIDTH     = "width_pct";     // 弹窗宽度百分比
    private static final String KEY_MEDIA_H   = "media_h_dp";    // 图片高度 dp
    private static final String KEY_DURATION  = "duration_ms";   // 自动消失时长
    private static final String KEY_DIM       = "dim_pct";       // 背景压暗百分比
    private static final String KEY_OFFSET    = "offset_dp";     // 垂直偏移 dp（负=上移）
    private static final String KEY_CORNER    = "corner_dp";     // 圆角 dp
    private static final String KEY_BLUR      = "blur";          // 是否启用系统实时模糊

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(P, Context.MODE_PRIVATE);
    }

    public static String media(Context c) { return sp(c).getString(KEY_MEDIA, null); }
    public static void setMedia(Context c, Uri u) {
        sp(c).edit().putString(KEY_MEDIA, u == null ? null : u.toString()).apply();
    }

    public static boolean enabled(Context c) { return sp(c).getBoolean(KEY_ENABLED, true); }
    public static void setEnabled(Context c, boolean v) { sp(c).edit().putBoolean(KEY_ENABLED, v).apply(); }

    public static int widthPct(Context c)    { return sp(c).getInt(KEY_WIDTH, 92); }
    public static void setWidthPct(Context c, int v) { sp(c).edit().putInt(KEY_WIDTH, v).apply(); }

    public static int mediaHDp(Context c)    { return sp(c).getInt(KEY_MEDIA_H, 240); }
    public static void setMediaHDp(Context c, int v) { sp(c).edit().putInt(KEY_MEDIA_H, v).apply(); }

    public static int durationMs(Context c)  { return sp(c).getInt(KEY_DURATION, 6500); }
    public static void setDurationMs(Context c, int v) { sp(c).edit().putInt(KEY_DURATION, v).apply(); }

    public static int dimPct(Context c)      { return sp(c).getInt(KEY_DIM, 30); }
    public static void setDimPct(Context c, int v) { sp(c).edit().putInt(KEY_DIM, v).apply(); }

    public static int offsetDp(Context c)    { return sp(c).getInt(KEY_OFFSET, 0); }
    public static void setOffsetDp(Context c, int v) { sp(c).edit().putInt(KEY_OFFSET, v).apply(); }

    public static int cornerDp(Context c)    { return sp(c).getInt(KEY_CORNER, 30); }
    public static void setCornerDp(Context c, int v) { sp(c).edit().putInt(KEY_CORNER, v).apply(); }

    public static boolean blur(Context c)    { return sp(c).getBoolean(KEY_BLUR, true); }
    public static void setBlur(Context c, boolean v) { sp(c).edit().putBoolean(KEY_BLUR, v).apply(); }

    private AppPrefs() {}
}
