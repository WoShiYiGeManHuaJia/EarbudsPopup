package com.earpopupx;

public final class BatteryState {
    public final int aggregate, left, right, caseLevel;
    public final boolean leftCharging, rightCharging, caseCharging;
    public final String source;
    public BatteryState(int aggregate, int left, int right, int caseLevel, boolean lc, boolean rc, boolean cc, String source) {
        this.aggregate=aggregate; this.left=left; this.right=right; this.caseLevel=caseLevel;
        this.leftCharging=lc; this.rightCharging=rc; this.caseCharging=cc; this.source=source;
    }
    public static BatteryState unknown(String source) { return new BatteryState(-1,-1,-1,-1,false,false,false,source); }
}
