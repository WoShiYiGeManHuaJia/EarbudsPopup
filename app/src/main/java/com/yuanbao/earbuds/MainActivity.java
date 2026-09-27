package com.yuanbao.earbuds;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
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

import androidx.palette.graphics.Palette;

import com.bumptech.glide.Glide;
import com.bumptech.glide.request.target.CustomTarget;
import com.bumptech.glide.request.transition.Transition;
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
    private Spinner spPos, spAnim, spEngine;
    private SeekBar sbDim, sbBlur;
    private TextView tvDim, tvBlur;
    private SwitchMaterial swLock, swNoFocus, swCase, swAutoColor;
    private SwitchMaterial swPowerSave, swHideNoti;
    private SwitchMaterial swMaster, swWired, swAutoStart, swBattery;

    // 实时预览
    private View pvCard, pvFade;
    private FrameLayout pvImageArea;
    private ImageView pvImage;
    private TextView pvTitle, pvSub, tvDevice;
    private BatteryRingView pvRingEarbud, pvRingBox;
    private LinearLayout pvBoxGroup;

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
                updatePreview();
                extractTheme(uri);
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
        pvCard = findViewById(R.id.pvCard);
        pvImageArea = findViewById(R.id.pvImageArea);
        pvImage = findViewById(R.id.pvImage);
        pvFade = findViewById(R.id.pvFade);
        pvTitle = findViewById(R.id.pvTitle);
        pvSub = findViewById(R.id.pvSub);
        pvRingEarbud = findViewById(R.id.pvRingEarbud);
        pvRingBox = findViewById(R.id.pvRingBox);
        pvBoxGroup = findViewById(R.id.pvBoxGroup);
        tvDevice = findViewById(R.id.tvDevice);
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
        spEngine = findViewById(R.id.spEngine);
        sbDim = findViewById(R.id.sbDim);
        sbBlur = findViewById(R.id.sbBlur);
        tvDim = findViewById(R.id.tvDim);
        tvBlur = findViewById(R.id.tvBlur);
        swLock = findViewById(R.id.swLock);
        swCase = findViewById(R.id.swCase);
        swAutoColor = findViewById(R.id.swAutoColor);
        swPowerSave = findViewById(R.id.swPowerSave);
        swHideNoti = findViewById(R.id.swHideNoti);
        swNoFocus = findViewById(R.id.swNoFocus);
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
        sbImgH.setProgress((int) (prefs.imageRatio() * 100) - 40);
        sbDuration.setProgress(prefs.durationMs() / 500 - 1);
        spPos.setSelection(prefs.position());
        spAnim.setSelection(prefs.animStyle());
        spEngine.setSelection(prefs.engine());
        sbDim.setProgress((int) (prefs.dimAmount() * 100));
        sbBlur.setProgress(prefs.blurRadius());
        swLock.setChecked(prefs.showOnLock());
        if (swPowerSave != null) swPowerSave.setChecked(prefs.powerSave());
        if (swHideNoti != null) swHideNoti.setChecked(prefs.hideNotification());
        swNoFocus.setChecked(prefs.notFocusable());
        swMaster.setChecked(prefs.masterEnabled());
        swWired.setChecked(prefs.wiredEnabled());
        swAutoStart.setChecked(prefs.autoStart());
        swBattery.setChecked(prefs.showBattery());
        allowSet.clear();
        Set<String> saved = prefs.allowedDevices();
        if (saved != null) allowSet.addAll(saved);
        syncSeekLabels();
        loadPreview();
        updatePreview();
        showDeviceInfo();
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
            updatePreview();
        });
        findViewById(R.id.btnTest).setOnClickListener(v -> {
            saveAll();
            Intent s = new Intent(this, PopupService.class);
            s.setAction(PopupService.ACTION_SHOW);
            s.putExtra(PopupService.EXTRA_NAME, "我的耳机");
            s.putExtra(PopupService.EXTRA_BATTERY, prefs.showBattery() ? 78 : -1);
            // 演示用：真实连接时若读不到充电盒电量会自动隐藏该圆环
            s.putExtra(PopupService.EXTRA_CASE,
                    (prefs.showBattery() && prefs.showCaseBattery()) ? 65 : -1);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(s);
            else startService(s);
        });
        findViewById(R.id.btnSave).setOnClickListener(v -> {
            saveAll();
            Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show();
        });
        findViewById(R.id.btnSetup).setOnClickListener(
                v -> startActivity(new android.content.Intent(this, SetupActivity.class)));
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
        sbDim.setOnSeekBarChangeListener(sl);
        sbBlur.setOnSeekBarChangeListener(sl);

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
        if (swPowerSave != null) {
            swPowerSave.setOnCheckedChangeListener((b, checked) -> {
                if (!bindingUi) {
                    prefs.setPowerSave(checked);
                    restartService();
                }
            });
        }
        if (swHideNoti != null) {
            swHideNoti.setOnCheckedChangeListener((b, checked) -> {
                if (!bindingUi) {
                    prefs.setHideNotification(checked);
                    restartService();
                }
            });
        }
        findViewById(R.id.btnNotiSettings).setOnClickListener(v -> openNotificationSettings());
        findViewById(R.id.btnBattery).setOnClickListener(v -> requestBatteryWhitelist());

        watch(etTitle, etSub, etBg, etTextColor, etAccent);
        spEngine.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> p, View v, int i, long id) {
                if (!bindingUi) prefs.setEngine(i);
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
            updatePreview();
        });
        if (swCase != null) {
            swCase.setChecked(prefs.showCaseBattery());
            swCase.setOnCheckedChangeListener((b, checked) -> {
                if (!bindingUi) prefs.setShowCaseBattery(checked);
                updatePreview();
            });
        }
        if (swAutoColor != null) {
            swAutoColor.setChecked(prefs.autoColor());
            swAutoColor.setOnCheckedChangeListener((b, checked) -> {
                if (!bindingUi) {
                    prefs.setAutoColor(checked);
                    String u = prefs.imageUri();
                    if (checked && !u.isEmpty()) extractTheme(Uri.parse(u));
                    updatePreview();
                }
            });
        }
        swLock.setOnCheckedChangeListener((b, checked) -> {
            if (!bindingUi) prefs.setShowOnLock(checked);
        });
        swNoFocus.setOnCheckedChangeListener((b, checked) -> {
            if (!bindingUi) prefs.setNotFocusable(checked);
        });
        findViewById(R.id.btnMiPerm).setOnClickListener(v -> copyMiuiPermCommands());
    }

    private void syncSeekLabels() {
        tvWidth.setText("弹窗宽度 " + (sbWidth.getProgress() + 180) + "dp");
        tvRadius.setText("圆角 " + sbRadius.getProgress() + "dp");
        tvImgH.setText("图片占弹窗高度 " + (sbImgH.getProgress() + 40) + "%");
        tvDuration.setText("显示时长 " + ((sbDuration.getProgress() + 1) * 500) + "ms");
        if (!bindingUi) updatePreview();
        if (sbDim != null) tvDim.setText("背景压暗 " + sbDim.getProgress() + "%");
        if (sbBlur != null) {
            int b = sbBlur.getProgress();
            tvBlur.setText(b == 0 ? "背景模糊 关闭" : "背景模糊半径 " + b + "dp");
        }
    }

    // ---------------- 实时预览 ----------------

    private int parseColor(String v, int fallback) {
        try {
            return Color.parseColor(v);
        } catch (Exception e) {
            return fallback;
        }
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    private void showDeviceInfo() {
        if (tvDevice == null) return;
        String model = Build.MODEL;
        String device = Build.DEVICE;
        String pretty = model;
        if ("manet".equalsIgnoreCase(device) || model.contains("23117RK66C")) {
            pretty = "Redmi K70 Pro";
        }
        tvDevice.setText(pretty + "  ·  Android " + Build.VERSION.RELEASE);
    }

    /** 把当前设置渲染到预览卡片上，做到所见即所得 */
    private void updatePreview() {
        if (pvCard == null) return;

        int cardColor = parseColor(
                prefs.autoColor() ? prefs.autoBgColor() : prefs.bgColor(), 0xF2141620);
        int accent = parseColor(
                prefs.autoColor() ? prefs.autoAccentColor() : prefs.accentColor(), 0xFF00E5A0);
        int textColor = parseColor(prefs.textColor(), Color.WHITE);

        float d = getResources().getDisplayMetrics().density;
        int w = (int) (prefs.widthDp() * d);
        ViewGroup.LayoutParams lp = pvCard.getLayoutParams();
        lp.width = w;
        pvCard.setLayoutParams(lp);

        GradientDrawable gd = new GradientDrawable();
        gd.setShape(GradientDrawable.RECTANGLE);
        gd.setCornerRadius(prefs.radiusDp() * d);
        gd.setColor(cardColor);
        pvCard.setBackground(gd);
        pvCard.setElevation(12 * d);
        pvCard.setClipToOutline(true);

        // 图片区：铺满宽度，高度 = 宽度 × 比例
        if (pvImageArea != null) {
            ViewGroup.LayoutParams ilp = pvImageArea.getLayoutParams();
            ilp.height = (int) (w * prefs.imageRatio());
            pvImageArea.setLayoutParams(ilp);
        }

        // 渐变遮罩：图片底部渐隐到卡片底色
        if (pvFade != null) {
            GradientDrawable fade = new GradientDrawable(
                    GradientDrawable.Orientation.BOTTOM_TOP,
                    new int[]{cardColor, Color.TRANSPARENT});
            pvFade.setBackground(fade);
        }

        pvTitle.setTextColor(textColor);
        pvSub.setTextColor(textColor);
        String t = etTitle.getText().toString().trim();
        pvTitle.setText(t.isEmpty() ? "耳机已连接" : t);
        String subRaw = etSub.getText().toString();
        if (subRaw.trim().isEmpty()) {
            pvSub.setVisibility(View.GONE);
        } else {
            pvSub.setVisibility(View.VISIBLE);
            pvSub.setText(subRaw.contains("%s")
                    ? String.format(subRaw, "Buds 5 Pro 电竞版") : subRaw);
        }

        boolean showBat = swBattery.isChecked();
        pvRingEarbud.setVisibility(showBat ? View.VISIBLE : View.GONE);
        pvRingEarbud.setProgress(78);
        pvRingEarbud.setRingColor(accent);
        pvRingEarbud.setTrackColor(adjustAlpha(textColor, 0.22f));
        pvRingEarbud.setTextColor(textColor);
        // 预览里始终展示充电盒圆环，方便看排版
        boolean showBox = showBat && swCase != null && swCase.isChecked();
        pvBoxGroup.setVisibility(showBox ? View.VISIBLE : View.GONE);
        if (showBox) {
            pvRingBox.setProgress(65);
            pvRingBox.setRingColor(accent);
            pvRingBox.setTrackColor(adjustAlpha(textColor, 0.22f));
            pvRingBox.setTextColor(textColor);
        }

        String uri = prefs.imageUri();
        if (!uri.isEmpty()) {
            pvImage.setScaleType(ImageView.ScaleType.CENTER_CROP);
            try {
                Glide.with(this).load(Uri.parse(uri)).centerCrop().into(pvImage);
            } catch (Exception e) {
                pvImage.setImageResource(R.drawable.ic_headphone);
            }
        } else {
            pvImage.setImageResource(R.drawable.ic_headphone);
            pvImage.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        }
    }

    private int adjustAlpha(int color, float alpha) {
        int a = Math.round(255 * alpha);
        return (a << 24) | (color & 0x00FFFFFF);
    }

    /** 从所选图片提取主色调，压暗后作为卡片底色，强调色取鲜艳色 */
    private void extractTheme(Uri uri) {
        if (!prefs.autoColor()) return;
        Glide.with(this)
                .asBitmap()
                .load(uri)
                .into(new CustomTarget<Bitmap>() {
                    @Override
                    public void onResourceReady(Bitmap bmp, Transition<? super Bitmap> t) {
                        Palette.from(bmp).generate(p -> {
                            if (p == null) return;
                            int seed = p.getDominantColor(0xFF141620);
                            if (seed == 0xFF141620) {
                                seed = p.getDarkVibrantColor(seed);
                            }
                            int bg = darken(seed, 0.26f);
                            prefs.setAutoBgColor(String.format("#%08X", bg));

                            int accent = p.getVibrantColor(0);
                            if (accent == 0) accent = p.getLightVibrantColor(0);
                            if (accent == 0) accent = p.getMutedColor(0xFF00E5A0);
                            prefs.setAutoAccentColor(String.format("#%08X", accent));
                            updatePreview();
                        });
                    }

                    @Override
                    public void onLoadCleared(android.graphics.drawable.Drawable d) {
                    }
                });
    }

    /** 保留色相、压暗明度，保证白字可读 */
    private int darken(int color, float maxV) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        hsv[1] = Math.min(hsv[1] * 1.15f, 1f);
        hsv[2] = Math.min(hsv[2], maxV);
        return Color.HSVToColor(0xF2, hsv);
    }

    /** 让输入框改动即时反映到预览上 */
    private void watch(EditText... eds) {
        TextWatcher tw = new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            public void onTextChanged(CharSequence s, int a, int b, int c) {
                if (!bindingUi) updatePreview();
            }

            public void afterTextChanged(Editable s) {
            }
        };
        for (EditText e : eds) {
            if (e != null) e.addTextChangedListener(tw);
        }
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
        prefs.setImageRatio((sbImgH.getProgress() + 40) / 100f);
        prefs.setDurationMs((sbDuration.getProgress() + 1) * 500);
        prefs.setDimAmount(sbDim.getProgress() / 100f);
        prefs.setBlurRadius(sbBlur.getProgress());
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

    /** 跳转系统通知设置，用户可手动彻底关闭通知渠道 */
    private void openNotificationSettings() {
        try {
            Intent i;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                i = new Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS);
                i.putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, getPackageName());
            } else {
                i = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                i.setData(Uri.parse("package:" + getPackageName()));
            }
            startActivity(i);
            Toast.makeText(this, "把通知开关关掉即可，服务仍会运行", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "无法打开设置，请手动在系统设置里关闭本 App 的通知",
                    Toast.LENGTH_LONG).show();
        }
    }

    /** 加入电池优化白名单，防止服务被系统杀掉 */
    private void requestBatteryWhitelist() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                android.os.PowerManager pm =
                        (android.os.PowerManager) getSystemService(Context.POWER_SERVICE);
                if (pm != null && pm.isIgnoringBatteryOptimizations(getPackageName())) {
                    Toast.makeText(this, "已在白名单中", Toast.LENGTH_SHORT).show();
                    return;
                }
                Intent i = new Intent(
                        android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                i.setData(Uri.parse("package:" + getPackageName()));
                startActivity(i);
            }
        } catch (Exception e) {
            try {
                startActivity(new Intent(
                        android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Exception ignored) {
            }
        }
    }

    /** 设置变更后重启服务，让新策略生效 */
    private void restartService() {
        try {
            Intent s = new Intent(this, PopupService.class);
            s.setAction(PopupService.ACTION_RESTART);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(s);
            else startService(s);
        } catch (Exception ignored) {
        }
    }

    private void copyMiuiPermCommands() {
        String cmds =
                "# 小米「后台弹出界面」——系统级引擎在 MIUI/HyperOS 上的额外一道闸\n"
                + "# opcode 10021，先试设置再读值验证\n"
                + "adb shell appops set com.yuanbao.earbuds 10021 allow\n"
                + "adb shell appops get com.yuanbao.earbuds 10021\n\n"
                + "# 锁屏显示（部分版本用 10023/10024，报错就换一个试）\n"
                + "adb shell appops set com.yuanbao.earbuds 10023 allow\n"
                + "adb shell appops set com.yuanbao.earbuds 10024 allow\n\n"
                + "# 基础权限\n"
                + "adb shell appops set com.yuanbao.earbuds SYSTEM_ALERT_WINDOW allow\n"
                + "adb shell pm grant com.yuanbao.earbuds android.permission.BLUETOOTH_CONNECT\n"
                + "adb shell pm grant com.yuanbao.earbuds android.permission.POST_NOTIFICATIONS\n"
                + "adb shell dumpsys deviceidle whitelist +com.yuanbao.earbuds\n\n"
                + "# 验证：把上面 get 的结果发我，我来确认 opcode 对不对";
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("adb", cmds));
            Toast.makeText(this, "已复制。执行后把 get 的结果发我确认", Toast.LENGTH_LONG).show();
        }
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
