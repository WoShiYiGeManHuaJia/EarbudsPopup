package com.earpopupx;

import android.animation.*;import android.content.*;import android.graphics.*;import android.graphics.drawable.*;import android.net.Uri;import android.os.*;import android.provider.Settings;import android.view.*;import android.widget.*;import java.util.*;

public final class EarPopupWindow {
 private static EarPopupWindow singleton;
 public static synchronized EarPopupWindow shared(Context c){if(singleton==null)singleton=new EarPopupWindow(c);return singleton;}
 private final Context c;private final Handler h=new Handler(Looper.getMainLooper());private WindowManager wm;private View root;private WindowManager.LayoutParams lp;
 private TextView title,battery,source;private ImageView media;private LinearLayout gauges;
 private float dx,dy;private int sx,sy;private boolean dragging;private Runnable dismissTask;
 private AnimatorSet floatAnim;private boolean testMode;
 private final List<View> floatTargets=new ArrayList<View>();
 private EarPopupWindow(Context x){c=x.getApplicationContext();}

 public void show(String n,BatteryState s){ show(n,s,false); }

 public void show(String n,BatteryState s,boolean test){
   if(Looper.myLooper()!=Looper.getMainLooper()){h.post(()->show(n,s,test));return;}
   if(!Settings.canDrawOverlays(c))return;
   testMode=test;
   boolean landscape=AppPrefs.landscapeMode(c)==1 && isLandscape();
   if(root==null){ build(n,s,landscape); if(root==null)return; }
   else update(n,s);
   if(root==null||lp==null)return;
   apply(); syncDrag();
   root.setVisibility(View.VISIBLE);animateIn();schedule();
 }

 public void update(String n,BatteryState s){
   if(Looper.myLooper()!=Looper.getMainLooper()){h.post(()->update(n,s));return;}
   if(root==null){show(n,s,testMode);return;}
   title.setText(n);
   boolean landscape=AppPrefs.landscapeMode(c)==1 && isLandscape();
   if(landscape){
     battery.setText(landscapeText(s));
     stopFloat();   // 读到就定住，不再浮动
     return;
   }
   fillGauges(s);
   if(s.hasDetail()||s.aggregate>=0) stopFloat();
 }

 private boolean isLandscape(){
   try{ return c.getResources().getDisplayMetrics().widthPixels > c.getResources().getDisplayMetrics().heightPixels; }
   catch(Throwable t){ return false; }
 }

 private String landscapeText(BatteryState s){
   String v;
   if(s.hasDetail()){
     StringBuilder b=new StringBuilder();
     if(s.left>=0) b.append(s.left).append("%");
     if(s.right>=0){ if(b.length()>0) b.append(" / "); b.append(s.right).append("%"); }
     if(s.caseLevel>=0){ if(b.length()>0) b.append(" / "); b.append(s.caseLevel).append("%"); }
     v=b.toString();
   } else if(s.aggregate>=0) v=s.aggregate+"%";
   else v="…";
   return v;
 }

 // ------------------------------------------------------------------ build

 private void build(String n,BatteryState s,boolean landscape){
   wm=(WindowManager)c.getSystemService(Context.WINDOW_SERVICE);
   if(wm==null)return;
   int r=dp(landscape?18:AppPrefs.radiusDp(c));
   FrameLayout card=new FrameLayout(c);
   card.setBackground(round(cardColor(),r));
   // 不加 elevation：overlay 窗口上的阴影会被画成直角矩形，
   // 那就是"弹窗里面套着一个矩形"的来源，而且用户明确不要阴影。
   card.setElevation(0f);
   card.setClipToOutline(true);
   card.setOutlineProvider(new ROutline(r));

   if(landscape){ buildLandscape(card,n,s,r); }
   else { buildPortrait(card,n,s,r); }

   root=card;
   lp=new WindowManager.LayoutParams();
   lp.type=WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
   lp.format=PixelFormat.TRANSLUCENT;
   lp.gravity=Gravity.CENTER;
   // 绝不加 FLAG_BLUR_BEHIND：它把整块窗口矩形做成磨砂，
   // 会让"手机全局变模糊"，而且窗口不响应外部点击。
   // 也绝不加 FLAG_DIM_BEHIND：它是直角压暗，从圆角外露出黑角。
   lp.flags=WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
           |WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
           |WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
   lp.dimAmount=0f;
   try{ lp.setBlurBehindRadius(0); }catch(Throwable ignored){}
   try{ lp.flags &= ~WindowManager.LayoutParams.FLAG_BLUR_BEHIND; }catch(Throwable ignored){}
   try{ lp.flags &= ~WindowManager.LayoutParams.FLAG_DIM_BEHIND; }catch(Throwable ignored){}
   apply();
   try{ wm.addView(root,lp); }catch(Throwable e){ root=null; lp=null; }
 }

