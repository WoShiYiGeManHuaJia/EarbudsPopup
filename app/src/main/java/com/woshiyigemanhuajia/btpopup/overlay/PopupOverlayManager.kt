package com.woshiyigemanhuajia.btpopup.overlay

import android.content.Context
import android.content.res.Configuration
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import coil.load
import coil.size.Size
import com.woshiyigemanhuajia.btpopup.R
import com.woshiyigemanhuajia.btpopup.battery.BatteryInfo
import com.woshiyigemanhuajia.btpopup.util.Prefs
import com.woshiyigemanhuajia.btpopup.widget.LiveBlurView
import com.woshiyigemanhuajia.btpopup.widget.RoundedCardLayout
import com.woshiyigemanhuajia.btpopup.widget.RoundedImageView
import java.util.WeakHashMap
import kotlin.math.min

/**
 * 系统级悬浮弹窗（TYPE_APPLICATION_OVERLAY）。
 * 支持：圆角、自定义图片/GIF、尺寸与位置调节、出入场动画、横竖屏两套布局。
 */
object PopupOverlayManager {

    private const val TAG = "PopupOverlay"

    /** 与 BluetoothPopupTrigger.FALLBACK_ADDRESS 一致：读不到蓝牙地址时的占位 key */
    private const val FALLBACK_ADDRESS = "00:00:00:00:00:00"

    /** 横屏卡片扁平比例：自动高度 = 卡片宽度 × 该比例，保证横屏永远是"扁长"形态 */
    private const val LAND_FLAT_RATIO = 0.46f

    /** 竖屏图片区基准高度（dp），与 popup_overlay_portrait.xml 里的默认值保持一致 */
    private const val IMAGE_BASE_DP = 176f

    /**
     * 系统「跨窗口模糊」是否可用（Android 12+）：可用时文字区的毛玻璃由系统实时合成，
     * 模糊的是**弹窗背后的手机页面**，而不是弹窗内的图片 / GIF。
     */
    private fun crossWindowBlurAvailable(context: Context): Boolean {
        if (android.os.Build.VERSION.SDK_INT < 31) return false
        return try {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            wm.isCrossWindowBlurEnabled
        } catch (t: Throwable) {
            false
        }
    }

    /**
     * 横屏整体等比缩放系数（100% = 原始大小）。
     * 宽度、高度、字号、内边距、间距、图标一把梭按同一系数缩放，形态不变、只是整体变小。
     */
    fun landScale(): Float = Prefs.landScalePercent.coerceIn(30, 150) / 100f

    /**
     * 竖屏整体等比缩放系数（100% = 原始大小）。
     *
     * 竖屏「高度」滑块的真实语义就是它：字号、内边距、控件间距、图片区高度
     * 全部按同一系数同步放大 / 缩小，卡片高度随内容自然变化。
     * 因此调大就是**整体等比放大**，而不是把文字下方的空白拉长。
     */
    fun portraitScale(): Float = Prefs.portraitScalePercent.coerceIn(50, 200) / 100f

    /**
     * 等比缩放所需的"原始尺寸"缓存：第一次处理某个 View 时记下它的初始
     * 字号 / padding / 尺寸 / 间距，之后每次缩放都从这份基准重算，
     * 避免多次拖动滑块后尺寸被反复累乘（导致越缩越小或回不去）。
     */
    private val scaleBase = WeakHashMap<View, FloatArray>()

    private val main = Handler(Looper.getMainLooper())

    private var rootView: View? = null
    private var rootWindowManager: WindowManager? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var dismissTask: Runnable? = null
    private var currentAddress: String? = null
    private var lastShowAt = 0L

    /** 外观参数签名：参数一旦变化即重建弹窗，保证改完参数预览立刻生效 */
    private var lastSignature: String? = null

    fun isShowing(): Boolean = rootView != null

    fun canDrawOverlay(context: Context): Boolean = Settings.canDrawOverlays(context)

    /**
     * 显示弹窗。
     *
     * 只依赖悬浮窗权限，**不依赖任何前台服务**，因此进程不在前台、由蓝牙广播兜底拉起时
     * 也能直接弹出（这正是"后台服务拉不起来 → 不弹窗"的破解点）。
     *
     * @return true 表示已提交显示；false 表示被拒，具体原因（未授权 / 已授权但添加失败）写入日志。
     */
    fun show(context: Context, info: BatteryInfo, imageUri: String?): Boolean {
        if (!canDrawOverlay(context)) {
            Log.e(TAG, "弹窗失败原因=未授权：缺少悬浮窗权限（SYSTEM_ALERT_WINDOW），请到首页点「一键授权」补齐")
            return false
        }
        main.post { showInternal(context.applicationContext, info, imageUri) }
        return true
    }

    fun update(info: BatteryInfo) {
        main.post {
            val v = rootView ?: return@post
            val cur = currentAddress
            if (cur != null && !cur.equals(info.address, ignoreCase = true)) {
                // 弹窗是用「读不到地址时的占位 key」弹出的（缺 BLUETOOTH_CONNECT 的兜底场景）：
                // 后续带真实地址的电量更新不能被地址匹配挡掉，直接收养这个真实地址
                if (cur == FALLBACK_ADDRESS) {
                    currentAddress = info.address
                } else {
                    return@post
                }
            }
            bindData(v, info)
        }
    }

    fun dismiss() {
        main.post { removeInternal(true) }
    }

    // ------------------------------------------------------------------ 预览（设置页舞台复用同一套布局与样式）

    /** 横屏卡片高度：固定 dp 优先，否则按"宽度 × 扁平比例"计算，保证横屏永远是扁长形态 */
    fun landscapeAutoHeightPx(context: Context, widthPx: Int): Int {
        val density = context.resources.displayMetrics.density
        val s = landScale()
        val minH = (96 * density * s).toInt()
        if (Prefs.landHeightFixed) {
            return (Prefs.landHeightDp.coerceIn(60, 900) * density * s).toInt().coerceAtLeast(minH)
        }
        return (widthPx * LAND_FLAT_RATIO).toInt().coerceAtLeast(minH)
    }

    /** 用与真实悬浮窗完全相同的布局 / 数据绑定 / 样式逻辑创建预览视图 */
    fun createPreviewView(
        context: Context,
        landscape: Boolean,
        info: BatteryInfo,
        imageUri: String?
    ): View {
        val layoutId = if (landscape) R.layout.popup_overlay_landscape else R.layout.popup_overlay_portrait
        val view = LayoutInflater.from(context).inflate(layoutId, null)
        bindData(view, info)
        applyImage(context, view, imageUri)
        applyPanelStyle(view, landscape)
        return view
    }

    /** 外观参数变化后只重套样式（圆角 / 透明度 / 配色 / 尺寸），同一张图片不重复解码，GIF 不会被打断重播 */
    fun restylePreviewView(context: Context, view: View, landscape: Boolean) {
        // 先确保图片就位（同一 URI 不会重复解码），再套样式，
        // 这样竖屏模糊层 setSource 绑定的必然是当前这张图 / GIF 的 Drawable
        applyImage(context, view, Prefs.imageUri)
        applyPanelStyle(view, landscape)
    }

    // ------------------------------------------------------------------

