package com.earpopupx;

import android.animation.*;import android.content.*;import android.graphics.*;import android.graphics.drawable.*;import android.net.Uri;import android.os.*;import android.provider.Settings;import android.view.*;import android.widget.*;import java.util.*;

public final class EarPopupWindow {
 private static EarPopupWindow singleton;
 public static synchronized EarPopupWindow shared(Context c){if(singleton==null)singleton=new EarPopupWindow(c);return singleton;}
 private final Context c;private final Handler h=new Handler(Looper.getMainLooper());private WindowManager wm;private View root;private WindowManager.LayoutParams lp;private TextView title,battery,source;private ImageView media;private float dx,dy;private int sx,sy;private boolean dragging;private Runnable dismissTask;
 private EarPopupWindow(Context x){c=x.getApplicationContext();}

 public void show(String n,BatteryState s){
   if(Looper.myLooper()!=Looper.getMainLooper()){h.post(()->show(n,s));return;}
   if(!Settings.canDrawOverlays(c))return;
   if(root==null){ build(n,s); if(root==null)return; }
   else update(n,s);
   apply(); syncDrag();
   // build() can fail (overlay denied, bad layout params). Bail instead of
   // dereferencing a null root - that was the crash on test popup.
   if(root==null||lp==null)return;
   root.setVisibility(View.VISIBLE);animateIn();schedule();
 }

 public void update(String n,BatteryState s){if(Looper.myLooper()!=Looper.getMainLooper()){h.post(()->update(n,s));return;}if(root==null){show(n,s);return;}title.setText(n);battery.setText(format(s));source.setText("数据来源 · "+s.source);}

 private void build(String n,BatteryState s){
   wm=(WindowManager)c.getSystemService(Context.WINDOW_SERVICE);
   if(wm==null)return;
   int r=dp(AppPrefs.radiusDp(c));
   FrameLayout card=new FrameLayout(c);
   card.setBackground(round(cardColor(),r));
   card.setElevation(dp(22));
   card.setClipToOutline(true);
   card.setOutlineProvider(new ROutline(r));
   LinearLayout content=new LinearLayout(c);content.setOrientation(LinearLayout.VERTICAL);content.setPadding(dp(16),dp(14),dp(16),dp(14));
   int mr=Math.max(dp(18),r-dp(8));
   FrameLayout mf=new FrameLayout(c);mf.setBackground(round(Color.argb(70,255,255,255),mr));mf.setClipToOutline(true);mf.setOutlineProvider(new ROutline(mr));media=new ImageView(c);media.setScaleType(ImageView.ScaleType.CENTER_CROP);loadMedia();mf.addView(media,new FrameLayout.LayoutParams(-1,dp(250)));content.addView(mf,new LinearLayout.LayoutParams(-1,dp(250)));
   FrameLayout row=new FrameLayout(c);title=txt(n,22,true);title.setTextColor(Color.rgb(18,20,24));title.setMaxLines(2);title.setGravity(Gravity.CENTER_VERTICAL);FrameLayout.LayoutParams t=new FrameLayout.LayoutParams(-1,dp(58));t.leftMargin=dp(6);t.rightMargin=dp(52);row.addView(title,t);
   TextView close=txt("×",30,false);close.setTextColor(Color.rgb(35,36,40));close.setGravity(Gravity.CENTER);close.setBackground(round(Color.argb(55,0,0,0),dp(22)));close.setContentDescription("关闭");close.setOnClickListener(v->dismiss());FrameLayout.LayoutParams cp=new FrameLayout.LayoutParams(dp(44),dp(44),Gravity.RIGHT|Gravity.CENTER_VERTICAL);cp.rightMargin=dp(2);row.addView(close,cp);content.addView(row,new LinearLayout.LayoutParams(-1,dp(60)));
   battery=txt(format(s),18,true);battery.setTextColor(Color.rgb(28,30,34));battery.setGravity(Gravity.CENTER);content.addView(battery,new LinearLayout.LayoutParams(-1,dp(62)));
   source=txt("数据来源 · "+s.source,11,false);source.setTextColor(Color.rgb(100,103,110));source.setGravity(Gravity.CENTER);content.addView(source,new LinearLayout.LayoutParams(-1,dp(26)));
   card.addView(content,new FrameLayout.LayoutParams(-1,-1));
   root=card;

   lp=new WindowManager.LayoutParams();
   lp.type=WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
   lp.format=PixelFormat.TRANSLUCENT;
   lp.gravity=Gravity.CENTER;
   // No FLAG_DIM_BEHIND: it paints a full-window rectangular dim that shows
   // through outside the card's rounded corners as two black right angles.
   lp.flags=WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN|WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
   lp.dimAmount=0f;
   apply();
   applyBlur();
   try{ wm.addView(root,lp); }catch(Throwable e){ root=null; lp=null; }
 }

