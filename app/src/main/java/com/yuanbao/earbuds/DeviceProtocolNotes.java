package com.yuanbao.earbuds;

/**
 * Fingerprint notes for Redmi Buds 5 Pro Gaming.
 * The device exposes an Airoha-style RACE GATT service; this class is intentionally
 * passive and never sends undocumented write commands.
 */
public final class DeviceProtocolNotes {
    public static final String REDMI_BUDS_5_PRO_GAMING = "Redmi Buds 5 Pro Gaming";
    public static final String RACE_SERVICE = "5052494D-2DAB-0341-6972-6F6861424C45";
    public static final String RACE_TX = "43484152-2DAB-3241-6972-6F6861424C45";
    public static final String RACE_RX = "43484152-2DAB-3141-6972-6F6861424C45";
    private DeviceProtocolNotes() {}
}
