package com.woshiyigemanhuajia.btpopup.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.AttributeSet
import android.util.Log
import android.view.View
import android.widget.ImageView
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import android.view.Choreographer
import android.graphics.RenderEffect
import android.os.Build

/**
 * 详情区「实时动态模糊层」（参考 ui_ref/2_弹窗渲染逻辑/LiveBlurView.java 的最终方案）。
 *
 * 【为什么必须是逐帧实时模糊】
 * 上一版把模糊源做成"一次性定格快照"，GIF 在动、背景却停在那一帧，
 * 看起来就是「贴了一张模糊过的死图」。参考实现的正确做法：
 * 直接引用源 ImageView 的同一个 Drawable，在 onDraw 里把它按偏移画出来
 * （只画底部对应区域），再叠加实时模糊 —— GIF 每前进一帧，模糊层跟着重算一次。
 *
 * 【动画同步】
 * 源 GIF 每帧变化会回调 Drawable.Callback.invalidateDrawable()。
 * 这里接管回调并【转发给原 callback（源 ImageView）】，同时限流 invalidate 自己
 * （~30fps）。直接 setCallback 会顶掉 ImageView 自己的回调导致源不刷新，
 * 所以必须保存原 callback 并转发。
 *
 * 【渲染方式】
 * 强制软件层（LAYER_TYPE_SOFTWARE）：本层只是一条约 100~200dp 的窄条，
 * 降采样 2 倍后做 StackBlur，开销可控；软件层下 saveLayer / PorterDuff /
 * BitmapShader 行为确定，不受 RenderNode / alpha layer 干扰
 * （参考实现验证过：硬件路径下圆角与合成"时有时无"）。
 *
 * 【兜底】
 * onDraw 任何异常都只退化成纯色底，绝不向上抛 —— 模糊属于视觉效果，
 * 不能把弹窗 / 整个 App 带崩。
 */
