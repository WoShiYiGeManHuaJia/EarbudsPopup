package com.earpopupx;

import android.Manifest;import android.app.*;import android.content.*;import android.content.pm.PackageManager;import android.graphics.Color;import android.net.Uri;import android.os.*;import android.provider.Settings;import android.view.*;import android.widget.*;import java.util.List;import java.util.Set;

public final class MainActivity extends Activity{
 static final int REQ_BT=10,REQ_NOTIFY=11,REQ_MEDIA=12;TextView status,pos,diag;SeekBar x,y,w,h,r,b,dim,dur;Switch auto,drag;int yHalf=500;
 @Override public void onCreate(Bundle b0){super.onCreate(b0);setContentView(R.layout.activity_main);bind();}
 private void bind(){
  status=findViewById(R.id.status);pos=findViewById(R.id.posLabel);
  findViewById(R.id.adbOneClick).setOnClickListener(v->runAdbOneClick());
  findViewById(R.id.start).setOnClickListener(v->startMonitor());
  findViewById(R.id.stop).setOnClickListener(v->stopMonitor());
  findViewById(R.id.test).setOnClickListener(v->{test();});
  findViewById(R.id.pick).setOnClickListener(v->pick());
  findViewById(R.id.resetMedia).setOnClickListener(v->{AppPrefs.setMedia(this,null);Toast.makeText(this,"已恢复默认素材",Toast.LENGTH_SHORT).show();});
  findViewById(R.id.resetPopup).setOnClickListener(v->{AppPrefs.resetPopup(this);sync();refreshPopup();});
  auto=findViewById(R.id.autoSwitch);drag=findViewById(R.id.dragSwitch);auto.setChecked(AppPrefs.enabled(this));drag.setChecked(AppPrefs.dragMode(this));
  auto.setOnCheckedChangeListener((b0,c)->{AppPrefs.setEnabled(this,c);if(c)startMonitor();else stopMonitor();});
  drag.setOnCheckedChangeListener((b0,c)->{AppPrefs.setDragMode(this,c);refreshPopup();});
  x=findViewById(R.id.xBar);y=findViewById(R.id.yBar);w=findViewById(R.id.widthBar);h=findViewById(R.id.heightBar);r=findViewById(R.id.radiusBar);b=findViewById(R.id.blurBar);dim=findViewById(R.id.dimBar);dur=findViewById(R.id.durationBar);
  yHalf=getResources().getDisplayMetrics().heightPixels/2;
  x.setMax(600);y.setMax(Math.max(1000,yHalf*2));w.setMax(26);h.setMax(400);r.setMax(64);b.setMax(80);dim.setMax(40);dur.setMax(28);sync();
  x.setOnSeekBarChangeListener(sl((p,f)->AppPrefs.setX(this,p-300)));y.setOnSeekBarChangeListener(sl((p,f)->AppPrefs.setY(this,p-yHalf)));w.setOnSeekBarChangeListener(sl((p,f)->AppPrefs.setWidthPercent(this,p+72)));h.setOnSeekBarChangeListener(sl((p,f)->AppPrefs.setHeightDp(this,p+280)));r.setOnSeekBarChangeListener(sl((p,f)->AppPrefs.setRadiusDp(this,p)));b.setOnSeekBarChangeListener(sl((p,f)->AppPrefs.setBlurDp(this,p)));dim.setOnSeekBarChangeListener(sl((p,f)->AppPrefs.setDimPercent(this,p)));dur.setOnSeekBarChangeListener(sl((p,f)->AppPrefs.setDurationSec(this,p+2)));
  addAdbAndDiagnostics();
 }

