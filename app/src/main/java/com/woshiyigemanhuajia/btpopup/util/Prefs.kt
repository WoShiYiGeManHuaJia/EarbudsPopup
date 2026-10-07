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

    // ---------------- 弹窗提示音 ----------------

    /** 是否开启弹窗提示音 */
    var soundEnabled: Boolean
        get() = gb("sound_enabled", false)
        set(v) = sb("sound_enabled", v)

    /** 自定义提示音的 URI（null = 不播放自定义音，仅播报） */
    var soundUri: String?
        get() = gs("sound_uri", null)
        set(v) = ss("sound_uri", v)

    /** 弹窗出现后延迟多少毫秒播放提示音（0 = 立即） */
    var soundDelayMs: Int
        get() = gi("sound_delay_ms", 0)
        set(v) = si("sound_delay_ms", v.coerceIn(0, 10_000))

    /** 提示音播放时长上限（毫秒），到点自动停止；0 = 播完整段 */
    var soundDurationMs: Int
        get() = gi("sound_duration_ms", 0)
        set(v) = si("sound_duration_ms", v.coerceIn(0, 60_000))

    /** 音量 0-100 */
    var soundVolume: Int
        get() = gi("sound_volume", 80)
        set(v) = si("sound_volume", v.coerceIn(0, 100))

    /**
     * 是否显示「充电中」。
     *
     * 【默认关闭】充电标志来自厂商私有 HFP / 广播上报，各家编码不一致，
     * 第三方 App 没有可靠途径校验，极易把非充电状态误判成充电（表现为永远显示"充电中"）。
     * 与其一直显示错的，不如默认不显示 —— 需要的话自行打开。
     */
    var showCharging: Boolean
        get() = gb("show_charging", false)
        set(v) = sb("show_charging", v)

    /**
     * 类型与名称都无法识别的蓝牙设备，是否也弹窗。
     *
     * 【默认关闭】设备判定已改成白名单式（见 BluetoothPopupTrigger.isAudioLike）：
     * 只有明确是音频大类 / 音频设备类 / 名称命中耳机关键词才弹。
     * 绝大多数耳机都能被正确识别，因此默认不需要这条兜底。
     *
     * 万一某款耳机识别不出来、开盖不弹，打开这个开关即可恢复旧的"宁可多弹"行为
     * —— 代价是热水器、手环之类的蓝牙设备也会弹。
     */
    var popupUnknownDevices: Boolean
        get() = gb("popup_unknown_devices", false)
        set(v) = sb("popup_unknown_devices", v)

    /**
     * 是否启用小米「超级岛 / 焦点通知」上岛。
     *
     * 默认关闭：上岛依赖系统岛能力与（多数情况下）Stellar / Shizuku 提权绕过白名单校验，
     * 并非所有机型都能成功。让用户在设置页自己试，成功就留着，不成功也不影响弹窗。
     */
    var islandEnabled: Boolean
        get() = gb("island_enabled", false)
        set(v) = sb("island_enabled", v)

    /**
     * 岛的左区显示什么：true=耳机图标（type=0 + picInfo），false=耳机名文字（type=1 + textInfo）。
     *
     * 两种写法在不同 ROM 上表现不一致，做成可切换，哪个能显示就用哪个。
     */
    var islandLeftIcon: Boolean
        // 默认 false：与课表成品一致（左区用文字），先保证能上岛，再谈图标
        get() = gb("island_left_icon", false)
        set(v) = sb("island_left_icon", v)

    // ---------------- 自绘「耳机岛」（不依赖系统焦点通知）----------------

    /** 自绘岛总开关：连接耳机时在屏幕顶部显示胶囊岛 */
    var islandOverlayEnabled: Boolean
        get() = gb("island_overlay_enabled", false)
        set(v) = sb("island_overlay_enabled", v)

    /** 岛显示的文字，默认「已连接」 */
    var islandOverlayText: String
        get() = gs("island_overlay_text", "已连接") ?: "已连接"
        set(v) = ss("island_overlay_text", v)

    /** 岛高度 dp（圆角按高度一半，天然胶囊形） */
    var islandOverlayHeightDp: Int
        get() = gi("island_overlay_h", 36)
        set(v) = si("island_overlay_h", v)

    /** 岛宽度 dp（仅在非全宽时生效） */
    var islandOverlayWidthDp: Int
        get() = gi("island_overlay_w", 130)
        set(v) = si("island_overlay_w", v)

    /** 是否左右撑满（更接近系统岛连通挖孔的观感） */
    var islandOverlayFullWidth: Boolean
        get() = gb("island_overlay_full", false)
        set(v) = sb("island_overlay_full", v)

    /** 距屏幕顶部 dp */
    var islandOverlayTopDp: Int
        get() = gi("island_overlay_top", 8)
        set(v) = si("island_overlay_top", v)

    /** 停留时长 ms，超时自动消失 */
    var islandOverlayStayMs: Int
        get() = gi("island_overlay_stay", 5000)
        set(v) = si("island_overlay_stay", v)

    /** 文字大小 sp */
    var islandOverlayTextSp: Int
        get() = gi("island_overlay_text_sp", 13)
        set(v) = si("island_overlay_text_sp", v)

    /** 文字后面是否附带设备名 */
    var islandOverlayShowName: Boolean
        get() = gb("island_overlay_show_name", false)
        set(v) = sb("island_overlay_show_name", v)

    /** 岛背景色（默认接近系统岛的纯黑） */
    var islandOverlayBgColor: Int
        get() = gi("island_overlay_bg", -0x1000000)
        set(v) = si("island_overlay_bg", v)

    /**
     * 岛的会话倒计时分钟数（仅用于驱动岛渲染，不展示给用户）。
     * 小米的岛必须挂一个真实倒计时才会被系统当焦点通知处理。
     */
    var islandCountdownMinutes: Int
        get() = gi("island_countdown_min", 60)
        set(v) = si("island_countdown_min", v)

    /**
     * 上传历史：用户上传过的图片 / GIF 列表，JSON 数组字符串。
     *
     * 每项格式：{"p":"本地文件绝对路径","n":"显示名","t":时间戳,"g":是否GIF}
     * 只存**已复制到私有目录**的本地文件路径 —— content:// 会因授权失效而丢失，
     * 不适合进历史。
     */
    var mediaHistoryJson: String
        get() = gs("media_history", "[]") ?: "[]"
        set(v) = ss("media_history", v)

    /**
     * 换图版本号：每次更换 / 清除自定义图片都 +1。
     *
     * 加载侧用「URI + 版本号」作为复用判断依据，
     * 这样即便 URI 因故保持不变，也能强制丢弃旧图重新解码，
     * 避免"换了图片但预览 / 弹窗还显示旧图"。
     */
    var imageRevision: Int
        get() = gi("image_revision", 0)
        set(v) = si("image_revision", v)

    // ---------------- 横屏圆角 ----------------

    /**
     * 横屏卡片圆角（dp）。
     *
     * 【默认 -1 = 跟随竖屏设置】
     * 之前给了个固定默认值，结果横屏圆角跟用户自己调的竖屏圆角不一致，看着更别扭。
     * -1 表示直接用竖屏那套数值，保证两个朝向观感统一；想单独调再手动改。
     */
    var landCornerRadiusDp: Int
        get() = gi("land_corner_radius", -1)
        set(v) = si("land_corner_radius", v.coerceIn(-1, 200))

    // ---------------- 设备自定义名称 ----------------
    //
    // 系统蓝牙设置里改名后，BluetoothDevice.getName() 未必同步
    // （部分 ROM 要重启蓝牙 / 重新配对才生效，有的干脆不同步到第三方 App）。
    // 与其依赖系统，不如让用户在 App 里直接指定显示名 —— 按 MAC 单独存，
    // 多副耳机各自独立，改一台不影响另一台。

    private const val KEY_CUSTOM_NAME_PREFIX = "custom_name_"

    /** 取某台设备的自定义名；未设置返回 null */
    fun customName(address: String?): String? {
        if (address.isNullOrBlank()) return null
        return gs(KEY_CUSTOM_NAME_PREFIX + address.uppercase(), null)
            ?.takeIf { it.isNotBlank() }
    }

    /** 设置自定义名；传空白表示清除（回退到系统名） */
    fun setCustomName(address: String?, name: String?) {
        if (address.isNullOrBlank()) return
        val k = KEY_CUSTOM_NAME_PREFIX + address.uppercase()
        if (name.isNullOrBlank()) sp.edit().remove(k).apply()
        else ss(k, name.trim())
    }

    /** 所有已设置的自定义名（地址 -> 名称），供设置页列出 */
    fun allCustomNames(): List<Pair<String, String>> {
        if (!ready) return emptyList()
        return sp.all
            .filterKeys { it.startsWith(KEY_CUSTOM_NAME_PREFIX) }
            .mapNotNull { (k, v) ->
                val addr = k.removePrefix(KEY_CUSTOM_NAME_PREFIX)
                val name = v as? String
                if (name.isNullOrBlank()) null else addr to name
            }
            .sortedBy { it.first }
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

    /**
     * 卡片圆角（dp）。
     *
     * 默认从 28 调到 **16**：28 的圆角在弹窗这种小卡片上显得过胖，
     * 四个角吃掉的面积太大，两侧文字也容易被圆角弧线切到。
     * 16 更接近主流系统弹窗的观感。
     */
    var cornerRadiusDp: Int
        get() = gi("corner_radius", 16)
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
     * 【默认开启】这就是用户要的效果：文字区背后是图片的模糊延伸，
     * 顶部有渐隐过渡带，GIF 每前进一帧模糊就跟着重算。
     * 崩溃根因（前台服务超时杀进程）已在 1.4.1 修复，模糊本身不是崩溃来源，
     * 因此恢复默认开启。整个初始化另有 try/catch 兜底，算失败只退化成无模糊。
     */
    var landBlurEnabled: Boolean
        get() = gb("land_blur_enabled", true)
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