    private fun showInternal(context: Context, info: BatteryInfo, imageUri: String?) {
        val now = System.currentTimeMillis()
        val landscape = context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val signature = buildSignature(info, imageUri, landscape)

        // 外观参数完全一致时才复用现有弹窗，否则重建，保证改完参数预览立即生效
        if (rootView != null && signature == lastSignature) {
            bindData(rootView!!, info)
            restartDismissTimer()
            return
        }
        // 【闪两下修复】弹窗已在屏上且距上次显示不足 500ms：
        // 此时签名不同往往只是"电量刚刚送到"（签名里含电量数值），
        // 重建会把窗口拆掉再装一遍、入场动画再播一次 —— 用户看到的就是闪两下。
        // 这种情况只刷新数据，不重建、不重播动画。
        if (rootView != null && now - lastShowAt < 500) {
            lastSignature = signature
            bindData(rootView!!, info)
            restartDismissTimer()
            return
        }
        if (rootView == null && now - lastShowAt < 600) {
            // 极短时间内重复事件，忽略
            return
        }

        removeInternal(false)

        val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        val layoutId = if (landscape) R.layout.popup_overlay_landscape else R.layout.popup_overlay_portrait
        val view = LayoutInflater.from(context).inflate(layoutId, null)

        val metrics = context.resources.displayMetrics
        val density = metrics.density
        val screenW = metrics.widthPixels
        val screenH = metrics.heightPixels

        val marginPx = if (landscape) (Prefs.landMarginDp * density).toInt() else (8 * density).toInt()
        val ratio = if (landscape) Prefs.landWidthPercent else Prefs.widthPercent
        // 横屏整体等比缩小：宽度、最小宽度、高度、内部字号与间距共用同一系数
        val scale = if (landscape) landScale() else 1f
        val minW = (200 * density * scale).toInt().coerceAtMost(screenW)
        val maxW = (screenW - marginPx * 2).coerceAtLeast(minW)
        val widthPx = (screenW * ratio / 100f * scale).toInt().coerceIn(minW, maxW)

        val minH = (96 * density * scale).toInt().coerceAtMost(screenH)
        // 横屏卡片高度永远是确定的（固定 dp 或 宽度 × 扁平比例）；
        // 竖屏高度完全由内容决定 —— 即「整体等比缩放后的自然高度」。
        // 旧逻辑在竖屏按固定 dp 精确测量，多出来的高度只能变成文字下方的空白，
        // 这正是「调高度只是把空白拉长」的根因，已废弃。
        val finalH = if (landscape) {
            landscapeAutoHeightPx(context, widthPx).coerceIn(minH, screenH)
        } else {
            0
        }

        // 先套用最终样式（含图片区尺寸与整体比例），再测量，避免"拉宽/拉高后出现空白"
        bindData(view, info)
        applyImage(context, view, imageUri)
        applyPanelStyle(view, landscape)

        val widthSpec = View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY)
        val heightSpec = if (finalH > 0) {
            View.MeasureSpec.makeMeasureSpec(finalH, View.MeasureSpec.EXACTLY)
        } else {
            View.MeasureSpec.makeMeasureSpec(screenH, View.MeasureSpec.AT_MOST)
        }
        view.measure(widthSpec, heightSpec)

        // 竖屏用内容自然高度（WRAP_CONTENT 窗口的真实高度），横向位置映射才不会算偏
        val contentH = if (finalH > 0) finalH else view.measuredHeight.coerceIn(1, screenH)

        // 位置映射：把进度条 0-100 映射到「可摆放的空白区间」——与设置页预览舞台完全同一套公式，
        // 因此拖动滑块全程都真实生效，不会出现"拖到一半就卡住不动"
        val freeX = (screenW - widthPx - marginPx * 2).coerceAtLeast(0)
        val freeY = (screenH - contentH - marginPx * 2).coerceAtLeast(0)
        val x = marginPx + (freeX * Prefs.posXPercent.coerceIn(0, 100) / 100f).toInt()
        val y = marginPx + (freeY * Prefs.posYPercent.coerceIn(0, 100) / 100f).toInt()
        Log.i(
            TAG,
            "弹窗定位 x=" + x + " y=" + y + " 尺寸 " + widthPx + "x" + contentH +
                " 横屏=" + landscape + " 进度条 posX=" + Prefs.posXPercent + " posY=" + Prefs.posYPercent
        )

        val type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED

        //
        // 【毛玻璃正解】模糊「弹窗背后那一片被盖住的手机界面」。
        //
        // 用户要的正是这个：弹窗盖住了手机界面上的某个字，
        // 弹窗上这块空白就显示"那个字被模糊后的样子"。
        // 采样对象是背后的屏幕内容，跟用户上传的图片 / GIF 毫无关系 ——
        // 之前拿弹窗自己的图当模糊源，方向从一开始就错了。
        //
        // FLAG_BLUR_BEHIND 由系统合成器实时采样：
        //  - 背后内容在动（GIF / 视频 / 滚动），模糊也跟着动 —— 横屏"固定一帧"根治；
        //  - 不需要 App 解码位图、不跑 stackBlur —— 卡顿与 OOM 闪退的源头一并消失。
        //
        val radiusDp = if (landscape) Prefs.landBlurRadiusDp else Prefs.portraitBlurRadiusDp
        // 只有「模糊屏幕内容」模式才挂跨窗口模糊；走「模糊上传图片」时必须关掉，
        // 否则两层模糊叠在一起，反而看不清
        val crossBlur = Prefs.windowBlurEnabled && !Prefs.blurFromImage && radiusDp > 0
        if (crossBlur) {
            flags = flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
        }

        val lp = WindowManager.LayoutParams(
            widthPx,
            if (finalH > 0) finalH else WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            flags,
            PixelFormat.TRANSLUCENT
        )
        if (crossBlur) {
            try {
                lp.blurBehindRadius = (radiusDp.coerceIn(0, 60) * density).toInt()
            } catch (t: Throwable) {
                // 设备不支持时只是没有模糊，绝不能影响弹窗本身
                Log.w(TAG, "设置毛玻璃半径失败（设备可能不支持，仅影响模糊）: " + t.message)
            }
        }
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = x
        lp.y = y

        view.setOnClickListener { dismiss() }

        //
        // 修复「弹窗出来会闪一下」：
        // 原来是 addView() 之后才 playEnter()，而 playEnter 第一步才是 alpha=0。
        // 窗口被加入的那一刻卡片还是完全不透明、且已在最终位置，
        // 系统完全可能在 playEnter 生效前就渲染出一帧完整卡片 —— 就是那一下闪。
        // 正确做法：addView 之前先把卡片设成动画起始状态，让首帧就是动画首帧。
        primeEnterState(view)

        try {
            wm.addView(view, lp)
        } catch (t: Throwable) {
            Log.e(TAG, "addView 失败: " + t.message)
            return
        }

        rootView = view
        rootWindowManager = wm
        layoutParams = lp
        currentAddress = info.address
        lastShowAt = now
        lastSignature = signature

