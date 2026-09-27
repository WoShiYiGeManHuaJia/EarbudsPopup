package com.yuanbao.earbuds;

/**
 * 三方电量数据：左耳 / 右耳 / 充电盒。
 * 任一值为 -1 表示未知，UI 显示 --%。
 */
public class BatteryLevels {

    public int left = -1;
    public int right = -1;
    public int caseBox = -1;
    /** 只拿到一个整机值时的兜底 */
    public int overall = -1;
    public long timestamp = 0L;
    /** 数据来源，便于诊断 */
    public String source = "unknown";

    public boolean anyKnown() {
        return left >= 0 || right >= 0 || caseBox >= 0 || overall >= 0;
    }

    /** 用整机值填补缺失的左右耳 */
    public void fillFromOverall() {
        if (overall >= 0) {
            if (left < 0) left = overall;
            if (right < 0) right = overall;
        }
    }

    public static String fmt(int v) {
        return v >= 0 ? v + "%" : "--%";
    }
}
