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
import android.util.Log
import com.woshiyigemanhuajia.btpopup.util.PopupSound
import android.os.SystemClock
import android.widget.ImageView
import coil.load
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val REQ_SHIZUKU = 10086
        private const val REQ_PERMS = 2002
        private const val REQ_PICK = 2001
        private const val REQ_PICK_SOUND = 2003
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
        setupTabs()

        // 权限静默失败修复：启动即自检，缺运行时权限（蓝牙连接 / 通知）时直接发起系统申请；
        // 悬浮窗权限不在这里跳系统页，交由首页横幅的「一键授权」入口触发，避免启动即被弹走。
        if (PermissionGuard.missingRuntimePermissions(this).isNotEmpty()) {
            ui.post { requestRuntimePerms() }
        }

        if (Prefs.monitorEnabled && !BluetoothMonitorService.running) {
            BluetoothMonitorService.start(this)
        }
    }

    // ------------------------------------------------------------------ 底部导航

    /**
     * 底部四页导航：弹窗 / 设备 / 外观 / 设置。
     *
     * 【本次改动的范围边界】
     * 只做「页面显隐 + 标题变化 + 选中态」这三件事。
     * 所有功能控件仍在各自页面里、id 与绑定逻辑完全没变 ——
     * 因此这次重排不会触及弹窗渲染、触发、去重、模糊等任何既有逻辑。
     */
    private fun setupTabs() {
        val items = listOf(
            TabItem(b.tabHome, b.pageHome, "弹窗"),
            TabItem(b.tabDevice, b.pageDevice, "设备"),
            TabItem(b.tabLook, b.pageLook, "外观"),
            TabItem(b.tabSet, b.pageSet, "设置")
        )
        items.forEach { item ->
            item.tab.setOnClickListener { selectTab(items, item) }
        }
        selectTab(items, items[0])

        b.btnHelp.setOnClickListener { showHelp() }
    }

    private class TabItem(
        val tab: android.view.View,
        val page: android.view.View,
        val title: String
    )

    private fun selectTab(items: List<TabItem>, target: TabItem) {
        items.forEach { item ->
            val on = item === target
            item.page.visibility = if (on) android.view.View.VISIBLE else android.view.View.GONE
            item.tab.setBackgroundResource(
                if (on) R.drawable.bg_tab_ind else android.R.color.transparent
            )
            // 选中态：文字改主色并加粗（结构是两个 LinearLayout > TextView）
            val tv = (item.tab as? android.view.ViewGroup)?.getChildAt(0) as? android.widget.TextView
            tv?.setTextColor(
                resources.getColor(
                    if (on) R.color.brand_teal_dark else R.color.text_secondary, theme
                )
            )
            tv?.setTypeface(null, if (on) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        }
        b.tvPageTitle.text = target.title
        // 切到设备页时刷新一次列表（可能是首次连接后才出现设备）
        if (target.title == "设备") refreshDeviceNameList()
    }

    private fun showHelp() {
        android.app.AlertDialog.Builder(this)
            .setTitle("使用说明")
            .setMessage(
                "弹窗：服务状态、提示音、实时电量\n" +
                    "设备：已配对耳机，可单独改名\n" +
                    "外观：预览、图片 / GIF、尺寸位置、动画，竖屏与横屏分别保存\n" +
                    "设置：权限与 ADB 授权、屏蔽系统弹窗、后台保活\n\n" +
                    "开盖即弹窗需要：悬浮窗权限 + 后台弹出权限 + 允许自启动 + 关闭电池优化。"
            )
            .setPositiveButton("知道了", null)
            .show()
    }

    // ------------------------------------------------------------------ 后台任务隐藏

    /**
     * 【修复「后台任务隐藏」不生效】
     *
     * 只靠 Manifest 的 excludeFromRecents 不够：它只在 **task 创建时** 生效，
     * 已经出现在最近任务里的 task 不会被立刻移除，要等系统下次重新评估
     * （通常得再切一次别的 App 才会刷新）—— 这正是「划到后台还有，点进别的 App 才消失」。
     *
     * 这里改成主动移除，两条一起上：
     *  1) ActivityManager.AppTask.setExcludeFromRecents(true)：立刻把当前 task 标记为排除；
     *  2) finishAndRemoveTask()：切到后台后把 Activity 连同 task 一起收掉，
     *     最近任务里从此不再有本 App 的卡片。
     *
     * 弹窗由 Service + 静态广播接收器驱动，Activity 销毁完全不影响弹窗。
     */

    /** 启动外部 Activity（选图 / 授权）期间抑制隐藏，否则回不来 */
    private var suppressHideTask = false

    /** 上次前台补拉监听服务的时间，用于节流 */
    private var lastStartAttemptAt = 0L

    private val hideTaskRunnable = Runnable { hideTaskFromRecents() }

    private fun hideTaskFromRecents() {
        if (suppressHideTask) return
        // 1) 先标记排除：多数 ROM 上这一步就已经让它从最近任务消失
        try {
            if (Build.VERSION.SDK_INT >= 21) {
                val am = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
                am.appTasks?.forEach { task ->
                    try {
                        task.setExcludeFromRecents(true)
                    } catch (ignored: Throwable) {
                    }
                }
            }
        } catch (ignored: Throwable) {
        }
        // 2) 再彻底收掉 task，保证下一次划到后台一定看不到
        try {
            if (Build.VERSION.SDK_INT >= 21 && !isFinishing) {
                finishAndRemoveTask()
            }
        } catch (ignored: Throwable) {
        }
    }

    private fun scheduleHideTask() {
        ui.removeCallbacks(hideTaskRunnable)
        ui.postDelayed(hideTaskRunnable, 600L)
    }

    private fun cancelHideTask() {
        ui.removeCallbacks(hideTaskRunnable)
    }

    /** 启动外部 Activity 前调用：期间不要隐藏，否则选图回来 App 已经没了 */
    private fun beginExternalActivity() {
        suppressHideTask = true
        cancelHideTask()
    }

    /** 回到前台时调用 */
    private fun endExternalActivity() {
        suppressHideTask = false
        // 回来后立刻补一次标记，保证不在最近任务里
        try {
            if (Build.VERSION.SDK_INT >= 21) {
                val am = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
                am.appTasks?.forEach { task ->
                    try {
                        task.setExcludeFromRecents(true)
                    } catch (ignored: Throwable) {
                    }
                }
            }
        } catch (ignored: Throwable) {
        }
    }

    override fun onResume() {
        super.onResume()
        cancelHideTask()
        suppressHideTask = false
        // 每次回到前台都补一次标记，防止 ROM 又把卡片放回去
        endExternalActivity()
        syncFromPrefs()
        refreshPermissions()
        refreshPermissionBanner()
        refreshLiveStatus()
        refreshDeviceNameList()
        refreshPreview()
        renderHistory()
        refreshServiceStatus()
        refreshAccessibilityState()
        // 服务启动是异步的：补拉后稍等再刷一次，避免仍显示"未启动"
        ui.postDelayed({ refreshServiceStatus() }, 600L)
    }

    override fun onStop() {
        super.onStop()
        // 真正切到后台（不是启动外部 Activity）时，安排隐藏
        scheduleHideTask()
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

        b.swLandBlur.isChecked = Prefs.landBlurEnabled
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
        b.swLandBlur.setOnCheckedChangeListener { _, v ->
            if (loadingUi) return@setOnCheckedChangeListener
            Prefs.landBlurEnabled = v
            refreshPreview()
        }

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

    /**
     * 查看崩溃日志。
     *
     * 连续多个版本反复闪退却定位不到原因，根源是此前没有任何崩溃记录、只能靠现象猜。
     * App 启动时已挂上全局未捕获异常处理器，崩溃堆栈写入 filesDir/crash/crash.log。
     * 这里读出来展示，支持复制与分享 —— 有了堆栈就能精确到行号，不再靠猜。
     */
    private fun showCrashLog() {
        val f = java.io.File(filesDir, "crash/crash.log")
        val text: String = if (f.exists()) {
            try {
                f.readText().takeLast(8000)
            } catch (t: Throwable) {
                "读取失败：" + t.message
            }
        } else {
            "暂无崩溃记录。\n\n如果刚闪退过却看不到记录，说明崩溃发生在极早期（进程启动阶段），" +
                "或者 App 是被系统直接杀掉而非抛异常。那种情况请改用系统的「设置 → 应用管理」查看。"
        }
        val tv = android.widget.TextView(this).apply {
            setText(text)
            setTextIsSelectable(true)
            textSize = 11f
            setPadding(40, 30, 40, 30)
        }
        val scroll = android.widget.ScrollView(this).apply { addView(tv) }
        android.app.AlertDialog.Builder(this)
            .setTitle("崩溃日志")
            .setView(scroll)
            .setNeutralButton("复制") { _, _ ->
                val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                        as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("crash", text))
                toast("已复制")
            }
            .setPositiveButton("分享") { _, _ ->
                try {
                    val uri = androidx.core.content.FileProvider.getUriForFile(
                        this, "$packageName.fileprovider", f
                    )
                    beginExternalActivity()
                    startActivity(
                        android.content.Intent.createChooser(
                            android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }, "导出崩溃日志"
                        )
                    )
                } catch (t: Throwable) {
                    toast("分享失败：" + t.message)
                }
            }
            .setNegativeButton("清空") { _, _ ->
                try { f.delete() } catch (_: Throwable) {}
                toast("已清空")
            }
            .setCancelable(true)
            .show()
    }

    private fun setupButtons() {

        b.btnPickImage.setOnClickListener { pickImage() }
        setupSound()
        b.swShowCharging.isChecked = Prefs.showCharging
        b.swShowCharging.setOnCheckedChangeListener { _, v ->
            if (loadingUi) return@setOnCheckedChangeListener
            Prefs.showCharging = v
            refreshPreview()
        }
        b.swPopupUnknown.isChecked = Prefs.popupUnknownDevices
        b.swPopupUnknown.setOnCheckedChangeListener { _, v ->
            if (loadingUi) return@setOnCheckedChangeListener
            Prefs.popupUnknownDevices = v
        }
        b.btnClearImage.setOnClickListener {
            Prefs.imageUri = null
            Prefs.imageRevision += 1
            previewStage.invalidate()
            refreshPreview()
            renderHistory()
        }
        b.btnClearHistory.setOnClickListener { clearHistory() }
        b.btnQuickPreview.setOnClickListener { showTestPopup() }
        b.btnLandscapePreview.setOnClickListener { toggleLandscapePreview() }
        b.btnAdbGrant.setOnClickListener { runOneKeyGrant() }
        b.btnCopyAdb.setOnClickListener { copyAdbScript() }
        b.btnCrashLog.setOnClickListener { showCrashLog() }
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
            beginExternalActivity()
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
                    //
                    // 关键发现：pm disable-user 对**系统应用**必然失败 ——
                    //   java.lang.SecurityException: Cannot disable system packages.
                    // com.xiaomi.bluetooth 是 /system_ext 下的系统包，这条路从一开始就堵死。
                    //
                    // 改用系统允许的四类手段依次尝试：
                    //  1) pm suspend           —— 挂起应用（系统包也允许），恢复用 pm unsuspend
                    //  2) pm uninstall -k --user 0 —— 对当前用户卸载（去更新 + 标记未安装），
                    //                                 系统包通常允许，恢复用 pm install-existing
                    //  3) appops deny          —— 禁「后台弹出界面」(MIUI op 10021) 与悬浮窗等
                    //  4) am force-stop        —— 仅立即止住当前这一轮，非持久
                    //
                    val cmds = listOf(
                        "pm suspend --user 0 com.xiaomi.bluetooth",
                        "pm uninstall -k --user 0 com.xiaomi.bluetooth",
                        "appops set com.xiaomi.bluetooth 10021 deny",
                        "cmd appops set com.xiaomi.bluetooth 10021 deny",
                        "appops set com.xiaomi.bluetooth SYSTEM_ALERT_WINDOW deny",
                        "appops set com.xiaomi.bluetooth START_FOREGROUND deny",
                        "appops set com.xiaomi.bluetooth RUN_IN_BACKGROUND deny",
                        "am force-stop com.xiaomi.bluetooth"
                    )
                    val results = AdbShell.execAll(cmds)
                    val okCount = results.count { it.ok }
                    // 回读真实状态：不看"命令有没有报错"，看"包现在到底处于什么状态"
                    val verifyCmds = listOf(
                        "dumpsys package com.xiaomi.bluetooth | grep -iE 'suspended|enabled=|firstInstallTime|flags=',",
                        "pm list packages -d com.xiaomi.bluetooth",
                        "pm list packages -u com.xiaomi.bluetooth",
                        "appops get com.xiaomi.bluetooth 10021"
                    )
                    val verifyResults = AdbShell.execAll(verifyCmds)
                    val verifyText = verifyCmds.mapIndexed { i, c ->
                        val r = verifyResults.getOrNull(i)
                        "【状态】$c\n" + (r?.let {
                            (it.out.trim().ifBlank { it.err.trim() }).ifBlank { "(空)" }
                        } ?: "(未执行)")
                    }.joinToString("\n\n")
                    runOnUiThread {
                        toast(
                            if (okCount > 0) "已执行 $okCount/${cmds.size} 条，见下方状态回读"
                            else "命令全部被拒（系统包限制），见下方状态回读"
                        )
                        showCommandResult(cmds, results, verifyText)
                    }
                }.start()
            }
            .setNeutralButton("复制命令") { _, _ -> copyDisableCommands() }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 汇总每条命令的退出码与输出，并附上状态回读，失败原因一目了然 */
    private fun showCommandResult(
        cmds: List<String>,
        results: List<com.woshiyigemanhuajia.btpopup.adb.AdbShell.Result>,
        verifyText: String = ""
    ) {
        val sb = StringBuilder()
        cmds.forEachIndexed { i, c ->
            val r = results.getOrNull(i)
            sb.append("\$ ").append(c).append('\n')
            if (r == null) {
                sb.append("  （未执行）\n\n")
                return@forEachIndexed
            }
            sb.append(if (r.ok) "  [成功]" else "  [失败]").append(" 退出码=").append(r.code).append('\n')
            val errTrim = r.err.trim()
            if (errTrim.isNotBlank()) {
                // 错误信息往往很长，只保留关键的第一行原因 + 首个调用点
                val first = errTrim.lines().firstOrNull()?.trim().orEmpty()
                sb.append("  原因: ").append(first).append('\n')
            }
            if (r.out.trim().isNotBlank()) sb.append("  输出: ").append(r.out.trim()).append('\n')
            sb.append('\n')
        }
        if (verifyText.isNotBlank()) {
            sb.append("──────── 执行后状态回读 ────────\n").append(verifyText).append('\n')
        }
        val text = sb.toString()
        val tv = android.widget.TextView(this).apply {
            setText(text)
            setTextIsSelectable(true)
            textSize = 11f
            setPadding(40, 30, 40, 30)
            setTypeface(android.graphics.Typeface.MONOSPACE)
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("执行结果")
            .setView(android.widget.ScrollView(this).apply { addView(tv) })
            .setPositiveButton("复制全部") { _, _ ->
                val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                        as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("cmds", text))
                toast("已复制")
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    /**
     * 直接把禁用命令复制到剪贴板。
     * 之前失败只弹一句 toast，用户根本没有可复制的地方 —— 这里单独给一个入口，
     * 复制后粘贴到任意 ADB / 终端工具即可手动执行。
     */
    private fun copyDisableCommands() {
        val script = listOf(
            "# —— 屏蔽小米快连弹窗（com.xiaomi.bluetooth 是系统包，pm disable-user 必失败，勿用）——",
            "pm suspend --user 0 com.xiaomi.bluetooth",
            "pm uninstall -k --user 0 com.xiaomi.bluetooth",
            "appops set com.xiaomi.bluetooth 10021 deny",
            "cmd appops set com.xiaomi.bluetooth 10021 deny",
            "appops set com.xiaomi.bluetooth SYSTEM_ALERT_WINDOW deny",
            "appops set com.xiaomi.bluetooth START_FOREGROUND deny",
            "appops set com.xiaomi.bluetooth RUN_IN_BACKGROUND deny",
            "",
            "# —— 恢复（按顺序执行）——",
            "pm unsuspend com.xiaomi.bluetooth",
            "pm install-existing --user 0 com.xiaomi.bluetooth",
            "appops reset com.xiaomi.bluetooth"
        ).joinToString("\n")
        val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("disable_cmds", script))
        toast("已复制禁用命令，粘贴到 ADB / 终端执行即可")
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
            beginExternalActivity()
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
            // 换图版本号 +1：即便极端情况下 URI 完全相同（例如回退到原始 content:// 且复用同名），
            // 也能强制加载侧丢弃旧图重新解码，杜绝"换了图还是旧图"。
            Prefs.imageRevision += 1
            previewStage.invalidate()
            refreshPreview()
            // 只有成功复制成本地文件的才进历史：content:// 会因授权失效而丢失，不适合入库
            if (stored != null) addToHistory(stored) else renderHistory()
        } else if (requestCode == REQ_PICK_SOUND && resultCode == RESULT_OK) {
            val uri: Uri = data?.data ?: return
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (ignored: Throwable) {
            }
            // 同样复制一份到私有目录，避免授权失效后提示音丢失
            val stored = copyMediaToLocal(uri)
            Prefs.soundUri = stored?.let { Uri.fromFile(it).toString() } ?: uri.toString()
            refreshSoundFile()
            toast("已选择提示音")
        }
    }

    /** 把用户选中的图片 / GIF 复制进应用私有目录（filesDir/popup_media/image.xxx） */
    private fun copyMediaToLocal(uri: Uri): java.io.File? {
        return try {
            val isGif = contentResolver.getType(uri) == "image/gif" ||
                (uri.lastPathSegment?.endsWith(".gif", ignoreCase = true) == true)
            val dir = java.io.File(filesDir, "popup_media").apply { mkdirs() }
            //
            // 【修复「换图片/GIF 后预览还固定显示旧图」】
            // 原实现永远写死成同一个文件名 image.gif / image.img，
            // 于是 Prefs.imageUri **每次换图都是完全相同的字符串**。
            // 加载侧有「URI 未变且已有图 → 直接复用、不重新 load」的判断，
            // 加上 Coil 的内存/磁盘缓存也以 URI 为 key，
            // 结果就是换了图也永远读到旧的那一帧 —— 换图完全不生效。
            //
            // 改成带时间戳的唯一文件名，每次换图 URI 必然不同，
            // 复用判断与 Coil 缓存都会自然失效，新图必定被重新加载。
            //
            val ext = if (isGif) "gif" else "img"
            val out = java.io.File(dir, "image_" + System.currentTimeMillis() + "." + ext)
            //
            // 注意：这里**不再**删除旧文件。
            // 加了「上传历史」后，旧图要能在历史里被切回来，文件必须留着。
            // 文件数量由历史上限统一管理（见 addToHistory），不会无限堆积。
            //
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

    // ------------------------------------------------------------------ 上传历史

    /** 历史里的一条记录：只记录**已复制到私有目录**的本地文件，content:// 不入库 */
    private data class MediaItem(
        val path: String,
        val name: String,
        val time: Long,
        val isGif: Boolean
    )

    /** 历史上限：超出后删掉最老的一条（连同文件一并删除，避免堆积） */
    private val HISTORY_LIMIT = 20

    private fun loadHistory(): MutableList<MediaItem> {
        return try {
            val arr = JSONArray(Prefs.mediaHistoryJson)
            val list = mutableListOf<MediaItem>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val p = o.optString("p", "")
                if (p.isBlank()) continue
                // 文件已经不在（被清理 / 手动删）的记录直接跳过，不再展示成空白格子
                if (!java.io.File(p).exists()) continue
                list += MediaItem(
                    path = p,
                    name = o.optString("n", "图片"),
                    time = o.optLong("t", 0L),
                    isGif = o.optBoolean("g", false)
                )
            }
            list
        } catch (t: Throwable) {
            Log.w("MainActivity", "读取上传历史失败: " + t.message)
            mutableListOf()
        }
    }

    private fun saveHistory(list: List<MediaItem>) {
        try {
            val arr = JSONArray()
            list.forEach { it2 ->
                arr.put(JSONObject().apply {
                    put("p", it2.path)
                    put("n", it2.name)
                    put("t", it2.time)
                    put("g", it2.isGif)
                })
            }
            Prefs.mediaHistoryJson = arr.toString()
        } catch (t: Throwable) {
            Log.w("MainActivity", "保存上传历史失败: " + t.message)
        }
    }

    /**
     * 把新上传的图加入历史。
     *
     * 同一张图重复选（内容相同）不做去重：文件名带时间戳，每次都是新记录，
     * 这样用户反复微调再上传时能保留每一步 —— 更符合"历史"的语义。
     */
    private fun addToHistory(file: java.io.File) {
        val list = loadHistory()
        list += MediaItem(
            path = file.absolutePath,
            name = file.name,
            time = System.currentTimeMillis(),
            isGif = file.name.endsWith(".gif", ignoreCase = true)
        )
        // 超出上限：删最老的，文件一并删掉
        while (list.size > HISTORY_LIMIT) {
            val oldest = list.removeAt(0)
            try {
                java.io.File(oldest.path).delete()
            } catch (t: Throwable) {
                Log.w("MainActivity", "删除超出上限的历史文件失败: " + t.message)
            }
        }
        saveHistory(list)
        renderHistory()
    }

    /** 渲染历史缩略图列表；当前正在用的那张加高亮边框 */
    private fun renderHistory() {
        val list = loadHistory()
        if (list.isEmpty()) {
            b.historySection.visibility = View.GONE
            b.historyList.removeAllViews()
            return
        }
        b.historySection.visibility = View.VISIBLE
        b.historyList.removeAllViews()

        val current = Prefs.imageUri
        val size = (64 * resources.displayMetrics.density).toInt()
        val gap = (8 * resources.displayMetrics.density).toInt()

        // 最新的排最前，符合直觉
        list.asReversed().forEach { item ->
            val wrap = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(size, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    marginEnd = gap
                }
            }
            val thumb = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(size, size)
                scaleType = ImageView.ScaleType.CENTER_CROP
                // 圆角 + 边框：当前使用的描主色边，其余描浅灰边
                val on = current == Uri.fromFile(java.io.File(item.path)).toString()
                val stroke = GradientDrawable().apply {
                    setColor(Color.WHITE)
                    cornerRadius = 10 * resources.displayMetrics.density
                    setStroke(
                        if (on) (2.5 * resources.displayMetrics.density).toInt() else 1,
                        if (on) ContextCompat.getColor(this@MainActivity, R.color.brand_teal)
                        else ContextCompat.getColor(this@MainActivity, R.color.divider)
                    )
                }
                background = stroke
                val pad = (2 * resources.displayMetrics.density).toInt()
                setPadding(pad, pad, pad, pad)
            }
            try {
                thumb.load(java.io.File(item.path))
            } catch (t: Throwable) {
                Log.w("MainActivity", "历史缩略图加载失败: " + t.message)
            }

            thumb.setOnClickListener { applyHistoryItem(item) }
            thumb.setOnLongClickListener {
                removeHistoryItem(item)
                true
            }
            wrap.addView(thumb)
            b.historyList.addView(wrap)
        }
    }

    /** 点历史项：切回这张图 */
    private fun applyHistoryItem(item: MediaItem) {
        Prefs.imageUri = Uri.fromFile(java.io.File(item.path)).toString()
        Prefs.imageRevision += 1
        previewStage.invalidate()
        refreshPreview()
        renderHistory()
        toast("已切换")
    }

    /** 长按历史项：删掉这一条（不删当前正在用的） */
    private fun removeHistoryItem(item: MediaItem) {
        val uri = Uri.fromFile(java.io.File(item.path)).toString()
        if (uri == Prefs.imageUri) {
            toast("这张正在使用，先换一张再删")
            return
        }
        val list = loadHistory().filter { it.path != item.path }
        try {
            java.io.File(item.path).delete()
        } catch (t: Throwable) {
            Log.w("MainActivity", "删除历史文件失败: " + t.message)
        }
        saveHistory(list)
        renderHistory()
        toast("已删除")
    }

    /** 清空历史：删掉所有历史文件与记录，当前正在用的那张保留 */
    private fun clearHistory() {
        val current = Prefs.imageUri
        val list = loadHistory()
        list.forEach { item ->
            if (Uri.fromFile(java.io.File(item.path)).toString() == current) return@forEach
            try {
                java.io.File(item.path).delete()
            } catch (t: Throwable) {
                Log.w("MainActivity", "清空历史删除文件失败: " + t.message)
            }
        }
        Prefs.mediaHistoryJson = "[]"
        renderHistory()
        toast("已清空上传历史")
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
            beginExternalActivity()
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
            beginExternalActivity()
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + packageName)
                )
            )
        } catch (t: Throwable) {
            try {
                beginExternalActivity()
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
                beginExternalActivity()
                startActivity(Intent().setComponent(cn))
                return
            } catch (t: Throwable) {
                // 继续尝试
            }
        }
        try {
            beginExternalActivity()
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

    /**
     * 确保监听服务在跑。
     *
     * 【修复「监听服务显示未启动」】
     * 之前只在 onCreate 里启动一次，onResume 只负责**显示**状态、不做任何补救。
     * 但服务是普通优先级进程组件，被系统回收后，
     * 后台路径（KeepAlive / GuardService）在 Android 8+ 的后台启动限制下往往拉不起来，
     * 于是用户切回 APP 就一直看到"未启动"，且永远不会自愈。
     *
     * 现在每次回到前台都补一次启动（幂等：已运行就不重复调），
     * 这样只要打开 APP，服务必然是活的。
     */
    private fun ensureMonitorService() {
        if (!Prefs.monitorEnabled) return
        if (BluetoothMonitorService.running) return
        val now = SystemClock.uptimeMillis()
        // 节流：避免服务刚被杀就疯狂重试
        if (now - lastStartAttemptAt < 2000L) return
        lastStartAttemptAt = now
        Log.i("MainActivity", "监听服务未运行，前台补拉")
        BluetoothMonitorService.start(this)
        if (Prefs.foregroundGuard) GuardService.start(this)
    }

    private fun refreshServiceStatus() {
        ensureMonitorService()
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

    /**
     * 构建「弹窗显示的设备名」列表。
     *
     * 数据来源：已配对 / 已连接的蓝牙设备（优先）+ 本 App 缓存里见过的设备。
     * 每一行显示当前生效的名字（自定义名优先于系统名），点击弹出输入框改名。
     */
    private fun refreshDeviceNameList() {
        val container = b.deviceNameList
        container.removeAllViews()

        data class Item(val address: String, val sysName: String)

        val items = LinkedHashMap<String, Item>()

        // 1) 已配对 / 已连接设备
        try {
            val bm = getSystemService(android.content.Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager
            val adapter = bm.adapter
            @Suppress("MissingPermission")
            adapter?.bondedDevices?.forEach { d ->
                val addr = d.address ?: return@forEach
                if (addr.isBlank()) return@forEach
                val sys = try {
                    d.name ?: ""
                } catch (t: Throwable) {
                    ""
                }
                items[addr] = Item(addr, sys.ifBlank { addr })
            }
        } catch (t: Throwable) {
            Log.w(TAG, "读取已配对设备失败: " + t.message)
        }

        // 2) 本 App 缓存里见过的设备（含自定义名已设置但当前未连接的）
        try {
            BatteryRepository.all().forEach { info ->
                if (info.address.isBlank()) return@forEach
                if (!items.containsKey(info.address)) {
                    items[info.address] = Item(info.address, info.name)
                }
            }
        } catch (ignored: Throwable) {
        }

        // 3) 只设过自定义名、其它来源都没有的设备
        Prefs.allCustomNames().forEach { (addr, _) ->
            if (!items.containsKey(addr)) items[addr] = Item(addr, addr)
        }

        if (items.isEmpty()) {
            val tv = android.widget.TextView(this).apply {
                text = "暂无设备记录；连接一次耳机后这里会出现"
                setTextColor(resources.getColor(R.color.text_secondary, theme))
                textSize = 12f
            }
            container.addView(tv)
            return
        }

        items.values.forEach { item ->
            val custom = Prefs.customName(item.address)
            val shown = custom ?: item.sysName

            val row = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                setPadding(0, 18, 0, 18)
                gravity = android.view.Gravity.CENTER_VERTICAL
            }

            val textCol = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                )
            }
            val nameView = android.widget.TextView(this).apply {
                text = shown
                setTextColor(resources.getColor(R.color.text_primary, theme))
                textSize = 13.5f
            }
            val subView = android.widget.TextView(this).apply {
                text = buildString {
                    append(item.address)
                    append("　·　")
                    append(if (custom != null) "已自定义" else "跟随系统：${item.sysName}")
                }
                setTextColor(resources.getColor(R.color.text_secondary, theme))
                textSize = 11f
            }
            textCol.addView(nameView)
            textCol.addView(subView)

            val btn = android.widget.TextView(this).apply {
                text = if (custom != null) "修改" else "改名"
                setTextColor(resources.getColor(R.color.brand_teal_dark, theme))
                textSize = 12.5f
                setPadding(24, 12, 16, 12)
                setOnClickListener { showRenameDialog(item.address, item.sysName, custom) }
            }

            row.addView(textCol)
            row.addView(btn)
            container.addView(row)
        }
    }

    private fun showRenameDialog(address: String, sysName: String, current: String?) {
        val input = android.widget.EditText(this).apply {
            setText(current ?: sysName)
            setSingleLine(true)
            hint = "留空则恢复为系统名"
            setSelection(text.length)
            setPadding(48, 24, 48, 12)
        }
        val box = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(24, 12, 24, 0)
            addView(input)
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("设备显示名")
            .setMessage("地址：$address")
            .setView(box)
            .setPositiveButton("保存") { _, _ ->
                val v = input.text.toString().trim()
                Prefs.setCustomName(address, v.ifBlank { null })
                // 立刻把新名字写进缓存并刷新弹窗（若正在显示）
                try {
                    BatteryRepository.update(address, v.ifBlank { sysName }) { it.copy(name = v.ifBlank { sysName }) }
                } catch (ignored: Throwable) {
                }
                refreshDeviceNameList()
                refreshPreview()
                toast(if (v.isBlank()) "已恢复为系统名" else "已保存：$v")
            }
            .setNeutralButton("清除") { _, _ ->
                Prefs.setCustomName(address, null)
                try {
                    BatteryRepository.update(address, sysName) { it.copy(name = sysName) }
                } catch (ignored: Throwable) {
                }
                refreshDeviceNameList()
                refreshPreview()
                toast("已恢复为系统名")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ------------------------------------------------------------------ 弹窗提示音

    private fun setupSound() {
        b.swSound.isChecked = Prefs.soundEnabled

        b.swSound.setOnCheckedChangeListener { _, v ->
            if (loadingUi) return@setOnCheckedChangeListener
            Prefs.soundEnabled = v
            if (!v) PopupSound.stop()
            refreshSoundFile()
        }

        b.btnPickSound.setOnClickListener {
            if (!Prefs.soundEnabled) {
                Prefs.soundEnabled = true
                loadingUi = true
                b.swSound.isChecked = true
                loadingUi = false
            }
            pickSound()
        }

        b.btnTestSound.setOnClickListener {
            if (Prefs.soundUri.isNullOrBlank()) {
                toast("请先选择音频文件")
                return@setOnClickListener
            }
            PopupSound.play(this)
            toast("试听中…")
        }

        b.sbSoundDelay.progress = Prefs.soundDelayMs.coerceIn(0, b.sbSoundDelay.max)
        b.tvSoundDelay.text = "${Prefs.soundDelayMs} ms"
        b.sbSoundDelay.setOnSeekBarChangeListener(simpleSeek { v ->
            Prefs.soundDelayMs = v
            b.tvSoundDelay.text = "$v ms"
        })

        b.sbSoundDuration.progress = Prefs.soundDurationMs.coerceIn(0, b.sbSoundDuration.max)
        b.tvSoundDuration.text =
            if (Prefs.soundDurationMs <= 0) "0 ms（播完整段）" else "${Prefs.soundDurationMs} ms"
        b.sbSoundDuration.setOnSeekBarChangeListener(simpleSeek { v ->
            Prefs.soundDurationMs = v
            b.tvSoundDuration.text = if (v <= 0) "0 ms（播完整段）" else "$v ms"
        })

        b.sbSoundVolume.progress = Prefs.soundVolume.coerceIn(0, 100)
        b.tvSoundVolume.text = "${Prefs.soundVolume}%"
        b.sbSoundVolume.setOnSeekBarChangeListener(simpleSeek { v ->
            Prefs.soundVolume = v
            b.tvSoundVolume.text = "$v%"
        })

        refreshSoundFile()
    }

    private fun refreshSoundFile() {
        val uri = Prefs.soundUri
        b.tvSoundFile.text = if (uri.isNullOrBlank()) "未选择音频" else "已选择：$uri"
    }

    private fun simpleSeek(onChange: (Int) -> Unit) = object : android.widget.SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(sb: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
            if (loadingUi || !fromUser) return
            onChange(progress)
        }
        override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
        override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {}
    }

    /** 选择提示音文件 */
    private fun pickSound() {
        try {
            val intent = android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(android.content.Intent.CATEGORY_OPENABLE)
                type = "audio/*"
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(android.content.Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            }
            beginExternalActivity()
            startActivityForResult(intent, REQ_PICK_SOUND)
        } catch (t: Throwable) {
            toast("无法打开文件选择器")
        }
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
