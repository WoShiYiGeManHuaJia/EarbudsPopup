package com.yuanbao.earbuds;

import android.content.Context;
import android.os.Build;
import android.os.Looper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 崩溃捕获（v1.4.1 诊断用）。
 *
 * 发生未捕获异常时，系统默认直接杀进程 —— 用户只看到「闪退」，
 * 看不到任何原因，排查也就无从下手。这里接管默认处理器：
 *   ① 把完整堆栈写进本机文件
 *   ② 再交还给系统默认处理（保持原有行为，不改变系统逻辑）
 *
 * 日志只写在本机，不联网、不上传。用户可在设置页导出后自行查看或发送。
 */
public final class CrashGuard implements Thread.UncaughtExceptionHandler {

    private static final String FILE = "crash-log.txt";
    private static final long MAX_BYTES = 256 * 1024L;

    private final Context app;
    private final Thread.UncaughtExceptionHandler prev;

    private CrashGuard(Context c, Thread.UncaughtExceptionHandler p) {
        this.app = c.getApplicationContext();
        this.prev = p;
    }

    /** 安装崩溃捕获。重复调用无副作用。 */
    public static void install(Context c) {
        if (c == null) return;
        Thread.UncaughtExceptionHandler cur = Thread.getDefaultUncaughtExceptionHandler();
        if (cur instanceof CrashGuard) return;
        Thread.setDefaultUncaughtExceptionHandler(new CrashGuard(c, cur));
    }

    /** 崩溃日志文件（供设置页分享出去） */
    public static File logFile(Context c) {
        File dir = c.getExternalFilesDir(null);
        if (dir == null) dir = c.getFilesDir();
        return new File(dir, FILE);
    }

    @Override
    public void uncaughtException(Thread t, Throwable e) {
        try {
            write(t, e);
        } catch (Throwable ignored) {
        }
        try {
            if (prev != null) prev.uncaughtException(t, e);
        } catch (Throwable ignored) {
        }
    }

    private void write(Thread t, Throwable e) {
        try {
            File f = logFile(app);
            if (f.exists() && f.length() > MAX_BYTES) {
                f.delete();
            }
            StringBuilder sb = new StringBuilder();
            sb.append("========== 崩溃 ").append(stamp()).append(" ==========\n");
            sb.append("线程: ").append(t == null ? "?" : t.getName()).append("\n");
            sb.append("是否主线程: ")
                    .append(Looper.getMainLooper().getThread() == t).append("\n");
            sb.append("机型: ").append(Build.MANUFACTURER).append(" ")
                    .append(Build.MODEL).append("\n");
            sb.append("系统: ").append(Build.DISPLAY)
                    .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n\n");
            appendThrowable(sb, e, 0);
            sb.append("\n");

            FileOutputStream fos = new FileOutputStream(f, true);
            OutputStreamWriter w = new OutputStreamWriter(fos, "UTF-8");
            w.write(sb.toString());
            w.flush();
            try {
                fos.getFD().sync();
            } catch (Throwable ignored) {
            }
            w.close();
        } catch (Throwable ignored) {
        }
    }

    private static void appendThrowable(StringBuilder sb, Throwable e, int depth) {
        if (e == null || depth > 6) return;
        sb.append(e.getClass().getName())
                .append(": ")
                .append(e.getMessage() == null ? "(无消息)" : e.getMessage())
                .append("\n");
        StackTraceElement[] st = e.getStackTrace();
        int n = Math.min(st == null ? 0 : st.length, 40);
        for (int i = 0; i < n; i++) {
            sb.append("    at ").append(st[i].toString()).append("\n");
        }
        Throwable cause = e.getCause();
        if (cause != null && cause != e) {
            sb.append("  引起: ");
            appendThrowable(sb, cause, depth + 1);
        }
    }

    private static String stamp() {
        try {
            return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
                    .format(new Date());
        } catch (Throwable ignored) {
            return String.valueOf(System.currentTimeMillis());
        }
    }
}
