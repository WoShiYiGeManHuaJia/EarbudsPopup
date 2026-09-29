package com.earpopupx;

import java.util.ArrayDeque;
import java.util.Deque;

/** 轻量运行日志，供设置页诊断区显示。只在内存里保留最近若干条。 */
public final class Diag {
    private static final int MAX = 60;
    private static final Deque<String> lines = new ArrayDeque<>();

    public static synchronized void log(String s) {
        try {
            java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.CHINA);
            lines.addLast(f.format(new java.util.Date()) + "  " + s);
            while (lines.size() > MAX) lines.removeFirst();
        } catch (Throwable ignored) {}
    }

    public static synchronized String dump() {
        if (lines.isEmpty()) return "（暂无日志，请先启动监听）";
        StringBuilder sb = new StringBuilder();
        for (String l : lines) sb.append(l).append('\n');
        return sb.toString().trim();
    }

    private Diag() {}
}
