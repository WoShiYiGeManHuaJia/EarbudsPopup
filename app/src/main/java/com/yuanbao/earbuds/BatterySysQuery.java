package com.yuanbao.earbuds;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从「系统蓝牙栈」读取权威电量。
 *
 * 为什么必须有这个类：
 *   之前电量全靠 BLE GATT 读 0x180F，但耳机通过经典蓝牙（A2DP）连着时，
 *   再开一路 BLE GATT 经常连不上或被系统拒绝；而反射调用
 *   BluetoothDevice.getBatteryLevel() 又是隐藏 API，Android 16 会直接拦掉（实测返回 -1）。
 *   结果就是：读不到真值，只能拿几小时前的旧缓存顶上——
 *   用户充完电重连，弹窗还显示上次的 30%，跟写死没区别。
 *
 * 这里的做法：借助 Shizuku / Stellar 执行
 *   dumpsys bluetooth_manager
 * 系统蓝牙栈自己维护着已连接设备的真实电量，这是免 root 下最权威的来源。
 *
 * dumpsys 输出格式各版本差异很大，所以这里做「宽松解析」：
 *   1. 定位 MAC 地址所在位置
 *   2. 取其后的一个文本窗口
 *   3. 在窗口内搜 battery 关键词附近的数字
 * 并把命中的原始片段原样保留，便于根据实际输出调整规则。
 */
public final class BatterySysQuery {

    /** 每次解析保留的证据片段，供诊断查看 */
    public static volatile String lastEvidence = "";

    private static final int WINDOW = 800;      // MAC 之后向后搜索的字符数
    private static final int WINDOW_BEFORE = 200; // MAC 之前向前搜索的字符数

    private BatterySysQuery() {
    }

    /**
     * 查询指定设备的电量。
     * @return 1~100 表示有效电量；-1 表示没解析出来
     */
    /**
     * 全文搜索模式：不依赖 MAC 定位，直接在整个 dumpsys 输出里
     * 找所有含 battery 的行。
     *
     * 为什么需要：MAC 定位命中的往往是 BLE 扫描统计（"Scan time in ms"、
     * "results (N) CB Regular Scan"），那只是"扫过这个地址"的记录，
     * 根本不含电量。所以按 MAC 附近搜索永远拿不到值。
     */
    public static String grepBattery(String service) {
        String out = ShizukuHelper.exec(
                "dumpsys " + service + " | grep -i -B2 -A2 battery");
        if (out == null || out.isEmpty()) return "";
        return out.length() > 3000 ? out.substring(0, 3000) : out;
    }

    public static int query(String mac) {
        if (mac == null || mac.isEmpty()) return -1;
        String out = ShizukuHelper.exec("dumpsys bluetooth_manager");
        if (out == null || out.isEmpty()) {
            lastEvidence = "dumpsys 无输出（Shizuku 未授权或服务未运行）";
            return -1;
        }
        if (out.startsWith("ERR:")) {
            lastEvidence = out;
            return -1;
        }
        return parse(out, mac);
    }

    static int parse(String dump, String mac) {
        String target = mac.toLowerCase(Locale.ROOT);
        String lower = dump.toLowerCase(Locale.ROOT);
        List<Integer> hits = new ArrayList<>();
        StringBuilder evidence = new StringBuilder();

        int from = 0;
        while (true) {
            int idx = lower.indexOf(target, from);
            if (idx < 0) break;
            int start = Math.max(0, idx - WINDOW_BEFORE);
            int end = Math.min(dump.length(), idx + mac.length() + WINDOW);
            String window = dump.substring(start, end);
            if (evidence.length() < 1200) {
                evidence.append("--- 命中 MAC @").append(idx).append(" ---\n")
                        .append(window).append("\n");
            }
            Integer v = batteryIn(window);
            if (v != null && BatteryLevels.valid(v)) hits.add(v);
            from = idx + mac.length();
            if (from >= lower.length()) break;
        }

        lastEvidence = evidence.length() > 0
                ? evidence.toString()
                : ("dumpsys 里没找到 MAC " + mac + "（输出长度 " + dump.length() + "）");

        if (hits.isEmpty()) return -1;
        // 多处命中时取中位数，避免把信号强度之类的数字误当电量
        return median(hits);
    }

