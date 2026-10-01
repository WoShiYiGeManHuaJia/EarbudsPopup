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
    /**
     * 卡片底色不透明度（%）。
     * 数值越低，背后被弹窗盖住的手机界面透得越多、毛玻璃越明显。
     * 100 = 完全实色（此时背景模糊看不见）。
     */
    var panelAlpha: Int
        get() = gi("panel_alpha", 82)
        set(v) = si("panel_alpha", v.coerceIn(0, 100))

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

    /**
     * 横屏详情区是否启用动态模糊（模糊上传的图片 / GIF）。
     *
     * 【默认关闭】「横屏弹窗不弹」与「反复闪退」高度同源：
     * 弹窗一触发模糊就崩，进程被杀，弹窗自然出不来。
     * 默认关掉，先保证「横屏能正常弹、App 不崩」；想试模糊再手动打开。
     */
    var landBlurEnabled: Boolean
        get() = gb("land_blur_enabled", false)
        set(v) = sb("land_blur_enabled", v)

    /** 横屏详情区动态模糊层的模糊半径（dp） */
    var landBlurRadiusDp: Int
        get() = gi("land_blur_radius", 26)
        set(v) = si("land_blur_radius", v.coerceIn(0, 60))

    /** 横屏详情区模糊层之上的压暗程度（%），只为保证文字可读 */
    var landBlurDimPercent: Int
        get() = gi("land_blur_dim", 12)
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
        get() = gi("portrait_blur_dim", 12)
        set(v) = si("portrait_blur_dim", v)

    // ---------------- 竖屏：纯白面板（不做任何模糊） ----------------
    //
    // 竖屏模糊是反复闪退的根源：整卡尺寸的 stackBlur 要在主线程上跑几十万像素，
    // 既卡又 OOM。用户改为要求：竖屏干脆不要模糊，做成系统弹窗那种纯白。
    // 竖屏不解码位图、不跑模糊、不绑定 ImageView —— 这条路径上再没有能崩的东西。
    // 模糊只保留在横屏那一小块（面积小，运算量可控）。

    /** 竖屏是否使用纯白面板（关闭模糊）。默认开。 */
    var portraitSolidWhite: Boolean
        get() = gb("portrait_solid_white", true)
        set(v) = sb("portrait_solid_white", v)

    /** 竖屏纯白面板的白（各厂商系统弹窗通用白） */
    val SOLID_WHITE = 0xFFFFFFFF.toInt()

    /** 纯白面板上的文字色（近黑），保证可读 */
    val SOLID_WHITE_TEXT = 0xFF1A1A1A.toInt()

    /** 纯白面板上的强调色（系统蓝，与原生弹窗一致） */
    val SOLID_WHITE_ACCENT = 0xFF1677FF.toInt()

    // ---------------- 毛玻璃：模糊"被弹窗盖住的手机界面" ----------------
    //
    // 这才是用户要的效果：弹窗盖住了手机界面上的某个字/图标，
    // 弹窗上这一块空白就显示"那个字被模糊后的样子"。
    // 模糊对象是弹窗背后的屏幕内容，不是用户上传的图片 / GIF。
    //
    // 实现走系统跨窗口模糊（FLAG_BLUR_BEHIND）：由系统合成器实时采样窗口背后内容，
    // 因此背后在动它也跟着动（横屏"固定一帧"的问题从根上消失），
    // 而且完全不需要 App 自己解码位图、跑 stackBlur —— 卡顿与 OOM 闪退的源头也被移除。

    /** 是否启用毛玻璃（模糊弹窗背后被盖住的手机界面） */
    var windowBlurEnabled: Boolean
        get() = gb("window_blur_enabled", true)
        set(v) = sb("window_blur_enabled", v)

    /** 毛玻璃模糊半径（dp），0 = 不模糊 */
    var windowBlurRadiusDp: Int
        get() = gi("window_blur_radius", 24)
        set(v) = si("window_blur_radius", v.coerceIn(0, 60))

    /**
     * 模糊源：
     *  - "screen"：模糊弹窗背后被盖住的手机界面（系统跨窗口模糊，实时）
     *  - "image" ：模糊弹窗自己上传的图片 / GIF（App 逐帧重算，跟着 GIF 一起动）
     *
     * 部分 ROM 的跨窗口模糊是静态快照、不随背后内容刷新，
     * 那种机器上选 "image" 才能看到会动的模糊。
     */
    var blurSourceMode: String
        get() = gs("blur_source_mode", "screen") ?: "screen"
        set(v) = ss("blur_source_mode", v)

    /** 便捷判断：当前是否走「模糊上传的图片 / GIF」 */
    val blurFromImage: Boolean get() = blurSourceMode == "image"

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