 /** 横屏小窗：只有名称 + 电量，不放耳机图标（第二张截图那种样式）。 */
 private void buildLandscape(FrameLayout card,String n,BatteryState s,int r){
   LinearLayout box=new LinearLayout(c);
   box.setOrientation(LinearLayout.VERTICAL);
   box.setPadding(dp(14),dp(10),dp(14),dp(10));
   title=txt(n,13,true);title.setTextColor(Color.rgb(40,42,48));title.setSingleLine(true);
   box.addView(title,new LinearLayout.LayoutParams(-1,-2));
   battery=txt(landscapeText(s),24,true);battery.setTextColor(Color.rgb(20,22,26));
   LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(-1,-2);bp.topMargin=dp(2);
   box.addView(battery,bp);
   card.addView(box,new FrameLayout.LayoutParams(-1,-1));
   media=null;gauges=null;source=null;
 }

 private void buildPortrait(FrameLayout card,String n,BatteryState s,int r){
   LinearLayout content=new LinearLayout(c);content.setOrientation(LinearLayout.VERTICAL);
   content.setPadding(dp(14),dp(12),dp(14),dp(12));
   int mr=Math.max(dp(16),r-dp(8));
   FrameLayout mf=new FrameLayout(c);mf.setClipToOutline(true);mf.setOutlineProvider(new ROutline(mr));
   media=new ImageView(c);media.setScaleType(ImageView.ScaleType.CENTER_CROP);loadMedia();
   mf.addView(media,new FrameLayout.LayoutParams(-1,-1));
   content.addView(mf,new LinearLayout.LayoutParams(-1,0,1f));

   // 标题行：只有名字，真实弹窗不创建 ×
   FrameLayout row=new FrameLayout(c);
   LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(-1,dp(40));rp.topMargin=dp(6);
   title=txt(n,17,true);title.setTextColor(Color.rgb(18,20,24));title.setMaxLines(1);
   title.setGravity(Gravity.CENTER_VERTICAL);
   FrameLayout.LayoutParams t=new FrameLayout.LayoutParams(-2,-1);t.leftMargin=dp(2);
   row.addView(title,t);
   if(testMode){
     TextView close=txt("×",22,false);close.setTextColor(Color.rgb(35,36,40));close.setGravity(Gravity.CENTER);
     close.setBackground(round(Color.argb(55,0,0,0),dp(20)));close.setOnClickListener(v->dismiss());
     FrameLayout.LayoutParams cp=new FrameLayout.LayoutParams(dp(36),dp(36),Gravity.RIGHT|Gravity.CENTER_VERTICAL);
     row.addView(close,cp);
   }
   source=txt(s.source==null?"":s.source,10,false);source.setTextColor(Color.rgb(120,123,130));
   source.setGravity(Gravity.CENTER_VERTICAL|Gravity.RIGHT);source.setSingleLine(true);
   FrameLayout.LayoutParams sp=new FrameLayout.LayoutParams(-2,-1,Gravity.RIGHT|Gravity.CENTER_VERTICAL);
   if(testMode) sp.rightMargin=dp(42);
   row.addView(source,sp);
   content.addView(row,rp);

   // 电量行：图标 + 数字并排，横向紧凑，不再占一整块
   gauges=new LinearLayout(c);gauges.setOrientation(LinearLayout.HORIZONTAL);
   gauges.setGravity(Gravity.CENTER);
   LinearLayout.LayoutParams gp=new LinearLayout.LayoutParams(-1,dp(52));gp.topMargin=dp(2);
   content.addView(gauges,gp);
   fillGauges(s);

   card.addView(content,new FrameLayout.LayoutParams(-1,-1));
 }

