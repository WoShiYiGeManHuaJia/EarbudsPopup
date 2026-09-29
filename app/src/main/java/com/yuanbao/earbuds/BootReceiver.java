package com.yuanbao.earbuds;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/** 开机 / 解锁 / 更新 / 低频自恢复：重新拉起弹窗前台服务。 */
public class BootReceiver extends BroadcastReceiver {
    public static final String ACTION_RECOVER_SERVICE = "com.yuanbao.earbuds.ACTION_RECOVER_SERVICE";

    @Override
    public void onReceive(Context c, Intent intent) {
        if (intent == null) return;
        String a = intent.getAction();
        boolean normalBoot = Intent.ACTION_BOOT_COMPLETED.equals(a)
                || Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(a)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)
                || Intent.ACTION_USER_UNLOCKED.equals(a)
                || ACTION_RECOVER_SERVICE.equals(a);
        if (!normalBoot) return;

        Prefs p = new Prefs(c);
        if (!p.autoStart() || !p.masterEnabled()) return;

        Intent s = new Intent(c, PopupService.class);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                c.startForegroundService(s);
            } else {
                c.startService(s);
            }
        } catch (Exception ignored) {
            // HyperOS 仍可能拦截后台启动；下一轮 Alarm / 用户启动会再次尝试。
        }
        KeepAliveController.arm(c);
    }
}
