package com.earpopupx;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

public final class AppPrefs {
    private static final String P="earpopupx";
    private static SharedPreferences p(Context c){return c.getSharedPreferences(P,Context.MODE_PRIVATE);}
    public static boolean enabled(Context c){return p(c).getBoolean("enabled",false);}
    public static void setEnabled(Context c,boolean v){p(c).edit().putBoolean("enabled",v).apply();}
    public static boolean dragMode(Context c){return p(c).getBoolean("drag",false);}
    public static void setDragMode(Context c,boolean v){p(c).edit().putBoolean("drag",v).apply();}
    public static int x(Context c){return p(c).getInt("x",0);} public static int y(Context c){return p(c).getInt("y",-120);}
    public static void setX(Context c,int v){p(c).edit().putInt("x",v).apply();} public static void setY(Context c,int v){p(c).edit().putInt("y",v).apply();}
    public static int widthPercent(Context c){return p(c).getInt("width",94);} public static void setWidthPercent(Context c,int v){p(c).edit().putInt("width",clamp(v,72,98)).apply();}
    public static int heightDp(Context c){return p(c).getInt("height",430);} public static void setHeightDp(Context c,int v){p(c).edit().putInt("height",clamp(v,280,680)).apply();}
    public static int radiusDp(Context c){return p(c).getInt("radius",34);} public static void setRadiusDp(Context c,int v){p(c).edit().putInt("radius",clamp(v,12,64)).apply();}
    public static int blurDp(Context c){return p(c).getInt("blur",34);} public static void setBlurDp(Context c,int v){p(c).edit().putInt("blur",clamp(v,0,80)).apply();}
    public static int dimPercent(Context c){return p(c).getInt("dim",10);} public static void setDimPercent(Context c,int v){p(c).edit().putInt("dim",clamp(v,0,40)).apply();}
    public static int durationSec(Context c){return p(c).getInt("duration",7);} public static void setDurationSec(Context c,int v){p(c).edit().putInt("duration",clamp(v,2,30)).apply();}
    public static String media(Context c){return p(c).getString("media",null);} public static void setMedia(Context c,Uri u){SharedPreferences.Editor e=p(c).edit(); if(u==null)e.remove("media");else e.putString("media",u.toString());e.apply();}
    public static void resetPopup(Context c){p(c).edit().putInt("x",0).putInt("y",-120).putInt("width",94).putInt("height",430).putInt("radius",34).putInt("blur",34).putInt("dim",10).putInt("duration",7).apply();}
    private static int clamp(int v,int a,int b){return Math.max(a,Math.min(b,v));}
}