 /** 一个单元 = 图标在上、数字在下，图标旁边就是电量。 */
 private View gauge(Drawable icon,String text,boolean right){
   LinearLayout u=new LinearLayout(c);u.setOrientation(LinearLayout.HORIZONTAL);
   u.setGravity(Gravity.CENTER);u.setPadding(dp(8),0,dp(8),0);
   ImageView iv=new ImageView(c);iv.setImageDrawable(icon);
   int s=dp(22);u.addView(iv,new LinearLayout.LayoutParams(s,s));
   TextView tv=txt(text,15,true);tv.setTextColor(Color.rgb(28,30,34));
   tv.setGravity(Gravity.CENTER_VERTICAL);
   LinearLayout.LayoutParams tp=new LinearLayout.LayoutParams(-2,-2);tp.leftMargin=dp(6);
   u.addView(tv,tp);
   u.setTag(tv);
   return u;
 }

 private void fillGauges(BatteryState s){
   if(gauges==null)return;
   floatTargets.clear();
   gauges.removeAllViews();
   boolean any=s.left>=0||s.right>=0||s.caseLevel>=0;
   if(any){
     if(s.left>=0)  gauges.addView(gauge(new BudIcon(false),"左 "+s.left+"%",false),new LinearLayout.LayoutParams(-2,-1));
     if(s.right>=0) gauges.addView(gauge(new BudIcon(true),"右 "+s.right+"%",true),new LinearLayout.LayoutParams(-2,-1));
     if(s.caseLevel>=0) gauges.addView(gauge(new CaseIcon(),"盒 "+s.caseLevel+"%",false),new LinearLayout.LayoutParams(-2,-1));
     stopFloat();
     return;
   }
   if(s.aggregate>=0){
     gauges.addView(gauge(new BudIcon(false),"电量 "+s.aggregate+"%",false),new LinearLayout.LayoutParams(-2,-1));
     stopFloat();
     return;
   }
   // 还没读到：不显示任何"正在读取"文字，两个耳塞图标轻轻浮动
   View a=gauge(new BudIcon(false),"",false);
   View b=gauge(new BudIcon(true),"",true);
   gauges.addView(a,new LinearLayout.LayoutParams(-2,-1));
   gauges.addView(b,new LinearLayout.LayoutParams(-2,-1));
   floatTargets.add(a);floatTargets.add(b);
   startFloat();
 }

 // ------------------------------------------------------------- 浮动动画

 private void startFloat(){
   stopFloat();
   if(floatTargets.isEmpty())return;
   AnimatorSet set=new AnimatorSet();
   List<Animator> as=new ArrayList<Animator>();
   for(int i=0;i<floatTargets.size();i++){
     View v=floatTargets.get(i);
     ObjectAnimator o=ObjectAnimator.ofFloat(v,"translationY",0f,-dp(6),0f);
     o.setRepeatCount(ValueAnimator.INFINITE);
     o.setDuration(1400L);
     o.setStartDelay(i*180L);
     as.add(o);
   }
   set.playTogether(as);
   set.start();
   floatAnim=set;
 }

 private void stopFloat(){
   if(floatAnim!=null){ try{floatAnim.cancel();}catch(Throwable ignored){} floatAnim=null; }
   for(View v:floatTargets){ try{v.setTranslationY(0f);}catch(Throwable ignored){} }
 }

