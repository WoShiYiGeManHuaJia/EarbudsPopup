package com.yuanbao.earbuds;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHeadset;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;

import com.bumptech.glide.Glide;

/**
 * 常驻前台服务：监听蓝牙 / 有线耳机的连接事件，弹出自定义悬浮窗动画。
 */
public class PopupService extends Service {

    /** 悬浮窗最近一次显示的时间戳。
     *  智能模式下 Activity 启动可能慢于降级判定，导致两个弹窗先后出现
     *  （用户看到的「闪两下」）。PopupActivity 会读这个值，
     *  若发现刚刚已有悬浮窗显示，就直接结束自己。 */
    public static volatile long lastOverlayShownAt = 0L;

    /** 沉底模式距屏幕底部的边距（dp） */
    public static final int BOTTOM_MARGIN_DP = 28;

    /**
     * 按连续垂直位置算出距屏幕顶部的偏移。
     * 上下各留安全边距（顶部避挖孔、底部避手势条），中间线性分布。
     */
    public static int topOffsetForVPos(int vpos, int cardHeightPx, int screenHeightPx) {
        return topOffsetForVPos(vpos, cardHeightPx, screenHeightPx, 0f);
    }

    /**
     * 按连续垂直位置算出距屏幕顶部的偏移（px）。
     *
     * 0   → 贴顶（仅留顶部安全边距）
     * 100 → 贴底（仅留底部安全边距）
     * 中间 → 在可用空间内线性分布
     *
     * @param density 用于把 dp 安全边距换算成 px
     */
    public static int topOffsetForVPos(int vpos, int cardHeightPx,
                                       int screenHeightPx, float density) {
        int topPad = (int) (24 * density);
        int botPad = (int) (24 * density);
        int usable = screenHeightPx - cardHeightPx - topPad - botPad;
        if (usable <= 0) return topPad;
        int v = Math.max(0, Math.min(100, vpos));
        return topPad + Math.round(usable * v / 100f);
    }

    public static final String ACTION_SHOW = "com.yuanbao.earbuds.ACTION_SHOW";
    public static final String ACTION_RESTART = "com.yuanbao.earbuds.ACTION_RESTART";
    public static final String EXTRA_NAME = "name";
    public static final String EXTRA_BATTERY = "battery";
    public static final String EXTRA_CASE = "case_battery";
    public static final String EXTRA_LEFT = "left_battery";
    public static final String EXTRA_OVERALL = "overall_battery";
    public static final String EXTRA_ADDRESS = "address";
    public static final String EXTRA_WIRED = "wired";
    /** 系统隐藏广播：耳机电量变化 */
    public static final String ACTION_BATTERY_CHANGED =
            "android.bluetooth.device.action.BATTERY_LEVEL_CHANGED";
    /**
     * 电量值的 extra 名。
     * BluetoothDevice.EXTRA_BATTERY_LEVEL 是隐藏 API，编译期不可见，
     * 所以这里直接写字面量（值与系统一致）。
     */
    public static final String EXTRA_BATTERY_LEVEL =
            "android.bluetooth.device.extra.BATTERY_LEVEL";

    private static final String CHANNEL_ID = "popup_service";
    private static final String CHANNEL_SILENT = "popup_service_silent";
    private static final int NOTI_ID = 1001;

