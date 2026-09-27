package com.yuanbao.earbuds;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

import rikka.shizuku.Shizuku;

/**
 * Shizuku / Stellar 封装层。
 *
 * Stellar 内置 Shizuku 兼容层（ShizukuServiceIntercept），
 * 所以这里用标准 Shizuku API 即可，Stellar 会自动接管。
 *
 * 用途：让 App 自己执行 shell 命令，完成「授权自身 + 采集诊断数据」，
 * 用户不用再手动敲命令。
 */
public final class ShizukuHelper {

    /** MIUI / HyperOS 扩展 AppOps 的候选编号，用于自动探测「后台弹出界面」等权限 */
    private static final int[] CANDIDATE_OPS = {
            10021, 10022, 10023, 10024, 10025, 10026, 10027, 10028
    };

    public static final String PKG = "com.yuanbao.earbuds";

    private ShizukuHelper() {
    }

    /** Shizuku / Stellar 服务是否在运行 */
    public static boolean isServiceRunning() {
        try {
            return Shizuku.pingBinder();
        } catch (Throwable t) {
            return false;
        }
    }

    /** 是否已拿到 API 授权 */
    public static boolean hasPermission() {
        try {
            if (!Shizuku.pingBinder()) return false;
            if (Shizuku.isPreV11()) return false;
            return Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    public interface PermCallback {
        void onResult(boolean granted);
    }

    private static final int REQ_CODE = 20240;

    public static void requestPermission(PermCallback cb) {
        if (hasPermission()) {
            if (cb != null) cb.onResult(true);
            return;
        }
        try {
            Shizuku.addRequestPermissionResultListener(
                    new Shizuku.OnRequestPermissionResultListener() {
                        @Override
                        public void onRequestPermissionResult(int requestCode, int grantResult) {
                            boolean ok = grantResult == PackageManager.PERMISSION_GRANTED;
                            Shizuku.removeRequestPermissionResultListener(this);
                            if (cb != null) cb.onResult(ok);
                        }
                    });
            Shizuku.requestPermission(REQ_CODE);
        } catch (Throwable t) {
            if (cb != null) cb.onResult(false);
        }
    }

    /** 执行一条 shell 命令，返回合并后的 stdout+stderr */
    public static String exec(String cmd) {
        return exec(new String[]{"sh", "-c", cmd});
    }

    public static String exec(String[] argv) {
        if (!hasPermission()) return "ERR: 未获得 Shizuku 授权";
        Process p = null;
        try {
            p = Shizuku.newProcess(argv, null, null);
            final StringBuilder out = new StringBuilder();
            final StringBuilder err = new StringBuilder();

            Thread t1 = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(
                        new InputStreamReader(p.getInputStream()))) {
                    String l;
                    while ((l = r.readLine()) != null) {
                        out.append(l).append('\n');
                    }
                } catch (Exception ignored) {
                }
            });
            Thread t2 = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(
                        new InputStreamReader(p.getErrorStream()))) {
                    String l;
                    while ((l = r.readLine()) != null) {
                        err.append(l).append('\n');
                    }
                } catch (Exception ignored) {
                }
            });
            t1.start();
            t2.start();
            p.waitFor();
            t1.join(3000);
            t2.join(3000);

            StringBuilder res = new StringBuilder(out);
            if (err.length() > 0) res.append("[stderr] ").append(err);
            return res.toString().trim();
        } catch (Throwable t) {
            return "ERR: " + t.getMessage();
        } finally {
            if (p != null) {
                try {
                    p.destroy();
                } catch (Exception ignored) {
                }
            }
        }
    }

    /** 一次性执行多条命令，返回「命令 → 输出」的条目列表 */
    public static List<String[]> execBatch(String[] cmds) {
        List<String[]> res = new ArrayList<>();
        for (String c : cmds) {
            res.add(new String[]{c, exec(c)});
        }
        return res;
    }

    // ---------------------------------------------------------------
    // 具体的业务动作
    // ---------------------------------------------------------------

    /** 给本 App 授予基础权限 */
    public static String[] grantSelfCommands() {
        return new String[]{
                "appops set " + PKG + " SYSTEM_ALERT_WINDOW allow",
                "pm grant " + PKG + " android.permission.BLUETOOTH_CONNECT",
                "pm grant " + PKG + " android.permission.BLUETOOTH_SCAN",
                "pm grant " + PKG + " android.permission.POST_NOTIFICATIONS",
                "dumpsys deviceidle whitelist +" + PKG,
        };
    }

    /** 屏蔽小米原生快连弹窗（只禁悬浮窗，不影响蓝牙连接） */
    public static String[] blockMiuiCommands() {
        return new String[]{
                "appops set com.xiaomi.bluetooth SYSTEM_ALERT_WINDOW ignore",
        };
    }

    /**
     * 自动探测 MIUI 扩展 AppOps：逐个尝试 set allow，
     * 不报错的即为有效编号，会被记录下来供后续硬编码。
     */
    public static List<String[]> probeOps() {
        List<String[]> res = new ArrayList<>();
        for (int op : CANDIDATE_OPS) {
            String set = exec("appops set " + PKG + " " + op + " allow");
            String get = exec("appops get " + PKG + " " + op);
            boolean ok = !set.contains("Unknown") && !set.contains("unknown")
                    && !set.toLowerCase().contains("error");
            res.add(new String[]{String.valueOf(op), ok ? "OK" : "FAIL", set, get});
        }
        return res;
    }

    /** 采集系统信息，供定制硬编码使用 */
    public static String[] infoCommands() {
        return new String[]{
                "getprop ro.product.model",
                "getprop ro.product.device",
                "getprop ro.build.version.incremental",
                "getprop ro.build.version.sdk",
                "getprop ro.miui.ui.version.name",
                "pm path com.xiaomi.bluetooth",
                "pm path com.android.bluetooth",
                "appops get " + PKG,
                "wm size",
                "wm density",
        };
    }

    /** 检测手机上是否装了 Stellar / Shizuku */
    public static String detectManager(Context c) {
        StringBuilder sb = new StringBuilder();
        sb.append(checkPkg(c, "roro.stellar.manager", "Stellar"));
        sb.append(checkPkg(c, "moe.shizuku.privileged.api", "Shizuku"));
        return sb.toString();
    }

    private static String checkPkg(Context c, String pkg, String label) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                c.getPackageManager().getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0));
            } else {
                c.getPackageManager().getPackageInfo(pkg, 0);
            }
            return label + ": 已安装\n";
        } catch (PackageManager.NameNotFoundException e) {
            return label + ": 未安装\n";
        } catch (Throwable t) {
            return label + ": 未知\n";
        }
    }
}