 private LinearLayout.LayoutParams lpBtn(){
  LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,(int)(46*getResources().getDisplayMetrics().density+.5f));
  p.topMargin=(int)(8*getResources().getDisplayMetrics().density+.5f);
  return p;
 }
 private LinearLayout.LayoutParams lpBtnBig(){
  LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,(int)(50*getResources().getDisplayMetrics().density+.5f));
  p.topMargin=(int)(10*getResources().getDisplayMetrics().density+.5f);
  return p;
 }

 // ---------------------------------------------------------- ADB one button

 private String adbScript(){
  String p=getPackageName();
  return "adb shell appops set "+p+" SYSTEM_ALERT_WINDOW allow\n"
   +"adb shell appops set "+p+" RUN_IN_BACKGROUND allow\n"
   +"adb shell appops set "+p+" RUN_ANY_IN_BACKGROUND allow\n"
   +"adb shell appops set "+p+" START_FOREGROUND allow\n"
   +"adb shell dumpsys deviceidle whitelist +"+p+"\n"
   +"adb shell pm grant "+p+" android.permission.BLUETOOTH_CONNECT\n"
   +"adb shell pm grant "+p+" android.permission.BLUETOOTH_SCAN\n"
   +"adb shell pm grant "+p+" android.permission.POST_NOTIFICATIONS\n"
   +"adb shell settings put global hidden_api_policy_p_apps 1\n"
   +"adb shell settings put global hidden_api_policy_pre_p_apps 1\n"
   +"adb shell settings put global hidden_api_policy 1\n";
 }

 private String shellScript(){
  String p=getPackageName();
  return "appops set "+p+" SYSTEM_ALERT_WINDOW allow\n"
   +"appops set "+p+" RUN_IN_BACKGROUND allow\n"
   +"appops set "+p+" RUN_ANY_IN_BACKGROUND allow\n"
   +"appops set "+p+" START_FOREGROUND allow\n"
   +"dumpsys deviceidle whitelist +"+p+"\n"
   +"pm grant "+p+" android.permission.BLUETOOTH_CONNECT\n"
   +"pm grant "+p+" android.permission.BLUETOOTH_SCAN\n"
   +"pm grant "+p+" android.permission.POST_NOTIFICATIONS\n"
   +"settings put global hidden_api_policy_p_apps 1\n"
   +"settings put global hidden_api_policy_pre_p_apps 1\n"
   +"settings put global hidden_api_policy 1\n";
 }

 private void addAdbAndDiagnostics(){
  try{
   ViewGroup host=findViewById(R.id.advHost);
   if(host==null)return;
   int pad=(int)(8*getResources().getDisplayMetrics().density+.5f);

   Button sh=new Button(this);sh.setText("① 复制命令到 Stellar 终端");
   sh.setBackgroundResource(R.drawable.button);sh.setTextColor(Color.parseColor("#17181C"));
   sh.setOnClickListener(v->{ClipboardManager cm=(ClipboardManager)getSystemService(Context.CLIPBOARD_SERVICE);if(cm!=null)cm.setPrimaryClip(ClipData.newPlainText("sh",shellScript()));Toast.makeText(this,"已复制，粘贴到 Stellar 执行",Toast.LENGTH_LONG).show();});
   host.addView(sh,lpBtn());

   Button pc=new Button(this);pc.setText("② 复制电脑版 ADB 命令");
   pc.setBackgroundResource(R.drawable.button);pc.setTextColor(Color.parseColor("#17181C"));
   pc.setOnClickListener(v->{ClipboardManager cm=(ClipboardManager)getSystemService(Context.CLIPBOARD_SERVICE);if(cm!=null)cm.setPrimaryClip(ClipData.newPlainText("adb",adbScript()));Toast.makeText(this,"已复制，粘贴到电脑终端执行",Toast.LENGTH_LONG).show();});
   host.addView(pc,lpBtn());

   Button scan=new Button(this);scan.setText("③ 查看当前已连接耳机");
   scan.setBackgroundResource(R.drawable.button);scan.setTextColor(Color.parseColor("#17181C"));
   scan.setOnClickListener(v->showConnected());
   host.addView(scan,lpBtn());

   TextView note=new TextView(this);note.setText("授权后重开一次监听。hidden_api_policy 用于解除隐藏 API 限制。\n如果连接耳机不弹窗，点④确认系统是否真的认为耳机已连接。");
   note.setTextSize(11);note.setTextColor(Color.parseColor("#74767D"));note.setPadding(pad,pad,pad,0);host.addView(note);

   TextView dh=new TextView(this);dh.setText("协议抓取（最近一次连接）");dh.setTextSize(15);dh.setTypeface(null,1);
   dh.setTextColor(Color.parseColor("#17181C"));dh.setPadding(pad,(int)(14*getResources().getDisplayMetrics().density+.5f),pad,pad);host.addView(dh);

   diag=new TextView(this);diag.setTextSize(10);diag.setPadding(pad,pad,pad,pad);diag.setTypeface(android.graphics.Typeface.MONOSPACE);
   diag.setTextColor(Color.parseColor("#74767D"));
   diag.setTextIsSelectable(true);
   host.addView(diag);

   Button land=new Button(this);land.setText(landText());
   land.setBackgroundResource(R.drawable.button);land.setTextColor(Color.parseColor("#17181C"));
   land.setOnClickListener(v->{
     int n=AppPrefs.landscapeMode(this);
     n=(n+1)%3;
     AppPrefs.setLandscapeMode(this,n);
     land.setText(landText());
     Toast.makeText(this,landText(),Toast.LENGTH_SHORT).show();
   });
   host.addView(land,lpBtn());

   Button sup=new Button(this);sup.setText("屏蔽系统耳机弹窗（ADB）");
   sup.setBackgroundResource(R.drawable.button);sup.setTextColor(Color.parseColor("#17181C"));
   sup.setOnClickListener(v->runSuppress(false));
   host.addView(sup,lpBtn());

   Button unsup=new Button(this);unsup.setText("恢复系统耳机弹窗（ADB）");
   unsup.setBackgroundResource(R.drawable.button);unsup.setTextColor(Color.parseColor("#17181C"));
   unsup.setOnClickListener(v->runSuppress(true));
   host.addView(unsup,lpBtn());

   Button rd=new Button(this);rd.setText("刷新协议抓取");
   rd.setBackgroundResource(R.drawable.button);rd.setTextColor(Color.parseColor("#17181C"));
   rd.setOnClickListener(v->refreshDiag());
   host.addView(rd,lpBtn());
   refreshDiag();
  }catch(Throwable ignored){}
 }

 private void showConnected(){
  StringBuilder sb=new StringBuilder();
  try{
   android.bluetooth.BluetoothAdapter a=android.bluetooth.BluetoothAdapter.getDefaultAdapter();
   if(a==null){sb.append("设备不支持蓝牙");}
   else if(!a.isEnabled()){sb.append("蓝牙未开启");}
   else{
    if(Build.VERSION.SDK_INT>=31&&checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED){sb.append("没有 BLUETOOTH_CONNECT 权限");}
    else{
     Set<android.bluetooth.BluetoothDevice> bonded=a.getBondedDevices();
     if(bonded==null||bonded.isEmpty())sb.append("没有已配对设备");
     else{
      for(android.bluetooth.BluetoothDevice d:bonded){
       boolean conn=false;
       try{java.lang.reflect.Method m=android.bluetooth.BluetoothDevice.class.getMethod("isConnected");Object o=m.invoke(d);if(o instanceof Boolean)conn=(Boolean)o;}catch(Throwable ignored){}
       int lv=-1;
       try{java.lang.reflect.Method m=android.bluetooth.BluetoothDevice.class.getMethod("getBatteryLevel");Object o=m.invoke(d);if(o instanceof Integer)lv=(Integer)o;}catch(Throwable ignored){}
       sb.append(conn?"● ":"○ ").append(d.getName()).append('\n').append("   ").append(d.getAddress()).append("  电量 ").append(lv<0?"未知":(lv+"%")).append('\n');
      }
     }
    }
   }
  }catch(Throwable t){sb.append("读取失败：").append(t);}
  new AlertDialog.Builder(this).setTitle("当前已配对设备").setMessage(sb.toString()).setPositiveButton("关闭",null).show();
 }

 /** 尝试拉起 Shizuku / Stellar；返回是否成功打开 */
 private boolean openStellar(){
  String[] pkgs={"moe.shizuku.stellar","moe.shizuku.privileged.api","rikka.shizuku.stellar","com.stellar.shizuku"};
  for(String p:pkgs){
   try{
    Intent i=getPackageManager().getLaunchIntentForPackage(p);
    if(i!=null){i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);startActivity(i);return true;}
   }catch(Throwable ignored){}
  }
  return false;
 }

 private void runAdbOneClick(){
  if(!ShizukuHelper.isServiceRunning()){
   AlertDialog.Builder b=new AlertDialog.Builder(this)
    .setTitle("未检测到 Shizuku / Stellar 服务")
    .setMessage("本应用内部已内置运行库，但权限通道必须由服务端交给它：\n\n"
      +"1. 打开 Stellar（或 Shizuku），把服务跑起来（无线调试 / Root）\n"
      +"2. 服务端里 → 设置 → 打开「允许 Shizuku」（Stellar 需要这一步）\n"
      +"3. 服务端 → 授权应用 → 找到 EarPopup X → 允许\n"
      +"4. 回到本页再点一次「一键 ADB 授权」\n\n"
      +"也可以直接复制命令，在 Stellar 的终端里粘贴执行一次。")
    .setPositiveButton("打开 Stellar / Shizuku",(d,w)->{
      if(!openStellar())Toast.makeText(this,"没找到 Stellar / Shizuku，请先安装",Toast.LENGTH_LONG).show();
    })
    .setNeutralButton("复制命令",(d,w)->{ClipboardManager cm=(ClipboardManager)getSystemService(Context.CLIPBOARD_SERVICE);if(cm!=null)cm.setPrimaryClip(ClipData.newPlainText("sh",shellScript()));Toast.makeText(this,"已复制，粘贴到 Stellar 终端执行",Toast.LENGTH_LONG).show();})
    .setNegativeButton("关闭",null);
   b.show();
   return;
  }
  if(!ShizukuHelper.hasPermission()){
   ShizukuHelper.requestPermission();
   Toast.makeText(this,"已在 Stellar / Shizuku 弹出授权请求，允许后再回来点一次。",Toast.LENGTH_LONG).show();
   return;
  }
  Toast.makeText(this,"正在以 adb 权限执行…",Toast.LENGTH_SHORT).show();
  final String script=shellScript();
  new Thread(()->{
   final String out=ShizukuHelper.run(script);
   runOnUiThread(()->{
    String msg=(out==null)?null:out.trim();
    if(msg==null||msg.isEmpty())msg="全部执行完成（appops / pm grant 成功时通常无输出）";
    new AlertDialog.Builder(this).setTitle("执行结果").setMessage(msg)
      .setPositiveButton("关闭",null).show();
    status();
   });
  }).start();
 }

 private String landText(){
  int n=AppPrefs.landscapeMode(this);
  if(n==0) return "横屏时：正常弹窗";
  if(n==1) return "横屏时：小弹窗（无耳机图标）";
  return "横屏时：不弹窗";
 }

 /** 小米的官方耳机弹窗由"小米快连/蓝牙"组件负责，用 shell 关掉它的悬浮窗。
  *  两条都试：先温和地撤掉悬浮窗权限，不行再把组件停掉。可一键恢复。 */
 private void runSuppress(boolean restore){
  if(!ShizukuHelper.isServiceRunning()){
   AlertDialog.Builder b=new AlertDialog.Builder(this)
    .setTitle("未检测到 Shizuku / Stellar 服务")
    .setMessage("屏蔽系统弹窗需要 shell 权限。先启动 Stellar 服务并授权本应用，再点一次。")
    .setPositiveButton("打开 Stellar / Shizuku",(d,w)->{ if(!openStellar())Toast.makeText(this,"没找到 Stellar / Shizuku，请先安装",Toast.LENGTH_LONG).show(); })
    .setNegativeButton("关闭",null);
   b.show();
   return;
  }
  if(!ShizukuHelper.hasPermission()){ ShizukuHelper.requestPermission(); return; }
  final String script=suppressScript(restore);
  new Thread(()->{
   final String out=ShizukuHelper.run(script);
   runOnUiThread(()->{
    String msg=(out==null)?null:out.trim();
    if(msg==null||msg.isEmpty())msg="执行完成";
    new AlertDialog.Builder(this).setTitle(restore?"恢复系统弹窗":"屏蔽系统弹窗").setMessage(msg).setPositiveButton("关闭",null).show();
    status();
   });
  }).start();
 }

 private String suppressScript(boolean restore){
  String[] pkgs={"com.xiaomi.bluetooth","com.milink.service","com.xiaomi.wearable"};
  StringBuilder sb=new StringBuilder();
  for(String p:pkgs){
   if(restore){
    sb.append("pm enable ").append(p).append("\n");
    sb.append("appops set ").append(p).append(" SYSTEM_ALERT_WINDOW allow\n");
   }else{
    sb.append("appops set ").append(p).append(" SYSTEM_ALERT_WINDOW deny\n");
    sb.append("pm disable-user --user 0 ").append(p).append("\n");
   }
  }
  return sb.toString();
 }

 private void refreshDiag(){
  if(diag==null)return;
  List<String> lines=BluetoothMonitorService.rawLog();
  if(lines.isEmpty()){diag.setText("暂无数据。连接一次耳机后回来点刷新。\nAT= 耳机通过 HFP 上报的命令\nADV= 蓝牙广播里的厂商数据");return;}
  StringBuilder sb=new StringBuilder();
  for(String s:lines)sb.append(s).append('\n');
  diag.setText(sb.toString().trim());
 }

 interface S{void set(int p,boolean fromUser);}SeekBar.OnSeekBarChangeListener sl(S s){return new SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(SeekBar v,int p,boolean f){s.set(p,f);pos();if(f)refreshPopup();}public void onStartTrackingTouch(SeekBar v){}public void onStopTrackingTouch(SeekBar v){}};}
 void sync(){x.setProgress(AppPrefs.x(this)+300);y.setProgress(Math.min(y.getMax(),Math.max(0,AppPrefs.y(this)+yHalf)));w.setProgress(AppPrefs.widthPercent(this)-72);h.setProgress(AppPrefs.heightDp(this)-280);r.setProgress(AppPrefs.radiusDp(this));b.setProgress(AppPrefs.blurDp(this));dim.setProgress(AppPrefs.dimPercent(this));dur.setProgress(AppPrefs.durationSec(this)-2);pos();}
 void pos(){pos.setText("X "+AppPrefs.x(this)+" · Y "+AppPrefs.y(this)+" · "+AppPrefs.widthPercent(this)+"% · "+AppPrefs.heightDp(this)+"dp · 圆角 "+AppPrefs.radiusDp(this)+"dp");}
 void overlay(){startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:"+getPackageName())));}
 void bt(){if(Build.VERSION.SDK_INT>=31)requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT,Manifest.permission.BLUETOOTH_SCAN},REQ_BT);}
 void notifyPerm(){if(Build.VERSION.SDK_INT>=33)requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},REQ_NOTIFY);}
 void batteryOpt(){try{startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,Uri.parse("package:"+getPackageName())));}catch(Throwable t){startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));}}
 void pick(){Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("image/*");i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);startActivityForResult(i,REQ_MEDIA);}
 @Override protected void onActivityResult(int r0,int c,Intent d){super.onActivityResult(r0,c,d);if(r0==REQ_MEDIA&&c==RESULT_OK&&d!=null&&d.getData()!=null){try{getContentResolver().takePersistableUriPermission(d.getData(),Intent.FLAG_GRANT_READ_URI_PERMISSION);}catch(Throwable ignored){}AppPrefs.setMedia(this,d.getData());refreshPopup();Toast.makeText(this,"素材已保存",Toast.LENGTH_SHORT).show();}}
 void startMonitor(){if(!Settings.canDrawOverlays(this)){overlay();Toast.makeText(this,"开启悬浮窗后再回来启动",Toast.LENGTH_LONG).show();return;}if(Build.VERSION.SDK_INT>=31&&checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED){bt();return;}AppPrefs.setEnabled(this,true);auto.setChecked(true);try{Intent i=new Intent(this,BluetoothMonitorService.class);if(Build.VERSION.SDK_INT>=26)startForegroundService(i);else startService(i);Toast.makeText(this,"耳机监听已启动",Toast.LENGTH_SHORT).show();}catch(Throwable t){Toast.makeText(this,"启动失败：请先在本页面保持可见并完成权限授权",Toast.LENGTH_LONG).show();}}
 void stopMonitor(){AppPrefs.setEnabled(this,false);auto.setChecked(false);try{stopService(new Intent(this,BluetoothMonitorService.class));}catch(Throwable ignored){}EarPopupWindow.shared(this).dismiss();}
 void test(){if(!Settings.canDrawOverlays(this)){overlay();return;}EarPopupWindow.shared(this).show("测试耳机 · EarPopup X",BatteryState.unknown("测试模式"),true);}
 void refreshPopup(){EarPopupWindow.shared(this).refreshLayout();}
 void status(){StringBuilder s=new StringBuilder();s.append(Settings.canDrawOverlays(this)?"● 悬浮窗已开启\n":"○ 悬浮窗未开启\n");if(Build.VERSION.SDK_INT>=31)s.append(checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED?"● 蓝牙权限已开启\n":"○ 蓝牙权限未开启\n");if(Build.VERSION.SDK_INT>=33)s.append(checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED?"● 通知权限已开启\n":"○ 通知权限未开启\n");s.append(AppPrefs.enabled(this)?"● 自动弹窗已启用\n":"○ 自动弹窗未启用\n");
  if(ShizukuHelper.isServiceRunning())s.append(ShizukuHelper.hasPermission()?"● Shizuku/Stellar 已授权（"+ShizukuHelper.serverInfo()+"）":"○ Shizuku/Stellar 已运行，但未授权本应用");
  else s.append("○ 未检测到 Shizuku / Stellar 服务");
  status.setText(s.toString());}
 @Override protected void onResume(){super.onResume();status();refreshDiag();}
}
