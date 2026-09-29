package com.yuanbao.earbuds;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;
import android.widget.Toast;

/**
 * v1.3 新增：蓝牙 HCI 日志抓包助手。
 *
 * 【为什么要有这个】
 *   拿到「左耳 / 右耳 / 充电盒」三项真实电量的唯一可行路径，
 *   是抓一份手机与耳机之间的 HCI 日志（btsnoop_hci.log），
 *   按真实字节序列反推私有协议。
 *
 *   此前一直以为导出日志必须 Root 或 ADB，于是一直卡在第一步。
 *   调研后确认：小米 / 红米（含 HyperOS）自带工程日志入口 ——
 *   在拨号盘输入 *#*#5959#*#* 即可开始收集，再输一次停止，
 *   日志落在 MIUI/debug_log/ 目录下，全程不需要 Root，也不需要电脑。
 *
 * 【本类只做三件不越权的事】
 *   1. 把工程日志代码复制到剪贴板并打开拨号盘（ACTION_DIAL 无需任何权限）
 *   2. 打开系统开发者选项页（用户自己开「蓝牙 HCI 信息收集日志」）
 *   3. 用系统文件选择器把日志文件分享出去（SAF，不需要存储权限）
 *
 *   它不做、也做不到的事：直接读取 /data/misc/bluetooth/logs。
 *   那个目录普通 App 无权限，本类不去碰，也不假装能碰。
 */
public final class HciLogHelper {

    /** 挑选日志文件的请求码（MainActivity.onActivityResult 会转发到这里） */
    public static final int REQ_PICK_LOG = 9911;

    /** 小米 / 红米工程蓝牙日志拨号代码 */
    public static final String LOG_DIAL_CODE = "*#*#5959#*#*";

    private HciLogHelper() {
    }

    /**
     * 复制工程日志代码并打开拨号盘。
     *
     * 这里【故意】不把号码塞进 Intent 的 data：
     * tel: URI 里的 # 会被 Uri 解析成 fragment，编码后又会被拨号盘显示成
     * %23 之类的字面量，两种做法在不同 ROM 上都可能失效。
     * 复制到剪贴板 + 打开拨号盘让用户长按粘贴，是唯一在所有 ROM 上都成立的方式。
     */
    public static void startLogCapture(Context c) {
        copy(c, LOG_DIAL_CODE);
        try {
            Intent i = new Intent(Intent.ACTION_DIAL);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
        } catch (Exception ignored) {
            // 打不开拨号盘也不影响：代码已经在剪贴板里了
        }
        Toast.makeText(c,
                "已复制 " + LOG_DIAL_CODE + "，长按拨号盘粘贴并拨出即可开始；"
                        + "再输一次停止。全程不需要 Root。",
                Toast.LENGTH_LONG).show();
    }

    /** 打开系统开发者选项，用户自行开启「蓝牙 HCI 信息收集日志」 */
    public static void openDeveloperOptions(Context c) {
        try {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
            Toast.makeText(c,
                    "开启「蓝牙 HCI 信息收集日志」后，关掉再打开一次蓝牙才会生效",
                    Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(c, "无法打开开发者选项，请手动进入系统设置", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 用系统文件选择器挑选日志文件。
     * 走 SAF（ACTION_OPEN_DOCUMENT），不需要申请任何存储权限，
     * 在 Android 11+ 的分区存储下也能正常拿到可读 Uri。
     */
    public static void pickLogFile(Activity a) {
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            // 常见日志名（不同 ROM 支持程度不一，此处仅作排序提示，不保证过滤生效）
            i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                    "application/zip", "application/octet-stream", "*/*"});
            a.startActivityForResult(i, REQ_PICK_LOG);
        } catch (Exception e) {
            Toast.makeText(a, "无法打开文件选择器，可用系统文件管理器手动查找", Toast.LENGTH_SHORT).show();
        }
    }

    /** 把选中的日志文件分享出去（可发到自己电脑、网盘或微信） */
    public static void shareLogFile(Activity a, Uri uri) {
        if (a == null || uri == null) return;
        try {
            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType("application/octet-stream");
            share.putExtra(Intent.EXTRA_STREAM, uri);
            share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            a.startActivity(Intent.createChooser(share, "导出蓝牙 HCI 日志"));
        } catch (Exception e) {
            Toast.makeText(a, "分享失败：可在文件管理器里手动发送该文件", Toast.LENGTH_SHORT).show();
        }
    }

    private static void copy(Context c, String text) {
        try {
            ClipboardManager cm = (ClipboardManager) c.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null) return;
            cm.setPrimaryClip(ClipData.newPlainText("hci_code", text));
        } catch (Exception ignored) {
        }
    }
}