 /** Dim is applied to the card itself so it can never escape the rounded shape. */
 private int cardColor(){
   int p=AppPrefs.dimPercent(c);
   if(p<0)p=0; if(p>100)p=100;
   int base=248;
   int v=base-((base-52)*p)/100;
   return Color.argb(218,v,Math.min(250,v+2),Math.min(252,v+4));
 }

 private void applyBlur(){if(Build.VERSION.SDK_INT>=31){try{if(wm!=null&&wm.isCrossWindowBlurEnabled()&&AppPrefs.blurDp(c)>0){lp.setBlurBehindRadius(dp(AppPrefs.blurDp(c)));lp.flags|=WindowManager.LayoutParams.FLAG_BLUR_BEHIND;}}catch(Throwable ignored){}}}

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

 public void refreshLayout(){if(Looper.myLooper()!=Looper.getMainLooper()){h.post(this::refreshLayout);return;}if(root==null)return;apply();syncDrag();}

 private void apply(){
   if(lp==null||root==null||wm==null)return;
   lp.width=(int)(ResourcesHelper.width(c)*(AppPrefs.widthPercent(c)/100f));
   lp.height=dp(AppPrefs.heightDp(c));
   lp.x=AppPrefs.x(c);lp.y=AppPrefs.y(c);
   applyBlur();
   try{wm.updateViewLayout(root,lp);}catch(Throwable ignored){}
 }

 private void schedule(){
   if(dismissTask!=null)h.removeCallbacks(dismissTask);
   dismissTask=this::dismiss;
   long ms=AppPrefs.durationSec(c)*1000L;
   if(ms<1000L)ms=1000L;
   h.postDelayed(dismissTask,ms);
 }

 private void animateIn(){
   if(root==null)return;
   root.setAlpha(0f);root.setScaleX(.93f);root.setScaleY(.93f);
   AnimatorSet a=new AnimatorSet();
   a.playTogether(ObjectAnimator.ofFloat(root,"alpha",0,1),ObjectAnimator.ofFloat(root,"scaleX",.93f,1),ObjectAnimator.ofFloat(root,"scaleY",.93f,1));
   a.setDuration(240);a.setInterpolator(new android.view.animation.DecelerateInterpolator());a.start();
 }

 public void dismiss(){
   if(Looper.myLooper()!=Looper.getMainLooper()){h.post(this::dismiss);return;}
   if(dismissTask!=null){h.removeCallbacks(dismissTask);dismissTask=null;}
   if(wm!=null&&root!=null){try{wm.removeView(root);}catch(Throwable ignored){}}
   root=null;lp=null;dragging=false;
 }

 private void loadMedia(){
   if(media==null)return;
   String raw=AppPrefs.media(c);if(raw==null)return;
   try{Uri u=Uri.parse(raw);
     if(Build.VERSION.SDK_INT>=28){
       ImageDecoder.Source src=ImageDecoder.createSource(c.getContentResolver(),u);
       Drawable d=ImageDecoder.decodeDrawable(src);
       media.setImageDrawable(d);
       if(d instanceof AnimatedImageDrawable)((AnimatedImageDrawable)d).start();
     }else media.setImageURI(u);
   }catch(Throwable ignored){}
 }

 private String format(BatteryState s){
   if(s.left>=0||s.right>=0||s.caseLevel>=0)
     return (s.left>=0?"左耳 "+s.left+"%":"左耳 —")+"   "+(s.right>=0?"右耳 "+s.right+"%":"右耳 —")+"   "+(s.caseLevel>=0?"盒子 "+s.caseLevel+"%":"盒子 —");
   return s.aggregate>=0?"电量  "+s.aggregate+"%":"正在读取电量…";
 }

 private TextView txt(String s,float z,boolean b){TextView v=new TextView(c);v.setText(s);v.setTextSize(z);v.setTypeface(null,b?1:0);return v;}
 private GradientDrawable round(int color,int r){GradientDrawable g=new GradientDrawable();g.setColor(color);g.setCornerRadius(r);return g;}
 private int dp(int n){return (int)(n*c.getResources().getDisplayMetrics().density+.5f);}
 private static final class ROutline extends ViewOutlineProvider{final int r;ROutline(int x){r=x;}public void getOutline(View v,Outline o){o.setRoundRect(0,0,v.getWidth(),v.getHeight(),r);}}
 private static final class ResourcesHelper{static int width(Context c){return c.getResources().getDisplayMetrics().widthPixels;}}
}
