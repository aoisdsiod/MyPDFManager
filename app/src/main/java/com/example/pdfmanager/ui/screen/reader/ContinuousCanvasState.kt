package com.example.pdfmanager.ui.screen.reader

import android.graphics.PointF
import android.graphics.pdf.PdfRenderer
import android.util.Log
import kotlin.math.max

/**
 * 连续画布状态管理（三层抽象模型）
 *
 * ===== 三层抽象模型 =====
 * 1. PDF 内容层：第 i 页原始尺寸 (pageW_i, pageH_i)
 * 2. 统一容器层：宽度固定 maxW（所有页最大宽度），高度 = pageH_i
 * 3. 屏幕视口层：设备物理尺寸 screenW × screenH
 *
 * ===== 布局规则 =====
 * - 所有容器左对齐（x=0），垂直方向紧密排列
 * - PDF 内容在容器内水平居中：(maxW - pageW_i) / 2
 * - PDF 内容在容器内顶格对齐：垂直偏移 = 0
 * - 容器内未被内容覆盖的区域绘制主题背景色
 *
 * ===== 坐标变换公式 =====
 * screenX = canvasX1x * currentScale + scrollX
 * screenY = canvasY1x * currentScale + scrollY
 *
 * ===== 调用位置 =====
 * - PdfContinuousView.kt 第 57 行声明成员变量
 * - PdfContinuousView.kt 第 184 行创建实例（在 doInit 中）
 * - 在 PdfContinuousView 的滚动、缩放、渲染逻辑中频繁调用
 *
 * ===== 使用场景 =====
 * 仅用于连续滚动模式（ContinueMode），与 ContinuousCanvasGestureHandler 配合使用。
 * ContinuousCanvasGestureHandler 负责从手势输入解析变化，
 * 本类负责维护和计算状态数据。
 *
 * @param pdfDocumentRepository PDF 文档仓库，统一管理 PdfRenderer 生命周期
 * @param viewportWidth         视口宽度（像素）
 * @param viewportHeight        视口高度（像素）
 * @param pageSpacing           页面间距（像素，在 1x 坐标系下）
 */
