package rikka.shizuku;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;

import moe.shizuku.api.BinderContainer;

/**
 * 接收 Shizuku / Stellar 服务端推送的 binder。
 *
 * Manifest 中必须这样声明（authority 必须是 ${applicationId}.shizuku）：
 * &lt;provider
 *     android:name="rikka.shizuku.ShizukuProvider"
 *     android:authorities="${applicationId}.shizuku"
 *     android:exported="true"
 *     android:multiprocess="false"
 *     android:permission="android.permission.INTERACT_ACROSS_USERS_FULL" /&gt;
 */
public class ShizukuProvider extends ContentProvider {

    private static final String TAG = "ShizukuProvider";

    public static final String METHOD_SEND_BINDER = "sendBinder";
    public static final String METHOD_GET_BINDER = "getBinder";
    public static final String ACTION_BINDER_RECEIVED = "moe.shizuku.api.action.BINDER_RECEIVED";
    private static final String EXTRA_BINDER = "moe.shizuku.privileged.api.intent.extra.BINDER";

    @Override
    public void attachInfo(Context context, ProviderInfo info) {
        super.attachInfo(context, info);
        if (info.multiprocess) {
            throw new IllegalStateException("android:multiprocess must be false");
        }
        if (!info.exported) {
            throw new IllegalStateException("android:exported must be true");
        }
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        if (extras == null) {
            return null;
        }
        extras.setClassLoader(BinderContainer.class.getClassLoader());

        Bundle reply = new Bundle();
        if (METHOD_SEND_BINDER.equals(method)) {
            handleSendBinder(extras);
        } else if (METHOD_GET_BINDER.equals(method)) {
            if (!handleGetBinder(reply)) {
                return null;
            }
        }
        return reply;
    }

    private void handleSendBinder(Bundle extras) {
        if (Shizuku.pingBinder()) {
            return;
        }
        BinderContainer container = extras.getParcelable(EXTRA_BINDER);
        if (container != null && container.binder != null) {
            Log.d(TAG, "binder received");
            Context ctx = getContext();
            Shizuku.onBinderReceived(container.binder, ctx == null ? null : ctx.getPackageName());
        }
    }

    private boolean handleGetBinder(Bundle reply) {
        IBinder binder = Shizuku.getBinder();
        if (binder == null || !binder.pingBinder()) {
            return false;
        }
        reply.putParcelable(EXTRA_BINDER, new BinderContainer(binder));
        return true;
    }

    @Override
    public final Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public final String getType(Uri uri) {
        return null;
    }

    @Override
    public final Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public final int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public final int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
