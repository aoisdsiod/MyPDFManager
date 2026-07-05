package com.example.pdfmanager.ui.screen.reader

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import com.example.pdfmanager.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.LinkedHashMap
import kotlin.math.min

/**
 * PDF 连续滚动画布视图
 *
 * ===== 功能概述 =====
 * 本视图实现 PDF 的连续滚动阅读模式，所有页面垂直排列，
 * 用户可以通过单指拖动浏览上下页、双指缩放。采用整页纹理缓存
 * 机制，异步渲染 PDF 页面为 Bitmap 并缓存，仅渲染可见区域及
 * 其前后页以优化性能和内存。
 *
 * ===== 核心机制 =====
 * 1. 集成 ContinuousCanvasState 管理页面布局和状态
 * 2. 使用 LRU 缓存（LinkedHashMap）管理已渲染的页面 Bitmap
 * 3. 后台预渲染：当前页 + 前3页 + 后5页，按优先级队列提交
 * 4. 限制并发渲染任务数（最多2个），防止 OOM
 * 5. 通过 GestureHandler 处理滚动、缩放、惯性滑动、双击等手势
 * 6. 自动跟随系统深色/浅色模式切换背景色
 *
 * ===== 调用位置 =====
 * - 创建实例：ReaderScreenV2.kt 第 132 行：PdfContinuousView(ctx)
 * - 初始化：ReaderScreenV2.kt 第 137 行：view.init(viewModel.pdfDocumentRepository)
 * - 设置协程：ReaderScreenV2.kt 第 135 行：view.setCoroutineScope(coroutineScope)
 * - 注册回调：ReaderScreenV2.kt 第 139-143 行（onPageChanged/onDoubleTap）
 * - 跳转页面：ReaderScreenV2.kt 第 147 行：view.scrollToPage(targetPage)
 *
 * ===== 使用场景 =====
 * 当 pageMode == "continuous" 时，ReaderScreenV2 使用此视图
 * 替代 SinglePageView。用户以连续滚动方式阅读 PDF。
 */
