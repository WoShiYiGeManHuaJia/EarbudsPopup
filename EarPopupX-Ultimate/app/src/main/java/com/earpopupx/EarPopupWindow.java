package com.earpopupx;

import android.animation.*;import android.content.*;import android.content.res.Configuration;import android.graphics.*;import android.graphics.drawable.*;import android.net.Uri;import android.os.*;import android.provider.Settings;import android.view.*;import android.widget.*;import java.util.*;

public final class EarPopupWindow {
 private static EarPopupWindow singleton;
 public static synchronized EarPopupWindow shared(Context c){if(singleton==null)singleton=new EarPopupWindow(c);return singleton;}
 private final Context c;private final Handler h=new Handler(Looper.getMainLooper());private WindowManager wm;private View root;private WindowManager.LayoutParams lp;
 private TextView title,battery,source,aggLine,lv,rv;private ImageView media,licon,ricon;private float dx,dy;private int sx,sy;private boolean dragging;private Runnable dismissTask;
 private boolean shownForThisDevice,miniMode,testMode;private String shownName;
 /** 还没拿到真实电量时不计时：弹窗会一直等，图标持续浮动，直到某个通道给出数值。 */
 private boolean hasValue;
 private AnimatorSet floatAnim;
 private EarPopupWindow(Context x){c=x.getApplicationContext();}

 public void show(String n,BatteryState s){ show(n,s,false); }

 public void show(String n,BatteryState s,boolean test){
   if(Looper.myLooper()!=Looper.getMainLooper()){h.post(()->show(n,s,test));return;}
   if(!Settings.canDrawOverlays(c))return;

   boolean land=c.getResources().getConfiguration().orientation==Configuration.ORIENTATION_LANDSCAPE;
   if(land && !test && AppPrefs.landscapeMode(c)==0){ dismiss(); return; }   // 横屏：不弹窗
   boolean mini = land && !test && AppPrefs.landscapeMode(c)==1;             // 横屏：小弹窗

   testMode=test;
   if(test)hasValue=true;           // 测试弹窗不阻塞：即使没电量也按设定时长消失

   // 已经显示中：只更新内容，不再重放入场动画 —— 那正是"闪一下"的来源。
   if(root!=null){
     if(mini!=miniMode){ dismiss(); }
     else { update(n,s); reschedule(); return; }
   }
   miniMode=mini;
   shownForThisDevice=true; shownName=n;
   build(n,s,mini,test);
   if(root==null||lp==null)return;
   root.setVisibility(View.VISIBLE);animateIn();schedule();
 }

 public void update(String n,BatteryState s){
   if(Looper.myLooper()!=Looper.getMainLooper()){h.post(()->update(n,s));return;}
   if(root==null){show(n,s,testMode);return;}
   shownName=n;
   title.setText(n);
   applyLevels(s);
   source.setText("数据来源 · "+s.source);
 }

 /** 左耳 / 右耳 两个图标与两个数值。读不到的一侧留空，绝不复制另一侧的值。 */
 private void applyLevels(BatteryState s){
   boolean haveLR = (s.left>=0)||(s.right>=0);
   lv.setText(s.left>=0 ? (s.left+"%") : "");
   rv.setText(s.right>=0 ? (s.right+"%") : "");
   if(s.caseLevel>=0) aggLine.setText("盒子 "+s.caseLevel+"%");
   else if(!haveLR && s.aggregate>=0) aggLine.setText("电量 "+s.aggregate+"%");
   else aggLine.setText("");
   battery.setText("");
   if(haveLR || s.aggregate>=0 || s.caseLevel>=0){
     stopFloat(); licon.setAlpha(1f); ricon.setAlpha(1f);
     hasValue=true; schedule();
   } else {
     hasValue=false; startFloat();
     if(dismissTask!=null){h.removeCallbacks(dismissTask);dismissTask=null;}
   }
 }