    /** 在一段文本里找 battery 附近的数字 */
    static Integer batteryIn(String window) {
        if (window == null || window.isEmpty()) return null;
        String w = window.toLowerCase(Locale.ROOT);

        // 优先匹配 "battery" 关键词后紧跟的数字（允许中间有少量非数字字符）
        // 只用「battery 在前、数字在后」的正向模式。
        // 曾经加过一个反向模式（数字在前 battery 在后）意图兼容更多格式，
        // 但实测它会把 "rssi=-55 ... battery" 里的 55 误判成电量——必须删掉。
        Pattern[] pats = new Pattern[]{
                Pattern.compile("battery[^0-9\\n]{0,40}?([0-9]{1,3})"),
                Pattern.compile("battery\\s*level[^0-9\\n]{0,40}?([0-9]{1,3})"),
                Pattern.compile("batterylevel[^0-9\\n]{0,40}?([0-9]{1,3})"),
        };
        for (Pattern p : pats) {
            Matcher m = p.matcher(w);
            if (m.find()) {
                try {
                    int v = Integer.parseInt(m.group(1));
                    if (v >= 1 && v <= 100) return v;
                } catch (Exception ignored) {
                }
            }
        }
        return null;
    }

    private static int median(List<Integer> xs) {
        List<Integer> sorted = new ArrayList<>(xs);
        java.util.Collections.sort(sorted);
        return sorted.get(sorted.size() / 2);
    }
    /**
     * 从 bluetooth_manager 的完整 dump 中解析带明确语义的左右耳/充电盒电量。
     * 只接受出现 left/right/case/charging-case 等语义词附近的数字，
     * 不再把 RSSI、扫描时间或其它数字当成电量。
     */
    public static BatteryLevels queryDetailed(String mac) {
        BatteryLevels out = new BatteryLevels();
        if (mac == null || mac.isEmpty()) return out;
        String dump = ShizukuHelper.exec("dumpsys bluetooth_manager");
        if (dump == null || dump.isEmpty() || dump.startsWith("ERR:")) return out;
        String lower = dump.toLowerCase(Locale.ROOT);
        int from = 0;
        while ((from = lower.indexOf(mac.toLowerCase(Locale.ROOT), from)) >= 0) {
            int a = Math.max(0, from - 600);
            int b = Math.min(dump.length(), from + mac.length() + 1400);
            String w = dump.substring(a, b);
            Integer l = semanticBattery(w, "left|l_battery|left_battery|untethered_left");
            Integer r = semanticBattery(w, "right|r_battery|right_battery|untethered_right");
            Integer c = semanticBattery(w, "case|case_battery|charging.case|untethered_case");
            if (l != null) out.left = l;
            if (r != null) out.right = r;
            if (c != null) out.caseBox = c;
            Integer o = batteryIn(w);
            if (o != null) out.overall = o;
            if (BatteryLevels.valid(out.left) || BatteryLevels.valid(out.right) || BatteryLevels.valid(out.caseBox)) break;
            from += mac.length();
        }
        if (out.anyKnown()) { out.source = "sys-dumpsys-detailed"; out.timestamp = System.currentTimeMillis(); }
        return out;
    }

    private static Integer semanticBattery(String w, String keys) {
        String[] ks = keys.split("\\|");
        for (String k : ks) {
            Pattern p = Pattern.compile("(?i)" + Pattern.quote(k) + "[^0-9\\n]{0,60}([0-9]{1,3})");
            Matcher m = p.matcher(w);
            if (m.find()) {
                try {
                    int v = Integer.parseInt(m.group(1));
                    if (BatteryLevels.valid(v)) return v;
                } catch (Exception ignored) {}
            }
        }
        return null;
    }

}
