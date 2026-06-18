package com.example.pdfmanager.ui.screen.reader

import android.graphics.PointF
import android.util.Log
import kotlin.math.min

/**
 * 单页模式状态管理
 *
 * ===== 特性 =====
 * 1. 容器大小 = 视口大小（全屏）
 * 2. 内容等比例放大直到一边顶格并居中显示
 * 3. 视口不能超出容器边界
 * 4. 支持双指缩放（缩放范围 [minScale, maxScale]）
 * 5. 1x 时单指拖动触发翻页；放大后单指用于平移
 *
 * ===== 与 ContinuousCanvasState 的区别 =====
 * - ContinuousCanvasState：所有页面垂直排列，滚动浏览
 * - SinglePageState：一次只显示一页，居中放大/缩小
 *
 * ===== 调用位置 =====
 * - SinglePageView.kt 第 59 行声明成员变量
 * - SinglePageView.kt 第 182 行创建实例
 *
 * ===== 使用场景 =====
 * 仅用于单页模式（SinglePageMode），一次只阅读一页，
 * 常见于支持缩放的电子书阅读器。
 *
 * @param pdfDocumentRepository PDF 文档仓库（统一管理 PdfRenderer 生命周期）
 * @param viewportWidth         视口宽度（像素）
 * @param viewportHeight        视口高度（像素）
 */