    private WindowManager wm;
    private View current;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Prefs prefs;
    private volatile boolean screenOn = true;
    private View currentRoot;
    private String currentName;
    private volatile String currentAddress;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            handle(i);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = new Prefs(this);
        // 弹窗渲染用静态 PrefsHolder 取配置，必须在服务里也初始化，
        // 否则开机自启时用户设的图片缩放会丢失（拿到 null 回退成默认值）
        PopupRenderer.PrefsHolder.init(this);
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        startForeground(NOTI_ID, buildNotification());
        refreshNotificationVisibility();
        syncScreenState();
        registerReceivers();
        warmUpImage();
    }

    /**
     * 预热：把弹窗图片提前解码进 Glide 内存缓存。
     * 弹窗时直接命中缓存，省掉解码耗时，首帧出现更快（目标 < 1 秒）。
     */
    private void warmUpImage() {
        try {
            String u = prefs.imageUri();
            if (u == null || u.isEmpty()) return;
            Glide.with(this).load(Uri.parse(u)).preload();
        } catch (Exception ignored) {
        }
    }

    /**
     * 注册广播。抽成方法是为了能在设置变化后重新注册——
     * 之前只在 onCreate 里注册一次，用户在设置页改「省电模式」后
     * （ ACTION_RESTART 只重载了 Prefs），屏幕开关监听不会跟着变，开关形同虚设。
     */
    private void registerReceivers() {
        try {
            unregisterReceiver(receiver);
        } catch (Exception ignored) {
        }
        IntentFilter f = new IntentFilter();
        f.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
        f.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        f.addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED);
        f.addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED);
        // 不在这里监听 ACTION_HEADSET_PLUG：
        // WiredReceiver（静态注册）也在监听它，两边都弹会导致插一次弹两遍。
        // 统一由 WiredReceiver 收到后发 ACTION_SHOW，走 onStartCommand 单路径弹窗。
        f.addAction(ACTION_SHOW);
        f.addAction(ACTION_RESTART);
        // 系统蓝牙栈在耳机电量变化时发出的广播（隐藏广播，但动态注册可收到）。
        // 这是最实时的电量来源：耳机一上报新电量，我们立刻更新缓存。
        // 之前完全没监听它，所以电量只能靠手动探测——这就是「录死」的根因。
        f.addAction(ACTION_BATTERY_CHANGED);
        if (prefs.powerSave()) {
            f.addAction(Intent.ACTION_SCREEN_ON);
            f.addAction(Intent.ACTION_SCREEN_OFF);
        }
        try {
            registerReceiver(receiver, f);
        } catch (Exception ignored) {
        }
    }

    /** 用系统实际状态初始化 screenOn，避免熄屏启动时误判为亮屏而弹窗 */
    private void syncScreenState() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            screenOn = pm == null || pm.isInteractive();
        } catch (Throwable t) {
            screenOn = true;
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_SHOW.equals(intent.getAction())) {
            String name = intent.getStringExtra(EXTRA_NAME);
            String addr = intent.getStringExtra(EXTRA_ADDRESS);
            BatteryLevels lv = new BatteryLevels();
            lv.left = intent.getIntExtra(EXTRA_LEFT, -1);
            lv.right = intent.getIntExtra(EXTRA_BATTERY, -1);
            lv.caseBox = intent.getIntExtra(EXTRA_CASE, -1);
            lv.overall = intent.getIntExtra(EXTRA_OVERALL, lv.right);
            lv.sanitize();
            lv.fillFromOverall();
            lv.timestamp = System.currentTimeMillis();
            show(name == null ? "耳机" : name, addr, lv, null);
        }
        return START_STICKY;
    }

    private Notification buildNotification() {
        boolean hide = prefs.hideNotification();
        String useChannel = hide && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? CHANNEL_SILENT : CHANNEL_ID;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                if (hide) {
                    NotificationChannel ch = new NotificationChannel(
                            CHANNEL_SILENT, "后台服务（静默）",
                            NotificationManager.IMPORTANCE_MIN);
                    ch.setDescription("不显示通知，仅保持服务运行");
                    ch.setShowBadge(false);
                    ch.enableLights(false);
                    ch.enableVibration(false);
                    ch.setSound(null, null);
                    nm.createNotificationChannel(ch);
                } else {
                    NotificationChannel ch = new NotificationChannel(
                            CHANNEL_ID, "耳机弹窗服务", NotificationManager.IMPORTANCE_LOW);
                    ch.setDescription("保持弹窗服务在后台运行");
                    ch.setShowBadge(false);
                    nm.createNotificationChannel(ch);
                }
            }
        }
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        android.app.PendingIntent pi = android.app.PendingIntent.getActivity(
                this, 1, open,
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                        ? android.app.PendingIntent.FLAG_IMMUTABLE
                        : 0);
        return new NotificationCompat.Builder(this, useChannel)
                .setContentTitle("耳机弹窗已就绪")
                .setContentText("连接耳机时会显示自定义弹窗")
                .setSmallIcon(R.drawable.ic_stat_notify)
                .setContentIntent(pi)
                .setOngoing(!hide)
                .setSilent(true)
                .setPriority(hide ? NotificationCompat.PRIORITY_MIN
                        : NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .build();
    }

    private void handle(Intent i) {
        if (i == null || i.getAction() == null) return;

        // 省电：熄屏时跳过弹窗，避免唤醒屏幕白白耗电
        if (prefs.powerSave() && Intent.ACTION_SCREEN_OFF.equals(i.getAction())) {
            screenOn = false;
            return;
        }
        if (Intent.ACTION_SCREEN_ON.equals(i.getAction())) {
            screenOn = true;
            return;
        }
        if (prefs.powerSave() && !screenOn
                && !ACTION_SHOW.equals(i.getAction())
                && !ACTION_RESTART.equals(i.getAction())) {
            return;
        }

        // 重新加载设置并应用（通知可见性 / 省电策略变了）
        if (ACTION_RESTART.equals(i.getAction())) {
            prefs = new Prefs(this);
            syncScreenState();
            registerReceivers();     // 省电开关可能变了，要重新注册
            refreshNotificationVisibility();
            startForeground(NOTI_ID, buildNotification());
            return;
        }
        if (!prefs.masterEnabled()) return;

        String action = i.getAction();

        if (ACTION_SHOW.equals(action)) {
            BatteryLevels demo = new BatteryLevels();
            demo.left = i.getIntExtra(EXTRA_LEFT, -1);
            demo.right = i.getIntExtra(EXTRA_BATTERY, -1);
            demo.caseBox = i.getIntExtra(EXTRA_CASE, -1);
            demo.overall = i.getIntExtra(EXTRA_OVERALL, demo.right);
            demo.sanitize();
            demo.fillFromOverall();
            demo.timestamp = System.currentTimeMillis();
            show(i.getStringExtra(EXTRA_NAME), i.getStringExtra(EXTRA_ADDRESS), demo, null);
            return;
        }

        // 电量变化广播：立刻写入缓存，下次弹窗就是最新值
        if (ACTION_BATTERY_CHANGED.equals(action)) {
            BluetoothDevice bd = i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            int lvl = i.getIntExtra(EXTRA_BATTERY_LEVEL, -1);
            if (bd != null && BatteryLevels.valid(lvl)) {
                String a2 = bd.getAddress();
                BatteryLevels bl = new BatteryStore(this).load(a2);
                bl.overall = lvl;
                bl.fillFromOverall();
                bl.sanitize();
                bl.timestamp = System.currentTimeMillis();
                bl.source = "sys-broadcast";
                new BatteryStore(this).save(a2, bl);
                // 若当前弹窗正是这台设备，原地刷新
                if (currentRoot != null && a2.equals(currentAddress)) {
                    PopupRenderer.updateInfo(currentRoot, currentName, bl, prefs);
                }
                // 超级岛由 SystemUI 托管，电量更新要重新 post 才能刷新
                int eng = prefs.engine();
                if (eng == 3 || eng == 4) {
                    HyperOSIslandNotifier.post(this,
                            currentName == null ? "耳机" : currentName, bl, prefs);
                }
            }
            return;
        }

        BluetoothDevice dev = i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
        if (dev == null) return;

        boolean connected;
        if (BluetoothDevice.ACTION_ACL_CONNECTED.equals(action)) {
            connected = true;
        } else if (BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(action)) {
            connected = false;
        } else {
            int st = i.getIntExtra(BluetoothA2dp.EXTRA_STATE, -1);
            connected = (st == BluetoothA2dp.STATE_CONNECTED
                    || st == BluetoothHeadset.STATE_CONNECTED);
        }
        if (!connected) return;

        String addr = dev.getAddress();
        if (!prefs.isAllowed(addr)) return;
        String custom = prefs.deviceName(addr);
        String name = (custom != null && !custom.trim().isEmpty())
                ? custom.trim() : safeName(dev);
        BatteryLevels cached = new BatteryStore(this).load(addr);
        int sys = readBattery(dev);
        // 只有有效值才采用；0 是「未上报」，不能当 0% 显示
        if (BatteryLevels.valid(sys)) {
            cached.overall = sys;
            cached.fillFromOverall();
        }
        cached.sanitize();
        // 关键：缓存必须「够新鲜」才敢显示。
        // 之前直接拿几小时前的旧值当实时电量，用户充完电还看到 30%。
        // 超过 STALE_MS 就作废，先显示 --%，由 autoRefreshBattery 实测后刷新。
        if (cached.timestamp > 0L
                && System.currentTimeMillis() - cached.timestamp > BatteryStore.STALE_MS) {
            cached.left = -1;
            cached.right = -1;
            cached.caseBox = -1;
            cached.overall = -1;
            cached.source = "stale";
        }
        currentAddress = addr;
        show(name, addr, cached, dev);
    }

    private String safeName(BluetoothDevice dev) {
        try {
            String n = dev.getName();
            return (n == null || n.isEmpty()) ? "蓝牙耳机" : n;
        } catch (SecurityException e) {
            return "蓝牙耳机";
        }
    }

    /** 把蓝牙广播名换成更好看的显示名 */
    private String prettyName(String raw) {
        if (raw == null) return "耳机";
        String n = raw.trim();
        String low = n.toLowerCase();
        if (low.contains("buds 5 pro") || low.contains("buds5pro")) {
            return n.contains("电竞") ? "Redmi Buds 5 Pro 电竞版" : "Redmi Buds 5 Pro";
        }
        if (low.contains("buds 5")) return "Redmi Buds 5";
        if (low.contains("buds 4 pro")) return "Xiaomi Buds 4 Pro";
        if (low.contains("airpods")) return "AirPods";
        return n;
    }

    /**
     * 后台通知可见性。
     * Android 8+ 系统强制前台服务必须带通知，App 无法凭空取消；
     * 但可以把渠道重要性降到最低、并引导用户在系统设置里彻底关闭，
     * 关掉后通知栏不再显示，服务照常运行。
     */
    private void refreshNotificationVisibility() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        boolean hide = prefs.hideNotification();
        if (hide) {
            NotificationChannel c = new NotificationChannel(
                    CHANNEL_SILENT, "后台服务（已隐藏）",
                    NotificationManager.IMPORTANCE_MIN);
            c.setShowBadge(false);
            c.setSound(null, null);
            c.enableVibration(false);
            c.enableLights(false);
            nm.createNotificationChannel(c);
        }
    }

    /** 省电：把服务降级为普通后台服务并撤销前台通知（会略微降低优先级） */
    private void applyPowerSave() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && prefs.hideNotification()) {
            try {
                stopForeground(STOP_FOREGROUND_DETACH);
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * 异步探测三方电量（BLE GATT 多实例 + MiBeacon + 系统隐藏单值）。
     * GATT 连接通常要 1~5 秒，来不及在弹窗出现瞬间拿到，
     * 所以先弹（用缓存/整机值），探测回来后原地更新信息条并写缓存，
     * 下次连接就能立刻显示。
     */
    private void startProbe(String name, String address, BluetoothDevice dev) {
        if (!prefs.showBattery() || address == null) return;
        new BatteryProbe(this).probe(address, dev, (levels, diagnostic) -> {
            lastDiagnostic = diagnostic;
            if (levels != null) levels.sanitize();
            new BatteryStore(PopupService.this).save(address, levels);
            if (currentRoot != null && current != null) {
                PopupRenderer.updateInfo(currentRoot, currentName, levels, prefs);
            }
        });
    }

    /** 最近一次探测的完整日志，供诊断页读取 */
    public static volatile String lastDiagnostic = "";

    /** 安卓没有公开的耳机电量 API，用反射读隐藏方法；失败返回 -1（不显示电量） */
    private int readBattery(BluetoothDevice dev) {
        try {
            java.lang.reflect.Method m = dev.getClass().getMethod("getBatteryLevel");
            Object r = m.invoke(dev);
            if (r instanceof Integer) return (Integer) r;
        } catch (Throwable ignored) {
        }
        return -1;
    }

    // ---------------- 弹窗渲染 ----------------

    private void show(String name, String address, BatteryLevels levels, BluetoothDevice dev) {
        main.post(() -> launch(name, address, levels, dev));
    }

    /**
     * 按引擎分发：系统级透明 Activity / 悬浮窗 / 智能降级。
     * 之所以能从后台启动 Activity：已授予 SYSTEM_ALERT_WINDOW 的应用
     * 属于 Android 10+ 后台启动 Activity 限制的官方例外之一。
     */
    /** 上次真正弹出弹窗的时间，用于防抖 */
    private static volatile long lastLaunchAt = 0L;

    private void launch(String name, String address, BatteryLevels levels, BluetoothDevice dev) {
        // 防抖：蓝牙连接会连发多个广播（ACL_CONNECTED、A2DP_CONNECTED、
        // HFP_CONNECTED…），导致 launch() 被连续调用多次，
        // 于是弹窗被创建了两个 —— 用户看到的「闪两次」。
        // 3 秒内只弹一次。
        long now = System.currentTimeMillis();
        if (now - lastLaunchAt < 3000L) return;
        lastLaunchAt = now;
        int engine = prefs.engine();
        // 3 = HyperOS 原生超级岛；4 = HyperOS 优先，失败自动回退 Overlay/Activity
        if (engine == 3 || engine == 4) {
            boolean gif = HyperOSIslandNotifier.looksLikeGif(this, prefs.imageUri());
            boolean posted = !gif && HyperOSIslandNotifier.post(this, name, levels, prefs);
            if (posted) {
                autoRefreshBattery(address, dev);
                // 原生岛由 SystemUI 自己管理生命周期，
                // 不能同时创建 Overlay，否则又是「两个弹窗打架」
                return;
            }
            // GIF、HyperOS 不支持、焦点通知权限关闭等情况：退回旧引擎
            engine = 1;
        }

        boolean canOverlay = android.provider.Settings.canDrawOverlays(this);

        // 无论走哪个引擎，都必须启动自动电量探测。
        // 之前只有 engine==1（悬浮窗）分支调用了 startProbe，
        // 系统级和智能模式下压根不会自动探测，所以用户只能手动点——这是根因。
        if (engine == 1) {
            showOverlay(name, address, levels);
            autoRefreshBattery(address, dev);
            return;
        }

        if (engine == 0) {
            try {
                startActivity(PopupActivity.makeIntent(this, name, levels));
            } catch (Exception e) {
                if (canOverlay) showOverlay(name, address, levels);
            }
            autoRefreshBattery(address, dev);
            return;
        }

        // 智能模式：先试系统级 Activity，确认没起来才降级悬浮窗。
        //
        // 之前确认窗口只有 800ms：系统级 Activity 走 AMS 调度本来就慢，
        // 经常 800ms 还没起，于是又弹一个悬浮窗 —— 两个弹窗叠着出现，
        // 用户看到的就是「闪两下」。同时 autoRefreshBattery 被调了两次，
        // 多一次 GATT 连接，白白拖慢弹窗速度。
        //
        // 现在：确认窗口放宽到 1600ms，且探测只做一次。
        PopupActivity.lastShownAt = 0L;
        final boolean[] probed = {false};
        Runnable doProbe = () -> {
            if (probed[0]) return;
            probed[0] = true;
            autoRefreshBattery(address, dev);
        };
        try {
            startActivity(PopupActivity.makeIntent(this, name, levels));
        } catch (Exception e) {
            if (canOverlay) showOverlay(name, address, levels);
            doProbe.run();
            return;
        }
        main.postDelayed(() -> {
            if (PopupActivity.lastShownAt <= 0L && canOverlay) {
                showOverlay(name, address, levels);
            }
            doProbe.run();
        }, 1600);
    }

    /**
     * 连接时自动刷新电量：先做一次轻量 GATT 读取（快），
     * 拿到值就写缓存并原地刷新弹窗信息条；下次连接立刻就能显示。
     */
    /**
     * 弹窗后异步取真实电量并原地刷新。
     *
     * 优先级：系统蓝牙栈（dumpsys，经 Shizuku）> BLE GATT。
     * 不沿用陈旧缓存：拿不到真值就在信息条上留 --%，
     * 绝不再把几小时前的旧数字当实时电量显示。
     */
    private void autoRefreshBattery(String address, BluetoothDevice dev) {
        if (!prefs.showBattery() || address == null) return;
        final String addr = address;
        final Handler h = main;

        //
        // 【新增】第 0 步：系统隐藏 API getBatteryLevel()
        //
        // 这是毫秒级的同步调用，系统蓝牙栈已经缓存了耳机电量（耳机通过
        // 标准 Battery Service 上报，栈里就有）。市面上的第三方弹窗 App
        // 基本都走这条路 —— 所以它们能"一连接就显示"，不需要用户手动抓。
        //
        // 之前这里一上来就跑 dumpsys（要 fork 进程、解析文本，几百毫秒
        // 甚至数秒），拿不到再退 GATT（又要走一遍连接+发现服务，1~5 秒）。
        // 换耳机时慢路径来不及返回，弹窗就只能显示旧值或空 ——
        // 这就是「换个耳机，电量直接不显示」的原因。
        //
        if (dev != null) {
            int fast = readBattery(dev);
            if (BatteryLevels.valid(fast)) {
                h.post(() -> applyMeasured(addr, fast, "sys-fast"));
                return;
            }
        }

        // 后台线程：先试系统栈（dumpsys 较慢，不能占主线程）
        new Thread(() -> {
            //
            // 【新增】第 0.5 步：轮询重试系统隐藏 API
            //
            // getBatteryLevel() 返回的是【系统蓝牙栈缓存】的电量，不是实时读。
            // 耳机刚连上时，系统往往要 1~3 秒才通过 HFP AT 命令或 GATT
            // 把电量填进缓存；连接那一刻读，几乎必然是 -1。
            // 原来只在连接瞬间读一次，拿不到就直接跳到慢路径，
            // 于是「换个耳机，电量就不显示」。
            // 这里轮询约 4 秒，系统一填上就能立刻拿到。
            //
            BluetoothAdapter ba = BluetoothAdapter.getDefaultAdapter();
            BluetoothDevice target = dev;
            if (target == null && ba != null) {
                try {
                    target = ba.getRemoteDevice(addr);
                } catch (Throwable ignored) {
                    target = null;
                }
            }
            if (target != null) {
                for (int attempt = 0; attempt < 6; attempt++) {
                    // attempt 会被 ++，不是 effectively final，lambda 里不能直接用
                    final int tag = attempt;
                    int v = readBattery(target);
                    if (BatteryLevels.valid(v)) {
                        final int fv = v;
                        h.post(() -> applyMeasured(addr, fv, "sys-retry" + tag));
                        return;
                    }
                    try {
                        Thread.sleep(700);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }

            int sysLvl = -1;
            if (ShizukuHelper.hasPermission()) {
                sysLvl = BatterySysQuery.query(addr);
            }
            final int v = sysLvl;

            if (BatteryLevels.valid(v)) {
                // 拿到权威值：写缓存 + 刷新弹窗
                h.post(() -> applyMeasured(addr, v, "sys-dumpsys"));
                return;
            }
            // 系统栈没拿到，退回 BLE GATT
            h.post(() -> new BatteryProbe(PopupService.this)
                    .quickRead(addr, dev, gattVal -> {
                        if (BatteryLevels.valid(gattVal)) {
                            applyMeasured(addr, gattVal, "gatt-auto");
                        } else {
                            // 两条路都没取到真值：把陈旧缓存作废，显示 --%
                            BatteryStore st = new BatteryStore(PopupService.this);
                            BatteryLevels b = st.load(addr);
                            b.left = -1;
                            b.right = -1;
                            b.caseBox = -1;
                            b.overall = -1;
                            b.source = "unavailable";
                            if (currentRoot != null && addr.equals(currentAddress)) {
                                PopupRenderer.updateInfo(currentRoot, currentName, b, prefs);
                            }
                        }
                    }));
        }).start();
    }

    /** 写入实测值并刷新当前弹窗 */
    private void applyMeasured(String addr, int value, String source) {
        if (!BatteryLevels.valid(value)) return;
        BatteryStore st = new BatteryStore(this);
        BatteryLevels b = st.load(addr);
        b.overall = value;
        b.fillFromOverall();
        b.sanitize();
        b.timestamp = System.currentTimeMillis();
        b.source = source;
        st.save(addr, b);
        if (currentRoot != null && addr.equals(currentAddress)) {
            PopupRenderer.updateInfo(currentRoot, currentName, b, prefs);
        }
    }

    private void showOverlay(String name, String address, BatteryLevels levels) {
        dismiss();
        lastOverlayShownAt = System.currentTimeMillis();
        if (!android.provider.Settings.canDrawOverlays(this)) return;

        View v = LayoutInflater.from(this).inflate(R.layout.popup_card, null);
        PopupRenderer.bind(this, v, name, levels, prefs, this::dismiss);
        currentRoot = v;
        currentName = name;

        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        // 高度必须写死：媒体区子 View 是 match_parent，
        // 用 WRAP_CONTENT 会让卡片高度失控（表现为比例奇怪）。
        // 算法与 PopupRenderer / PopupActivity 保持一致。
        float r = Math.max(0.40f, Math.min(0.94f, prefs.imageRatio()));
        int widthPx = (int) dp(prefs.widthDp());
        int heightPx = (int) (widthPx * (0.45f + r * 0.33f));

        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                widthPx,
                heightPx,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);

        // 连续垂直位置：0=贴顶 100=贴底。
        // 用 TOP gravity + 计算出的 y 偏移，比三档重力更精细可调。
        int vpos = prefs.verticalPos();
        p.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        int screenH = getResources().getDisplayMetrics().heightPixels;
        float dens = getResources().getDisplayMetrics().density;
        p.y = topOffsetForVPos(vpos, heightPx, screenH, dens);

        try {
            wm.addView(v, p);
        } catch (Exception e) {
            Toast.makeText(this, "弹窗显示失败，请检查悬浮窗权限", Toast.LENGTH_SHORT).show();
            return;
        }
        current = v;

        // 注意：这里【不能】再调 applyEnter。
        // PopupRenderer.bind() 内部已经执行过入场动画；
        // 再调一次会 cancel 掉正在进行的动画并重置 translationY(140dp)，
        // 于是弹窗滑上来后又被拽回起点重新播一遍 ——
        // 这就是「弹出来一瞬间，然后又被压下去」的原因。

        v.postDelayed(this::dismiss, prefs.durationMs());
    }

    private void dismiss() {
        if (current != null) {
            final View v = current;
            current = null;
            currentRoot = null;
            v.animate().cancel();
            v.animate().alpha(0f).translationY(-dp(24)).setDuration(200)
                    .withEndAction(() -> {
                        try {
                            wm.removeView(v);
                        } catch (Exception ignored) {
                        }
                    }).start();
        }
    }

    private float dp(float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    @Override
    public void onDestroy() {
        dismiss();
        try {
            unregisterReceiver(receiver);
        } catch (Exception ignored) {
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
