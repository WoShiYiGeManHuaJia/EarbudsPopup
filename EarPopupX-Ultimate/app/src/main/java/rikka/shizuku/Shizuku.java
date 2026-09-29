package rikka.shizuku;

import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

import moe.shizuku.server.IShizukuApplication;
import moe.shizuku.server.IShizukuService;

/**
 * 内置精简版 Shizuku 客户端。
 *
 * 与官方 API 的区别：去掉 androidx / Sui / UserService 相关部分，
 * 只保留本 App 需要的「拿 binder + 用 adb 权限起进程」能力。
 * 包名、AIDL 描述、transact 方式与官方完全一致，因此 Shizuku 与 Stellar 都能识别。
 */
public final class Shizuku {

    private static final String TAG = "Shizuku";

    private static IBinder binder;
    private static IShizukuService service;

    private static int serverUid = -1;
    private static int serverApiVersion = -1;
    private static int serverPatchVersion = -1;
    private static String serverContext = null;
    private static boolean permissionGranted = false;
    private static boolean shouldShowRequestPermissionRationale = false;
    private static boolean preV11 = false;
    private static boolean binderReady = false;

    public interface OnBinderReceivedListener {
        void onBinderReceived();
    }

    public interface OnBinderDeadListener {
        void onBinderDead();
    }

    public interface OnRequestPermissionResultListener {
        void onRequestPermissionResult(int requestCode, int grantResult);
    }

    private static final List<OnBinderReceivedListener> RECEIVED_LISTENERS = new ArrayList<>();
    private static final List<OnBinderDeadListener> DEAD_LISTENERS = new ArrayList<>();
    private static final List<OnRequestPermissionResultListener> PERMISSION_LISTENERS = new ArrayList<>();

    private static final IShizukuApplication SHIZUKU_APPLICATION = new IShizukuApplication.Stub() {
        @Override
        public void bindApplication(Bundle data) {
            serverUid = data.getInt(ShizukuApiConstants.BIND_APPLICATION_SERVER_UID, -1);
            serverApiVersion = data.getInt(ShizukuApiConstants.BIND_APPLICATION_SERVER_VERSION, -1);
            serverPatchVersion = data.getInt(ShizukuApiConstants.BIND_APPLICATION_SERVER_PATCH_VERSION, -1);
            serverContext = data.getString(ShizukuApiConstants.BIND_APPLICATION_SERVER_SECONTEXT);
            permissionGranted = data.getBoolean(ShizukuApiConstants.BIND_APPLICATION_PERMISSION_GRANTED, false);
            shouldShowRequestPermissionRationale =
                    data.getBoolean(ShizukuApiConstants.BIND_APPLICATION_SHOULD_SHOW_REQUEST_PERMISSION_RATIONALE, false);
            binderReady = true;
            scheduleBinderReceivedListeners();
        }

        @Override
        public void dispatchRequestPermissionResult(int requestCode, Bundle data) {
            boolean allowed = data.getBoolean(ShizukuApiConstants.REQUEST_PERMISSION_REPLY_ALLOWED, false);
            scheduleRequestPermissionResultListener(requestCode, allowed ? 0 : -1);
        }

        @Override
        public void showPermissionConfirmation(int requestUid, int requestPid, String requestPackageName, int requestCode) {
            // 非 App 侧实现
        }
    };

    private static final IBinder.DeathRecipient DEATH_RECIPIENT = () -> {
        binderReady = false;
        onBinderReceived(null, null);
    };

    private Shizuku() {
    }

