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
    private RoundedImageView pvImage;
    private TextView pvInfo, pvTip, pvDeviceName, pvBattery, pvCase;
    private View pvGradient;

    // 外观
    private RoundedImageView pvFrameImage;
    private TextView tvFrameHint;
    private TextView tvSoundName;
    private TextView tvSoundVol;
    private SwitchMaterial swSound;
    private EditText etTitle, etSub;
    private View swatchBg, swatchText, swatchAccent;
    private TextView tvBg, tvTextColor, tvAccent;
    private TextView tvWidth, tvRadius, tvImgH, tvDuration, tvPos, tvAnim;
    private SwitchMaterial swAutoColor;

    // 设置
    private SwitchMaterial swMaster, swWired, swAutoStart, swBattery, swCase;
    private SwitchMaterial swLock, swNoFocus, swPowerSave, swHideNoti;
    private TextView tvEngine, tvDim, tvBlur;
    private LinearLayout deviceList;
    private TextView tvProbeHint;
    /** 最近一次电量探测的结果，供预览显示真实值 */
    private BatteryLevels lastProbeResult;
    private SwitchMaterial swHideRecents;

    private View tabHome, tabLook, tabSet;

    private final Set<String> denySet = new LinkedHashSet<>();
    private boolean bindingUi = false;
    private boolean saving = false;
    private String lastProbeLog = "";

    private final ActivityResultLauncher<String[]> pickImage =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri == null) return;
                try {
                    getContentResolver().takePersistableUriPermission(uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (Exception ignored) {
                }
                prefs.setImageUri(uri.toString());
                prefs.addImageHistory(uri.toString());
                loadThumb();
                renderImageHistory();
                updatePreview();
                extractTheme(uri);
            });

    private final ActivityResultLauncher<String[]> pickSound =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri == null) return;
                try {
                    getContentResolver().takePersistableUriPermission(uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (Exception ignored) {
                }
                prefs.setSoundUri(uri.toString());
                updateSoundLabel();
                Toast.makeText(this, "音效已设置", Toast.LENGTH_SHORT).show();
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // 不使用 DynamicColors：它会用壁纸色覆盖主题，浅色壁纸下
        // 标题、开关、按钮会跟背景融为一体，整页看起来是空白的。
        // 这里固定用高对比度配色，保证任何壁纸下都看得清。
        setTheme(R.style.AppTheme);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        prefs = new Prefs(this);
        PopupRenderer.PrefsHolder.init(this);

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
        pvDeviceName = findViewById(R.id.pvDeviceName);
        pvBattery = findViewById(R.id.pvBattery);
        pvCase = findViewById(R.id.pvCase);
        pvGradient = findViewById(R.id.pvGradient);
        pvTip = findViewById(R.id.pvTip);

        pvFrameImage = findViewById(R.id.pvFrameImage);
        tvFrameHint = findViewById(R.id.tvFrameHint);
        swSound = findViewById(R.id.swSound);
        tvSoundName = findViewById(R.id.tvSoundName);
        tvSoundVol = findViewById(R.id.tvSoundVol);
        setupImageGesture();
        setupSoundUi();
        com.google.android.material.button.MaterialButton resetTf =
                findViewById(R.id.btnResetTransform);
        if (resetTf != null) {
            resetTf.setOnClickListener(v -> {
                prefs.resetImageTransform();
                loadThumb();
                Toast.makeText(this, "已重置缩放", Toast.LENGTH_SHORT).show();
            });
        }
        etTitle = findViewById(R.id.etTitle);
        etSub = findViewById(R.id.etSub);
        swatchBg = findViewById(R.id.swatchBg);
        swatchText = findViewById(R.id.swatchText);
        swatchAccent = findViewById(R.id.swatchAccent);
        tvBg = findViewById(R.id.tvBg);
        tvTextColor = findViewById(R.id.tvTextColor);
        tvAccent = findViewById(R.id.tvAccent);
        setupColorPickers();
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
        TextView tvDiagMma = findViewById(R.id.tvDiagMma);
        TextView tvDiagIsland = findViewById(R.id.tvDiagIsland);
        com.google.android.material.switchmaterial.SwitchMaterial swForceIsland =
                findViewById(R.id.swForceIsland);
        if (swForceIsland != null) {
            swForceIsland.setChecked(prefs.forceIsland());
            swForceIsland.setOnCheckedChangeListener((btn, on) -> prefs.setForceIsland(on));
        }
        TextView tvDiagService = findViewById(R.id.tvDiagService);
        TextView tvDiagKeep = findViewById(R.id.tvDiagKeep);
        TextView tvDiagBatSrc = findViewById(R.id.tvDiagBatSrc);
        TextView tvDiagMeta = findViewById(R.id.tvDiagMeta);
        findViewById(R.id.btnDiagRefresh).setOnClickListener(v -> {
            KeepAliveController.arm(this);   // 立即排一次保活 Alarm
            refreshDiag(tvDiagMma, tvDiagIsland, tvDiagService, tvDiagKeep, tvDiagBatSrc, tvDiagMeta);
        });
        refreshDiag(tvDiagMma, tvDiagIsland, tvDiagService, tvDiagKeep, tvDiagBatSrc, tvDiagMeta);
        swHideRecents = findViewById(R.id.swHideRecents);
    }

    // ---------------- 载入与保存 ----------------

    private void loadPrefsToUi() {
        bindingUi = true;
        denySet.clear();
        prefs.migrateLegacyAllowedIfNeeded();
        denySet.addAll(prefs.deniedDevices());

        etTitle.setText(prefs.titleText());
        etSub.setText(prefs.subText());
        syncColorSwatches();

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
        renderImageHistory();
        updatePreview();
        showDeviceInfo();
        bindingUi = false;
    }

    private void saveAll() {
        String t = etTitle.getText().toString().trim();
        prefs.setTitleText(t.isEmpty() ? "耳机已连接" : t);
        prefs.setSubText(etSub.getText().toString());
        // 颜色现在由色彩盘写入 Prefs，这里不再从输入框读取
        prefs.setDeniedDevices(denySet);
        syncValueLabels();
    }

    /** 把所有数值项的当前值刷新到行尾 */
    private void syncValueLabels() {
        tvWidth.setText(prefs.widthDp() + " dp");
        tvRadius.setText(prefs.radiusDp() + " dp");
        tvImgH.setText(Math.round(prefs.imageRatio() * 100) + "%");
        tvDuration.setText(prefs.durationMs() + " ms");
        tvPos.setText(prefs.verticalPos() + "%");
        tvAnim.setText(new String[]{"缩放淡入", "底部上滑", "顶部下滑"}[prefs.animStyle()]);
        String[] engineLabels = {"系统级", "悬浮窗", "智能", "HyperOS 超级岛", "HyperOS 智能"};
        tvEngine.setText(engineLabels[Math.max(0, Math.min(engineLabels.length - 1, prefs.engine()))]);
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
            // 预览改用真实探测结果，不再写死 78 / 65。
            // 之前无论什么设备都是这两个数，用户以为电量已经读到了，实际是假的。
            BatteryLevels pv = new BatteryStore(this).load(prefs.lastAddress());
            int l = show ? pv.left : -1;
            int r = show ? pv.right : -1;
            int c = (show && prefs.showCaseBattery()) ? pv.caseBox : -1;
            if (!show || !BatteryLevels.valid(l)) l = show ? pv.overall : -1;
            if (!show || !BatteryLevels.valid(r)) r = show ? pv.overall : -1;
            s.putExtra(PopupService.EXTRA_LEFT, l);
            s.putExtra(PopupService.EXTRA_BATTERY, r);
            s.putExtra(PopupService.EXTRA_CASE, c);
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
        findViewById(R.id.rowPos).setOnClickListener(v -> showPositionSlider());
        findViewById(R.id.rowAnim).setOnClickListener(v -> showChoice("入场动画",
                new String[]{"缩放淡入", "底部上滑", "顶部下滑"}, prefs.animStyle(),
                prefs::setAnimStyle));
        findViewById(R.id.rowEngine).setOnClickListener(v -> showChoice("弹出引擎",
                new String[]{"系统级（透明 Activity，锁屏也能弹）",
                        "悬浮窗（兼容性最好）",
                        "智能：先系统级，失败自动降级悬浮窗",
                        "HyperOS 原生超级岛（由系统 SystemUI 渲染）",
                        "HyperOS 智能（超级岛优先，失败自动回退）"},
                Math.max(0, Math.min(4, prefs.engine())), prefs::setEngine));

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
        findViewById(R.id.rowClearBattery).setOnClickListener(v ->
                new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                        .setTitle("重置电量缓存")
                        .setMessage("会清掉已保存的电量数据。下次连接耳机会重新读取。\n\n"
                                + "如果弹窗一直显示 0%，先重置一次再试。")
                        .setNegativeButton("取消", null)
                        .setPositiveButton("重置", (d, w) -> {
                            new BatteryStore(this).clearAll();
                            if (tvProbeHint != null) {
                                tvProbeHint.setText("已重置，下次连接会重新读取");
                            }
                            Toast.makeText(this, "电量缓存已清空", Toast.LENGTH_SHORT).show();
                        })
                        .show());
        findViewById(R.id.rowProbe).setOnClickListener(v -> {
            if (lastProbeLog != null && !lastProbeLog.isEmpty()) {
                new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                        .setTitle("上次探测日志")
                        .setMessage("已保存到「下载/电量探测日志.txt」。\n"
                                + "要重新探测，还是直接复制上次的？")
                        .setPositiveButton("重新探测", (d, w) -> runBatteryProbe())
                        .setNegativeButton("复制上次", (d, w) -> copy(lastProbeLog, "日志已复制"))
                        .setNeutralButton("关闭", null)
                        .show();
            } else {
                runBatteryProbe();
            }
        });
        // 充电盒电量推断开关：默认关闭（该字节未确认是电量，贸然显示会乱跳）
        View rowCase = findViewById(R.id.rowPrivateCase);
        if (rowCase != null) {
            final com.google.android.material.materialswitch.MaterialSwitch swCaseSrc =
                    rowCase.findViewById(R.id.swPrivateCase);
            final android.widget.TextView tvCaseDesc =
                    rowCase.findViewById(R.id.tvPrivateCaseDesc);
            if (swCaseSrc != null) {
                swCaseSrc.setChecked(prefs.privateCaseEnabled());
                swCaseSrc.setOnCheckedChangeListener((btn, on) -> {
                    prefs.setPrivateCaseEnabled(on);
                    if (tvCaseDesc != null) {
                        tvCaseDesc.setText(on
                                ? "开启：用私有特征读数（已验证恒为32，非电量，仅调试用）"
                                : "关闭：充电盒显示 --%（该特征值已被证实不是电量）");
                    }
                });
                if (tvCaseDesc != null) {
                    tvCaseDesc.setText(prefs.privateCaseEnabled()
                            ? "开启：用私有特征读数当充电盒电量（未确认，可能不准）"
                            : "关闭：充电盒显示 --%");
                }
            }
        }
        findViewById(R.id.rowInfo).setOnClickListener(v -> showAbout());

        watch(etTitle, etSub);
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

    /**
     * 垂直位置滑块，带【屏幕示意图实时预览】。
     *
     * 用户要求：拖动时能准确、实时地看到弹窗会落在屏幕的哪个位置。
     * 所以这里画一个按比例缩小的手机屏幕轮廓，里面放一个卡片块，
     * 拖动时卡片块按相同的百分比上下移动 —— 所见即所得。
     */
    private void showPositionSlider() {
        final int[] value = {prefs.verticalPos()};
        final int original = value[0];

        float d = getResources().getDisplayMetrics().density;
        int stageH = (int) (200 * d);   // 示意图高度
        int stageW = (int) (110 * d);

        // 屏幕外框
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        box.setPadding(16, 8, 16, 0);

        TextView label = new TextView(this);
        label.setText("距顶部 " + value[0] + "%");
        label.setTextSize(16);
        label.setPadding(24, 12, 24, 4);
        box.addView(label);

        // 屏幕示意图（用 FrameLayout 模拟：外框 + 内部卡片块）
        FrameLayout stage = new FrameLayout(this);
        stage.setBackgroundColor(0xFFE8E8EE);
        LinearLayout.LayoutParams slp =
                new LinearLayout.LayoutParams(stageW, stageH);
        slp.setMargins(0, (int) (8 * d), 0, (int) (8 * d));
        box.addView(stage, slp);

        // 卡片块（按弹窗比例画的示意方块）
        final View chip = new View(this);
        int chipH = Math.max((int) (30 * d), (int) (stageH * 0.28f));
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(
                (int) (stageW * 0.86f), chipH);
        clp.gravity = android.view.Gravity.TOP | android.view.Gravity.CENTER_HORIZONTAL;
        GradientDrawable cg = new GradientDrawable();
        cg.setShape(GradientDrawable.RECTANGLE);
        cg.setCornerRadius(8 * d);
        cg.setColor(0xFFFF9800);
        chip.setBackground(cg);
        stage.addView(chip, clp);

        SeekBar sb = new SeekBar(this);
        sb.setMax(100);
        sb.setProgress(value[0]);
        sb.setPadding(16, 8, 16, 8);
        box.addView(sb);

        Runnable[] apply = new Runnable[1];
        apply[0] = () -> {
            label.setText("距顶部 " + value[0] + "%");
            int pad = (int) (6 * d);
            int usable = stageH - chipH - pad * 2;
            if (usable < 0) usable = 0;
            clp.topMargin = pad + Math.round(usable * value[0] / 100f);
            chip.setLayoutParams(clp);
        };
        apply[0].run();

        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int p, boolean b) {
                value[0] = p;
                prefs.setVerticalPos(p);   // 实时写入
                apply[0].run();
                syncValueLabels();
            }

            public void onStartTrackingTouch(SeekBar s) {
            }

            public void onStopTrackingTouch(SeekBar s) {
            }
        });

        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("弹窗垂直位置")
                .setView(box)
                .setNegativeButton("取消", (dlg, w) -> {
                    prefs.setVerticalPos(original);
                    syncValueLabels();
                })
                .setPositiveButton("确定", (dlg, w) -> {
                    prefs.setVerticalPos(value[0]);
                    syncValueLabels();
                })
                .show();
    }

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

    // ---------------- 色彩盘 ----------------

    /**
     * 三个颜色项改为「点开色彩盘」。
     * 用户明确要求：不要填颜色代码，要能直观看到颜色。
     * 这里弹一个含 HSV 色彩盘的对话框，拖动即时预览并存进 Prefs。
     */
    private void setupColorPickers() {
        bindColorRow(R.id.rowBg, swatchBg, prefs::bgColor, prefs::setBgColor);
        bindColorRow(R.id.rowTextColor, swatchText, prefs::textColor, prefs::setTextColor);
        bindColorRow(R.id.rowAccent, swatchAccent, prefs::accentColor, prefs::setAccentColor);
        setupTextColorTabs();
    }

    // ---------- 文字颜色分档（标题 / 电量 / 状态） ----------
    // 当前正在编辑哪一档：0=标题 1=电量 2=状态
    private int textColorTarget = 0;

    /** 预设色点：照参考图给一排常用色，点击即换 */
    private static final int[] TEXT_PRESETS = {
            0xFFFFFFFF, 0xFF000000, 0xFF4ADE80, 0xFF60A5FA,
            0xFFF472B6, 0xFFFACC15, 0xFFA78BFA, 0xFFFB923C,
            0xFF94A3B8, 0xFF22D3EE,
    };

    private void setupTextColorTabs() {
        com.google.android.material.button.MaterialButton b0 = findViewById(R.id.tabColorTitle);
        com.google.android.material.button.MaterialButton b1 = findViewById(R.id.tabColorBattery);
        com.google.android.material.button.MaterialButton b2 = findViewById(R.id.tabColorStatus);
        if (b0 == null || b1 == null || b2 == null) return;

        b0.setOnClickListener(v -> selectTextColorTarget(0));
        b1.setOnClickListener(v -> selectTextColorTarget(1));
        b2.setOnClickListener(v -> selectTextColorTarget(2));

        buildColorDots();
        selectTextColorTarget(0);
    }

    private void selectTextColorTarget(int which) {
        textColorTarget = which;
        com.google.android.material.button.MaterialButton[] bs = {
                findViewById(R.id.tabColorTitle),
                findViewById(R.id.tabColorBattery),
                findViewById(R.id.tabColorStatus),
        };
        for (int i = 0; i < bs.length; i++) {
            if (bs[i] == null) continue;
            // 选中的那档用实心按钮，其余弱化
            bs[i].setAlpha(i == which ? 1f : 0.5f);
        }
        refreshColorDots();
    }

    private void buildColorDots() {
        LinearLayout box = findViewById(R.id.colorDots);
        if (box == null) return;
        box.removeAllViews();
        float d = getResources().getDisplayMetrics().density;
        int size = (int) (32 * d);
        int gap = (int) (10 * d);

        for (int c : TEXT_PRESETS) {
            View dot = new View(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
            lp.setMargins(0, 0, gap, 0);
            dot.setLayoutParams(lp);
            android.graphics.drawable.GradientDrawable gd =
                    new android.graphics.drawable.GradientDrawable();
            gd.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            gd.setColor(c);
            gd.setStroke((int) (1.5 * d), 0x33000000);
            dot.setBackground(gd);
            dot.setTag(c);
            dot.setOnClickListener(v -> applyTextColor((Integer) v.getTag()));
            // 长按打开完整色彩盘
            dot.setOnLongClickListener(v -> {
                applyTextColor((Integer) v.getTag());
                showColorPicker(currentTextColor(),
                        hex -> applyTextColor(parseColor(hex, Color.WHITE)));
                return true;
            });
            box.addView(dot);
        }
    }

    private void refreshColorDots() {
        LinearLayout box = findViewById(R.id.colorDots);
        if (box == null) return;
        int cur = currentTextColorInt();
        float d = getResources().getDisplayMetrics().density;
        for (int i = 0; i < box.getChildCount(); i++) {
            View dot = box.getChildAt(i);
            Object tag = dot.getTag();
            if (!(tag instanceof Integer)) continue;
            boolean on = ((Integer) tag) == cur;
            android.graphics.drawable.GradientDrawable gd =
                    new android.graphics.drawable.GradientDrawable();
            gd.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            gd.setColor((Integer) tag);
            gd.setStroke((int) (on ? 3 * d : 1.5 * d), on ? 0xFFFFFFFF : 0x33000000);
            dot.setBackground(gd);
        }
    }

    /** 当前这一档存的 hex（空串表示跟随通用文字色） */
    private String currentTextColor() {
        switch (textColorTarget) {
            case 1: return prefs.batteryColor();
            case 2: return prefs.statusColor();
            default: return prefs.titleColor();
        }
    }

    private int currentTextColorInt() {
        String hex = currentTextColor();
        if (hex == null || hex.isEmpty()) {
            return parseColor(prefs.textColor(), Color.WHITE);
        }
        try {
            return Color.parseColor(hex);
        } catch (Exception e) {
            return Color.WHITE;
        }
    }

    private void applyTextColor(int c) {
        String hex = String.format("#%08X", c);
        switch (textColorTarget) {
            case 1: prefs.setBatteryColor(hex); break;
            case 2: prefs.setStatusColor(hex); break;
            default: prefs.setTitleColor(hex); break;
        }
        refreshColorDots();
        updatePreview();
    }

    // ---------- 图片 / GIF 历史 ----------
    private void renderImageHistory() {
        LinearLayout box = findViewById(R.id.historyList);
        View scroll = findViewById(R.id.historyScroll);
        TextView title = findViewById(R.id.tvHistoryTitle);
        if (box == null) return;

        java.util.List<String> list = prefs.imageHistory();
        if (list.isEmpty()) {
            if (scroll != null) scroll.setVisibility(View.GONE);
            if (title != null) title.setVisibility(View.GONE);
            return;
        }
        if (scroll != null) scroll.setVisibility(View.VISIBLE);
        if (title != null) title.setVisibility(View.VISIBLE);

        box.removeAllViews();
        float d = getResources().getDisplayMetrics().density;
        int size = (int) (58 * d);
        int gap = (int) (8 * d);

        for (String u : list) {
            android.widget.ImageView iv = new android.widget.ImageView(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
            lp.setMargins(0, 0, gap, 0);
            iv.setLayoutParams(lp);
            iv.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
            android.graphics.drawable.GradientDrawable bg =
                    new android.graphics.drawable.GradientDrawable();
            bg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
            bg.setCornerRadius(10 * d);
            bg.setColor(0x22FFFFFF);
            iv.setBackground(bg);
            iv.setClipToOutline(true);
            try {
                Glide.with(this).load(android.net.Uri.parse(u)).into(iv);
            } catch (Exception ignored) {
            }
            // 点击：切换
            iv.setOnClickListener(v -> {
                prefs.setImageUri(u);
                prefs.addImageHistory(u);
                prefs.resetImageTransform();
                loadThumb();
                renderImageHistory();
                updatePreview();
                Toast.makeText(this, "已切换", Toast.LENGTH_SHORT).show();
            });
            // 长按：从历史里删掉
            iv.setOnLongClickListener(v -> {
                prefs.removeImageHistory(u);
                renderImageHistory();
                Toast.makeText(this, "已从历史移除", Toast.LENGTH_SHORT).show();
                return true;
            });
            box.addView(iv);
        }
    }

    private void bindColorRow(int rowId, View swatch,
                              java.util.function.Supplier<String> getter,
                              java.util.function.Consumer<String> setter) {
        View row = findViewById(rowId);
        if (row == null) return;
        row.setOnClickListener(v -> showColorPicker(getter.get(), setter));
    }

    private void showColorPicker(String currentHex,
                                 java.util.function.Consumer<String> setter) {
        int initial;
        try {
            initial = Color.parseColor(currentHex);
        } catch (Exception e) {
            initial = 0xFFFFFFFF;
        }

        final int[] picked = {initial};

        ColorPickerView picker = new ColorPickerView(this);
        picker.setColor(initial);

        int pad = (int) (18 * getResources().getDisplayMetrics().density);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad, pad, pad / 2);

        // 色彩盘本身（高度固定，宽度铺满）
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int) (230 * dp(1)));
        box.addView(picker, lp);

        // 实时数值 + 大色块预览
        LinearLayout previewRow = new LinearLayout(this);
        previewRow.setOrientation(LinearLayout.HORIZONTAL);
        previewRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        previewRow.setPadding(0, pad / 2, 0, pad / 2);

        final View bigSwatch = new View(this);
        int sz = (int) (44 * dp(1));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(sz, sz);
        slp.setMarginEnd(pad / 2);
        previewRow.addView(bigSwatch, slp);

        final TextView tvHex = new TextView(this);
        tvHex.setTextSize(13f);
        tvHex.setTextColor(0xFF666666);
        previewRow.addView(tvHex, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        box.addView(previewRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        Runnable[] refresh = new Runnable[1];
        refresh[0] = () -> {
            int c = picked[0];
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(c);
            g.setStroke(Math.max(1, (int) dp(1)), 0x33000000);
            bigSwatch.setBackground(g);
            tvHex.setText(String.format("#%08X", c));
        };
        refresh[0].run();

        picker.setOnColorChangedListener(c -> {
            picked[0] = c;
            refresh[0].run();
            // 实时应用，预览卡片立刻变色
            setter.accept(String.format("#%08X", c));
            syncColorSwatches();
            updatePreview();
        });

        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("选择颜色")
                .setView(box)
                .setNegativeButton("取消", (d, w) -> {
                    // 拖动时已实时改过，取消要还原
                    setter.accept(currentHex);
                    syncColorSwatches();
                    updatePreview();
                })
                .setPositiveButton("确定", (d, w) -> {
                    setter.accept(String.format("#%08X", picked[0]));
                    syncColorSwatches();
                    updatePreview();
                })
                .show();
    }

    /** 把三个色块和文字同步成当前配置值 */
    private void syncColorSwatches() {
        applySwatch(swatchBg, prefs.bgColor(), tvBg);
        applySwatch(swatchText, prefs.textColor(), tvTextColor);
        applySwatch(swatchAccent, prefs.accentColor(), tvAccent);
    }

    private void applySwatch(View v, String hex, TextView label) {
        if (v == null) return;
        int c;
        try {
            c = Color.parseColor(hex);
        } catch (Exception e) {
            c = 0xFF888888;
        }
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(c);
        g.setStroke(Math.max(1, (int) dp(1)), 0x33000000);
        v.setBackground(g);
        if (label != null) label.setText(hex.toUpperCase());
    }

    // ---------------- 预览 ----------------

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
        float pvw = prefs.widthDp() * d;
        // 预览缩放系数：卡片变宽后要留足边距，取 0.82
        int w = (int) (pvw * 0.82f);
        float ratio = Math.max(0.40f, Math.min(0.94f, prefs.imageRatio()));
        float cardRatio = 0.45f + ratio * 0.33f;
        int h = (int) (w * cardRatio);
        ViewGroup.LayoutParams lp = pvCard.getLayoutParams();
        lp.width = w;
        lp.height = h;
        pvCard.setLayoutParams(lp);

        GradientDrawable gd = new GradientDrawable();
        gd.setShape(GradientDrawable.RECTANGLE);
        gd.setCornerRadius(prefs.radiusDp() * d * 0.82f);
        gd.setColor(cardColor);
        gd.setStroke(Math.max(1, (int) d), 0x33FFFFFF);
        pvCard.setBackground(gd);
        pvCard.setElevation(12 * d);
        // 与真实弹窗一致：框架级裁剪，保证铺满的图片不盖掉圆角
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            final float pr = prefs.radiusDp() * d * 0.82f;
            pvCard.setOutlineProvider(new android.view.ViewOutlineProvider() {
                @Override
                public void getOutline(android.view.View v, android.graphics.Outline o) {
                    o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), pr);
                }
            });
            pvCard.setClipToOutline(true);
        }
        // 与真实弹窗一致：不用系统 clipToOutline（有锯齿），靠背景自身圆角

        // GIF 区吃掉全部剩余空间，文字区保持紧凑（与真实弹窗一致）

        // 预览图片圆角：与真实弹窗一致，用抗锯齿离屏合成
        if (pvImage != null) {
            float pr = Math.max(0f, prefs.radiusDp() - 2) * d * 0.78f;
            pvImage.setRadius(pr);
        }

        // 信息窄条：与真实弹窗同一套拼接逻辑。
        //
        // 之前这里写死 78 / 65 作为预览假数据 —— 结果预览上一直显示
        // 「L 78% R 78% Case 65%」，看起来像电量已经打通了，实际是假的。
        // 现在改用真实探测缓存：有值就显示真实值，没有就 --%。
        // 数据源优先级：本次探测结果 > 上次探测的缓存
        BatteryLevels cached = null;
        if (swBattery.isChecked()) {
            if (lastProbeResult != null) {
                cached = lastProbeResult;
            } else {
                String addr = prefs.lastAddress();
                if (addr != null && !addr.isEmpty()) {
                    cached = new BatteryStore(this).load(addr);
                }
            }
        }
        int bat = (cached != null && BatteryLevels.valid(cached.left)) ? cached.left : -1;
        int cas = (cached != null && BatteryLevels.valid(cached.caseBox)
                && swCase.isChecked()) ? cached.caseBox : -1;
        String devName = etTitle.getText().toString().trim();
        if (devName.isEmpty()) devName = "我的耳机";

        // 三档分别取色，没设过退回通用色（必须先用后定义会编译失败，
        // 所以这段放在使用之前）
        int pvTitleC = textColor, pvStatC = textColor, pvBattC = textColor;
        try {
            if (!prefs.titleColor().isEmpty()) pvTitleC = Color.parseColor(prefs.titleColor());
            if (!prefs.statusColor().isEmpty()) pvStatC = Color.parseColor(prefs.statusColor());
            if (!prefs.batteryColor().isEmpty()) pvBattC = Color.parseColor(prefs.batteryColor());
        } catch (Exception ignored) {
        }

        if (pvBattery != null) {
            pvBattery.setText(bat >= 0 ? bat + "%" : "--%");
            pvBattery.setTextColor(pvBattC);
        }
        if (pvCase != null) {
            pvCase.setText(cas >= 0 ? cas + "%" : "--%");
            pvCase.setVisibility(cas >= 0 ? View.VISIBLE : View.GONE);
        }
        if (pvInfo != null) pvInfo.setText(devName);
        pvTip.setTextColor(adjustAlpha(pvStatC, 0.55f));
        if (pvDeviceName != null) {
            pvDeviceName.setText(devName);
            pvDeviceName.setTextColor(pvTitleC);
        }
        // 渐变层：透明 → 卡片底色，与真实弹窗一致
        if (pvGradient != null) {
            GradientDrawable fade = new GradientDrawable(
                    GradientDrawable.Orientation.BOTTOM_TOP,
                    new int[]{cardColor, Color.TRANSPARENT});
            pvGradient.setBackground(fade);
        }

        String uri = prefs.imageUri();
        pvImage.setScaleType(ImageView.ScaleType.MATRIX);
        if (!uri.isEmpty()) {
            try {
                Glide.with(this)
                        .load(Uri.parse(uri))
                        .dontTransform()
                        .into(new com.bumptech.glide.request.target.CustomViewTarget<ImageView,
                                android.graphics.drawable.Drawable>(pvImage) {
                            @Override
                            public void onResourceReady(
                                    android.graphics.drawable.Drawable resource,
                                    com.bumptech.glide.request.transition.Transition<? super
                                            android.graphics.drawable.Drawable> t) {
                                pvImage.setImageDrawable(resource);
                                PopupRenderer.applyImageMatrix(pvImage);
                                if (resource instanceof android.graphics.drawable.Animatable) {
                                    ((android.graphics.drawable.Animatable) resource).start();
                                }
                            }

                            @Override
                            public void onLoadFailed(android.graphics.drawable.Drawable d) {
                                pvImage.setImageResource(R.drawable.ic_headphone);
                                PopupRenderer.applyImageMatrix(pvImage);
                            }

                            @Override
                            protected void onResourceCleared(
                                    android.graphics.drawable.Drawable d) {
                            }
                        });
            } catch (Exception e) {
                pvImage.setImageResource(R.drawable.ic_headphone);
                PopupRenderer.applyImageMatrix(pvImage);
            }
        } else {
            pvImage.setImageResource(R.drawable.ic_headphone);
            PopupRenderer.applyImageMatrix(pvImage);
        }
    }

    private int adjustAlpha(int color, float alpha) {
        int a = Math.round(255 * alpha);
        return (a << 24) | (color & 0x00FFFFFF);
    }

    /**
     * 把选中图片加载进「相框」预览。
     * 用 MATRIX 而非 centerCrop：这样才能叠加用户的双指缩放 / 拖动。
     */
    private void loadThumb() {
        if (pvFrameImage == null) return;
        String uri = prefs.imageUri();
        pvFrameImage.setScaleType(ImageView.ScaleType.MATRIX);
        if (!uri.isEmpty()) {
            try {
                Glide.with(this)
                        .load(Uri.parse(uri))
                        .dontTransform()
                        .into(new com.bumptech.glide.request.target.CustomViewTarget<ImageView,
                                android.graphics.drawable.Drawable>(pvFrameImage) {
                            @Override
                            public void onResourceReady(
                                    android.graphics.drawable.Drawable resource,
                                    com.bumptech.glide.request.transition.Transition<? super
                                            android.graphics.drawable.Drawable> t) {
                                pvFrameImage.setImageDrawable(resource);
                                PopupRenderer.applyImageMatrix(pvFrameImage);
                                if (resource instanceof android.graphics.drawable.Animatable) {
                                    ((android.graphics.drawable.Animatable) resource).start();
                                }
                            }

                            @Override
                            public void onLoadFailed(
                                    android.graphics.drawable.Drawable d) {
                                pvFrameImage.setImageResource(R.drawable.ic_headphone);
                                PopupRenderer.applyImageMatrix(pvFrameImage);
                            }

                            @Override
                            protected void onResourceCleared(
                                    android.graphics.drawable.Drawable d) {
                            }
                        });
            } catch (Exception e) {
                pvFrameImage.setImageResource(R.drawable.ic_headphone);
                PopupRenderer.applyImageMatrix(pvFrameImage);
            }
            if (tvFrameHint != null) {
                tvFrameHint.setText("双指缩放 · 单指拖动");
            }
        } else {
            pvFrameImage.setImageResource(R.drawable.ic_headphone);
            PopupRenderer.applyImageMatrix(pvFrameImage);
            if (tvFrameHint != null) {
                tvFrameHint.setText("未选择图片 · 双指缩放 / 单指拖动");
            }
        }
        // 首页预览同步
        updatePreview();
    }

    /** 给相框和首页预览都挂上双指缩放 / 单指拖动 */
    private void setupImageGesture() {
        attachGesture(pvFrameImage);
        attachGesture(pvImage);
    }

    /**
     * 手势：双指缩放 + 单指拖动，实时写入 Prefs，
     * 这样弹窗渲染时读到的是同一套变换，所见即所得。
     */
    private void attachGesture(ImageView v) {
        if (v == null) return;
        final float[] startScale = {1f};
        final float[] startDx = {0f};
        final float[] startDy = {0f};
        final float[] lastX = {0f};
        final float[] lastY = {0f};
        final android.view.ScaleGestureDetector sgd = new android.view.ScaleGestureDetector(
                this, new android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(android.view.ScaleGestureDetector det) {
                float factor = det.getScaleFactor();
                float ns = prefs.imageScale() * factor;
                prefs.setImageScale(ns);
                PopupRenderer.applyImageMatrix(pvFrameImage);
                PopupRenderer.applyImageMatrix(pvImage);
                return true;
            }
        });
        v.setOnTouchListener((view, ev) -> {
            sgd.onTouchEvent(ev);
            float density = getResources().getDisplayMetrics().density;
            switch (ev.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    startScale[0] = prefs.imageScale();
                    startDx[0] = prefs.imageOffsetX();
                    startDy[0] = prefs.imageOffsetY();
                    lastX[0] = ev.getX();
                    lastY[0] = ev.getY();
                    view.getParent().requestDisallowInterceptTouchEvent(true);
                    return true;
                case android.view.MotionEvent.ACTION_MOVE:
                    if (ev.getPointerCount() == 1) {
                        float dx = (ev.getX() - lastX[0]) / density;
                        float dy = (ev.getY() - lastY[0]) / density;
                        prefs.setImageOffsetX(startDx[0] + dx);
                        prefs.setImageOffsetY(startDy[0] + dy);
                        PopupRenderer.applyImageMatrix(pvFrameImage);
                        PopupRenderer.applyImageMatrix(pvImage);
                    }
                    return true;
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL:
                    view.getParent().requestDisallowInterceptTouchEvent(false);
                    return true;
                default:
                    return true;
            }
        });
    }

    // ---------------- 弹窗音效 ----------------

    private void setupSoundUi() {
        if (swSound != null) {
            swSound.setChecked(prefs.soundEnabled());
            swSound.setOnCheckedChangeListener((b, checked) -> prefs.setSoundEnabled(checked));
        }
        updateSoundLabel();

        View rowSound = findViewById(R.id.rowSound);
        if (rowSound != null) {
            rowSound.setOnClickListener(v -> pickSound());
        }
        View rowVol = findViewById(R.id.rowSoundVol);
        if (rowVol != null) {
            rowVol.setOnClickListener(v -> showSlider("音量", "%",
                    0, 100, Math.round(prefs.soundVolume() * 100),
                    val -> {
                        prefs.setSoundVolume(val / 100f);
                        updateSoundLabel();
                    }));
        }
        com.google.android.material.button.MaterialButton test =
                findViewById(R.id.btnTestSound);
        if (test != null) {
            test.setOnClickListener(v -> {
                SoundPlayer.play(this, prefs);
                Toast.makeText(this, "试听中", Toast.LENGTH_SHORT).show();
            });
        }
    }

    private void updateSoundLabel() {
        if (tvSoundVol != null) {
            tvSoundVol.setText(Math.round(prefs.soundVolume() * 100) + "%");
        }
        if (tvSoundName != null) {
            String u = prefs.soundUri();
            tvSoundName.setText(u.isEmpty() ? "未选择（用系统提示音）" : "已选择音频文件");
        }
    }

    private void pickSound() {
        try {
            pickSound.launch(new String[]{"audio/*"});
        } catch (Exception e) {
            Toast.makeText(this, "无法打开文件选择器", Toast.LENGTH_SHORT).show();
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
            // 勾选 = 允许弹窗（默认），取消勾选 = 排除这台设备。
            // 新配对的设备从没被排除过，默认就是允许的 ——
            // 这样换任何耳机都能弹、都能读电量。
            cb.setChecked(!denySet.contains(addr));
            cb.setOnCheckedChangeListener((b, checked) -> {
                if (checked) denySet.remove(addr);
                else denySet.add(addr);
                prefs.setDeniedDevices(denySet);
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
        deviceList.addView(hintText("默认所有耳机都弹窗；取消勾选 = 排除这台设备"));
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
            // 之前这里直接 return，用户点了完全没反应，以为按钮坏了
            Toast.makeText(this,
                    "读取已配对设备被拒绝（缺 BLUETOOTH_CONNECT）。\n"
                            + "请回首页点「一键设置」重新授权。", Toast.LENGTH_LONG).show();
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
            lastProbeResult = levels;
            new BatteryStore(this).save(addr, levels);
        }
        if (tvProbeHint != null) {
            tvProbeHint.setText("上次结果 L:" + BatteryLevels.fmt(levels.left)
                    + " R:" + BatteryLevels.fmt(levels.right)
                    + " Case:" + BatteryLevels.fmt(levels.caseBox)
                    + "（" + levels.source + "）");
        }
        String summary = "设备: " + (name == null ? addr : name) + " (" + addr + ")\n"
                + "系统栈(dumpsys): " + BatterySysQuery.lastEvidence + "\n"
                + "结果: L=" + BatteryLevels.fmt(levels.left)
                + "  R=" + BatteryLevels.fmt(levels.right)
                + "  Case=" + BatteryLevels.fmt(levels.caseBox)
                + "  来源=" + levels.source;
        final String payload = summary + "\n\n" + (diagnostic == null ? "(无日志)" : diagnostic);

        // 同时静默存一份到下载目录：万一分享面板被错过，文件一定在那里
        lastProbeLog = payload;
        saveProbeLogSilently(payload);

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

    /** 静默存文件，不弹 Toast（与手动「存到文件」区分） */
    private void saveProbeLogSilently(String content) {
        try {
            java.io.File dir = android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOWNLOADS);
            if (dir != null && !dir.exists()) dir.mkdirs();
            java.io.File f = new java.io.File(dir, "电量探测日志.txt");
            java.io.FileOutputStream fos = new java.io.FileOutputStream(f, false);
            fos.write(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            fos.close();
        } catch (Exception ignored) {
        }
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
    /**
     * 补丁状态诊断 —— Patch1/Patch2 都是底层改动，界面上看不到，
     * 所以这里把「代码到底跑没跑」直接显示出来。
     */
    private void refreshDiag(TextView mma, TextView island, TextView svc, TextView keep,
                             TextView src, TextView meta) {
        // -1) 小米 MMA 语义电量：这是唯一能给真实 L/R/Case 的只读通道
        if (mma != null) {
            android.bluetooth.BluetoothDevice dev0 = null;
            try {
                android.bluetooth.BluetoothAdapter ad =
                        android.bluetooth.BluetoothAdapter.getDefaultAdapter();
                String a0 = prefs.lastAddress();
                if (ad != null && a0 != null && !a0.isEmpty()) {
                    dev0 = ad.getRemoteDevice(a0);
                }
            } catch (Throwable ignored) {
                dev0 = null;
            }
            boolean xiaomi = XiaomiMmaBatteryReader.likelyXiaomiRedmi(dev0);
            StringBuilder sb = new StringBuilder();
            sb.append("设备匹配=").append(xiaomi ? "是（Redmi/Xiaomi TWS）" : "否");
            String err = HyperOSIslandNotifier.lastError;
            if (err != null && !err.isEmpty()) sb.append("\n").append(err);
            if (xiaomi) {
                sb.append("\n通道：RFCOMM MMA GET_DEVICE_INFO");
                sb.append("\n成功则返回真实 左/右/盒，否则显示占位");
            }
            mma.setText(sb.toString());
        }
        // 0) HyperOS 超级岛：把「为什么没上岛」直接摊开
        if (island != null) {
            boolean hyper = HyperOSIslandNotifier.isHyperOS(this);
            int ver = HyperOSIslandNotifier.protocolVersion(this);
            boolean focus = HyperOSIslandNotifier.hasFocusPermission(this);
            boolean noti = HyperOSIslandNotifier.hasNotificationPermission(this);
            boolean gif;
            try {
                gif = HyperOSIslandNotifier.looksLikeGif(this, prefs.imageUri());
            } catch (Throwable t) {
                gif = false;
            }
            StringBuilder sb = new StringBuilder();
            sb.append("协议版本=").append(ver)
              .append(" · HyperOS=").append(hyper ? "是" : "否")
              .append(" · 焦点资格=").append(focus ? "有" : "无")
              .append("\n通知权限=").append(noti ? "已授予" : "未授予");
            if (gif) sb.append("\n当前是 GIF → 岛不支持动图，已回退悬浮窗");
            if (noti && (focus || prefs.forceIsland()) && !gif && ver >= 2) {
                sb.append("\n条件满足，下次连接应上岛");
            } else if (ver < 2) {
                sb.append("\nROM 未开启焦点协议（<2），无法上岛");
            } else if (!focus) {
                sb.append("\n系统未授予焦点资格 → 可开「强制上岛」试试");
            }
            island.setText(sb.toString());
        }
        // 1) 服务是否在运行
        boolean running = false;
        try {
            android.app.ActivityManager am = (android.app.ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) {
                for (android.app.ActivityManager.RunningServiceInfo i : am.getRunningServices(200)) {
                    if (i.service != null && PopupService.class.getName().equals(i.service.getClassName())) {
                        running = true;
                        break;
                    }
                }
            }
        } catch (Throwable ignored) {}
        svc.setText(running ? "运行中 ✓" : "未运行（点按钮会启动）");

        // 2) 保活 Alarm 是否已排（PendingIntent 存在即代表已注册过）
        boolean armed = false;
        try {
            android.app.AlarmManager am = (android.app.AlarmManager) getSystemService(Context.ALARM_SERVICE);
            android.content.Intent i = new android.content.Intent(this, BootReceiver.class)
                    .setAction(BootReceiver.ACTION_RECOVER_SERVICE).setPackage(getPackageName());
            android.app.PendingIntent pi = android.app.PendingIntent.getBroadcast(this, 9147, i,
                    android.app.PendingIntent.FLAG_NO_CREATE |
                            (android.os.Build.VERSION.SDK_INT >= 23 ? android.app.PendingIntent.FLAG_IMMUTABLE : 0));
            armed = pi != null;
        } catch (Throwable ignored) {}
        keep.setText(armed ? "已排定（15 分钟低频自恢复）✓" : "未排定 → 点上方按钮");

        // 3) 电量来源 + 4) metadata 实读
        String addr = prefs.lastAddress();
        BatteryLevels b = addr == null ? null : new BatteryStore(this).load(addr);
        if (b == null || (b.source == null || b.source.isEmpty())) {
            src.setText("暂无（连接一次耳机后再看）");
        } else {
            src.setText(b.source);
        }
        if (addr == null) {
            meta.setText("无设备地址");
        } else {
            String r = BatteryAuthorityProbeText(addr);
            meta.setText(r);
        }
    }

    /** 直接反射读一次系统 TWS metadata，把结果当字符串显示 */
    private String BatteryAuthorityProbeText(String addr) {
        try {
            android.bluetooth.BluetoothAdapter ba = android.bluetooth.BluetoothAdapter.getDefaultAdapter();
            if (ba == null || !ba.isEnabled()) return "蓝牙未开启";
            android.bluetooth.BluetoothDevice d = ba.getRemoteDevice(addr);
            BatteryLevels lv = BatteryAuthority.read(d);
            StringBuilder sb = new StringBuilder();
            sb.append("左=").append(BatteryLevels.valid(lv.left) ? lv.left + "%" : "--")
              .append(" 右=").append(BatteryLevels.valid(lv.right) ? lv.right + "%" : "--")
              .append(" 盒=").append(BatteryLevels.valid(lv.caseBox) ? lv.caseBox + "%" : "--");
            if (!lv.anyKnown()) sb.append("（该 ROM 未暴露给第三方）");
            return sb.toString();
        } catch (Throwable e) {
            return "读取异常: " + e.getClass().getSimpleName();
        }
    }


}
