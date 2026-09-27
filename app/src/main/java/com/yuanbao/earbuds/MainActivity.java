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
import android.os.Handler;
import android.os.Looper;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
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
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.color.DynamicColors;
import com.google.android.material.switchmaterial.SwitchMaterial;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 主界面：Material 3 三页结构（首页 / 外观 / 设置）+ 底部导航。
 */
public class MainActivity extends AppCompatActivity {

    private Prefs prefs;

    // 首页
    private TextView permStatus, tvDevice;
    private View pvCard;
    private FrameLayout pvImageArea;
    private ImageView pvImage;
    private TextView pvInfo, pvTip;

    // 外观
    private ImageView imgPreview;
    private EditText etTitle, etSub, etBg, etTextColor, etAccent;
    private TextView tvWidth, tvRadius, tvImgH, tvDuration, tvPos, tvAnim;
    private SwitchMaterial swAutoColor;

    // 设置
    private SwitchMaterial swMaster, swWired, swAutoStart, swBattery, swCase;
    private SwitchMaterial swLock, swNoFocus, swPowerSave, swHideNoti;
    private TextView tvEngine, tvDim, tvBlur;
    private LinearLayout deviceList;
    private TextView tvProbeHint;
    private SwitchMaterial swHideRecents;

    private View tabHome, tabLook, tabSet;

    private final Set<String> allowSet = new LinkedHashSet<>();
    private boolean bindingUi = false;
    private boolean saving = false;

