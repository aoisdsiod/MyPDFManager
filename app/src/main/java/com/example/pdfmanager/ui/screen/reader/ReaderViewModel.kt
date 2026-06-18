/*
 * ============================================================================
 * 阅读器 ViewModel（ReaderViewModel.kt）
 * ============================================================================
 *
 * 【文件功能】
 * ReaderViewModel 是 PDF 阅读器核心的 ViewModel，继承 AndroidViewModel，
 * 负责管理阅读器的所有业务逻辑和状态，包括：
 *
 *   1. PDF 文档生命周期管理（打开/关闭 PdfRenderer，通过 PdfDocumentRepository 代理）
 *   2. 页面状态管理（currentPage、totalPages、currentBitmap）
 *   3. PDF 页面渲染（根据屏幕宽度计算合适分辨率，带 LruCache 缓存）
 *   4. 阅读偏好设置（pageMode、toolbarMode）的观察和修改
 *   5. 阅读进度持久化（退出时自动保存 lastReadPage 到 Room 数据库）
 *   6. Bitmap 缓存管理（LruCache 自动限制 5 页上限，防 OOM）
 *   7. 并发渲染控制（Semaphore 限制最多 2 个并发渲染任务）
 *
 * 【ViewModel 生命周期】
 *   ViewModel 由 Jetpack Navigation Compose 的 viewModel() 委托创建，
 *   生命周期与 ReaderScreenV2 所在的 NavBackStackEntry 绑定。
 *   当用户退出阅读器页面时，onCleared() 被调用，释放所有资源。
 *
 * 【数据流架构】
 *   ┌──────────────────────────────────────────────────────┐
 *   │                    ReaderScreenV2                     │
 *   │  （Composable UI 层）                                 │
 *   │  收集 StateFlow → 渲染页面、显示页码、工具栏等         │
 *   └─────────────────┬────────────────────────────────────┘
 *                     │  collectAsStateWithLifecycle()
 *   ┌─────────────────▼────────────────────────────────────┐
 *   │              ReaderViewModel                          │
 *   │  - MutableStateFlow（currentPage, totalPages, ...）   │
 *   │  - PdfDocumentRepository（PdfRenderer 生命周期管理）  │
 *   │  - LruCache（Bitmap 缓存）                            │
 *   │  - PreferencesManager Flow（阅读偏好设置）            │
 *   └────┬─────────────────────┬───────────────────────────┘
 *        │                     │
 *        ▼                     ▼
 *   PdfDocumentRepository   Room Database / SharedPreferences
 *   （PdfRenderer 封装）    （持久化层）
 *
 * 【调用位置】
 * - app/src/main/java/com/example/pdfmanager/ui/screen/reader/ReaderScreenV2.kt（第 43 行）
 *   → 通过 viewModel() 委托创建，生命周期与阅读器页面绑定
 *
 * 【使用场景】
 * - 在阅读器页面的整个生命周期内使用
 * - 初始化 PDF 文档（initialize）
 * - 翻页操作（nextPage、previousPage、goToPage）
 * - 切换翻页模式（setPageMode）
 * - 切换工具栏模式（setToolbarMode）
 * - 保存阅读进度（saveCurrentPage，在页面销毁时调用）
 *
 * 【相关文件】
 * - ReaderScreenV2.kt：主阅读器 UI，观察本 ViewModel 的状态并调用方法
 * - PdfDocumentRepository.kt：PDF 文档仓库，封装 PdfRenderer 的打开/关闭/渲染操作
 * - PdfContinuousView.kt：连续滚动阅读视图（调用 getPageBitmap() 渲染页面）
 * - SinglePageView.kt：单页阅读视图（调用 getPageBitmap() 渲染页面）
 * - PreferencesManager.kt：偏好设置管理（pageMode、toolbarMode 的读写）
 * ============================================================================
 */

package com.example.pdfmanager.ui.screen.reader

import android.app.Application
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.pdfmanager.data.local.PreferencesManager
import com.example.pdfmanager.data.repository.AppContainer
import com.example.pdfmanager.data.model.PdfFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import java.io.IOException
import androidx.lifecycle.ViewModel