class SinglePageState(
    private val pdfDocumentRepository: PdfDocumentRepository,
    private var viewportWidth: Int,
    private var viewportHeight: Int
) {
    companion object {
        /** 日志标签 */
        private const val TAG = "SinglePageState"
    }

    /**
     * 总页数
     *
     * 通过 PdfDocumentRepository 获取，带空文档检查。
     */
    val totalPages: Int get() = pdfDocumentRepository.getPageCount()

    /** 当前显示的页面索引（0-based，外部只读） */
    var currentPage: Int = 0
        private set

    /**
     * 最小缩放比例
     *
     * 内容刚好填满容器的最小缩放值。
     * 取两个方向缩放值中的较小者，确保内容完整可见。
     * 即 minScale = min(viewportWidth/pageWidth, viewportHeight/pageHeight)
     */
    var minScale: Float = 1.0f
        private set

    /** 最大允许的缩放比例 */
    val maxScale: Float = 4.0f

    /** 当前缩放比例，初始值 = minScale */
    var currentScale: Float = 1.0f
        private set

    /** 当前 X 轴滚动偏移量（像素） */
    var scrollX: Float = 0f
        private set

    /** 当前 Y 轴滚动偏移量（像素） */
    var scrollY: Float = 0f
        private set

    /**
     * 获取 PdfDocumentRepository
     *
     * 提供给外部类（SinglePageView 的纹理渲染逻辑）使用。
     *
     * @return PdfDocumentRepository 实例
     */
    fun getPdfDocumentRepository(): PdfDocumentRepository = pdfDocumentRepository

    /**
     * 获取指定页面的原始宽度（PDF 坐标系）
     *
     * @param pageIndex 页面索引
     * @return 页面原始宽度，如果文档不可用或索引无效则返回 0
     */
    fun getPageOriginalWidth(pageIndex: Int): Int {
        if (!pdfDocumentRepository.isDocumentAvailable()) {
            Log.w(TAG, "getPageOriginalWidth: document not available")
            return 0
        }
        val page = pdfDocumentRepository.openPage(pageIndex) ?: return 0
        val width = page.width
        page.close()
        return width
    }

    /**
     * 获取指定页面的原始高度（PDF 坐标系）
     *
     * @param pageIndex 页面索引
     * @return 页面原始高度，如果文档不可用或索引无效则返回 0
     */
    fun getPageOriginalHeight(pageIndex: Int): Int {
        if (!pdfDocumentRepository.isDocumentAvailable()) {
            Log.w(TAG, "getPageOriginalHeight: document not available")
            return 0
        }
        val page = pdfDocumentRepository.openPage(pageIndex) ?: return 0
        val height = page.height
        page.close()
        return height
    }

    /**
     * 容器坐标 → 屏幕坐标
     *
     * 将 1x 画布坐标系中的点转换为屏幕像素坐标。
     * 变换公式：screen = container1x * scale + scrollOffset
     *
     * @param containerX1x 容器 X 坐标（1x 坐标系）
     * @param containerY1x 容器 Y 坐标（1x 坐标系）
     * @param scale        当前缩放比例
     * @param scrollX      X 轴滚动偏移
     * @param scrollY      Y 轴滚动偏移
     * @return 屏幕坐标 PointF
     */
    fun containerToScreen(
        containerX1x: Float,
        containerY1x: Float,
        scale: Float,
        scrollX: Float,
        scrollY: Float
    ): PointF {
        return PointF(
            containerX1x * scale + scrollX,
            containerY1x * scale + scrollY
        )
    }

    init {
        // 延迟计算 minScale，等 viewportWidth/Height > 0 后再算
        // 避免在构造函数时 viewport 尚未确定导致除以 0
        if (viewportWidth > 0 && viewportHeight > 0) {
            updateFitScale()
            currentScale = minScale
        }
    }

    /**
     * 计算适配缩放比例（使内容刚好填满容器）
     *
     * 取宽度方向缩放和高度方向缩放中的较小者：
     * scaleX = viewportWidth / pageWidth
     * scaleY = viewportHeight / pageHeight
     * minScale = min(scaleX, scaleY)
     *
     * 使页面内容完整可见，且至少在一个方向上填满屏幕。
     */
    private fun updateFitScale() {
        if (totalPages <= 0) return

        // 获取第一页的尺寸来计算 minScale
        val firstPageWidth = getPageOriginalWidth(0)
        val firstPageHeight = getPageOriginalHeight(0)

        if (firstPageWidth <= 0 || firstPageHeight <= 0) return

        // 计算缩放比例，使内容等比例缩放后刚好填满容器
        val scaleX = viewportWidth.toFloat() / firstPageWidth
        val scaleY = viewportHeight.toFloat() / firstPageHeight
        minScale = min(scaleX, scaleY)
    }

    /**
     * 更新缩放比例
     *
     * 将新缩放值钳制到 [minScale, maxScale] 范围后，
     * 重新限制滚动偏移。
     *
     * @param newScale 新的缩放比例
     */
    fun updateScale(newScale: Float) {
        currentScale = newScale.coerceIn(minScale, maxScale)
        clampScroll()
    }

    /**
     * 更新视口尺寸（例如屏幕旋转时）
     *
     * 重新计算最小缩放比例、钳制当前缩放和滚动偏移。
     *
     * @param newWidth  新的视口宽度（像素）
     * @param newHeight 新的视口高度（像素）
     */
    fun updateViewportSize(newWidth: Int, newHeight: Int) {
        viewportWidth = newWidth
        viewportHeight = newHeight

        // 重新计算最小缩放比例
        updateFitScale()

        // 钳制当前缩放
        currentScale = currentScale.coerceIn(minScale, maxScale)

        clampScroll()
    }

    /**
     * 限制滚动范围
     *
     * 确保缩放后内容的平移不会使页面完全移出视口。
     * 滚动范围基于缩放后内容尺寸与视口尺寸的差值：
     * maxScrollX = max(0, (drawWidth - viewportWidth) / 2)
     * maxScrollY = max(0, (drawHeight - viewportHeight) / 2)
     *
     * 如果内容比视口小（缩放到最小），则不允许滚动。
     */
    fun clampScroll() {
        // 计算内容在屏幕上的实际尺寸
        if (totalPages <= 0 || currentPage < 0 || currentPage >= totalPages) return

        val pageWidth = getPageOriginalWidth(currentPage).toFloat()
        val pageHeight = getPageOriginalHeight(currentPage).toFloat()

        val drawWidth = pageWidth * currentScale
        val drawHeight = pageHeight * currentScale

        // 限制滚动范围，使内容不会完全移出屏幕
        // 当内容大于视口时，允许在一定范围内平移查看被遮挡的部分
        val maxScrollX = maxOf(0f, (drawWidth - viewportWidth) / 2f)
        val maxScrollY = maxOf(0f, (drawHeight - viewportHeight) / 2f)

        scrollX = scrollX.coerceIn(-maxScrollX, maxScrollX)
        scrollY = scrollY.coerceIn(-maxScrollY, maxScrollY)
    }

    /**
     * 更新滚动偏移量
     *
     * @param newScrollX 新的 X 轴滚动偏移
     * @param newScrollY 新的 Y 轴滚动偏移
     */
    fun updateScroll(newScrollX: Float, newScrollY: Float) {
        scrollX = newScrollX
        scrollY = newScrollY
        clampScroll()
    }

    /**
     * 跳转到指定页面
     *
     * 切换到新页面并重置滚动偏移和缩放比例。
     * 新的缩放比例基于新页面的尺寸重新计算。
     *
     * @param pageIndex 目标页面索引（0-based）
     */
    fun jumpToPage(pageIndex: Int) {
        if (pageIndex < 0 || pageIndex >= totalPages) return
        currentPage = pageIndex
        // 重置滚动偏移
        scrollX = 0f
        scrollY = 0f
        // 更新缩放比例
        updateFitScale()
        currentScale = minScale
    }

    /**
     * 检查当前是否处于最小缩放（1x）状态
     *
     * 用于在 SinglePageView 中判断是否应该响应翻页手势：
     * - 最小缩放时：单指拖动触发翻页
     * - 放大后：单指拖动触发平移
     *
     * @return true 表示当前为最小缩放（差距 < 0.01）
     */
    fun isAtMinScale(): Boolean {
        return kotlin.math.abs(currentScale - minScale) < 0.01f
    }
}
