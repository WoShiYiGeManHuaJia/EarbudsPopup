package com.yuanbao.earbuds;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * v1.4：电量抓取服务（无障碍）。
 *
 * 【它解决什么】
 *   系统耳机弹窗在开盖/连接瞬间会显示「左耳 / 右耳 / 充电盒」三个真实百分比。
 *   这三个数字是耳机真实上报的，但只对系统进程可见，第三方 App 的公开 API
 *   只能拿到一个聚合值。本服务读取那个弹窗窗口上的文本，把三个真实数字
 *   交给本 App 的自定义弹窗，让自定义弹窗显示同一组真值。
 *
 * 【它不做什么 —— 必须如实说明】
 *   · 不连接耳机、不发蓝牙命令、不联网、不上传
 *   · 不编造数字：读不到三个就一个都不填，弹窗继续显示等待态
 *   · 数字本身是耳机真实值，但来源是系统弹窗，不是本 App 直连耳机
 *     所以官方 App / 系统弹窗不出数时，这里也拿不到
 *
 * 【为什么用 getWindows() 而不是 rootInActiveWindow】
 *   本 App 自己的弹窗也会盖在屏幕上，rootInActiveWindow 可能取到的是
 *   我们自己的窗口。遍历所有窗口才能读到压在下面的系统弹窗。
 */
public class BatteryCaptorService extends AccessibilityService {

    public static final String ACTION_CAPTURED =
            "com.yuanbao.earbuds.ACTION_BATTERY_CAPTURED";
    public static final String EXTRA_LEFT = "left";
    public static final String EXTRA_RIGHT = "right";
    public static final String EXTRA_CASE = "case";
    public static final String EXTRA_SOURCE = "source";

    /** 最近一次成功抓到三项的时刻 */
    public static volatile long lastCaptureAt = 0L;
    public static volatile int lastLeft = -1;
    public static volatile int lastRight = -1;
    public static volatile int lastCase = -1;
    /** 服务是否已在运行（供设置页显示状态） */
    public static volatile boolean running = false;

    /** 判定「新鲜」的时间窗：超过这个时长就不再当作本次连接的读数 */
    public static final long FRESH_MS = 15000L;

    private static final Pattern PCT = Pattern.compile("(\\d{1,3})\\s*%");

    /**
     * 允许作为来源的包名前缀。
     * 只认系统与小米/红米自家组件，避免把随便某个 App 里的三个百分比
     * 误当成耳机电量。
     */
    private static final String[] ALLOWED_PREFIX = {
            "com.android.systemui",
            "com.android.bluetooth",
            "com.android.settings",
            "com.miui.",
            "com.xiaomi.",
            "com.mi.",
            "com.miui.earphone",
            "android",
    };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        running = true;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        int type = event.getEventType();
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                && type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            return;
        }
        scan();
    }

    /** 扫描所有窗口，尝试抓出左 / 右 / 盒三个真实百分比 */
    private void scan() {
        List<AccessibilityWindowInfo> wins;
        try {
            wins = getWindows();
        } catch (Throwable ignored) {
            return;
        }
        if (wins == null || wins.isEmpty()) return;

        for (AccessibilityWindowInfo w : wins) {
            AccessibilityNodeInfo root;
            try {
                root = w.getRoot();
            } catch (Throwable ignored) {
                continue;
            }
            if (root == null) continue;

            String pkg = root.getPackageName() == null
                    ? "" : root.getPackageName().toString();
            if (pkg.isEmpty() || !allowed(pkg)) continue;

            List<String> texts = new ArrayList<>();
            collectText(root, texts, 0);
            if (texts.isEmpty()) continue;

            int[] trio = extract(texts);
            if (trio != null) {
                publish(trio[0], trio[1], trio[2], pkg);
                return;
            }
        }
    }

    /**
     * 从文本集合中提取「左 / 右 / 盒」。
     *
     * 两种布局都覆盖：
     *   ① 带标签：如「左耳 90%」「右耳 80%」「充电盒 100%」
     *   ② 无标签：窗口里恰好出现三个 0~100 的百分比，按常见顺序取
     */
    private int[] extract(List<String> texts) {
        int left = -1, right = -1, caseBox = -1;
        List<Integer> plain = new ArrayList<>();

        for (String t : texts) {
            if (t == null) continue;
            String s = t.trim();
            if (s.isEmpty()) continue;
            Matcher m = PCT.matcher(s);
            if (!m.find()) continue;
            int v;
            try {
                v = Integer.parseInt(m.group(1));
            } catch (Exception e) {
                continue;
            }
            if (v < 0 || v > 100) continue;

            // ① 带标签：按关键词归位
            if (s.contains("左") || s.contains("L") || s.toLowerCase().contains("left")) {
                if (left < 0) left = v;
            } else if (s.contains("右") || s.contains("R") || s.toLowerCase().contains("right")) {
                if (right < 0) right = v;
            } else if (s.contains("盒") || s.contains("仓") || s.toLowerCase().contains("case")) {
                if (caseBox < 0) caseBox = v;
            } else {
                // ② 无标签：先攒着，稍后按出现顺序补位
                if (!plain.contains(v)) plain.add(v);
            }
        }

        // 标签没写全时，用剩余百分比按 左→右→盒 的顺序补位
        if (left < 0 && !plain.isEmpty()) left = plain.remove(0);
        if (right < 0 && !plain.isEmpty()) right = plain.remove(0);
        if (caseBox < 0 && !plain.isEmpty()) caseBox = plain.remove(0);

        // 三个都拿到才算成功：缺任何一个都不填，绝不猜
        if (left < 0 || right < 0 || caseBox < 0) return null;
        return new int[]{left, right, caseBox};
    }

    private void publish(int l, int r, int c, String pkg) {
        lastLeft = l;
        lastRight = r;
        lastCase = c;
        lastCaptureAt = System.currentTimeMillis();
        try {
            Intent i = new Intent(ACTION_CAPTURED);
            i.setPackage(getPackageName());
            i.putExtra(EXTRA_LEFT, l);
            i.putExtra(EXTRA_RIGHT, r);
            i.putExtra(EXTRA_CASE, c);
            i.putExtra(EXTRA_SOURCE, pkg);
            sendBroadcast(i);
        } catch (Throwable ignored) {
        }
    }

    /** 递归收集窗口内所有可见文本 */
    private void collectText(AccessibilityNodeInfo node, List<String> out, int depth) {
        if (node == null || depth > 24 || out.size() > 120) return;
        try {
            CharSequence cs = node.getText();
            if (cs != null) {
                String s = cs.toString();
                if (!s.isEmpty()) out.add(s);
            }
            CharSequence cd = node.getContentDescription();
            if (cd != null) {
                String s = cd.toString();
                if (!s.isEmpty()) out.add(s);
            }
        } catch (Throwable ignored) {
        }
        try {
            int n = node.getChildCount();
            for (int i = 0; i < n; i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child == null) continue;
                collectText(child, out, depth + 1);
            }
        } catch (Throwable ignored) {
        }
    }

    private static boolean allowed(String pkg) {
        if (pkg == null) return false;
        String p = pkg.toLowerCase();
        for (String pre : ALLOWED_PREFIX) {
            if (p.startsWith(pre)) return true;
        }
        return false;
    }

    /** 供外部读取：本次连接是否抓到了新鲜的三项真值 */
    public static boolean hasFresh() {
        return lastCaptureAt > 0L
                && (System.currentTimeMillis() - lastCaptureAt) < FRESH_MS;
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public void onDestroy() {
        running = false;
        super.onDestroy();
    }
}