class ContinuousCanvasState(
    private val pdfDocumentRepository: PdfDocumentRepository,
    private var viewportWidth: Int,
    private var viewportHeight: Int,
    private val pageSpacing: Int = 20
) {
    /**
     * 总页数
     *
     * 通过 PdfDocumentRepository 获取，带空文档检查。
     * 如果文档未打开或已关闭，返回 0。
     */
    val totalPages: Int get() = pdfDocumentRepository.getPageCount()

    /**
     * 每页的原始宽度（PDF 坐标系，单位：point）
     *
     * key = pageIndex, value = 页面原始宽度
     * 在 init 时通过 calculatePageOriginalSizes() 初始化
     */
    private val pageOriginalWidths = mutableMapOf<Int, Int>()

    /**
     * 每页的原始高度（PDF 坐标系，单位：point）
     *
     * key = pageIndex, value = 页面原始高度
     * 在 init 时通过 calculatePageOriginalSizes() 初始化
     */
    private val pageOriginalHeights = mutableMapOf<Int, Int>()

    /**
     * 最大页面宽度，即所有页面原始宽度的最大值
     *
     * 在 1x 坐标系下每个容器的宽度都固定为此值（统一容器宽度 maxW）
     */
    private var maxPageWidth: Int = 0
        private set

    /**
     * 最小缩放比例
     *
     * 定义为 1x 时画布宽度正好等于屏幕宽度，
     * 即 minScale = viewportWidth / maxPageWidth
     */
    var minScale: Float = 1.0f
        private set

    /** 最大允许的缩放比例 */
    val maxScale: Float = 4.0f

    /** 当前缩放比例，初始值 = minScale */
    var currentScale: Float = 1.0f
        private set

    /**
     * 每个容器在 1x 画布坐标系中的 Y 坐标
     *
     * key = pageIndex, value = 该容器顶部在 1x 画布中的 Y 坐标
     * 用于将页面索引转换为垂直位置
     */
    private val containerTops1x = mutableMapOf<Int, Float>()

    /**
     * 容器 Y 坐标的有序数组
     *
     * 从 containerTops1x 提取构建，用于二分查找（getTopVisiblePage 中调用）
     */
    var containerTops1xArray: FloatArray = FloatArray(0)
        private set

    /**
     * 连续画布总高度（1x 坐标系下）
     *
     * = 最后一页容器的底部 Y 坐标
     * = containerTops1x[lastPage] + pageHeight[lastPage]
     */
    var totalHeight1x: Float = 0f
        private set

    /** 当前 X 轴滚动偏移量（像素，一般为负值） */
    var scrollX: Float = 0f
        private set

    /** 当前 Y 轴滚动偏移量（像素，一般为负值） */
    var scrollY: Float = 0f
        private set

    /**
     * 当前可见的页面索引集合
     *
     * 由 updateVisiblePages() 根据当前 scrollX/scrollY 和 viewport 尺寸计算得出。
     * 用于指导纹理缓存层只渲染可见区域的页面。
     */
    var visiblePages: Set<Int> = emptySet()
        private set

    /**
     * 获取 PdfDocumentRepository
     *
     * 提供给外部类（PdfContinuousView 的纹理渲染逻辑）使用。
     *
     * @return PdfDocumentRepository 实例
     */
    fun getPdfDocumentRepository(): PdfDocumentRepository = pdfDocumentRepository

    /**
     * 获取最大页面宽度（即容器宽度 maxW）
     *
     * @return 最大页面宽度（PDF 坐标系）
     */
    fun getMaxPageWidth(): Int = maxPageWidth

    /**
     * 获取指定页面的原始宽度（PDF 坐标系）
     *
     * @param pageIndex 页面索引
     * @return 页面原始宽度，如果索引无效则返回 0
     */
    fun getPageOriginalWidth(pageIndex: Int): Int = pageOriginalWidths[pageIndex] ?: 0

    /**
     * 获取指定页面的原始高度（PDF 坐标系）
     *
     * @param pageIndex 页面索引
     * @return 页面原始高度，如果索引无效则返回 0
     */
    fun getPageOriginalHeight(pageIndex: Int): Int = pageOriginalHeights[pageIndex] ?: 0

    /**
     * 获取指定容器在 1x 画布中的 Y 坐标
     *
     * @param pageIndex 页面索引
     * @return 容器顶部 Y 坐标（1x 画布坐标系），如果索引无效则返回 0f
     */
    fun getContainerTop1x(pageIndex: Int): Float = containerTops1x[pageIndex] ?: 0f

    /**
     * 获取指定页面内容在容器中的水平偏移（1x 坐标系下）
     *
     * 计算公式：(maxW - pageW_i) / 2
     * 用于在容器内水平居中显示 PDF 内容
     *
     * @param pageIndex 页面索引
     * @return 内容在容器内的 X 偏移量
     */
    fun getContentLeft1x(pageIndex: Int): Float {
        val pageW = pageOriginalWidths[pageIndex] ?: 0
        return (maxPageWidth - pageW) / 2f
    }

    /**
     * 获取指定页面内容在容器中的垂直偏移（1x 坐标系下）
     *
     * 顶格对齐，始终返回 0f
     *
     * @param pageIndex 页面索引（未使用，仅用于接口一致性）
     * @return 始终为 0f
     */
    fun getContentTop1x(pageIndex: Int): Float = 0f

    /**
     * 获取需要预渲染的页面列表
     *
     * 按优先级排序：当前页 > 后1页 > 前1页 > 后2页 > 前2页 > 后3页 > 前3页 > 后4页 > 后5页
     * 这个优先级策略确保用户最可能看到的页面最先被渲染。
     *
     * 调用位置：PdfContinuousView 的纹理缓存更新逻辑中
     *
     * @param currentPage 当前页面索引
     * @return 需要预渲染的页面索引列表（按优先级降序排列）
     */
    fun getPagesToPrerender(currentPage: Int): List<Int> {
        // 检查文档是否可用
        if (!pdfDocumentRepository.isDocumentAvailable()) {
            Log.w("ContinuousCanvasState", "Document not available in getPagesToPrerender()")
            return emptyList()
        }

        val pages = mutableListOf<Int>()
        val added = mutableSetOf<Int>()

        /**
         * 辅助函数：添加页面到列表
         *
         * 如果页面索引在有效范围内且未添加过，则添加
         *
         * @param index 页面索引
         */
        fun addPage(index: Int) {
            if (index in 0 until totalPages && index !in added) {
                pages.add(index)
                added.add(index)
            }
        }

        // 优先级 1：当前页
        addPage(currentPage)

        // 优先级 2-9：后1页、前1页、后2页、前2页、后3页、前3页、后4页、后5页
        val offsets = listOf(1, -1, 2, -2, 3, -3, 4, 5)
        for (offset in offsets) {
            addPage(currentPage + offset)
        }

        return pages
    }

    /**
     * 容器坐标 → 屏幕坐标
     *
     * 将 1x 画布坐标系中的点转换为屏幕像素坐标。
     *
     * 变换公式：
     * screenX = containerX1x * scale + scrollX
     * screenY = containerY1x * scale + scrollY
     *
     * @param containerX1x 容器的 X 坐标（1x 画布坐标系）
     * @param containerY1x 容器的 Y 坐标（1x 画布坐标系）
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

    /**
     * 内容坐标 → 屏幕坐标
     *
     * 将页面内容的局部坐标转换为屏幕像素坐标。
     * 需要考虑内容在容器内的居中偏移和容器在画布中的偏移。
     *
     * @param pageIndex  页面索引
     * @param contentX1x 内容的局部 X 坐标（1x 坐标系，相对于内容左上角）
     * @param contentY1x 内容的局部 Y 坐标（1x 坐标系，相对于内容左上角）
     * @param scale      当前缩放比例
     * @param scrollX    X 轴滚动偏移
     * @param scrollY    Y 轴滚动偏移
     * @return 屏幕坐标 PointF
     */
    fun contentToScreen(
        pageIndex: Int,
        contentX1x: Float,
        contentY1x: Float,
        scale: Float,
        scrollX: Float,
        scrollY: Float
    ): PointF {
        val containerTop1x = getContainerTop1x(pageIndex)
        val contentLeft1x = getContentLeft1x(pageIndex)

        val screenX = (contentLeft1x + contentX1x) * scale + scrollX
        val screenY = (containerTop1x + contentY1x) * scale + scrollY

        return PointF(screenX, screenY)
    }

    /**
     * 屏幕坐标 → 1x 画布坐标
     *
     * 屏幕坐标的逆变换，将像素坐标还原为 1x 画布坐标系中的点。
     *
     * 逆变换公式：
     * canvasX1x = (screenX - scrollX) / scale
     * canvasY1x = (screenY - scrollY) / scale
     *
     * @param screenX 屏幕 X 坐标
     * @param screenY 屏幕 Y 坐标
     * @param scale   当前缩放比例
     * @param scrollX X 轴滚动偏移
     * @param scrollY Y 轴滚动偏移
     * @return 1x 画布坐标 PointF
     */
    fun screenToCanvas1x(
        screenX: Float,
        screenY: Float,
        scale: Float,
        scrollX: Float,
        scrollY: Float
    ): PointF {
        return PointF(
            (screenX - scrollX) / scale,
            (screenY - scrollY) / scale
        )
    }

    init {
        // 检查文档是否可用
        if (!pdfDocumentRepository.isDocumentAvailable()) {
            Log.e("ContinuousCanvasState", "Document not available in init()")
            throw IllegalStateException("Document not available")
        }

        // 计算每页的原始尺寸和最大宽度
        calculatePageOriginalSizes()
        // 计算最小缩放比例
        calculateMinScale()
        // 计算容器布局（1x 坐标系下）
        calculateContainerLayout()
        // 设置当前缩放为最小缩放
        currentScale = minScale
    }

    /**
     * 计算每页的原始尺寸和最大宽度
     *
     * 遍历所有页面，读取原始宽度和高度，同时记录最大宽度。
     * 在 init 时调用一次。
     */
    private fun calculatePageOriginalSizes() {
        var maxW = 0
        for (i in 0 until totalPages) {
            val page = pdfDocumentRepository.openPage(i) ?: continue
            val originalWidth = page.width
            val originalHeight = page.height
            page.close()

            pageOriginalWidths[i] = originalWidth
            pageOriginalHeights[i] = originalHeight

            if (originalWidth > maxW) {
                maxW = originalWidth
            }
        }
        maxPageWidth = maxW
    }

    /**
     * 计算最小缩放比例
     *
     * 1x 定义为：画布宽度（即最大页面宽度）正好填满屏幕宽度。
     * minScale = viewportWidth / maxPageWidth
     *
     * 使得最小缩放下，所有页面水平撑满屏幕。
     */
    private fun calculateMinScale() {
        minScale = if (maxPageWidth > 0) {
            viewportWidth.toFloat() / maxPageWidth.toFloat()
        } else {
            1.0f
        }
    }

    /**
     * 计算容器布局（1x 坐标系下）
     *
     * 从上到下依次排列每个页面容器：
     * - 容器顶部 Y 坐标 = 前一个容器底部 + pageSpacing
     * - 第一个容器顶部 Y 坐标 = 0
     *
     * 同时计算总高度并构建有序 Y 坐标数组（用于二分查找）。
     */
    private fun calculateContainerLayout() {
        var currentY = 0f
        for (i in 0 until totalPages) {
            val pageHeight = pageOriginalHeights[i]?.toFloat() ?: 0f

            containerTops1x[i] = currentY

            // 更新下一个容器的 Y 坐标
            currentY += pageHeight + pageSpacing
        }

        // 计算总高度 = 最后一页底部 Y 坐标
        totalHeight1x = if (totalPages > 0) {
            val lastPage = totalPages - 1
            (containerTops1x[lastPage] ?: 0f) + (pageOriginalHeights[lastPage]?.toFloat() ?: 0f)
        } else {
            0f
        }

        // 构建有序数组（用于 getTopVisiblePage 中的二分查找）
        containerTops1xArray = FloatArray(totalPages)
        for (i in 0 until totalPages) {
            containerTops1xArray[i] = containerTops1x[i] ?: 0f
        }
    }

    /**
     * 更新缩放比例
     *
     * 将新缩放值钳制到 [minScale, maxScale] 范围内，
     * 然后重新钳制滚动偏移并更新可见页面。
     *
     * @param newScale 新的缩放比例
     */
    fun updateScale(newScale: Float) {
        currentScale = newScale.coerceIn(minScale, maxScale)
        clampScroll()
        updateVisiblePages()
    }

    /**
     * 批量更新滚动偏移和缩放比例（缩放过程中使用）
     *
     * 合并 updateScroll + updateScale，确保只触发一次
     * clampScroll 和 updateVisiblePages，避免缩放时
     * 因双重状态更新导致的可见页面抖动和渲染闪屏。
     *
     * @param newScrollX 新的 X 轴滚动偏移
     * @param newScrollY 新的 Y 轴滚动偏移
     * @param newScale   新的缩放比例
     */
    fun updateScrollAndScale(newScrollX: Float, newScrollY: Float, newScale: Float) {
        scrollX = newScrollX
        scrollY = newScrollY
        currentScale = newScale.coerceIn(minScale, maxScale)
        clampScroll()
        updateVisiblePages()
    }

    /**
     * 更新视口尺寸
     *
     * 在屏幕旋转或布局变化时调用。
     * 重新计算最小缩放比例并钳制当前缩放值和滚动偏移。
     *
     * @param newWidth  新的视口宽度（像素）
     * @param newHeight 新的视口高度（像素）
     */
    fun updateViewportSize(newWidth: Int, newHeight: Int) {
        viewportWidth = newWidth
        viewportHeight = newHeight

        // 重新计算最小缩放比例
        calculateMinScale()

        // 钳制当前缩放
        currentScale = currentScale.coerceIn(minScale, maxScale)

        // 注意：在我们的模型中，页面原始尺寸不依赖视口，所以不需要重新计算
        // 但如果未来支持自适应布局，则需要重新计算

        clampScroll()
        updateVisiblePages()
    }

    /**
     * 限制滚动范围
     *
     * 确保画布不会超出视口边界：
     * scrollX ∈ [-maxScrollX, 0]
     * scrollY ∈ [-maxScrollY, 0]
     *
     * 其中 maxScrollX = max(0, canvasW - viewportWidth)
     *       maxScrollY = max(0, canvasH - viewportHeight)
     * 当画布小于视口时，滚动范围为 0。
     */
    fun clampScroll() {
        val canvasW = maxPageWidth * currentScale
        val canvasH = totalHeight1x * currentScale

        val maxScrollX = max(0f, canvasW - viewportWidth)
        val maxScrollY = max(0f, canvasH - viewportHeight)

        scrollX = scrollX.coerceIn(-maxScrollX, 0f)
        scrollY = scrollY.coerceIn(-maxScrollY, 0f)
    }

    /**
     * 更新滚动偏移量
     *
     * 设置新的滚动偏移，钳制后重新计算可见页面。
     *
     * @param newScrollX 新的 X 轴滚动偏移
     * @param newScrollY 新的 Y 轴滚动偏移
     */
    fun updateScroll(newScrollX: Float, newScrollY: Float) {
        scrollX = newScrollX
        scrollY = newScrollY
        clampScroll()
        updateVisiblePages()
    }

    /**
     * 计算当前可见的页面集合
     *
     * 将视口（viewport）变换回 1x 画布坐标系，
     * 然后判断每个页面的容器是否与视口相交。
     *
     * 相交条件：container.bottom > viewport.top && container.top < viewport.bottom
     *
     * 结果存储到 visiblePages 中，用于纹理缓存和渲染。
     */
    private fun updateVisiblePages() {
        val visible = mutableSetOf<Int>()

        // 检查文档是否可用
        if (!pdfDocumentRepository.isDocumentAvailable()) {
            visiblePages = visible
            return
        }

        // 计算视口在 1x 画布坐标系中的范围
        val viewportLeft1x = (-scrollX) / currentScale
        val viewportTop1x = (-scrollY) / currentScale
        val viewportRight1x = viewportLeft1x + viewportWidth / currentScale
        val viewportBottom1x = viewportTop1x + viewportHeight / currentScale

        for (i in 0 until totalPages) {
            val containerTop1x = getContainerTop1x(i)
            val containerBottom1x = containerTop1x + (pageOriginalHeights[i]?.toFloat() ?: 0f)

            // 判断容器是否可见
            if (containerBottom1x > viewportTop1x && containerTop1x < viewportBottom1x) {
                visible.add(i)
            }
        }

        visiblePages = visible
    }

    /**
     * 获取当前最顶部的可见页面索引
     *
     * 使用二分查找优化性能（针对 containerTops1xArray 有序数组）。
     * 找到第一个底部超过视口顶部的页面。
     *
     * @return 页面索引，如果没有可见页面或文档为空则返回 0
     */
    fun getTopVisiblePage(): Int {
        if (totalPages == 0) return 0

        // 检查文档是否可用
        if (!pdfDocumentRepository.isDocumentAvailable()) {
            Log.w("ContinuousCanvasState", "Document not available in getTopVisiblePage()")
            return 0
        }

        // 视口顶部在 1x 画布中的 Y 坐标
        val viewportTop1x = (-scrollY) / currentScale

        // 二分查找：找到第一个底部超过视口顶部的页面
        val tops = containerTops1xArray
        if (tops.isEmpty()) return 0

        var left = 0
        var right = tops.size - 1
        var result = 0

        while (left <= right) {
            val mid = (left + right) / 2
            val containerBottom1x = tops[mid] + (pageOriginalHeights[mid]?.toFloat() ?: 0f)

            if (containerBottom1x > viewportTop1x) {
                result = mid
                right = mid - 1
            } else {
                left = mid + 1
            }
        }

        return result
    }

    /**
     * 滚动到指定页面
     *
     * 使指定页面的顶部对齐到屏幕顶部。
     *
     * 计算逻辑：
     * screenY = containerTop1x * currentScale + scrollY
     * 让 screenY = 0 → scrollY = -containerTop1x * currentScale
     *
     * @param pageIndex 目标页面索引
     * @param alignTop  是否对齐到顶部（当前仅支持顶部对齐）
     */
    fun scrollToPage(pageIndex: Int, alignTop: Boolean = false) {
        if (pageIndex < 0 || pageIndex >= totalPages) return

        val containerTop1x = getContainerTop1x(pageIndex)

        // 坐标变换：screenY = canvasY1x * scale + scrollY
        // 要让页面顶部对齐到屏幕顶部(screenY=0)：
        // 0 = containerTop1x * currentScale + scrollY
        // => scrollY = -containerTop1x * currentScale
        val newScrollY = -containerTop1x * currentScale

        scrollY = newScrollY
        clampScroll()
        updateVisiblePages()
    }

    /**
     * 获取滚动进度
     *
     * 将当前垂直滚动位置归一化为 0.0 ~ 1.0 之间的比例值。
     * 0.0 = 顶部，1.0 = 底部。
     *
     * @return 滚动进度值（0.0 ~ 1.0）
     */
    fun getScrollProgress(): Float {
        val canvasH = totalHeight1x * currentScale
        val maxScrollY = max(0f, canvasH - viewportHeight)
        if (maxScrollY <= 0) return 0f
        return (-scrollY) / maxScrollY
    }

    /**
     * 根据进度滚动到指定位置
     *
     * 滚动进度的逆操作：将 0.0~1.0 的比例转换为具体的滚动偏移。
     *
     * @param progress 目标进度（0.0 ~ 1.0），会自动钳制到合法范围
     */
    fun scrollToProgress(progress: Float) {
        val clampedProgress = progress.coerceIn(0f, 1f)
        val canvasH = totalHeight1x * currentScale
        val maxScrollY = max(0f, canvasH - viewportHeight)
        scrollY = -clampedProgress * maxScrollY
        clampScroll()
        updateVisiblePages()
    }
}
