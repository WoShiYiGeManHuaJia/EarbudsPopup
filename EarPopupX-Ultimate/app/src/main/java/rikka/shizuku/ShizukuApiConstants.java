package rikka.shizuku;

/**
 * Shizuku API 常量（内置版，去掉 androidx 依赖）。
 */
public class ShizukuApiConstants {

    public static final int SERVER_VERSION = 13;
    public static final int SERVER_PATCH_VERSION = 6;

    public static final String BINDER_DESCRIPTOR = "moe.shizuku.server.IShizukuService";

    public static final String BIND_APPLICATION_SERVER_VERSION = "shizuku:attach-reply-version";
    public static final String BIND_APPLICATION_SERVER_PATCH_VERSION = "shizuku:attach-reply-patch-version";
    public static final String BIND_APPLICATION_SERVER_UID = "shizuku:attach-reply-uid";
    public static final String BIND_APPLICATION_SERVER_SECONTEXT = "shizuku:attach-reply-secontext";
    public static final String BIND_APPLICATION_PERMISSION_GRANTED = "shizuku:attach-reply-permission-granted";
    public static final String BIND_APPLICATION_SHOULD_SHOW_REQUEST_PERMISSION_RATIONALE = "shizuku:attach-reply-should-show-request-permission-rationale";

    public static final String REQUEST_PERMISSION_REPLY_ALLOWED = "shizuku:request-permission-reply-allowed";
    public static final String REQUEST_PERMISSION_REPLY_IS_ONETIME = "shizuku:request-permission-reply-is-onetime";

    public static final String ATTACH_APPLICATION_PACKAGE_NAME = "shizuku:attach-package-name";
    public static final String ATTACH_APPLICATION_API_VERSION = "shizuku:attach-api-version";

    private ShizukuApiConstants() {
    }
}
