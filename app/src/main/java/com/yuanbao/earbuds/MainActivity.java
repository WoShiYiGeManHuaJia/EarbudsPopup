package com.yuanbao.earbuds;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.bumptech.glide.Glide;
import com.google.android.material.switchmaterial.SwitchMaterial;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class MainActivity extends AppCompatActivity {

    private Prefs prefs;

    private TextView permStatus;
    private LinearLayout deviceList;
    private ImageView imgPreview;
    private EditText etTitle, etSub, etBg, etTextColor, etAccent;
    private SeekBar sbWidth, sbRadius, sbImgH, sbDuration;
    private TextView tvWidth, tvRadius, tvImgH, tvDuration;
    private Spinner spPos, spAnim;
    private SwitchMaterial swMaster, swWired, swAutoStart, swBattery;

    private final Set<String> allowSet = new LinkedHashSet<>();
    private boolean bindingUi = false;

    private final ActivityResultLauncher<String[]> pickImage =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri == null) return;
                try {
                    getContentResolver().takePersistableUriPermission(uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (Exception ignored) {
                }
                prefs.setImageUri(uri.toString());
                loadPreview();
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        prefs = new Prefs(this);

        bindViews();
        loadPrefsToUi();
        setupListeners();
        requestMissingPermissions();
        refreshDevices();
    }

    private void bindViews() {
        permStatus = findViewById(R.id.permStatus);
        deviceList = findViewById(R.id.deviceList);
        imgPreview = findViewById(R.id.imgPreview);
        etTitle = findViewById(R.id.etTitle);
        etSub = findViewById(R.id.etSub);
        etBg = findViewById(R.id.etBg);
        etTextColor = findViewById(R.id.etTextColor);
        etAccent = findViewById(R.id.etAccent);
        sbWidth = findViewById(R.id.sbWidth);
        sbRadius = findViewById(R.id.sbRadius);
        sbImgH = findViewById(R.id.sbImgH);
        sbDuration = findViewById(R.id.sbDuration);
        tvWidth = findViewById(R.id.tvWidth);
        tvRadius = findViewById(R.id.tvRadius);
        tvImgH = findViewById(R.id.tvImgH);
        tvDuration = findViewById(R.id.tvDuration);
        spPos = findViewById(R.id.spPos);
        spAnim = findViewById(R.id.spAnim);
        swMaster = findViewById(R.id.swMaster);
        swWired = findViewById(R.id.swWired);
        swAutoStart = findViewById(R.id.swAutoStart);
        swBattery = findViewById(R.id.swBattery);
    }

    private void loadPrefsToUi() {
        bindingUi = true;
        etTitle.setText(prefs.titleText());
        etSub.setText(prefs.subText());
        etBg.setText(prefs.bgColor());
        etTextColor.setText(prefs.textColor());
        etAccent.setText(prefs.accentColor());
        sbWidth.setProgress(prefs.widthDp() - 180);
        sbRadius.setProgress(prefs.radiusDp());
        sbImgH.setProgress(prefs.imageHeightDp() - 60);
        sbDuration.setProgress(prefs.durationMs() / 500 - 1);
        spPos.setSelection(prefs.position());
        spAnim.setSelection(prefs.animStyle());
        swMaster.setChecked(prefs.masterEnabled());
        swWired.setChecked(prefs.wiredEnabled());
        swAutoStart.setChecked(prefs.autoStart());
        swBattery.setChecked(prefs.showBattery());
        allowSet.clear();
        Set<String> saved = prefs.allowedDevices();
        if (saved != null) allowSet.addAll(saved);
        syncSeekLabels();
        loadPreview();
        bindingUi = false;
    }

    private void setupListeners() {
        findViewById(R.id.btnPerm).setOnClickListener(v -> openPermissionSettings());
        findViewById(R.id.btnAdb).setOnClickListener(v -> copyAdbCommands());
        findViewById(R.id.btnBatteryOpt).setOnClickListener(v -> requestBatteryWhitelist());
        findViewById(R.id.btnPickImage).setOnClickListener(v -> pickImage.launch(new String[]{"image/*"}));
        findViewById(R.id.btnClearImage).setOnClickListener(v -> {
            prefs.setImageUri("");
            loadPreview();
        });
        findViewById(R.id.btnTest).setOnClickListener(v -> {
            saveAll();
            Intent s = new Intent(this, PopupService.class);
            s.setAction(PopupService.ACTION_SHOW);
            s.putExtra(PopupService.EXTRA_NAME, "我的耳机");
            s.putExtra(PopupService.EXTRA_BATTERY, prefs.showBattery() ? 78 : -1);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(s);
            else startService(s);
        });
        findViewById(R.id.btnSave).setOnClickListener(v -> {
            saveAll();
            Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show();
        });
        findViewById(R.id.btnRefresh).setOnClickListener(v -> refreshDevices());
        findViewById(R.id.btnBlockMi).setOnClickListener(v -> showMiPopupGuide());

        SeekBar.OnSeekBarChangeListener sl = new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar sb, int p, boolean b) {
                syncSeekLabels();
            }

            public void onStartTrackingTouch(SeekBar sb) {
            }

            public void onStopTrackingTouch(SeekBar sb) {
                saveAll();
            }
        };
        sbWidth.setOnSeekBarChangeListener(sl);
        sbRadius.setOnSeekBarChangeListener(sl);
        sbImgH.setOnSeekBarChangeListener(sl);
        sbDuration.setOnSeekBarChangeListener(sl);

        spPos.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> p, View v, int i, long id) {
                if (!bindingUi) prefs.setPosition(i);
            }

            public void onNothingSelected(AdapterView<?> p) {
            }
        });
        spAnim.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> p, View v, int i, long id) {
                if (!bindingUi) prefs.setAnimStyle(i);
            }

            public void onNothingSelected(AdapterView<?> p) {
            }
        });

        swMaster.setOnCheckedChangeListener((b, checked) -> {
            if (bindingUi) return;
            prefs.setMasterEnabled(checked);
            toggleService(checked);
        });
        swWired.setOnCheckedChangeListener((b, checked) -> {
            if (!bindingUi) prefs.setWiredEnabled(checked);
        });
        swAutoStart.setOnCheckedChangeListener((b, checked) -> {
            if (!bindingUi) prefs.setAutoStart(checked);
        });
        swBattery.setOnCheckedChangeListener((b, checked) -> {
            if (!bindingUi) prefs.setShowBattery(checked);
        });
    }

    private void syncSeekLabels() {
        tvWidth.setText("弹窗宽度 " + (sbWidth.getProgress() + 180) + "dp");
        tvRadius.setText("圆角 " + sbRadius.getProgress() + "dp");
        tvImgH.setText("图片高度 " + (sbImgH.getProgress() + 60) + "dp");
        tvDuration.setText("显示时长 " + ((sbDuration.getProgress() + 1) * 500) + "ms");
    }

    private void loadPreview() {
        String uri = prefs.imageUri();
        if (uri.isEmpty()) {
            imgPreview.setImageResource(R.drawable.ic_headphone);
        } else {
            try {
                Glide.with(this).load(Uri.parse(uri)).centerCrop().into(imgPreview);
            } catch (Exception e) {
                imgPreview.setImageResource(R.drawable.ic_headphone);
            }
        }
    }

    private void saveAll() {
        String t = etTitle.getText().toString().trim();
        prefs.setTitleText(t.isEmpty() ? "耳机已连接" : t);
        prefs.setSubText(etSub.getText().toString());
        String bg = etBg.getText().toString().trim();
        prefs.setBgColor(bg.isEmpty() ? "#E6222426" : bg);
        String tc = etTextColor.getText().toString().trim();
        prefs.setTextColor(tc.isEmpty() ? "#FFFFFFFF" : tc);
        String ac = etAccent.getText().toString().trim();
        prefs.setAccentColor(ac.isEmpty() ? "#FF00E5A0" : ac);
        prefs.setWidthDp(sbWidth.getProgress() + 180);
        prefs.setRadiusDp(sbRadius.getProgress());
        prefs.setImageHeightDp(sbImgH.getProgress() + 60);
        prefs.setDurationMs((sbDuration.getProgress() + 1) * 500);
        prefs.setAllowedDevices(allowSet);
    }

    // ---------------- 设备白名单 ----------------

    private void refreshDevices() {
        deviceList.removeAllViews();
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED && Build.VERSION.SDK_INT >= 31) {
            TextView t = new TextView(this);
            t.setText("需要「蓝牙连接」权限才能列出已配对设备，点上方按钮授权。");
            deviceList.addView(t);
            return;
        }
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) {
            TextView t = new TextView(this);
            t.setText("本机不支持蓝牙");
            deviceList.addView(t);
            return;
        }
        Set<BluetoothDevice> bonded;
        try {
            bonded = adapter.getBondedDevices();
        } catch (SecurityException e) {
            return;
        }
        if (bonded == null || bonded.isEmpty()) {
            TextView t = new TextView(this);
            t.setText("没有已配对设备，先在系统蓝牙里配对耳机。");
            deviceList.addView(t);
            return;
        }
        for (BluetoothDevice d : bonded) {
            CheckBox cb = new CheckBox(this);
            String name = null;
            try {
                name = d.getName();
            } catch (SecurityException ignored) {
            }
            cb.setText((name == null ? "未知设备" : name) + "\n" + d.getAddress());
            cb.setChecked(allowSet.isEmpty() || allowSet.contains(d.getAddress()));
            cb.setOnCheckedChangeListener((b, checked) -> {
                String addr = d.getAddress();
                if (checked) allowSet.add(addr);
                else allowSet.remove(addr);
                prefs.setAllowedDevices(allowSet);
            });
            deviceList.addView(cb, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        TextView hint = new TextView(this);
        hint.setText("全部取消勾选 = 所有设备都弹窗");
        hint.setAlpha(0.6f);
        deviceList.addView(hint);
    }

    // ---------------- 权限 ----------------

    private String[] neededPermissions() {
        List<String> list = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 31) {
            list.add(Manifest.permission.BLUETOOTH_CONNECT);
            list.add(Manifest.permission.BLUETOOTH_SCAN);
        } else {
            list.add(Manifest.permission.BLUETOOTH);
            list.add(Manifest.permission.BLUETOOTH_ADMIN);
        }
        if (Build.VERSION.SDK_INT >= 33) {
            list.add(Manifest.permission.POST_NOTIFICATIONS);
            list.add(Manifest.permission.READ_MEDIA_IMAGES);
        } else {
            list.add(Manifest.permission.READ_EXTERNAL_STORAGE);
        }
        return list.toArray(new String[0]);
    }

    private void requestMissingPermissions() {
        List<String> miss = new ArrayList<>();
        for (String p : neededPermissions()) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                miss.add(p);
            }
        }
        if (!miss.isEmpty()) {
            ActivityCompat.requestPermissions(this, miss.toArray(new String[0]), 100);
        }
        refreshPermStatus();
        if (prefs.masterEnabled()) toggleService(true);
    }

    private void refreshPermStatus() {
        boolean overlay = Settings.canDrawOverlays(this);
        boolean noti;
        if (Build.VERSION.SDK_INT >= 33) {
            noti = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    == PackageManager.PERMISSION_GRANTED;
        } else {
            noti = true;
        }
        boolean bt = Build.VERSION.SDK_INT < 31
                || ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED;

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        boolean idle = Build.VERSION.SDK_INT < 23
                || (pm != null && pm.isIgnoringBatteryOptimizations(getPackageName()));

        StringBuilder sb = new StringBuilder();
        sb.append(overlay ? "✅" : "❌").append(" 悬浮窗权限").append("\n");
        sb.append(noti ? "✅" : "❌").append(" 通知权限").append("\n");
        sb.append(bt ? "✅" : "❌").append(" 蓝牙连接权限").append("\n");
        sb.append(idle ? "✅" : "⚠️").append(" 电池优化白名单").append("\n");
        if (!overlay) sb.append("\n悬浮窗权限是弹窗的前提，务必先开。");
        permStatus.setText(sb.toString());
    }

    private void openPermissionSettings() {
        if (!Settings.canDrawOverlays(this)) {
            Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivity(i);
            return;
        }
        Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + getPackageName()));
        startActivity(i);
    }

    private void requestBatteryWhitelist() {
        if (Build.VERSION.SDK_INT >= 23) {
            try {
                Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:" + getPackageName()));
                startActivity(i);
                return;
            } catch (Exception ignored) {
            }
        }
        Toast.makeText(this, "请在 设置 → 应用设置 → 本应用 → 省电策略 选择「无限制」",
                Toast.LENGTH_LONG).show();
    }

    private void showMiPopupGuide() {
        String msg =
                "Redmi K70 Pro（HyperOS 2）上关掉小米原生快连弹窗，三种方式任选一种：\n\n"
                + "【方式一｜最干净，纯系统设置】\n"
                + "设置 → 蓝牙 → 右上角/底部「高级设置」→ 关闭「小米快连」。\n"
                + "关掉后系统原生弹窗不再出现，蓝牙连接、低延迟模式都不受影响，耳机电量仍可在通知栏和蓝牙页面查看。\n\n"
                + "【方式二｜只关这一副耳机】\n"
                + "设置 → 蓝牙 → 点 Redmi Buds 5 Pro 电竞版 右边的「>」→ 关闭「连接弹窗 / 弹窗动画」。\n\n"
                + "【方式三｜ADB，温和屏蔽】\n"
                + "adb shell appops set com.xiaomi.bluetooth SYSTEM_ALERT_WINDOW ignore\n"
                + "（只禁它的悬浮窗，不动蓝牙连接。想恢复把 ignore 换成 allow）\n\n"
                + "如果三种都不生效：设置 → 蓝牙 → 高级设置 → 把「小米快连」关掉再打开一次，"
                + "或进 设置 → 应用设置 → 应用管理 → 搜索「MIUI蓝牙」→ 清除数据，然后重连耳机。";
        new android.app.AlertDialog.Builder(this)
                .setTitle("屏蔽小米原生弹窗")
                .setMessage(msg)
                .setPositiveButton("复制ADB命令", (d, w) -> copyMiBlockCommands())
                .setNegativeButton("知道了", null)
                .show();
    }

    private void copyMiBlockCommands() {
        String cmds =
                "# 屏蔽小米快连弹窗（不影响蓝牙连接）\n"
                + "adb shell appops set com.xiaomi.bluetooth SYSTEM_ALERT_WINDOW ignore\n\n"
                + "# 恢复命令（需要时用）\n"
                + "# adb shell appops set com.xiaomi.bluetooth SYSTEM_ALERT_WINDOW allow\n\n"
                + "# 本 App 自身权限\n"
                + "adb shell appops set com.yuanbao.earbuds SYSTEM_ALERT_WINDOW allow\n"
                + "adb shell pm grant com.yuanbao.earbuds android.permission.BLUETOOTH_CONNECT\n"
                + "adb shell pm grant com.yuanbao.earbuds android.permission.BLUETOOTH_SCAN\n"
                + "adb shell pm grant com.yuanbao.earbuds android.permission.POST_NOTIFICATIONS\n"
                + "adb shell dumpsys deviceidle whitelist +com.yuanbao.earbuds";
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("adb", cmds));
            Toast.makeText(this, "已复制屏蔽命令", Toast.LENGTH_SHORT).show();
        }
    }

    private void copyAdbCommands() {
        String cmds =
                "# 1) 悬浮窗权限（小米上最省事的一步）\n"
                        + "adb shell appops set com.yuanbao.earbuds SYSTEM_ALERT_WINDOW allow\n\n"
                        + "# 2) 蓝牙 / 通知 / 存储权限\n"
                        + "adb shell pm grant com.yuanbao.earbuds android.permission.BLUETOOTH_CONNECT\n"
                        + "adb shell pm grant com.yuanbao.earbuds android.permission.BLUETOOTH_SCAN\n"
                        + "adb shell pm grant com.yuanbao.earbuds android.permission.POST_NOTIFICATIONS\n"
                        + "adb shell pm grant com.yuanbao.earbuds android.permission.READ_MEDIA_IMAGES\n"
                        + "adb shell pm grant com.yuanbao.earbuds android.permission.READ_EXTERNAL_STORAGE\n\n"
                        + "# 3) 加入省电白名单，防止服务被杀\n"
                        + "adb shell dumpsys deviceidle whitelist +com.yuanbao.earbuds\n\n"
                        + "# 4) 屏蔽小米原生快连弹窗（只禁悬浮窗，不影响蓝牙连接）\n"
                        + "adb shell appops set com.xiaomi.bluetooth SYSTEM_ALERT_WINDOW ignore\n\n"
                        + "# 5) K70 Pro 后台保活\n"
                        + "adb shell dumpsys deviceidle whitelist +com.yuanbao.earbuds\n";
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("adb", cmds));
            Toast.makeText(this, "ADB 命令已复制", Toast.LENGTH_SHORT).show();
        }
    }

    private void toggleService(boolean on) {
        Intent s = new Intent(this, PopupService.class);
        try {
            if (on) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(s);
                else startService(s);
            } else {
                stopService(s);
            }
        } catch (Exception e) {
            Toast.makeText(this, "服务启动失败：" + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshPermStatus();
    }
}
