package com.yuanbao.earbuds;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/**
 * 开机 / 应用更新后拉起弹窗服务。
 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context c, Intent intent) {
        if (intent == null) return;
        String a = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(a)
                && !Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(a)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)) return;

        if (!new Prefs(c).autoStart()) return;

        Intent s = new Intent(c, PopupService.class);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                c.startForegroundService(s);
            } else {
                c.startService(s);
            }
        } catch (Exception ignored) {
            // 部分 ROM 禁止开机自启，忽略即可，用户手动打开 App 也能启动
        }
    }
}
