/*
 * ============================================================================
 * 阅读器主页面 V2（ReaderScreenV2.kt）
 * ============================================================================
 *
 * 【文件功能】
 * 本文件定义了 PDF 阅读器的主界面，使用 Jetpack Compose 构建。
 * 这是 PDF 阅读的核心 UI，整合了以下功能：
 *
 *   1. PDF 文档渲染：根据翻页模式选择 PdfContinuousView（滚动）或 SinglePageView（单页）
 *   2. 工具栏系统：可动画展开/收起顶部标题栏和底部翻页工具栏
 *   3. 页码角标：始终显示当前页码/总页数，并随工具栏状态自动调整位置
 *   4. 页面跳转逻辑：在翻页模式切换时保持当前页面位置不变
 *   5. 双指/双击切换工具栏显示
 *   6. 阅读进度自动保存（页面销毁时）
 *   7. 加载状态显示（文档未就绪时显示 CircularProgressIndicator）
 *
 * 【UI 结构】
 * ┌─────────────────────────────────────────┐
 * │  AnimatedVisibility（顶部工具栏）          │  ← 滑入/滑出动画
 * │  ┌───┬─────────────────────────────┐    │
 * │  │ ← │ 文件名                       │    │  ← TopAppBar
 * │  └───┴─────────────────────────────┘    │
 * ├─────────────────────────────────────────┤
 * │  PageNumberBadge（页码角标）              │  ← 始终显示，位置自适应
 * │  12/100                                  │
 * ├─────────────────────────────────────────┤
 * │                                         │
 * │      PDF 内容区域                        │  ← AndroidView
 * │   （PdfContinuousView / SinglePageView）  │    根据 pageMode 选择
 * │                                         │
 * ├─────────────────────────────────────────┤
 * │  AnimatedVisibility（底部工具栏）          │  ← 滑入/滑出动画
 * │  ┌──────┬────┬──┬────┬──────┐           │
 * │  │ 设置 │ ◀  │12│ ▶ │ 模式 │           │  ← ReaderBottomBar
 * │  └──────┴────┴──┴────┴──────┘           │
 * └─────────────────────────────────────────┘
 *
 * 【调用位置】
 * - app/src/main/java/com/example/pdfmanager/ui/navigation/AppNavGraph.kt（第 152 行）
 *   → composable("reader/{fileId}") 路由中创建
 *   → 通过 Navigation Compose 从文件列表/详情页跳转
 *
 * 【使用场景】
 * - 用户从文件列表点击 PDF 文件进入阅读
 * - 用户从详情页点击"阅读"按钮进入
 * - 支持 forceStart=true 参数实现"从头阅读"
 *
 * 【相关文件】
 * - ReaderViewModel.kt：阅读器核心 ViewModel，管理文档和页面状态
 * - ReaderBottomBar.kt：底部工具栏组件（翻页按钮、模式切换）
 * - PageNumberBadge.kt：页码角标组件
 * - PdfContinuousView.kt：连续滚动阅读视图（Android View 实现）
 * - SinglePageView.kt：单页阅读视图（Android View 实现）
 * - PdfDocumentRepository.kt：PDF 文档仓库，管理 PdfRenderer 生命周期
 * ============================================================================
 */

package com.example.pdfmanager.ui.screen.reader

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.animation.core.animateDpAsState