class LiveBlurView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        private const val TAG = "LiveBlur"

        /** 最小重绘间隔（ms）：把模糊重算限制在 ~30fps，降低 CPU 占用 */
        private const val MIN_REDRAW_MS = 33L

        /** 逐帧心跳间隔（ms）：约 12fps，足够让模糊"看起来在跟着动"，开销又可控 */
        private const val FRAME_MS = 80L
    }

    /** 绑定源 ImageView（弹窗里那张图 / GIF）——实时逐帧模糊的来源 */
    private var src: ImageView? = null

    /** 静态源位图（外部独立交付，作为图片尚未加载完成时的兜底模糊源） */
    private var sourceBitmap: Bitmap? = null

    private var blurRadiusPx = 26f
    private var dimColor = 0x66000000.toInt()
    private var fadeRatio = 0.45f
    private var bottomRadiusPx = 0f

    private val shaderPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fadePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val upscalePaint = Paint(Paint.FILTER_BITMAP_FLAG)

    private var fadeGradient: LinearGradient? = null
    private var cornerPath: Path? = null

    // ---- 动画回调接管（参考实现）----
    private var attachedDrawable: Drawable? = null
    private var originalCallback: Drawable.Callback? = null
    private var lastDrawAt = 0L

    /** 输出位图的实际尺寸（可能被分辨率上限压缩过），供 shader 矩阵放大回原尺寸 */
    private var outBufW = 0
    private var outBufH = 0

    /** 复用同一个矩阵对象，避免每次绘制都新建 */
    private val shaderMatrix = Matrix()

    /** 源 ImageView 的 Drawable 还没到位时的重绘重试计数（上限 80 次 ≈ 12 秒） */
    private var srcWaitTicks = 0

    /**
     * 源内容是否「缩放铺满本 View」。
     * true  = 整块模糊区都是图片的模糊延展（竖屏整卡毛玻璃用，图片四周留白也有模糊）
     * false = 只取源底部那一段并对齐到底边（横屏详情区用）
     */
    private var coverSource = false
    private val mainHandler = Handler(Looper.getMainLooper())

    // ---- StackBlur 缓冲区：尺寸变化时才重建（必须按 w/h 分别判断，见 ensureBuffers）----
    private var bufPix: IntArray? = null
    private var bufR: IntArray? = null
    private var bufG: IntArray? = null
    private var bufB: IntArray? = null
    private var bufA: IntArray? = null
    private var bufVmin: IntArray? = null
    private var bufDv: IntArray? = null
    private var bufStack: Array<IntArray>? = null

    private var bufW = 0
    private var bufH = 0

    /**
     * 逐帧驱动：跟着屏幕刷新（VSYNC）走，而不是按固定定时器。
     *
     * 【这才是"跟着 GIF 一起动"、且不与清晰区错帧的关键】
     *
     * 1) 只靠接管 Drawable.Callback 不可靠：源是 GIF 时帧回调未必能驱动本 View 重绘
     *    （Coil 的 crossfade、或动画由其它线程驱动时都可能收不到 invalidateDrawable），
     *    结果就是模糊层停在某一帧不动 —— 即"截取一帧固定住"。
     * 2) 但用固定间隔定时器（如 80ms ≈ 12fps）又会比 GIF / 屏幕刷新慢，
     *    模糊区落后清晰区一帧，看起来一卡一卡。
     *
     * 因此改用 Choreographer：每个 VSYNC 帧回调都 invalidate 一次，
     * onDraw 里重新采样源 Drawable 的**当前帧**再模糊。
     * 这样模糊区与清晰区在同一帧更新，不存在"慢一帧"。
     */
    private var liveTicking = false

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!liveTicking) return
            //
            // 【帧预算自适应】GIF 播到后面（帧尺寸大 / 系统负载高）时，
            // 每帧都重算模糊会拖垮主线程，表现就是"前面正常、后面卡顿"。
            // 这里按实测耗时动态跳帧：单帧越重，重算间隔越大，
            // 保证模糊永远不抢占 GIF 与 UI 的帧预算。
            //
            framesSeen++
            if (framesSeen >= skipEvery) {
                framesSeen = 0
                invalidate()
            }
            // 继续请求下一帧，形成逐帧循环
            try {
                Choreographer.getInstance().postFrameCallback(this)
            } catch (ignored: Throwable) {
                liveTicking = false
            }
        }
    }

    /** 跳帧计数：每隔 skipEvery 个 VSYNC 才重算一次模糊 */
    private var framesSeen = 0
    private var skipEvery = 1

    /** 根据上次模糊耗时动态调整跳帧倍率 */
    private fun adaptFrameBudget(costMs: Long) {
        val target = when {
            costMs > 24 -> 4
            costMs > 16 -> 3
            costMs > 10 -> 2
            costMs < 5 -> 1
            else -> skipEvery
        }
        // 变好时缓慢回落，避免来回抖动
        skipEvery = if (target < skipEvery) maxOf(target, skipEvery - 1) else target
    }

    /**
     * 动画期间暂停逐帧模糊。
     *
     * 【修复「横屏弹窗动画卡」】
     * 入场动画（位移 / 缩放 / 淡入）与模糊重算都挤在主线程上：
     * 动画每一帧都要跑一次 stackBlur，帧预算被吃光，动画自然一卡一卡。
     * 动画期间先停掉模糊，动画结束再恢复 —— 用户根本注意不到动画那几百毫秒里
     * 模糊是静止的，但能明显感觉到动画变顺了。
     */
    fun pauseForAnimation() {
        animPaused = true
        stopTicking()
    }

    fun resumeAfterAnimation() {
        animPaused = false
        if (attachedDrawable != null) startTicking()
        else invalidate()
    }

    private var animPaused = false

    private fun startTicking() {
        if (liveTicking || animPaused) return
        liveTicking = true
        try {
            Choreographer.getInstance().postFrameCallback(frameCallback)
        } catch (t: Throwable) {
            Log.w(TAG, "Choreographer 不可用，退定时器: " + t.message)
            mainHandler.post(tickFallback)
        }
    }

    private fun stopTicking() {
        liveTicking = false
        try {
            Choreographer.getInstance().removeFrameCallback(frameCallback)
        } catch (ignored: Throwable) {
        }
        mainHandler.removeCallbacks(tickFallback)
    }

    /** 极端情况下 Choreographer 不可用时的兜底（频率略低，但至少会动） */
    private val tickFallback = object : Runnable {
        override fun run() {
            if (!liveTicking) return
            invalidate()
            mainHandler.postDelayed(this, FRAME_MS)
        }
    }

    /** 降采样缓冲（2 倍：4 倍会把像素块拉得太大，放大回来是马赛克） */
    private var smallBuf: Bitmap? = null

    /** 最近一次算好的模糊位图：动画期间直接复用，避免每帧重算 */
    private var blurCache: Bitmap? = null
    private var smallCanvas: Canvas? = null

    /** 放大回原尺寸的模糊位图，供 BitmapShader 填充圆角形状 */
    private var outBuf: Bitmap? = null
    private var outCanvas: Canvas? = null

    //
    // 【用 GPU 做模糊，不再用 CPU 逐像素 —— 这是横屏 GIF 卡成 PPT 的根因】
    //
    // 原实现是"软件层 + 每帧 stackBlur"三重叠加：
    //   1) LAYER_TYPE_SOFTWARE：整个 View 每帧都要在 CPU 上重新光栅化成位图；
    //   2) 每帧 saveLayer 开一张离屏图层；
    //   3) stackBlur 在主线程逐像素运算。
    // GIF 每前进一帧就整套重跑一次，主线程帧预算被吃光 —— 表现为"卡成 PPT"。
    //
    // Android 12（API 31）起有 RenderEffect：模糊发生在 GPU 渲染管线里，
    // 本 View 只负责"把源画出来"，CPU 成本几乎为零，GIF 再快也不卡。
    // 用户的机器是 Android 16，必然走这条路径；低于 31 才回退到旧的 CPU 方案。
    //
    private val gpuBlur: Boolean = Build.VERSION.SDK_INT >= 31

    init {
        dimPaint.color = dimColor
        if (gpuBlur) {
            // 硬件层是使用 RenderEffect 的前提；同时省掉每帧 CPU 光栅化
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
            setClipToOutline(false)
            applyGpuBlur()
        } else {
            // 旧路径：软件层下 saveLayer / PorterDuff / BitmapShader 行为确定
            setLayerType(View.LAYER_TYPE_SOFTWARE, null)
            setClipToOutline(false)
        }
    }

    /** 把模糊半径交给 GPU 渲染管线 */
    private fun applyGpuBlur() {
        if (!gpuBlur) return
        try {
            // 半径上限 25（系统限制）；按 dp 值换算即可
            val r = blurRadiusPx.coerceIn(0f, 25f)
            setRenderEffect(
                if (r <= 0f) null
                else RenderEffect.createBlurEffect(r, r, Shader.TileMode.CLAMP)
            )
        } catch (t: Throwable) {
            Log.w(TAG, "RenderEffect 不可用，回退 CPU 模糊: " + t.message)
        }
    }

    // ------------------------------------------------------------------ 对外 API

    /**
     * 绑定源 ImageView（实时逐帧模糊）。源 drawable 每前进一帧（GIF），
     * 本层限流 ~30fps 跟着重算 —— 这是参考实现的核心行为。
     */
    fun setSource(source: ImageView?) {
        detachCallback()
        src = source
        srcWaitTicks = 0
        invalidate()
    }

    /**
     * 静态源位图兜底：源 ImageView 还没拿到 Drawable（图片加载中）时，
     * 用外部交付的小位图先铺一层模糊，避免详情区突然裸露。
     */
    fun setSourceBitmap(bmp: Bitmap?) {
        if (sourceBitmap === bmp) return
        sourceBitmap = bmp
        invalidate()
    }

    /**
     * 源内容是否缩放铺满整个本 View。
     * 竖屏整卡毛玻璃要 true（图片四周留白也能被模糊填满），横屏详情区保持 false。
     */
    fun setCoverSource(cover: Boolean) {
        if (coverSource == cover) return
        coverSource = cover
        invalidate()
    }

    fun hasSource(): Boolean = src?.drawable != null || sourceBitmap?.isRecycled == false

    fun setBlurRadius(px: Float) {
        blurRadiusPx = max(0f, px)
        // GPU 模式：半径变化要同步给 RenderEffect（CPU 模式下由绘制时读取）
        applyGpuBlur()
        invalidate()
    }

    /** 叠加的半透明色（argb），保证文字可读 */
    fun setDim(argb: Int) {
        dimColor = argb
        dimPaint.color = argb
        invalidate()
    }

    /** 设置底部两角圆角半径（px），与卡片圆角保持一致 */
    fun setBottomCornerRadius(px: Float) {
        bottomRadiusPx = max(0f, px)
        cornerPath = null
        invalidate()
    }

    /** 设置顶部渐隐带高度占比（0 = 硬边，1 = 整层渐隐） */
    fun setFadeRatio(r: Float) {
        fadeRatio = r.coerceIn(0f, 1f)
        fadeGradient = null
        invalidate()
    }

    // ------------------------------------------------------------------ 动画回调接管

    private fun detachCallback() {
        val d = attachedDrawable
        val cb = originalCallback
        if (d != null && cb != null) {
            try {
                d.callback = cb
            } catch (t: Throwable) {
                // ignore
            }
        }
        attachedDrawable = null
        originalCallback = null
        stopTicking()
    }

    private fun attachCallback(dr: Drawable) {
        attachedDrawable = dr
        originalCallback = dr.callback
        val source = src
        dr.callback = object : Drawable.Callback {
            override fun invalidateDrawable(who: Drawable) {
                // 关键：先让源继续刷新（否则 GIF 会停），再限流刷新自己
                originalCallback?.invalidateDrawable(who)
                val now = SystemClock.uptimeMillis()
                if (now - lastDrawAt >= MIN_REDRAW_MS) {
                    lastDrawAt = now
                    invalidate()
                }
            }

            override fun scheduleDrawable(who: Drawable, what: Runnable, `when`: Long) {
                val delay = (`when` - SystemClock.uptimeMillis()).coerceAtLeast(0L)
                val cb = originalCallback
                if (cb != null) {
                    cb.scheduleDrawable(who, what, `when`)
                } else if (source != null) {
                    source.postDelayed(what, delay)
                } else {
                    mainHandler.postDelayed(what, delay)
                }
            }

            override fun unscheduleDrawable(who: Drawable, what: Runnable) {
                val cb = originalCallback
                if (cb != null) {
                    cb.unscheduleDrawable(who, what)
                } else if (source != null) {
                    source.removeCallbacks(what)
                } else {
                    mainHandler.removeCallbacks(what)
                }
            }
        }
    }

    override fun onDetachedFromWindow() {
        stopTicking()
        detachCallback()
        super.onDetachedFromWindow()
    }

    // ------------------------------------------------------------------ 绘制

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // 尺寸变了必须丢弃旧圆角路径与渐变，否则会一直用第一次的尺寸绘制
        cornerPath = null
        fadeGradient = null
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // 兜底：模糊属于视觉效果，任何异常都只能退化成纯色，绝不能带崩弹窗 / App
        try {
            drawBlur(canvas)
        } catch (t: Throwable) {
            //
            // 修复「横屏状态的弹窗，文字模糊部分直接变黑了」：
            // 原来是 canvas.drawColor(dimColor)，而 dimColor 默认 0x66 = 40% 黑 ——
            // 横屏下模糊一旦算不出来（位图尺寸异常 / 分配失败 / 图层合成失败都会触发），
            // 整块文字区就被涂成一片黑，正是你看到的现象。
            //
            // 改成：把源画面【不模糊】直接画出来 —— 至少还是画面，
            // 再叠一层极淡压暗（10%）保证文字可读。绝不涂整块深色。
            Log.e(TAG, "模糊绘制失败，降级为不模糊画面", t)
            try {
                val iv = src
                val dr = iv?.drawable
                val sw = iv?.width ?: 0
                val sh = iv?.height ?: 0
                val vw = width
                val vh = height
                if (dr != null && sw > 0 && sh > 0 && vw > 0 && vh > 0) {
                    drawSource(canvas, dr, sw, sh, vw, vh)
                    canvas.drawColor(0x1A000000)
                } else {
                    canvas.drawColor(0x1A000000)
                }
            } catch (ignored: Throwable) {
            }
        }
    }

    private fun drawBlur(canvas: Canvas) {
        val vw = width
        val vh = height
        if (vw <= 0 || vh <= 0) return

        // 源优先级：源 ImageView 的当前 Drawable（实时逐帧）> 静态兜底位图
        val imageView = src
        val dr = imageView?.drawable
            ?: sourceBitmap?.takeIf { !it.isRecycled }?.let { BitmapDrawable(resources, it) }

        // 绑定后源换了 Drawable（换图 / GIF 加载完成），要重新接管回调
        if (dr != null && imageView != null && dr !== attachedDrawable) {
            detachCallback()
            attachCallback(dr)
            // 源到位：启动逐帧心跳，模糊开始跟着动
            startTicking()
        } else if (dr == null && attachedDrawable != null) {
            detachCallback()
            stopTicking()
        } else if (dr != null && attachedDrawable != null && !liveTicking) {
            // 已接管但心跳没跑（异常中断）：补一次
            startTicking()
        }

        //
        // 【修复】横屏模糊层"只截一帧、不会跟着动图动"。
        //
        // 根因：绑定模糊源时图片往往还没加载完，此刻 imageView.drawable 为 null，
        // 本方法会走到下面的 return 直接收工。而此后**再没有任何东西触发 invalidate** ——
        // 图片加载完成时只刷新 ImageView 自己，模糊层不会重绘，
        // 于是它永远停在"预解码的那一张静态位图"上（看起来就是截取一帧固定住）。
        //
        // 修法：源还没就绪时安排一次重绘重试，等 drawable 到位后
        // 上面那段 attachCallback 就会接管 GIF 回调，之后每帧自动重算。
        //
        if (dr == null || imageView == null) {
            if (src != null && srcWaitTicks++ < 80) {
                postInvalidateDelayed(150L)
            }
            return
        }
        srcWaitTicks = 0

        val sw = imageView.width
        val sh = imageView.height
        if (sw <= 0 || sh <= 0) return

        ensureCornerPath(vw, vh)
        val shape = cornerPath ?: Path().apply {
            addRect(0f, 0f, vw.toFloat(), vh.toFloat(), Path.Direction.CW)
        }

        //
        // 【动画期不重算 —— 这是横屏动画卡顿的真正根因】
        // 入场动画改变 scaleX / scaleY / alpha，系统会**自动** invalidate 本 View，
        // onDraw → drawBlur 就会跟着重跑一次 stackBlur。
        // 光停掉 Choreographer 心跳没用：那只是不再主动请求重绘，
        // 动画自身的重绘照样把模糊拖进来，帧预算照样被吃光。
        //
        // 因此动画期间直接复用上一次算好的模糊位图，只画不算。
        // 动画结束（resumeAfterAnimation）后再恢复逐帧重算。
        //
        val cached = blurCache
        if (animPaused && cached != null && !cached.isRecycled && !gpuBlur) {
            drawBlurredToCanvas(canvas, cached, shape)
            return
        }

        //
        // GPU 模式：模糊由 RenderEffect 完成，这里只负责把源画面画出来，
        // 不做任何 CPU 模糊运算 —— 每帧成本从"几十万像素 stackBlur"降到一次 draw。
        //
        val blurred: Bitmap? = if (gpuBlur) {
            null
        } else try {
            val t0 = SystemClock.uptimeMillis()
            val b = renderBlurredBitmap(dr, sw, sh, vw, vh)
            adaptFrameBudget(SystemClock.uptimeMillis() - t0)
            b
        } catch (t: Throwable) {
            Log.e(TAG, "生成模糊位图失败", t)
            null
        }

        if (blurred != null) blurCache = blurred
        drawBlurredContent(canvas, blurred, dr, sw, sh, vw, vh, shape)
    }

    /** 动画期：只把缓存位图画出来，不重算模糊 */
    private fun drawBlurredToCanvas(canvas: Canvas, cached: Bitmap, shape: Path) {
        drawBlurredContent(canvas, cached, null, 0, 0, width, height, shape)
    }

    /**
     * 把模糊内容（含压暗与顶部渐隐）画进图层。
     *
     * 正确顺序：开一个覆盖整个 View 的图层 → 把模糊内容和压暗画进去
     * → 再在同一个图层里用 DST_OUT 擦顶部（渐隐）。图层里有内容，擦除才真正生效。
     * 动画期复用缓存位图时走的是同一条绘制路径，观感一致。
     */
    private fun drawBlurredContent(
        canvas: Canvas,
        bmp: Bitmap?,
        fallbackDr: Drawable?,
        srcW: Int,
        srcH: Int,
        vw: Int,
        vh: Int,
        shape: Path
    ) {
        val saved = canvas.saveLayer(0f, 0f, vw.toFloat(), vh.toFloat(), null)
        try {
            if (bmp != null && !bmp.isRecycled) {
                val bsh = BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                // 输出位图被压缩过时，用矩阵放大回本 View 尺寸，避免模糊层只画了一小块
                if (outBufW > 0 && outBufH > 0 && (outBufW != vw || outBufH != vh)) {
                    shaderMatrix.reset()
                    shaderMatrix.setScale(vw.toFloat() / outBufW, vh.toFloat() / outBufH)
                    bsh.setLocalMatrix(shaderMatrix)
                }
                shaderPaint.shader = bsh
                canvas.drawPath(shape, shaderPaint)
            } else if (fallbackDr != null && srcW > 0 && srcH > 0) {
                // 模糊位图算不出来：直接画不模糊的源画面，绝不留一层压暗黑块
                try {
                    drawSource(canvas, fallbackDr, srcW, srcH, vw, vh)
                } catch (ignored: Throwable) {
                }
            }
            // 压暗画在圆角形状内（模糊失败时这就是可见的底板）
            canvas.drawPath(shape, dimPaint)

            if (fadeRatio > 0f) {
                val fh = vh * fadeRatio
                ensureGradient(vw, vh)
                if (fadeGradient != null && fh > 0f) {
                    canvas.drawRect(0f, 0f, vw.toFloat(), fh, fadePaint)
                }
            }
        } finally {
            canvas.restoreToCount(saved)
        }
    }

    /** 顶部渐隐：y=0 全擦除（透明，露出清晰画面）→ y=fh 不擦除（保留模糊层） */
    private fun ensureGradient(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        val fh = h * fadeRatio
        if (fh <= 0f) {
            fadeGradient = null
            return
        }
        if (fadeGradient == null) {
            fadeGradient = LinearGradient(
                0f, 0f, 0f, fh,
                0xFF000000.toInt(), 0x00000000,
                Shader.TileMode.CLAMP
            )
            fadePaint.shader = fadeGradient
            fadePaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
        }
    }

    /** 底部两角圆角、顶部两角直角的路径 */
    private fun ensureCornerPath(w: Int, h: Int) {
        if (bottomRadiusPx <= 0f || w <= 0 || h <= 0) {
            cornerPath = null
            return
        }
        if (cornerPath != null) return
        val r = min(bottomRadiusPx, min(w, h) / 2f)
        val p = Path()
        p.moveTo(0f, 0f)
        p.lineTo(w.toFloat(), 0f)
        p.lineTo(w.toFloat(), h - r)
        p.arcTo(RectF(w - 2 * r, h - 2 * r, w.toFloat(), h.toFloat()), 0f, 90f)
        p.lineTo(r, h.toFloat())
        p.arcTo(RectF(0f, h - 2 * r, 2 * r, h.toFloat()), 90f, 90f)
        p.close()
        cornerPath = p
    }

    // ------------------------------------------------------------------ 模糊流水线

    /**
     * 生成模糊位图（与 View 同尺寸，已模糊）。降采样倍数 2（参考实现）：
     * 4 倍像素块太大，放大回来是马赛克。
     */
    private fun renderBlurredBitmap(dr: Drawable, sw: Int, sh: Int, vw: Int, vh: Int): Bitmap {
        //
        // 【性能修复】降采样系数改为按尺寸自适应。
        // 模糊层铺满整卡后面积比原来（底部一小条）大十几倍，
        // 固定 factor=2 会让 stackBlur 在主线程上跑不动 —— 表现就是操作卡顿、随后 OOM 闪退。
        // 这里保证参与模糊的小图最长边不超过 ~200px：无论卡片多大，模糊运算量恒定。
        //
        //
        // 逐帧驱动后每帧都要算一次模糊（原来 12fps，现在跟随 VSYNC 最高 60fps），
        // 因此把参与运算的小图进一步缩小：像素数按平方下降，单帧耗时才能压进一帧预算。
        // 放大回来由 BitmapShader + FILTER_BITMAP 平滑，观感几乎无损。
        //
        val targetSmall = 150
        val factor = max(1, (max(vw, vh) + targetSmall - 1) / targetSmall).coerceAtMost(8)
        val bw = max(1, vw / factor)
        val bh = max(1, vh / factor)

        var sb = smallBuf
        if (sb == null || sb.width != bw || sb.height != bh) {
            sb = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
            smallBuf = sb
            smallCanvas = Canvas(sb)
        }
        val sc = smallCanvas!!
        sb.eraseColor(Color.TRANSPARENT)
        sc.save()
        sc.scale(1f / factor, 1f / factor)
        drawSource(sc, dr, sw, sh, vw, vh)
        sc.restore()

        // 半径按小图尺寸动态取（参考实现）：上限收紧到短边的 1/8，
        // 模糊依旧明显但保留结构，不会糊成一片纯色。
        // 半径按小图尺寸动态取（参考实现）：上限收紧到短边的 1/8，
        // 模糊依旧明显但保留结构，不会糊成一片纯色。
        // 除以 factor 让模糊强度不随降采样倍数变化 —— 卡片变大时观感保持一致。
        val maxR = max(4, min(bw, bh) / 8)
        val radius = min(maxR, max(3, (blurRadiusPx / factor / 2f).roundToInt()))
        try {
            stackBlur(sb, radius)
        } catch (t: Throwable) {
            Log.e(TAG, "stackBlur 失败 r=$radius small=${bw}x$bh", t)
            try {
                stackBlur(sb, 6)
            } catch (t2: Throwable) {
                Log.e(TAG, "stackBlur 兜底也失败", t2)
            }
        }

        //
        // 【内存修复】输出位图也设分辨率上限。
        // 整卡模糊时全尺寸位图（例：1100×1650）一次就要 7MB+，反复重建会 OOM 闪退。
        // 这里限制像素总量，绘制时靠 shader 矩阵放大回去，观感几乎无损。
        //
        val maxOutPixels = 900_000
        var outScale = 1f
        while (vw * vh * outScale * outScale > maxOutPixels && outScale > 0.25f) {
            outScale *= 0.75f
        }
        val ow = max(1, (vw * outScale).toInt())
        val oh = max(1, (vh * outScale).toInt())

        var ob = outBuf
        if (ob == null || ob.width != ow || ob.height != oh) {
            ob = Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888)
            outBuf = ob
            outCanvas = Canvas(ob)
            outBufW = ow
            outBufH = oh
        }
        val oc = outCanvas!!
        ob.eraseColor(Color.TRANSPARENT)
        oc.save()
        // sb(bw×bh) 放大到 ow×oh：缩放比 = ow / bw = factor × outScale
        val up = factor.toFloat() * outScale
        oc.scale(up, up)
        oc.drawBitmap(sb, 0f, 0f, upscalePaint)
        oc.restore()
        return ob
    }

    /**
     * 把源内容画出来，只显示其底部 vh 高度的那一段。
     *
     * 用 src.getImageMatrix() 保证与 ImageView 屏幕上的显示完全一致；
     * 矩阵还没就绪时现算 centerCrop 兜底，保证底部区域一定有内容。
     * Drawable bounds 只在本层画布上临时改写、finally 里还原，不污染 ImageView 自身绘制。
     */
    private fun drawSource(canvas: Canvas, dr: Drawable, sw: Int, sh: Int, vw: Int, vh: Int) {
        val imageView = src ?: return
        val oldBounds = Rect(dr.bounds)
        canvas.save()
        try {
            if (coverSource) {
                //
                // 【铺满模式】把源内容按 centerCrop 语义缩放，覆盖整个本 View。
                //
                // 模糊层铺满整张卡片时（竖屏），如果还沿用「底部对齐」，
                // 源会被整体下推、只有底部一小条落在可见范围内 —— 表现就是
                // 「只有下方文字那块有模糊，图片四周的留白是空的」。
                // 铺满后整卡背景都是图片的模糊延展，图片区被不透明原图盖住，
                // 露出来的四周留白自然也是毛玻璃。
                //
                var iw = dr.intrinsicWidth
                var ih = dr.intrinsicHeight
                if (iw <= 0 || ih <= 0) {
                    iw = sw
                    ih = sh
                }
                val scale = max(vw.toFloat() / iw, vh.toFloat() / ih)
                canvas.translate((vw - iw * scale) / 2f, (vh - ih * scale) / 2f)
                canvas.scale(scale, scale)
                dr.setBounds(0, 0, iw, ih)
                dr.draw(canvas)
                return
            }

            // 平移 -(sh - vh)：源的底部对齐到本 View 的位置
            canvas.translate(0f, -(sh - vh).toFloat())
            val m: Matrix? = imageView.imageMatrix
            if (m != null && !m.isIdentity) {
                canvas.concat(m)
            } else {
                var iw = dr.intrinsicWidth
                var ih = dr.intrinsicHeight
                if (iw <= 0 || ih <= 0) {
                    iw = sw
                    ih = sh
                }
                val scale = max(sw.toFloat() / iw, sh.toFloat() / ih)
                canvas.scale(scale, scale)
                canvas.translate((sw - iw * scale) / 2f / scale, (sh - ih * scale) / 2f / scale)
            }
            var iw = dr.intrinsicWidth
            var ih = dr.intrinsicHeight
            if (iw <= 0 || ih <= 0) {
                iw = sw
                ih = sh
            }
            dr.setBounds(0, 0, iw, ih)
            dr.draw(canvas)
        } finally {
            dr.bounds = oldBounds
            canvas.restore()
        }
    }

    /** 按需分配（并复用）stackBlur 所需缓冲区：必须按 w 和 h 分别判断 */
    private fun ensureBuffers(w: Int, h: Int) {
        if (bufW == w && bufH == h && bufPix != null) return
        val wh = w * h
        bufPix = IntArray(wh)
        bufR = IntArray(wh)
        bufG = IntArray(wh)
        bufB = IntArray(wh)
        bufA = IntArray(wh)
        // vmin 在水平 pass 按列 x 索引、垂直 pass 按行 y 索引，
        // 长度必须容纳 max(w, h)；只比 w*h 会在"面积相同、宽高比不同"时越界闪退
        bufVmin = IntArray(max(w, h) + 1)
        bufDv = null
        bufStack = null
        bufW = w
        bufH = h
    }

    /** Mario Klingemann StackBlur：真高斯近似，缓冲区全部缓存复用 */
    private fun stackBlur(bmp: Bitmap, radiusIn: Int) {
        val w = bmp.width
        val h = bmp.height
        if (w <= 0 || h <= 0 || radiusIn < 1) return
        val radius = min(radiusIn, max(1, min(w, h) / 2))

        ensureBuffers(w, h)
        val pix = bufPix!!
        val r = bufR!!
        val g = bufG!!
        val b = bufB!!
        val a = bufA!!
        val vmin = bufVmin!!
        bmp.getPixels(pix, 0, w, 0, 0, w, h)

        val wm = w - 1
        val hm = h - 1
        val div = radius * 2 + 1
        val divsum = ((div + 1) shr 1) * ((div + 1) shr 1)
        val r1 = radius + 1

        var dv = bufDv
        if (dv == null || dv.size < 256 * divsum) {
            dv = IntArray(256 * divsum)
            bufDv = dv
        }
        val dvv = dv
        for (i in 0 until 256 * divsum) dvv[i] = i / divsum

        var stack = bufStack
        if (stack == null || stack.size < div) {
            stack = Array(max(div, 16)) { IntArray(4) }
            bufStack = stack
        }
        val st = stack

        // ---- 水平方向 ----
        var yw = 0
        var yi = 0
        for (y in 0 until h) {
            var rinsum = 0
            var ginsum = 0
            var binsum = 0
            var ainsum = 0
            var routsum = 0
            var goutsum = 0
            var boutsum = 0
            var aoutsum = 0
            var rsum = 0
            var gsum = 0
            var bsum = 0
            var asum = 0

            for (i in -radius..radius) {
                val p = pix[yi + min(wm, max(i, 0))]
                val sir = st[i + radius]
                sir[0] = (p shr 16) and 0xff
                sir[1] = (p shr 8) and 0xff
                sir[2] = p and 0xff
                sir[3] = (p ushr 24) and 0xff
                val rbs = r1 - abs(i)
                rsum += sir[0] * rbs
                gsum += sir[1] * rbs
                bsum += sir[2] * rbs
                asum += sir[3] * rbs
                if (i > 0) {
                    rinsum += sir[0]
                    ginsum += sir[1]
                    binsum += sir[2]
                    ainsum += sir[3]
                } else {
                    routsum += sir[0]
                    goutsum += sir[1]
                    boutsum += sir[2]
                    aoutsum += sir[3]
                }
            }

            var stackpointer = radius
            for (x in 0 until w) {
                r[yi] = dvv[rsum]
                g[yi] = dvv[gsum]
                b[yi] = dvv[bsum]
                a[yi] = dvv[asum]

                rsum -= routsum
                gsum -= goutsum
                bsum -= boutsum
                asum -= aoutsum

                val stackstart = stackpointer - radius + div
                var sir = st[stackstart % div]

                routsum -= sir[0]
                goutsum -= sir[1]
                boutsum -= sir[2]
                aoutsum -= sir[3]

                // 水平 pass 里 vmin 按【列 x】索引（值是行内偏移），只在 y==0 时初始化整行
                if (y == 0) vmin[x] = min(x + r1, wm)

                val p = pix[yw + vmin[x]]
                sir[0] = (p shr 16) and 0xff
                sir[1] = (p shr 8) and 0xff
                sir[2] = p and 0xff
                sir[3] = (p ushr 24) and 0xff

                rinsum += sir[0]
                ginsum += sir[1]
                binsum += sir[2]
                ainsum += sir[3]

                rsum += rinsum
                gsum += ginsum
                bsum += binsum
                asum += ainsum

                stackpointer = (stackpointer + 1) % div
                sir = st[stackpointer % div]

                routsum += sir[0]
                goutsum += sir[1]
                boutsum += sir[2]
                aoutsum += sir[3]

                rinsum -= sir[0]
                ginsum -= sir[1]
                binsum -= sir[2]
                ainsum -= sir[3]

                yi++
            }
            yw += w
        }

        // ---- 垂直方向 ----
        for (x in 0 until w) {
            var rinsum = 0
            var ginsum = 0
            var binsum = 0
            var ainsum = 0
            var routsum = 0
            var goutsum = 0
            var boutsum = 0
            var aoutsum = 0
            var rsum = 0
            var gsum = 0
            var bsum = 0
            var asum = 0
            var yp = -radius * w

            for (i in -radius..radius) {
                val index = max(0, yp) + x
                val sir = st[i + radius]
                sir[0] = r[index]
                sir[1] = g[index]
                sir[2] = b[index]
                sir[3] = a[index]
                val rbs = r1 - abs(i)
                rsum += r[index] * rbs
                gsum += g[index] * rbs
                bsum += b[index] * rbs
                asum += a[index] * rbs
                if (i > 0) {
                    rinsum += sir[0]
                    ginsum += sir[1]
                    binsum += sir[2]
                    ainsum += sir[3]
                } else {
                    routsum += sir[0]
                    goutsum += sir[1]
                    boutsum += sir[2]
                    aoutsum += sir[3]
                }
                if (i < hm) yp += w
            }

            yi = x
            var stackpointer = radius
            for (y in 0 until h) {
                pix[yi] = (dvv[asum] shl 24) or (dvv[rsum] shl 16) or (dvv[gsum] shl 8) or dvv[bsum]

                rsum -= routsum
                gsum -= goutsum
                bsum -= boutsum
                asum -= aoutsum

                val stackstart = stackpointer - radius + div
                var sir = st[stackstart % div]

                routsum -= sir[0]
                goutsum -= sir[1]
                boutsum -= sir[2]
                aoutsum -= sir[3]

                if (x == 0) vmin[y] = min(y + r1, hm) * w

                val p = x + vmin[y]
                sir[0] = r[p]
                sir[1] = g[p]
                sir[2] = b[p]
                sir[3] = a[p]

                rinsum += sir[0]
                ginsum += sir[1]
                binsum += sir[2]
                ainsum += sir[3]

                rsum += rinsum
                gsum += ginsum
                bsum += binsum
                asum += ainsum

                stackpointer = (stackpointer + 1) % div
                sir = st[stackpointer % div]

                routsum += sir[0]
                goutsum += sir[1]
                boutsum += sir[2]
                aoutsum += sir[3]

                rinsum -= sir[0]
                ginsum -= sir[1]
                binsum -= sir[2]
                ainsum -= sir[3]

                yi += w
            }
        }

        bmp.setPixels(pix, 0, w, 0, 0, w, h)
    }
}