 // ------------------------------------------------------------------ misc

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
   // 横竖屏切换会改变版式，直接重建
   boolean landscape=AppPrefs.landscapeMode(c)==1 && isLandscape();
   dismiss();
   show("蓝牙耳机",BatteryState.unknown(""),testMode);
 }

 private void apply(){
   if(lp==null||root==null||wm==null)return;
   boolean landscape=AppPrefs.landscapeMode(c)==1 && isLandscape();
   DisplayMetrics dm=c.getResources().getDisplayMetrics();
   if(landscape){
     lp.width=(int)(Math.min(dm.widthPixels,dm.heightPixels)*0.42f);
     lp.height=dp(76);
   }else{
     lp.width=(int)(dm.widthPixels*(AppPrefs.widthPercent(c)/100f));
     lp.height=dp(AppPrefs.heightDp(c));
   }
   lp.x=AppPrefs.x(c);lp.y=AppPrefs.y(c);
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
   root.setAlpha(0f);root.setScaleX(.94f);root.setScaleY(.94f);
   AnimatorSet a=new AnimatorSet();
   a.playTogether(ObjectAnimator.ofFloat(root,"alpha",0,1),ObjectAnimator.ofFloat(root,"scaleX",.94f,1),ObjectAnimator.ofFloat(root,"scaleY",.94f,1));
   a.setDuration(220);a.setInterpolator(new android.view.animation.DecelerateInterpolator());a.start();
 }

 public void dismiss(){
   if(Looper.myLooper()!=Looper.getMainLooper()){h.post(this::dismiss);return;}
   stopFloat();
   if(dismissTask!=null){h.removeCallbacks(dismissTask);dismissTask=null;}
   if(wm!=null&&root!=null){try{wm.removeView(root);}catch(Throwable ignored){}}
   root=null;lp=null;dragging=false;floatTargets.clear();
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

 private int cardColor(){
   int p=AppPrefs.dimPercent(c);
   if(p<0)p=0; if(p>100)p=100;
   int base=248;
   int v=base-((base-52)*p)/100;
   return Color.argb(222,v,Math.min(250,v+2),Math.min(252,v+4));
 }

 private TextView txt(String s,float z,boolean b){TextView v=new TextView(c);v.setText(s);v.setTextSize(z);v.setTypeface(null,b?1:0);return v;}
 private GradientDrawable round(int color,int r){GradientDrawable g=new GradientDrawable();g.setColor(color);g.setCornerRadius(r);return g;}
 private int dp(int n){return (int)(n*c.getResources().getDisplayMetrics().density+.5f);}
 private static final class ROutline extends ViewOutlineProvider{final int r;ROutline(int x){r=x;}public void getOutline(View v,Outline o){o.setRoundRect(0,0,v.getWidth(),v.getHeight(),r);}}

 /** 耳塞图标：代码绘制，不依赖任何图片资源。 */
 private static final class BudIcon extends Drawable{
   private final boolean mirror;private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
   BudIcon(boolean m){mirror=m;}
   public void draw(Canvas cv){
     Rect b=getBounds();if(b.width()<=0||b.height()<=0)return;
     float w=b.width(),hh=b.height();
     p.setColor(Color.rgb(52,56,64));p.setStyle(Paint.Style.FILL);
     cv.save();
     if(mirror){ cv.scale(-1f,1f,b.left+w/2f,b.top+hh/2f); }
     // 头部（椭圆）
     float cx=b.left+w*0.5f, cy=b.top+hh*0.36f, rx=w*0.26f, ry=hh*0.30f;
     cv.drawOval(cx-rx,cy-ry,cx+rx,cy+ry,p);
     // 柄（圆角矩形）
     float lw=w*0.16f, lx=cx-lw/2f, ly=cy+ry*0.75f, lh=hh*0.36f;
     cv.drawRoundRect(lx,ly,lx+lw,ly+lh,lw/2f,lw/2f,p);
     cv.restore();
   }
   public void setAlpha(int a){p.setAlpha(a);}
   public void setColorFilter(ColorFilter f){p.setColorFilter(f);}
   public int getOpacity(){return PixelFormat.TRANSLUCENT;}
 }

 /** 充电盒图标。 */
 private static final class CaseIcon extends Drawable{
   private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
   public void draw(Canvas cv){
     Rect b=getBounds();if(b.width()<=0||b.height()<=0)return;
     float w=b.width(),hh=b.height();
     p.setColor(Color.rgb(52,56,64));p.setStyle(Paint.Style.FILL);
     cv.drawRoundRect(b.left+w*0.10f,b.top+hh*0.18f,b.left+w*0.90f,b.top+hh*0.82f,w*0.16f,w*0.16f,p);
   }
   public void setAlpha(int a){p.setAlpha(a);}
   public void setColorFilter(ColorFilter f){p.setColorFilter(f);}
   public int getOpacity(){return PixelFormat.TRANSLUCENT;}
 }
}
