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

            // 看门狗模式。
            // 上一版错误地用了 p.waitFor(15, SECONDS)：Shizuku 返回的远程 Process
            // 不支持带超时的 waitFor，会直接抛 UnsupportedOperationException，
            // 表现为 "ERR: process hasn't exited"，整条 dumpsys 路径失效。
            // 正确做法是：主线程 waitFor() 不带超时，另起一个看门狗线程，
            // 超时后 destroy 进程，迫使 waitFor() 返回。
            final boolean[] timedOut = {false};
            Thread watchdog = new Thread(() -> {
                try {
                    Thread.sleep(15000);
                } catch (InterruptedException e) {
                    return;
                }
                timedOut[0] = true;
                try {
                    p.destroy();
                } catch (Exception ignored) {
                }
                try {
                    p.destroyForcibly();
                } catch (Exception ignored) {
                }
            });
            watchdog.setDaemon(true);
            watchdog.start();

            try {
                p.waitFor();          // 无超时，靠看门狗打断
            } catch (InterruptedException e) {
                timedOut[0] = true;
            }
            watchdog.interrupt();
            t1.join(3000);
            t2.join(3000);

            if (timedOut[0]) {
                return (res(out, err)
                        + "\n[超时] 命令执行超过 15 秒，已被看门狗终止").trim();
            }

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
                // 以下三项来自补丁包 adb_setup.sh：后台存活的关键。
                // 之前只做了 deviceidle 白名单，没有 AUTO_START / RUN_IN_BACKGROUND，
                // 系统在内存紧张或长时间后台时仍会回收服务 ——
                // 表现就是「用一阵子之后弹窗不弹了」。
                "appops set " + PKG + " AUTO_START allow",
                "appops set " + PKG + " RUN_IN_BACKGROUND allow",
                "appops set " + PKG + " START_FOREGROUND allow",
        };
    }

    /**
     * 系统蓝牙栈电量诊断。
     *
     * 来自补丁包的排查命令：直接看系统蓝牙栈里有没有这副耳机的电量记录。
     * 有值 → 一定能拿到真电量；空白 → 只能走 GATT，且大概率也拿不到。
     */
    public static String[] batteryDiagCommands() {
        return new String[]{
                "dumpsys bluetooth_manager | grep -iE 'Address:|BatteryLevel|mBatteryLevel'",
        };
    }

    /**
     * 屏蔽小米原生快连弹窗 —— 温和手段。
     *
     * 重要认知修正：小米快连弹窗不是悬浮窗，而是【系统进程启动的 Activity】。
     * 所以单禁 SYSTEM_ALERT_WINDOW 常常压不住它（实测印证：执行后弹窗照弹）。
     * 这里同时禁 MIUI 私有的「后台弹出界面」op（10021），
     * 它管的才是「后台启动界面」这类行为，比前者更对口。
     */
    public static String[] blockMiuiCommands() {
        return new String[]{
                "appops set com.xiaomi.bluetooth SYSTEM_ALERT_WINDOW ignore",
                // MIUI 扩展 op：后台弹出界面
                "appops set com.xiaomi.bluetooth 10021 ignore",
                // 部分版本快连弹窗挂在蓝牙扩展的另一个包上，一并处理
                "appops set com.milink.service SYSTEM_ALERT_WINDOW ignore",
        };
    }

    /**
     * 屏蔽 —— 强力手段：直接冻结小米蓝牙扩展组件。
     *
     * 这是目前第三方唯一可能真正压住快连弹窗的办法：
     * 弹窗的代码就在这个组件里，组件被禁用就没人能弹。
     *
     * 代价必须说清：
     *   · 小米「快连」（开盖即连、弹窗配对）会失效
     *   · 普通蓝牙配对与音频连接不受影响（那是 com.android.bluetooth 管的）
     *   · 想恢复就执行 restoreMiuiCommands()，或到系统设置里重新启用
     */
    public static String[] blockMiuiStrongCommands() {
        return new String[]{
                "pm disable-user --user 0 com.xiaomi.bluetooth",
        };
    }

    /** 恢复：重新启用小米蓝牙扩展 */
    public static String[] restoreMiuiCommands() {
        return new String[]{
                "pm enable com.xiaomi.bluetooth",
                "appops set com.xiaomi.bluetooth SYSTEM_ALERT_WINDOW allow",
                "appops set com.xiaomi.bluetooth 10021 allow",
        };
    }

    /**
     * 回读小米蓝牙扩展的真实状态，用来判断屏蔽到底生效没有。
     * 不能只看命令有没有报错 —— 必须读回来验证。
     */
    public static String[] verifyBlockState() {
        String disabled = exec("pm list packages -d com.xiaomi.bluetooth");
        boolean frozen = disabled != null && disabled.contains("com.xiaomi.bluetooth");
        String sa = trim(exec("appops get com.xiaomi.bluetooth SYSTEM_ALERT_WINDOW"));
        String bg = trim(exec("appops get com.xiaomi.bluetooth 10021"));
        return new String[]{
                "组件是否已被冻结: " + (frozen ? "是 ✓（弹窗应当已消失）" : "否 ✗（弹窗可能照弹）"),
                "  pm list packages -d → " + trim(disabled),
                "  SYSTEM_ALERT_WINDOW → " + sa,
                "  10021 后台弹出界面 → " + bg,
        };
    }

    /**
     * 列出小米蓝牙扩展里的组件，用来定位「快连弹窗」到底是哪个 Activity。
     *
     * 为什么要找具体组件：冻结整个 com.xiaomi.bluetooth 会连「开盖快连」
     * 一起干掉，代价偏大。如果能定位到弹窗那个 Activity，
     * 就能只禁它、保留蓝牙功能。
     *
     * dumpsys package 输出动辄几万行，这里只抽取含包名的组件行，
     * 并优先列出名字里带 popup / dialog / fastconnect / ui 的（弹窗嫌疑最大）。
     */
    public static String dumpMiuiComponents() {
        String out = exec("dumpsys package com.xiaomi.bluetooth");
        if (out == null || out.trim().isEmpty()) return "  (无输出)";
        String[] lines = out.split("\n");
        java.util.LinkedHashSet<String> all = new java.util.LinkedHashSet<>();
        java.util.LinkedHashSet<String> suspect = new java.util.LinkedHashSet<>();
        String lower = out.toLowerCase();
        for (String ln : lines) {
            String t = ln.trim();
            if (!t.contains("com.xiaomi.bluetooth")) continue;
            // 只保留组件声明行（形如 com.xiaomi.bluetooth/.xxxActivity）
            if (!t.contains("/")) continue;
            if (all.size() < 120) all.add(t);
            String tl = t.toLowerCase();
            if (tl.contains("popup") || tl.contains("dialog")
                    || tl.contains("fastconnect") || tl.contains("fast")
                    || tl.contains("ui") || tl.contains("activity")
                    || tl.contains("connect")) {
                if (suspect.size() < 40) suspect.add(t);
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append("  嫌疑最大（弹窗/连接/界面相关），共 ").append(suspect.size()).append(" 条：\n");
        for (String s : suspect) sb.append("    ").append(s).append('\n');
        sb.append("\n  全部组件行（最多120条）：\n");
        for (String s : all) sb.append("    ").append(s).append('\n');
        if (lower.contains("enabled=true")) sb.append("\n  (含 enabled 状态字段，可据此判断组件当前是否启用)\n");
        return sb.toString();
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
