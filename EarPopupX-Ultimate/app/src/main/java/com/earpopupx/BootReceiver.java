package com.earpopupx;
import android.content.*;
public final class BootReceiver extends BroadcastReceiver {
 @Override public void onReceive(Context c,Intent i){
   // Android 15 restricts BOOT_COMPLETED -> foreground-service starts for some service types.
   // We intentionally do not start the service here. The app's visible UI starts it safely.
 }
}