/**
 * ReaderScreenV2 - 阅读器主页面 Composable
 *
 * 【功能描述】
 * 阅读器的核心 UI 组件。根据 ViewModel 提供的状态，动态渲染 PDF 内容、
 * 控制工具栏显示、处理翻页动画和模式切换。
 *
 * V2 版本相对于 V1 的改进：
 * - 引入 PdfDocumentRepository 统一管理 PdfRenderer 生命周期
 * - 使用 StateFlow 替代 LiveData，更好地与 Compose 生命周期集成
 * - 修复页面跳转的时序问题（pendingPageJump 机制）
 * - 支持翻页模式切换时保持当前页面
 *
 * 【行为逻辑】
 * 1. 页面初始化：
 *    - LaunchedEffect(fileId) → viewModel.initialize(fileId, forceStart)
 *    - 文档加载完成后显示阅读视图
 *
 * 2. 初始页面跳转：
 *    - isInitializationComplete 为 true 时触发 pendingPageJump
 *    - 确保阅读器打开后直接跳转到上次阅读位置
 *
 * 3. 翻页模式切换：
 *    - pageMode 变化时，通过 savedCurrentPage 保存切换前的页码
 *    - 切换后通过 pendingPageJump 恢复页面
 *
 * 4. 工具栏控制：
 *    - 双击 PDF 视图切换工具栏显示
 *    - toolbarMode == "hidden" 时彻底隐藏
 *    - 页码角标位置随工具栏自动调整（带动画）
 *
 * 5. 页面退出：
 *    - DisposableEffect.onDispose → viewModel.saveCurrentPage()
 *    - Activity 方向锁定在竖屏
 *
 * 【调用位置】
 * - AppNavGraph.kt（第 152 行）：Navigation Compose 的 composable("reader/{fileId}?forceStart={forceStart}") 路由
 *
 * 【使用场景】
 * - 用户打开任意 PDF 文件进行阅读
 *
 * @param fileId PDF 文件的唯一 ID（Room 数据库主键），用作导航参数
 * @param forceStart 是否强制从第 0 页开始阅读（忽略 lastReadPage），传递给 ViewModel.initialize()
 * @param onBack 返回按钮的回调函数，通常为 navController.popBackStack()
 * @param navController NavController 实例，用于页面间导航（当前保留供扩展使用）
 * @param viewModel 阅读器 ViewModel，通过 viewModel() 委托自动创建
 *
 * @return 无返回值，为 Composable 函数
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreenV2(
    fileId: String,
    forceStart: Boolean = false,
    onBack: () -> Unit,
    navController: NavController,
    viewModel: ReaderViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
) {
    // ── 获取 Activity 实例（用于锁定屏幕方向） ──
    val context = LocalContext.current
    val activity = context as? android.app.Activity

    // ── 从 ViewModel 收集状态（StateFlow → Compose State） ──
    // 使用 collectAsStateWithLifecycle 确保在 Lifecycle 停止时停止收集
    val currentPage by viewModel.currentPage.collectAsStateWithLifecycle()
    val totalPages by viewModel.totalPages.collectAsStateWithLifecycle()
    val pdfFile by viewModel.pdfFile.collectAsStateWithLifecycle()
    val isDocumentAvailable by viewModel.isDocumentAvailableFlow.collectAsStateWithLifecycle(false)
    val isInitializationComplete by viewModel.isInitializationComplete.collectAsStateWithLifecycle(false)

    // ── 待处理的页面跳转请求（关键状态） ──
    /**
     * pendingPageJump：当用户主动请求翻页时设置，在 AndroidView 的 update 块中消费。
     *
     * 使用场景：
     * 1. 初始页面跳转：isInitializationComplete 变为 true 时，
     *    将 currentPage 写入 pendingPageJump，AndroidView 创建后立即跳转
     * 2. 翻页按钮：用户点击上一页/下一页，设置目标页码到 pendingPageJump
     * 3. 模式切换：pageMode 变化时，将 savedCurrentPage 写入 pendingPageJump
     *
     * 消费方式：AndroidView 的 update 块中：
     *   pendingPageJump?.let { targetPage ->
     *       view.scrollToPage(targetPage)  // 滚动模式
     *       view.jumpToPage(targetPage)     // 单页模式
     *   }
     *   pendingPageJump = null
     */
    var pendingPageJump by remember { mutableStateOf<Int?>(null) }

    // 在 Composable 层获取协程作用域（正确位置）
    val coroutineScope = rememberCoroutineScope()

    // ── 屏幕方向管理 ──
    /**
     * 进入阅读器时把方向控制权交还系统（允许旋转/弹出旋转确认按钮），
     * 退出时恢复竖屏锁定。
     */
    DisposableEffect(Unit) {
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_USER
        onDispose {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }

    // ── 观察阅读偏好设置 ──
    val toolbarMode by viewModel.toolbarMode.collectAsStateWithLifecycle()
    val pageMode by viewModel.pageMode.collectAsStateWithLifecycle()

    // ── 工具栏可见性状态（从 ViewModel 管理，配置变更后保持） ──
    val isToolbarVisible by viewModel.isToolbarVisible.collectAsStateWithLifecycle()

    // ── 页码角标顶部间距动画 ──
    /**
     * badgeExtraTopPadding：页码角标额外的顶部间距，带动画。
     *
     * 当顶部工具栏显示时（isToolbarVisible && toolbarMode == "full"），
     * 页码角标需要自动下移 56.dp 以避免被工具栏遮挡。
     * 使用 animateDpAsState 实现平滑动画过渡。
     *
     * 动画参数：tween(durationMillis = 300)，与工具栏滑入/滑出动画时间一致
     */
    val badgeExtraTopPadding by animateDpAsState(
        targetValue = if (isToolbarVisible && toolbarMode == "full") 56.dp else 0.dp,
        animationSpec = tween(durationMillis = 300),
        label = "badgeExtraTopPadding"
    )

    // ── 初始化阅读器 ──
    /**
     * LaunchedEffect(fileId)：当 fileId 变化时，重新初始化 ViewModel。
     * 仅在 fileId 改变时触发（包括首次进入页面）。
     */
    LaunchedEffect(fileId) {
        viewModel.initialize(fileId, forceStart = forceStart)
    }

    // ── 保存阅读进度（退出时） ──
    /**
     * DisposableEffect(fileId)：监听页面销毁事件，保存当前阅读位置。
     *
     * 关键行为：
     * - 创建时无操作
     * - 销毁时（onDispose）调用 viewModel.saveCurrentPage()
     * - 使用 fileId 作为 key，当 fileId 变化时会重新创建此 Effect
     *
     * 注意：即使用户在应用外（杀后台），onDispose 也会被调用，
     * 因为 LAUNCHER Activity 的销毁会触发 Composition 的销毁。
     */
    DisposableEffect(fileId) {
        android.util.Log.d("ReaderScreenV2", "DisposableEffect: 创建，fileId=$fileId")
        onDispose {
            android.util.Log.d("ReaderScreenV2", "DisposableEffect: 销毁，准备保存页码，currentPage=${viewModel.currentPage.value}")
            viewModel.saveCurrentPage()
            android.util.Log.d("ReaderScreenV2", "DisposableEffect: 已调用 saveCurrentPage()")
        }
    }

    // ── 初始页面跳转（修复：确保首次打开时跳转到正确页码） ──
    /**
     * initialPageJumpTriggered：跟踪初始页面跳转是否已触发。
     * 使用 fileId 作为 remember 的 key，当打开新文件时重置。
     */
    var initialPageJumpTriggered by remember(fileId) { mutableStateOf(false) }
    
    /**
     * LaunchedEffect(isInitializationComplete)：当初始化完成时触发初始页面跳转。
     *
     * 解决的问题：
     * 在 ReaderScreenV2 中，AndroidView 的 factory 在 Compose 首次组合时创建视图，
     * 而 initialPageJump 需要在 view 初始化完成后执行。
     * 因此在 isInitializationComplete 变为 true 时设置 pendingPageJump，
     * 由 AndroidView 的 update 块在 view 初始化后消费。
     */
    LaunchedEffect(isInitializationComplete) {
        if (isInitializationComplete && !initialPageJumpTriggered) {
            pendingPageJump = currentPage
            initialPageJumpTriggered = true
            android.util.Log.d("ReaderScreenV2", "初始页面跳转：currentPage=$currentPage")
        }
    }

    // ── 保存当前页码（跨模式切换不丢失） ──
    /**
     * savedCurrentPage：保存当前页码的快照，用于在翻页模式切换后恢复。
     *
     * 关键作用：
     * 当从 "continuous"（滚动模式）切换到 "single_page"（单页模式）时，
     * SinglePageView 内部的 onPageChanged 可能会重置 currentPage。
     * savedCurrentPage 在 currentPage 变化前保存了真实值，
     * 确保模式切换后能跳转到正确的页码。
     */
    var savedCurrentPage by remember(fileId) { mutableStateOf(currentPage) }

    /**
     * LaunchedEffect(currentPage)：当 currentPage 正常变化时更新 savedCurrentPage。
     * 正常滚动或翻页时同步保存最新页码。
     */
    LaunchedEffect(currentPage) {
        savedCurrentPage = currentPage
    }

    // ── 翻页模式切换时的页面恢复 ──
    /**
     * previousPageMode：记录之前的翻页模式，用于检测切换事件。
     *
     * 逻辑流程：
     * 1. previousPageMode 初始为空字符串
     * 2. 当 pageMode 首次变化（从 "" 到实际模式）时，跳过（首次不是切换）
     * 3. 后续 pageMode 再次变化时，触发 pendingPageJump = savedCurrentPage
     * 4. 确保模式切换后用户停留在同一页面
     */
    var previousPageMode by remember { mutableStateOf("") }
    LaunchedEffect(pageMode) {
        if (previousPageMode != "" && previousPageMode != pageMode) {
            // 使用 savedCurrentPage（不会被 onPageChanged 重置）
            pendingPageJump = savedCurrentPage
        }
        previousPageMode = pageMode
    }

    // ── 屏幕旋转时恢复页码 ──
    val configuration = LocalConfiguration.current
    var previousOrientation by remember { mutableStateOf(configuration.orientation) }
    LaunchedEffect(configuration.orientation) {
        if (previousOrientation != configuration.orientation) {
            previousOrientation = configuration.orientation
            if (isDocumentAvailable && isInitializationComplete) {
                pendingPageJump = currentPage
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 主界面布局
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 最外层 Box：全屏容器，作为所有子元素的叠放容器。
     * 子元素按声明顺序从底层到顶层绘制：
     *   1. PDF 内容视图（底层）
     *   2. 顶部工具栏（动画层）
     *   3. 底部工具栏（动画层）
     *   4. 页码角标（顶层）
     */
    Box(modifier = Modifier.fillMaxSize()) {
        // ═════════════════════════════════════════════════════════════
        // 第 1 层：PDF 内容视图
        // ═════════════════════════════════════════════════════════════
        if (isDocumentAvailable) {
            // ✅ Document 已就绪，才创建/显示阅读器视图
            if (pageMode == "continuous") {
                // ── 滚动模式 ──
                AndroidView(
                    factory = { ctx -> PdfContinuousView(ctx) },
                    modifier = Modifier.fillMaxSize(),
                    update = { view ->
                        // 注入协程作用域（供 PdfContinuousView 内部调用 ViewModel 的 suspend 方法）
                        view.setCoroutineScope(coroutineScope)
                        if (!view.isInitialized) {
                            // 首次初始化：绑定 ViewModel 的文档仓库和事件回调
                            view.init(viewModel.pdfDocumentRepository)
                            /**
                             * onPageChanged：当用户通过触摸滚动改变页面时触发。
                             * 仅更新 ViewModel 中的页码状态（用于 UI 显示），
                             * 不触发渲染（避免与 PdfContinuousView 内部渲染冲突）。
                             */
                            view.onPageChanged = { page ->
                                viewModel.updateCurrentPageForScroll(page)
                            }
                            /**
                             * onDoubleTap：双击切换工具栏可见性。
                             * _ 和 _ 分别代表 x, y 坐标（当前未使用）。
                             */
                            view.onDoubleTap = { _, _ -> viewModel.toggleToolbar() }
                            // ✅ 初始页面跳转由 LaunchedEffect(isDocumentAvailable, currentPage) 统一处理
                        }
                        // 处理用户主动翻页请求（每次 recomposition 时检查）
                        pendingPageJump?.let { targetPage ->
                            view.scrollToPage(targetPage)
                            pendingPageJump = null
                        }
                    }
                )
            } else {
                // ── 单页模式 ──
                AndroidView(
                    factory = { ctx -> SinglePageView(ctx) },
                    modifier = Modifier.fillMaxSize(),
                    update = { view ->
                        view.setCoroutineScope(coroutineScope)
                        if (!view.isInitialized) {
                            view.init(viewModel.pdfDocumentRepository)
                            /**
                             * onPageChanged：单页模式下用户滑动/翻页时触发。
                             * 仅更新页码状态，不触发额外渲染。
                             */
                            view.onPageChanged = { page ->
                                viewModel.updateCurrentPageForScroll(page)
                            }
                            view.onDoubleTap = { _, _ -> viewModel.toggleToolbar() }
                            // ✅ 初始页面跳转由 LaunchedEffect(isDocumentAvailable, currentPage) 统一处理
                        }
                        // 处理用户主动翻页请求
                        pendingPageJump?.let { targetPage ->
                            view.jumpToPage(targetPage)
                            pendingPageJump = null
                        }
                    }
                )
            }
        } else {
            // ⏳ Document 未就绪，显示加载指示器
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        }

        // ═════════════════════════════════════════════════════════════
        // 第 2 层：顶部工具栏（带动画滑入/滑出）
        // ═════════════════════════════════════════════════════════════
        /**
         * AnimatedVisibility：控制顶部工具栏的显示/隐藏动画。
         *
         * 显示条件：isToolbarVisible && toolbarMode == "full"
         * 进入动画：从上方滑入 + 淡入，300ms
         * 退出动画：朝上方滑出 + 淡出，300ms
         */
        AnimatedVisibility(
            visible = isToolbarVisible && toolbarMode == "full",
            enter = slideInVertically(
                initialOffsetY = { -it },
                animationSpec = tween(durationMillis = 300)
            ) + androidx.compose.animation.fadeIn(animationSpec = tween(300)),
            exit = slideOutVertically(
                targetOffsetY = { -it },
                animationSpec = tween(durationMillis = 300)
            ) + androidx.compose.animation.fadeOut(animationSpec = tween(300)),
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            TopAppBar(
                title = { Text(pdfFile?.name ?: "加载中...") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.Filled.ArrowBack,
                            contentDescription = "返回"
                        )
                    }
                }
            )
        }

        // ═════════════════════════════════════════════════════════════
        // 第 3 层：底部工具栏（带动画滑入/滑出）
        // ═════════════════════════════════════════════════════════════
        /**
         * AnimatedVisibility：控制底部工具栏的显示/隐藏动画。
         * 与顶部工具栏使用相同的动画参数（300ms），保持一致性。
         *
         * ReaderBottomBar 组件包含：上一页、页码显示、下一页、翻页模式切换等按钮。
         * 点击上一页/下一页时同时更新 ViewModel 页码和 pendingPageJump。
         */
        AnimatedVisibility(
            visible = isToolbarVisible && toolbarMode == "full",
            enter = slideInVertically(
                initialOffsetY = { it },
                animationSpec = tween(durationMillis = 300)
            ) + androidx.compose.animation.fadeIn(animationSpec = tween(300)),
            exit = slideOutVertically(
                targetOffsetY = { it },
                animationSpec = tween(durationMillis = 300)
            ) + androidx.compose.animation.fadeOut(animationSpec = tween(300)),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            ReaderBottomBar(
                pageMode = pageMode,
                currentPage = currentPage,
                totalPages = totalPages,
                isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE,
                // 翻页模式切换回调
                onPageModeChange = { newMode: String ->
                    viewModel.setPageMode(newMode)
                },
                // 上一页
                onPreviousPage = {
                    if (currentPage > 0) {
                        val targetPage = currentPage - 1
                        viewModel.previousPage()
                        pendingPageJump = targetPage
                    }
                },
                // 下一页
                onNextPage = {
                    if (currentPage < totalPages - 1) {
                        val targetPage = currentPage + 1
                        viewModel.nextPage()
                        pendingPageJump = targetPage
                    }
                },
                modifier = Modifier.navigationBarsPadding()
            )
        }

        // ═════════════════════════════════════════════════════════════
        // 第 4 层：页码角标（始终显示）
        // ═════════════════════════════════════════════════════════════
        /**
         * PageNumberBadge：固定在左上角的页码指示器。
         *
         * 显示条件：toolbarMode != "hidden"
         * 点击行为：点击后展开完整工具栏（isToolbarVisible = true）
         *
         * 定位：
         * - align = TopStart（左上角）
         * - statusBarsPadding()：避开系统状态栏
         * - start = 16.dp（左侧间距）
         * - top = 8.dp + badgeExtraTopPadding（上侧间距 + 工具栏动态偏移）
         */
        if (toolbarMode != "hidden") {
            PageNumberBadge(
                currentPage = currentPage,
                totalPages = totalPages,
                onClick = { viewModel.showToolbar() },
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .statusBarsPadding()
                    .padding(start = 16.dp, top = 8.dp + badgeExtraTopPadding)
            )
        }
    }
}
