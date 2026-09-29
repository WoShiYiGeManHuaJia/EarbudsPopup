package com.earpopupx;
import android.Manifest;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.Build;
import android.provider.Settings;

public final class BootReceiver extends BroadcastReceiver {
 @Override public void onReceive(Context c,Intent i){
   String a = i==null?null:i.getAction();
   if(a==null) return;
   try{
     if(!AppPrefs.enabled(c)) return;
     if(!Settings.canDrawOverlays(c)) return;
     if(Build.VERSION.SDK_INT>=31 && c.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED) return;
     Intent s=new Intent(c,BluetoothMonitorService.class);
     if(Build.VERSION.SDK_INT>=26) c.startForegroundService(s); else c.startService(s);
   }catch(Throwable ignored){}
 }
}
