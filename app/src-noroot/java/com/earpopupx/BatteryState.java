package com.earpopupx;

/** 一次电量读取的完整快照。所有 -1 表示"这一项设备没有提供"，UI 必须显示占位而不是编造数字。 */
public final class BatteryState {
    public final int aggregate;      // 整机 / 综合电量
    public final int left;           // 左耳
    public final int right;          // 右耳
    public final int caseLevel;      // 充电盒
    public final boolean leftCharging, rightCharging, caseCharging;
    public final String source;      // 数据来源，用于诊断

    public BatteryState(int aggregate, int left, int right, int caseLevel,
                        boolean lc, boolean rc, boolean cc, String source) {
        this.aggregate = aggregate;
        this.left = left;
        this.right = right;
        this.caseLevel = caseLevel;
        this.leftCharging = lc;
        this.rightCharging = rc;
        this.caseCharging = cc;
        this.source = source;
    }

    public static BatteryState unknown(String source) {
        return new BatteryState(-1, -1, -1, -1, false, false, false, source);
    }

    /** 是否有任意一项分项电量 */
    public boolean hasDetail() { return left >= 0 || right >= 0 || caseLevel >= 0; }

    /** 是否完全没有数据 */
    public boolean isEmpty() { return aggregate < 0 && !hasDetail(); }

    public String describe() {
        StringBuilder sb = new StringBuilder();
        if (aggregate >= 0) sb.append("整机 ").append(aggregate).append('%');
        if (left >= 0) { if (sb.length() > 0) sb.append(" / "); sb.append("左 ").append(left).append('%'); }
        if (right >= 0) { if (sb.length() > 0) sb.append(" / "); sb.append("右 ").append(right).append('%'); }
        if (caseLevel >= 0) { if (sb.length() > 0) sb.append(" / "); sb.append("盒 ").append(caseLevel).append('%'); }
        if (sb.length() == 0) sb.append("未知");
        return sb.toString();
    }
}
