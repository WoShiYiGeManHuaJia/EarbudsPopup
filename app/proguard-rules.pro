# 压缩规则：只缩减第三方库，本 App 的代码一行不动。
#
# 这样做的理由：
#   本项目方法数已达 64811，距 dex 单文件 65536 上限只剩几百个名额，
#   dex 体积也被第三方库撑到 8.8MB。开启 R8 可以去掉没用到的库代码。
#   但为了不引入新变量，本 App 自身代码全部 keep —— 行为与压缩前完全一致。

# ===== 本 App 代码全部保留，不做任何混淆/移除 =====
-keep class com.yuanbao.earbuds.** { *; }
-keep interface com.yuanbao.earbuds.** { *; }

# 保留行号：崩溃日志里的堆栈才能定位到具体行
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ===== 四大组件（Manifest 声明的必须保留）=====
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider
-keep public class * extends android.app.Application

# ===== Glide =====
-keep public class * implements com.bumptech.glide.module.GlideModule
-keep class * extends com.bumptech.glide.module.AppGlideModule {
    <init>(...);
}
-keep public enum com.bumptech.glide.load.ImageHeaderParser$** {
    **[] $VALUES;
    public *;
}

# ===== Shizuku（含 AIDL / Binder）=====
-keep class rikka.shizuku.** { *; }
-keep interface rikka.shizuku.** { *; }
-keep class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}

# ===== AndroidX / Material =====
# 布局 XML 里引用的自定义控件必须有 <init>(Context, AttributeSet)
-keep class androidx.** { *; }
-keep class com.google.android.material.** { *; }
-dontwarn androidx.**
-dontwarn com.google.android.material.**

# ===== 反射：通过反射调用系统隐藏 API，不能移除 =====
-keepclassmembers class android.bluetooth.BluetoothDevice { *; }
-keepclassmembers class android.bluetooth.BluetoothAdapter { *; }

# ===== 通用兜底 =====
-dontwarn org.slf4j.**
-dontwarn com.bumptech.glide.load.resource.bitmap.**
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes InnerClasses
-keepattributes EnclosingMethod
