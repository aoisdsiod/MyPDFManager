package com.example.pdfmanager.ui.screen.favorites

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material3.Icon
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.example.pdfmanager.data.model.FavoriteFolder
import com.example.pdfmanager.data.model.PdfFile
import com.example.pdfmanager.data.model.SavedFilter
import com.example.pdfmanager.data.repository.AppContainer
import com.example.pdfmanager.ui.component.PdfThumbnail
import com.example.pdfmanager.util.ThumbSize
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import coil.Coil
import coil.request.ImageRequest
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * 虚拟文件夹（收藏夹）内容页面
 *
 * ## 功能描述
 * 根据虚拟文件夹 ID 加载对应的筛选条件（SavedFilter），从全部 PDF 文件中筛选出匹配的文件，
 * 并以网格或列表形式展示。支持多选模式、缩略图预加载、文件排序合并等功能。
 * 该页面是"收藏"Tab 中点击一个虚拟文件夹后进入的详情页。
 *
 * ## 调用位置
 * - [com.example.pdfmanager.navigation.NavGraph]：导航图注册了路径 "favorite_folder/{folderId}"，
 *   当用户在 [com.example.pdfmanager.ui.screen.favorites.FavoritesScreen] 中点击某个
 *   [FavoriteFolder] 卡片时，通过 NavController.navigate("favorite_folder/${folder.id}") 跳转至此页面。
 *
 * ## 使用场景
 * - 用户在"收藏"页面点击一个已创建的虚拟文件夹（如"数学相关"、"工作文档"等），
 *   进入后看到该筛选条件下所有匹配的 PDF 文件列表。
 * - 用户可在该页面进行查看、分享、多选等操作。
 *
 * ## 核心逻辑流程
 * 1. 根据 folderId 从数据库加载 FavoriteFolder 对象（包含筛选条件的 JSON 字符串）
 * 2. 将 JSON 反序列化为 SavedFilter 对象
 * 3. 从 PdfRepository 中查询匹配该筛选条件的所有 PDF 文件 URI
 * 4. 从全量 PDF 列表中筛选出 URI 匹配的文件
 * 5. 读取已持久化的文件排序状态，将新匹配的文件插入列表头部
 * 6. 滚动时自动预加载前后区域的缩略图到 Coil 缓存，提升滑动流畅度
 *
 * @param folderId 虚拟文件夹的唯一标识（字符串 ID）。
 *                 通过导航参数传入，用于从 [FavoritesRepository] 中加载对应的 [FavoriteFolder] 对象。
 * @param navController 导航控制器，用于页面间的返回和跳转。
 *                      在本页面中主要用于：
 *                      - 返回按钮和系统返回键时调用 popBackStack()
 *                      - 点击文件时跳转到 "detail/{pdf.id}" 详情页
 * @param modifier 外部布局修饰符，可选参数，默认 Modifier。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FavoriteFolderContentScreen(
    folderId: String,
    navController: NavController,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    // ─────────────────────────────────────────────────────────────────────
    // 拦截系统返回手势，防止直接退出 APP
    // 问题3修复：返回时通过设置 AppContainer.selectedTab.value = 0 确保底部导航栏选中收藏 Tab
    // ─────────────────────────────────────────────────────────────────────
    BackHandler {
        AppContainer.selectedTab.value = 0  // 问题3修复：返回时确保选中收藏 Tab
        navController.popBackStack()
    }

    // ─────────────────────────────────────────────────────────────────────
    // 加载虚拟文件夹对象
    // 根据传入的 folderId 从 FavoritesRepository 数据库中查找到对应的 FavoriteFolder 实体
    // 该实体包含了 name（文件夹名）、savedFilterJson（JSON 字符串格式的筛选条件）等字段
    // ─────────────────────────────────────────────────────────────────────
    var favoriteFolder by remember { mutableStateOf<FavoriteFolder?>(null) }
    LaunchedEffect(folderId) {
        favoriteFolder = AppContainer.favoritesRepository.getFavoriteFolderById(folderId)
    }

    // ─────────────────────────────────────────────────────────────────────
    // 多选模式相关状态
    // isMultiSelectMode：从 PreferencesManager 中读取的全局多选状态，支持生命周期感知收集
    // selectedFileIds：当前已被选中的文件 ID 集合（StateFlow），用于显示选中标记
    // ─────────────────────────────────────────────────────────────────────
    val isMultiSelectMode by AppContainer.preferencesManager.getMultiSelectModeFlow()
        .collectAsStateWithLifecycle(initialValue = false)
    val selectedFileIds by AppContainer.selectedFileIds.collectAsStateWithLifecycle()

    // ─────────────────────────────────────────────────────────────────────
    // 观察全量 PDF 文件列表 Flow
    // PdfRepository.pdfFiles 是一个 StateFlow<List<PdfFile>>，当数据库中的文件
    // 发生变化时会自动发射新值，此处通过 collectAsStateWithLifecycle() 实现
    // 生命周期感知的响应式监听，确保页面重建时数据自动刷新
    // ─────────────────────────────────────────────────────────────────────
    val allPdfFiles by AppContainer.pdfRepository.pdfFiles.collectAsStateWithLifecycle()
    
    // ─────────────────────────────────────────────────────────────────────
    // 页面本地状态
    // displayFiles：经过筛选 + 合并排序后的最终展示文件列表
    // isGridView：当前视图模式（true=网格视图，false=列表视图）
    // thumbSize：缩略图尺寸枚举（SMALL/MEDIUM/LARGE），影响网格列数和预加载策略
    // ─────────────────────────────────────────────────────────────────────
    var displayFiles by remember { mutableStateOf<List<PdfFile>>(emptyList()) }
    var isGridView by remember { mutableStateOf(true) }
    var thumbSize by remember { mutableStateOf(ThumbSize.MEDIUM) }
    
    // ── 缩略图预加载相关 ────────────────────────────────────────────────
    // 获取 Coil 图片加载器实例，用于执行预加载请求
    val imageLoader = Coil.imageLoader(context)
    // 懒加载网格的状态控制器，用于监听当前可见项的范围
    val gridState = rememberLazyGridState()
    
    // 屏幕密度，用于 dp → px 转换（预加载时的图片尺寸计算）
    val density = context.resources.displayMetrics.density
    
    // 根据当前缩略图尺寸计算预加载阈值
    // 即在可视区域前后各预加载多少个文件项的缩略图
    // 缩略图越大，屏幕内能显示的项越少，预加载数量相应减少
    val preloadThreshold = remember(thumbSize) {
        when (thumbSize) {
            ThumbSize.LARGE -> 8   // 大缩略图（2列网格）：前后各预加载 8 个
            ThumbSize.MEDIUM -> 16  // 中缩略图（3列网格）：前后各预加载 16 个
            ThumbSize.SMALL -> 24   // 小缩略图（4列网格）：前后各预加载 24 个
        }
    }
    
    // ── 预加载逻辑 ─────────────────────────────────────────────────────
    // 使用 snapshotFlow 监听 LazyVerticalGrid 的滚动位置变化
    // 当用户滚动时，获取当前可见的第一个和最后一个 item 的索引
    // 双向预加载（往前和往后各 preloadThreshold 个）缩略图到 Coil 缓存中
    // 这样当用户滚动到相应位置时，缩略图可以立即从缓存中显示，避免卡顿
    LaunchedEffect(gridState, displayFiles.size, thumbSize, preloadThreshold) {
        snapshotFlow {
            val visibleItems = gridState.layoutInfo.visibleItemsInfo
            if (visibleItems.isEmpty()) -1 to -1 else visibleItems.first().index to visibleItems.last().index
        }
            .distinctUntilChanged()  // 仅当可见范围变化时才处理，避免重复计算
            .debounce(100)           // 100ms 防抖，避免快速滚动时频繁触发预加载
            .collect { (firstVisible, lastVisible) ->
                if (firstVisible < 0 || displayFiles.isEmpty()) return@collect
                
                // 计算预加载范围：往前和往后各扩展 preloadThreshold 个
                val start = (firstVisible - preloadThreshold).coerceIn(0, displayFiles.size)
                val end = (lastVisible + preloadThreshold + 1).coerceIn(0, displayFiles.size)
                
                if (start < end) {
                    // 异步并发预加载，不阻塞主线程
                    for (i in start until end) {
                        val pdf = displayFiles[i]
                        launch {
                            try {
                                // 第一步：在 IO 线程中获取缩略图缓存 URI
                                // ThumbnailGenerator.getCachedThumbnail() 会检查磁盘缓存，
                                // 如果不存在则生成新的缩略图并缓存
                                val thumbnailUri = withContext(Dispatchers.IO) {
                                    com.example.pdfmanager.data.local.ThumbnailGenerator.getCachedThumbnail(context, pdf.uri)
                                }
                                
                                if (thumbnailUri != null) {
                                    // 根据当前缩略图模式计算网格列数和尺寸
                                    val columns = when (thumbSize) {
                                        ThumbSize.LARGE -> 2
                                        ThumbSize.MEDIUM -> 3
                                        ThumbSize.SMALL -> 4
                                    }
                                    val thumbnailSize = when (thumbSize) {
                                        ThumbSize.LARGE -> 180   // 大：180dp → px 约为 540px
                                        ThumbSize.MEDIUM -> 120  // 中：120dp → px 约为 360px
                                        ThumbSize.SMALL -> 90    // 小：90dp → px 约为 270px
                                    }
                                    // 构建 Coil 图片请求并预先加载到缓存中
                                    val request = ImageRequest.Builder(context)
                                        .data(thumbnailUri)  // 使用已缓存的缩略图 URI
                                        .size(thumbnailSize, (thumbnailSize * 1.414).toInt())  // 保持 A4 纸 1:1.414 比例
                                        .build()
                                    imageLoader.execute(request)  // 执行预加载（同步，阻塞当前协程）
                                }
                            } catch (e: Exception) { /* 预加载失败不影响主流程，静默忽略 */ }
                        }
                    }
                }
            }
    }
    // ────────────────────────────────────────────────────────────────────

    // ── 加载并合并文件列表 ─────────────────────────────────────────────
    // 当虚拟文件夹信息或全量文件列表发生变化时，重新执行筛选和排序
    LaunchedEffect(favoriteFolder, allPdfFiles) {
        val folder = favoriteFolder ?: return@LaunchedEffect
        if (allPdfFiles.isEmpty()) return@LaunchedEffect  // 全量数据还未加载完成，等待下一轮触发

        // 将虚拟文件夹中保存的 JSON 筛选条件反序列化为 SavedFilter 对象
        val savedFilter = Gson().fromJson(folder.savedFilterJson, SavedFilter::class.java)

        // 1. 从全量 PDF 文件列表中获取所有文件（已通过 Flow 观察到最新数据）
        val allFiles = allPdfFiles

        // 2. 根据筛选条件从 PdfRepository 数据库中查询匹配的 PDF 文件 URI 集合
        val matchedPdfUris = AppContainer.pdfRepository.getFileUrisByFilter(savedFilter)
        // 从全量列表中筛选出 URI 匹配的文件
        val matchedFiles = allFiles.filter { it.uri.toString() in matchedPdfUris }

        // 3. 从 FavoritesRepository 读取该虚拟文件夹已持久化的文件排序顺序
        // 这是为了保持用户手动排序的顺序（如拖拽排序后）
        val orderedPdfIds = AppContainer.favoritesRepository.getPdfOrder(folder.id)

        // 4. 合并排序策略：
        //    - orderedList：已存在排序记录中的文件，保持原有顺序
        //    - newList：新匹配的文件（之前没有排序记录），插入到列表开头
        //    这样设计确保新文件不会丢失，同时保留用户对已有文件的排序偏好
        val matchedIds = matchedFiles.map { it.id }.toSet()
        val newPdfIds = matchedIds - orderedPdfIds.toSet()
        val orderedList = orderedPdfIds.mapNotNull { id -> matchedFiles.find { it.id == id } }
        val newList = newPdfIds.mapNotNull { id -> matchedFiles.find { it.id == id } }

        displayFiles = newList + orderedList  // 新文件在前，已排序文件在后
    }

    // ── 页面主体布局 ─────────────────────────────────────────────────
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (isMultiSelectMode) {
                        // 多选模式下标题显示当前已选文件数量
                        Text("已选 ${selectedFileIds.size} 个")
                    } else {
                        // 普通模式下显示虚拟文件夹名称
                        Text(favoriteFolder?.name ?: "收藏内容")
                    }
                },
                navigationIcon = {
                    // 返回按钮（和 BackHandler 逻辑一致）
                    IconButton(onClick = { 
                        AppContainer.selectedTab.value = 0  // 问题3修复：返回时确保选中收藏 Tab
                        navController.popBackStack() 
                    }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (isMultiSelectMode) {
                        // 多选模式下在顶栏右侧显示"分享"按钮
                        Button(
                            onClick = { /* TODO: 显示分享目标选择器，待实现 */ },
                            modifier = Modifier.padding(end = 8.dp)
                        ) {
                            Text("分享")
                        }
                    } else {
                        // 普通模式下显示视图切换按钮（网格 ↔ 列表）
                        TextButton(onClick = { isGridView = !isGridView }) {
                            Text(if (isGridView) "列表" else "网格")
                        }
                    }
                }
            )
        },
        modifier = modifier
    ) { paddingValues ->
        if (displayFiles.isEmpty()) {
            // ── 空状态提示 ──
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                Text("此虚拟文件夹暂无匹配的文件")
            }
        } else {
            // ── 网格视图 ──
            if (isGridView) {
                val columns = when (thumbSize) {
                    ThumbSize.LARGE -> 2
                    ThumbSize.MEDIUM -> 3
                    ThumbSize.SMALL -> 4
                }
                LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Fixed(columns),
                    contentPadding = PaddingValues(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(paddingValues)
                ) {
                    items(displayFiles, key = { it.id }) { pdf ->
                        GridItem(
                            pdf = pdf,
                            isSelected = selectedFileIds.contains(pdf.id),
                            onClick = {
                                if (isMultiSelectMode) {
                                    // 多选模式：切换选中/取消选中状态
                                    val newSet = selectedFileIds.toMutableSet()
                                    if (newSet.contains(pdf.id)) newSet.remove(pdf.id) else newSet.add(pdf.id)
                                    AppContainer.selectedFileIds.value = newSet
                                } else {
                                    // 普通模式：导航到文件详情页
                                    navController.navigate("detail/${pdf.id}")
                                }
                            },
                            thumbSize = thumbSize
                        )
                    }
                }
            } else {
                // ── 列表视图（1列网格模拟列表） ──
                LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Fixed(1),
                    contentPadding = PaddingValues(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(paddingValues)
                ) {
                    items(displayFiles, key = { it.id }) { pdf ->
                        ListItem(
                            pdf = pdf,
                            isSelected = selectedFileIds.contains(pdf.id),
                            onClick = {
                                if (isMultiSelectMode) {
                                    val newSet = selectedFileIds.toMutableSet()
                                    if (newSet.contains(pdf.id)) newSet.remove(pdf.id) else newSet.add(pdf.id)
                                    AppContainer.selectedFileIds.value = newSet
                                } else {
                                    navController.navigate("detail/${pdf.id}")
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}


/**
 * 网格项（卡片式 PDF 文件卡片）
 *
 * ## 功能描述
 * 在网格视图中渲染单个 PDF 文件的预览卡片。包含缩略图、文件名和选中态边框。
 * 与 [com.example.pdfmanager.ui.screen.allfiles.AllFilesScreen] 中的网格项保持一致的 UI 风格。
 *
 * ## 调用位置
 * - [FavoriteFolderContentScreen]：当 isGridView = true 时，在 LazyVerticalGrid 的 items 中调用。
 * - [com.example.pdfmanager.ui.screen.allfiles.AllFilesScreen]：文件列表页面也使用相同风格的 GridItem。
 *
 * ## 使用场景
 * - 虚拟文件夹内容页以网格模式展示文件时，每个文件项显示为带缩略图的卡片
 * - 多选模式下选中时卡片边框高亮，右上角显示 CheckBox 图标
 *
 * @param pdf 要展示的 PDF 文件数据模型，包含文件名、URI、ID 等字段
 * @param isSelected 当前文件是否被选中。选中时卡片加边框并显示 CheckBox 图标
 * @param onClick 点击卡片时的回调函数。响应逻辑由外层调用方决定：
 *                - 多选模式：切换选中状态
 *                - 普通模式：导航到文件详情页
 * @param thumbSize 缩略图尺寸枚举，决定卡片缩略图区域的大小：
 *                  LARGE → 180dp，MEDIUM → 120dp，SMALL → 90dp
 *                  默认为 MEDIUM
 */
@Composable
private fun GridItem(
    pdf: PdfFile,
    isSelected: Boolean,
    onClick: () -> Unit,
    thumbSize: ThumbSize = ThumbSize.MEDIUM
) {
    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (isSelected)
                    Modifier.border(1.dp, MaterialTheme.colorScheme.primary, MaterialTheme.shapes.small)
                else
                    Modifier
            ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Box {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                // 根据缩略图尺寸计算卡片的缩略图宽高（保持 1:1.414 的 A4 比例）
                val thumbnailWidthDp = when (thumbSize) {
                    ThumbSize.LARGE -> 180.dp
                    ThumbSize.MEDIUM -> 120.dp
                    ThumbSize.SMALL -> 90.dp
                }
                val thumbnailHeightDp = (thumbnailWidthDp.value * 1.414).dp
                
                PdfThumbnail(
                    pdfFile = pdf,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(0.75f),
                    thumbnailWidthDp = thumbnailWidthDp,
                    thumbnailHeightDp = thumbnailHeightDp
                )
                Text(
                    text = pdf.name,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    modifier = Modifier.padding(4.dp)
                )
            }
            // 选中状态时在卡片右上角显示打勾的 CheckBox
            if (isSelected) {
                Checkbox(
                    checked = true,
                    onCheckedChange = null,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                )
            }
        }
    }
}

/**
 * 列表项（列表视图中的 PDF 文件行）
 *
 * ## 功能描述
 * 在列表视图中渲染单个 PDF 文件的横向行布局。左侧显示小尺寸缩略图，
 * 中间显示文件名，右侧（选中时）显示 CheckBox。
 * 与 [com.example.pdfmanager.ui.screen.allfiles.AllFilesScreen] 中的列表项保持一致的 UI 风格。
 *
 * ## 调用位置
 * - [FavoriteFolderContentScreen]：当 isGridView = false 时，以 1 列网格模拟列表视图，在 items 中调用。
 * - [com.example.pdfmanager.ui.screen.allfiles.AllFilesScreen]：文件列表页面也使用相同风格的 ListItem。
 *
 * ## 使用场景
 * - 用户点击顶栏的"列表"按钮后，虚拟文件夹内容以列表模式展示
 * - 适合需要在一屏内浏览更多文件、快速扫读文件名的场景
 * - 多选模式下同样支持选中态标识
 *
 * @param pdf 要展示的 PDF 文件数据模型，包含文件名、URI、ID 等字段
 * @param isSelected 当前文件是否被选中。选中时整行卡片加高亮边框，右侧显示 CheckBox 图标
 * @param onClick 点击行时的回调函数。响应逻辑由外层调用方决定：
 *                - 多选模式：切换选中状态
 *                - 普通模式：导航到文件详情页
 */
@Composable
private fun ListItem(
    pdf: PdfFile,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = if (isSelected) 1.dp else 0.dp,
                color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                shape = MaterialTheme.shapes.small
            ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier.padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 列表视图缩略图固定尺寸：48dp × 64dp（保持 1:1.414 比例）
            PdfThumbnail(
                pdfFile = pdf,
                modifier = Modifier.size(width = 48.dp, height = 64.dp),
                thumbnailWidthDp = 48.dp,
                thumbnailHeightDp = 64.dp
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = pdf.name,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
            // 选中状态时在行尾显示打勾的 CheckBox
            if (isSelected) {
                Checkbox(
                    checked = true,
                    onCheckedChange = null,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
        }
    }
}
