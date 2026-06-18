package com.example.pdfmanager.ui.screen.reader

import android.content.Context
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.Scroller
import androidx.core.view.GestureDetectorCompat
import kotlin.math.max

/**
 * 连续画布手势处理器
 *
 * ===== 功能说明 =====
 * 负责处理连续滚动模式下 PDF 阅读器的所有触摸手势交互，包括：
 * 1. 双指缩放（利用 ScaleGestureDetector）
 * 2. 单指拖动（利用 GestureDetector）
 * 3. 惯性滚动（利用 Scroller 实现 fling 效果）
 * 4. 单击/双击事件
 *
 * ===== 调用位置 =====
 * - PdfContinuousView.kt 第 102 行声明成员变量
 * - PdfContinuousView.kt 第 189-194 行创建实例并注册回调
 * - 在 PdfContinuousView 的 onTouchEvent() 中通过手势分发将 event 传至此处
 *
 * ===== 使用场景 =====
 * 仅用于连续滚动模式（ContinueMode），与 ContinuousCanvasState 配合使用。
 * ContinuousCanvasState 负责状态数据（缩放比、滚动偏移等），
 * 本类负责从底层手势输入解析出这些状态的变化。
 *
 * @param context       Android 上下文，用于初始化 GestureDetector / ScaleGestureDetector / Scroller
 * @param onScroll      滚动回调 —— 当手指拖动时触发，参数为 (deltaX, deltaY)
 * @param onScale       缩放回调 —— 当双指缩放时触发，参数为 (scaleFactor, focusX, focusY)
 * @param onScaleEnd    缩放结束回调 —— 双指离开时触发，无参数
 * @param onFling       惯性滚动回调 —— 快速滑动后触发，参数为 (velocityX, velocityY)
 * @param onSingleTap   单击回调 —— 确认单击时触发，参数为 (tapX, tapY)，屏幕坐标
 * @param onDoubleTap   双击回调 —— 双击时触发，参数为 (tapX, tapY)，屏幕坐标
 */
