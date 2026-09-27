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

    /**
     * Shizuku API 13 起把 newProcess 改成了 private，
     * 这里用反射调用（社区通用做法），失败时降级到其它途径。
     */
    /**
     * 反射调用 Shizuku.newProcess。
     * 各版本签名不一致，这里依次尝试多种形式；
     * 之前 catch 分支里重复了与 try 完全相同的签名，
     * 一旦 NoSuchMethodException 必然再次抛出，等于没有回退——纯死代码。
     */
    private static Process newProcessViaReflection(String[] argv) throws Exception {
        Exception last = null;
        // 依次尝试：三参 (String[], String[], String) / 单参 (String[])
        Class<?>[][] sigs = new Class<?>[][]{
                {String[].class, String[].class, String.class},
                {String[].class},
        };
        for (Class<?>[] sig : sigs) {
            try {
                java.lang.reflect.Method m = Shizuku.class.getDeclaredMethod("newProcess", sig);
                m.setAccessible(true);
                Object[] args = new Object[sig.length];
                args[0] = argv;
                for (int i = 1; i < sig.length; i++) args[i] = null;
                return (Process) m.invoke(null, args);
            } catch (Exception e) {
                last = e;
            }
        }
        throw last != null ? last : new NoSuchMethodException("newProcess");
    }

    /** 执行一条 shell 命令，返回合并后的 stdout+stderr */
    public static String exec(String cmd) {
        return exec(new String[]{"sh", "-c", cmd});
    }

    public static String exec(String[] argv) {
        if (!hasPermission()) return "ERR: 未获得 Shizuku 授权";
        Process proc;
        final Process[] holder = new Process[1];
        try {
            proc = newProcessViaReflection(argv);
            holder[0] = proc;
            final Process p = proc;
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
            // 必须带超时：某些命令（如 dumpsys）可能挂起，
            // waitFor() 无超时会让整个单线程池永久卡死，表现为「一键设置一直转圈」
            boolean finished = p.waitFor(15, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                try {
                    p.destroyForcibly();
                } catch (Exception ignored) {
                }
                t1.join(1000);
                t2.join(1000);
                return (res(out, err) + "\n[超时] 命令执行超过 15 秒，已强制终止").trim();
            }
            t1.join(3000);
            t2.join(3000);

            StringBuilder res = new StringBuilder(out);
            if (err.length() > 0) res.append("[stderr] ").append(err);
            return res.toString().trim();
        } catch (Throwable t) {
            return "ERR: " + t.getMessage();
        } finally {
            if (holder[0] != null) {
                try {
                    holder[0].destroy();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static String res(StringBuilder out, StringBuilder err) {
        StringBuilder sb = new StringBuilder(out);
        if (err.length() > 0) sb.append("[stderr] ").append(err);
        return sb.toString();
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
    /**
     * 探测 MIUI 扩展权限编号，并在设置后回读真实值确认是否真的生效。
     * 小米会在系统重启、安全扫描或权限重置时把这些值改回 ignore，
     * 所以不能只看 set 是否报错，必须 get 回来验证。
     */
    public static List<String[]> probeOps() {
        List<String[]> res = new ArrayList<>();
        for (int op : CANDIDATE_OPS) {
            String set = exec("appops set " + PKG + " " + op + " allow");
            String get = exec("appops get " + PKG + " " + op);
            boolean cmdOk = !set.contains("Unknown") && !set.contains("unknown")
                    && !set.toLowerCase().contains("error");
            // 回读：只有真的出现 allow 才算成功
            boolean effective = get != null && get.toLowerCase().contains("allow");
            res.add(new String[]{String.valueOf(op),
                    cmdOk ? (effective ? "OK" : "命令通过但未生效") : "FAIL",
                    set, get});
        }
        return res;
    }

    /** 回读关键权限的真实状态，用于设置后验证 */
    public static String[] verifyKeyPerms() {
        return new String[]{
                "悬浮窗 SYSTEM_ALERT_WINDOW: "
                        + trim(exec("appops get " + PKG + " SYSTEM_ALERT_WINDOW")),
                "后台弹出界面 10021: " + trim(exec("appops get " + PKG + " 10021")),
                "锁屏显示 10023: " + trim(exec("appops get " + PKG + " 10023")),
                "锁屏显示 10024: " + trim(exec("appops get " + PKG + " 10024")),
        };
    }

    private static String trim(String s) {
        if (s == null) return "(无返回)";
        String t = s.trim().replace("\n", " ");
        return t.isEmpty() ? "(空)" : (t.length() > 90 ? t.substring(0, 90) : t);
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
