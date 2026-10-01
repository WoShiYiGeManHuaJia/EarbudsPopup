package com.woshiyigemanhuajia.btpopup.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.woshiyigemanhuajia.btpopup.R
import com.woshiyigemanhuajia.btpopup.adb.AdbShell
import com.woshiyigemanhuajia.btpopup.battery.BatteryInfo
import com.woshiyigemanhuajia.btpopup.battery.BatteryRepository
import com.woshiyigemanhuajia.btpopup.databinding.ActivityMainBinding
import com.woshiyigemanhuajia.btpopup.overlay.PopupOverlayManager
import com.woshiyigemanhuajia.btpopup.service.BluetoothMonitorService
import com.woshiyigemanhuajia.btpopup.service.GuardService
import com.woshiyigemanhuajia.btpopup.service.KeepAliveAccessibilityService
import com.woshiyigemanhuajia.btpopup.util.PermissionGuard
import com.woshiyigemanhuajia.btpopup.util.Prefs
import rikka.shizuku.Shizuku

class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQ_SHIZUKU = 10086
        private const val REQ_PERMS = 2002
        private const val REQ_PICK = 2001
    }

    private lateinit var b: ActivityMainBinding
    private lateinit var previewStage: PopupPreviewStage
    private var loadingUi = false
    private var chainingPerms = false
    private val ui = Handler(Looper.getMainLooper())
    private val rebuildPreviewTask = Runnable { rebuildLivePreview() }

    private val binderReceived = object : Shizuku.OnBinderReceivedListener {
        override fun onBinderReceived() {
            runOnUiThread { refreshPermissions() }
        }
    }

    private val binderDead = object : Shizuku.OnBinderDeadListener {
        override fun onBinderDead() {
            runOnUiThread { refreshPermissions() }
        }
    }

    private val permListener = object : Shizuku.OnRequestPermissionResultListener {
        override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
            runOnUiThread {
                if (requestCode == REQ_SHIZUKU && grantResult == PackageManager.PERMISSION_GRANTED) {
                    toast("已获得 Stellar / Shizuku 权限，开始执行一键授权")
                    runOneKeyGrant()
                } else if (requestCode == REQ_SHIZUKU) {
                    toast("Stellar / Shizuku 权限被拒绝")
                }
                refreshPermissions()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        previewStage = PopupPreviewStage(this, b.previewStage)

        setupSliders()
        setupSwitches()
        setupButtons()
        setupShizuku()

        // 权限静默失败修复：启动即自检，缺运行时权限（蓝牙连接 / 通知）时直接发起系统申请；
        // 悬浮窗权限不在这里跳系统页，交由首页横幅的「一键授权」入口触发，避免启动即被弹走。
        if (PermissionGuard.missingRuntimePermissions(this).isNotEmpty()) {
            ui.post { requestRuntimePerms() }
        }

        if (Prefs.monitorEnabled && !BluetoothMonitorService.running) {
            BluetoothMonitorService.start(this)
        }
    }

    override fun onResume() {
        super.onResume()
        syncFromPrefs()
        refreshPermissions()
        refreshPermissionBanner()
        refreshLiveStatus()
        refreshPreview()
        refreshServiceStatus()
        refreshAccessibilityState()
    }

    /** 刷新「无障碍保活」开关与状态提示：开关本身只反映系统里的真实启用状态 */
    private fun refreshAccessibilityState() {
        val on = KeepAliveAccessibilityService.isEnabled(this)
        if (b.swAccessibility.isChecked != on) {
            loadingUi = true
            b.swAccessibility.isChecked = on
            loadingUi = false
        }
        b.tvA11yState.text =
            if (on) getString(R.string.a11y_state_on) else getString(R.string.a11y_state_off)
    }

    override fun onDestroy() {
        ui.removeCallbacks(rebuildPreviewTask)
        if (::previewStage.isInitialized) previewStage.destroy()
        try {
            Shizuku.removeBinderReceivedListener(binderReceived)
            Shizuku.removeBinderDeadListener(binderDead)
            Shizuku.removeRequestPermissionResultListener(permListener)
        } catch (t: Throwable) {
            // ignore
        }
        super.onDestroy()
    }

    // ------------------------------------------------------------------ 滑块

    private fun setupSliders() {
        setupSeek(b.sbAlpha, b.tvAlphaVal, 0, 100,
            { Prefs.panelAlpha }, { Prefs.panelAlpha = it }) { "$it%" }

        // 图片位置与大小微调（修复「上传的图片跟 GIF 不能自己调整位置跟大小」）
        setupSeek(b.sbImageScale, b.tvImageScaleVal, 20, 400,
            { Prefs.imageScalePercent }, { Prefs.imageScalePercent = it }, { "$it%" })
        setupSeek(b.sbImageOffsetX, b.tvImageOffsetXVal, -200, 200,
            { Prefs.imageOffsetXDp }, { Prefs.imageOffsetXDp = it }, { "${it}dp" })
        setupSeek(b.sbImageOffsetY, b.tvImageOffsetYVal, -200, 200,
            { Prefs.imageOffsetYDp }, { Prefs.imageOffsetYDp = it }, { "${it}dp" })
        setupSeek(b.sbImageRotation, b.tvImageRotationVal, -180, 180,
            { Prefs.imageRotationDeg }, { Prefs.imageRotationDeg = it }, { "${it}°" })

        setupSeek(b.sbImageHeight, b.tvImageHeightVal, 40, 600,
            { Prefs.imageHeightDp }, { Prefs.imageHeightDp = it }) { "${it}dp" }

        setupSeek(b.sbWidth, b.tvWidthVal, 20, 100,
            { Prefs.widthPercent }, { Prefs.widthPercent = it }) { "$it%" }

        // 竖屏整体等比缩放：字号 / 内边距 / 间距 / 图片区高度共用同一系数，
        // 卡片高度随内容自然变化 —— 调大就是整体等比放大，不是把文字下方的空白拉长
        setupSeek(b.sbHeight, b.tvHeightVal, 50, 200,
            { Prefs.portraitScalePercent }, { Prefs.portraitScalePercent = it }) { "$it%" }

        setupSeek(b.sbPosX, b.tvPosXVal, 0, 100,
            { Prefs.posXPercent }, { Prefs.posXPercent = it }) { posLabel(it) }

        setupSeek(b.sbPosY, b.tvPosYVal, 0, 100,
            { Prefs.posYPercent }, { Prefs.posYPercent = it }) { posLabel(it) }

        setupSeek(b.sbRadius, b.tvRadiusVal, 0, 200,
            { Prefs.cornerRadiusDp }, { Prefs.cornerRadiusDp = it }) { "${it}dp" }

        setupSeek(b.sbLandWidth, b.tvLandWidthVal, 20, 100,
            { Prefs.landWidthPercent }, { Prefs.landWidthPercent = it }) { "$it%" }

        setupSeek(b.sbLandHeight, b.tvLandHeightVal, 0, 800,
            { if (Prefs.landHeightFixed) Prefs.landHeightDp else 0 },
            { v ->
                Prefs.landHeightFixed = v > 0
                if (v > 0) Prefs.landHeightDp = if (v < 80) 80 else v
            }) { if (it <= 0) "自动" else "${it}dp" }

        setupSeek(b.sbLandMargin, b.tvLandMarginVal, 0, 120,
            { Prefs.landMarginDp }, { Prefs.landMarginDp = it }) { "${it}dp" }

        // 横屏整体等比缩放：宽度 / 高度 / 字号 / 间距共用同一系数，形态不变、只是整体变小
        setupSeek(b.sbLandScale, b.tvLandScaleVal, 30, 150,
            { Prefs.landScalePercent }, { Prefs.landScalePercent = it }) { "$it%" }

        // 详情区动态模糊层的模糊强度
        setupSeek(b.sbLandBlurRadius, b.tvLandBlurRadiusVal, 0, 60,
            { Prefs.landBlurRadiusDp }, { Prefs.landBlurRadiusDp = it }) { "${it}dp" }

        // 模糊层与上方清晰画面之间的渐隐过渡带（0 = 硬边）
        setupSeek(b.sbLandBlurFade, b.tvLandBlurFadeVal, 0, 80,
            { Prefs.landBlurFadeDp }, { Prefs.landBlurFadeDp = it }) { "${it}dp" }

        setupSeek(b.sbAnimDuration, b.tvAnimDurationVal, 60, 1500,
            { Prefs.animDuration }, { Prefs.animDuration = it }) { "${it}ms" }

        setupSeek(b.sbDismissDelay, b.tvDismissDelayVal, 0, 120,
            { Prefs.dismissDelayMs / 1000 }, { Prefs.dismissDelayMs = it * 1000 }) {
            if (it <= 0) "不自动关闭" else "${it}s"
        }

        setupImageScale()
        setupColorButtons()
    }

    private fun posLabel(v: Int): String = when {
        v < 20 -> "靠左/靠上 $v%"
        v > 80 -> "靠右/靠下 $v%"
        else -> "居中 $v%"
    }

    private fun setupSeek(
        bar: SeekBar,
        label: TextView,
        min: Int,
        max: Int,
        get: () -> Int,
        set: (Int) -> Unit,
        fmt: (Int) -> String
    ) {
        bar.max = (max - min).coerceAtLeast(1)
        bar.progress = (get() - min).coerceIn(0, bar.max)
        label.text = fmt(get())
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val v = (min + progress).coerceIn(min, max)
                set(v)
                label.text = fmt(v)
                if (fromUser) refreshPreview()
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
    }

    private fun syncFromPrefs() {
        loadingUi = true
        setSeekValue(b.sbAlpha, b.tvAlphaVal, 0, Prefs.panelAlpha) { "$it%" }
        setSeekValue(b.sbImageHeight, b.tvImageHeightVal, 40, Prefs.imageHeightDp) { "${it}dp" }
        setSeekValue(b.sbImageScale, b.tvImageScaleVal, 20, Prefs.imageScalePercent) { "$it%" }
        setSeekValue(b.sbImageOffsetX, b.tvImageOffsetXVal, -200, Prefs.imageOffsetXDp) { "${it}dp" }
        setSeekValue(b.sbImageOffsetY, b.tvImageOffsetYVal, -200, Prefs.imageOffsetYDp) { "${it}dp" }
        setSeekValue(b.sbImageRotation, b.tvImageRotationVal, -180, Prefs.imageRotationDeg) { "${it}°" }
        setSeekValue(b.sbWidth, b.tvWidthVal, 20, Prefs.widthPercent) { "$it%" }
        setSeekValue(b.sbHeight, b.tvHeightVal, 50, Prefs.portraitScalePercent) { "$it%" }
        setSeekValue(b.sbPosX, b.tvPosXVal, 0, Prefs.posXPercent) { posLabel(it) }
        setSeekValue(b.sbPosY, b.tvPosYVal, 0, Prefs.posYPercent) { posLabel(it) }
        setSeekValue(b.sbRadius, b.tvRadiusVal, 0, Prefs.cornerRadiusDp) { "${it}dp" }
        setSeekValue(b.sbLandWidth, b.tvLandWidthVal, 20, Prefs.landWidthPercent) { "$it%" }
        setSeekValue(b.sbLandHeight, b.tvLandHeightVal, 0, if (Prefs.landHeightFixed) Prefs.landHeightDp else 0) {
            if (it <= 0) "自动" else "${it}dp"
        }
        setSeekValue(b.sbLandMargin, b.tvLandMarginVal, 0, Prefs.landMarginDp) { "${it}dp" }
        setSeekValue(b.sbAnimDuration, b.tvAnimDurationVal, 60, Prefs.animDuration) { "${it}ms" }
        setSeekValue(b.sbDismissDelay, b.tvDismissDelayVal, 0, Prefs.dismissDelayMs / 1000) {
            if (it <= 0) "不自动关闭" else "${it}s"
        }

        b.swPortraitWhite.isChecked = Prefs.portraitSolidWhite
        b.swWindowBlur.isChecked = Prefs.windowBlurEnabled
        b.swBlurFromImage.isChecked = Prefs.blurFromImage
        b.swAutoPopup.isChecked = Prefs.autoPopup
        b.swForeground.isChecked = Prefs.foregroundGuard
        b.swAccessibility.isChecked = KeepAliveAccessibilityService.isEnabled(this)
        b.swBootStart.isChecked = Prefs.bootStart
        b.swBatteryOpt.isChecked = isIgnoringBatteryOptimization()

        when (Prefs.animType) {
            "fade" -> b.chipFade.isChecked = true
            "scale" -> b.chipScale.isChecked = true
            "slide_top" -> b.chipSlideTop.isChecked = true
            "slide_bottom" -> b.chipSlideBottom.isChecked = true
            else -> b.chipSpring.isChecked = true
        }

        when (Prefs.imageScaleMode) {
            "fit" -> b.chipScaleFit.isChecked = true
            "stretch" -> b.chipScaleStretch.isChecked = true
            "center" -> b.chipScaleCenter.isChecked = true
            else -> b.chipScaleCrop.isChecked = true
        }
        refreshColorSwatches()
        loadingUi = false
    }

    private fun setSeekValue(bar: SeekBar, label: TextView, min: Int, value: Int, fmt: (Int) -> String) {
        bar.max = bar.max.coerceAtLeast(1)
        bar.progress = (value - min).coerceIn(0, bar.max)
        label.text = fmt(value)
    }

    // ------------------------------------------------------------------ 开关

    private fun setupSwitches() {
        b.swPortraitWhite.setOnCheckedChangeListener { _, v ->
            if (loadingUi) return@setOnCheckedChangeListener
            Prefs.portraitSolidWhite = v
            refreshPreview()
        }

        b.swWindowBlur.setOnCheckedChangeListener { _, v ->
            if (loadingUi) return@setOnCheckedChangeListener
            Prefs.windowBlurEnabled = v
            refreshPreview()
        }

        b.swBlurFromImage.setOnCheckedChangeListener { _, v ->
            if (loadingUi) return@setOnCheckedChangeListener
            Prefs.blurSourceMode = if (v) "image" else "screen"
            refreshPreview()
        }

        b.swAutoPopup.setOnCheckedChangeListener { _, v ->
            if (loadingUi) return@setOnCheckedChangeListener
            Prefs.autoPopup = v
        }

        b.swForeground.setOnCheckedChangeListener { _, v ->
            if (loadingUi) return@setOnCheckedChangeListener
            Prefs.foregroundGuard = v
            if (v) {
                BluetoothMonitorService.start(this)
                GuardService.start(this)
            } else {
                GuardService.stop(this)
            }
        }

        // 无障碍保活：点击直接跳到系统无障碍设置页（回来时 onResume 会刷新真实状态）
        b.swAccessibility.setOnClickListener {
            loadingUi = true
            b.swAccessibility.isChecked = KeepAliveAccessibilityService.isEnabled(this)
            loadingUi = false
            KeepAliveAccessibilityService.openSettings(this)
        }

        b.swBootStart.setOnCheckedChangeListener { _, v ->
            if (loadingUi) return@setOnCheckedChangeListener
            Prefs.bootStart = v
        }

        b.swBatteryOpt.setOnCheckedChangeListener { _, v ->
            if (loadingUi) return@setOnCheckedChangeListener
            if (v && !isIgnoringBatteryOptimization()) {
                requestIgnoreBatteryOptimization()
            }
        }

        b.cgAnimType.setOnCheckedStateChangeListener { _, checkedIds ->
            if (loadingUi) return@setOnCheckedStateChangeListener
            val type = when (checkedIds.firstOrNull()) {
                R.id.chipFade -> "fade"
                R.id.chipScale -> "scale"
                R.id.chipSlideTop -> "slide_top"
                R.id.chipSlideBottom -> "slide_bottom"
                else -> "spring"
            }
            Prefs.animType = type
        }
    }

    // ------------------------------------------------------------------ 按钮

    private fun setupButtons() {
        b.btnPickImage.setOnClickListener { pickImage() }
        b.btnClearImage.setOnClickListener {
            Prefs.imageUri = null
            refreshPreview()
        }
        b.btnQuickPreview.setOnClickListener { showTestPopup() }
        b.btnLandscapePreview.setOnClickListener { toggleLandscapePreview() }
        b.btnAdbGrant.setOnClickListener { runOneKeyGrant() }
        b.btnCopyAdb.setOnClickListener { copyAdbScript() }
        b.btnAutoStartSetting.setOnClickListener { openAutoStartSettings() }
        b.btnPermFix.setOnClickListener { fixPermissions() }
        b.btnOpenBtDetail.setOnClickListener { openBluetoothDeviceSettings() }
        b.btnDisableSysPopup.setOnClickListener { disableSystemEarbudsPopup() }
    }

    // ------------------------------------------------------------------ 系统耳机弹窗屏蔽

    /**
     * 打开系统蓝牙设置：HyperOS 的耳机系统弹窗是**按设备记忆**的 ——
     * 你对 Redmi Buds 5 Pro 关过"连接弹窗"，换成 Buds 6（另一台设备）要单独再关一次。
     * 在耳机详情页把「连接弹窗 / 智能弹窗」关掉即可。
     */
    private fun openBluetoothDeviceSettings() {
        try {
            startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
            toast("找到 Redmi Buds 6 的详情页，关闭「连接弹窗」")
        } catch (t: Throwable) {
            toast("无法打开蓝牙设置")
        }
    }

    /**
     * 通过 Shizuku 禁用 HyperOS 耳机弹窗相关服务（com.xiaomi.bluetooth）。
     * 这是全局方案：禁用后所有小米耳机的系统弹窗都不会再出现。
     * 恢复命令：adb shell pm enable com.xiaomi.bluetooth
     */
    private fun disableSystemEarbudsPopup() {
        if (!AdbShell.binderAlive()) {
            toast("未检测到 Stellar / Shizuku，请先启动服务")
            return
        }
        if (!AdbShell.hasPermission()) {
            AdbShell.requestPermission(REQ_SHIZUKU)
            return
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("禁用系统耳机弹窗")
            .setMessage(
                "将执行：pm disable-user --user 0 com.xiaomi.bluetooth\n\n" +
                    "效果：HyperOS 所有耳机的系统弹窗（含 Redmi Buds 6）不再出现，" +
                    "本应用的弹窗不受影响。\n" +
                    "可能影响：小米设备互联 / 耳机地图等小米快连周边功能。\n\n" +
                    "恢复方式（随时）：adb shell pm enable com.xiaomi.bluetooth"
            )
            .setPositiveButton("确认禁用") { _, _ ->
                toast("正在执行…")
                Thread {
                    val results = AdbShell.execAll(
                        listOf("pm disable-user --user 0 com.xiaomi.bluetooth")
                    )
                    val ok = results.firstOrNull()?.ok == true
                    runOnUiThread {
                        if (ok) toast("已禁用系统耳机弹窗；换耳机也不会再弹")
                        else toast("执行失败，可复制 ADB 命令手动执行")
                    }
                }.start()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun setupImageScale() {
        b.cgImageScale.setOnCheckedStateChangeListener { _, checkedIds ->
            if (loadingUi) return@setOnCheckedStateChangeListener
            Prefs.imageScaleMode = when (checkedIds.firstOrNull()) {
                R.id.chipScaleFit -> "fit"
                R.id.chipScaleStretch -> "stretch"
                R.id.chipScaleCenter -> "center"
                else -> "crop"
            }
            refreshPreview()
        }
    }

    private fun setupColorButtons() {
        b.btnPanelColor.setOnClickListener {
            pickColor("面板底色", Prefs.panelColor) { Prefs.panelColor = it }
        }
        b.btnTextColor.setOnClickListener {
            pickColor("文字颜色", Prefs.textColor) { Prefs.textColor = it }
        }
        b.btnAccentColor.setOnClickListener {
            pickColor("强调颜色", Prefs.accentColor) { Prefs.accentColor = it }
        }
    }

    private fun pickColor(title: String, current: Int, apply: (Int) -> Unit) {
        ColorPickerDialog(this, title, current) { picked ->
            apply(picked)
            refreshColorSwatches()
            refreshPreview()
        }.show()
    }

    private fun refreshColorSwatches() {
        paintSwatch(b.btnPanelColor, Prefs.panelColor)
        paintSwatch(b.btnTextColor, Prefs.textColor)
        paintSwatch(b.btnAccentColor, Prefs.accentColor)
    }

    private fun paintSwatch(view: TextView, color: Int) {
        val bg = ContextCompat.getDrawable(this, R.drawable.bg_color_swatch)?.mutate() as? GradientDrawable
            ?: GradientDrawable().apply { cornerRadius = dp(10).toFloat() }
        bg.setColor(color)
        bg.setStroke(dp(1), if (isColorDark(color)) 0x33FFFFFF else 0x33000000)
        view.background = bg
        view.setTextColor(if (isColorDark(color)) Color.WHITE else 0xFF0F172A.toInt())
    }

    private fun isColorDark(color: Int): Boolean {
        val luminance = 0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)
        return luminance < 140
    }

    private fun pickImage() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        try {
            startActivityForResult(intent, REQ_PICK)
        } catch (t: Throwable) {
            toast("无法打开图片选择器")
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PICK && resultCode == RESULT_OK) {
            val uri: Uri = data?.data ?: return
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (t: Throwable) {
                // 部分来源不支持持久化授权，忽略
            }
            // 【图片丢失根治】把所选图片 / GIF 复制一份到应用私有目录，
            // 之后永远从本地文件加载 —— content:// 授权失效、提供方抽风、系统清理
            // 等任何原因都不再会导致"图片突然没了"。失败时回退用原 URI。
            val stored = copyMediaToLocal(uri)
            Prefs.imageUri = stored?.let { Uri.fromFile(it).toString() } ?: uri.toString()
            refreshPreview()
        }
    }

    /** 把用户选中的图片 / GIF 复制进应用私有目录（filesDir/popup_media/image.xxx） */
    private fun copyMediaToLocal(uri: Uri): java.io.File? {
        return try {
            val isGif = contentResolver.getType(uri) == "image/gif" ||
                (uri.lastPathSegment?.endsWith(".gif", ignoreCase = true) == true)
            val dir = java.io.File(filesDir, "popup_media").apply { mkdirs() }
            // 先写临时文件再替换，避免复制到一半时弹窗加载到损坏文件
            val out = java.io.File(dir, if (isGif) "image.gif" else "image.img")
            val tmp = java.io.File(dir, "image.tmp")
            val input = contentResolver.openInputStream(uri) ?: return null
            input.use { src -> tmp.outputStream().use { dst -> src.copyTo(dst) } }
            if (tmp.length() <= 0) return null
            if (out.exists()) out.delete()
            val ok = tmp.renameTo(out)
            if (ok && out.length() > 0) out else null
        } catch (t: Throwable) {
            null
        }
    }

    private fun refreshPreview() {
        if (!::previewStage.isInitialized) return
        previewStage.setInfo(BatteryRepository.all().maxByOrNull { it.updatedAt } ?: dummyInfo())
        previewStage.requestRender()
        syncPreviewMode()
        // 弹窗已在屏上时，改完参数立刻重建，做到所见即所得
        ui.removeCallbacks(rebuildPreviewTask)
        ui.postDelayed(rebuildPreviewTask, 220)
    }

    /** 横屏预览入口：竖屏持机时也能看到横屏弹窗的真实样式与位置 */
    private fun toggleLandscapePreview() {
        val next = !previewStage.isLandscape()
        previewStage.setLandscape(next)
        syncPreviewMode()
    }

    /** 预览模式切换后同步按钮文案 */
    private fun syncPreviewMode() {
        if (!::previewStage.isInitialized) return
        b.btnLandscapePreview.text = if (previewStage.isLandscape()) "竖屏预览" else "横屏预览"
    }

    private fun rebuildLivePreview() {
        if (!PopupOverlayManager.isShowing()) return
        if (!Settings.canDrawOverlays(this)) return
        PopupOverlayManager.show(this, BatteryRepository.all().maxByOrNull { it.updatedAt } ?: dummyInfo(), Prefs.imageUri)
    }

    /**
     * 预览占位数据：必须是**同一个稳定实例**。
     * BatteryInfo 是 data class，每次新建都会带上新的 updatedAt，
     * 导致 PopupPreviewStage.setInfo 的「内容相同则复用视图」判断永远失效 ——
     * 滑块每动一下整个预览视图就被销毁重建，Coil 被迫重新解码 GIF / 图片，
     * 连续拖动时解码被反复取消，表现就是「一调参数 GIF / 图片直接没了」。
     */
    private val previewDummy: BatteryInfo =
        BatteryInfo("测试耳机", "00:11:22:33:44:55", 88, 76, 54, 88, false, "预览数据")

    private fun dummyInfo(): BatteryInfo = previewDummy

    private fun showTestPopup() {
        if (!Settings.canDrawOverlays(this)) {
            toast("请先授予悬浮窗权限")
            requestOverlayPermission()
            return
        }
        val info = BatteryRepository.all().maxByOrNull { it.updatedAt } ?: dummyInfo()
        PopupOverlayManager.show(this, info, Prefs.imageUri)
    }

    // ------------------------------------------------------------------ 权限

    private fun refreshPermissions() {
        val status = PermissionGuard.check(this)
        val rows = mutableListOf<PermRow>()
        rows += PermRow(getString(R.string.perm_label_overlay), status.overlay) { requestOverlayPermission() }
        rows += PermRow(getString(R.string.perm_label_notif), status.notifications) { requestRuntimePerms() }
        rows += PermRow(getString(R.string.perm_label_bt), status.bluetoothConnect) { requestRuntimePerms() }
        rows += PermRow("定位权限（部分机型扫描必需）", hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) { requestRuntimePerms() }
        rows += PermRow("忽略电池优化", isIgnoringBatteryOptimization()) { requestIgnoreBatteryOptimization() }
        rows += PermRow(
            "Stellar / Shizuku 权限",
            AdbShell.hasPermission()
        ) { runOneKeyGrant() }

        b.permContainer.removeAllViews()
        rows.forEach { addPermRow(it) }

        val shizukuText = when {
            !AdbShell.binderAlive() -> "Stellar / Shizuku：未连接（请先打开 Stellar 并启动服务）"
            AdbShell.hasPermission() -> "Stellar / Shizuku：已连接且已授权"
            else -> "Stellar / Shizuku：已连接，等待授权"
        }
        b.tvShizukuState.text = shizukuText

        refreshPermissionBanner()
    }

    /**
     * 首页顶部权限横幅：未授权时列出缺失项并显示「一键授权」，
     * 已授权时给出明确结论——避免用户"以为已经开启"的静默失败。
     */
    private fun refreshPermissionBanner() {
        val missing = PermissionGuard.missingLabels(this)
        if (missing.isEmpty()) {
            b.tvPermBanner.text = getString(R.string.perm_banner_ok)
            b.btnPermFix.visibility = View.GONE
        } else {
            b.tvPermBanner.text = getString(R.string.perm_banner_missing, missing.joinToString("、"))
            b.btnPermFix.visibility = View.VISIBLE
        }
    }

    /** 一键授权：悬浮窗走系统设置页，蓝牙连接 / 通知走运行时权限对话框，逐项补齐 */
    private fun fixPermissions() {
        if (!PermissionGuard.check(this).overlay) {
            requestOverlayPermission()
            return
        }
        if (PermissionGuard.missingRuntimePermissions(this).isNotEmpty()) {
            chainingPerms = true
            requestRuntimePerms()
            return
        }
        toast(getString(R.string.perm_banner_ok))
        refreshPermissions()
    }

    private data class PermRow(val title: String, val granted: Boolean, val action: () -> Unit)

    private fun addPermRow(row: PermRow) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(9), 0, dp(9))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val title = TextView(this).apply {
            text = row.title
            textSize = 13f
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val state = TextView(this).apply {
            text = if (row.granted) "已授权" else "去授权"
            textSize = 12f
            setPadding(dp(12), dp(5), dp(12), dp(5))
            setTextColor(
                ContextCompat.getColor(
                    context,
                    if (row.granted) R.color.brand_teal_dark else R.color.text_tertiary
                )
            )
            setBackgroundResource(R.drawable.bg_perm_chip)
        }

        container.addView(title)
        container.addView(state)
        container.setOnClickListener { row.action() }
        b.permContainer.addView(container)
    }

    private fun hasPermission(perm: String): Boolean {
        if (Build.VERSION.SDK_INT < 23) return true
        if (perm == Manifest.permission.POST_NOTIFICATIONS && Build.VERSION.SDK_INT < 33) return true
        if ((perm == Manifest.permission.BLUETOOTH_CONNECT || perm == Manifest.permission.BLUETOOTH_SCAN)
            && Build.VERSION.SDK_INT < 31
        ) return true
        return ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestRuntimePerms() {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 31) {
            perms += Manifest.permission.BLUETOOTH_CONNECT
            perms += Manifest.permission.BLUETOOTH_SCAN
        }
        if (Build.VERSION.SDK_INT >= 33) {
            perms += Manifest.permission.POST_NOTIFICATIONS
        }
        perms += Manifest.permission.ACCESS_FINE_LOCATION
        perms += Manifest.permission.ACCESS_COARSE_LOCATION
        ActivityCompat.requestPermissions(this, perms.toTypedArray(), REQ_PERMS)
    }

    private fun requestOverlayPermission() {
        try {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + packageName)
                )
            )
        } catch (t: Throwable) {
            toast("无法打开悬浮窗权限设置")
        }
    }

    private fun isIgnoringBatteryOptimization(): Boolean {
        return try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.isIgnoringBatteryOptimizations(packageName)
        } catch (t: Throwable) {
            false
        }
    }

    private fun requestIgnoreBatteryOptimization() {
        try {
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + packageName)
                )
            )
        } catch (t: Throwable) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (t2: Throwable) {
                toast("无法打开电池优化设置")
            }
        }
    }

    private fun openAutoStartSettings() {
        val candidates = listOf(
            ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
            ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
            ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"),
            ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
            ComponentName("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"),
            ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
            ComponentName("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"),
            ComponentName("com.letv.android.letvsafe", "com.letv.android.letvsafe.AutobootManageActivity")
        )
        for (cn in candidates) {
            try {
                startActivity(Intent().setComponent(cn))
                return
            } catch (t: Throwable) {
                // 继续尝试
            }
        }
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + packageName)))
        } catch (t: Throwable) {
            toast("请手动在系统设置中开启自启动")
        }
    }

    // ------------------------------------------------------------------ Shizuku

    private fun setupShizuku() {
        try {
            Shizuku.addBinderReceivedListener(binderReceived)
            Shizuku.addBinderDeadListener(binderDead)
            Shizuku.addRequestPermissionResultListener(permListener)
        } catch (t: Throwable) {
            // Stellar 未安装或未实现 Shizuku 协议
        }
    }

    private fun runOneKeyGrant() {
        if (!AdbShell.binderAlive()) {
            toast("未检测到 Stellar / Shizuku 服务，请先打开 Stellar 并启动服务")
            refreshPermissions()
            try {
                val i = packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
                val i2 = packageManager.getLaunchIntentForPackage("com.stellar.adb")
                val target = i ?: i2
                if (target != null) startActivity(target)
                else toast("未安装 Stellar / Shizuku")
            } catch (t: Throwable) {
                toast("未安装 Stellar / Shizuku")
            }
            return
        }
        if (!AdbShell.hasPermission()) {
            AdbShell.requestPermission(REQ_SHIZUKU)
            return
        }
        val pkg = packageName
        toast("正在执行一键授权…")
        Thread {
            val results = AdbShell.execAll(AdbShell.buildGrantCommands(pkg))
            val okCount = results.count { it.ok }
            runOnUiThread {
                toast("一键授权完成：$okCount/${results.size} 项成功")
                refreshPermissions()
            }
        }.start()
    }

    private fun copyAdbScript() {
        val script = AdbShell.buildGrantScript(packageName)
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("bt-popup-adb", script))
            toast("ADB 授权命令已复制到剪贴板")
        } catch (t: Throwable) {
            toast("复制失败")
        }
    }

    // ------------------------------------------------------------------ 状态

    private fun refreshServiceStatus() {
        val running = BluetoothMonitorService.running
        val missing = PermissionGuard.missingLabels(this)
        // 权限静默失败修复：把"服务在跑"和"权限是否真的给了"分开说清楚
        b.tvServiceStatus.text = when {
            !running -> getString(R.string.svc_state_stopped)
            missing.isEmpty() -> getString(R.string.svc_state_running_ok)
            else -> getString(R.string.svc_state_running_perm_missing, missing.joinToString("、"))
        }
        val color = if (running) ContextCompat.getColor(this, R.color.brand_teal)
        else ContextCompat.getColor(this, R.color.status_off)
        b.dotStatus.setTextColor(color)
    }

    private fun refreshLiveStatus() {
        val info = BatteryRepository.all().maxByOrNull { it.updatedAt }
        if (info == null) {
            b.tvLiveDevice.text = "当前未连接蓝牙音频设备"
            b.tvLiveLeft.text = "--%"
            b.tvLiveRight.text = "--%"
            b.tvLiveCase.text = "--%"
            b.tvBatterySource.text = "数据来源：等待耳机连接"
            return
        }
        b.tvLiveDevice.text = info.name + "\n" + info.address
        b.tvLiveLeft.text = percent(info.left)
        b.tvLiveRight.text = percent(info.right)
        b.tvLiveCase.text = percent(info.case)
        val extra = if (!info.hasSplit && info.overall >= 0) "（系统仅上报整体电量 ${info.overall}%）" else ""
        b.tvBatterySource.text = "数据来源：" + info.source.ifBlank { "未知" } + extra
    }

    private fun percent(v: Int): String = if (v in 0..100) "$v%" else "--%"

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERMS) {
            refreshPermissions()
            // 一键授权链路：运行时权限处理完后，若还缺悬浮窗权限就继续往下走
            if (chainingPerms) {
                chainingPerms = false
                if (!PermissionGuard.check(this).overlay) requestOverlayPermission()
            }
        }
    }
}
