package com.woshiyigemanhuajia.btpopup.util

import android.content.Context
import android.content.SharedPreferences

/**
 * 全局配置存储。
 */
object Prefs {

    private const val NAME = "bt_popup_prefs"
    private lateinit var sp: SharedPreferences
    private var ready = false

    fun init(ctx: Context) {
        if (ready) return
        sp = ctx.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)
        ready = true
    }

    private fun gs(k: String, d: String?): String? = if (ready) sp.getString(k, d) else d
    private fun ss(k: String, v: String?) {
        if (ready) sp.edit().putString(k, v).apply()
    }

    private fun gi(k: String, d: Int): Int = if (ready) sp.getInt(k, d) else d
    private fun si(k: String, v: Int) {
        if (ready) sp.edit().putInt(k, v).apply()
    }

    private fun gb(k: String, d: Boolean): Boolean = if (ready) sp.getBoolean(k, d) else d
    private fun sb(k: String, v: Boolean) {
        if (ready) sp.edit().putBoolean(k, v).apply()
    }

    // ---------------- 外观 ----------------
    var imageUri: String?
        get() = gs("image_uri", null)
        set(v) = ss("image_uri", v)

    /** 0-100，面板背景不透明度 */
    var panelAlpha: Int
        get() = gi("panel_alpha", 88)
        set(v) = si("panel_alpha", v)

    /** 图片区高度 dp（40 - 600） */
    var imageHeightDp: Int
        get() = gi("image_height", 176)
        set(v) = si("image_height", v)

    /** 图片 / GIF 缩放模式：crop 裁剪填充 / fit 完整显示 / stretch 拉伸铺满 / center 原尺寸居中 */
    var imageScaleMode: String
        get() = gs("image_scale_mode", "crop") ?: "crop"
        set(v) = ss("image_scale_mode", v)

    // ---------------- 图片自由变换（修复「上传的图片跟 GIF 不能自己调整位置跟大小」） ----------------
    //
    // 之前只有 4 种固定 scaleMode，上传完想让主体露多一点、想左右挪一点都做不到，
    // 只能换图重传。这里补上连续的缩放倍数、水平/垂直位移、旋转角度，
    // 叠加在 scaleMode 算出的基准尺寸之上。

    /** 图片额外缩放倍数（%），100 = scaleMode 算出的基准大小 */
    var imageScalePercent: Int
        get() = gi("image_scale_percent", 100)
        set(v) = si("image_scale_percent", v.coerceIn(20, 400))

    /** 水平位移（dp），正 = 向右 */
    var imageOffsetXDp: Int
        get() = gi("image_offset_x", 0)
        set(v) = si("image_offset_x", v.coerceIn(-400, 400))

    /** 垂直位移（dp），正 = 向下 */
    var imageOffsetYDp: Int
        get() = gi("image_offset_y", 0)
        set(v) = si("image_offset_y", v.coerceIn(-400, 400))

    /** 旋转角度（度） */
    var imageRotationDeg: Int
        get() = gi("image_rotation", 0)
        set(v) = si("image_rotation", v.coerceIn(-180, 180))

    /** 重置图片变换回默认值 */
    fun resetImageTransform() {
        imageScalePercent = 100
        imageOffsetXDp = 0
        imageOffsetYDp = 0
        imageRotationDeg = 0
    }

    /** 面板背景色（RGB，透明度由 panelAlpha 控制） */
    var panelColor: Int
        get() = gi("panel_color", 0xFF1B2436.toInt())
        set(v) = si("panel_color", v)

    /** 弹窗文字颜色（RGB） */
    var textColor: Int
        get() = gi("text_color", 0xFFFFFFFF.toInt())
        set(v) = si("text_color", v)

    /** 强调色：电量数字与进度条（RGB） */
    var accentColor: Int
        get() = gi("accent_color", 0xFF2DD4BF.toInt())
        set(v) = si("accent_color", v)

    var autoPopup: Boolean
        get() = gb("auto_popup", true)
        set(v) = sb("auto_popup", v)

    // ---------------- 竖屏尺寸 / 位置 ----------------
    /** 弹窗宽度占屏幕宽度百分比 */
    var widthPercent: Int
        get() = gi("width_percent", 84)
        set(v) = si("width_percent", v)

    /**
     * 竖屏弹窗**整体等比缩放**百分比（100 = 原始大小）。
     *
     * 这就是竖屏「高度」滑块的真实语义：字号、内边距、控件间距、图片区高度
     * 全部按同一系数同步放大 / 缩小，卡片高度随内容自然变化。
     * 调大 = 整体等比放大；不会再出现"只把文字下方空白拉长"的行为。
     *
     * 旧字段 heightFixed / heightDp 已废弃（竖屏不再按固定 dp 精确测量），
     * 仅保留在 Prefs 里做历史配置兼容，不参与任何渲染。
     */
    var portraitScalePercent: Int
        get() = gi("portrait_scale_percent", 100)
        set(v) = si("portrait_scale_percent", v)

    /** 弹窗水平位置 0-100（0 贴左边缘 / 100 贴右边缘，映射到可摆放的空白区间） */
    var posXPercent: Int
        get() = gi("pos_x", 50)
        set(v) = si("pos_x", v)

    /** 弹窗垂直位置 0-100（0 贴顶 / 100 贴底，映射到可摆放的空白区间） */
    var posYPercent: Int
        get() = gi("pos_y", 50)
        set(v) = si("pos_y", v)

    var cornerRadiusDp: Int
        get() = gi("corner_radius", 28)
        set(v) = si("corner_radius", v)

    // ---------------- 横屏 ----------------
    var landWidthPercent: Int
        get() = gi("land_width_percent", 64)
        set(v) = si("land_width_percent", v)

    var landHeightFixed: Boolean
        get() = gb("land_height_fixed", false)
        set(v) = sb("land_height_fixed", v)

    /** 固定高度时生效；自动高度 = 卡片宽度 × 扁平比例（保证永远是扁长形态） */
    var landHeightDp: Int
        get() = gi("land_height_dp", 120)
        set(v) = si("land_height_dp", v)

    /** 横屏弹窗距屏幕左右边缘的最小留白 */
    var landMarginDp: Int
        get() = gi("land_margin", 28)
        set(v) = si("land_margin", v)

    /**
     * 横屏弹窗整体等比缩放百分比（100 = 原始大小）。
     * 宽度、高度、内部字号、内边距、间距、图标尺寸按同一系数同步缩放，
     * 形态保持不变，只是整体变小。
     */
    var landScalePercent: Int
        get() = gi("land_scale_percent", 78)
        set(v) = si("land_scale_percent", v)

    /** 横屏详情区动态模糊层的模糊半径（dp） */
    var landBlurRadiusDp: Int
        get() = gi("land_blur_radius", 26)
        set(v) = si("land_blur_radius", v)

    /** 横屏详情区模糊层之上的压暗程度（%），只为保证文字可读 */
    var landBlurDimPercent: Int
        get() = gi("land_blur_dim", 30)
        set(v) = si("land_blur_dim", v)

    /** 横屏模糊层顶部的渐隐过渡带高度（dp）：越大过渡越柔和，0 = 硬边 */
    var landBlurFadeDp: Int
        get() = gi("land_blur_fade", 18)
        set(v) = si("land_blur_fade", v)

    // ---------------- 竖屏背景模糊 ----------------
    /** 竖屏卡片背景（图片正下方区域）高斯模糊半径（dp），0 = 不模糊只压暗 */
    var portraitBlurRadiusDp: Int
        get() = gi("portrait_blur_radius", 24)
        set(v) = si("portrait_blur_radius", v)

    /** 竖屏背景模糊层之上的压暗程度（%），保证文字可读 */
    var portraitBlurDimPercent: Int
        get() = gi("portrait_blur_dim", 30)
        set(v) = si("portrait_blur_dim", v)

    // ---------------- 动画 ----------------
    /** fade / scale / slide_top / slide_bottom / spring */
    var animType: String
        get() = gs("anim_type", "spring") ?: "spring"
        set(v) = ss("anim_type", v)

    var animDuration: Int
        get() = gi("anim_duration", 320)
        set(v) = si("anim_duration", v)

    /** 自动关闭延迟 ms，0 表示不自动关闭 */
    var dismissDelayMs: Int
        get() = gi("dismiss_delay", 4000)
        set(v) = si("dismiss_delay", v)

    // ---------------- 保活 ----------------
    var foregroundGuard: Boolean
        get() = gb("foreground_guard", true)
        set(v) = sb("foreground_guard", v)

    var bootStart: Boolean
        get() = gb("boot_start", true)
        set(v) = sb("boot_start", v)

    var monitorEnabled: Boolean
        get() = gb("monitor_enabled", true)
        set(v) = sb("monitor_enabled", v)

    // ---------------- 权限引导 ----------------
    /** 是否已经做过首次启动的权限引导（只在首次启动自动串一遍授权流程） */
    var permissionPrompted: Boolean
        get() = gb("permission_prompted", false)
        set(v) = sb("permission_prompted", v)

    fun all(): Map<String, *> = if (ready) sp.all else emptyMap<String, Any>()
}