class PdfContinuousView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /**
     * 协程作用域（必须由外部通过 setCoroutineScope() 传入）
     *
     * 使用场景：用于启动 PDF 页面渲染的异步协程任务。
     * 必须传入生命周期感知的 scope（如 viewModelScope），
     * 以确保视图销毁时渲染协程能自动取消。
     *
     * 调用位置：ReaderScreenV2.kt 第 135 行传入 coroutineScope
     */
    private lateinit var scope: CoroutineScope

    companion object {
        /** 日志标签，用于调试输出 */
        private const val TAG = "PdfContinuousView"
    }

    /**
     * 构造函数 init 块
     *
     * 功能：设置视图为可点击、可聚焦，确保能接收触摸事件。
     * (Android 中 View 默认不可点击，需要手动启用才能响应触摸)
     */
    init {
        isClickable = true
        isFocusable = true
        Log.d(TAG, "Constructor: View created")
    }

    /**
     * 连续画布状态管理器
     *
     * 功能：管理页面布局（1x 坐标系下的页面位置和尺寸）、
     * 缩放比例、滚动偏移等状态数据。
     *
     * 类型：ContinuousCanvasState 定义在 ContinuousCanvasState.kt
     * 创建位置：本文件的 doInit() 方法中
     */
    private var canvasState: ContinuousCanvasState? = null

    // ===== LRU 页面纹理缓存（内存限制 64MB）=====

    /**
     * 最大缓存容量（字节）：64MB
     *
     * 用于限制 pageTextures 缓存的总大小，当缓存超出此限制时，
     * 会自动移除最久未访问的位图以腾出空间。
     */
    private val maxCacheSizeBytes = 64 * 1024 * 1024  // 64MB

    /**
     * 页面纹理缓存（LRU 淘汰策略）
     *
     * 功能：缓存已渲染完成的 PDF 页面 Bitmap，避免重复渲染。
     * key = 页面索引（0-based），value = 渲染后的 Bitmap。
     *
     * 实现：继承 LinkedHashMap，设置 accessOrder=true（访问顺序排序），
     * 使得最近访问的条目在末尾，最久未访问的在头部。
     * 重写 put/remove 方法以跟踪当前缓存占用的总字节数，
     * 在超出 maxCacheSizeBytes 时自动淘汰最旧条目并回收 Bitmap。
     *
     * 调用位置：
     * - onDraw() 中读取缓存绘制页面
     * - requestPageRender() 中写入缓存
     * - cleanup() 中清空所有缓存
     */
    private val pageTextures = object : LinkedHashMap<Int, Bitmap>(16, 0.75f, true) {
        /** 当前缓存占用的总字节数 */
        private var currentSizeBytes = 0L

        /**
         * 放入缓存（重写 LinkedHashMap.put）
         *
         * 功能：将页面 Bitmap 存入缓存，同时更新已用字节数。
         * 如果旧值存在，先减去旧值大小。写入后检查是否超出限制，
         * 超出则从头部（最久未访问）开始移除直到符合限制。
         *
         * @param key   页面索引（0-based）
         * @param value 页面 Bitmap
         * @return 旧的 Bitmap（如果存在），否则 null
         */
        override fun put(key: Int, value: Bitmap): Bitmap? {
            val oldValue = super.put(key, value)
            if (oldValue != null) {
                currentSizeBytes -= oldValue.allocationByteCount
            }
            currentSizeBytes += value.allocationByteCount

            // 超出限制时移除最旧的条目（缩放中跳过淘汰，保护可见页）
            if (!isZooming) {
                while (currentSizeBytes > maxCacheSizeBytes && isNotEmpty()) {
                    val oldestKey = keys.iterator().next()
                    val oldestBitmap = remove(oldestKey)
                    if (oldestBitmap != null) {
                        currentSizeBytes -= oldestBitmap.allocationByteCount
                        oldestBitmap.recycle()
                    }
                }
            }
            return oldValue
        }

        /**
         * 移除缓存（重写 LinkedHashMap.remove）
         *
         * 功能：从缓存中移除指定页面的 Bitmap，并减去其占用的字节数。
         *
         * @param key 页面索引
         * @return 被移除的 Bitmap，或 null
         */
        override fun remove(key: Int): Bitmap? {
            val value = super.remove(key)
            if (value != null) {
                currentSizeBytes -= value.allocationByteCount
            }
            return value
        }

        /**
         * 清空所有缓存
         *
         * 功能：遍历回收所有 Bitmap 并清空缓存，重置字节计数为 0。
         *
         * 调用位置：cleanup() 方法
         * 使用场景：视图清理销毁时释放所有纹理内存
         */
        fun clearAll() {
            for ((_, bitmap) in this) {
                bitmap.recycle()
            }
            clear()
            currentSizeBytes = 0
        }
    }

    /**
     * 正在执行的渲染任务映射表
     *
     * 功能：跟踪当前正在进行的页面渲染协程，防止同一页面
     * 被多次重复渲染。key = 页面索引，value = 协程 Job。
     *
     * 使用场景：requestPageRender() 中提交新任务前先检查此映射，
     * 如果任务已存在则跳过；任务完成或失败后从映射中移除。
     */
    private val renderJobs = mutableMapOf<Int, Job>()

    /** renderJobs 的同步锁对象，用于线程安全操作 */
    private val renderJobsLock = Any()

    /**
     * 最大并发渲染任务数
     *
     * 限制同时进行的后台渲染协程数量，防止大量渲染请求
     * 同时执行导致内存压力过大或性能问题。
     */
    private val maxConcurrentRenderJobs = 2

    /**
     * 手势处理器（连续画布手势处理）
     *
     * 功能：处理所有触摸手势，包括单指拖动、双指缩放、
     * 惯性滑动（fling）、双击等。
     *
     * 类型：ContinuousCanvasGestureHandler，定义在：
     * ContinuousCanvasGestureHandler.kt
     *
     * 创建位置：本文件的 doInit() 方法中
     * 调用位置：onTouchEvent() 中分发触摸事件到此处理器
     */
    private var gestureHandler: ContinuousCanvasGestureHandler? = null

    /**
     * 当前缩放比例
     *
     * 范围：[minScale, maxScale]，初始值为 minScale。
     * 外部可读，内部可写。缩放操作时通过手势处理器更新此值。
     *
     * 使用场景：onDraw() 中计算页面绘制尺寸、getRenderSize() 中计算渲染分辨率
     */
    var currentScale: Float = 1.0f
        private set

    /**
     * 最小缩放比例
     *
     * 由 ContinuousCanvasState.minScale 决定，含义为
     * 内容宽度正好填满屏幕宽度时的缩放值。
     *
     * 使用场景：限制用户缩小操作的下限
     */
    var minScale: Float = 1.0f
        private set

    /** 最大缩放比例（固定为 4.0f） */
    val maxScale: Float = 4.0f

    /**
     * 页面间距（1x 坐标系下，单位像素）
     *
     * 每两页之间的垂直间隔，传递给 ContinuousCanvasState 使用。
     */
    private val pageSpacing = 20

    /**
     * 绘制 Bitmap 的画笔
     *
     * 配置了抗锯齿和位图滤镜，使页面缩放时边缘更平滑。
     * 在 onDraw() 中用于 canvas.drawBitmap() 调用。
     */
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    /**
     * 页面容器背景画笔
     *
     * 功能：绘制每个页面容器（container）的纯色背景。
     * 背景色固定为白色（android.R.color.white），
     * 与系统主题背景色（getSystemBackgroundColor()）不同。
     * 此背景填充了容器中未被 PDF 内容覆盖的区域。
     */
    private val backgroundPaint = Paint().apply {
        color = ContextCompat.getColor(context, android.R.color.white)
    }

    /**
     * 页面变化回调（由外部注册）
     *
     * 功能：当用户滚动导致顶部可见页面变化时触发，
     * 通知外部（ReaderScreenV2）更新页码显示。
     *
     * 参数：新页面的索引（0-based）
     *
     * 注册位置：ReaderScreenV2.kt 第 139 行
     * 调用位置：onDraw() 中检测到 lastReportedPage 变化时调用
     */
    var onPageChanged: ((Int) -> Unit)? = null

    /**
     * 滚动进度变化回调（由外部注册）
     *
     * 功能：当用户滚动时触发，传递当前滚动进度（0.0 ~ 1.0）。
     *
     * 参数：滚动进度值（0.0 = 顶部，1.0 = 底部）
     *
     * 使用场景：可用于显示阅读进度条或其他 UI 元素
     * 调用位置：onDraw() 中每次绘制时计算并调用
     */
    var onScrollProgressChanged: ((Float) -> Unit)? = null

    /**
     * 双击回调（由外部注册）
     *
     * 功能：用户双击视图时触发，传递双击位置的屏幕坐标。
     *
     * 参数 (x, y)：双击点的屏幕坐标
     *
     * 注册位置：ReaderScreenV2.kt 第 142 行，用于切换工具栏显示/隐藏
     * 调用位置：ContinuousCanvasGestureHandler 的双击回调链中
     */
    var onDoubleTap: ((Float, Float) -> Unit)? = null

    /**
     * 视图是否已完成初始化
     *
     * 功能：标记视图是否已经成功调用 doInit() 完成初始化。
     * 外部可读，仅内部可写。在 onDraw()、onTouchEvent() 等
     * 方法中作为前置检查条件。
     *
     * 使用场景：ReaderScreenV2 通过此标志判断是否需要调用 view.init()
     */
    var isInitialized = false
        private set

    // ===== 预渲染频率限制优化 =====

    /**
     * 上次预渲染的时间戳（毫秒）
     *
     * 功能：用于限制预渲染调用的频率，避免短时间内
     * 重复触发大量渲染请求。
     */
    private var lastPrerenderTime = 0L

    /** 是否有待处理的预渲染请求 */
    private var pendingPrerender = false

    /** 上次记录的可见页面集合（用于优化，避免重复预渲染） */
    private var lastVisiblePages: Set<Int> = emptySet()

    // ===== 页面变化回调去重优化 =====

    /**
     * 上次报告的页面索引（用于去重 onPageChanged 回调）
     *
     * 功能：防止在连续滚动中同一页面多次触发 onPageChanged 回调，
     * 仅在页面实际变化时才通知外部。
     */
    private var lastReportedPage = -1

    /**
     * 待处理的跳转页面（初始化期间缓存）
     *
     * 功能：如果在视图初始化完成前调用了 scrollToPage()，
     * 将目标页码缓存在此，等 doInit() 完成后自动执行跳转。
     *
     * 使用场景：ReaderScreenV2 在 update 块中调用 scrollToPage()
     * 可能早于视图初始化完成。
     */
    private var pendingScrollToPage: Int? = null

    /** 缩放操作时的基准焦点 X 坐标（1x 坐标系） */
    private var baseFocusX1x: Float = 0f

    /** 缩放操作时的基准焦点 Y 坐标（1x 坐标系） */
    private var baseFocusY1x: Float = 0f

    /** 是否正在执行缩放操作 */
    private var isScaling = false

    /** 缩放下 LRU 缓存保护标志（与 isScaling 同步） */
    private var isZooming = false

    // ===== 延迟初始化机制 =====

    /**
     * 待处理的 PdfDocumentRepository（延迟初始化使用）
     *
     * 功能：如果在 onSizeChanged() 之前调用了 init()，
     * 将仓库引用暂存于此，等 onSizeChanged() 触发视图布局后
     * 再用 doInit() 完成真正的初始化。
     *
     * 使用场景：解决 init() 在 onSizeChanged() 之前被调用
     * 导致 width/height 为 0 的问题。
     */
    private var pendingPdfDocumentRepository: PdfDocumentRepository? = null

    /** 待处理的缓存大小（与 pendingPdfDocumentRepository 配合使用） */
    private var pendingCacheSize: Int = 64 * 1024 * 1024

    /**
     * 设置协程作用域
     *
     * 功能：外部（ReaderScreenV2）传入生命周期感知的 CoroutineScope，
     * 用于启动页面渲染协程。必须在 init() 之前调用。
     *
     * 调用位置：ReaderScreenV2.kt 第 135 行
     * 使用场景：在 AndroidView 的 update 块中，视图创建后立即调用
     *
     * @param scope 协程作用域（如 viewModelScope），必须支持生命周期感知
     */
    fun setCoroutineScope(scope: CoroutineScope) {
        this.scope = scope
    }

    /**
     * 初始化视图（公开入口）
     *
     * 功能：初始化 PDF 连续滚动视图。首先检查文档是否可用，
     * 然后检查视图是否已完成布局（width > 0 && height > 0）。
     * 如果未布局则延迟到 onSizeChanged() 执行；否则立即执行 doInit()。
     *
     * 调用位置：ReaderScreenV2.kt 第 137 行
     * 使用场景：当 isDocumentAvailable 为 true 且 !view.isInitialized 时调用
     *
     * @param pdfDocumentRepository PDF 文档仓库实例（统一管理 PdfRenderer 生命周期）
     * @param cacheSize            缓存大小（字节），默认 64MB
     */
    fun init(pdfDocumentRepository: PdfDocumentRepository, cacheSize: Int = 64 * 1024 * 1024) {
        Log.d(TAG, "init() called, width=$width, height=$height")

        // 检查文档是否可用
        if (!pdfDocumentRepository.isDocumentAvailable()) {
            Log.e(TAG, "init() failed: Document not available")
            return
        }

        // 延迟初始化：如果视图尚未布局（width=0 或 height=0），等待 onSizeChanged() 触发
        if (width <= 0 || height <= 0) {
            Log.w(TAG, "init() delayed: width=$width, height=$height")
            pendingPdfDocumentRepository = pdfDocumentRepository
            pendingCacheSize = cacheSize
            return
        }

        doInit(pdfDocumentRepository, cacheSize)
    }

    /**
     * 实际初始化逻辑
     *
     * 功能：创建 ContinuousCanvasState 和 ContinuousCanvasGestureHandler，
     * 计算初始缩放比例，注册手势回调，处理缓存的跳转请求，启动预渲染。
     *
     * 调用位置：
     * - init() 方法中调用（width/height 已有效时）
     * - onSizeChanged() 中调用（延迟初始化场景）
     *
     * 使用场景：确保 width 和 height 已有效时执行真正的初始化工作
     *
     * @param pdfDocumentRepository PDF 文档仓库
     * @param cacheSize            缓存大小（字节）
     */
    private fun doInit(pdfDocumentRepository: PdfDocumentRepository, cacheSize: Int) {
        Log.d(TAG, "doInit() with size=${width}x$height")

        if (!::scope.isInitialized) {
            Log.e(TAG, "CoroutineScope not set! Call setCoroutineScope() before init()")
            return
        }

        // 创建状态管理器，传入页面间距
        canvasState = ContinuousCanvasState(pdfDocumentRepository, width, height, pageSpacing)
        minScale = canvasState?.minScale ?: 1.0f
        currentScale = minScale
        Log.d(TAG, "init() state created, minScale=$minScale, totalPages=${canvasState?.totalPages}")

        // 创建手势处理器，注册所有手势回调
        gestureHandler = ContinuousCanvasGestureHandler(
            context = context,
            // 滚动回调：视图失效触发重绘（使用 postInvalidateOnAnimation 优化性能）
            onScroll = { _, _ -> postInvalidateOnAnimation() },
            // 缩放回调：保持缩放焦点位置不变，更新滚动偏移和缩放比例
            // 缩放回调：保持缩放焦点位置不变，批量更新滚动和缩放
            onScale = { scaleFactor, focusX, focusY ->
                val handler = gestureHandler ?: return@ContinuousCanvasGestureHandler
                val state = canvasState ?: return@ContinuousCanvasGestureHandler

                if (isScaling) {
                    val newScale = handler.scale
                    handler.scrollX = focusX - baseFocusX1x * newScale
                    handler.scrollY = focusY - baseFocusY1x * newScale

                    currentScale = newScale

                    // 批量更新滚动和缩放（一次 clamp + updateVisiblePages，避免闪屏）
                    state.updateScrollAndScale(handler.scrollX, handler.scrollY, currentScale)
                    handler.scrollX = state.scrollX
                    handler.scrollY = state.scrollY

                    updateGestureHandlerDimensions()
                    postInvalidateOnAnimation()
                } else {
                    // 缩放开始：记录焦点 + 进入 LRU 缓存保护
                    isScaling = true
                    isZooming = true
                    baseFocusX1x = (focusX - handler.scrollX) / currentScale
                    baseFocusY1x = (focusY - handler.scrollY) / currentScale
                }
            },
            // 缩放结束：退出缓存保护，立即触发预渲染
            onScaleEnd = {
                isScaling = false
                isZooming = false
                prerenderCurrentPage()
            },
            // 惯性滑动回调
            onFling = { _, _ -> invalidate() },
            // 单击回调（当前为空操作）
            onSingleTap = { _, _ -> },
            // 双击回调：传递给外部注册的 onDoubleTap
            onDoubleTap = { x, y -> onDoubleTap?.invoke(x, y) }
        )

        gestureHandler?.setScaleRange(minScale, maxScale)
        updateGestureHandlerDimensions()
        isInitialized = true

        // 补执行初始化期间缓存的跳转请求
        pendingScrollToPage?.let { page ->
            pendingScrollToPage = null
            scrollToPage(page)
        }

        // 启动当前页及其邻居的预渲染
        prerenderCurrentPage()
        invalidate()
    }

    /**
     * 视图绘制方法（重写 View.onDraw）
     *
     * 功能：绘制当前可见的所有 PDF 页面。
     * 1. 绘制视图背景色（跟随系统深色/浅色模式）
     * 2. 计算当前可见页面列表
     * 3. 对每个可见页面：绘制容器背景、绘制页面纹理（Bitmap）
     *    （如纹理未就绪则绘制灰色占位符并发起渲染请求）
     * 4. 检测页面变化，触发 onPageChanged 回调
     * 5. 计算并触发 onScrollProgressChanged 回调
     * 6. 调度延迟预渲染
     *
     * 调用位置：由 Android 绘制系统自动调用（硬件 vsync 信号）
     *
     * @param canvas Android 画布对象，用于绘制所有图形
     */
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!isInitialized) {
            Log.w(TAG, "onDraw() called but isInitialized=false, width=$width, height=$height")
            return
        }
        val state = canvasState ?: return
        val handler = gestureHandler ?: return

        // 检查文档是否可用
        if (!state.getPdfDocumentRepository().isDocumentAvailable()) {
            Log.w(TAG, "onDraw() called but document not available")
            return
        }

        Log.v(TAG, "onDraw() scale=$currentScale, scroll=(${handler.scrollX}, ${handler.scrollY})")
        val scrollX = handler.scrollX
        val scrollY = handler.scrollY
        val scale = state.currentScale

        // 绘制视图背景（白色/黑色，跟随系统深色模式）
        canvas.drawColor(getSystemBackgroundColor())

        // 遍历当前可见的页面并逐个绘制
        val visiblePages = calculateVisiblePages()
        for (pageIndex in visiblePages) {
            // --- 绘制页面容器背景 ---
            val containerTop1x = state.getContainerTop1x(pageIndex)
            val containerHeight1x = state.getPageOriginalHeight(pageIndex).toFloat()
            val containerWidth1x = state.getMaxPageWidth().toFloat()

            // 将 1x 坐标系中的容器矩形变换到屏幕坐标系
            val containerLeftScreen = 0f * scale + scrollX
            val containerTopScreen = containerTop1x * scale + scrollY
            val containerRightScreen = containerWidth1x * scale + scrollX
            val containerBottomScreen = (containerTop1x + containerHeight1x) * scale + scrollY

            // 绘制容器背景（纯白色矩形）
            canvas.drawRect(
                containerLeftScreen, containerTopScreen,
                containerRightScreen, containerBottomScreen, backgroundPaint
            )

            // --- 绘制 PDF 页面内容 ---
            val contentLeft1x = state.getContentLeft1x(pageIndex)
            val contentTop1x = state.getContentTop1x(pageIndex)
            val pageWidth1x = state.getPageOriginalWidth(pageIndex).toFloat()
            val pageHeight1x = state.getPageOriginalHeight(pageIndex).toFloat()

            // 将内容矩形变换到屏幕坐标系
            val contentLeftScreen = (contentLeft1x + 0f) * scale + scrollX
            val contentTopScreen = (containerTop1x + contentTop1x) * scale + scrollY
            val contentRightScreen = contentLeftScreen + pageWidth1x * scale
            val contentBottomScreen = contentTopScreen + pageHeight1x * scale

            // 从缓存中获取已渲染的页面纹理
            val pageTexture = pageTextures[pageIndex]
            if (pageTexture != null) {
                // 缓存命中：直接绘制 Bitmap
                val dstRect = RectF(
                    contentLeftScreen, contentTopScreen,
                    contentRightScreen, contentBottomScreen
                )
                canvas.drawBitmap(pageTexture, null, dstRect, paint)
            } else {
                // 缓存未命中：绘制灰色占位符，并发起渲染请求
                val placeholderRect = RectF(
                    contentLeftScreen, contentTopScreen,
                    contentRightScreen, contentBottomScreen
                )
                canvas.drawRect(placeholderRect, placeholderPaint)
                requestPageRender(pageIndex)
            }
        }

        // --- 触发页面变化回调（去重）---
        val topPage = visiblePages.firstOrNull() ?: 0
        if (topPage != lastReportedPage) {
            lastReportedPage = topPage
            onPageChanged?.invoke(topPage)
        }

        // --- 计算并触发滚动进度回调 ---
        val canvasH = state.totalHeight1x * scale
        val maxScrollY = maxOf(0f, canvasH - height)
        val progress = if (maxScrollY <= 0) 0f else (-scrollY) / maxScrollY
        onScrollProgressChanged?.invoke(progress)

        // --- 调度延迟预渲染 ---
        schedulePrerender(visiblePages)
    }

    /**
     * 占位符画笔（灰色）
     *
     * 功能：当页面纹理尚未渲染完成时，用灰色矩形作为占位符显示。
     */
    private val placeholderPaint = Paint().apply { color = Color.LTGRAY }

    /**
     * 计算当前可见的页面列表
     *
     * 功能：根据当前滚动偏移和缩放比例，计算哪些页面（部分或全部）
     * 在视口范围内。使用二分查找优化起始页的定位。
     *
     * 算法：
     * 1. 将视口上下边界变换回 1x 坐标系（除以 currentScale）
     * 2. 二分查找第一个底部超过视口顶部的页面（起始页）
     * 3. 从起始页向前扩展一页（捕获部分可见的上一个页面）
     * 4. 向后遍历直到页面完全超出视口底部
     *
     * 调用位置：onDraw() 中每次绘制时调用
     * 使用场景：确定当前视口范围内需要渲染的页面集合
     *
     * @return 当前可见的页面索引列表（从小到大排序）
     */
    private fun calculateVisiblePages(): List<Int> {
        val state = canvasState ?: return emptyList()
        val handler = gestureHandler ?: return emptyList()

        // 检查文档是否可用
        if (!state.getPdfDocumentRepository().isDocumentAvailable()) {
            return emptyList()
        }

        val tops = state.containerTops1xArray
        if (tops.isEmpty()) return emptyList()

        // 将视口上下边界转换到 1x 画布坐标系
        val viewTop1x = -handler.scrollY / state.currentScale
        val viewBottom1x = (-handler.scrollY + height) / state.currentScale

        // 二分查找：找到第一个底部超过视口顶部的页面
        var start = 0
        var left = 0
        var right = tops.size - 1

        while (left <= right) {
            val mid = (left + right) / 2
            val containerBottom1x = tops[mid] + state.getPageOriginalHeight(mid)

            if (containerBottom1x > viewTop1x) {
                start = mid
                right = mid - 1
            } else {
                left = mid + 1
            }
        }

        // 向前扩展一页（捕获部分可见的页面）
        if (start > 0) start--

        // 向后遍历，收集所有与视口相交的页面
        val visiblePages = mutableListOf<Int>()
        for (i in start until tops.size) {
            val pageTop = tops[i]
            val pageBottom = pageTop + state.getPageOriginalHeight(i)

            if (pageTop < viewBottom1x && pageBottom > viewTop1x) {
                visiblePages.add(i)
            } else if (pageTop >= viewBottom1x) {
                break
            }
        }

        Log.v(TAG, "calculateVisiblePages() result=$visiblePages")
        return visiblePages
    }

    /**
     * 调度延迟预渲染
     *
     * 功能：限制预渲染频率。在检测到可见页面变化后，
     * 不会立即执行预渲染，而是推迟 100ms 后执行。
     * 两次预渲染之间的间隔至少为 300ms。
     *
     * 优化目的：避免快速滚动时提交大量冗余渲染请求，
     * 减轻 CPU/GPU 和内存压力。
     *
     * 调用位置：onDraw() 中每次绘制后调用
     *
     * @param visiblePages 当前可见页面列表
     */
    private fun schedulePrerender(visiblePages: List<Int>) {
        // 检查文档是否可用
        val state = canvasState
        if (state == null || !state.getPdfDocumentRepository().isDocumentAvailable()) {
            return
        }

        val now = SystemClock.elapsedRealtime()
        if (now - lastPrerenderTime > 300 && !pendingPrerender) {
            pendingPrerender = true
            postDelayed({
                pendingPrerender = false
                lastPrerenderTime = SystemClock.elapsedRealtime()
                prerenderVisiblePages(visiblePages)
            }, 100)
        }
    }

    /**
     * 计算渲染尺寸（基于物理像素需求）
     *
     * 功能：根据当前缩放比例和页面原始尺寸，计算渲染 Bitmap
     * 的最佳分辨率。避免以过高的分辨率渲染（浪费内存），
     * 也避免过低分辨率（显示模糊）。
     *
     * 优化策略：
     * - 计算页面在当前缩放下的屏幕绘制尺寸
     * - 限制最大渲染尺寸为 4096x4096（4K 分辨率上限）
     * - 在不超过上限的前提下，按绘制尺寸渲染
     *
     * 调用位置：requestPageRender() 中创建 Bitmap 前调用
     *
     * @param pageIndex 页面索引（0-based）
     * @return Pair(renderW, renderH) 渲染宽度和高度（像素）
     */
    private fun getRenderSize(pageIndex: Int): Pair<Int, Int> {
        val state = canvasState ?: return 0 to 0

        // 检查文档是否可用
        if (!state.getPdfDocumentRepository().isDocumentAvailable()) {
            return 0 to 0
        }

        val pageW = state.getPageOriginalWidth(pageIndex)
        val pageH = state.getPageOriginalHeight(pageIndex)

        // 当前缩放下，内容在屏幕上的绘制尺寸
        val drawWidth = (pageW * currentScale).toInt()
        val drawHeight = (pageH * currentScale).toInt()

        // 最大渲染尺寸限制（防止超大页面）
        val maxSize = 4096  // 4K分辨率
        val scale = minOf(maxSize.toFloat() / drawWidth, maxSize.toFloat() / drawHeight, 1f)

        val renderW = (drawWidth * scale).toInt().coerceAtLeast(1)
        val renderH = (drawHeight * scale).toInt().coerceAtLeast(1)

        return renderW to renderH
    }

    /**
     * 请求渲染指定页面
     *
     * 功能：异步渲染指定页面为 Bitmap 并存入缓存。
     * 在后台线程（Dispatchers.IO）执行渲染，完成后
     * 切回主线程更新缓存并触发视图重绘。
     *
     * 优化策略：
     * - 已存在渲染任务或已缓存的任务跳过
     * - 限制最大并发渲染数（maxConcurrentRenderJobs = 2）
     * - 使用 getRenderSize() 按需计算渲染分辨率
     * - 使用 postInvalidateOnAnimation() 优化重绘时机
     *
     * 调用位置：
     * - onDraw() 中缓存未命中时调用
     * - prerenderCurrentPage() 中预渲染调用
     * - prerenderVisiblePages() 中预渲染调用
     *
     * @param pageIndex 需要渲染的页面索引（0-based）
     */
    private fun requestPageRender(pageIndex: Int) {
        val state = canvasState ?: return
        val repository = state.getPdfDocumentRepository()

        // 检查文档是否可用
        if (!repository.isDocumentAvailable()) {
            Log.w(TAG, "requestPageRender() failed: Document not available")
            return
        }

        synchronized(renderJobsLock) {
            // 去重：跳过已在渲染或已缓存的任务
            if (renderJobs.containsKey(pageIndex)) return
            if (pageTextures.containsKey(pageIndex)) return
            if (renderJobs.size >= maxConcurrentRenderJobs) return

            // 提交后台渲染协程
            val job = scope.launch(Dispatchers.IO) {
                try {
                    // 计算渲染分辨率
                    val (renderW, renderH) = getRenderSize(pageIndex)

                    if (renderW <= 0 || renderH <= 0) {
                        synchronized(renderJobsLock) { renderJobs.remove(pageIndex) }
                        return@launch
                    }

                    val bitmap = Bitmap.createBitmap(renderW, renderH, Bitmap.Config.ARGB_8888)

                    // 使用 PdfDocumentRepository 渲染页面
                    val success = repository.renderPage(pageIndex, bitmap)

                    if (success) {
                        // 渲染成功：切回主线程更新缓存
                        launch(Dispatchers.Main) {
                            pageTextures[pageIndex] = bitmap
                            postInvalidateOnAnimation()
                            synchronized(renderJobsLock) { renderJobs.remove(pageIndex) }
                        }
                    } else {
                        // 渲染失败：回收 Bitmap
                        bitmap.recycle()
                        synchronized(renderJobsLock) { renderJobs.remove(pageIndex) }
                    }
                } catch (e: Exception) {
                    Log.e("PdfContinuousView", "Failed to render page $pageIndex", e)
                    synchronized(renderJobsLock) { renderJobs.remove(pageIndex) }
                }
            }
            renderJobs[pageIndex] = job
        }
    }

    /**
     * 预渲染当前页及其邻居页
     *
     * 功能：获取当前顶部可见页面，然后通过 ContinuousCanvasState
     * 的 getPagesToPrerender() 获取需要预渲染的页面列表（当前页 +
     * 前3页 + 后5页），逐个发起渲染请求。
     *
     * 调用位置：doInit() 初始化完成后调用
     * 使用场景：视图首次初始化或页面发生重大跳转后，立即加载周边页面
     */
    private fun prerenderCurrentPage() {
        val state = canvasState ?: return

        // 检查文档是否可用
        if (!state.getPdfDocumentRepository().isDocumentAvailable()) {
            return
        }

        val topPage = state.getTopVisiblePage()
        val pagesToPrerender = state.getPagesToPrerender(topPage)
        for (pageIndex in pagesToPrerender) {
            requestPageRender(pageIndex)
        }
    }

    /**
     * 预渲染可见页面列表中的当前页及其邻居
     *
     * 功能：与 prerenderCurrentPage() 功能类似，
     * 但以传入的 visiblePages 列表中的第一页作为"当前页"。
     *
     * 调用位置：schedulePrerender() 延迟回调中调用
     * 使用场景：用户滚动过程中，定期触发预渲染
     *
     * @param visiblePages 当前可见页面列表
     */
    private fun prerenderVisiblePages(visiblePages: List<Int>) {
        val state = canvasState ?: return

        // 检查文档是否可用
        if (!state.getPdfDocumentRepository().isDocumentAvailable()) {
            return
        }

        val currentPage = visiblePages.firstOrNull() ?: return
        val pagesToPrerender = state.getPagesToPrerender(currentPage)
        for (pageIndex in pagesToPrerender) {
            requestPageRender(pageIndex)
        }
    }

    /**
     * 获取系统背景色（跟随深色/浅色模式）
     *
     * 功能：根据系统当前深色/浅色模式设置，返回对应的背景色。
     * 深色模式下返回黑色（Color.BLACK），浅色模式下返回白色（Color.WHITE）。
     *
     * 调用位置：onDraw() 中绘制视图背景色时调用
     *
     * @return 系统背景色（int 颜色值）
     */
    private fun getSystemBackgroundColor(): Int {
        val nightMode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return if (nightMode == Configuration.UI_MODE_NIGHT_YES) Color.BLACK else Color.WHITE
    }

    /**
     * 触摸事件处理（重写 View.onTouchEvent）
     *
     * 功能：将触摸事件分发给手势处理器处理。
     * 手势处理器内部会进一步分发到 GestureDetector（单指手势）
     * 和 ScaleGestureDetector（双指缩放手势）。
     *
     * 调用位置：由 Android 触摸事件系统自动调用
     *
     * @param event Android 触摸事件对象
     * @return true 表示消费了此次触摸事件
     */
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isInitialized) return false
        return gestureHandler?.onTouchEvent(event) ?: false
    }

    /**
     * 视图尺寸变化回调（重写 View.onSizeChanged）
     *
     * 功能：当视图尺寸变化时（如屏幕旋转、布局改变）触发。
     * 若尚未初始化且有缓存的仓库引用，执行延迟初始化。
     * 若已初始化，则更新视口尺寸、缩放比例和手势处理器。
     *
     * 调用位置：由 Android 布局系统自动在布局变化时调用
     *
     * @param w    新宽度（像素）
     * @param h    新高度（像素）
     * @param oldw 旧宽度（像素）
     * @param oldh 旧高度（像素）
     */
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        Log.d(TAG, "onSizeChanged() newSize=${w}x$h, oldSize=${oldw}x$oldh")

        // 检查是否有延迟的初始化的任务
        if (!isInitialized && pendingPdfDocumentRepository != null) {
            val repository = pendingPdfDocumentRepository!!
            val cacheSize = pendingCacheSize
            pendingPdfDocumentRepository = null
            doInit(repository, cacheSize)
            return
        }

        // 已初始化：更新视口尺寸
        canvasState?.updateViewportSize(w, h)
        minScale = canvasState?.minScale ?: 1.0f
        currentScale = currentScale.coerceIn(minScale, maxScale)
        gestureHandler?.setScaleRange(minScale, maxScale)
        updateGestureHandlerDimensions()
    }

    /**
     * 惯性滚动计算（重写 View.computeScroll）
     *
     * 功能：在惯性滚动（fling）动画期间，每一帧调用手势处理器的
     * computeScroll() 获取 Scroller 计算的当前帧滚动位置。
     * 如果惯性滚动尚未结束，触发视图重绘。
     *
     * 调用位置：由 Android 绘制系统在每一帧绘制前自动调用
     */
    override fun computeScroll() {
        val needsInvalidate = gestureHandler?.computeScroll() ?: false
        if (needsInvalidate) postInvalidateOnAnimation()
    }

    /**
     * 滚动到指定页面
     *
     * 功能：将指定页面滚动到屏幕顶部。如果视图尚未初始化，
     * 将页码缓存起来，等初始化完成后自动执行。
     *
     * 调用位置：ReaderScreenV2.kt 第 147 行（处理 pendingPageJump）
     * 使用场景：
     * - 初始化完成后自动跳转到上次阅读位置
     * - 用户通过底部工具栏的翻页按钮跳转
     * - 切换阅读模式（单页→连续）后保持当前页码
     *
     * @param pageIndex 目标页面索引（0-based）
     * @param alignTop  是否对齐顶部（当前仅支持顶部对齐）
     */
    fun scrollToPage(pageIndex: Int, alignTop: Boolean = false) {
        val state = canvasState
        val handler = gestureHandler
        if (state == null || handler == null) {
            // 视图尚未初始化，缓存页码，初始化完成后补执行
            pendingScrollToPage = pageIndex
            return
        }
        val containerTop1x = state.getContainerTop1x(pageIndex)
        handler.scrollY = -containerTop1x * currentScale
        handler.clampScroll()
        invalidate()
    }

    /**
     * 获取当前页面索引
     *
     * 功能：获取当前最顶部可见页面的索引。
     *
     * 调用位置：ReaderScreenV2 中可能用于同步页码状态
     *
     * @return 页面索引（0-based），未初始化时返回 0
     */
    fun getCurrentPage(): Int = canvasState?.getTopVisiblePage() ?: 0

    /**
     * 获取总页数
     *
     * 功能：获取 PDF 文档的总页数。
     *
     * @return 总页数，未初始化时返回 0
     */
    fun getTotalPages(): Int = canvasState?.totalPages ?: 0

    /**
     * 更新手势处理器的画布和视口尺寸
     *
     * 功能：当前缩放比例或视图尺寸变化时，重新计算画布总宽高
     * （maxPageWidth * scale 和 totalHeight1x * scale）并传递给
     * 手势处理器，用于正确钳制滚动范围。
     *
     * 调用位置：
     * - doInit() 中初始化后
     * - onSizeChanged() 中视图尺寸变化后
     * - 缩放回调中缩放比例变化后
     */
    private fun updateGestureHandlerDimensions() {
        val state = canvasState
        val handler = gestureHandler
        if (state != null && handler != null) {
            val canvasW = state.getMaxPageWidth() * currentScale
            val canvasH = state.totalHeight1x * currentScale
            handler.setDimensions(canvasW, canvasH, width.toFloat(), height.toFloat())
        }
    }

    /**
     * 清理资源
     *
     * 功能：
     * 1. 取消所有正在进行的渲染协程
     * 2. 清空页面纹理缓存并回收所有 Bitmap
     * 3. 释放手势处理器资源
     * 4. 清空状态引用
     *
     * 注意：不取消 CoroutineScope（由调用者管理，如 viewModelScope）
     *
     * 调用位置：onDetachedFromWindow() 中调用
     * 使用场景：视图从窗口分离时（如导航返回、Fragment 销毁）释放资源
     */
    fun cleanup() {
        synchronized(renderJobsLock) {
            for (job in renderJobs.values) job.cancel()
            renderJobs.clear()
        }
        pageTextures.clearAll()
        gestureHandler?.recycle()
        gestureHandler = null
        canvasState = null
        isInitialized = false
        pendingScrollToPage = null
    }

    /**
     * 视图从窗口分离回调（重写 View.onDetachedFromWindow）
     *
     * 功能：视图从窗口分离时自动调用 cleanup() 释放资源。
     *
     * 调用位置：由 Android 视图系统在视图从窗口分离时自动调用
     * （如 Activity 销毁、Fragment 视图移除等）
     */
    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        cleanup()
    }
}