/**
 * ReaderViewModel - 阅读器核心 ViewModel
 *
 * 【功能】
 * 1. 持有 PdfDocumentRepository（统一管理 PdfRenderer 生命周期）
 * 2. 管理页面状态（currentPage, totalPages, currentBitmap）
 * 3. 观察阅读设置（通过 PreferencesManager Flow，均为 String 类型）
 * 4. 渲染 PDF 页面（全分辨率 Bitmap，带 LruCache 缓存）
 * 5. 在 ViewModel 清除时自动关闭文档并释放资源
 *
 * 【调用位置】
 * - ReaderScreenV2.kt（第 43 行）：viewModel() 委托创建
 *
 * @param application Application 实例（来自 AndroidViewModel），用于获取 ContentResolver 和资源
 */
class ReaderViewModel(application: Application) : AndroidViewModel(application) {

    // ── 依赖注入 ──

    /** ContentResolver，用于 URI 内容读取（打开 PDF 文件） */
    private val contentResolver = application.contentResolver

    /** 偏好设置管理器，单例（通过 AppContainer 访问） */
    private val preferencesManager = AppContainer.preferencesManager

    // ── PDF 文档仓库（统一管理 PdfRenderer 生命周期）─────────────────────────────

    /**
     * PDF 文档仓库
     * 封装了 PdfRenderer 的打开、关闭、页面渲染等操作。
     * 通过 PdfDocumentRepository 代理，使 ViewModel 无需直接操作 PdfRenderer。
     */
    val pdfDocumentRepository = PdfDocumentRepository()

    /**
     * 文档是否可用（StateFlow，供 UI 观察）
     *
     * 初始值为 false，当 PDF 文档成功打开并获取页数后设为 true。
     * Ui 层（ReaderScreenV2）通过 collectAsStateWithLifecycle 观察此状态，
     * 为 true 时创建 AndroidView 显示 PDF 内容，否则显示加载指示器。
     *
     * 使用场景：控制阅读器主页面中 AndroidView 的创建时机，防止在 PdfRenderer
     * 尚未就绪时访问。
     */
    private val _isDocumentAvailable = MutableStateFlow(false)
    val isDocumentAvailableFlow: StateFlow<Boolean> = _isDocumentAvailable.asStateFlow()

    // ── PDF 文件信息 ─────────────────────────────────

    /** PDF 文件元数据（当前正在阅读的文件信息） */
    private val _pdfFile = MutableStateFlow<PdfFile?>(null)
    val pdfFile: StateFlow<PdfFile?> = _pdfFile

    // ── 页面状态 ─────────────────────────────────

    /** 当前阅读的页码（0-based），供 UI 展示和工具栏页码显示 */
    private val _currentPage = MutableStateFlow(0)
    val currentPage: StateFlow<Int> = _currentPage

    /** 当前页的渲染 Bitmap，供单页模式下 ImageView 显示 */
    private val _currentBitmap = MutableStateFlow<Bitmap?>(null)
    val currentBitmap: StateFlow<Bitmap?> = _currentBitmap

    /** PDF 文档总页数 */
    private val _totalPages = MutableStateFlow(0)
    val totalPages: StateFlow<Int> = _totalPages

    // ── 阅读设置（观察 PreferencesManager Flow，均为 String）─────────────────────────────

