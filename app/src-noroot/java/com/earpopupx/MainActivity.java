package com.earpopupx;

import android.Manifest;
import android.app.Activity;
import android.app.ActivityManager;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.ImageDecoder;
import android.graphics.Outline;
import android.graphics.drawable.AnimatedImageDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

public final class MainActivity extends Activity {
    private static final int REQ_BT = 10, REQ_NOTIFY = 11, REQ_MEDIA = 12;

    private TextView status, serviceState, diag;
    private TextView tvOffset, tvWidth, tvMediaH, tvCorner, tvDuration, tvDim;
    private SeekBar sbOffset, sbWidth, sbMediaH, sbCorner, sbDuration, sbDim;
    private CheckBox cbBlur, cbEnabled;
    private ImageView preview;
    private boolean binding = false;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);
        bind();
        syncControls();
        loadPreview();
        refreshStatus();
    }

    private void bind() {
        status = findViewById(R.id.status);
        serviceState = findViewById(R.id.serviceState);
        diag = findViewById(R.id.diag);
        preview = findViewById(R.id.preview);

        tvOffset = findViewById(R.id.tvOffset);
        tvWidth = findViewById(R.id.tvWidth);
        tvMediaH = findViewById(R.id.tvMediaH);
        tvCorner = findViewById(R.id.tvCorner);
        tvDuration = findViewById(R.id.tvDuration);
        tvDim = findViewById(R.id.tvDim);

        sbOffset = findViewById(R.id.sbOffset);
        sbWidth = findViewById(R.id.sbWidth);
        sbMediaH = findViewById(R.id.sbMediaH);
        sbCorner = findViewById(R.id.sbCorner);
        sbDuration = findViewById(R.id.sbDuration);
        sbDim = findViewById(R.id.sbDim);

        cbBlur = findViewById(R.id.cbBlur);
        cbEnabled = findViewById(R.id.cbEnabled);

        roundPreview();
        bind((Button) findViewById(R.id.btnOverlay), v -> openOverlay());
        bind((Button) findViewById(R.id.btnBluetooth), v -> requestBluetooth());
        bind((Button) findViewById(R.id.btnNotify), v -> requestNotifications());
        bind((Button) findViewById(R.id.btnBattery), v -> {
            try {
                startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:" + getPackageName())));
            } catch (Throwable t) {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            }
        });

        bind((Button) findViewById(R.id.btnStart), v -> startMonitor());
        bind((Button) findViewById(R.id.btnStop), v -> stopMonitor());
        bind((Button) findViewById(R.id.btnTest), v -> {
            if (!Settings.canDrawOverlays(this)) { openOverlay(); return; }
            new EarPopupWindow(this).show("测试耳机", BatteryState.unknown("测试（不伪造电量）"));
        });
        bind((Button) findViewById(R.id.btnRefreshDiag), v -> refreshStatus());

        bind((Button) findViewById(R.id.btnPick), v -> pickMedia());
        bind((Button) findViewById(R.id.btnReset), v -> {
            AppPrefs.setMedia(this, null);
            preview.setImageDrawable(null);
            toast("已恢复默认");
        });

        bind((Button) findViewById(R.id.btnTop), v -> setOffset(-140));
        bind((Button) findViewById(R.id.btnMid), v -> setOffset(0));
        bind((Button) findViewById(R.id.btnBottom), v -> setOffset(140));

        sbOffset.setOnSeekBarChangeListener(bar(v -> { AppPrefs.setOffsetDp(this, v - 200); updateLabels(); }));
        sbWidth.setOnSeekBarChangeListener(bar(v -> { AppPrefs.setWidthPct(this, v + 60); updateLabels(); }));
        sbMediaH.setOnSeekBarChangeListener(bar(v -> { AppPrefs.setMediaHDp(this, v + 120); updateLabels(); }));
        sbCorner.setOnSeekBarChangeListener(bar(v -> { AppPrefs.setCornerDp(this, v + 12); updateLabels(); }));
        sbDuration.setOnSeekBarChangeListener(bar(v -> { AppPrefs.setDurationMs(this, v + 2000); updateLabels(); }));
        sbDim.setOnSeekBarChangeListener(bar(v -> { AppPrefs.setDimPct(this, v); updateLabels(); }));

        cbBlur.setOnCheckedChangeListener((btn, on) -> { if (!binding) AppPrefs.setBlur(this, on); });
        cbEnabled.setOnCheckedChangeListener((btn, on) -> { if (!binding) AppPrefs.setEnabled(this, on); });
    }

    private void bind(Button b, View.OnClickListener l) { if (b != null) b.setOnClickListener(l); }

    private interface IntConsumer { void accept(int v); }

    private SeekBar.OnSeekBarChangeListener bar(IntConsumer c) {
        return new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) { if (fromUser) c.accept(p); }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) { updateLabels(); }
        };
    }

    private void syncControls() {
        binding = true;
        sbOffset.setProgress(Math.max(0, Math.min(400, AppPrefs.offsetDp(this) + 200)));
        sbWidth.setProgress(Math.max(0, Math.min(40, AppPrefs.widthPct(this) - 60)));
        sbMediaH.setProgress(Math.max(0, Math.min(280, AppPrefs.mediaHDp(this) - 120)));
        sbCorner.setProgress(Math.max(0, Math.min(28, AppPrefs.cornerDp(this) - 12)));
        sbDuration.setProgress(Math.max(0, Math.min(13000, AppPrefs.durationMs(this) - 2000)));
        sbDim.setProgress(Math.max(0, Math.min(70, AppPrefs.dimPct(this))));
        cbBlur.setChecked(AppPrefs.blur(this));
        cbEnabled.setChecked(AppPrefs.enabled(this));
        binding = false;
        updateLabels();
    }

    private void setOffset(int dp) {
        AppPrefs.setOffsetDp(this, dp);
        binding = true;
        sbOffset.setProgress(dp + 200);
        binding = false;
        updateLabels();
    }

    private void updateLabels() {
        tvOffset.setText(String.valueOf(AppPrefs.offsetDp(this)));
        tvWidth.setText(AppPrefs.widthPct(this) + "%");
        tvMediaH.setText(AppPrefs.mediaHDp(this) + "dp");
        tvCorner.setText(AppPrefs.cornerDp(this) + "dp");
        tvDuration.setText(String.format(java.util.Locale.CHINA, "%.1f 秒", AppPrefs.durationMs(this) / 1000f));
        tvDim.setText(AppPrefs.dimPct(this) + "%");
    }

    private void roundPreview() {
        if (preview == null) return;
        GradientDrawable g = new GradientDrawable();
        g.setColor(0xFFEDEFF5);
        g.setCornerRadius(dp(14));
        preview.setBackground(g);
        preview.setClipToOutline(true);
        preview.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(14));
            }
        });
    }

    private void loadPreview() {
        String raw = AppPrefs.media(this);
        if (raw == null || preview == null) return;
        try {
            Uri u = Uri.parse(raw);
            ImageDecoder.Source src = ImageDecoder.createSource(getContentResolver(), u);
            Drawable d = ImageDecoder.decodeDrawable(src);
            preview.setImageDrawable(d);
            if (d instanceof AnimatedImageDrawable) ((AnimatedImageDrawable) d).start();
        } catch (Throwable ignored) {}
    }

    private void refreshStatus() {
        StringBuilder s = new StringBuilder();
        boolean ok = true;
        boolean ov = Settings.canDrawOverlays(this);
        s.append(ov ? "✓ " : "✗ ").append("悬浮窗").append(ov ? "：已授权" : "：未授权（必须）").append('\n');
        if (!ov) ok = false;

        if (Build.VERSION.SDK_INT >= 31) {
            boolean bt = checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
            s.append(bt ? "✓ " : "✗ ").append("蓝牙").append(bt ? "：已授权" : "：未授权").append('\n');
            if (!bt) ok = false;
        }
        if (Build.VERSION.SDK_INT >= 33) {
            boolean nt = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
            s.append(nt ? "✓ " : "✗ ").append("通知").append(nt ? "：已授权" : "：未授权（前台服务需要）").append('\n');
        }
        s.append(ok ? "\n权限已就绪。" : "\n请先补齐上面标 ✗ 的权限。");
        status.setText(s.toString());

        boolean running = isServiceRunning();
        serviceState.setText(running ? "状态：监听中" : "状态：未运行");
        diag.setText(Diag.dump());
    }

    private boolean isServiceRunning() {
        try {
            ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) return false;
            for (ActivityManager.RunningServiceInfo i : am.getRunningServices(100)) {
                if (BluetoothMonitorService.class.getName().equals(i.service.getClassName())) return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private void openOverlay() {
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())));
        } catch (Throwable t) { toast("无法打开悬浮窗设置页"); }
    }

    private void requestBluetooth() {
        if (Build.VERSION.SDK_INT >= 31) {
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN}, REQ_BT);
        } else toast("无需申请");
    }

    private void requestNotifications() {
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFY);
        } else toast("无需申请");
    }

    private void pickMedia() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("image/*");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try { startActivityForResult(i, REQ_MEDIA); } catch (Throwable t) { toast("无法打开选择器"); }
    }

    @Override protected void onActivityResult(int r, int c, Intent d) {
        super.onActivityResult(r, c, d);
        if (r == REQ_MEDIA && c == RESULT_OK && d != null && d.getData() != null) {
            try {
                getContentResolver().takePersistableUriPermission(d.getData(), Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Throwable ignored) {}
            AppPrefs.setMedia(this, d.getData());
            loadPreview();
            toast("图片 / GIF 已保存");
        }
    }

    private void startMonitor() {
        if (Build.VERSION.SDK_INT >= 31 &&
                checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            requestBluetooth();
            return;
        }
        if (!Settings.canDrawOverlays(this)) { openOverlay(); return; }
        if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestNotifications();
        }
        try {
            Intent i = new Intent(this, BluetoothMonitorService.class);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
            Diag.log("手动启动监听");
            toast("监听已启动");
        } catch (Throwable t) {
            toast("启动失败：" + t.getMessage());
        }
        serviceState.postDelayed(this::refreshStatus, 600);
    }

    private void stopMonitor() {
        try { stopService(new Intent(this, BluetoothMonitorService.class)); } catch (Throwable ignored) {}
        Diag.log("手动停止监听");
        serviceState.postDelayed(this::refreshStatus, 400);
    }

    private int dp(int n) { return (int) (n * getResources().getDisplayMetrics().density + 0.5f); }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    @Override protected void onResume() { super.onResume(); refreshStatus(); }
}