    private final ActivityResultLauncher<String[]> pickImage =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri == null) return;
                try {
                    getContentResolver().takePersistableUriPermission(uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (Exception ignored) {
                }
                prefs.setImageUri(uri.toString());
                loadThumb();
                updatePreview();
                extractTheme(uri);
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // Android 12+ 取壁纸色，让 App 跟系统主题融为一体
        DynamicColors.applyToActivityIfAvailable(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        prefs = new Prefs(this);

        setupToolbarAndNav();
        bindViews();
        loadPrefsToUi();
        setupListeners();
        requestMissingPermissions();
        refreshDevices();
    }

    // ---------------- 顶部栏与底部导航 ----------------

    private void setupToolbarAndNav() {
        com.google.android.material.appbar.MaterialToolbar tb = findViewById(R.id.toolbar);
        if (tb != null) {
            tb.setOnMenuItemClickListener(item -> {
                if (item.getItemId() == R.id.action_setup) {
                    startActivity(new Intent(this, SetupActivity.class));
                    return true;
                }
                return false;
            });
        }

        BottomNavigationView nav = findViewById(R.id.bottomNav);
        if (nav != null) {
            nav.setOnItemSelectedListener(item -> {
                int id = item.getItemId();
                showTab(id == R.id.nav_look ? 1 : id == R.id.nav_settings ? 2 : 0);
                return true;
            });
        }
    }

    private void showTab(int i) {
        tabHome.setVisibility(i == 0 ? View.VISIBLE : View.GONE);
        tabLook.setVisibility(i == 1 ? View.VISIBLE : View.GONE);
        tabSet.setVisibility(i == 2 ? View.VISIBLE : View.GONE);
        com.google.android.material.appbar.MaterialToolbar tb = findViewById(R.id.toolbar);
        if (tb != null) {
            tb.setTitle(i == 0 ? "耳机弹窗" : i == 1 ? "外观" : "设置");
        }
        if (i == 0) updatePreview();
    }

    // ---------------- 绑定 ----------------

    private void bindViews() {
        tabHome = findViewById(R.id.tabHome);
        tabLook = findViewById(R.id.tabLook);
        tabSet = findViewById(R.id.tabSet);

        permStatus = findViewById(R.id.permStatus);
        tvDevice = findViewById(R.id.tvDevice);
        pvCard = findViewById(R.id.pvCard);
        pvImageArea = findViewById(R.id.pvImageArea);
        pvImage = findViewById(R.id.pvImage);
        pvInfo = findViewById(R.id.pvInfo);
        pvTip = findViewById(R.id.pvTip);

        imgPreview = findViewById(R.id.imgPreview);
        etTitle = findViewById(R.id.etTitle);
        etSub = findViewById(R.id.etSub);
        etBg = findViewById(R.id.etBg);
        etTextColor = findViewById(R.id.etTextColor);
        etAccent = findViewById(R.id.etAccent);
        tvWidth = findViewById(R.id.tvWidth);
        tvRadius = findViewById(R.id.tvRadius);
        tvImgH = findViewById(R.id.tvImgH);
        tvDuration = findViewById(R.id.tvDuration);
        tvPos = findViewById(R.id.tvPos);
        tvAnim = findViewById(R.id.tvAnim);
        swAutoColor = findViewById(R.id.swAutoColor);

        swMaster = findViewById(R.id.swMaster);
        swWired = findViewById(R.id.swWired);
        swAutoStart = findViewById(R.id.swAutoStart);
        swBattery = findViewById(R.id.swBattery);
        swCase = findViewById(R.id.swCase);
        swLock = findViewById(R.id.swLock);
        swNoFocus = findViewById(R.id.swNoFocus);
        swPowerSave = findViewById(R.id.swPowerSave);
        swHideNoti = findViewById(R.id.swHideNoti);
        tvEngine = findViewById(R.id.tvEngine);
        tvDim = findViewById(R.id.tvDim);
        tvBlur = findViewById(R.id.tvBlur);
        deviceList = findViewById(R.id.deviceList);
        tvProbeHint = findViewById(R.id.tvProbeHint);
        swHideRecents = findViewById(R.id.swHideRecents);
    }

    // ---------------- 载入与保存 ----------------

    private void loadPrefsToUi() {
        bindingUi = true;
        allowSet.clear();
        allowSet.addAll(prefs.allowedDevices());

        etTitle.setText(prefs.titleText());
        etSub.setText(prefs.subText());
        etBg.setText(prefs.bgColor());
        etTextColor.setText(prefs.textColor());
        etAccent.setText(prefs.accentColor());

        swMaster.setChecked(prefs.masterEnabled());
        swWired.setChecked(prefs.wiredEnabled());
        swAutoStart.setChecked(prefs.autoStart());
        swBattery.setChecked(prefs.showBattery());
        swCase.setChecked(prefs.showCaseBattery());
        swLock.setChecked(prefs.showOnLock());
        swNoFocus.setChecked(prefs.notFocusable());
        swPowerSave.setChecked(prefs.powerSave());
        swHideNoti.setChecked(prefs.hideNotification());
        if (swHideRecents != null) swHideRecents.setChecked(prefs.hideFromRecents());
        swAutoColor.setChecked(prefs.autoColor());

        syncValueLabels();
        loadThumb();
        updatePreview();
        showDeviceInfo();
        bindingUi = false;
    }

    private void saveAll() {
        String t = etTitle.getText().toString().trim();
        prefs.setTitleText(t.isEmpty() ? "耳机已连接" : t);
        prefs.setSubText(etSub.getText().toString());
        String bg = etBg.getText().toString().trim();
        prefs.setBgColor(bg.isEmpty() ? "#F2141620" : bg);
        String tc = etTextColor.getText().toString().trim();
        prefs.setTextColor(tc.isEmpty() ? "#FFFFFFFF" : tc);
        String ac = etAccent.getText().toString().trim();
        prefs.setAccentColor(ac.isEmpty() ? "#FF00E5A0" : ac);
        prefs.setAllowedDevices(allowSet);
        syncValueLabels();
    }

    /** 把所有数值项的当前值刷新到行尾 */
    private void syncValueLabels() {
        tvWidth.setText(prefs.widthDp() + " dp");
        tvRadius.setText(prefs.radiusDp() + " dp");
        tvImgH.setText(Math.round(prefs.imageRatio() * 100) + "%");
        tvDuration.setText(prefs.durationMs() + " ms");
        tvPos.setText(new String[]{"顶部", "居中", "底部"}[prefs.position()]);
        tvAnim.setText(new String[]{"缩放淡入", "底部上滑", "顶部下滑"}[prefs.animStyle()]);
        tvEngine.setText(new String[]{"系统级", "悬浮窗", "智能"}[prefs.engine()]);
        tvDim.setText(Math.round(prefs.dimAmount() * 100) + "%");
        tvBlur.setText(prefs.blurRadius() + " dp");
    }

    // ---------------- 事件 ----------------

    private void setupListeners() {
        findViewById(R.id.btnSetup).setOnClickListener(
                v -> startActivity(new Intent(this, SetupActivity.class)));

        findViewById(R.id.btnTest).setOnClickListener(v -> {
            saveAll();
            Intent s = new Intent(this, PopupService.class);
            s.setAction(PopupService.ACTION_SHOW);
            s.putExtra(PopupService.EXTRA_NAME, "我的耳机");
            boolean show = prefs.showBattery();
            s.putExtra(PopupService.EXTRA_LEFT, show ? 78 : -1);
            s.putExtra(PopupService.EXTRA_BATTERY, show ? 78 : -1);
            s.putExtra(PopupService.EXTRA_CASE,
                    (show && prefs.showCaseBattery()) ? 65 : -1);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(s);
            else startService(s);
        });

        findViewById(R.id.btnPickImage).setOnClickListener(
                v -> pickImage.launch(new String[]{"image/*"}));
        findViewById(R.id.btnClearImage).setOnClickListener(v -> {
            prefs.setImageUri("");
            loadThumb();
            updatePreview();
        });

        // 数值项：点一下弹滑块
        findViewById(R.id.rowWidth).setOnClickListener(v -> showSlider("弹窗宽度", "dp",
                220, 400, prefs.widthDp(), val -> prefs.setWidthDp(val)));
        findViewById(R.id.rowRadius).setOnClickListener(v -> showSlider("卡片圆角", "dp",
                0, 40, prefs.radiusDp(), val -> prefs.setRadiusDp(val)));
        findViewById(R.id.rowImgH).setOnClickListener(v -> showSlider("图片占弹窗高度", "%",
                40, 90, Math.round(prefs.imageRatio() * 100),
                val -> prefs.setImageRatio(val / 100f)));
        findViewById(R.id.rowDuration).setOnClickListener(v -> showSlider("显示时长", "ms",
                500, 20000, prefs.durationMs(), val -> prefs.setDurationMs(val)));
        findViewById(R.id.rowDim).setOnClickListener(v -> showSlider("背景压暗", "%",
                0, 70, Math.round(prefs.dimAmount() * 100),
                val -> prefs.setDimAmount(val / 100f)));
        findViewById(R.id.rowBlur).setOnClickListener(v -> showSlider("背景模糊半径", "dp",
                0, 40, prefs.blurRadius(), val -> prefs.setBlurRadius(val)));

        // 单选项
        findViewById(R.id.rowPos).setOnClickListener(v -> showChoice("弹出位置",
                new String[]{"屏幕顶部", "屏幕居中", "屏幕底部"}, prefs.position(),
                prefs::setPosition));
        findViewById(R.id.rowAnim).setOnClickListener(v -> showChoice("入场动画",
                new String[]{"缩放淡入", "底部上滑", "顶部下滑"}, prefs.animStyle(),
                prefs::setAnimStyle));
        findViewById(R.id.rowEngine).setOnClickListener(v -> showChoice("弹出引擎",
                new String[]{"系统级（透明 Activity，锁屏也能弹）",
                        "悬浮窗（兼容性最好）",
                        "智能：先系统级，失败自动降级悬浮窗"},
                prefs.engine(), prefs::setEngine));

        // 开关
        bindSwitch(swMaster, prefs::setMasterEnabled, () -> toggleService(swMaster.isChecked()));
        bindSwitch(swWired, prefs::setWiredEnabled, null);
        bindSwitch(swAutoStart, prefs::setAutoStart, null);
        bindSwitch(swBattery, prefs::setShowBattery, this::updatePreview);
        bindSwitch(swCase, prefs::setShowCaseBattery, this::updatePreview);
        bindSwitch(swLock, prefs::setShowOnLock, null);
        bindSwitch(swNoFocus, prefs::setNotFocusable, null);
        bindSwitch(swPowerSave, prefs::setPowerSave, this::restartService);
        bindSwitch(swHideNoti, prefs::setHideNotification, this::restartService);
        bindSwitch(swHideRecents, v -> {
            prefs.setHideFromRecents(v);
            applyRecentsHidden(v);
        }, null);
        bindSwitch(swAutoColor, v -> {
            prefs.setAutoColor(v);
            String u = prefs.imageUri();
            if (v && !u.isEmpty()) extractTheme(Uri.parse(u));
        }, this::updatePreview);

        // 首页快捷操作
        findViewById(R.id.rowBlockMi).setOnClickListener(v -> showMiPopupGuide());
        findViewById(R.id.rowAdb).setOnClickListener(v -> copyAdbCommands());

        // 设置页
        findViewById(R.id.btnRefresh).setOnClickListener(v -> refreshDevices());
        findViewById(R.id.btnNotiSettings).setOnClickListener(v -> openNotificationSettings());
        findViewById(R.id.btnBattery).setOnClickListener(v -> requestBatteryWhitelist());
        findViewById(R.id.rowMiPerm).setOnClickListener(v -> copyMiuiPermCommands());
        findViewById(R.id.rowProbe).setOnClickListener(v -> runBatteryProbe());
        findViewById(R.id.rowInfo).setOnClickListener(v -> showAbout());

        watch(etTitle, etSub, etBg, etTextColor, etAccent);
    }

    private void bindSwitch(SwitchMaterial sw, java.util.function.Consumer<Boolean> save,
                            Runnable after) {
        if (sw == null) return;
        sw.setOnCheckedChangeListener((b, checked) -> {
            if (bindingUi) return;
            save.accept(checked);
            if (after != null) after.run();
        });
    }

    private void watch(EditText... eds) {
        TextWatcher tw = new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            public void onTextChanged(CharSequence s, int a, int b, int c) {
                if (bindingUi) return;
                updatePreview();
                // 边打字边落库：之前必须点「保存设置」才写进去，
                // 用户改了直接连耳机会发现没变化
                if (saving) return;
                saving = true;
                saveAll();
                saving = false;
            }

            public void afterTextChanged(Editable s) {
            }
        };
        for (EditText e : eds) {
            if (e != null) e.addTextChangedListener(tw);
        }
    }

    // ---------------- 对话框 ----------------

    private void showSlider(String title, String unit, int min, int max, int current,
                            java.util.function.Consumer<Integer> onPick) {
        showSlider(title, unit, min, max, current, onPick, true);
    }

    /**
     * @param live 是否在拖动过程中就应用并刷新预览。
     *             之前只有点「确定」才生效，用户拖滑块看不到任何变化，
     *             以为参数没用；现在默认边拖边变，点确定只是最终落库。
     */
    private void showSlider(String title, String unit, int min, int max, int current,
                            java.util.function.Consumer<Integer> onPick, boolean live) {
        final int[] value = {clamp(current, min, max)};
        TextView label = new TextView(this, null, android.R.attr.textAppearanceMedium);
        label.setText(value[0] + " " + unit);
        label.setTextSize(16);
        label.setPadding(24, 12, 24, 4);

        SeekBar sb = new SeekBar(this);
        sb.setMax(max - min);
        sb.setProgress(value[0] - min);
        sb.setPadding(16, 8, 16, 8);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(8, 8, 8, 0);
        box.addView(label);
        box.addView(sb);
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int p, boolean b) {
                value[0] = p + min;
                label.setText(value[0] + " " + unit);
                if (live) {
                    onPick.accept(value[0]);   // 实时写入设置
                    syncValueLabels();         // 行尾数字跟着变
                    updatePreview();           // 预览卡片立刻变化
                }
            }

            public void onStartTrackingTouch(SeekBar s) {
            }

            public void onStopTrackingTouch(SeekBar s) {
            }
        });

        final int original = clamp(current, min, max);
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(title)
                .setView(box)
                .setNegativeButton("取消", (d, w) -> {
                    // 拖动时已经实时改过了，取消要还原，否则等于改了却说没改
                    onPick.accept(original);
                    syncValueLabels();
                    updatePreview();
                })
                .setPositiveButton("确定", (d, w) -> {
                    onPick.accept(value[0]);
                    syncValueLabels();
                    updatePreview();
                })
                .show();
    }

    private void showChoice(String title, String[] items, int current,
                            java.util.function.Consumer<Integer> onPick) {
        final int[] sel = {current};
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(title)
                .setSingleChoiceItems(items, current, (d, w) -> sel[0] = w)
                .setNegativeButton("取消", null)
                .setPositiveButton("确定", (d, w) -> {
                    onPick.accept(sel[0]);
                    syncValueLabels();
                    updatePreview();
                })
                .show();
    }

    private int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    // ---------------- 预览 ----------------

    /** 动态设置 LinearLayout 子项 weight */
    private void setWeight(View v, int weight) {
        if (v == null) return;
        ViewGroup.LayoutParams lp = v.getLayoutParams();
        if (lp instanceof LinearLayout.LayoutParams) {
            ((LinearLayout.LayoutParams) lp).weight = weight;
            v.setLayoutParams(lp);
        }
    }

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
        String pretty = Build.MODEL;
        if ("manet".equalsIgnoreCase(Build.DEVICE) || Build.MODEL.contains("23117RK66C")) {
            pretty = "Redmi K70 Pro";
        }
        tvDevice.setText(pretty + "  ·  Android " + Build.VERSION.RELEASE);
    }

    private void updatePreview() {
        if (pvCard == null) return;

        int cardColor = parseColor(
                prefs.autoColor() ? prefs.autoBgColor() : prefs.bgColor(), 0x1FFFFFFF);
        int textColor = parseColor(prefs.textColor(), Color.WHITE);

        float d = getResources().getDisplayMetrics().density;
        // 预览按屏幕比例缩小，比例与真实弹窗一致
        int w = (int) (prefs.widthDp() * d * 0.78f);
        int h = (int) (w * 1.15f);
        ViewGroup.LayoutParams lp = pvCard.getLayoutParams();
        lp.width = w;
        lp.height = h;
        pvCard.setLayoutParams(lp);

        GradientDrawable gd = new GradientDrawable();
        gd.setShape(GradientDrawable.RECTANGLE);
        gd.setCornerRadius(prefs.radiusDp() * d * 0.78f);
        gd.setColor(cardColor);
        gd.setStroke(Math.max(1, (int) d), 0x33FFFFFF);
        pvCard.setBackground(gd);
        pvCard.setElevation(12 * d);
        pvCard.setClipToOutline(true);

        // 三区比例与真实弹窗用同一套算法，预览才等于所见即所得
        float ratio = Math.max(0.30f, Math.min(0.92f, prefs.imageRatio()));
        int gifW = Math.round(ratio * 100);
        int rest = Math.max(6, 100 - gifW);
        int infoW = Math.round(rest * 2f / 3f);
        int tipW = Math.max(2, rest - infoW);
        setWeight(pvImageArea, gifW);
        setWeight(pvInfo, infoW);
        setWeight(pvTip, tipW);

        // 预览里的图片区也做圆角裁切，跟真实弹窗保持一致
        if (pvImageArea != null) {
            final float pr = 16 * d * 0.78f;
            pvImageArea.setOutlineProvider(new android.view.ViewOutlineProvider() {
                @Override
                public void getOutline(android.view.View v, android.graphics.Outline o) {
                    o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), pr);
                }
            });
            pvImageArea.setClipToOutline(true);
        }

        // 信息窄条：与真实弹窗同一套拼接逻辑
        int bat = swBattery.isChecked() ? 78 : -1;
        int cas = (swBattery.isChecked() && swCase.isChecked()) ? 65 : -1;
        String devName = etTitle.getText().toString().trim();
        if (devName.isEmpty()) devName = "我的耳机";
        StringBuilder sb = new StringBuilder(devName);
        String sub = etSub.getText().toString().trim();
        if (!sub.isEmpty()) sb.append(" · ").append(sub.contains("%s")
                ? String.format(sub, devName) : sub);
        if (bat >= 0 || cas >= 0) {
            sb.append("  |  L:").append(bat >= 0 ? bat + "%" : "--%")
              .append("  R:").append(bat >= 0 ? bat + "%" : "--%")
              .append("  Case:").append(cas >= 0 ? cas + "%" : "--%");
        }
        pvInfo.setText(sb.toString());
        pvInfo.setTextColor(adjustAlpha(textColor, 0.82f));
        pvTip.setTextColor(adjustAlpha(textColor, 0.55f));

        String uri = prefs.imageUri();
        if (!uri.isEmpty()) {
            pvImage.setScaleType(ImageView.ScaleType.FIT_CENTER);
            try {
                Glide.with(this).load(Uri.parse(uri)).dontTransform().into(pvImage);
            } catch (Exception e) {
                pvImage.setImageResource(R.drawable.ic_headphone);
            }
        } else {
            pvImage.setImageResource(R.drawable.ic_headphone);
            pvImage.setScaleType(ImageView.ScaleType.FIT_CENTER);
        }
    }

    private int adjustAlpha(int color, float alpha) {
        int a = Math.round(255 * alpha);
        return (a << 24) | (color & 0x00FFFFFF);
    }

    private void loadThumb() {
        if (imgPreview == null) return;
        String uri = prefs.imageUri();
        if (!uri.isEmpty()) {
            imgPreview.setScaleType(ImageView.ScaleType.CENTER_CROP);
            try {
                Glide.with(this).load(Uri.parse(uri)).centerCrop().into(imgPreview);
            } catch (Exception e) {
                imgPreview.setImageResource(R.drawable.ic_headphone);
            }
        } else {
            imgPreview.setImageResource(R.drawable.ic_headphone);
            imgPreview.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        }
    }

    /** 从图片提取主色调，压暗后作为卡片底色，强调色取鲜艳色 */
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
                            if (seed == 0xFF141620) seed = p.getDarkVibrantColor(seed);
                            prefs.setAutoBgColor(String.format("#%08X", darken(seed, 0.26f)));
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

    // ---------------- 设备白名单 ----------------

    private void refreshDevices() {
        deviceList.removeAllViews();
        if (Build.VERSION.SDK_INT >= 31
                && ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
            deviceList.addView(hintText("需要「蓝牙连接」权限才能列出已配对设备，先回首页点一键设置。"));
            return;
        }
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) {
            deviceList.addView(hintText("本机不支持蓝牙"));
            return;
        }
        Set<BluetoothDevice> bonded;
        try {
            bonded = adapter.getBondedDevices();
        } catch (SecurityException e) {
            return;
        }
        if (bonded == null || bonded.isEmpty()) {
            deviceList.addView(hintText("没有已配对设备，先在系统蓝牙里配对耳机。"));
            return;
        }
        for (BluetoothDevice d : bonded) {
            final String addr = d.getAddress();
            final String[] sysName = new String[1];
            try {
                sysName[0] = d.getName();
            } catch (SecurityException e) {
                sysName[0] = null;
            }
            String custom = prefs.deviceName(addr);
            String shown = (custom != null && !custom.trim().isEmpty()) ? custom.trim() : sysName[0];

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setPadding(0, 6, 0, 6);

            MaterialCheckBox cb = new MaterialCheckBox(this);
            cb.setText((shown == null ? "未知设备" : shown) + "\n" + addr);
            cb.setChecked(allowSet.isEmpty() || allowSet.contains(addr));
            cb.setOnCheckedChangeListener((b, checked) -> {
                if (checked) allowSet.add(addr);
                else allowSet.remove(addr);
                prefs.setAllowedDevices(allowSet);
            });
            LinearLayout.LayoutParams cbLp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            row.addView(cb, cbLp);

            // 改名：弹窗上显示什么名字，由这里决定，优先级高于系统蓝牙名
            com.google.android.material.button.MaterialButton rename =
                    new com.google.android.material.button.MaterialButton(this,
                            null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
            rename.setText("改名");
            rename.setMinWidth(0);
            rename.setMinimumWidth(0);
            rename.setPadding(12, 0, 12, 0);
            rename.setTextSize(11);
            rename.setOnClickListener(v -> showRenameDialog(addr, sysName[0], (name, addr2) -> {
                refreshDevices();
            }));
            row.addView(rename, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            deviceList.addView(row, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        deviceList.addView(hintText("全部取消勾选 = 所有设备都弹窗"));
        deviceList.addView(hintText("点「改名」可自定义弹窗上显示的名字，优先级高于系统蓝牙名"));
    }

    private void showRenameDialog(String addr, String sysName, RenameDone done) {
        String cur = prefs.deviceName(addr);
        android.widget.EditText input = new android.widget.EditText(this);
        input.setSingleLine(true);
        input.setHint(sysName == null ? "留空则用系统蓝牙名" : sysName);
        input.setText(cur == null ? "" : cur);
        input.setSelection(input.getText().length());

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(48, 24, 48, 0);
        box.addView(input);

        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("弹窗显示名")
                .setMessage("设备 " + addr + "\n留空则跟随系统蓝牙名（" + sysName + "）")
                .setView(box)
                .setNeutralButton("清空", (d, w) -> {
                    prefs.clearDeviceName(addr);
                    Toast.makeText(this, "已恢复为系统蓝牙名", Toast.LENGTH_SHORT).show();
                    if (done != null) done.onDone("", addr);
                })
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", (d, w) -> {
                    String v = input.getText().toString().trim();
                    prefs.setDeviceName(addr, v);
                    Toast.makeText(this, v.isEmpty() ? "已恢复为系统蓝牙名" : "已保存：" + v,
                            Toast.LENGTH_SHORT).show();
                    if (done != null) done.onDone(v, addr);
                })
                .show();
    }

    private interface RenameDone {
        void onDone(String name, String addr);
    }

    private TextView hintText(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(12.5f);
        t.setAlpha(0.7f);
        t.setPadding(4, 8, 4, 8);
        return t;
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
        applyRecentsHidden(prefs.hideFromRecents());
    }

    private void refreshPermStatus() {
        boolean overlay = Settings.canDrawOverlays(this);
        boolean noti = Build.VERSION.SDK_INT < 33
                || ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
        boolean bt = Build.VERSION.SDK_INT < 31
                || ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED;
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        boolean idle = Build.VERSION.SDK_INT < 23
                || (pm != null && pm.isIgnoringBatteryOptimizations(getPackageName()));

        int ok = overlay && noti && bt ? 1 : 0;
        StringBuilder sb = new StringBuilder();
        if (ok == 1) {
            sb.append("权限已就绪，连接耳机就会弹窗。");
        } else {
            sb.append("还差一步：");
            List<String> miss = new ArrayList<>();
            if (!overlay) miss.add("悬浮窗");
            if (!noti) miss.add("通知");
            if (!bt) miss.add("蓝牙");
            sb.append(android.text.TextUtils.join("、", miss));
            sb.append("。点下面的按钮，App 会自己跑完。");
        }
        if (!idle) sb.append("\n建议加入电池白名单，防止服务被回收。");
        if (!overlay) {
            sb.append("\n\n⚠ 悬浮窗权限被关闭（SYSTEM_ALERT_WINDOW=ignore），"
                    + "悬浮窗引擎会失效。点「一键设置」重新授予。");
        }
        permStatus.setText(sb.toString());
    }

    private void openPermissionSettings() {
        if (!Settings.canDrawOverlays(this)) {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
            return;
        }
        startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + getPackageName())));
    }

    private void requestBatteryWhitelist() {
        if (Build.VERSION.SDK_INT >= 23) {
            try {
                startActivity(new Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:" + getPackageName())));
                return;
            } catch (Exception ignored) {
            }
        }
        Toast.makeText(this, "请手动在系统设置里把本 App 的电池优化设为「无限制」",
                Toast.LENGTH_LONG).show();
    }

    private void openNotificationSettings() {
        try {
            Intent i;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                i = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
                i.putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
            } else {
                i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                i.setData(Uri.parse("package:" + getPackageName()));
            }
            startActivity(i);
            Toast.makeText(this, "把通知开关关掉即可，服务仍会运行", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "无法打开设置，请手动在系统设置里关闭本 App 的通知",
                    Toast.LENGTH_LONG).show();
        }
    }

    private void restartService() {
        try {
            Intent s = new Intent(this, PopupService.class);
            s.setAction(PopupService.ACTION_RESTART);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(s);
            else startService(s);
        } catch (Exception ignored) {
        }
    }

    // ---------------- 提示与命令 ----------------

    /**
     * 电量探测：扫描 BLE 广播 + 读取 GATT Battery Service 全部实例，
     * 把完整服务树和原始字节 dump 出来，用于确定这台耳机实际的电量字段布局。
     */
    @android.annotation.SuppressLint("MissingPermission")
    private void runBatteryProbe() {
        if (Build.VERSION.SDK_INT >= 31 && ContextCompat.checkSelfPermission(
                this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "需要蓝牙连接权限，先回首页点一键设置", Toast.LENGTH_LONG).show();
            return;
        }
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) {
            Toast.makeText(this, "本机不支持蓝牙", Toast.LENGTH_SHORT).show();
            return;
        }
        java.util.Set<BluetoothDevice> bonded;
        try {
            bonded = adapter.getBondedDevices();
        } catch (SecurityException e) {
            return;
        }
        if (bonded == null || bonded.isEmpty()) {
            Toast.makeText(this, "没有已配对设备", Toast.LENGTH_SHORT).show();
            return;
        }

        // 优先挑名字像耳机的，否则让用户从列表里选
        java.util.List<BluetoothDevice> list = new ArrayList<>(bonded);
        BluetoothDevice pick = null;
        for (BluetoothDevice d : list) {
            String n;
            try {
                n = d.getName();
            } catch (SecurityException e) {
                n = null;
            }
            if (n != null) {
                String low = n.toLowerCase();
                if (low.contains("buds") || low.contains("airpods") || low.contains("耳机")
                        || low.contains("pod") || low.contains("tws")) {
                    pick = d;
                    break;
                }
            }
        }

        if (pick == null && list.size() == 1) {
            pick = list.get(0);
        }

        if (pick == null) {
            String[] names = new String[list.size()];
            for (int i = 0; i < list.size(); i++) {
                String n;
                try {
                    n = list.get(i).getName();
                } catch (SecurityException e) {
                    n = null;
                }
                names[i] = (n == null ? "未知" : n) + "\n" + list.get(i).getAddress();
            }
            final int[] sel = {0};
            new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                    .setTitle("选择要探测的设备")
                    .setSingleChoiceItems(names, 0, (d, w) -> sel[0] = w)
                    .setNegativeButton("取消", null)
                    .setPositiveButton("开始探测", (d, w) -> doProbe(list.get(sel[0])))
                    .show();
            return;
        }
        doProbe(pick);
    }

    private void doProbe(BluetoothDevice dev) {
        final String addr = dev.getAddress();
        final String[] nHolder = new String[1];
        try {
            nHolder[0] = dev.getName();
        } catch (SecurityException e) {
            nHolder[0] = null;
        }
        final String n = nHolder[0];

        final android.app.ProgressDialog pd = new android.app.ProgressDialog(this);
        pd.setTitle("正在探测");
        pd.setMessage((n == null ? addr : n) + "\n扫描 BLE 广播并读取 GATT，最多 25 秒…");
        pd.setCancelable(false);
        pd.show();

        final BatteryProbe probe = new BatteryProbe(this);
        final boolean[] finished = new boolean[1];

        // 硬超时兜底：GATT 卡死或回调没来时也不能让用户干等，
        // 25 秒后强制用当前累积日志弹出结果窗口
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (finished[0]) return;
            finished[0] = true;
            dismissQuietly(pd);
            showProbeResult(n, addr, null, probe.currentLog()
                    + "\n\n[超时兜底] 探测超过 25 秒未结束，以上是不完整日志。");
        }, 25000);

        probe.probe(addr, dev, (levels, diagnostic) -> {
            if (finished[0]) return;
            finished[0] = true;
            dismissQuietly(pd);
            showProbeResult(n, addr, levels, diagnostic);
        });
    }

    private void dismissQuietly(android.app.Dialog d) {
        try {
            d.dismiss();
        } catch (Exception ignored) {
        }
    }

    /** 探测结果窗口：无论成功失败都会出现，并且一定能导出日志 */
    private void showProbeResult(String name, String addr, BatteryLevels levels,
                                 String diagnostic) {
        if (levels == null) {
            levels = new BatteryLevels();
            levels.timestamp = System.currentTimeMillis();
            levels.source = "未取到";
        } else {
            new BatteryStore(this).save(addr, levels);
        }
        if (tvProbeHint != null) {
            tvProbeHint.setText("上次结果 L:" + BatteryLevels.fmt(levels.left)
                    + " R:" + BatteryLevels.fmt(levels.right)
                    + " Case:" + BatteryLevels.fmt(levels.caseBox)
                    + "（" + levels.source + "）");
        }
        String summary = "设备: " + (name == null ? addr : name) + " (" + addr + ")\n"
                + "结果: L=" + BatteryLevels.fmt(levels.left)
                + "  R=" + BatteryLevels.fmt(levels.right)
                + "  Case=" + BatteryLevels.fmt(levels.caseBox)
                + "  来源=" + levels.source;
        final String payload = summary + "\n\n" + (diagnostic == null ? "(无日志)" : diagnostic);

        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("探测完成")
                .setMessage(summary + "\n\n点「导出日志」把完整 GATT 服务树和原始字节发出来，"
                        + "我据此为你这台耳机写死精确的电量解析规则。")
                .setPositiveButton("导出日志", (d, w) -> {
                    copy(payload, "日志已复制，粘贴给我");
                    Intent share = new Intent(Intent.ACTION_SEND);
                    share.setType("text/plain");
                    share.putExtra(Intent.EXTRA_TEXT, payload);
                    try {
                        startActivity(Intent.createChooser(share, "发送电量探测日志"));
                    } catch (Exception ignored) {
                    }
                })
                .setNeutralButton("存到文件", (d, w) -> saveProbeLog(payload))
                .setNegativeButton("关闭", null)
                .show();
    }

    /** 日志存到下载目录，方便从文件管理器发出来 */
    private void saveProbeLog(String content) {
        try {
            java.io.File dir = android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOWNLOADS);
            if (dir != null && !dir.exists()) dir.mkdirs();
            java.io.File f = new java.io.File(dir, "电量探测日志.txt");
            java.io.FileOutputStream fos = new java.io.FileOutputStream(f, false);
            fos.write(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            fos.close();
            Toast.makeText(this, "已保存到 下载/电量探测日志.txt", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "保存失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /**
     * 在最近任务（多任务列表）里隐藏/显示本 App。
     * manifest 的 excludeFromRecents 是默认值，这里用 AppTask.setExcludeFromRecents
     * 做运行时切换，开关一改立刻生效。
     */
    private void applyRecentsHidden(boolean hide) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return;
        try {
            android.app.ActivityManager am =
                    (android.app.ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) return;
            for (android.app.ActivityManager.AppTask task : am.getAppTasks()) {
                task.setExcludeFromRecents(hide);
            }
        } catch (Throwable ignored) {
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        // 兜底：任何情况下离开界面都把当前值写进去
        try {
            saveAll();
        } catch (Exception ignored) {
        }
    }

    private void showAbout() {
        String msg = "自定义耳机连接弹窗 · 免 Root\n\n"
                + "· 不联网、不上传任何数据，配置全部存在手机本地\n"
                + "· 只在耳机连接事件发生时唤醒，不轮询、不定位\n"
                + "· 弹窗尺寸参照华为官方公开的耳机弹窗设计规范（1440×1792，圆角约宽度 8.9%）\n\n"
                + "已知边界：小米「开盖即弹」走私有快连协议，第三方拿不到，"
                + "本方案在蓝牙真正连上那一刻弹出，比原生晚约 1 秒。";
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("关于")
                .setMessage(msg)
                .setPositiveButton("知道了", null)
                .show();
    }

    private void copy(String text, String toast) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("adb", text));
            Toast.makeText(this, toast, Toast.LENGTH_SHORT).show();
        }
    }

    private void showMiPopupGuide() {
        String msg =
                "关掉小米原生快连弹窗，三种方式任选一种：\n\n"
                + "【方式一｜最干净，纯系统设置】\n"
                + "设置 → 蓝牙 → 高级设置 → 关闭「小米快连」。\n"
                + "关掉后原生弹窗不再出现，蓝牙连接、电竞低延迟都不受影响，电量照样能在通知栏看到。\n\n"
                + "【方式二｜只关这一副耳机】\n"
                + "设置 → 蓝牙 → 点 Redmi Buds 5 Pro 电竞版 右边的「>」→ 关闭「连接弹窗」。\n\n"
                + "【方式三｜命令，温和屏蔽】\n"
                + "appops set com.xiaomi.bluetooth SYSTEM_ALERT_WINDOW ignore\n"
                + "（只禁它的悬浮窗，不动蓝牙。想恢复把 ignore 换成 allow）";
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("屏蔽小米原生弹窗")
                .setMessage(msg)
                .setPositiveButton("复制命令", (d, w) -> copy(
                        "appops set com.xiaomi.bluetooth SYSTEM_ALERT_WINDOW ignore\n"
                        + "# 恢复：把 ignore 换成 allow",
                        "已复制屏蔽命令"))
                .setNegativeButton("知道了", null)
                .show();
    }

    private void copyMiuiPermCommands() {
        copy("appops set com.yuanbao.earbuds SYSTEM_ALERT_WINDOW allow\n"
                + "appops set com.yuanbao.earbuds 10021 allow\n"
                + "appops set com.yuanbao.earbuds 10023 allow\n"
                + "appops set com.yuanbao.earbuds 10024 allow\n"
                + "pm grant com.yuanbao.earbuds android.permission.BLUETOOTH_CONNECT\n"
                + "pm grant com.yuanbao.earbuds android.permission.POST_NOTIFICATIONS\n"
                + "dumpsys deviceidle whitelist +com.yuanbao.earbuds",
                "已复制，在 Stellar 命令页粘贴执行");
    }

    private void copyAdbCommands() {
        copy("appops set com.yuanbao.earbuds SYSTEM_ALERT_WINDOW allow\n"
                + "pm grant com.yuanbao.earbuds android.permission.BLUETOOTH_CONNECT\n"
                + "pm grant com.yuanbao.earbuds android.permission.BLUETOOTH_SCAN\n"
                + "pm grant com.yuanbao.earbuds android.permission.POST_NOTIFICATIONS\n"
                + "dumpsys deviceidle whitelist +com.yuanbao.earbuds\n"
                + "appops set com.xiaomi.bluetooth SYSTEM_ALERT_WINDOW ignore",
                "已复制，在 Stellar 命令页粘贴执行");
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