    /**
     * 翻页方式（Page Mode）
     *
     * 取值：
     * - "single_page"：单页模式，一次显示一页，通过按钮翻页
     * - "continuous"：滚动模式，可以连续滚动浏览所有页面
     *
     * 通过 PreferencesManager.getReaderPageModeFlow() 从 SharedPreferences 读取，
     * 用 stateIn() 转换为 StateFlow，默认值为 "single_page"。
     *
     * 调用位置：ReaderScreenV2.kt（第 68 行）观察此状态决定使用哪个 AndroidView
     */
    val pageMode: StateFlow<String> = preferencesManager.getReaderPageModeFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), "single_page")

    /**
     * 工具栏显示模式（Toolbar Mode）
     *
     * 取值：
     * - "full"：正常显示（顶部标题栏 + 底部工具栏）
     * - "page_only"：仅显示页码角标
     * - "hidden"：完全隐藏，仅通过双击切换显示
     *
     * 通过 PreferencesManager.getReaderToolbarModeFlow() 从 SharedPreferences 读取，
     * 用 stateIn() 转换为 StateFlow，默认值为 "full"。
     *
     * 调用位置：ReaderScreenV2.kt（第 66 行）观察此状态控制工具栏动画显示
     */
    val toolbarMode: StateFlow<String> = preferencesManager.getReaderToolbarModeFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), "full")

    // ── 当前文件 ID ─────────────────────────────────

    /**
     * 初始化是否完成（StateFlow，供 UI 观察）
     *
     * 用于触发 ReaderScreenV2 中的初始页面跳转逻辑。
     * 当 initialize() 完成后设为 true，UI 层在 LaunchedEffect 中观察此状态，
     * 在初始化完成后执行 pendingPageJump。
     *
     * 调用位置：ReaderScreenV2.kt（第 52、100-106 行）
     */
    private val _isInitializationComplete = MutableStateFlow(false)
    val isInitializationComplete: StateFlow<Boolean> = _isInitializationComplete.asStateFlow()

    /** 当前文件 ID（字符串标识），用于保存阅读进度时标识文件 */
    private var currentFileId: String = ""

    // ── Bitmap 缓存 ─────────────────────────────────

    /**
     * LruCache 缓存，限制缓存大小以避免 OOM（OutOfMemoryError）。
     *
     * 缓存最多 5 页的渲染 Bitmap。当缓存超过 5 页时，最久未使用的条目会被自动回收。
     * 此机制在连续翻页和滚动阅读时显著减少重复渲染次数。
     *
     * 使用场景：
     * - renderPage()：渲染前先从缓存查找，命中则跳过渲染
     * - getPageBitmap()：滚动模式渲染前检查缓存
     * - goToPage()：跳转时立即从缓存显示，消除等待感
     * - cache cleanup：onCleared() 中遍历回收所有 Bitmap
     */
    private val bitmapCache = android.util.LruCache<Int, Bitmap>(5)

    /**
     * 并发渲染信号量，限制最多 2 个渲染任务同时进行。
     *
     * 因为渲染操作在 IO 线程上执行且涉及 Bitmap 分配，限制并发数可以：
     * 1. 降低内存峰值，减少 OOM 风险
     * 2. 避免 CPU 过度竞争
     * 3. 确保后台渲染不会阻塞 UI 线程
     *
     * 使用场景：getPageBitmap() 中 acquire()/release()
     */
    private val renderSemaphore = Semaphore(2)

    // ═══════════════════════════════════════════════════════════════════════
    // 初始化
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * initialize - 初始化阅读器
     *
     * 【功能描述】
     * 异步初始化阅读器，执行以下步骤：
     * 1. 重置状态（_isDocumentAvailable = false, _isInitializationComplete = false）
     * 2. 从 Room 数据库获取 PdfFileEntity（含最新 lastReadPage）
     * 3. 打开 PdfRenderer（通过 PdfDocumentRepository）
     * 4. 确定初始页面：优先恢复上次阅读位置，或根据 forceStart 决定
     * 5. 渲染目标页面
     * 6. 标记初始化完成
     *
     * 【调用位置】
     * - ReaderScreenV2.kt（第 82-84 行）：LaunchedEffect(fileId) 中调用
     *   当 fileId 变化时触发重新初始化
     *
     * 【使用场景】
     * - 用户从文件列表/详情页点击进入阅读器时
     * - 用户切换阅读文件时（fileId 变化）
     *
     * @param fileId PDF 文件的唯一标识（Room 数据库中的主键 ID）
     * @param forceStart 是否强制从第 0 页开始阅读（忽略上次保存的 lastReadPage）
     *                   当此参数为 true 时，忽略 lastReadPage 记录，始终从首页开始。
     *                   用于"从头阅读"功能。
     *
     * @return 无返回值，协程异步执行
     */
    fun initialize(fileId: String, forceStart: Boolean = false) {
        viewModelScope.launch {
            try {
                // 重置状态
                _isDocumentAvailable.value = false
                _isInitializationComplete.value = false
                currentFileId = fileId

                // 1. 从 Room 数据库获取 PdfFileEntity（直接读取，确保 lastReadPage 是最新值）
                val pdfFileEntity = AppContainer.database.pdfFileDao().getById(fileId)
                val pdfFile = pdfFileEntity?.let { entity ->
                    PdfFile(
                        id = entity.id,
                        name = entity.name,
                        displayName = entity.displayName,
                        uri = Uri.parse(entity.uri),
                        size = entity.size,
                        lastModified = entity.lastModified,
                        notes = entity.notes,
                        isFavorite = entity.isFavorite,
                        lastReadPage = entity.lastReadPage,
                        thumbnailPath = entity.thumbnailPath
                    )
                }
                _pdfFile.value = pdfFile

                // 2. 打开 PdfRenderer（使用 PdfDocumentRepository）
                openPdfRenderer(pdfFile?.uri)

                // 3. 确定要渲染的页面
                var targetPage = 0 // 默认渲染第 0 页

                if (!forceStart) {
                    // 恢复上次阅读页码（从 Room 数据库读取）
                    if (pdfFile != null) {
                        val lastReadPage = pdfFile.lastReadPage
                        if (lastReadPage > 0) {
                            // lastReadPage 是 1-based，需要转为 0-based
                            targetPage = (lastReadPage - 1).coerceIn(0, _totalPages.value - 1)
                        }
                    }
                } else {
                    android.util.Log.d("ReaderViewModel", "initialize: forceStart=true，强制从第 0 页开始")
                }

                // 4. 渲染目标页面
                renderPage(targetPage)
                
                // 5. 标记初始化完成
                _isInitializationComplete.value = true
                android.util.Log.d("ReaderViewModel", "initialize: 初始化完成，currentPage=$targetPage")
            } catch (e: Exception) {
                android.util.Log.e("ReaderViewModel", "Error initializing", e)
            }
        }
    }

    /**
     * openPdfRenderer - 打开 PDF 渲染器
     *
     * 【功能描述】
     * 在 IO 线程上通过 PdfDocumentRepository 打开 PDF 文档。
     * 如果成功，更新总页数并将 _isDocumentAvailable 设为 true。
     *
     * 【调用位置】
     * - initialize()（第 127 行）：初始化流程中调用
     *
     * @param uri PDF 文件的 Uri（从 Room 数据库读取）
     *
     * @return 无返回值，suspend 函数在 IO 调度器执行
     */
    private suspend fun openPdfRenderer(uri: Uri?) = withContext(Dispatchers.IO) {
        try {
            if (uri == null) return@withContext

            // 关闭之前的文档
            closePdfRenderer()

            // 使用 PdfDocumentRepository 打开文档
            val success = pdfDocumentRepository.openDocument(getApplication(), uri)
            if (success) {
                _totalPages.value = pdfDocumentRepository.getPageCount()
                _isDocumentAvailable.value = true
                android.util.Log.d("ReaderViewModel", "Document opened: $uri, pageCount=${_totalPages.value}")
            } else {
                android.util.Log.e("ReaderViewModel", "Failed to open document: $uri")
                _isDocumentAvailable.value = false
            }

        } catch (e: Exception) {
            android.util.Log.e("ReaderViewModel", "Error opening PdfRenderer", e)
            _isDocumentAvailable.value = false
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 页面渲染
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * renderPage - 渲染指定页面（带缓存）
     *
     * 【功能描述】
     * 渲染指定索引的 PDF 页面到 Bitmap，并更新当前页码和 Bitmap 状态。
     * 流程：
     * 1. 同步设置 _currentPage（保证 UI 能即时观察到页码变化）
     * 2. 启动 IO 协程执行渲染
     * 3. 先检查 LruCache 缓存，命中则立即返回
     * 4. 未命中则调用 renderPageInternal 执行实际渲染
     * 5. 将结果放入缓存
     * 6. 清理不可见页缓存
     *
     * 【调用位置】
     * - initialize()（第 147 行）：初始化完成后渲染目标页面
     * - nextPage()（第 362 行）：翻到下一页
     * - previousPage()（第 371 行）：翻到上一页
     *
     * 【使用场景】
     * 单页模式下的页面渲染。此方法与 goToPage() 的区别在于：
     * renderPage 不包含预渲染逻辑，适合单一页面渲染场景。
     *
     * @param index 页面索引（0-based），表示要渲染的页数
     *
     * @return 无返回值，协程异步执行，渲染结果通过 _currentBitmap 暴露给 UI
     */
    fun renderPage(index: Int) {
        // ✅ 同步设置当前页码（在 launch 之前），确保 UI 能立即观察到正确页码
        _currentPage.value = index

        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (index < 0 || index >= _totalPages.value) {
                    android.util.Log.w("ReaderViewModel", "renderPage: 跳过无效页码 index=$index, totalPages=${_totalPages.value}")
                    return@launch
                }

                // 1. 检查缓存（立即显示，消除延迟感）
                val cachedBitmap = bitmapCache.get(index)
                if (cachedBitmap != null) {
                    _currentBitmap.value = cachedBitmap
                    return@launch
                }

                // 2. 渲染
                val bitmap = renderPageInternal(index) ?: return@launch
                
                // 3. 缓存
                bitmapCache.put(index, bitmap)
                
                // 4. 更新当前Bitmap（_currentPage 已在上面同步设置）
                _currentBitmap.value = bitmap

                // 5. 清理不可见页
                cleanCache(index)

            } catch (e: Exception) {
                android.util.Log.e("ReaderViewModel", "Error rendering page $index", e)
            }
        }
    }

    /**
     * getPageBitmap - 按需渲染指定页面（供滚动模式和单页模式使用）
     *
     * 【功能描述】
     * 供自定义视图（PdfContinuousView / SinglePageView）调用的高性能渲染方法。
     * 特点：
     * - 带并发限制（Semaphore，最多 2 个并发渲染）
     * - 带缓存（LruCache，最多缓存 5 页）
     * - 线程安全（suspend + withContext(Dispatchers.IO)）
     * - 使用 NonCancellable 确保渲染和缓存原子完成
     * - CancellationException 被正常忽略（协程取消是生命周期事件）
     *
     * 【调用位置】
     * - PdfContinuousView.kt：滚动模式下，在 RecyclerView 的 onBindViewHolder 中调用
     * - SinglePageView.kt：单页模式下，在页面切换时调用
     *
     * 【使用场景】
     * 自定义 View 需要渲染某页时调用此方法获取 Bitmap。
     * 与 renderPage() 的区别：
     * - getPageBitmap 返回 Bitmap 对象，由调用方自行显示
     * - renderPage 将 Bitmap 设置到 _currentBitmap StateFlow，由 Compose 观察显示
     *
     * @param index 页面索引（0-based）
     *
     * @return 渲染好的 Bitmap，如果渲染失败或页码无效则返回 null
     */
    suspend fun getPageBitmap(index: Int): Bitmap? = withContext(Dispatchers.IO) {
        try {
            if (index < 0 || index >= _totalPages.value) return@withContext null

            // 1. 检查缓存
            bitmapCache.get(index)?.let { return@withContext it }

            // 2. 获取信号量（限制并发）
            renderSemaphore.acquire()
            try {
                // 3. 再次检查缓存（可能其他协程已经渲染了）
                bitmapCache.get(index)?.let { return@withContext it }

                // 4. 渲染（使用 NonCancellable 确保渲染和缓存完成）
                val bitmap = withContext(kotlinx.coroutines.NonCancellable) {
                    renderPageInternal(index)
                }

                // 如果渲染失败，返回 null
                if (bitmap == null) return@withContext null

                // 5. 缓存
                bitmapCache.put(index, bitmap)

                // 6. 更新当前页和当前Bitmap（滚动模式需要）
                _currentPage.value = index
                _currentBitmap.value = bitmap

                // 7. 清理不可见页
                cleanCache(index)

                return@withContext bitmap
            } finally {
                renderSemaphore.release()
            }

        } catch (e: Exception) {
            // CancellationException 是正常生命周期事件，不记录为错误
            if (e !is kotlinx.coroutines.CancellationException) {
                android.util.Log.e("ReaderViewModel", "Error getting page bitmap $index", e)
            }
            return@withContext null
        }
    }

    /**
     * renderPageInternal - 内部渲染方法
     *
     * 【功能描述】
     * 实际的 PDF 页面渲染实现。不检查缓存、不管理信号量、不更新任何状态。
     * 仅执行：打开 PdfRenderer 页面 → 获取页面尺寸 → 创建合适尺寸的 Bitmap →
     * 渲染 → 返回 Bitmap。
     *
     * 根据屏幕宽度计算合适的分辨率，避免渲染过大 Bitmap 浪费内存。
     *
     * 【调用位置】
     * - renderPage()（第 207 行）：同步渲染
     * - getPageBitmap()（第 271 行）：并发渲染
     * - goToPage()（第 396-417 行）：预渲染逻辑
     *
     * @param index 页面索引（0-based）
     *
     * @return 渲染完成的 Bitmap，失败则返回 null
     */
    private fun renderPageInternal(index: Int): Bitmap? {
        return try {
            // 使用 PdfDocumentRepository 渲染页面
            val screenWidthPx = getApplication<Application>().resources.displayMetrics.widthPixels

            // 获取页面尺寸（通过打开页面）
            val tempPage = pdfDocumentRepository.openPage(index)
            if (tempPage == null) {
                android.util.Log.e("ReaderViewModel", "Failed to open page $index")
                return null
            }

            val pageWidth = tempPage.width
            val pageHeight = tempPage.height
            tempPage.close()

            // 根据屏幕宽度渲染合适大小的 bitmap
            val scale = screenWidthPx.toFloat() / pageWidth
            val bmpWidth = screenWidthPx
            val bmpHeight = (pageHeight * scale).toInt()

            // 创建 Bitmap
            val bitmap = Bitmap.createBitmap(bmpWidth, bmpHeight, Bitmap.Config.ARGB_8888)

            // 使用 PdfDocumentRepository 渲染页面
            val success = pdfDocumentRepository.renderPage(index, bitmap)
            if (!success) {
                bitmap.recycle()
                android.util.Log.e("ReaderViewModel", "Failed to render page $index")
                return null
            }

            bitmap
        } catch (e: Exception) {
            android.util.Log.e("ReaderViewModel", "Error rendering page $index internally", e)
            null
        }
    }

    /**
     * saveCurrentPage - 保存当前阅读页码到数据库
     *
     * 【功能描述】
     * 将当前阅读的页数（1-based）保存到 Room 数据库的 PdfFileEntity 中。
     * 此方法在阅读器页面销毁时调用，确保下次打开时能恢复阅读位置。
     *
     * 使用 runBlocking(Dispatchers.IO) 确保同步完成保存操作，
     * 避免在页面销毁时异步协程来不及执行。
     *
     * 【调用位置】
     * - ReaderScreenV2.kt（第 91 行）：DisposableEffect 的 onDispose 中调用
     *   当阅读器页面从组合树中移除时（导航离开、杀后台等场景）
     *
     * 【使用场景】
     * - 用户按下返回键退出阅读器
     * - 用户导航到其他页面
     * - Activity 被系统销毁（杀后台）
     * - 页面被回收（Composition 被销毁）
     *
     * @return 无返回值，同步执行
     */
    fun saveCurrentPage() {
        val pageIndex = _currentPage.value
        val fileId = currentFileId
        if (fileId.isEmpty()) return
        
        // ✅ 同步保存到数据库（使用 runBlocking 确保完成）
        try {
            kotlinx.coroutines.runBlocking(Dispatchers.IO) {
                val entity = AppContainer.database.pdfFileDao().getById(fileId)
                if (entity != null) {
                    entity.lastReadPage = pageIndex + 1
                    AppContainer.database.pdfFileDao().update(entity)
                    android.util.Log.d("ReaderViewModel", "saveCurrentPage: 已保存 lastReadPage=${pageIndex + 1}, fileId=$fileId")
                } else {
                    android.util.Log.w("ReaderViewModel", "saveCurrentPage: 未找到实体，fileId=$fileId")
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("ReaderViewModel", "saveCurrentPage: 保存失败", e)
        }
    }

    /**
     * cleanCache - 清理不可见页的缓存
     *
     * 【功能描述】
     * 清除当前可见页之外的不必要缓存。
     * 当前使用 LruCache 自动管理缓存大小（最多 5 页），因此此方法为空实现。
     * 保留以保持代码兼容性和扩展性。
     *
     * 【调用位置】
     * - renderPage()（第 216 行）
     * - getPageBitmap()（第 285 行）
     *
     * @param currentIndex 当前可见页索引（0-based），不需要手动清理
     *
     * @return 无返回值
     */
    private fun cleanCache(currentIndex: Int) {
        // LruCache 会自动管理缓存大小，无需手动清理
        // 保留此方法以保持代码兼容性
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 页面导航
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * nextPage - 翻到下一页
     *
     * 【功能描述】
     * 将当前页码加 1，如果未超出总页数则渲染下一页。
     * 页码由 ReaderScreenV2 的 pendingPageJump 机制配合完成实际视图跳转。
     *
     * 【调用位置】
     * - ReaderScreenV2.kt（第 239-244 行）：底部工具栏"下一页"按钮的 onClick
     *
     * @return 无返回值
     */
    fun nextPage() {
        val next = _currentPage.value + 1
        if (next < _totalPages.value) {
            renderPage(next)
        }
    }

    /**
     * previousPage - 翻到上一页
     *
     * 【功能描述】
     * 将当前页码减 1，如果未小于 0 则渲染上一页。
     *
     * 【调用位置】
     * - ReaderScreenV2.kt（第 232-237 行）：底部工具栏"上一页"按钮的 onClick
     *
     * @return 无返回值
     */
    fun previousPage() {
        val prev = _currentPage.value - 1
        if (prev >= 0) {
            renderPage(prev)
        }
    }

    /**
     * goToPage - 跳转到指定页面（带预渲染）
     *
     * 【功能描述】
     * 跳转到指定页面并进行智能预渲染：
     * 1. 立即检查缓存，命中则立即更新 UI（消除等待感）
     * 2. 渲染当前页（如果缓存未命中）
     * 3. 后台预渲染下一页
     * 4. 后台预渲染上一页
     *
     * 此方法仅用于手动翻页（按钮点击/页码跳转），
     * 滚动模式和单页模式的自动页码更新应使用 updateCurrentPageForScroll()。
     *
     * 【调用位置】
     * （当前版本中主要使用 pendingPageJump + 底部工具栏翻页，此方法为备用跳转）
     *
     * @param index 目标页码（0-based），必须在 [0, totalPages) 范围内
     *
     * @return 无返回值
     */
    fun goToPage(index: Int) {
        if (index !in 0 until _totalPages.value) {
            return
        }

        // 立即检查缓存，如果有则立即显示（消除延迟感）
        val cachedBitmap = bitmapCache.get(index)
        if (cachedBitmap != null) {
            _currentPage.value = index
            _currentBitmap.value = cachedBitmap
        }

        // 渲染当前页（如果缓存未命中）并预渲染相邻页
        viewModelScope.launch {
            // 渲染当前页
            if (cachedBitmap == null) {
                renderPageInternal(index)?.let { bmp ->
                    bitmapCache.put(index, bmp)
                    _currentPage.value = index
                    _currentBitmap.value = bmp
                }
            }

            // 预渲染下一页（低优先级后台渲染）
            if (index + 1 < _totalPages.value && bitmapCache.get(index + 1) == null) {
                renderPageInternal(index + 1)?.let { bmp ->
                    bitmapCache.put(index + 1, bmp)
                }
            }

            // 预渲染上一页（低优先级后台渲染）
            if (index - 1 >= 0 && bitmapCache.get(index - 1) == null) {
                renderPageInternal(index - 1)?.let { bmp ->
                    bitmapCache.put(index - 1, bmp)
                }
            }
        }
    }

    /**
     * updateCurrentPageForScroll - 轻量级页码更新
     *
     * 【功能描述】
     * 供滚动模式（PdfContinuousView）和单页模式（SinglePageView）使用的
     * 轻量页码更新方法。
     * 
     * 功能：
     * 1. 仅更新 _currentPage 状态（用于 UI 显示页码）
     * 2. 不触发渲染（避免与 PdfContinuousView/SinglePageView 内部渲染重复）
     * 3. 不更新 _currentBitmap（滚动模式和单页模式自己管理渲染）
     *
     * 【调用位置】
     * - PdfContinuousView.kt | SinglePageView.kt 的 onPageChanged 回调中调用
     *   → 通过 AndroidView update 块中的 view.onPageChanged = { page -> viewModel.updateCurrentPageForScroll(page) }
     *
     * 【使用场景】
     * 当用户通过触摸滚动/滑动改变页面时，只更新页码显示，不触发重复渲染。
     *
     * @param page 新的页码（0-based），必须在有效范围内
     *
     * @return 无返回值
     */
    fun updateCurrentPageForScroll(page: Int) {
        if (page in 0 until _totalPages.value) {
            _currentPage.value = page
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 设置项切换（同时持久化到 SharedPreferences）
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * setPageMode - 切换翻页方式
     *
     * 【功能描述】
     * 设置翻页方式（"single_page" 单页 / "continuous" 滚动），
     * 通过 PreferencesManager 持久化到 SharedPreferences。
     * 设置变更后，ReaderScreenV2 中观察的 pageMode StateFlow 会自动更新。
     *
     * 【调用位置】
     * - ReaderScreenV2.kt（第 230 行）：底部工具栏翻页模式切换按钮
     *
     * @param mode 翻页模式字符串："single_page"（单页）或 "continuous"（滚动）
     *
     * @return 无返回值
     */
    fun setPageMode(mode: String) {
        viewModelScope.launch {
            preferencesManager.setReaderPageMode(mode)
        }
    }

    /**
     * setToolbarMode - 切换工具栏显示模式
     *
     * 【功能描述】
     * 设置工具栏显示模式（"full" 正常显示 / "page_only" 仅页码 / "hidden" 隐藏），
     * 通过 PreferencesManager 持久化到 SharedPreferences。
     * 设置变更后，ReaderScreenV2 中观察的 toolbarMode StateFlow 会自动更新。
     *
     * 【调用位置】
     * （当前版本中工具栏模式在 ReaderSettingsScreen 中设置，此方法保留以供扩展）
     *
     * @param mode 工具栏模式字符串：
     *             - "full"：正常显示（顶部标题栏 + 底部工具栏）
     *             - "page_only"：仅显示页码角标
     *             - "hidden"：完全隐藏
     *
     * @return 无返回值
     */
    fun setToolbarMode(mode: String) {
        viewModelScope.launch {
            preferencesManager.setReaderToolbarMode(mode)
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 资源清理
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * closePdfRenderer - 关闭 PDF 渲染器
     *
     * 【功能描述】
     * 通过 PdfDocumentRepository 关闭当前打开的 PDF 文档。
     * 更新 _isDocumentAvailable 状态为 false。
     *
     * 【调用位置】
     * - openPdfRenderer()（第 165 行）：重新打开新文档前关闭旧文档
     * - cleanup()（第 477 行）：ViewModel 清除时一并关闭
     *
     * @return 无返回值
     */
    private fun closePdfRenderer() {
        try {
            pdfDocumentRepository.closeDocument()
            _isDocumentAvailable.value = false
            android.util.Log.d("ReaderViewModel", "Document closed")
        } catch (e: Exception) {
            android.util.Log.e("ReaderViewModel", "Error closing document", e)
        }
    }

    /**
     * cleanup - 清理所有资源
     *
     * 【功能描述】
     * 执行完整的资源清理：
     * 1. 关闭 PDF 文档（释放 PdfRenderer 资源）
     * 2. 遍历 LruCache，显式回收所有 Bitmap（防止内存泄漏）
     * 3. 清空缓存
     *
     * 【调用位置】
     * - onCleared()（第 488 行）：ViewModel 生命周期结束时
     *
     * @return 无返回值
     */
    private fun cleanup() {
        // 1. 关闭文档
        closePdfRenderer()
        
        // 2. 回收 Bitmap（LruCache 需要遍历回收）
        bitmapCache.snapshot().values.forEach { it.recycle() }
        bitmapCache.evictAll()
    }

    /**
     * onCleared - ViewModel 销毁回调
     *
     * 【功能描述】
     * AndroidViewModel 的生命周期回调，当 ViewModel 不再被使用且即将被销毁时调用。
     * 
     * 注意：
     * - 阅读进度已经在 DisposableEffect.onDispose 中保存（在 ReaderScreenV2.kt 中）
     * - 此处仅做资源清理，避免重复保存操作
     *
     * 【调用位置】
     * 由 Android 系统在 ViewModel 清除时自动调用
     *
     * @return 无返回值
     */
    override fun onCleared() {
        // ✅ 已经在 DisposableEffect.onDispose 中保存了页码
        // 此处仅做资源清理，避免重复保存
        super.onCleared()
        cleanup()
    }
}
