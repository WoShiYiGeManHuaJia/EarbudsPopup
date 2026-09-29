package com.earpopupx;

import android.content.pm.PackageManager;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;

import rikka.shizuku.Shizuku;
import rikka.shizuku.ShizukuRemoteProcess;

/**
 * Shizuku / Stellar 封装。
 *
 * Stellar 内置 Shizuku 兼容层（ShizukuServiceIntercept），所以这里用标准
 * Shizuku API 即可，Stellar 会自动接管。用途：让 App 自己把那几条授权命令
 * 跑掉，用户不用再复制粘贴。
 */
public final class ShizukuHelper {

    private ShizukuHelper() {
    }

    /** Shizuku / Stellar 服务是否在运行 */
    public static boolean isRunning() {
        try {
            return Shizuku.pingBinder();
        } catch (Throwable t) {
            return false;
        }
    }

    /** 是否已被授予 Shizuku 权限 */
    public static boolean hasPermission() {
        try {
            return Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 发起授权请求 */
    public static void requestPermission(int code) {
        try {
            Shizuku.requestPermission(code);
        } catch (Throwable t) {
            // ignore
        }
    }

    /** 以 adb 权限执行一段 shell 脚本，返回合并后的输出 */
    public static String run(String script) {
        try {
            ShizukuRemoteProcess proc =
                    Shizuku.newProcess(new String[]{"sh", "-c", script}, null, null);
            String out = readAll(proc.getInputStream());
            String err = readAll(proc.getErrorStream());
            try {
                proc.waitFor();
            } catch (Throwable t) {
                // ignore
            }
            if (err != null && err.length() > 0) {
                return out + "\n[stderr] " + err;
            }
            return out;
        } catch (Throwable t) {
            return "执行失败: " + t;
        }
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
            try {
                r.close();
            } catch (Throwable t) {
                // ignore
            }
        } catch (Throwable t) {
            return sb.toString();
        }
        return sb.toString();
    }
}
