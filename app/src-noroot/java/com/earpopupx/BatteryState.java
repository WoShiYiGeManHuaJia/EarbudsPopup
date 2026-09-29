package com.earpopupx;

/**
 * 电量快照。
 * -1 表示「耳机/系统没有提供这个值」，UI 显示为 --，绝不用别的数字填充。
 */
public final class BatteryState {
    public final int aggregate;
    public final int left;
    public final int right;
    public final int caseLevel;
    public final boolean leftCharging;
    public final boolean rightCharging;
    public final boolean caseCharging;
    public final String source;

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

    public static BatteryState aggregateOnly(int v, String source) {
        return new BatteryState(v, -1, -1, -1, false, false, false, source);
    }

    public boolean hasParts() {
        return left >= 0 || right >= 0 || caseLevel >= 0;
    }
}
