package com.yuanbao.earbuds;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/**
 * 有线耳机（3.5mm / Type-C）插入触发。
 * ACTION_HEADSET_PLUG 属于系统豁免广播，Android 8+ 静态注册依旧可用。
 */
public class WiredReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context c, Intent intent) {
        if (intent == null || !Intent.ACTION_HEADSET_PLUG.equals(intent.getAction())) return;

        int state = intent.getIntExtra("state", 0);
        if (state != 1) return; // 1=插入 0=拔出

        Prefs p = new Prefs(c);
        if (!p.masterEnabled() || !p.wiredEnabled()) return;

        String name = intent.getStringExtra("name");
        Intent s = new Intent(c, PopupService.class);
        s.setAction(PopupService.ACTION_SHOW);
        s.putExtra(PopupService.EXTRA_NAME, (name == null || name.isEmpty()) ? "有线耳机" : name);
        s.putExtra(PopupService.EXTRA_WIRED, true);
        s.putExtra(PopupService.EXTRA_BATTERY, -1);

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                c.startForegroundService(s);
            } else {
                c.startService(s);
            }
        } catch (Exception ignored) {
            // 后台启动受限时忽略：服务常驻时会通过动态注册的同一广播自行弹窗
        }
    }
}
