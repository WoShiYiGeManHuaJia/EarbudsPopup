package com.earpopupx;

import android.Manifest;
import android.app.*;
import android.bluetooth.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.*;
import java.util.*;

public final class BluetoothMonitorService extends Service {
    public static final String ACTION_STOP="com.earpopupx.STOP";
    private static final String CHANNEL="earpopupx_monitor";
    private final Handler main=new Handler(Looper.getMainLooper());
    private BroadcastReceiver receiver; private BluetoothBatteryReader reader; private EarPopupWindow popup; private String currentAddress; private boolean started;
    @Override public void onCreate(){super.onCreate(); createChannel(); reader=new BluetoothBatteryReader(this); popup=EarPopupWindow.shared(this); registerBluetoothReceiver();}
    @Override public int onStartCommand(Intent intent,int flags,int id){
        if(ACTION_STOP.equals(intent==null?null:intent.getAction())){stopSelf();return START_NOT_STICKY;}
        if(!promisesMet()) { stopSelf(); return START_NOT_STICKY; }
        startAsForeground(); started=true; main.post(this::detectAlreadyConnected); return START_STICKY;
    }
    private boolean promisesMet(){
        if(Build.VERSION.SDK_INT>=31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED)return false;
        return android.provider.Settings.canDrawOverlays(this);
    }
    private void startAsForeground(){
        Notification.Builder b=new Notification.Builder(this,CHANNEL).setSmallIcon(android.R.drawable.stat_sys_data_bluetooth).setContentTitle("EarPopup X").setContentText("耳机连接监听运行中").setOngoing(true).setCategory(Notification.CATEGORY_SERVICE).setContentIntent(PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE));
        Notification.Action stop=new Notification.Action.Builder(null,"停止监听",PendingIntent.getService(this,2,new Intent(this,BluetoothMonitorService.class).setAction(ACTION_STOP),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE)).build(); b.addAction(stop);
        try{if(Build.VERSION.SDK_INT>=29)startForeground(7,b.build(),ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);else startForeground(7,b.build());}catch(Throwable t){stopSelf();}
    }
    private void registerBluetoothReceiver(){
        receiver=new BroadcastReceiver(){@Override public void onReceive(Context c,Intent i){BluetoothDevice d=getDevice(i); if(d==null)return; String a=i.getAction();
            if(BluetoothDevice.ACTION_ACL_CONNECTED.equals(a)){main.post(()->handleConnected(d));}
            else if(BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(a)){main.post(()->{if(same(d)){currentAddress=null;popup.dismiss();}});}
            else if("android.bluetooth.device.action.BATTERY_LEVEL_CHANGED".equals(a)){int v=i.getIntExtra("android.bluetooth.device.extra.BATTERY_LEVEL",-1);if(valid(v)&&same(d))main.post(()->popup.update(safeName(d),new BatteryState(v,-1,-1,-1,false,false,false,"系统蓝牙")));}
        }};
        IntentFilter f=new IntentFilter();f.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);f.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);f.addAction("android.bluetooth.device.action.BATTERY_LEVEL_CHANGED");
        if(Build.VERSION.SDK_INT>=33)registerReceiver(receiver,f,Context.RECEIVER_EXPORTED);else registerReceiver(receiver,f);
    }
    private void handleConnected(BluetoothDevice d){if(!AppPrefs.enabled(this))return;if(Build.VERSION.SDK_INT>=31&&checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED)return;String addr;try{addr=d.getAddress();}catch(Throwable t){return;}if(addr==null)return;currentAddress=addr;popup.show(safeName(d),BatteryState.unknown("正在读取真实电量…"));reader.read(d,s->{main.post(()->{if(same(d)&&AppPrefs.enabled(this))popup.update(safeName(d),s);});});}
    private void detectAlreadyConnected(){if(!AppPrefs.enabled(this))return;try{BluetoothAdapter a=BluetoothAdapter.getDefaultAdapter();if(a==null||!a.isEnabled())return;for(BluetoothDevice d:a.getBondedDevices()){try{if(d.getConnectionState()==BluetoothProfile.STATE_CONNECTED){handleConnected(d);return;}}catch(Throwable ignored){}}}catch(Throwable ignored){}}
    private BluetoothDevice getDevice(Intent i){try{if(Build.VERSION.SDK_INT>=33)return i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE,BluetoothDevice.class);return (BluetoothDevice)i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);}catch(Throwable t){return null;}}
    private boolean same(BluetoothDevice d){try{return currentAddress!=null&&currentAddress.equals(d.getAddress());}catch(Throwable t){return false;}}
    private String safeName(BluetoothDevice d){try{String n=d.getName();return n==null||n.trim().isEmpty()?"蓝牙耳机":n;}catch(Throwable t){return "蓝牙耳机";}}
    private boolean valid(int v){return v>=0&&v<=100;}
    private void createChannel(){if(Build.VERSION.SDK_INT>=26){NotificationManager n=getSystemService(NotificationManager.class);n.createNotificationChannel(new NotificationChannel(CHANNEL,"耳机监听",NotificationManager.IMPORTANCE_LOW));}}
    @Override public void onDestroy(){started=false;try{unregisterReceiver(receiver);}catch(Throwable ignored){}if(reader!=null)reader.close();if(popup!=null)popup.dismiss();super.onDestroy();}
    @Override public IBinder onBind(Intent i){return null;}
}