 /** 未读到电量时让耳机图标上下浮动；读到就停住并恢复原位。 */
 private void startFloat(){
   stopFloat();
   ObjectAnimator a=ObjectAnimator.ofFloat(licon,"translationY",0f,-dp(7));
   ObjectAnimator b=ObjectAnimator.ofFloat(ricon,"translationY",0f,-dp(7));
   a.setDuration(900);b.setDuration(900);
   a.setRepeatCount(ValueAnimator.INFINITE);b.setRepeatCount(ValueAnimator.INFINITE);
   a.setRepeatMode(ValueAnimator.REVERSE);b.setRepeatMode(ValueAnimator.REVERSE);
   b.setStartDelay(180);
   floatAnim=new AnimatorSet();floatAnim.playTogether(a,b);floatAnim.start();
 }
 private void stopFloat(){
   if(floatAnim!=null){try{floatAnim.cancel();}catch(Throwable ignored){}}floatAnim=null;
   if(licon!=null){licon.setTranslationY(0f);}
   if(ricon!=null){ricon.setTranslationY(0f);}
 }

 private void build(String n,BatteryState s,boolean mini,boolean test){
   wm=(WindowManager)c.getSystemService(Context.WINDOW_SERVICE);
   if(wm==null)return;
   int r=dp(mini?20:AppPrefs.radiusDp(c));
   FrameLayout card=new FrameLayout(c);
   card.setBackground(round(cardColor(),r));
   card.setElevation(dp(mini?10:14));
   card.setClipToOutline(true);
   card.setOutlineProvider(new ROutline(r));
   LinearLayout content=new LinearLayout(c);content.setOrientation(LinearLayout.VERTICAL);
   int pad=dp(mini?12:16);
   content.setPadding(pad,dp(mini?10:14),pad,dp(mini?10:14));

   if(!mini){
     int mr=Math.max(dp(18),r-dp(8));
     // 图片容器只负责把素材裁成圆角，不再铺一层半透明白底。
     FrameLayout mf=new FrameLayout(c);mf.setClipToOutline(true);mf.setOutlineProvider(new ROutline(mr));
     media=new ImageView(c);media.setScaleType(ImageView.ScaleType.CENTER_CROP);loadMedia();
     mf.addView(media,new FrameLayout.LayoutParams(-1,dp(250)));
     content.addView(mf,new LinearLayout.LayoutParams(-1,dp(250)));
   }

   FrameLayout row=new FrameLayout(c);
   title=txt(n,mini?16:22,true);title.setTextColor(Color.rgb(18,20,24));title.setMaxLines(2);title.setGravity(Gravity.CENTER_VERTICAL);
   FrameLayout.LayoutParams t=new FrameLayout.LayoutParams(-1,dp(mini?40:58));t.leftMargin=dp(6);t.rightMargin=test?dp(52):dp(6);
   row.addView(title,t);
   if(test){
     TextView close=txt("×",30,false);close.setTextColor(Color.rgb(35,36,40));close.setGravity(Gravity.CENTER);
     close.setBackground(round(Color.argb(55,0,0,0),dp(22)));close.setContentDescription("关闭");
     close.setOnClickListener(v->dismiss());
     FrameLayout.LayoutParams cp=new FrameLayout.LayoutParams(dp(44),dp(44),Gravity.RIGHT|Gravity.CENTER_VERTICAL);cp.rightMargin=dp(2);
     row.addView(close,cp);
   }
   content.addView(row,new LinearLayout.LayoutParams(-1,dp(mini?42:60)));

   // 左耳 / 右耳 图标 + 数值
   LinearLayout cells=new LinearLayout(c);cells.setOrientation(LinearLayout.HORIZONTAL);cells.setGravity(Gravity.CENTER);
   LinearLayout cellL=cell(true);licon=(ImageView)cellL.getChildAt(0);lv=(TextView)cellL.getChildAt(1);
   LinearLayout cellR=cell(false);ricon=(ImageView)cellR.getChildAt(0);rv=(TextView)cellR.getChildAt(1);
   LinearLayout.LayoutParams cw=new LinearLayout.LayoutParams(0,-2,1f);
   cells.addView(cellL,cw);cells.addView(cellR,cw);
   content.addView(cells,new LinearLayout.LayoutParams(-1,dp(mini?56:66)));

   aggLine=txt("",mini?15:17,true);aggLine.setTextColor(Color.rgb(28,30,34));aggLine.setGravity(Gravity.CENTER);
   content.addView(aggLine,new LinearLayout.LayoutParams(-1,dp(mini?26:34)));

   battery=txt("",11,false);content.addView(battery,new LinearLayout.LayoutParams(-1,0));

   if(!mini){
     source=txt("数据来源 · "+s.source,11,false);source.setTextColor(Color.rgb(100,103,110));source.setGravity(Gravity.CENTER);
     content.addView(source,new LinearLayout.LayoutParams(-1,dp(26)));
   }else{
     source=txt("",1,false);content.addView(source,new LinearLayout.LayoutParams(-1,0));
   }

   card.addView(content,new FrameLayout.LayoutParams(-1,-1));
   root=card;

   lp=new WindowManager.LayoutParams();
   lp.type=WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
   lp.format=PixelFormat.TRANSLUCENT;
   lp.gravity=Gravity.CENTER;
   // No FLAG_DIM_BEHIND: 那是一层铺满窗口的直角矩形压暗，会把卡片圆角外的两个角落涂黑。
   lp.flags=WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN|WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
   lp.dimAmount=0f;
   apply(mini);
   applyBlur();
   try{ wm.addView(root,lp); }catch(Throwable e){ root=null; lp=null; return; }
   applyLevels(s);
 }