        playEnter(view)
        restartDismissTimer()
    }

    /** 外观参数签名：任一参数变化都触发重建，避免"改了参数但预览没变化" */
    private fun buildSignature(info: BatteryInfo, imageUri: String?, landscape: Boolean): String {
        return listOf(
            info.address, imageUri ?: "", landscape.toString(),
            Prefs.widthPercent, Prefs.portraitScalePercent,
            Prefs.landWidthPercent, Prefs.landHeightFixed, Prefs.landHeightDp, Prefs.landMarginDp,
            Prefs.landScalePercent, Prefs.landBlurRadiusDp, Prefs.landBlurDimPercent, Prefs.landBlurFadeDp,
            Prefs.posXPercent, Prefs.posYPercent, Prefs.cornerRadiusDp,
            Prefs.panelAlpha, Prefs.panelColor, Prefs.textColor, Prefs.accentColor,
            Prefs.imageHeightDp, Prefs.imageScaleMode, Prefs.animType, Prefs.animDuration
        ).joinToString("|")
    }

    private fun removeInternal(animate: Boolean) {
        val v = rootView ?: return
        val wm = rootWindowManager
        cancelDismissTimer()
        rootView = null
        rootWindowManager = null
        layoutParams = null
        currentAddress = null
        lastSignature = null

        if (!animate) {
            forceRemove(v, wm)
            return
        }

        var finished = false
        val finish = Runnable {
            if (finished) return@Runnable
            finished = true
            forceRemove(v, wm)
        }
        val duration = (Prefs.animDuration.toLong().coerceIn(80L, 1500L)) * 3 / 4
        try {
            v.animate().alpha(0f).scaleX(0.94f).scaleY(0.94f).setDuration(duration)
                .setInterpolator(DecelerateInterpolator())
                .withEndAction { main.post(finish) }
                .start()
        } catch (t: Throwable) {
            Log.w(TAG, "退场动画失败: " + t.message)
            finish.run()
            return
        }
        // 兜底：动画回调一旦失效（被取消 / 窗口状态异常），超时后也强制摘除窗口，
        // 避免残留一层无法点击的透明层，导致必须去关悬浮窗权限才能恢复
        main.postDelayed(finish, duration + 350L)
    }

    /** 幂等地把窗口从 WindowManager 上摘除，任何情况下都保证不留残影 */
    private fun forceRemove(view: View, wm: WindowManager?) {
        try {
            view.animate().cancel()
        } catch (t: Throwable) {
            Log.w(TAG, "取消动画失败: " + t.message)
        }
        try {
            val manager = wm ?: (view.context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)
            if (manager != null && (view.parent != null || view.isAttachedToWindow)) {
                manager.removeViewImmediate(view)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "removeView 失败: " + t.message)
        }
    }

    private fun restartDismissTimer() {
        cancelDismissTimer()
        val delay = Prefs.dismissDelayMs
        if (delay <= 0) return
        val task = Runnable { removeInternal(true) }
        dismissTask = task
        main.postDelayed(task, delay.toLong().coerceIn(1000L, 120_000L))
    }

    private fun cancelDismissTimer() {
        dismissTask?.let { main.removeCallbacks(it) }
        dismissTask = null
    }

    // ------------------------------------------------------------------ 数据绑定

    private fun bindData(view: View, info: BatteryInfo) {
        view.findViewById<TextView>(R.id.tvDeviceName)?.text = info.name.ifBlank { BatteryInfo.UNKNOWN_NAME }
        view.findViewById<TextView>(R.id.tvConnState)?.text = buildStateText(info)

        // 只展示有意义的来源缩写；拿不到明确来源时整块隐藏，不显示 "SYS" 占位标签
        val tag = view.findViewById<TextView>(R.id.tvSourceTag)
        val sourceTag = when {
            info.source.contains("GATT") -> "GATT"
            info.source.contains("HFP") -> "HFP"
            else -> ""
        }
        if (tag != null) {
            if (sourceTag.isEmpty()) {
                tag.visibility = View.GONE
            } else {
                tag.visibility = View.VISIBLE
                tag.text = sourceTag
            }
        }

        bindCell(view, "Left", info.left)
        bindCell(view, "Right", info.right)
        bindCell(view, "Case", info.case)
        // 横屏弹窗没有三栏大框，电量走这一行小字（竖屏布局里没有该控件，findViewById 返回 null，安全跳过）
        view.findViewById<TextView>(R.id.tvBatteryLine)?.text = buildBatteryLine(info)
    }

    /** 横屏电量行：左耳 / 右耳 / 充电仓，一行小字直接显示 */
    private fun buildBatteryLine(info: BatteryInfo): String {
        fun cell(label: String, value: Int) = label + " " + if (value in 0..100) "$value%" else "--%"
        // 读不到分体电量（非 TWS / 只上报整体）时退化成整体电量，避免整行都是 --
        if (!info.hasSplit && info.overall in 0..100) return "整体电量 ${info.overall}%"
        return listOf(
            cell("左耳", info.left),
            cell("右耳", info.right),
            cell("充电仓", info.case)
        ).joinToString("  ·  ")
    }

    private fun bindCell(view: View, prefix: String, value: Int) {
        val labelId = view.resources.getIdentifier("tv" + prefix + "Label", "id", view.context.packageName)
        val percentId = view.resources.getIdentifier("tv" + prefix + "Percent", "id", view.context.packageName)
        val barId = view.resources.getIdentifier("pb" + prefix, "id", view.context.packageName)

        val percent = view.findViewById<TextView>(percentId)
        val bar = view.findViewById<ProgressBar>(barId)
        if (value in 0..100) {
            percent?.text = "$value%"
            bar?.progress = value
        } else {
            percent?.text = "--%"
            bar?.progress = 0
        }
        view.findViewById<TextView>(labelId)?.let { /* label 文案由布局固定 */ }
    }

    private fun buildStateText(info: BatteryInfo): String {
        val parts = mutableListOf<String>()
        parts += if (info.charging) "已连接 · 充电中" else "已连接"
        if (!info.hasSplit && info.overall >= 0) {
            parts += "整体电量 " + info.overall + "%"
        }
        if (!info.hasAny) {
            parts += "电量读取中…"
        }
        return parts.joinToString(" · ")
    }

    private fun applyImage(context: Context, view: View, imageUri: String?) {
        val image = view.findViewById<ImageView>(R.id.popupImage) ?: return
        val hint = view.findViewById<View>(R.id.noImageHint)

        val density = context.resources.displayMetrics.density
        // 横屏（图片铺满卡片）用整卡圆角；竖屏图片在面板内留了内边距，圆角收一点更自然
        val onCard = view.findViewById<View>(R.id.card) != null
        val radiusPx = Prefs.cornerRadiusDp.coerceIn(0, 200) * density * (if (onCard) 1f else 0.72f)

        if (image is RoundedImageView) {
            // 圆角裁剪策略由 RoundedImageView 自己按"源是不是动画"决定：
            // 静态图走离屏 DST_IN 遮罩（抗锯齿最好），GIF 走 GPU outline 裁剪（不每帧开离屏图层）。
            // 这里绝不能再去清空 outlineProvider / clipToOutline —— 那会把动画的圆角裁剪关掉，
            // GIF 又回到"每帧一个离屏图层"的老路，正是播放卡顿的来源之一。
            image.setRadius(radiusPx)
        } else {
            image.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, outline: Outline) {
                    outline.setRoundRect(0, 0, v.width, v.height, radiusPx)
                }
            }
            image.clipToOutline = true
        }
        // 图片 / GIF 缩放模式可调，解决"只能调一点点大小"的观感问题
        image.scaleType = when (Prefs.imageScaleMode) {
            "fit" -> ImageView.ScaleType.FIT_CENTER
            "stretch" -> ImageView.ScaleType.FIT_XY
            "center" -> ImageView.ScaleType.CENTER
            else -> ImageView.ScaleType.CENTER_CROP
        }

        if (imageUri.isNullOrBlank()) {
            hint?.visibility = View.VISIBLE
            image.tag = null
            image.setImageDrawable(null)
            // 没有图 / GIF：横屏模糊层保持透明不绘制，绝不能残留一块压暗黑块
            view.findViewById<LiveBlurView>(R.id.detailBlurBg)?.setSourceBitmap(null)
            return
        }
        hint?.visibility = View.GONE
        // 横屏详情区模糊层的源位图：与 Coil 的加载 / 缓存 / GIF 播放完全解耦，
        // 独立解码一份小图交付给模糊层（见 prefetchBlurSource），
        // 这样"开模糊"绝不会再影响图片 / GIF 自身的显示。
        prefetchBlurSource(context, view, imageUri)
        // 同一张图不重复发起请求：拖动尺寸 / 颜色滑块会高频重套样式，
        // 每帧都 load 会让旧请求（尤其 GIF 解码）被反复取消，表现为图片久不显示。
        // 但"已经显示出来了"才算数——上次请求没结果时绝不能吞掉这次机会。
        if (image.tag == imageUri && image.drawable != null) {
            // 图片已就绪：拖动「图片缩放 / 位移 / 旋转」滑块时走这里，立即套用新变换
            applyImageTransform(image)
            return
        }
        image.tag = imageUri
        // 看门狗：请求若长时间既没成功也没失败（视图未 attach、URI 权限失效且无回调等），
        // 主动把占位提示重新亮出来，绝不让用户面对一块没有任何说明的空白。
        imageLoadGuard[image]?.let { image.removeCallbacks(it) }
        val guard = Runnable {
            if (image.drawable == null && image.tag == imageUri) {
                Log.w(TAG, "图片超时未就绪，回退占位提示：" + imageUri)
                hint?.visibility = View.VISIBLE
            }
        }
        imageLoadGuard[image] = guard
        image.postDelayed(guard, IMAGE_LOAD_TIMEOUT_MS)
        try {
            val uri = android.net.Uri.parse(imageUri)
            val animated = isAnimatedSource(context, uri)
            image.load(uri) {
                // 淡入只在静态图上保留：GIF 场景下淡入会让窗口重建时多一次闪烁
                crossfade(!animated)
                // GIF 逐帧都是"全尺寸位图"，尺寸越大每帧缩放 / 合成的成本越高。
                // 这里把 GIF 的解码尺寸**固定**成 640×640（不随卡片宽高变化）：
                //  1) 每帧合成成本大幅下降，播放不再卡；
                //  2) Coil 的内存缓存 key 里带解码尺寸，尺寸固定后，调宽度 / 调高度
                //     触发窗口重建时能直接命中缓存秒显，不会再出现"一调宽度 GIF 直接没了"。
                if (animated) {
                    size(Size(640, 640))
                    // 硬件位图不允许被软件离屏绘制复用；动画源统一走软件位图最稳
                    allowHardware(false)
                } else {
                    // 静态图同样固定解码尺寸：调宽度 / 调高度会重建窗口，尺寸固定后 Coil
                    // 内存缓存能直接命中并瞬间回填，不会再有"一调宽度自定义图片直接没了"的空白期。
                    // 1080 已覆盖绝大多数机型的卡片宽度，清晰度不受影响。
                    size(Size(1080, 1080))
                }
                listener(
                    onSuccess = { _, _ ->
                        imageLoadGuard[image]?.let { image.removeCallbacks(it) }
                        hint?.visibility = View.GONE
                        // 图片到位后再套变换：此时 intrinsic 尺寸与 View 尺寸才都已知
                        applyImageTransform(image)
                    },
                    onError = { _, _ ->
                        imageLoadGuard[image]?.let { image.removeCallbacks(it) }
                        Log.w(TAG, "图片加载失败：" + imageUri)
                        hint?.visibility = View.VISIBLE
                    }
                )
            }
        } catch (t: Throwable) {
            Log.w(TAG, "图片加载失败: " + t.message)
            imageLoadGuard[image]?.let { image.removeCallbacks(it) }
            image.tag = null
            hint?.visibility = View.VISIBLE
        }
    }

    /**
     * 套用用户对图片 / GIF 的自由变换：缩放倍数 + 水平/垂直位移 + 旋转角度。
     *
     * 修复「上传的图片跟 GIF 不能自己调整位置跟大小」：
     * 之前只有 4 种固定 scaleMode，上传完想让主体露多一点、想左右挪一点都做不到。
     *
     * 实现：切到 MATRIX 模式，在当前 scaleMode 算出的基准尺寸之上再叠加变换。
     * 变换全为默认值（100% / 0 / 0 / 0°）时不动，保持原有 scaleType 行为。
     */
    private fun applyImageTransform(image: ImageView) {
        val pct = Prefs.imageScalePercent
        val dxDp = Prefs.imageOffsetXDp
        val dyDp = Prefs.imageOffsetYDp
        val rot = Prefs.imageRotationDeg
        if (pct == 100 && dxDp == 0 && dyDp == 0 && rot == 0) return

        val dr = image.drawable ?: return
        val vw = image.width
        val vh = image.height
        if (vw <= 0 || vh <= 0) {
            // 还没测量完，等一帧再来（最多重试几次，避免死循环）
            val tries = (image.getTag(R.id.tag_transform_retry) as? Int) ?: 0
            if (tries >= 6) return
            image.setTag(R.id.tag_transform_retry, tries + 1)
            image.post { applyImageTransform(image) }
            return
        }
        image.setTag(R.id.tag_transform_retry, 0)

        val dw = dr.intrinsicWidth
        val dh = dr.intrinsicHeight
        if (dw <= 0 || dh <= 0) return

        val density = image.resources.displayMetrics.density
        val k = pct / 100f
        val m = android.graphics.Matrix()

        when (Prefs.imageScaleMode) {
            "stretch" -> m.postScale((vw.toFloat() / dw) * k, (vh.toFloat() / dh) * k)
            "center" -> m.postScale(k, k)
            "fit" -> {
                val base = minOf(vw.toFloat() / dw, vh.toFloat() / dh)
                m.postScale(base * k, base * k)
            }
            else -> {
                val base = maxOf(vw.toFloat() / dw, vh.toFloat() / dh)
                m.postScale(base * k, base * k)
            }
        }

        // 旋转围绕图片中心
        if (rot != 0) m.postRotate(rot.toFloat(), dw / 2f, dh / 2f)

        // 先居中，再叠加用户位移
        val m2 = android.graphics.Matrix(m)
        val rect = android.graphics.RectF(0f, 0f, dw.toFloat(), dh.toFloat())
        m2.mapRect(rect)
        val tx = (vw - rect.width()) / 2f - rect.left + dxDp * density
        val ty = (vh - rect.height()) / 2f - rect.top + dyDp * density
        m.postTranslate(tx, ty)

        image.scaleType = ImageView.ScaleType.MATRIX
        image.imageMatrix = m
    }

    /**
     * URI 指向的是不是 GIF 动图：优先看 MIME（部分 provider 返回 null，就退回看扩展名）。
     * 只有动图才需要限制解码尺寸，静态图完全不动，避免影响清晰度。
     */
    private fun isAnimatedSource(context: Context, uri: android.net.Uri): Boolean {
        val byName = uri.lastPathSegment?.endsWith(".gif", ignoreCase = true) == true
        val type = try {
            context.contentResolver.getType(uri)
        } catch (t: Throwable) {
            null
        }
        // MIME 最可信；部分选择器 / 第三方文件源会给出空 MIME 或错误的 image/*，
        // 这时再按扩展名与文件头兜底，避免 GIF 被误判成静态图（走静态分支 → 不播动画）。
        if (!type.isNullOrBlank() && type.startsWith("image/")) {
            return type.equals("image/gif", ignoreCase = true)
        }
        if (byName) return true
        return hasGifHeader(context, uri)
    }

    /** 读文件头 6 字节判断 GIF 魔数（GIF87a / GIF89a），兜底 MIME 为空的来源 */
    private fun hasGifHeader(context: Context, uri: android.net.Uri): Boolean = try {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val head = ByteArray(6)
            val n = input.read(head)
            n >= 6 && head[0] == 'G'.code.toByte() && head[1] == 'I'.code.toByte() &&
                head[2] == 'F'.code.toByte()
        } ?: false
    } catch (t: Throwable) {
        false
    }

    /** 模糊源解码线程：单线程串行，连续换图时逐个处理，避免并发解码堆积内存 */
    private val blurSrcExec: java.util.concurrent.ExecutorService =
        java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "popup-blur-source").apply { isDaemon = true }
        }

    /**
     * 横屏详情区模糊层的源位图准备。
     *
     * **刻意不用 popupImage 的 Drawable 当模糊源**（原因见 LiveBlurView.onDraw 注释：
     * 取它的 Drawable 需要临时改写 bounds / matrix，会与 ImageView 自身绘制互相干扰，
     * 横屏表现为"设置了图片 / GIF 却看不见"；取不到时又只剩一块压暗黑块）。
     *
     * 这里按 URI **独立解码**一份小图（最长边 ≤ 480；模糊本身是低通，完全够用）：
     *  1. 与 Coil 的加载 / 内存缓存 / GIF 播放彻底解耦，不会干扰图片与动画；
     *  2. GIF 只会解出首帧（BitmapFactory 不支持动画），播放期间模糊层不参与重算；
     *  3. 只有 URI 变化才重新解码，拖动尺寸 / 颜色 / 位置滑块不会重复解码。
     */
    /**
     * 为模糊层预解码一张静态兜底位图。
     * 它只在「图片还没加载完」时顶上，图片一旦就绪就由 setSource(ImageView)
     * 接管为实时逐帧模糊（GIF 动、图片换，模糊跟着变）。
     */
    /**
     * 不再为模糊层解码任何位图。
     *
     * 模糊对象是弹窗背后的手机界面（系统跨窗口模糊），不是用户上传的图片。
     * 这里保留函数只是为了让调用点不变，实际不再解码 —— 少一条解码链路，
     * 就少一处可能误伤图片 / GIF 显示、也少一处 OOM 的地方。
     */
    private fun prefetchBlurSource(context: Context, view: View, imageUri: String?) {
        val blur = view.findViewById<LiveBlurView>(R.id.detailBlurBg) ?: return
        // 只在「模糊上传的图片」模式下需要；屏幕模式由系统合成器采样，不解码任何位图
        if (!Prefs.blurFromImage) {
            blur.setSourceBitmap(null)
            return
        }
        val tagKey = imageUri ?: ""
        if (blur.getTag(R.id.detailBlurBg) == tagKey) return
        blur.setTag(R.id.detailBlurBg, tagKey)
        if (imageUri.isNullOrBlank()) {
            blur.setSourceBitmap(null)
            return
        }
        blur.setSourceBitmap(null)
        val appContext = context.applicationContext
        blurSrcExec.execute {
            val bmp = decodeBlurSource(appContext, imageUri)
            if (bmp != null) {
                blur.post { blur.setSourceBitmap(bmp) }
            } else {
                Log.w(TAG, "模糊源解码失败：" + imageUri)
            }
        }
    }

    /** 独立解码一份"小图"作为模糊源；GIF 只取首帧，不会启动动画 */
    private fun decodeBlurSource(context: Context, imageUri: String): android.graphics.Bitmap? = try {
        val uri = android.net.Uri.parse(imageUri)
        val scheme = uri.scheme?.lowercase()
        // content://（相册选择）走 resolver；file:// 与裸路径直接读文件，保证各种来源都能取到源
        val open: () -> java.io.InputStream? = {
            when {
                scheme.isNullOrEmpty() -> java.io.File(imageUri).inputStream()
                scheme == "file" -> java.io.File(uri.path ?: imageUri).inputStream()
                else -> context.contentResolver.openInputStream(uri)
            }
        }
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open()?.use {
            android.graphics.BitmapFactory.decodeStream(it, null, bounds)
        }
        var sample = 1
        val maxSide = maxOf(bounds.outWidth, bounds.outHeight)
        if (maxSide > 0) {
            while (maxSide / (sample * 2) >= 480) sample *= 2
        }
        val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
        open()?.use {
            android.graphics.BitmapFactory.decodeStream(it, null, opts)
        }
    } catch (t: Throwable) {
        Log.w(TAG, "模糊源解码异常", t)
        null
    }

    private fun applyPanelStyle(view: View, landscape: Boolean) {
        val density = view.context.resources.displayMetrics.density
        val radiusPx = Prefs.cornerRadiusDp.coerceIn(0, 200) * density
        //
        // 卡片底色必须半透明，系统模糊才能透出来。
        // 图片 / GIF 区域由不透明的原图铺满，天然盖住、始终清晰 ——
        // 露出来的空白区显示的就是"被盖住的手机界面模糊后的样子"。
        // 竖屏横屏统一走面板不透明度。
        //
        val alphaPercent = Prefs.panelAlpha.coerceIn(0, 100)
        val alpha = (alphaPercent * 255 / 100).coerceIn(0, 255)
        val panelColor = ColorUtils.setAlphaComponent(Prefs.panelColor or (0xFF shl 24), alpha)

        // 参考外观：横屏的圆角 / 底色 / 裁剪全部交给 RoundedCardLayout 处理，
        // 这样"图片铺满整张卡片"时四个角也会被平滑裁掉，不会出现直角毛边
        val card = view.findViewById<View>(R.id.card) as? RoundedCardLayout
        if (card != null) {
            card.setCardStyle(radiusPx, panelColor, Prefs.accentColor or (0xFF shl 24), 0f)
        } else {
            // 竖屏：整块换成「纯色 + 圆角 + 细描边」的 GradientDrawable。
            // 不能沿用「改原背景 + setColor」的写法：原背景是 <gradient> 渐变，
            // 渐变填充下 setColor 不生效，面板颜色 / 不透明度两个参数会看似失灵。
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = radiusPx
                setColor(panelColor)
                setStroke((1 * density).toInt(), 0x33FFFFFF)
            }
            view.background = bg
        }

        // 图片区尺寸：高度 = 图片高度滑块 × 整体缩放系数，宽度永远撑满卡片。
        // 竖屏**不再让图片区用 weight 吃剩余空间** —— 卡片高度不够时，
        // 文字换行会把图片区压缩成 0dp，表现就是「一调宽度，自定义图片 / GIF 直接没了」。
        // 「图片高度」滑块是整卡的高度基准（与布局默认 176dp 的比值），
        // 竖屏把它并入整体系数：调高度 = 卡片整体等比放大（字号 / 间距 / 图标 / 图片区同步），
        // 而不是只把图片区拉高、在文字下方留出一段空白（旧行为的根因）。
        val heightFactor = Prefs.imageHeightDp.coerceIn(40, 600) / IMAGE_BASE_DP
        val s = if (landscape) {
            landScale()
        } else {
            (portraitScale() * heightFactor).coerceIn(0.35f, 2.5f)
        }
        val wrap = view.findViewById<View>(R.id.imageWrap)
        when (val lp = wrap?.layoutParams) {
            is LinearLayout.LayoutParams -> {
                lp.width = ViewGroup.LayoutParams.MATCH_PARENT
                lp.height = (IMAGE_BASE_DP * density * s).toInt().coerceAtLeast((24 * density).toInt())
                lp.weight = 0f
                wrap.layoutParams = lp
            }
            is FrameLayout.LayoutParams -> {
                if (landscape) {
                    // 横屏参考外观：媒体区铺满整张卡片，图片本身就是卡片背景
                    lp.width = FrameLayout.LayoutParams.MATCH_PARENT
                    lp.height = FrameLayout.LayoutParams.MATCH_PARENT
                    wrap.layoutParams = lp
                }
            }
            else -> Unit
        }

        // 横屏：① 整体等比缩小（字号 / 内边距 / 间距 / 图标同步缩，形态不变）
        //       ② 详情区换成动态模糊背景层，并与上方清晰画面做渐隐过渡
        if (landscape) {
            scaleSubtree(view, s)
            setupLandscapeBlur(view, s, density)
        } else {
            // 竖屏：① 整体等比缩放（字号 / 内边距 / 间距 / 图标同步缩放），
            //           卡片高度随内容自然变化 —— 调高度就是整体等比放大，不是拉长空白；
            //       ② 只在「图片区以外的区域」铺模糊背景层，图片 / GIF 区域完全不参与模糊
            scaleSubtree(view, s)
            setupPortraitBlur(view, density)
        }

        applyColors(view)
    }

    // ------------------------------------------------------------------ 横屏等比缩放

    /** 记录某个 View 的原始尺寸基准（字号 / 内边距 / 宽高 / 间距） */
    private fun captureBase(v: View): FloatArray {
        val b = FloatArray(11)
        if (v is TextView) b[0] = v.textSize
        b[1] = v.paddingLeft.toFloat()
        b[2] = v.paddingTop.toFloat()
        b[3] = v.paddingRight.toFloat()
        b[4] = v.paddingBottom.toFloat()
        val lp = v.layoutParams
        if (lp != null) {
            b[5] = lp.width.toFloat()
            b[6] = lp.height.toFloat()
            if (lp is ViewGroup.MarginLayoutParams) {
                b[7] = lp.leftMargin.toFloat()
                b[8] = lp.topMargin.toFloat()
                b[9] = lp.rightMargin.toFloat()
                b[10] = lp.bottomMargin.toFloat()
            }
        }
        return b
    }

    /**
     * 横屏整体等比缩放的递归实现：
     * 把「字号 / 内边距 / 间距 / 宽高（固定尺寸）/ 图标尺寸」按同一系数重算，
     * 所以弹窗形态与比例完全保持原样，只是整体变小。
     *
     * 基准值只记一次，之后每次都从基准 × 系数得到，避免反复拖动滑块造成累乘失真。
     */
    private fun scaleSubtree(v: View, s: Float) {
        // 模糊层是纯色/模糊绘制，不参与缩放（高度由 detailArea 实测高度决定）
        if (v is LiveBlurView) return

        // 图片区自身尺寸由 applyPanelStyle 按「图片高度滑块 × 整体系数」单独设置，
        // 这里跳过它自身的宽高 / 边距改写，避免两处逻辑互相覆盖
        // （但它内部的占位图标 / 提示文字仍要跟随整体缩放）
        if (v.id == R.id.imageWrap) {
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) scaleSubtree(v.getChildAt(i), s)
            }
            return
        }

        val base = scaleBase.getOrPut(v) { captureBase(v) }

        if (v is TextView && base[0] > 0f) {
            v.setTextSize(TypedValue.COMPLEX_UNIT_PX, (base[0] * s).coerceAtLeast(1f))
        }

        v.setPadding(
            (base[1] * s).toInt(),
            (base[2] * s).toInt(),
            (base[3] * s).toInt(),
            (base[4] * s).toInt()
        )

        val lp = v.layoutParams
        if (lp != null) {
            // 仅缩放固定尺寸；MATCH_PARENT(-1) / WRAP_CONTENT(-2) / 0dp+weight 一律不动
            if (base[5] > 0f) lp.width = (base[5] * s).toInt().coerceAtLeast(1)
            if (base[6] > 0f) lp.height = (base[6] * s).toInt().coerceAtLeast(1)
            if (lp is ViewGroup.MarginLayoutParams) {
                lp.leftMargin = (base[7] * s).toInt()
                lp.topMargin = (base[8] * s).toInt()
                lp.rightMargin = (base[9] * s).toInt()
                lp.bottomMargin = (base[10] * s).toInt()
                lp.marginStart = (base[7] * s).toInt()
                lp.marginEnd = (base[9] * s).toInt()
            }
            v.layoutParams = lp
        }

        if (v is ViewGroup) {
            for (i in 0 until v.childCount) scaleSubtree(v.getChildAt(i), s)
        }
    }

    // ------------------------------------------------------------------ 横屏动态模糊

    /** 每张卡片当前挂起的模糊层几何对齐监听，重复调用时先摘旧的再挂新的 */
    private val blurFitListeners = WeakHashMap<View, View.OnLayoutChangeListener>()

    /**
     * 横屏详情区【实时动态模糊背景层】（参考 ui_ref 的最终方案）：
     *  1. 直接绑定上方 popupImage 的 Drawable → GIF 每前进一帧就重算一次模糊，真正逐帧同步；
     *  2. 模糊层几何由 [fitBlurToDetail] 精确锚定：顶部 = 详情区上沿 − 渐隐带，
     *     底部 = 卡片底边，**永远铺满到底**，不会再露出一条未覆盖的细线；
     *  3. 渐隐带让模糊层顶部柔和融进上方清晰画面，不做硬边一刀切；
     *  4. 底部两角圆角跟随卡片圆角；轻度压暗只为保证文字可读。
     */
    private fun setupLandscapeBlur(view: View, s: Float, density: Float) {
        //
        // 横屏同样停用自制模糊层。
        //
        // 之前"横屏模糊固定一帧不动"的根因：自制层绑定弹窗自己的图，
        // 只在源就位的那一帧算了一次，之后 GIF 帧变化未必能驱动它重绘。
        // 改用系统跨窗口模糊后，模糊由系统合成器实时采样弹窗背后的屏幕内容，
        // 背后在动它就跟着动，不存在"截一帧固定住"这回事。
        // 同时省掉整段解码 + stackBlur，卡顿与闪退的源头消失。
        //
        val blur = view.findViewById<LiveBlurView>(R.id.detailBlurBg) ?: return

        if (!Prefs.blurFromImage) {
            // 模糊「弹窗背后被盖住的手机界面」：由系统跨窗口模糊提供，
            // 这一层不参与绘制。（若你的 ROM 把它渲染成静态快照、不会动，
            // 请在设置里把「模糊来源」改成「上传的图片 / GIF」）
            blur.setSource(null)
            blur.setSourceBitmap(null)
            blur.visibility = View.GONE
            return
        }

        // —— 以下为「模糊上传的图片 / GIF」模式：实时逐帧，跟着 GIF 一起动 ——
        val detail = view.findViewById<View>(R.id.detailArea)
        val image = view.findViewById<ImageView>(R.id.popupImage)
        blur.setCoverSource(false)
        val extraPx = (Prefs.landBlurFadeDp.coerceIn(0, 80) * density * s).toInt()
        blur.setBlurRadius(Prefs.landBlurRadiusDp.coerceIn(0, 60) * density)
        val dim = Prefs.landBlurDimPercent.coerceIn(0, 90) * 255 / 100
        blur.setDim(dim shl 24)
        blur.setBottomCornerRadius(Prefs.cornerRadiusDp.coerceIn(0, 200) * density)
        blur.setSource(image)
        blur.visibility = View.VISIBLE
        if (detail == null) {
            blur.setFadeRatio(if (extraPx > 0) 0.25f else 0f)
            return
        }
        fitBlurToDetail(blur, detail, extraPx)
    }

    /**
     * 把模糊层精确锚定为「(详情区上沿 − 渐隐带) → 卡片底边」这一整段。
     *
     * 旧实现用 postDelayed 重试 + detail.height 计算，布局收敛时机对不上时
     * 模糊层比详情区矮一截，底部就会露出一条未覆盖的细线。
     * 现在监听详情区自身的布局变化（电量文本刷新 / 缩放变化都会触发），
     * 每次都按 detail.top 精确重算，保证：
     *  - 底边 = 卡片底边（分毫不差，没有细线）；
     *  - 顶部 = 详情区上沿 − 渐隐带（渐隐带 = 用户设定值与详情区高度 45% 取大者，
     *    与参考外观一致：大面积柔和过渡，不做硬边）。
     */
    private fun fitBlurToDetail(blur: LiveBlurView, detail: View, extraPx: Int) {
        val card = blur.parent as? View ?: return
        blurFitListeners.remove(detail)?.let { detail.removeOnLayoutChangeListener(it) }
        val refit = object : View.OnLayoutChangeListener {
            override fun onLayoutChange(
                v: View, left: Int, top: Int, right: Int, bottom: Int,
                oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int
            ) {
                try {
                    alignBlur(blur, detail, card, extraPx)
                } catch (t: Throwable) {
                    Log.w(TAG, "模糊层对齐失败: " + t.message)
                }
            }
        }
        blurFitListeners[detail] = refit
        detail.addOnLayoutChangeListener(refit)
        // 首次对齐：立即一次 + 延迟两次，等首帧布局收敛
        alignBlur(blur, detail, card, extraPx)
        detail.postDelayed({ alignBlur(blur, detail, card, extraPx) }, 80L)
        detail.postDelayed({ alignBlur(blur, detail, card, extraPx) }, 300L)
    }

    private fun alignBlur(blur: LiveBlurView, detail: View, card: View, extraPx: Int) {
        val cardH = card.height
        if (cardH <= 0) return
        val detailH = detail.height
        val fadePx = maxOf(
            extraPx,
            (detailH * 0.45f).toInt(),
            (cardH * 0.12f).toInt()
        ).coerceAtLeast(1)
        // 顶部 = 详情区上沿 − 渐隐带（不高于卡片顶部）；底边 = 卡片底边
        val top = (detail.top - fadePx).coerceAtLeast(0)
        val h = cardH - top
        if (h <= 0) {
            blur.visibility = View.GONE
            return
        }
        blur.visibility = View.VISIBLE
        val lp = blur.layoutParams
        if (lp != null && lp.height != h) {
            lp.height = h
            blur.layoutParams = lp
        }
        blur.setFadeRatio(min(1f, fadePx.toFloat() / h.toFloat()))
    }

    // ------------------------------------------------------------------ 竖屏卡片背景模糊

    /** 每个竖屏模糊层当前挂起的"对齐尺寸"任务，用于反复调参时取消旧任务 */
    private val portraitFitTask = WeakHashMap<View, Runnable>()

    /** 每张预览图的"加载看门狗"：请求迟迟不回调时把占位提示重新亮出来 */
    private val imageLoadGuard = WeakHashMap<ImageView, Runnable>()

    /**
     * 模糊层上限：必须能完整铺满「图片区下方 → 卡片底部」整段，
     * 否则底部会露出一条未模糊的突兀色块；同时用整卡高度兜底坐标异常。
     */
    private val BLUR_MAX_CARD_RATIO = 1f

    /** 图片请求看门狗超时：超过这个时间既没成功也没失败，就认为加载不可用 */
    // 超时兜底必须足够宽松：大图 / GIF 首次解码在低端机上可能超过 2s，
    // 过早回退会把「正在加载」误判成「没有图片」，反而显示成"未设置图片"提示文字。
    private val IMAGE_LOAD_TIMEOUT_MS = 8000L

    /**
     * 竖屏卡片背景【高斯模糊】：**只作用于「图片 / GIF 区域以外」的弹窗区域**。
     *
     *  1. 源图 = 卡片上方那张图片 / GIF，取定格帧做一次性快照模糊，
     *     播放动画时不参与逐帧重算 —— 既不拖慢 GIF，也不会让文字区糊成一片；
     *  2. 模糊层只在图片区正下方铺到卡片底部（= 文字区所在的那一段），
     *     图片 / GIF 所在区域完全不参与模糊，永远不会被盖住；
     *  3. 顶部留一段渐隐带（fadeRatio），与上方清晰画面柔和衔接，不做硬边；
     *  4. 图片 / GIF 所在的 imageWrap 分层在模糊层之上，始终清晰显示原图。
     */
    /**
     * 竖屏：不再自制模糊层。
     *
     * 模糊对象改为「弹窗背后被盖住的手机界面」，由系统跨窗口模糊提供
     * （见 showInternal 里的 crossBlur），靠半透明卡片透出来。
     *
     * 自制层彻底停用：它要自己解码位图并跑 stackBlur，
     * 整卡尺寸下正是卡顿与 OOM 闪退的根源；而且源是弹窗自己的图，方向本身就不对。
     */
    private fun setupPortraitBlur(view: View, density: Float) {
        val blur = view.findViewById<LiveBlurView>(R.id.popupBlurBg) ?: return

        if (!Prefs.blurFromImage) {
            // 模糊「弹窗背后被盖住的手机界面」：由系统跨窗口模糊提供
            blur.setSource(null)
            blur.setSourceBitmap(null)
            blur.visibility = View.GONE
            return
        }

        // —— 「模糊上传的图片 / GIF」模式：铺满整卡，逐帧重算 ——
        val image = view.findViewById<ImageView>(R.id.popupImage)
        blur.setCoverSource(true)
        blur.setBlurRadius(Prefs.portraitBlurRadiusDp.coerceIn(0, 60) * density)
        val dim = Prefs.portraitBlurDimPercent.coerceIn(0, 90) * 255 / 100
        blur.setDim(dim shl 24)
        blur.setBottomCornerRadius(Prefs.cornerRadiusDp.coerceIn(0, 200) * density)
        blur.setFadeRatio(0f)
        blur.setSource(image)
        fitPortraitBlurHeight(blur, 0)
    }

    /**
     * 把模糊层高度对齐为「卡片实测高度」，且**只在布局完成后**写固定像素值。
     * 测量阶段一律保持 1dp，绝不参与父容器 wrap_content 的测量 ——
     * 否则会把整张卡片顶到全屏（这正是 1.3.5「弹窗铺满全屏」的根因）。
     */
    private fun fitPortraitBlurHeight(blur: LiveBlurView, attempt: Int) {
        val host = blur.parent as? View ?: return
        val lp0 = blur.layoutParams
        if (lp0 != null && lp0.height > 1) {
            lp0.height = 1
            blur.layoutParams = lp0
        }
        blur.post {
            val h = host.height
            if (h > 0) {
                val lp = blur.layoutParams
                if (lp != null) {
                    lp.height = h
                    if (lp is FrameLayout.LayoutParams) lp.gravity = Gravity.TOP
                    blur.layoutParams = lp
                }
                blur.visibility = View.VISIBLE
            } else if (attempt < 8) {
                fitPortraitBlurHeight(blur, attempt + 1)
            } else {
                blur.visibility = View.GONE
            }
        }
    }

    /**
     * 等卡片走完布局后，把模糊层对齐到「图片区正下方 → 卡片底部」这一整段：
     *  1. 高度 = 卡片高 − 图片区底边；图片区底边用窗口坐标换算，
     *     不受中间层级 padding 影响，比直接读 sharp.bottom 更可靠；
     *  2. **必须显式设置 gravity = BOTTOM** —— 模糊层是 FrameLayout 的子 View，
     *     默认停在左上角；只改高度会让它整体跑到卡片顶部，既盖住顶部圆角，
     *     又在图片区上方露出一条突兀色块，这正是「边角 / 色块 / 颜色异常」的来源；
     *  3. 本层现在只做压暗，模糊本身由系统跨窗口模糊提供，因此不再需要横向拉伸源内容。
     */
    private fun alignPortraitBlur(blur: LiveBlurView, sharp: View) {
        val host = blur.parent as? View
        portraitFitTask[blur]?.let { blur.removeCallbacks(it) }
        val attempt = object : Runnable {
            private var tries = 0
            private var passes = 0
            override fun run() {
                val cardH = host?.height ?: 0
                val w = blur.width
                if (cardH > 0 && w > 0 && sharp.height > 0) {
                    // 用「父链 top 累加」而不是窗口坐标：预览舞台整体做了缩放，
                    // getLocationInWindow 给的是缩放前的坐标，换算到卡片内会偏一截，
                    // 结果就是模糊层比图片区底边低一截、压住图片下缘。
                    var acc = 0
                    var cur: View? = sharp
                    while (cur != null && cur !== host) {
                        acc += cur.top
                        cur = cur.parent as? View
                    }
                    val sharpBottomInCard = acc + sharp.height
                    // 模糊层只出现在图片区正下方，并一直铺到卡片底部：
                    // 1) 上限取「整卡高度 × 比例」，避免图片下方那段被截短、底部露出未模糊的突兀色块；
                    // 2) 再加一道兜底：模糊层顶部绝不高于「图片区自身的底边」，
                    //    这样即使坐标换算抖动（图片区位置读到 0），也绝不会向上盖住图片 / GIF
                    //    —— 之前"乱加模糊把图片糊没了"就是这么来的。
                    val maxBlurH = (cardH * BLUR_MAX_CARD_RATIO).toInt().coerceAtLeast(0)
                    val startY = sharpBottomInCard.coerceAtLeast(sharp.height).coerceAtMost(cardH)
                    val target = (cardH - startY).coerceIn(0, maxBlurH)
                    val lp = blur.layoutParams
                    if (lp != null) {
                        if (lp.height != target) lp.height = target
                        if (lp is FrameLayout.LayoutParams) lp.gravity = Gravity.BOTTOM
                        blur.layoutParams = lp
                    }
                    // 注意：绝不能对 imageWrap 调 bringToFront()。它是 contentColumn(LinearLayout)
                    // 的第一个子项，bringToFront 会把它挪到末尾，竖屏布局直接被改成
                    // 「设备名 → 电量 → 图片」，表现为图片区跑到卡片底部 / 被模糊层压掉下半截。
                    // 层级关系由布局文件保证：popupBlurBg 是 popupRoot 的第一个子项，
                    // contentColumn 在其之后，天然压在模糊层之上。
                    // 没有可模糊的空间时直接收起这一层，避免留下一条突兀色带
                    blur.visibility = if (target > 0) View.VISIBLE else View.GONE
                    // 卡片高度 / 图片区高度可能还在收敛：多补算几次取最终稳定几何。
                    // 否则模糊层会比图片区底边低一截，压住图片下缘形成一条突兀暗带
                    // （表现就是「图片只显示在上面、下面被吃掉一块」）。
                    if (passes++ < 3) blur.postDelayed(this, 80L)
                } else if (tries++ < 12) {
                    blur.postDelayed(this, 50L)
                }
            }
        }
        portraitFitTask[blur] = attempt
        blur.post(attempt)
    }

    // ------------------------------------------------------------------ 动画

    /**
     * 把卡片预先设成入场动画的起始状态。
     * 必须在 addView 之前调用 —— 否则窗口加入后会先渲染一帧完整卡片，表现为「闪一下」。
     */
    private fun primeEnterState(view: View) {
        val density = view.context.resources.displayMetrics.density
        val offset = 90 * density
        view.animate().cancel()
        view.alpha = 0f
        when (Prefs.animType) {
            "fade" -> { }
            "scale" -> { view.scaleX = 0.85f; view.scaleY = 0.85f }
            "slide_top" -> view.translationY = -offset
            "slide_bottom" -> view.translationY = offset
            else -> { view.scaleX = 0.9f; view.scaleY = 0.9f }
        }
    }

    private fun playEnter(view: View) {
        val duration = Prefs.animDuration.toLong().coerceIn(80L, 1500L)
        val density = view.context.resources.displayMetrics.density
        val offset = 90 * density

        when (Prefs.animType) {
            "fade" -> {
                view.alpha = 0f
                view.animate().alpha(1f).setDuration(duration)
                    .setInterpolator(DecelerateInterpolator()).start()
            }
            "scale" -> {
                view.alpha = 0f
                view.scaleX = 0.85f
                view.scaleY = 0.85f
                view.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(duration)
                    .setInterpolator(DecelerateInterpolator()).start()
            }
            "slide_top" -> {
                view.alpha = 0f
                view.translationY = -offset
                view.animate().alpha(1f).translationY(0f).setDuration(duration)
                    .setInterpolator(DecelerateInterpolator()).start()
            }
            "slide_bottom" -> {
                view.alpha = 0f
                view.translationY = offset
                view.animate().alpha(1f).translationY(0f).setDuration(duration)
                    .setInterpolator(DecelerateInterpolator()).start()
            }
            else -> {
                view.alpha = 0f
                view.scaleX = 0.9f
                view.scaleY = 0.9f
                view.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(duration)
                    .setInterpolator(OvershootInterpolator(1.1f)).start()
            }
        }
    }

    // ------------------------------------------------------------------ 配色

    private fun applyColors(view: View) {
        val text = Prefs.textColor or (0xFF shl 24)
        val accent = Prefs.accentColor or (0xFF shl 24)

        view.findViewById<TextView>(R.id.tvDeviceName)?.setTextColor(text)
        view.findViewById<TextView>(R.id.tvConnState)?.setTextColor(ColorUtils.setAlphaComponent(text, 180))
        view.findViewById<TextView>(R.id.tvSourceTag)?.setTextColor(accent)
        // 横屏电量行：跟随主文字色，和三个百分比保持同一套配色
        view.findViewById<TextView>(R.id.tvBatteryLine)?.setTextColor(text)

        intArrayOf(R.id.tvLeftLabel, R.id.tvRightLabel, R.id.tvCaseLabel).forEach { id ->
            view.findViewById<TextView>(id)?.setTextColor(ColorUtils.setAlphaComponent(text, 190))
        }
        intArrayOf(R.id.tvLeftPercent, R.id.tvRightPercent, R.id.tvCasePercent).forEach { id ->
            view.findViewById<TextView>(id)?.setTextColor(accent)
        }
        intArrayOf(R.id.pbLeft, R.id.pbRight, R.id.pbCase).forEach { id ->
            view.findViewById<ProgressBar>(id)?.let { paintProgressBar(it, accent) }
        }

        // 未设置图片 / GIF 时的占位提示
        (view.findViewById<View>(R.id.noImageHint) as? ViewGroup)?.let { group ->
            for (i in 0 until group.childCount) {
                when (val child = group.getChildAt(i)) {
                    is TextView -> child.setTextColor(ColorUtils.setAlphaComponent(text, 140))
                    is ImageView -> child.alpha = 0.62f
                }
            }
        }
    }

    /** 用自定义强调色重建进度条前景，避免直接 tint 把轨道一起染色 */
    private fun paintProgressBar(bar: ProgressBar, accent: Int) {
        try {
            val track = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 999f
                setColor(ColorUtils.setAlphaComponent(0xFFFFFFFF.toInt(), 51))
            }
            val fill = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 999f
                orientation = GradientDrawable.Orientation.LEFT_RIGHT
                colors = intArrayOf(accent, ColorUtils.setAlphaComponent(accent, 165))
            }
            val clip = ClipDrawable(fill, Gravity.START, ClipDrawable.HORIZONTAL)
            val layer = LayerDrawable(arrayOf(track, clip))
            layer.setId(0, android.R.id.background)
            layer.setId(1, android.R.id.progress)
            bar.progressDrawable = layer
            bar.progressTintList = null
        } catch (t: Throwable) {
            Log.w(TAG, "进度条染色失败: " + t.message)
        }
    }
}
