package com.example.pdfmanager.ui.screen.reader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.core.content.ContextCompat
import com.example.pdfmanager.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.min

/**
 * PDF 单页视图（翻页模式）
 *
 * ===== 功能概述 =====
 * 本视图实现 PDF 的单页阅读模式，一次显示一页内容。
 * 采用翻页动画方式切换页面，支持点击左右半屏翻页、
 * 滑动翻页、双指缩放、放大后平移浏览。
 *
 * ===== 核心特性 =====
 * 1. 页面居中显示，等比例缩放直到一边顶格
 * 2. 点击左半屏→上一页，右半屏→下一页
 * 3. 翻页动画：两个 Bitmap 快照 + 平移动画（AccelerateDecelerateInterpolator）
 * 4. 双指缩放（范围 [minScale, maxScale]，max = 4.0f）
 * 5. 最小缩放(1x)时单指拖动触发翻页；放大后单指拖动平移
 * 6. LRU 页面纹理缓存（64MB），含去重渲染任务管理
 * 7. 前后各3页预渲染机制
 *
 * ===== 调用位置 =====
 * - 创建实例：ReaderScreenV2.kt 第 154 行：SinglePageView(ctx)
 * - 初始化：ReaderScreenV2.kt 第 159 行：view.init(viewModel.pdfDocumentRepository)
 * - 设置协程：ReaderScreenV2.kt 第 157 行：view.setCoroutineScope(coroutineScope)
 * - 注册回调：ReaderScreenV2.kt 第 161-163 行（onPageChanged/onDoubleTap）
 * - 跳转页面：ReaderScreenV2.kt 第 169 行：view.jumpToPage(targetPage)
 *
 * ===== 使用场景 =====
 * 当 pageMode == "single_page" 时，ReaderScreenV2 使用此视图
 * 替代 PdfContinuousView。用户以逐页翻页方式阅读 PDF。
 */
class SinglePageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        /** 日志标签，用于调试输出 */
        private const val TAG = "SinglePageView"
        /** 翻页动画时长（毫秒） */
        private const val ANIMATION_DURATION = 300L
    }

    /**
     * 协程作用域（由外部通过 setCoroutineScope() 传入）
     *
     * 功能：用于启动 PDF 页面渲染的异步协程。
     * 必须传入生命周期感知的 scope（如 viewModelScope），
     * 确保视图销毁时渲染协程能自动取消。
     *
     * 调用位置：ReaderScreenV2.kt 第 157 行
     */
    private lateinit var scope: CoroutineScope

    /**
     * 构造函数 init 块
     *
     * 功能：设置视图为可点击、可聚焦，确保能接收触摸事件。
     */
    init {
        isClickable = true
        isFocusable = true
    }

    /**
     * 单页状态管理器
     *
     * 功能：管理当前页面索引、缩放比例、滚动偏移、页面尺寸
     * 等状态数据。
     *
     * 类型：SinglePageState，定义在 SinglePageState.kt
     * 创建位置：本文件的 doInit() 方法中
     */
    private var pageState: SinglePageState? = null

    /**
     * 滑动翻页累积距离（水平方向）
     *
     * 功能：跟踪用户在 1x 缩放下手势拖动的水平距离。
     * 当累计距离超过阈值（屏幕宽度的 1/4）时触发翻页。
     * 翻页完成后或动画开始前重置为 0。
     *
     * 使用场景：单指滑动翻页逻辑中（GestureDetector.onScroll 回调）
     */
    private var scrollAccumulatorX = 0f

    // ===== LRU 页面纹理缓存（内存限制 64MB）=====

    /** 最大缓存容量（字节）：64MB */
    private val maxCacheSizeBytes = 64 * 1024 * 1024

    /**
     * 页面纹理缓存（LRU 淘汰策略）
     *
     * 功能：缓存已渲染完成的 PDF 页面 Bitmap，避免重复渲染。
     * 实现机制与 PdfContinuousView 中的 pageTextures 完全相同：
     * 继承 LinkedHashMap（accessOrder=true），自动跟踪已用字节数，
     * 超出限制时淘汰最久未访问的条目。
     *
     * 调用位置：
     * - drawCurrentPage() / drawPageAnimation() 中读取缓存
     * - requestPageRender() 中写入缓存
     * - startPageAnimation() 中检查缓存状态
     * - cleanup() 中清空所有缓存
     */
    private val pageTextures = object : LinkedHashMap<Int, Bitmap>(16, 0.75f, true) {
        /** 当前缓存占用的总字节数 */
        private var currentSizeBytes = 0L

        /**
         * 放入缓存（重写 LinkedHashMap.put）
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

            // 超出限制时移除最旧的条目
            while (currentSizeBytes > maxCacheSizeBytes && isNotEmpty()) {
                val oldestKey = keys.iterator().next()
                val oldestBitmap = remove(oldestKey)
                if (oldestBitmap != null) {
                    currentSizeBytes -= oldestBitmap.allocationByteCount
                    oldestBitmap.recycle()
                }
            }
            return oldValue
        }

        /**
         * 移除缓存（重写 LinkedHashMap.remove）
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
         * 功能：遍历回收所有 Bitmap 并清空缓存。
         *
         * 调用位置：cleanup() 方法
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
     * 功能：跟踪当前正在进行的页面渲染协程，防止同一页面多次渲染。
     * key = 页面索引，value = 协程 Job。
     */
    private val renderJobs = mutableMapOf<Int, Job>()

    /** renderJobs 的同步锁对象 */
    private val renderJobsLock = Any()

    /**
     * 最大并发渲染任务数
     *
     * 允许同时进行最多 4 个渲染协程（比连续模式多，因为单页模式下
     * 翻页需要快速渲染新页面）。
     */
    private val maxConcurrentRenderJobs = 4

    /**
     * 绘制 Bitmap 的画笔（抗锯齿 + 滤镜）
     */
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    /**
     * 页面背景画笔（白色）
     */
    private val backgroundPaint = Paint().apply {
        color = ContextCompat.getColor(context, android.R.color.white)
    }

    // ===== 翻页动画相关变量 =====

    /** 是否正在播放翻页动画 */
    private var isAnimating = false

    /** 动画开始时间戳（System.currentTimeMillis()） */
    private var animationStartTime = 0L

    /** 动画起始页索引 */
    private var animationFromPage = -1

    /** 动画目标页索引 */
    private var animationToPage = -1

    /** 动画起始页的 Bitmap 快照 */
    private var animationFromBitmap: Bitmap? = null

    /** 动画目标页的 Bitmap 快照 */
    private var animationToBitmap: Bitmap? = null

    /**
     * 动画插值器（加速减速插值器）
     *
     * 功能：翻页动画使用先加速后减速的插值曲线，
     * 使翻页效果更自然流畅。
     */
    private val animInterpolator = AccelerateDecelerateInterpolator()

    /**
     * 待执行的翻页动画数据类
     *
     * 功能：当翻页动画无法立即开始（目标页或当前页的 Bitmap
     * 尚未渲染完成）时，暂存翻页参数，等待渲染完成后执行。
     *
     * @param fromPage  起始页索引
     * @param toPage    目标页索引
     * @param direction 翻页方向（-1 = 向前翻，1 = 向后翻）
     */
    private var pendingAnimation: PendingAnimation? = null

    data class PendingAnimation(
        val fromPage: Int,
        val toPage: Int,
        val direction: Int
    )

    // ===== 手势检测器 =====

    /**
     * 手势检测器（用于单指手势：点击、滚动、滑动、双击）
     *
     * 创建位置：initGestureDetectors() 方法中
     */
    private var gestureDetector: GestureDetector? = null

    /**
     * 缩放手势检测器（用于双指缩放）
     *
     * 创建位置：initGestureDetectors() 方法中
     */
    private var scaleGestureDetector: ScaleGestureDetector? = null

    /**
     * 当前缩放比例
     *
     * 范围：[minScale, maxScale]，初始值为 minScale。
     * 在 1x 缩放时点击拖动触发翻页，大于 1x 时触发平移。
     */
    private var currentScale: Float = 1.0f
        private set

    /**
     * 最小缩放比例
     *
     * 由 SinglePageState.minScale 决定，内容刚好填满屏幕时的缩放值。
     */
    private var minScale: Float = 1.0f
        private set

    /**
     * 页面变化回调（由外部注册）
     *
     * 参数：新页面的索引（0-based）
     *
     * 调用位置：drawPageAnimation() 动画结束时调用
     * 注册位置：ReaderScreenV2.kt 第 161 行
     */
    var onPageChanged: ((Int) -> Unit)? = null

    /**
     * 双击回调（由外部注册）
     *
     * 参数 (x, y)：双击点的屏幕坐标
     *
     * 注册位置：ReaderScreenV2.kt 第 163 行（用于切换工具栏显示/隐藏）
     */
    var onDoubleTap: ((Float, Float) -> Unit)? = null

    /**
     * 视图是否已完成初始化
     *
     * 功能：在 onDraw()、onTouchEvent() 中作为前置检查条件。
     * 外部可读（ReaderScreenV2 通过此标志判断是否需要调用 init()）。
     */
    var isInitialized = false
        private set

    /**
     * 待处理的跳转页面（初始化期间缓存）
     *
     * 功能：如果在视图初始化完成前调用了 jumpToPage()，
     * 将目标页码缓存在此，等 doInit() 完成后自动执行。
     */
    private var pendingJumpToPage: Int? = null

    /**
     * 待处理的 PdfDocumentRepository（延迟初始化使用）
     *
     * 功能：如果在 onSizeChanged() 之前调用了 init()，
     * 将仓库引用暂存于此，等待布局完成后执行 doInit()。
     */
    private var pendingPdfDocumentRepository: PdfDocumentRepository? = null

    /**
     * 设置协程作用域
     *
     * 功能：外部传入生命周期感知的 CoroutineScope，用于启动页面渲染协程。
     * 必须在 init() 之前调用。
     *
     * 调用位置：ReaderScreenV2.kt 第 157 行
     *
     * @param scope 协程作用域（如 viewModelScope）
     */
    fun setCoroutineScope(scope: CoroutineScope) {
        this.scope = scope
    }

    /**
     * 初始化视图（公开入口）
     *
     * 功能：检查文档可用性和视图布局状态，决定是立即执行 doInit()
     * 还是延迟到 onSizeChanged()。
     *
     * 调用位置：ReaderScreenV2.kt 第 159 行
     *
     * @param pdfDocumentRepository PDF 文档仓库实例
     */
    fun init(pdfDocumentRepository: PdfDocumentRepository) {
        // 检查文档是否可用
        if (!pdfDocumentRepository.isDocumentAvailable()) {
            Log.e(TAG, "init() failed: Document not available")
            return
        }

        // 延迟初始化：如果视图尚未布局（width=0 或 height=0），等待 onSizeChanged() 触发
        if (width <= 0 || height <= 0) {
            Log.w(TAG, "init() delayed: width=$width, height=$height")
            pendingPdfDocumentRepository = pdfDocumentRepository
            return
        }

        doInit(pdfDocumentRepository)
    }

    /**
     * 实际初始化逻辑
     *
     * 功能：创建 SinglePageState、初始化手势检测器、
     * 处理缓存的跳转请求、启动预渲染。
     *
     * 调用位置：
     * - init() 方法中
     * - onSizeChanged() 中（延迟初始化场景）
     *
     * @param pdfDocumentRepository PDF 文档仓库
     */
    private fun doInit(pdfDocumentRepository: PdfDocumentRepository) {
        if (!::scope.isInitialized) {
            Log.e(TAG, "CoroutineScope not set! Call setCoroutineScope() before init()")
            return
        }

        // 创建单页状态管理器
        pageState = SinglePageState(pdfDocumentRepository, width, height)
        minScale = pageState?.minScale ?: 1.0f
        currentScale = minScale

        // 初始化手势检测器
        initGestureDetectors()

        isInitialized = true

        // 补执行初始化期间缓存的跳转请求
        pendingJumpToPage?.let { page ->
            pendingJumpToPage = null
            jumpToPage(page)
        }

        // 启动当前页及前后页的预渲染
        prerenderCurrentPage()
        invalidate()
    }

    /**
     * 初始化手势检测器
     *
     * 功能：创建 GestureDetector（单指手势）和 ScaleGestureDetector（双指缩放手势）。
     * 单指手势包括：单击翻页、滑动翻页、拖动平移、双击、惯性滑动。
     *
     * 调用位置：
     * - doInit() 中初始化时调用
     * - onSizeChanged() 中视图尺寸变化后重新初始化
     *
     * 注意：onSizeChanged 时重新初始化是为了更新宽度引用（用于左右半屏判断），
     * 手势检测器内部使用了 View 的宽度字段。
     */
    private fun initGestureDetectors() {
        // ===== 单指手势检测器 =====
        gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            /**
             * 单击确认回调
             *
             * 功能：用户单击视图时触发。根据点击位置在左半屏还是右半屏，
             * 决定向前翻页还是向后翻页。动画中不响应单击。
             *
             * @param e 触摸事件（包含点击坐标）
             * @return true 表示消费此事件
             */
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (isAnimating) return true

                val page = pageState?.currentPage ?: 0
                if (e.x < width / 2) {
                    // 左半屏：上一页
                    if (page > 0) {
                        startPageAnimation(page, page - 1, -1)  // -1 表示向左滑动
                    }
                } else {
                    // 右半屏：下一页
                    if (page < (pageState?.totalPages ?: 0) - 1) {
                        startPageAnimation(page, page + 1, 1)   // 1 表示向右滑动
                    }
                }
                return true
            }

            /**
             * 手指拖动回调
             *
             * 功能：处理手指拖动事件。
             * - 最小缩放(1x)时：累积水平滑动距离，超过阈值（屏幕宽度的 1/4）触发翻页
             * - 放大后：更新 scrollX/scrollY 实现平移浏览
             *
             * 注意：Android 的 distanceX 为负表示手指向右滑（视图内容向左移）。
             * 当 distanceX > 0（手指向左滑，下一张）时触发下一页；
             * 当 distanceX < 0（手指向右滑，上一张）时触发上一页。
             *
             * @param e1        上一次触摸事件
             * @param e2        当前触摸事件
             * @param distanceX 手指在 X 轴上的移动距离
             * @param distanceY 手指在 Y 轴上的移动距离
             * @return true 表示消费此事件
             */
            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float
            ): Boolean {
                if (isAnimating) return true

                val state = pageState ?: return true

                if (state.isAtMinScale()) {
                    // 1x 缩放：单指滑动触发翻页
                    scrollAccumulatorX += distanceX

                    // 阈值：屏幕宽度的 1/4（更容易触发）
                    val threshold = width / 4f

                    if (abs(scrollAccumulatorX) > threshold) {
                        if (scrollAccumulatorX > 0) {
                            // 手指向左滑（distanceX > 0）：下一页
                            if (state.currentPage < state.totalPages - 1) {
                                startPageAnimation(state.currentPage, state.currentPage + 1, 1)
                            }
                        } else {
                            // 手指向右滑（distanceX < 0）：上一页
                            if (state.currentPage > 0) {
                                startPageAnimation(state.currentPage, state.currentPage - 1, -1)
                            }
                        }
                        scrollAccumulatorX = 0f
                    }
                } else {
                    // 放大后：单指平移
                    // Android 的 distanceX 向左滑动为正，但 scrollX 需要相反方向
                    state.updateScroll(state.scrollX - distanceX, state.scrollY - distanceY)
                    invalidate()
                }
                return true
            }

            /**
             * 快速滑动（Fling）回调
             *
             * 功能：用户快速滑动并抬起手指时触发。
             * 当前实现为空操作，可以在此添加惯性翻页逻辑。
             *
             * @param e1        按下事件
             * @param e2        抬起事件
             * @param velocityX X 轴速度
             * @param velocityY Y 轴速度
             * @return true 表示消费此事件
             */
            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                // 可以在这里添加惯性翻页逻辑
                return true
            }

            /**
             * 双击回调
             *
             * 功能：用户双击时触发，调用外部注册的 onDoubleTap 回调。
             *
             * @param e 触摸事件
             * @return true 表示消费此事件
             */
            override fun onDoubleTap(e: MotionEvent): Boolean {
                onDoubleTap?.invoke(e.x, e.y)
                return true
            }
        })

        // ===== 双指缩放手势检测器 =====
        scaleGestureDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            /** 缩放开始时的基准焦点 X 坐标（内容坐标系） */
            private var baseFocusX = 0f
            /** 缩放开始时的基准焦点 Y 坐标（内容坐标系） */
            private var baseFocusY = 0f
            /** 是否正在缩放中（用于区分第一次 onScale 回调） */
            private var isScaling = false

            /**
             * 缩放开始回调
             *
             * @param detector 缩放手势检测器
             * @return true 表示消费此事件；动画中不响应缩放
             */
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                if (isAnimating) return false
                isScaling = false
                return true
            }

            /**
             * 缩放进行中回调
             *
             * 功能：计算新的缩放比例，并保持双指焦点位置不变。
             * 
             * 算法：
             * 1. 首次缩放时，计算焦点在内容坐标系中的位置（baseFocusX/Y）
             * 2. 每次缩放时，根据新的缩放比例反向计算 scrollX/scrollY，
             *    使焦点对应的内容点保持在屏幕位置不变
             *
             * @param detector 缩放手势检测器
             * @return true 表示消费此事件
             */
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val state = pageState ?: return true
                if (state.currentPage < 0) return true

                if (!isScaling) {
                    isScaling = true

                    // 计算当前页的绘制尺寸和居中偏移
                    val pageW = state.getPageOriginalWidth(state.currentPage).toFloat()
                    val pageH = state.getPageOriginalHeight(state.currentPage).toFloat()
                    val drawW = pageW * currentScale
                    val drawH = pageH * currentScale

                    // 内容在屏幕上的实际位置（考虑居中偏移 + 滚动偏移）
                    val contentLeft = (width - drawW) / 2f + state.scrollX
                    val contentTop = (height - drawH) / 2f + state.scrollY

                    // 计算焦点在内容坐标系中的位置
                    baseFocusX = (detector.focusX - contentLeft) / currentScale
                    baseFocusY = (detector.focusY - contentTop) / currentScale
                }

                // 计算新的缩放比例（钳制到合法范围）
                val newScale = (currentScale * detector.scaleFactor)
                    .coerceIn(state.minScale, state.maxScale)

                // 计算新缩放下页面的绘制尺寸
                val pageW = state.getPageOriginalWidth(state.currentPage).toFloat()
                val pageH = state.getPageOriginalHeight(state.currentPage).toFloat()
                val newDrawW = pageW * newScale
                val newDrawH = pageH * newScale

                // 缩放后保持焦点位置不变，反向计算 scrollX/scrollY
                val newContentLeft = detector.focusX - baseFocusX * newScale
                val newContentTop = detector.focusY - baseFocusY * newScale

                val newScrollX = newContentLeft - (width - newDrawW) / 2f
                val newScrollY = newContentTop - (height - newDrawH) / 2f

                // 更新状态
                currentScale = newScale
                state.updateScale(newScale)
                state.updateScroll(newScrollX, newScrollY)

                invalidate()
                return true
            }

            /**
             * 缩放结束回调
             */
            override fun onScaleEnd(detector: ScaleGestureDetector) {
                isScaling = false
            }
        })
    }

    /**
     * 开始翻页动画
     *
     * 功能：触发从当前页到目标页的翻页动画。
     * 如果两个页面的 Bitmap 都已缓存，立即开始动画。
     * 否则先发起渲染请求，渲染完成后自动执行动画。
     *
     * 调用位置：
     * - onSingleTapConfirmed() 中单击翻页时
     * - onScroll() 中滑动翻页时
     *
     * @param fromPage  当前页面索引
     * @param toPage    目标页面索引
     * @param direction 翻页方向：-1 向前翻（上一页），1 向后翻（下一页）
     */
    private fun startPageAnimation(fromPage: Int, toPage: Int, direction: Int) {
        if (isAnimating) {
            Log.w(TAG, "startPageAnimation: already animating, ignored")
            return
        }

        // 重置滑动累积距离
        scrollAccumulatorX = 0f

        // 检查缓存中是否已有两个页面的 Bitmap
        val fromBitmap = pageTextures[fromPage]
        val toBitmap = pageTextures[toPage]

        if (fromBitmap != null && toBitmap != null) {
            // 两个页面都已缓存，直接开始动画
            executePageAnimation(fromPage, toPage, direction, fromBitmap, toBitmap)
            return
        }

        // 至少有一个页面未缓存：先保存待执行动画，再发起渲染
        pendingAnimation = PendingAnimation(fromPage, toPage, direction)

        // 渲染未缓存的页面
        if (fromBitmap == null) {
            requestPageRenderWithCallback(fromPage) { renderedPage ->
                checkAndExecutePendingAnimation()
            }
        }

        if (toBitmap == null) {
            requestPageRenderWithCallback(toPage) { renderedPage ->
                checkAndExecutePendingAnimation()
            }
        }
    }

    /**
     * 执行翻页动画
     *
     * 功能：设置动画状态，将两个页面的 Bitmap 快照保存到动画变量中，
     * 更新 pageState 到目标页，重置缩放和预渲染，触发动画第一帧绘制。
     *
     * 调用位置：
     * - startPageAnimation() 中（两个页面都已缓存时直接调用）
     * - checkAndExecutePendingAnimation() 中（等待渲染完成后调用）
     *
     * @param fromPage  起始页索引
     * @param toPage    目标页索引
     * @param direction 翻页方向
     * @param fromBitmap 起始页纹理
     * @param toBitmap   目标页纹理
     */
    private fun executePageAnimation(
        fromPage: Int,
        toPage: Int,
        direction: Int,
        fromBitmap: Bitmap,
        toBitmap: Bitmap
    ) {
        // 设置动画状态
        isAnimating = true
        animationStartTime = System.currentTimeMillis()
        animationFromPage = fromPage
        animationToPage = toPage
        animationFromBitmap = fromBitmap
        animationToBitmap = toBitmap

        // 更新状态管理器中的当前页
        pageState?.jumpToPage(toPage)
        currentScale = pageState?.minScale ?: 1.0f

        // 立即预渲染新页面的前后页（不等动画结束）
        prerenderCurrentPage()

        invalidate()
    }

    /**
     * 检查并执行待定的翻页动画
     *
     * 功能：当页面渲染完成后调用，检查 pendingAnimation 中
     * 的两个页面是否都已缓存。如果都已就绪，执行翻页动画。
     *
     * 调用位置：
     * - requestPageRenderWithCallback() 的回调中（单个页面渲染完成时）
     */
    private fun checkAndExecutePendingAnimation() {
        val pending = pendingAnimation ?: return

        val fromBitmap = pageTextures[pending.fromPage]
        val toBitmap = pageTextures[pending.toPage]

        if (fromBitmap != null && toBitmap != null) {
            // 两个页面都已缓存，执行动画
            pendingAnimation = null
            executePageAnimation(
                pending.fromPage, pending.toPage,
                pending.direction, fromBitmap, toBitmap
            )
        }
    }

    /**
     * 视图绘制方法（重写 View.onDraw）
     *
     * 功能：根据当前状态绘制页面内容。
     * - 如果在动画中，调用 drawPageAnimation() 绘制翻页动画帧
     * - 如果不在动画中，调用 drawCurrentPage() 绘制当前静止页面
     *
     * @param canvas Android 画布对象
     */
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!isInitialized) {
            Log.w(TAG, "onDraw() called but isInitialized=false")
            return
        }

        val state = pageState ?: return

        // 检查文档是否可用
        if (!state.getPdfDocumentRepository().isDocumentAvailable()) {
            Log.w(TAG, "onDraw() called but document not available")
            return
        }

        if (isAnimating) {
            drawPageAnimation(canvas)
        } else {
            drawCurrentPage(canvas)
        }
    }

    /**
     * 绘制当前页（居中显示 + 可缩放平移）
     *
     * 功能：将当前页的 Bitmap 绘制到画布上，居中显示并应用
     * 缩放和滚动偏移。如果 Bitmap 尚未缓存，绘制灰色占位符
     * 并发起渲染请求。
     *
     * 调用位置：onDraw() 中非动画状态时调用
     *
     * @param canvas Android 画布对象
     */
    private fun drawCurrentPage(canvas: Canvas) {
        val state = pageState ?: return
        val currentPage = state.currentPage

        // 检查文档是否可用
        if (!state.getPdfDocumentRepository().isDocumentAvailable()) {
            Log.w(TAG, "drawCurrentPage: document not available")
            return
        }

        val pageW = state.getPageOriginalWidth(currentPage).toFloat()
        val pageH = state.getPageOriginalHeight(currentPage).toFloat()

        // 内容在当前缩放下的绘制尺寸
        val drawW = pageW * currentScale
        val drawH = pageH * currentScale

        // 居中偏移 + 滚动偏移
        val finalLeft = (width - drawW) / 2f + state.scrollX
        val finalTop = (height - drawH) / 2f + state.scrollY
        val finalRight = finalLeft + drawW
        val finalBottom = finalTop + drawH

        val pageTexture = pageTextures[currentPage]
        if (pageTexture != null) {
            val dstRect = RectF(finalLeft, finalTop, finalRight, finalBottom)
            canvas.drawBitmap(pageTexture, null, dstRect, paint)
        } else {
            // 占位符（白屏/灰屏的原因）
            Log.e(TAG, "drawCurrentPage: page=$currentPage NOT in cache! " +
                    "cachedPages=[${pageTextures.keys.sorted().joinToString(",")}]")
            val placeholderRect = RectF(finalLeft, finalTop, finalRight, finalBottom)
            canvas.drawRect(placeholderRect, placeholderPaint)
            requestPageRender(currentPage)
        }
    }

    /**
     * 绘制翻页动画帧
     *
     * 功能：根据当前动画进度（0.0~1.0），计算旧页面滑出和新页面
     * 滑入的位置并绘制。使用 AccelerateDecelerateInterpolator
     * 使动画速度曲线更自然。
     *
     * 动画逻辑：
     * - 向后翻页（to > from）：旧页面向左滑出，新页面从右侧滑入
     * - 向前翻页（to < from）：旧页面向右滑出，新页面从左侧滑入
     * - 动画始终在 1x 缩放（minScale）下进行，不考虑 currentScale
     *
     * 调用位置：onDraw() 中 isAnimating=true 时调用
     *
     * @param canvas Android 画布对象
     */
    private fun drawPageAnimation(canvas: Canvas) {
        // 计算动画进度（0.0 ~ 1.0）
        val elapsed = System.currentTimeMillis() - animationStartTime
        val progress = (elapsed.toFloat() / ANIMATION_DURATION).coerceIn(0f, 1f)
        val interpolatedProgress = animInterpolator.getInterpolation(progress)

        val state = pageState ?: return

        // 获取起始页和目标页的原始尺寸
        val fromPageW = state.getPageOriginalWidth(animationFromPage).toFloat()
        val fromPageH = state.getPageOriginalHeight(animationFromPage).toFloat()
        val toPageW = state.getPageOriginalWidth(animationToPage).toFloat()
        val toPageH = state.getPageOriginalHeight(animationToPage).toFloat()

        // 计算绘制尺寸（翻页动画始终在 1x 下进行，不使用 currentScale）
        val fromDrawW = fromPageW * state.minScale
        val fromDrawH = fromPageH * state.minScale
        val toDrawW = toPageW * state.minScale
        val toDrawH = toPageH * state.minScale

        // 居中偏移
        val fromLeft = (width - fromDrawW) / 2f
        val fromTop = (height - fromDrawH) / 2f
        val toLeft = (width - toDrawW) / 2f
        val toTop = (height - toDrawH) / 2f

        // 计算动画偏移量
        val direction = if (animationToPage > animationFromPage) 1 else -1
        val offsetX = width * interpolatedProgress * direction

        // 绘制旧页面（向后翻：向左滑出；向前翻：向右滑出）
        animationFromBitmap?.let { bitmap ->
            val fromRect = RectF(
                fromLeft - offsetX, fromTop,
                fromLeft - offsetX + fromDrawW, fromTop + fromDrawH
            )
            canvas.drawBitmap(bitmap, null, fromRect, paint)
        }

        // 绘制新页面
        animationToBitmap?.let { bitmap ->
            val toRectLeft = if (direction > 0) {
                // 向后翻（toPage > fromPage）：新页面从右侧滑入
                toLeft + width - offsetX
            } else {
                // 向前翻（toPage < fromPage）：新页面从左侧滑入
                toLeft - toDrawW - offsetX
            }
            val toRect = RectF(
                toRectLeft, toTop,
                toRectLeft + toDrawW, toTop + toDrawH
            )
            canvas.drawBitmap(bitmap, null, toRect, paint)
        }

        if (progress >= 1f) {
            // 动画结束：清理动画状态、触发 onPageChanged、停止动画循环
            isAnimating = false
            animationFromBitmap = null
            animationToBitmap = null
            prerenderCurrentPage()
            onPageChanged?.invoke(animationToPage)
            invalidate()
        } else {
            invalidate()  // 动画进行中：继续请求绘制下一帧
        }
    }

    /**
     * 占位符画笔（灰色）
     */
    private val placeholderPaint = Paint().apply { color = Color.LTGRAY }

    /**
     * 触摸事件处理（重写 View.onTouchEvent）
     *
     * 功能：先处理双指缩放手势，如果不处于缩放状态
     * 则继续处理单指手势（点击/滑动/拖动）。
     *
     * @param event Android 触摸事件
     * @return true 表示消费了触摸事件
     */
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isInitialized) return false

        // 先处理缩放手势
        scaleGestureDetector?.onTouchEvent(event)

        // 再处理单指手势（如果不处于缩放）
        if (scaleGestureDetector?.isInProgress != true) {
            gestureDetector?.onTouchEvent(event)
        }

        return true
    }

    /**
     * 视图尺寸变化回调（重写 View.onSizeChanged）
     *
     * 功能：
     * - 如果尚未初始化且有缓存仓库引用，执行延迟初始化
     * - 如果已初始化，更新视口尺寸和缩放比例，重新初始化手势检测器
     *
     * @param w    新宽度（像素）
     * @param h    新高度（像素）
     * @param oldw 旧宽度（像素）
     * @param oldh 旧高度（像素）
     */
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)

        // 检查是否有延迟的初始化的任务
        if (!isInitialized && pendingPdfDocumentRepository != null) {
            val repository = pendingPdfDocumentRepository!!
            pendingPdfDocumentRepository = null
            doInit(repository)
            return
        }

        // 已初始化：更新视口尺寸
        pageState?.updateViewportSize(w, h)
        minScale = pageState?.minScale ?: 1.0f
        currentScale = currentScale.coerceIn(minScale, pageState?.maxScale ?: 4.0f)

        // 重新初始化手势检测器（更新宽高引用）
        initGestureDetectors()
    }

    /**
     * 请求渲染指定页面（带回调包装）
     *
     * 功能：对 requestPageRender() 的简单包装，提供回调参数。
     *
     * @param pageIndex 页面索引
     * @param onComplete 渲染完成回调（无论成功或失败都会调用）
     */
    private fun requestPageRenderWithCallback(pageIndex: Int, onComplete: (Int) -> Unit) {
        requestPageRender(pageIndex, onComplete)
    }

    /**
     * 请求渲染指定页面（核心方法）
     *
     * 功能：异步渲染指定页面为 Bitmap 并存入缓存。支持渲染完成回调。
     * 渲染在后台线程（Dispatchers.IO）执行，完成后切回主线程更新缓存。
     *
     * 优化策略：
     * - 去重：跳过已有渲染任务或已在缓存的页面
     * - 并发限制：最多 4 个并发渲染任务
     * - 按需计算渲染尺寸（根据 minScale 适配屏幕宽度）
     * - 渲染失败也回调（外部可根据需要处理）
     *
     * 调用位置：
     * - prerenderCurrentPage() 中预渲染
     * - startPageAnimation() 中翻页前渲染未缓存的页面
     * - drawCurrentPage() 中缓存未命中时
     *
     * @param pageIndex  页面索引（0-based）
     * @param onComplete 渲染完成回调（可选），参数为页面索引
     */
    private fun requestPageRender(pageIndex: Int, onComplete: ((Int) -> Unit)? = null) {
        val state = pageState ?: return
        val repository = state.getPdfDocumentRepository()

        // 检查文档是否可用
        if (!repository.isDocumentAvailable()) {
            Log.w(TAG, "requestPageRender() failed: Document not available")
            return
        }

        synchronized(renderJobsLock) {
            // 去重：已有渲染任务则跳过
            if (renderJobs.containsKey(pageIndex)) {
                return
            }
            // 已在缓存中，直接回调
            if (pageTextures.containsKey(pageIndex)) {
                onComplete?.invoke(pageIndex)
                return
            }
            // 并发限制
            if (renderJobs.size >= maxConcurrentRenderJobs) {
                Log.w(TAG, "requestPageRender: page=$pageIndex, too many render jobs (${renderJobs.size}), skipped")
                onComplete?.invoke(pageIndex)
                return
            }

            // 提交后台渲染协程
            val job = scope.launch(Dispatchers.IO) {
                try {
                    val pageW = state.getPageOriginalWidth(pageIndex)
                    val pageH = state.getPageOriginalHeight(pageIndex)

                    // 计算渲染尺寸（按 minScale 适配屏幕宽度）
                    val fitScale = min(width.toFloat() / pageW, height.toFloat() / pageH)
                    val renderW = (pageW * fitScale).toInt().coerceAtLeast(1)
                    val renderH = (pageH * fitScale).toInt().coerceAtLeast(1)

                    val bitmap = Bitmap.createBitmap(renderW, renderH, Bitmap.Config.ARGB_8888)

                    // 使用 PdfDocumentRepository 渲染页面
                    val success = repository.renderPage(pageIndex, bitmap)

                    if (success) {
                        // 渲染成功：切回主线程更新缓存
                        launch(Dispatchers.Main) {
                            pageTextures[pageIndex] = bitmap
                            invalidate()
                            synchronized(renderJobsLock) { renderJobs.remove(pageIndex) }
                            onComplete?.invoke(pageIndex)
                        }
                    } else {
                        // 渲染失败：回收 Bitmap
                        bitmap.recycle()
                        synchronized(renderJobsLock) { renderJobs.remove(pageIndex) }
                        launch(Dispatchers.Main) {
                            Log.w(TAG, "requestPageRender: failed page=$pageIndex (renderPage returned false)")
                            onComplete?.invoke(pageIndex)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to render page $pageIndex", e)
                    synchronized(renderJobsLock) { renderJobs.remove(pageIndex) }
                }
            }
            renderJobs[pageIndex] = job
        }
    }

    /**
     * 预渲染当前页及其邻居页
     *
     * 功能：渲染当前页及其前后各 3 页，共 7 页。
     * 确保翻页时目标页尽可能已经在缓存中，减少等待时间。
     *
     * 调用位置：
     * - doInit() 初始化完成后
     * - executePageAnimation() 翻页动画开始时
     * - drawPageAnimation() 动画结束后
     * - jumpToPage() 跳转页面后
     */
    private fun prerenderCurrentPage() {
        val state = pageState ?: return

        // 检查文档是否可用
        if (!state.getPdfDocumentRepository().isDocumentAvailable()) {
            return
        }

        val currentPage = state.currentPage

        // 预加载范围：前 3 页 + 当前页 + 后 3 页

        // 渲染当前页
        requestPageRender(currentPage)

        // 渲染前一页、前二页、前三页
        if (currentPage > 0) requestPageRender(currentPage - 1)
        if (currentPage > 1) requestPageRender(currentPage - 2)
        if (currentPage > 2) requestPageRender(currentPage - 3)

        // 渲染后一页、后二页、后三页
        if (currentPage < (state.totalPages - 1)) requestPageRender(currentPage + 1)
        if (currentPage < (state.totalPages - 2)) requestPageRender(currentPage + 2)
        if (currentPage < (state.totalPages - 3)) requestPageRender(currentPage + 3)
    }

    /**
     * 获取系统背景色
     *
     * 功能：根据系统深色/浅色模式返回对应的背景色。
     *
     * @return 深色模式返回 Color.BLACK，浅色模式返回 Color.WHITE
     */
    private fun getSystemBackgroundColor(): Int {
        val nightMode = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
        return if (nightMode == android.content.res.Configuration.UI_MODE_NIGHT_YES) Color.BLACK else Color.WHITE
    }

    /**
     * 获取当前页面索引
     *
     * @return 当前页面索引（0-based），未初始化时返回 0
     */
    fun currentPage(): Int = pageState?.currentPage ?: 0

    /**
     * 获取总页数
     *
     * @return 总页数，未初始化时返回 0
     */
    fun getTotalPages(): Int = pageState?.totalPages ?: 0

    /**
     * 跳转到指定页面（公开接口）
     *
     * 功能：跳转到指定页面，重置缩放和滚动偏移。
     * 如果视图尚未初始化，缓存目标页码，等初始化完成后自动执行。
     *
     * 调用位置：ReaderScreenV2.kt 第 168-169 行（处理 pendingPageJump）
     * 使用场景：
     * - 初始化完成后自动跳转到上次阅读位置
     * - 外部通过页面跳转表单输入页码
     * - 切换阅读模式后保持页码
     *
     * @param pageIndex 目标页面索引（0-based）
     */
    fun jumpToPage(pageIndex: Int) {
        if (!isInitialized) {
            // 视图尚未初始化，缓存页码，初始化完成后补执行
            pendingJumpToPage = pageIndex
            return
        }

        pageState?.jumpToPage(pageIndex)
        // 跳转后重置缩放
        currentScale = pageState?.minScale ?: 1.0f
        // 预渲染新页面附近的内容
        prerenderCurrentPage()
        invalidate()
        // 通知页码变化
        onPageChanged?.invoke(pageIndex)
    }

    /**
     * 清理资源
     *
     * 功能：
     * 1. 取消所有正在进行的渲染协程
     * 2. 清空页面纹理缓存并回收所有 Bitmap
     * 3. 释放手势检测器引用
     * 4. 清空状态管理器和待处理跳转
     *
     * 注意：不取消 CoroutineScope（由调用者管理，如 viewModelScope）
     *
     * 调用位置：onDetachedFromWindow() 中调用
     * 使用场景：视图从窗口分离时释放资源
     */
    fun cleanup() {
        synchronized(renderJobsLock) {
            for (job in renderJobs.values) job.cancel()
            renderJobs.clear()
        }
        pageTextures.clearAll()
        gestureDetector = null
        scaleGestureDetector = null
        pageState = null
        isInitialized = false
        pendingJumpToPage = null
    }

    /**
     * 视图从窗口分离回调（重写 View.onDetachedFromWindow）
     *
     * 功能：视图从窗口分离时自动调用 cleanup() 释放资源。
     */
    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        cleanup()
    }
}