 private LinearLayout cell(boolean left){
   LinearLayout l=new LinearLayout(c);l.setOrientation(LinearLayout.VERTICAL);l.setGravity(Gravity.CENTER);
   ImageView iv=new ImageView(c);
   iv.setImageResource(left?R.drawable.ic_earbud_left:R.drawable.ic_earbud_right);
   iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
   iv.setAdjustViewBounds(true);
   l.addView(iv,new LinearLayout.LayoutParams(dp(miniMode?26:38),dp(miniMode?44:64)));
   TextView t=txt("",miniMode?14:16,true);t.setTextColor(Color.rgb(28,30,34));t.setGravity(Gravity.CENTER);
   l.addView(t,new LinearLayout.LayoutParams(-2,-2));
   return l;
 }

 /** Dim 作用在卡片自己的颜色上，永远被圆角裁住，不可能溢出成直角。 */
 private int cardColor(){
   int p=AppPrefs.dimPercent(c);
   if(p<0)p=0; if(p>100)p=100;
   int base=248;
   int v=base-((base-52)*p)/100;
   return Color.argb(218,v,Math.min(250,v+2),Math.min(252,v+4));
 }

 private void applyBlur(){
  if(lp==null)return;
  if(Build.VERSION.SDK_INT>=31){
   try{
    if(wm!=null&&wm.isCrossWindowBlurEnabled()&&(AppPrefs.blurDp(c)>0||miniMode)){
     lp.setBlurBehindRadius(dp(miniMode?Math.max(16,AppPrefs.blurDp(c)):AppPrefs.blurDp(c)));
     lp.flags|=WindowManager.LayoutParams.FLAG_BLUR_BEHIND;
    }else{
     lp.setBlurBehindRadius(0);
     lp.flags&=~WindowManager.LayoutParams.FLAG_BLUR_BEHIND;
    }
   }catch(Throwable ignored){}
  }
 }

 private void syncDrag(){
   if(root==null||lp==null)return;
   boolean want=AppPrefs.dragMode(c);
   if(want&&!dragging){
     root.setOnTouchListener((v,e)->{ if(lp==null)return true; switch(e.getActionMasked()){
       case MotionEvent.ACTION_DOWN:dx=e.getRawX();dy=e.getRawY();sx=lp.x;sy=lp.y;return true;
       case MotionEvent.ACTION_MOVE:lp.x=sx+Math.round(e.getRawX()-dx);lp.y=sy+Math.round(e.getRawY()-dy);try{if(wm!=null)wm.updateViewLayout(root,lp);}catch(Throwable ignored){}return true;
       case MotionEvent.ACTION_UP:AppPrefs.setX(c,lp.x);AppPrefs.setY(c,lp.y);return true;}
       return true;});
     dragging=true;
   }else if(!want&&dragging){root.setOnTouchListener(null);dragging=false;}
 }

