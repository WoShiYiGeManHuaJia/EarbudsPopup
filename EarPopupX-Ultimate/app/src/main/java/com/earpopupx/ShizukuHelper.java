package com.earpopupx;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;

import rikka.shizuku.Shizuku;
import rikka.shizuku.ShizukuRemoteProcess;

/**
 * Shizuku / Stellar 封装。
 *
 * API 已内置在工程里（rikka.shizuku.* + moe.shizuku.server.*），
 * 不再走反射，因此 Stellar（基于 Shizuku，完整兼容其 API）可直接识别并授权本应用。
 */
public final class ShizukuHelper {

    private static final String TAG = "ShizukuHelper";

    /** 收到 binder 说明 Shizuku/Stellar 服务已在运行 */
    public static boolean isServiceRunning() {
        try {
            return Shizuku.pingBinder();
        } catch (Throwable t) {
            return false;
        }
    }

    /** binder 已到，但用户可能还没对本应用授权 */
    public static boolean hasPermission() {
        try {
            return Shizuku.checkSelfPermission() == 0;
        } catch (Throwable t) {
            return false;
        }
    }

    public static void requestPermission() {
        try {
            Shizuku.requestPermission(1001);
        } catch (Throwable t) {
            Log.w(TAG, "requestPermission", t);
        }
    }

    public static String serverInfo() {
        try {
            return "v" + Shizuku.getServerApiVersion() + "." + Shizuku.getServerPatchVersion()
                    + " uid=" + Shizuku.getServerUid();
        } catch (Throwable t) {
            return "unknown";
        }
    }

    /** 以 adb 权限执行一段 shell；返回合并后的输出，失败返回 null */
    public static String run(String script) {
        ShizukuRemoteProcess process = null;
        try {
            process = Shizuku.newProcess(new String[]{"sh", "-c", script}, null, null);
            if (process == null) {
                return null;
            }
            String out = readAll(process.getInputStream());
            String err = readAll(process.getErrorStream());
            try {
                process.waitFor();
            } catch (Throwable t) {
                // ignore
            }
            if (err != null && err.trim().length() > 0) {
                return out + "\n[stderr] " + err.trim();
            }
            return out == null ? "" : out;
        } catch (Throwable t) {
            Log.w(TAG, "run", t);
            return null;
        } finally {
            if (process != null) {
                try {
                    process.destroy();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** 逐条执行，返回每条命令与其结果 */
    public static String runAll(Context context, String[] cmds) {
        StringBuilder sb = new StringBuilder();
        for (String c : cmds) {
            String r = run(c);
            sb.append("$ ").append(c).append('\n');
            if (r == null) {
                sb.append("  <执行失败>\n");
            } else {
                String t = r.trim();
                sb.append(t.isEmpty() ? "  ok\n" : "  ").append(t).append('\n');
            }
        }
        return sb.toString();
    }

    private static String readAll(InputStream in) {
        if (in == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(in));
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line).append('\n');
            }
            r.close();
        } catch (Throwable t) {
            return sb.toString();
        }
        return sb.toString();
    }

    private ShizukuHelper() {
    }
}
