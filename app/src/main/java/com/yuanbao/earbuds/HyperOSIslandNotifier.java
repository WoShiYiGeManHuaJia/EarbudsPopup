package com.yuanbao.earbuds;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.bumptech.glide.Glide;
import com.bumptech.glide.request.target.CustomTarget;
import com.bumptech.glide.request.transition.Transition;

import org.json.JSONObject;

import java.io.InputStream;
import java.util.Locale;

/**
 * HyperOS 3 小米超级岛客户端接入层。
 *
 * 这里不创建 Overlay；而是按小米公开的岛通知协议，把普通 Notification
 * 附加 miui.focus.param / miui.focus.pics 后交给 HyperOS SystemUI 渲染。
 * 不支持超级岛、焦点权限关闭或用户选择 GIF 时，由 PopupService 自动回退
 * 到原有 Overlay/Activity 引擎。
 */
public final class HyperOSIslandNotifier {

    public static final int NOTIFICATION_ID = 42051;
    private static final String CHANNEL_ID = "earbuds_island";
    private static final String FOCUS_PARAM = "miui.focus.param";
    private static final String FOCUS_PICS = "miui.focus.pics";
    private static final String PIC_IMAGE = "miui.focus.pic_imageText";

    private HyperOSIslandNotifier() {}

    /** 0/未知=不支持；1=OS1；2=OS2；3=OS3。 */
    public static int protocolVersion(Context context) {
        try {
            return Settings.System.getInt(
                    context.getContentResolver(),
                    "notification_focus_protocol", 0);
        } catch (Throwable ignored) {
            return 0;
        }
    }

    public static boolean isHyperOS(Context context) {
        String manufacturer = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER.toLowerCase(Locale.ROOT);
        String brand = Build.BRAND == null ? "" : Build.BRAND.toLowerCase(Locale.ROOT);
        return manufacturer.contains("xiaomi") || brand.contains("xiaomi")
                || brand.contains("redmi") || brand.contains("poco");
    }

    public static boolean isSupported(Context context) {
        return isHyperOS(context) && protocolVersion(context) >= 2;
    }

    /** 小米公开的 content provider：查询当前应用是否有焦点通知/超级岛资格。 */
    public static boolean hasFocusPermission(Context context) {
        if (!isSupported(context)) return false;
        try {
            Uri uri = Uri.parse("content://miui.statusbar.notification.public");
            Bundle extras = new Bundle();
            extras.putString("package", context.getPackageName());
            Bundle result = context.getContentResolver().call(uri, "canShowFocus", null, extras);
            return result != null && result.getBoolean("canShowFocus", false);
        } catch (Throwable ignored) {
            // 某些 ROM 没暴露 provider 时，不阻塞普通通知/Overlay fallback。
            return false;
        }
    }

