package com.earpopupx;

import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/**
 * 开机 / 解锁 / 包替换后拉起监听服务。
 * 注：Android 8+ 对静态注册的隐式广播有限制，ACL_CONNECTED 静态不一定能收到，
 * 所以主要靠服务内的动态接收器 + 轮询兜底，这里只是尽力而为。
 */
public final class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        String a = i == null ? null : i.getAction();
        if (a == null) return;
        if (Intent.ACTION_BOOT_COMPLETED.equals(a)
                || Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(a)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)
                || BluetoothDevice.ACTION_ACL_CONNECTED.equals(a)
                || "com.earpopupx.RESTART".equals(a)) {
            start(c);
        }
    }

    private void start(Context c) {
        try {
            Intent s = new Intent(c, BluetoothMonitorService.class);
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(s); else c.startService(s);
        } catch (Throwable ignored) {}
    }
}