    private static boolean attachApplicationV13(IBinder binder, String packageName) throws RemoteException {
        Bundle args = new Bundle();
        args.putInt(ShizukuApiConstants.ATTACH_APPLICATION_API_VERSION, ShizukuApiConstants.SERVER_VERSION);
        args.putString(ShizukuApiConstants.ATTACH_APPLICATION_PACKAGE_NAME, packageName);

        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(ShizukuApiConstants.BINDER_DESCRIPTOR);
            data.writeStrongBinder(SHIZUKU_APPLICATION.asBinder());
            data.writeInt(1);
            args.writeToParcel(data, 0);
            boolean result = binder.transact(18, data, reply, 0);
            reply.readException();
            return result;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private static boolean attachApplicationV11(IBinder binder, String packageName) throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(ShizukuApiConstants.BINDER_DESCRIPTOR);
            data.writeStrongBinder(SHIZUKU_APPLICATION.asBinder());
            data.writeString(packageName);
            boolean result = binder.transact(14, data, reply, 0);
            reply.readException();
            return result;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    public static void onBinderReceived(IBinder newBinder, String packageName) {
        if (binder == newBinder) {
            return;
        }
        if (newBinder == null) {
            binder = null;
            service = null;
            serverUid = -1;
            serverApiVersion = -1;
            serverContext = null;
            binderReady = false;
            scheduleBinderDeadListeners();
            return;
        }
        if (binder != null) {
            try {
                binder.unlinkToDeath(DEATH_RECIPIENT, 0);
            } catch (Throwable ignored) {
            }
        }
        binder = newBinder;
        service = IShizukuService.Stub.asInterface(newBinder);
        try {
            binder.linkToDeath(DEATH_RECIPIENT, 0);
        } catch (Throwable ignored) {
        }
        try {
            if (!attachApplicationV13(binder, packageName) && !attachApplicationV11(binder, packageName)) {
                preV11 = true;
            }
        } catch (Throwable e) {
            Log.w(TAG, "attachApplication", e);
        }
        if (preV11) {
            binderReady = true;
            scheduleBinderReceivedListeners();
        }
    }

    public static IBinder getBinder() {
        return binder;
    }

    public static boolean pingBinder() {
        return binder != null && binder.pingBinder();
    }

    public static boolean isPreV11() {
        return preV11;
    }

    public static int getServerUid() {
        return serverUid;
    }

    public static int getServerApiVersion() {
        return serverApiVersion;
    }

    public static int getServerPatchVersion() {
        return serverPatchVersion;
    }

    public static String getSELinuxContext() {
        return serverContext;
    }

    public static void addBinderReceivedListener(OnBinderReceivedListener listener) {
        if (listener == null || RECEIVED_LISTENERS.contains(listener)) {
            return;
        }
        RECEIVED_LISTENERS.add(listener);
    }

    public static void removeBinderReceivedListener(OnBinderReceivedListener listener) {
        RECEIVED_LISTENERS.remove(listener);
    }

    public static void addBinderDeadListener(OnBinderDeadListener listener) {
        if (listener == null || DEAD_LISTENERS.contains(listener)) {
            return;
        }
        DEAD_LISTENERS.add(listener);
    }

    public static void addRequestPermissionResultListener(OnRequestPermissionResultListener listener) {
        if (listener == null || PERMISSION_LISTENERS.contains(listener)) {
            return;
        }
        PERMISSION_LISTENERS.add(listener);
    }

    private static void scheduleBinderReceivedListeners() {
        List<OnBinderReceivedListener> copy = new ArrayList<>(RECEIVED_LISTENERS);
        for (OnBinderReceivedListener l : copy) {
            l.onBinderReceived();
        }
    }

    private static void scheduleBinderDeadListeners() {
        List<OnBinderDeadListener> copy = new ArrayList<>(DEAD_LISTENERS);
        for (OnBinderDeadListener l : copy) {
            l.onBinderDead();
        }
    }

    private static void scheduleRequestPermissionResultListener(int requestCode, int result) {
        List<OnRequestPermissionResultListener> copy = new ArrayList<>(PERMISSION_LISTENERS);
        for (OnRequestPermissionResultListener l : copy) {
            l.onRequestPermissionResult(requestCode, result);
        }
    }

    public static int checkSelfPermission() {
        if (service == null) {
            return -1;
        }
        try {
            return service.checkSelfPermission() ? 0 : -1;
        } catch (Throwable e) {
            return -1;
        }
    }

    public static void requestPermission(int requestCode) {
        if (service == null) {
            return;
        }
        try {
            service.requestPermission(requestCode);
        } catch (Throwable e) {
            Log.w(TAG, "requestPermission", e);
        }
    }

    public static boolean shouldShowRequestPermissionRationale() {
        return shouldShowRequestPermissionRationale;
    }

    /**
     * 以 Shizuku / Stellar 提供的 adb(root) 权限启动进程。
     */
    public static ShizukuRemoteProcess newProcess(String[] cmd, String[] env, String dir) {
        if (service == null) {
            return null;
        }
        try {
            return new ShizukuRemoteProcess(service.newProcess(cmd, env, dir));
        } catch (Throwable e) {
            Log.w(TAG, "newProcess", e);
            return null;
        }
    }

    public static int getVersion() {
        if (service == null) {
            return -1;
        }
        try {
            return service.getVersion();
        } catch (Throwable e) {
            return -1;
        }
    }
}
