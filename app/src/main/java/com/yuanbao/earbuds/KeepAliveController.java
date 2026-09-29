package com.yuanbao.earbuds;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.provider.Settings;
import android.net.Uri;

/**
 * 保活增强层：不承诺“永不被杀”，而是把 Android/HyperOS 允许的几条恢复链全部接上。
 * 1) 电池优化白名单引导
 * 2) AlarmManager 低频自恢复
 * 3) AppOps 后台运行由 ADB 脚本辅助
 * 4) Boot / package-replaced / user-unlocked 恢复由 BootReceiver 负责
 */
public final class KeepAliveController {
    private static final int REQUEST_CODE = 9147;
    private static final long RECOVER_INTERVAL_MS = 15 * 60 * 1000L;

    private KeepAliveController() {}

    public static void arm(Context context) {
        try {
            AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            Intent i = new Intent(context, BootReceiver.class)
                    .setAction(BootReceiver.ACTION_RECOVER_SERVICE)
                    .setPackage(context.getPackageName());
            PendingIntent pi = PendingIntent.getBroadcast(context, REQUEST_CODE, i,
                    PendingIntent.FLAG_UPDATE_CURRENT |
                            (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
            long when = System.currentTimeMillis() + RECOVER_INTERVAL_MS;
            if (Build.VERSION.SDK_INT >= 23) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, pi);
            } else {
                am.set(AlarmManager.RTC_WAKEUP, when, pi);
            }
        } catch (Throwable ignored) {}
    }

    public static void cancel(Context context) {
        try {
            AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            Intent i = new Intent(context, BootReceiver.class)
                    .setAction(BootReceiver.ACTION_RECOVER_SERVICE)
                    .setPackage(context.getPackageName());
            PendingIntent pi = PendingIntent.getBroadcast(context, REQUEST_CODE, i,
                    PendingIntent.FLAG_UPDATE_CURRENT |
                            (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
            if (am != null) am.cancel(pi);
        } catch (Throwable ignored) {}
    }

    /** 打开系统电池优化设置；真正加入白名单仍由用户确认。 */
    public static void requestIgnoreBatteryOptimization(Context context) {
        try {
            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + context.getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(i);
        } catch (Throwable ignored) {
            try {
                Intent i = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(i);
            } catch (Throwable ignored2) {}
        }
    }
}
