package com.earpopupx;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Method;

/**
 * Shizuku / Stellar 封装（反射版）。
 *
 * 这里刻意不引入 rikka.shizuku 编译依赖：Stellar 内置了 Shizuku 兼容层，
 * 运行时如果它的类可用，就直接调用；不可用就降级提示，不影响其余功能。
 */
public final class ShizukuHelper {

    private ShizukuHelper() {
    }

    private static Class<?> cls() {
        try {
            return Class.forName("rikka.shizuku.Shizuku");
        } catch (Throwable t) {
            return null;
        }
    }

    public static boolean isRunning() {
        try {
            Class<?> c = cls();
            if (c == null) {
                return false;
            }
            Method m = c.getMethod("pingBinder");
            Object r = m.invoke(null);
            return r instanceof Boolean && (Boolean) r;
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean hasPermission() {
        try {
            Class<?> c = cls();
            if (c == null) {
                return false;
            }
            Method m = c.getMethod("checkSelfPermission");
            Object r = m.invoke(null);
            return r instanceof Integer && ((Integer) r) == 0;
        } catch (Throwable t) {
            return false;
        }
    }

    public static void requestPermission(int code) {
        try {
            Class<?> c = cls();
            if (c == null) {
                return;
            }
            Method m = c.getMethod("requestPermission", int.class);
            m.invoke(null, code);
        } catch (Throwable t) {
            // ignore
        }
    }

    /** 以 adb 权限执行 shell 脚本；不可用时返回 null */
    public static String run(String script) {
        try {
            Class<?> c = cls();
            if (c == null) {
                return null;
            }
            Method m = c.getMethod("newProcess", String[].class, String[].class, String.class);
            Object proc = m.invoke(null, new String[]{"sh", "-c", script}, null, null);
            String out = readAll((InputStream) proc.getClass().getMethod("getInputStream").invoke(proc));
            String err = readAll((InputStream) proc.getClass().getMethod("getErrorStream").invoke(proc));
            try {
                proc.getClass().getMethod("waitFor").invoke(proc);
            } catch (Throwable t) {
                // ignore
            }
            if (err != null && err.length() > 0) {
                return out + "\n[stderr] " + err;
            }
            return out;
        } catch (Throwable t) {
            return null;
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
