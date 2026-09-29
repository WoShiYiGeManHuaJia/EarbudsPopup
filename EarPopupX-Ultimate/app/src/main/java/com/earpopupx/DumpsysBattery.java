package com.earpopupx;

import android.content.Context;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Left / right / case battery straight out of the bluetooth stack.
 *
 * Why this exists: the popup the user takes a screenshot of shows
 * 90 / 80 / 100, so those three numbers DO exist somewhere in the system.
 * They are not in the metadata a normal app can read (those fields are null),
 * but `dumpsys bluetooth_manager` prints them, and shell can see them.
 *
 * The dump looks like this:
 *
 *   48:73:CB:63:8E:3A last_active_time=0 {profile connection policy(...),
 *   ... custom metadata(manufacturer_name=null| ... |untethered_left_battery=90|
 *   untethered_right_battery=80|untethered_case_battery=100|
 *   untethered_left_charging=false| ...), hfp client audio policy(...)}
 *
 * So: find the line that starts with our MAC, then read the fields out of it.
 * Only numbers actually printed by the stack are returned. If a field is
 * absent or -1 we report -1, never a guess.
 */
public final class DumpsysBattery {

    public interface Log { void onLine(String s); }

    private static final Pattern LEFT  = Pattern.compile("untethered_left_battery=(-?\\d+)");
    private static final Pattern RIGHT = Pattern.compile("untethered_right_battery=(-?\\d+)");
    private static final Pattern CASE  = Pattern.compile("untethered_case_battery=(-?\\d+)");
    private static final Pattern MAIN  = Pattern.compile("main_battery=(-?\\d+)");
    private static final Pattern LC    = Pattern.compile("untethered_left_charging=(true|false)");
    private static final Pattern RC    = Pattern.compile("untethered_right_charging=(true|false)");
    private static final Pattern CC    = Pattern.compile("untethered_case_charging=(true|false)");

    /** Parsed result. -1 means "the stack did not print this field". */
    public static final class Result {
        public int left = -1, right = -1, caseLevel = -1, main = -1;
        public boolean leftCharging, rightCharging, caseCharging;
        public boolean found;

        public boolean hasAny() { return left >= 0 || right >= 0 || caseLevel >= 0; }

        public BatteryState toState(String source) {
            int agg = main >= 0 ? main : -1;
            if (agg < 0 && left >= 0 && right >= 0) agg = (left + right) / 2;
            return new BatteryState(agg, left, right, caseLevel,
                    leftCharging, rightCharging, caseCharging, source);
        }

        @Override public String toString() {
            return String.format(Locale.US,
                    "L=%d R=%d C=%d main=%d charge=%b/%b/%b",
                    left, right, caseLevel, main, leftCharging, rightCharging, caseCharging);
        }
    }

    /**
     * Runs `dumpsys bluetooth_manager` and reads the block for `address`.
     * Tries shell (Shizuku/Stellar) first, then the app's own process as a
     * fallback - on some ROMs the plain process can still read it.
     */
    public static Result read(Context c, String address, Log log) {
        Result r = new Result();
        if (address == null) return r;

        String dump = runShell();
        if (isEmpty(dump)) dump = runLocal();

        if (isEmpty(dump)) {
            if (log != null) log.onLine("[dumpsys] 无输出（需要 shell 权限）");
            return r;
        }

        String block = blockFor(dump, address);
        if (block == null) {
            if (log != null) log.onLine("[dumpsys] 未找到该 MAC 的段落");
            return r;
        }

        r.found = true;
        r.left  = grab(LEFT, block);
        r.right = grab(RIGHT, block);
        r.caseLevel = grab(CASE, block);
        r.main  = grab(MAIN, block);
        r.leftCharging  = "true".equals(grabStr(LC, block));
        r.rightCharging = "true".equals(grabStr(RC, block));
        r.caseCharging  = "true".equals(grabStr(CC, block));

        if (log != null) log.onLine("[dumpsys] " + r.toString());
        return r;
    }

    /** The block for one device: from the line starting with the MAC up to the
     *  next device line. Device lines look like "  XX:XX:XX:XX:XX:XX last_active_time=". */
    private static String blockFor(String dump, String address) {
        String[] lines = dump.split("\n");
        StringBuilder sb = null;
        String mac = address.toUpperCase(Locale.US);
        for (String ln : lines) {
            String t = ln.trim();
            if (t.toUpperCase(Locale.US).startsWith(mac)) {
                sb = new StringBuilder();
                sb.append(ln).append('\n');
                continue;
            }
            if (sb != null) {
                // next device line ends the block
                if (t.matches("^[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}\\s+last_active_time=.*")) break;
                if (t.startsWith("Metadata:")) continue;
                sb.append(ln).append('\n');
            }
        }
        return sb == null ? null : sb.toString();
    }

    private static String runShell() {
        try {
            return ShizukuHelper.run("dumpsys bluetooth_manager");
        } catch (Throwable t) {
            return null;
        }
    }

    private static String runLocal() {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"dumpsys", "bluetooth_manager"});
            java.io.InputStream in = p.getInputStream();
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            try { in.close(); } catch (Throwable ignored) {}
            try { p.destroy(); } catch (Throwable ignored) {}
            return new String(bo.toByteArray(), "UTF-8");
        } catch (Throwable t) {
            return null;
        }
    }

    private static int grab(Pattern p, String s) {
        Matcher m = p.matcher(s);
        if (!m.find()) return -1;
        try {
            int v = Integer.parseInt(m.group(1));
            if (v < 0 || v > 100) return -1;
            return v;
        } catch (Throwable t) {
            return -1;
        }
    }

    private static String grabStr(Pattern p, String s) {
        Matcher m = p.matcher(s);
        return m.find() ? m.group(1) : null;
    }

    private static boolean isEmpty(String s) { return s == null || s.trim().isEmpty(); }
}