    public static boolean hasNotificationPermission(Context context) {
        if (Build.VERSION.SDK_INT < 33) return true;
        return ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * 尝试发布原生超级岛。成功只表示 NotificationManager 接收了通知；最终是否上岛由 HyperOS
     * 根据 ROM、用户焦点通知权限和模板支持情况决定。
     */
    public static boolean post(Context context, String name, BatteryLevels levels, Prefs prefs) {
        if (!isSupported(context) || !hasFocusPermission(context) || !hasNotificationPermission(context)) {
            return false;
        }
        try {
            ensureChannel(context);
            NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_stat_notify)
                    .setContentTitle(name == null ? "蓝牙耳机" : name)
                    .setContentText(buildContent(levels, prefs))
                    .setCategory(NotificationCompat.CATEGORY_EVENT)
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .setOngoing(false)
                    .setAutoCancel(true)
                    .setOnlyAlertOnce(true);

            Bundle extras = new Bundle();
            Bundle pics = new Bundle();
            Bitmap bitmap = loadStaticBitmap(context, prefs.imageUri());
            if (bitmap != null) {
                pics.putParcelable(PIC_IMAGE, android.graphics.drawable.Icon.createWithBitmap(bitmap));
                extras.putBundle(FOCUS_PICS, pics);
                builder.addExtras(extras);
            }

            JSONObject root = new JSONObject();
            JSONObject paramV2 = new JSONObject();
            paramV2.put("protocol", 1);
            paramV2.put("business", "earbuds");
            paramV2.put("enableFloat", true);
            paramV2.put("updatable", true);
            paramV2.put("ticker", name == null ? "耳机已连接" : name);
            paramV2.put("aodTitle", name == null ? "耳机已连接" : name);
            paramV2.put("param_island", buildIsland(levels, prefs, bitmap != null));
            paramV2.put("baseInfo", buildBaseInfo(name, levels, prefs));
            root.put("param_v2", paramV2);

            Notification notification = builder.build();
            notification.extras.putString(FOCUS_PARAM, root.toString());
            context.getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, notification);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** GIF/动态图片不能假设 HyperOS 模板会播放，所以只对静态资源走原生岛。 */
    public static boolean looksLikeGif(Context context, String uriString) {
        if (uriString == null || uriString.trim().isEmpty()) return false;
        try {
            Uri uri = Uri.parse(uriString);
            String type = context.getContentResolver().getType(uri);
            if (type != null && type.equalsIgnoreCase("image/gif")) return true;
            String s = uri.toString().toLowerCase(Locale.ROOT);
            return s.endsWith(".gif") || s.contains(".gif?");
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static void cancel(Context context) {
        try {
            NotificationManager nm = context.getSystemService(NotificationManager.class);
            if (nm != null) nm.cancel(NOTIFICATION_ID);
        } catch (Throwable ignored) {
        }
    }

    private static void ensureChannel(Context context) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        if (nm == null) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "耳机超级岛", NotificationManager.IMPORTANCE_DEFAULT);
        channel.setDescription("耳机连接与电量的 HyperOS 超级岛通知");
        channel.setShowBadge(false);
        nm.createNotificationChannel(channel);
    }

    private static JSONObject buildIsland(BatteryLevels l, Prefs prefs, boolean hasPic) throws Exception {
        JSONObject island = new JSONObject();
        island.put("islandProperty", 1);
        island.put("islandTimeout", Math.max(2, Math.round(prefs.durationMs() / 1000f)));

        JSONObject small = new JSONObject();
        if (hasPic) {
            JSONObject picInfo = new JSONObject();
            picInfo.put("type", 1);
            picInfo.put("pic", PIC_IMAGE);
            small.put("picInfo", picInfo);
        }
        island.put("smallIslandArea", small);

        JSONObject big = new JSONObject();
        if (hasPic) {
            JSONObject left = new JSONObject();
            left.put("type", 1);
            JSONObject picInfo = new JSONObject();
            picInfo.put("type", 1);
            picInfo.put("pic", PIC_IMAGE);
            left.put("picInfo", picInfo);
            JSONObject text = new JSONObject();
            text.put("frontTitle", "连接");
            text.put("title", l == null ? "已连接" : batteryTitle(l, prefs));
            text.put("content", l == null ? "耳机已连接" : batteryContent(l, prefs));
            text.put("useHighLight", true);
            left.put("miui.focus.paramtextInfo", text);
            big.put("imageTextInfoLeft", left);
        } else {
            JSONObject text = new JSONObject();
            text.put("frontTitle", "连接");
            text.put("title", l == null ? "已连接" : batteryTitle(l, prefs));
            text.put("content", l == null ? "耳机已连接" : batteryContent(l, prefs));
            text.put("useHighLight", true);
            big.put("imageTextInfoLeft", text);
        }
        island.put("bigIslandArea", big);
        return island;
    }

    private static JSONObject buildBaseInfo(String name, BatteryLevels l, Prefs prefs) throws Exception {
        JSONObject base = new JSONObject();
        base.put("title", name == null ? "耳机已连接" : name);
        base.put("content", l == null ? "已连接" : batteryContent(l, prefs));
        String color = prefs.accentColor();
        base.put("colorTitle", color == null || color.isEmpty() ? "#00E5A0" : color);
        base.put("type", 2);
        return base;
    }

    private static String batteryTitle(BatteryLevels l, Prefs prefs) {
        if (!prefs.showBattery()) return "已连接";
        if (BatteryLevels.valid(l.left) || BatteryLevels.valid(l.right)) {
            return String.format(Locale.getDefault(), "L %s%%  R %s%%", fmt(l.left), fmt(l.right));
        }
        if (BatteryLevels.valid(l.overall)) return l.overall + "%";
        return "已连接";
    }

    private static String batteryContent(BatteryLevels l, Prefs prefs) {
        if (!prefs.showBattery()) return "耳机已连接";
        StringBuilder b = new StringBuilder();
        if (BatteryLevels.valid(l.left)) b.append("左 ").append(l.left).append("%  ");
        if (BatteryLevels.valid(l.right)) b.append("右 ").append(l.right).append("%  ");
        if (prefs.showCaseBattery() && BatteryLevels.valid(l.caseBox)) b.append("盒 ").append(l.caseBox).append("%");
        String s = b.toString().trim();
        return s.isEmpty() ? "电量读取中" : s;
    }

    private static String fmt(int v) { return BatteryLevels.valid(v) ? String.valueOf(v) : "--"; }

    /**
     * 通知的正文文案。
     *
     * 整合包里 post() 调用了 buildContent(levels, prefs)，但文件本身没有
     * 定义这个方法 —— 编译直接报 cannot find symbol。这里补上。
     * 内容与 batteryContent 一致（左/右/盒三段电量）。
     */
    private static String buildContent(BatteryLevels levels, Prefs prefs) {
        if (levels == null) return "耳机已连接";
        return batteryContent(levels, prefs);
    }

    private static Bitmap loadStaticBitmap(Context context, String uriString) {
        if (uriString == null || uriString.trim().isEmpty()) return null;
        if (looksLikeGif(context, uriString)) return null;
        try (InputStream in = context.getContentResolver().openInputStream(Uri.parse(uriString))) {
            if (in == null) return null;
            Bitmap b = BitmapFactory.decodeStream(in);
            if (b == null) return null;
            int max = 512;
            if (b.getWidth() > max || b.getHeight() > max) {
                float scale = Math.min(max / (float)b.getWidth(), max / (float)b.getHeight());
                return Bitmap.createScaledBitmap(b, Math.max(1, Math.round(b.getWidth() * scale)),
                        Math.max(1, Math.round(b.getHeight() * scale)), true);
            }
            return b;
        } catch (Throwable ignored) {
            return null;
        }
    }
}
