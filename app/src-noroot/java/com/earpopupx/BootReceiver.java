package com.earpopupx;

import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

public final class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context c, Intent i) {
        if (c == null || i == null) return;
        String a = i.getAction();
        if (BluetoothDevice.ACTION_ACL_CONNECTED.equals(a)) {
            start(c);
            return;
        }
        if (Intent.ACTION_BOOT_COMPLETED.equals(a) || Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(a)) {
            start(c);
        }
    }

    private void start(Context c) {
        if (!AppPrefs.enabled(c)) return;
        try {
            Intent s = new Intent(c, BluetoothMonitorService.class);
            if (Build.VERSION.SDK_INT >= 26) {
                c.startForegroundService(s);
            } else {
                c.startService(s);
            }
        } catch (Throwable ignored) {
        }
    }
}
