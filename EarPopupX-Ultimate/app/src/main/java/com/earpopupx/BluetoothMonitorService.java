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
    private static final int RAW_MAX = 60;

    /** HyperOS re-delivers a stale "connected" broadcast right after a real
     *  disconnect. Ignore connect events for this long after a disconnect so the
     *  popup does not reappear when the headset is switched off. */
    private static final long DISCONNECT_SUPPRESS_MS = 9000L;

    private static final List<String> RAW_LOG = Collections.synchronizedList(new ArrayList<String>());
    public static List<String> rawLog(){ return new ArrayList<String>(RAW_LOG); }
    public static void record(String line){
        synchronized(RAW_LOG){
            if(RAW_LOG.size()>=RAW_MAX) RAW_LOG.remove(0);
            RAW_LOG.add(line);
        }
    }

    private final Handler main=new Handler(Looper.getMainLooper());
    private BroadcastReceiver receiver;
    private BluetoothBatteryReader reader;
    private VendorBatterySniffer sniffer;
    private EarPopupWindow popup;
    private String currentAddress;
    private boolean started;
    private boolean gotValue;
    private long lastDisconnectAt;
    /** 开盖那一瞬间就要有真电量，所以最多等这么久就必须把弹窗放出来。 */
    private static final long PRE_READ_MS = 1200L;
    private final java.util.Map<String,BatteryState> prefetched = new java.util.concurrent.ConcurrentHashMap<String,BatteryState>();
    private boolean prefetching;
    private android.bluetooth.le.ScanCallback prefetchCb;

    private final Runnable pollTask = new Runnable() {
        @Override public void run() {
            if(!started) return;
            detectAlreadyConnected();
            main.postDelayed(this, 2500L);
        }
    };

    @Override public void onCreate(){
        super.onCreate();
        createChannel();
        reader=new BluetoothBatteryReader(this);
        sniffer=new VendorBatterySniffer(this);
        popup=EarPopupWindow.shared(this);
        registerBluetoothReceiver();
    }

    @Override public int onStartCommand(Intent intent,int flags,int id){
        if(ACTION_STOP.equals(intent==null?null:intent.getAction())){ stopSelf(); return START_NOT_STICKY; }
        if(!promisesMet()){ stopSelf(); return START_NOT_STICKY; }
        startAsForeground();
        started=true;
        main.post(this::detectAlreadyConnected);
        main.removeCallbacks(pollTask);
        main.postDelayed(pollTask, 2500L);
        main.post(this::startPrefetchScan);
        return START_STICKY;
    }

    private boolean promisesMet(){
        if(Build.VERSION.SDK_INT>=31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED) return false;
        return android.provider.Settings.canDrawOverlays(this);
    }

    private void startAsForeground(){
        Notification.Builder b=new Notification.Builder(this,CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("EarPopup X")
            .setContentText("耳机连接监听运行中")
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setContentIntent(PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE));
        Notification.Action stop=new Notification.Action.Builder(null,"停止监听",
            PendingIntent.getService(this,2,new Intent(this,BluetoothMonitorService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE)).build();
        b.addAction(stop);
        try{
            if(Build.VERSION.SDK_INT>=29) startForeground(7,b.build(),ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            else startForeground(7,b.build());
        }catch(Throwable t){ stopSelf(); }
    }

    private void registerBluetoothReceiver(){
        receiver=new BroadcastReceiver(){
            @Override public void onReceive(Context c,Intent i){
                BluetoothDevice d=getDevice(i);
                if(d==null) return;
                String a=i.getAction();
                if(BluetoothDevice.ACTION_ACL_CONNECTED.equals(a)){
                    main.post(()->handleConnected(d));
                } else if(BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(a)){
                    main.post(()->{
                        if(same(d)){
                            currentAddress=null;
                            lastDisconnectAt=System.currentTimeMillis();
                            record("断开 "+safeName(d));
                            popup.dismiss();
                        }
                    });
                } else if("android.bluetooth.device.action.BATTERY_LEVEL_CHANGED".equals(a)){
                    int v=i.getIntExtra("android.bluetooth.device.extra.BATTERY_LEVEL",-1);
                    if(valid(v)&&same(d)) main.post(()->{
                        if(!gotValue) gotValue=true;
                        popup.update(safeName(d),new BatteryState(v,-1,-1,-1,false,false,false,"系统蓝牙广播"));
                    });
                }
            }
        };
        IntentFilter f=new IntentFilter();
        f.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
        f.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        f.addAction("android.bluetooth.device.action.BATTERY_LEVEL_CHANGED");
        if(Build.VERSION.SDK_INT>=33) registerReceiver(receiver,f,Context.RECEIVER_EXPORTED);
        else registerReceiver(receiver,f);
    }

    /** 开盖就要有真电量：连接后先并发抓取，拿到值（或超时）才弹窗。
     *  不再"先弹一个占位、再慢慢抓"。 */
    private void handleConnected(BluetoothDevice d){
        if(!AppPrefs.enabled(this)) return;
        if(Build.VERSION.SDK_INT>=31&&checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED) return;
        String addr;
        try{ addr=d.getAddress(); }catch(Throwable t){ return; }
        if(addr==null) return;
        if(addr.equals(currentAddress)) return;
        if(System.currentTimeMillis()-lastDisconnectAt < DISCONNECT_SUPPRESS_MS){
            record("抑制补发的连接广播 "+addr);
            return;
        }
        currentAddress=addr;
        gotValue=false;
        synchronized(RAW_LOG){ RAW_LOG.clear(); }
        final String name=safeName(d);
        record("连接 "+name+" ("+addr+")");

        // 开盖预读：如果在 ACL 之前 BLE 广播阶段已经抓到过，直接用
        BatteryState pre=prefetched.get(addr);
        final BatteryState[] best=new BatteryState[]{ pre!=null?pre:BatteryState.unknown("") };
        final boolean[] shown=new boolean[]{false};

        final Runnable showNow=new Runnable(){
            @Override public void run(){
                if(shown[0])return;
                shown[0]=true;
                popup.show(name,best[0]);
            }
        };

        // 已经拿到真值就立刻弹，否则最多等 PRE_READ_MS
        if(best[0].hasDetail()||best[0].aggregate>=0){ main.post(showNow); }
        else { main.postDelayed(showNow,PRE_READ_MS); }

        sniffer.setSink(new VendorBatterySniffer.Sink(){
            public void onBattery(BatteryState s){
                main.post(()->{
                    if(same(d)&&AppPrefs.enabled(BluetoothMonitorService.this)){
                        gotValue=true;
                        best[0]=s;
                        prefetched.put(addr,s);
                        if(shown[0]) popup.update(name,s); else main.post(showNow);
                    }
                });
            }
            public void onRaw(String line){ record(line); }
        });
        sniffer.start(addr);

        reader.read(d,new BluetoothBatteryReader.Callback(){
            public void onState(BatteryState s){
                main.post(()->{
                    record("读取 "+s.source+(s.hasDetail()?(" L="+s.left+" R="+s.right+" C="+s.caseLevel):(s.aggregate>=0?(" = "+s.aggregate+"%"):"")));
                    if(same(d)&&AppPrefs.enabled(BluetoothMonitorService.this)){
                        if(s.hasDetail()||s.aggregate>=0){
                            gotValue=true;
                            best[0]=s;
                            prefetched.put(addr,s);
                            if(shown[0]) popup.update(name,s); else main.post(showNow);
                        }
                    }
                });
            }
            public void onRaw(String line){ record(line); }
        });
    }

    /** 开盖广播阶段就提前读一次：耳机开盖会先发 BLE 广播，比 ACL 更早。 */
    private void startPrefetchScan(){
        if(prefetching) return;
        try{
            BluetoothAdapter a=BluetoothAdapter.getDefaultAdapter();
            if(a==null) return;
            if(Build.VERSION.SDK_INT>=31&&checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)!=PackageManager.PERMISSION_GRANTED) return;
            final android.bluetooth.le.BluetoothLeScanner sc=a.getBluetoothLeScanner();
            if(sc==null) return;
            prefetching=true;
            android.bluetooth.le.ScanSettings st=new android.bluetooth.le.ScanSettings.Builder()
                .setScanMode(android.bluetooth.le.ScanSettings.SCAN_MODE_LOW_POWER).build();
            prefetchCb=new android.bluetooth.le.ScanCallback(){
                @Override public void onScanResult(int cbType,android.bluetooth.le.ScanResult r){
                    try{
                        BluetoothDevice dev=r.getDevice();
                        String ad=dev.getAddress();
                        if(ad==null||prefetched.containsKey(ad)) return;
                        if(!isBonded(ad)) return;
                        record("开盖广播 "+ad);
                        reader.read(dev,new BluetoothBatteryReader.Callback(){
                            public void onState(BatteryState s){
                                if(s.hasDetail()||s.aggregate>=0) prefetched.put(ad,s);
                            }
                            public void onRaw(String line){ record(line); }
                        });
                    }catch(Throwable ignored){}
                }
            };
            sc.startScan(null,st,prefetchCb);
        }catch(Throwable t){ prefetching=false; }
    }

    private boolean isBonded(String addr){
        try{
            BluetoothAdapter a=BluetoothAdapter.getDefaultAdapter();
            if(a==null) return false;
            Set<BluetoothDevice> bs=a.getBondedDevices();
            if(bs==null) return false;
            for(BluetoothDevice d:bs){ if(addr.equals(d.getAddress())) return true; }
        }catch(Throwable ignored){}
        return false;
    }

    private void stopPrefetchScan(){
        try{
            if(prefetchCb!=null){
                BluetoothAdapter a=BluetoothAdapter.getDefaultAdapter();
                if(a!=null){ android.bluetooth.le.BluetoothLeScanner sc=a.getBluetoothLeScanner(); if(sc!=null) sc.stopScan(prefetchCb); }
            }
        }catch(Throwable ignored){}
        prefetchCb=null;prefetching=false;
    }

    /** Poll is the reliable path on HyperOS: ACL broadcasts get throttled, but
     *  the bonded-device connection state stays readable. */
    private void detectAlreadyConnected(){
        if(!AppPrefs.enabled(this)) return;
        if(Build.VERSION.SDK_INT>=31&&checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED) return;
        try{
            BluetoothAdapter a=BluetoothAdapter.getDefaultAdapter();
            if(a==null||!a.isEnabled()) return;
            Set<BluetoothDevice> bonded=a.getBondedDevices();
            if(bonded==null) return;
            for(BluetoothDevice d:bonded){
                try{
                    if(isConnected(d)){ handleConnected(d); return; }
                }catch(Throwable ignored){
                }
            }
        }catch(Throwable ignored){
        }
    }

    private boolean isConnected(BluetoothDevice d){
        try{
            java.lang.reflect.Method m=BluetoothDevice.class.getMethod("isConnected");
            Object r=m.invoke(d);
            if(r instanceof Boolean) return (Boolean)r;
        }catch(Throwable ignored){
        }
        return false;
    }

    private BluetoothDevice getDevice(Intent i){
        try{
            if(Build.VERSION.SDK_INT>=33) return i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE,BluetoothDevice.class);
            return (BluetoothDevice)i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
        }catch(Throwable t){ return null; }
    }

    private boolean same(BluetoothDevice d){
        try{ return currentAddress!=null&&currentAddress.equals(d.getAddress()); }catch(Throwable t){ return false; }
    }

    private String safeName(BluetoothDevice d){
        try{
            String n=d.getName();
            return n==null||n.trim().isEmpty()?"蓝牙耳机":n;
        }catch(Throwable t){ return "蓝牙耳机"; }
    }

    private boolean valid(int v){ return v>=0&&v<=100; }

    private void createChannel(){
        if(Build.VERSION.SDK_INT>=26){
            NotificationManager n=getSystemService(NotificationManager.class);
            n.createNotificationChannel(new NotificationChannel(CHANNEL,"耳机监听",NotificationManager.IMPORTANCE_LOW));
        }
    }

    @Override public void onDestroy(){
        started=false;
        main.removeCallbacks(pollTask);
        try{ unregisterReceiver(receiver); }catch(Throwable ignored){}
        stopPrefetchScan();
        if(reader!=null) reader.close();
        if(sniffer!=null) sniffer.stop();
        if(popup!=null) popup.dismiss();
        super.onDestroy();
    }

    @Override public void onTaskRemoved(Intent rootIntent){
        // 任务被划掉（或 App 退到后台被系统回收）时立刻自我重启，
        // 否则「不在 App 界面里就没有弹窗」
        try{
            Intent i=new Intent(this,BluetoothMonitorService.class);
            if(Build.VERSION.SDK_INT>=26){ startForegroundService(i); }
            else { startService(i); }
        }catch(Throwable e){
            try{ startService(new Intent(this,BluetoothMonitorService.class)); }catch(Throwable ignored){}
        }
        // 兜底：3 秒后再确认一次，防止上面的调用被后台启动限制拦掉
        try{
            main.postDelayed(new Runnable(){
                @Override public void run(){
                    if(!started){
                        try{
                            Intent i=new Intent(BluetoothMonitorService.this,BluetoothMonitorService.class);
                            if(Build.VERSION.SDK_INT>=26){ startForegroundService(i); }
                            else { startService(i); }
                        }catch(Throwable ignored){}
                    }
                }
            },3000);
        }catch(Throwable ignored){}
        super.onTaskRemoved(rootIntent);
    }

    @Override public IBinder onBind(Intent i){ return null; }
}