class ContinuousCanvasGestureHandler(
    context: Context,
    private val onScroll: (Float, Float) -> Unit,
    private val onScale: (Float, Float, Float) -> Unit,
    private val onScaleEnd: () -> Unit,
    private val onFling: (Float, Float) -> Unit,
    private val onSingleTap: (Float, Float) -> Unit,
    private val onDoubleTap: (Float, Float) -> Unit
) {
    /** 当前缩放比例（外部只读，内部可修改） */
    var scale: Float = 1.0f
        private set

    /** 当前 X 轴滚动偏移量（像素，一般为负值或 0） */
    var scrollX: Float = 0f
    /** 当前 Y 轴滚动偏移量（像素，一般为负值或 0） */
    var scrollY: Float = 0f

    /** 最小允许的缩放比例（外部只读，通过 setScaleRange 设置） */
    var minScale: Float = 1.0f
        private set

    /** 最大允许的缩放比例（外部只读，通过 setScaleRange 设置） */
    var maxScale: Float = 5.0f
        private set

    /** 画布总宽度（外部只读，通过 setDimensions 设置） */
    var canvasWidth: Float = 0f
        private set

    /** 画布总高度（外部只读，通过 setDimensions 设置） */
    var canvasHeight: Float = 0f
        private set

    /** 视口宽度（外部只读，通过 setDimensions 设置） */
    var viewportWidth: Float = 0f
        private set

    /** 视口高度（外部只读，通过 setDimensions 设置） */
    var viewportHeight: Float = 0f
        private set

    /** 双指缩放手势检测器（Android 原生） */
    private val scaleDetector: ScaleGestureDetector

    /** 手势检测器（兼容包，用于滚动/滑动/单击/双击） */
    private val gestureDetector: GestureDetectorCompat

    /** 惯性滚动计算器（Android 原生，用于 fling 动画插值） */
    private val scroller: Scroller = Scroller(context)

    /** 是否正在双指缩放中 */
    private var isScaling = false

    /** 缩放焦点 X 坐标（屏幕坐标系） */
    private var scaleFocusX = 0f

    /** 缩放焦点 Y 坐标（屏幕坐标系） */
    private var scaleFocusY = 0f

    init {
        // ===== 初始化双指缩放手势检测器 =====
        scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {

            /**
             * 缩放开始回调
             *
             * @param detector 缩放手势检测器
             * @return true 表示消费此事件
             */
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                isScaling = true
                scaleFocusX = detector.focusX
                scaleFocusY = detector.focusY
                return true
            }

            /**
             * 缩放进行中回调
             *
             * @param detector 缩放手势检测器，通过 detector.scaleFactor 获取缩放因子
             * @return true 表示消费此事件
             */
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val scaleFactor = detector.scaleFactor
                scaleFocusX = detector.focusX
                scaleFocusY = detector.focusY

                // 钳制缩放比例到 [minScale, maxScale] 范围内
                val newScale = (scale * scaleFactor).coerceIn(minScale, maxScale)
                scale = newScale

                onScale(scaleFactor, scaleFocusX, scaleFocusY)
                return true
            }

            /**
             * 缩放结束回调
             *
             * @param detector 缩放手势检测器
             */
            override fun onScaleEnd(detector: ScaleGestureDetector) {
                isScaling = false
                onScaleEnd()
            }
        })

        // ===== 初始化手势检测器（滚动/滑动/单击/双击） =====
        gestureDetector = GestureDetectorCompat(context, object : GestureDetector.SimpleOnGestureListener() {

            /**
             * 手指按下回调
             *
             * 核心修复：检测到触摸时立即中断正在进行的惯性滚动，
             * 防止惯性滚动与新的拖动操作冲突。
             *
             * @param e 触摸事件
             * @return true 表示消费此事件
             */
            override fun onDown(e: MotionEvent): Boolean {
                // 核心修复：检测到触摸时立即中断惯性滚动
                if (!scroller.isFinished) {
                    scroller.forceFinished(true)
                }
                return true
            }

            /**
             * 手指拖动回调
             *
             * 将 distanceX/distanceY（手指移动的物理距离）取反，
             * 转换为画布滚动偏移量（scrollX/scrollY）的增量。
             *
             * @param e1        上一次触摸事件
             * @param e2        当前触摸事件
             * @param distanceX 手指在 X 轴上的移动距离
             * @param distanceY 手指在 Y 轴上的移动距离
             * @return true 表示消费此事件
             */
            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
                // 如果在缩放中，不处理拖动
                if (isScaling) return false

                // 防御性检查：如果惯性滚动还在运行，强制停止（理论上 onDown 已经处理了）
                if (!scroller.isFinished) {
                    scroller.forceFinished(true)
                }

                // distanceX/distanceY 是手指移动的物理距离，取反得到画布滚动方向
                val deltaX = -distanceX
                val deltaY = -distanceY

                scrollX += deltaX
                scrollY += deltaY
                clampScroll()
                onScroll(deltaX, deltaY)
                return true
            }

            /**
             * 快速滑动（fling）回调
             *
             * 将速度传递给 Scroller 以计算惯性滚动动画，
             * 同时通过 onFling 回调通知外部（如启动动画循环）。
             *
             * @param e1        按下事件
             * @param e2        抬起事件
             * @param velocityX X 轴滑动速度（像素/秒）
             * @param velocityY Y 轴滑动速度（像素/秒）
             * @return true 表示消费此事件
             */
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (isScaling) return false

                // 启动 Scroller 的惯性滚动计算
                // 使用极值范围（±Int.MAX_VALUE/2）表示不限制滚动边界，
                // 实际的边界限制由 clampScroll() 保证
                scroller.fling(
                    scrollX.toInt(), scrollY.toInt(),
                    velocityX.toInt(), velocityY.toInt(),
                    -Int.MAX_VALUE / 2, Int.MAX_VALUE / 2,
                    -Int.MAX_VALUE / 2, Int.MAX_VALUE / 2
                )
                onFling(velocityX, velocityY)
                return true
            }

            /**
             * 单击确认回调
             *
             * GestureDetector 在确保不是双击或长按后才会触发此回调。
             *
             * @param e 触摸事件
             * @return true 表示消费此事件
             */
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                onSingleTap(e.x, e.y)
                return true
            }

            /**
             * 双击回调
             *
             * @param e 触摸事件
             * @return true 表示消费此事件
             */
            override fun onDoubleTap(e: MotionEvent): Boolean {
                onDoubleTap(e.x, e.y)
                return true
            }
        })
    }

    /**
     * 触摸事件分发入口
     *
     * 外部（PdfContinuousView）在 onTouchEvent() 中调用此方法，
     * 将设备触摸事件传递给内部的手势检测器处理。
     *
     * 调用位置：PdfContinuousView 的 onTouchEvent() 中
     *
     * @param event Android MotionEvent 触摸事件
     * @return true 表示消费了该事件
     */
    fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)

        // 手指抬起或取消时，重置缩放状态
        if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
            isScaling = false
        }
        return true
    }

    /**
     * 按增量滚动
     *
     * 调用位置：PdfContinuousView 的惯性滚动动画循环中（computeScroll 相关逻辑）
     *
     * @param deltaX X 轴滚动增量（像素）
     * @param deltaY Y 轴滚动增量（像素）
     */
    fun scrollBy(deltaX: Float, deltaY: Float) {
        scrollX += deltaX
        scrollY += deltaY
        clampScroll()
    }

    /**
     * 滚动到指定位置
     *
     * @param x 目标 X 坐标（像素）
     * @param y 目标 Y 坐标（像素）
     */
    fun scrollTo(x: Float, y: Float) {
        scrollX = x
        scrollY = y
        clampScroll()
    }

    /**
     * 钳制滚动偏移量到合法范围
     *
     * 确保 scrollX ∈ [-maxScrollX, 0]，scrollY ∈ [-maxScrollY, 0]，
     * 即画布不能超出视口边界。
     */
    fun clampScroll() {
        val maxScrollX = max(0f, canvasWidth - viewportWidth)
        val maxScrollY = max(0f, canvasHeight - viewportHeight)
        scrollX = scrollX.coerceIn(-maxScrollX, 0f)
        scrollY = scrollY.coerceIn(-maxScrollY, 0f)
    }

    /**
     * 设置画布和视口尺寸
     *
     * 这些尺寸决定了 scrollX/scrollY 的钳制范围。
     * 在 PdfContinuousView 布局变化或缩放变化后调用。
     *
     * 调用位置：PdfContinuousView 的 onSizeChanged / 缩放更新逻辑中
     *
     * @param canvasW    画布总宽度（像素，= maxPageWidth * scale）
     * @param canvasH    画布总高度（像素，= totalHeight1x * scale）
     * @param viewportW  视口宽度（像素，= View 的宽度）
     * @param viewportH  视口高度（像素，= View 的高度）
     */
    fun setDimensions(canvasW: Float, canvasH: Float, viewportW: Float, viewportH: Float) {
        canvasWidth = canvasW
        canvasHeight = canvasH
        viewportWidth = viewportW
        viewportHeight = viewportH
        clampScroll()
    }

    /**
     * 设置缩放范围
     *
     * @param min 最小缩放比例
     * @param max 最大缩放比例
     */
    fun setScaleRange(min: Float, max: Float) {
        minScale = min
        maxScale = max
        scale = scale.coerceIn(minScale, maxScale)
    }

    /**
     * 惯性滚动计算
     *
     * 每次动画帧调用此方法，计算 Scroller 当前帧的滚动位置。
     * 通常在 View.computeScroll() 中调用。
     *
     * 调用位置：PdfContinuousView 的 computeScroll() 中
     *
     * @return true 表示惯性滚动尚未结束，需要继续动画
     */
    fun computeScroll(): Boolean {
        if (scroller.computeScrollOffset()) {
            scrollX = scroller.currX.toFloat()
            scrollY = scroller.currY.toFloat()
            clampScroll()
            onScroll(scrollX - scroller.currX, scrollY - scroller.currY)
            return true
        }
        return false
    }

    /**
     * 释放资源
     *
     * 立即中止正在进行的惯性滚动动画。
     * 在 View detached from window 时调用。
     *
     * 调用位置：PdfContinuousView 的 onDetachedFromWindow 或清理逻辑中
     */
    fun recycle() {
        scroller.abortAnimation()
    }
}
