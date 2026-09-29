package com.earpopupx;

import android.content.Context;
import android.net.Uri;

public final class AppPrefs {
    private static final String P = "earpopupx";
    private static final String KEY_MEDIA = "media";
    private static final String KEY_ENABLED = "enabled";

    public static String media(Context c) {
        return c.getSharedPreferences(P, 0).getString(KEY_MEDIA, null);
    }

    public static void setMedia(Context c, Uri u) {
        c.getSharedPreferences(P, 0).edit()
                .putString(KEY_MEDIA, u == null ? null : u.toString()).apply();
    }

    public static boolean enabled(Context c) {
        return c.getSharedPreferences(P, 0).getBoolean(KEY_ENABLED, true);
    }

    public static void setEnabled(Context c, boolean v) {
        c.getSharedPreferences(P, 0).edit().putBoolean(KEY_ENABLED, v).apply();
    }

    private AppPrefs() {
    }
}