 public void refreshLayout(){
   if(Looper.myLooper()!=Looper.getMainLooper()){h.post(this::refreshLayout);return;}
   if(root==null)return;
   apply(miniMode);syncDrag();
 }

 private void apply(boolean mini){
   if(lp==null||root==null)return;
   if(mini){
     lp.width=dp(300);
     lp.height=dp(140);
   }else{
     lp.width=(int)(c.getResources().getDisplayMetrics().widthPixels*(AppPrefs.widthPercent(c)/100f));
     lp.height=dp(AppPrefs.heightDp(c));
     lp.x=AppPrefs.x(c);lp.y=AppPrefs.y(c);
   }
   applyBlur();
   try{if(wm!=null)wm.updateViewLayout(root,lp);}catch(Throwable ignored){}
 }

 private void schedule(){
   if(dismissTask!=null)h.removeCallbacks(dismissTask);
   dismissTask=null;
   if(!hasValue){                   // 还没读到：等更久（25s 兜底），读到后会被重排成设定时长
     dismissTask=this::dismiss;
     h.postDelayed(dismissTask,25000L);
     return;
   }
   dismissTask=this::dismiss;
   long ms=AppPrefs.durationSec(c)*1000L;
   if(ms<1000L)ms=1000L;
   h.postDelayed(dismissTask,ms);
 }
 private void reschedule(){ if(root!=null) schedule(); }

 private void animateIn(){
   if(root==null)return;
   root.setAlpha(0f);root.setScaleX(.96f);root.setScaleY(.96f);
   AnimatorSet a=new AnimatorSet();
   a.playTogether(ObjectAnimator.ofFloat(root,"alpha",0,1),ObjectAnimator.ofFloat(root,"scaleX",.96f,1),ObjectAnimator.ofFloat(root,"scaleY",.96f,1));
   a.setDuration(200);a.setInterpolator(new android.view.animation.DecelerateInterpolator());a.start();
 }

 public void dismiss(){
   if(Looper.myLooper()!=Looper.getMainLooper()){h.post(this::dismiss);return;}
   if(dismissTask!=null){h.removeCallbacks(dismissTask);dismissTask=null;}
   stopFloat();
   if(wm!=null&&root!=null){try{wm.removeView(root);}catch(Throwable ignored){}}
   root=null;lp=null;dragging=false;shownForThisDevice=false;shownName=null;
 }

 private void loadMedia(){
   if(media==null)return;
   String raw=AppPrefs.media(c);
   if(raw==null){ media.setImageDrawable(null); return; }
   try{Uri u=Uri.parse(raw);
     if(Build.VERSION.SDK_INT>=28){
       ImageDecoder.Source src=ImageDecoder.createSource(c.getContentResolver(),u);
       Drawable d=ImageDecoder.decodeDrawable(src);
       media.setImageDrawable(d);
       if(d instanceof AnimatedImageDrawable)((AnimatedImageDrawable)d).start();
     }else media.setImageURI(u);
   }catch(Throwable ignored){}
 }

 private TextView txt(String s,float z,boolean b){TextView v=new TextView(c);v.setText(s);v.setTextSize(z);v.setTypeface(null,b?1:0);return v;}
 private GradientDrawable round(int color,int r){GradientDrawable g=new GradientDrawable();g.setColor(color);g.setCornerRadius(r);return g;}
 private int dp(int n){return (int)(n*c.getResources().getDisplayMetrics().density+.5f);}
 private static final class ROutline extends ViewOutlineProvider{final int r;ROutline(int x){r=x;}public void getOutline(View v,Outline o){o.setRoundRect(0,0,v.getWidth(),v.getHeight(),r);}}
}
