package com.yuanbao.earbuds;

/**
 * 三方电量数据：左耳 / 右耳 / 充电盒。
 *
 * 关键约定：只有 1~100 才是有效电量。
 * 0 和 -1 都表示「未知」——蓝牙规范里 0 经常代表「未上报」而不是真的没电，
 * 之前把 0 当成有效值填进左右耳，导致弹窗显示 0%，这是最大的一个 bug。
 */
public class BatteryLevels {

    public int left = -1;

    /** 系统元数据提供的真实充电状态。 */
    public boolean leftCharging = false;
    public boolean rightCharging = false;
    public boolean caseCharging = false;
    public int right = -1;
    public int caseBox = -1;
    /** 只拿到一个整机值时的兜底 */
    public int overall = -1;
    public long timestamp = 0L;
    /** 数据来源，便于诊断 */
    public String source = "unknown";

    /** 有效电量：1~100。0 与 -1 都视为未知 */
    public static boolean valid(int v) {
        return v >= 1 && v <= 100;
    }

    /** 把无效值统一成 -1，避免 0 流入 UI */
    public static int norm(int v) {
        return valid(v) ? v : -1;
    }

    public boolean anyKnown() {
        return valid(left) || valid(right) || valid(caseBox) || valid(overall);
    }

    /** 用整机值填补缺失的左右耳（整机值必须有效才填） */
    public void fillFromOverall() {
        if (valid(overall)) {
            if (!valid(left)) left = overall;
            if (!valid(right)) right = overall;
        }
    }

    /** 把所有字段里的 0 清成 -1 */
    public void sanitize() {
        left = norm(left);
        right = norm(right);
        caseBox = norm(caseBox);
        overall = norm(overall);
    }

    public static String fmt(int v) {
        return valid(v) ? v + "%" : "--%";
    }
}
