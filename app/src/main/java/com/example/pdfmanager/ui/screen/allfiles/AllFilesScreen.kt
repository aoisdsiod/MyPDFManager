package com.example.pdfmanager.ui.screen.allfiles

import android.net.Uri
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import coil.Coil
import coil.ImageLoader
import coil.request.ImageRequest
import com.example.pdfmanager.data.model.PdfFile
import com.example.pdfmanager.data.model.SavedFilter
import com.example.pdfmanager.data.model.FilterLogic
import com.example.pdfmanager.data.repository.AppContainer
import com.example.pdfmanager.ui.component.PdfThumbnail
import com.example.pdfmanager.util.ThumbSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.debounce


/**
 * 全部文件页面（主内容页）
 * 
 * 功能说明：
 * 1. 显示库文件夹中的 PDF 文件列表（支持网格/列表两种视图模式）
 * 2. 管理文件列表生命周期（初始化、扫描、刷新）
 * 3. 支持多选模式（选中文件、全选、清空）
 * 4. 支持筛选模式（按标签筛选，显示筛选状态栏）
 * 5. 包含缩略图预加载优化（滚动双向预加载）
 * 6. 未绑定库文件夹时显示引导页面（LibrarySetupScreen）
 * 
 * UI 结构：
 * ```
 * TopAppBar（搜索/筛选/视图切换按钮）
 * ├── 筛选状态栏（筛选激活时显示）
 * ├── 扫描进度条（扫描期间显示）
 * ├── 文件列表（网格或列表模式）
 * │   ├── GridItem（网格模式）
 * │   └── PdfListItem（列表模式）
 * └── 空状态（无文件时显示）
 * └── 对话框（退出多选/保存收藏）
 * ```
 * 
 * 交互逻辑：
 * - 点击文件：多选模式=选中/取消选中，普通模式=跳转详情页
 * - 长按/多选按钮：进入多选模式
 * - 筛选按钮：跳转 FilterScreen
 * - 搜索按钮：跳转 SearchScreen
 * 
 * 生命周期：
 * - ON_RESUME：首次触发 initialize()，后续不做扫描
 * - DisposableEffect：管理生命周期监听器
 * 
 * 调用位置：
 * - MainScreen.kt - 底部 Tab 1 的内容
 * 
 * @param onRequestLibrary SAF 库文件夹选择回调
 * @param navController 导航控制器
 * @param viewModel AllFilesViewModel 实例（通过 Factory 创建）
 * @param modifier 修饰符
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AllFilesScreen(
    onRequestLibrary: () -> Unit = {},
    navController: NavController,
    viewModel: AllFilesViewModel = viewModel(
        factory = AllFilesViewModel.Factory()
    ),
    modifier: Modifier = Modifier
) {
    // ── ViewModel 状态收集 ──
    val pdfFiles by viewModel.pdfFiles.collectAsStateWithLifecycle()
    val isGridView by viewModel.isGridView.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val thumbSize by viewModel.thumbSize.collectAsStateWithLifecycle()
    val needsLibrarySetup by viewModel.needsLibrarySetup.collectAsStateWithLifecycle()
    val isFilterActive by viewModel.isFilterActive.collectAsStateWithLifecycle()
    val currentFilter by viewModel.currentFilter.collectAsStateWithLifecycle()
    val scanProgress by viewModel.scanProgress.collectAsStateWithLifecycle()

    // 多选分享相关状态
    val isMultiSelectMode by viewModel.isMultiSelectMode.collectAsStateWithLifecycle()
    val selectedFileIds by AppContainer.selectedFileIds.collectAsStateWithLifecycle()
    var showExitMultiSelectDialog by remember { mutableStateOf(false) }
    var showSaveFavoriteDialog by remember { mutableStateOf(false) }
    var saveFavoriteName by remember { mutableStateOf("") }
    val coroutineScope = rememberCoroutineScope()

    // ── 缩略图预加载 ──
    val context = LocalContext.current
    val imageLoader = Coil.imageLoader(context)
    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()
    // 屏幕密度（用于 dp → px 转换）
    val density = context.resources.displayMetrics.density

    // 根据视图模式和缩略图大小计算预加载阈值
    val preloadThreshold = remember(isGridView, thumbSize) {
        if (!isGridView) {
            20  // 列表模式：前后各预加载 20 个
        } else {
            when (thumbSize) {
                ThumbSize.LARGE -> 8   // 网格大（2列）：前后各 8 个
                ThumbSize.MEDIUM -> 16  // 网格中（3列）：前后各 16 个
                ThumbSize.SMALL -> 24   // 网格小（4列）：前后各 24 个
            }
        }
    }

    Log.d("AllFilesScreen", ">>> COMPOSED: pdfFiles.size=${pdfFiles.size}, isGridView=$isGridView, preloadThreshold=$preloadThreshold")

    // ── 列表模式缩略图预加载 ──
    // 使用 snapshotFlow 监听滚动位置，双向预加载（往前+往后）
    if (!isGridView) {
        LaunchedEffect(listState, pdfFiles.size, preloadThreshold) {
            Log.d("AllFilesScreen", ">>> LIST LaunchedEffect START, pdfFiles.size=${pdfFiles.size}, threshold=$preloadThreshold")
            snapshotFlow {
                val visibleItems = listState.layoutInfo.visibleItemsInfo
                if (visibleItems.isEmpty()) -1 to -1 else visibleItems.first().index to visibleItems.last().index
            }
                .distinctUntilChanged()
                .debounce(100)  // 100ms 防抖，避免过度频繁触发
                .collect { (firstVisible, lastVisible) ->
                    if (firstVisible < 0 || pdfFiles.isEmpty()) return@collect
                    // 双向预加载：往前 preloadThreshold 个 + 往后 preloadThreshold 个
                    val start = (firstVisible - preloadThreshold).coerceIn(0, pdfFiles.size)
                    val end = (lastVisible + preloadThreshold + 1).coerceIn(0, pdfFiles.size)
                    if (start < end) {
                        Log.d("AllFilesScreen", "Preload list: visible=$firstVisible~$lastVisible, range=$start~${end - 1}")
                        for (i in start until end) {
                            val pdf = pdfFiles[i]
                            coroutineScope.launch {
                                try {
                                    val thumbnailUri = withContext(Dispatchers.IO) {
                                        com.example.pdfmanager.data.local.ThumbnailGenerator.getCachedThumbnail(context, pdf.uri)
                                    }
                                    if (thumbnailUri != null) {
                                        // 列表视图缩略图尺寸：48.dp × 64.dp → 转换为 px
                                        val sizeWidthPx = (48 * density).toInt()
                                        val sizeHeightPx = (64 * density).toInt()
                                        val request = ImageRequest.Builder(context)
                                            .data(thumbnailUri)
                                            .size(sizeWidthPx, sizeHeightPx)
                                            .build()
                                        imageLoader.execute(request)
                                    }
                                } catch (e: Exception) { }
                            }
                        }
                    }
                }
        }
    }

    // ── 网格模式缩略图预加载 ──
    if (isGridView) {
        LaunchedEffect(gridState, pdfFiles.size, thumbSize, preloadThreshold) {
            Log.d("AllFilesScreen", ">>> GRID LaunchedEffect START, pdfFiles.size=${pdfFiles.size}, thumbSize=$thumbSize, threshold=$preloadThreshold")
            snapshotFlow {
                val visibleItems = gridState.layoutInfo.visibleItemsInfo
                if (visibleItems.isEmpty()) -1 to -1 else visibleItems.first().index to visibleItems.last().index
            }
                .distinctUntilChanged()
                .debounce(100)
                .collect { (firstVisible, lastVisible) ->
                    if (firstVisible < 0 || pdfFiles.isEmpty()) return@collect
                    val start = (firstVisible - preloadThreshold).coerceIn(0, pdfFiles.size)
                    val end = (lastVisible + preloadThreshold + 1).coerceIn(0, pdfFiles.size)
                    if (start < end) {
                        Log.d("AllFilesScreen", "Preload grid[$thumbSize]: visible=$firstVisible~$lastVisible, range=$start~${end - 1}")
                        for (i in start until end) {
                            val pdf = pdfFiles[i]
                            coroutineScope.launch {
                                try {
                                    val thumbnailUri = withContext(Dispatchers.IO) {
                                        com.example.pdfmanager.data.local.ThumbnailGenerator.getCachedThumbnail(context, pdf.uri)
                                    }
                                    if (thumbnailUri != null) {
                                        val thumbDp = when (thumbSize) {
                                            ThumbSize.LARGE -> 180.dp
                                            ThumbSize.MEDIUM -> 120.dp
                                            ThumbSize.SMALL -> 90.dp
                                        }
                                        val sizePx = (thumbDp.value * density).toInt()
                                        val request = ImageRequest.Builder(context)
                                            .data(thumbnailUri)
                                            .size(sizePx, (sizePx * 1.414).toInt())  // 1:1.414 比例
                                            .build()
                                        imageLoader.execute(request)
                                    }
                                } catch (e: Exception) { }
                            }
                        }
                    }
                }
        }
    }

    // ── 监听筛选结果（FilterScreen 返回后消费） ──
    val pendingFilter by AppContainer.pendingFilterResult.collectAsStateWithLifecycle()
    LaunchedEffect(pendingFilter) {
        if (pendingFilter != null) {
            viewModel.applyFilter(pendingFilter!!)
            AppContainer.consumePendingFilterResult()
        }
    }

    // ── 生命周期管理 ──
    val lifecycleOwner = LocalLifecycleOwner.current
    var hasInitialized by remember { mutableStateOf(false) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                if (!hasInitialized) {
                    viewModel.initialize()
                    hasInitialized = true
                }
                // 后续 resume 不做扫描
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // ── 切 tab 后恢复滚动位置 ──
    LaunchedEffect(Unit) {
        viewModel.getSavedScrollPosition()?.let { (index, offset) ->
            if (isGridView) {
                gridState.scrollToItem(index, offset)
            } else {
                listState.scrollToItem(index, offset)
            }
        }

    }

    // ── 切 tab 前保存滚动位置 ──
    DisposableEffect(listState, gridState, isGridView) {
        onDispose {
            if (isGridView) {
                viewModel.saveScrollPosition(
                    gridState.firstVisibleItemIndex,
                    gridState.firstVisibleItemScrollOffset
                )
            } else {
                viewModel.saveScrollPosition(
                    listState.firstVisibleItemIndex,
                    listState.firstVisibleItemScrollOffset
                )
            }

        }
    }

    // ── 显示引导页或主内容 ──
    if (needsLibrarySetup) {
        LibrarySetupScreen(onRequestLibrary = onRequestLibrary)
    } else {
        // 多选模式下拦截系统返回键
        BackHandler(enabled = isMultiSelectMode) {
            showExitMultiSelectDialog = true
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        if (isMultiSelectMode) {
                            Text("已选 ${selectedFileIds.size} 个")
                        } else {
                            Column {
                                Text(if (isFilterActive) "筛选" else "全部文件")
                                Text("共 ${pdfFiles.size} 个文件",
                                    style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    },
                    navigationIcon = {
                        if (isMultiSelectMode) {
                            TextButton(onClick = { showExitMultiSelectDialog = true }) {
                                Text("退出多选模式")
                            }
                        }
                    },
                    actions = {
                        if (isMultiSelectMode) {
                            // 多选模式下：保留筛选和视图切换按钮
                            BadgedBox(badge = { if (isFilterActive) Badge() }) {
                                TextButton(onClick = { navController.navigate("filter") }) { Text("筛选") }
                            }
                            TextButton(onClick = { viewModel.toggleViewMode() }) {
                                Text(if (isGridView) "列表" else "网格")
                            }
                        } else {
                            // 非多选模式：搜索、筛选、视图切换
                            IconButton(onClick = { navController.navigate("search") }) {
                                Icon(Icons.Default.Search, contentDescription = "搜索")
                            }
                            BadgedBox(badge = { if (isFilterActive) Badge() }) {
                                TextButton(onClick = { navController.navigate("filter") }) { Text("筛选") }
                            }
                            TextButton(onClick = { viewModel.toggleViewMode() }) {
                                Text(if (isGridView) "列表" else "网格")
                            }
                        }
                    }
                )
            },
            modifier = modifier
        ) { paddingValues ->
            Column(modifier = Modifier.padding(paddingValues)) {
                // 筛选状态栏
                if (isFilterActive && currentFilter != null) {
                    FilterStatusBar(
                        currentFilter = currentFilter!!,
                        onClearFilter = { viewModel.clearFilter() }
                    )
                }

                // 保存为收藏按钮（筛选激活且非多选模式时显示）
                if (isFilterActive && currentFilter != null && !isMultiSelectMode) {
                    Button(
                        onClick = {
                            val tagKeys = currentFilter?.selectedTagKeys ?: emptyList()
                            saveFavoriteName = tagKeys.joinToString("+") { it.substringAfter(":") }
                            showSaveFavoriteDialog = true
                        },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                    ) { Text("保存为收藏") }
                }

                Box(modifier = Modifier.fillMaxSize()) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        // 扫描进度条
                        if (scanProgress != null) {
                            LinearProgressIndicator(
                                progress = { scanProgress!!.scannedCount / (pdfFiles.size.coerceAtLeast(1)).toFloat() },
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                            )
                            Text("正在扫描... 已扫描 ${scanProgress!!.scannedCount} 个文件",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(horizontal = 16.dp))
                            if (scanProgress!!.currentFileName.isNotBlank()) {
                                Text("当前: ${scanProgress!!.currentFileName}",
                                    style = MaterialTheme.typography.bodySmall, maxLines = 1,
                                    modifier = Modifier.padding(horizontal = 16.dp))
                            }
                        }

                        when {
                            // 空状态（无筛选且无文件）
                            pdfFiles.isEmpty() && !isLoading -> {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text(
                                        if (isFilterActive) "此筛选条件下无符合要求的文件" else "没有 PDF 文件\n请绑定库文件夹",
                                        textAlign = TextAlign.Center, modifier = Modifier.padding(16.dp))
                                }
                            }
                            // 加载中
                            pdfFiles.isEmpty() && isLoading -> {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        CircularProgressIndicator()
                                        Spacer(modifier = Modifier.height(12.dp))
                                        Text("正在加载...", style = MaterialTheme.typography.bodyMedium)
                                    }
                                }
                            }
                            // 文件列表
                            else -> {
                                if (isGridView) {
                                    val columns = when (thumbSize) {
                                        ThumbSize.LARGE -> 2; ThumbSize.MEDIUM -> 3; ThumbSize.SMALL -> 4
                                    }
                                    LazyVerticalGrid(
                                        state = gridState,
                                        columns = GridCells.Fixed(columns),
                                        contentPadding = PaddingValues(8.dp),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        items(pdfFiles, key = { it.id }) { pdf ->
                                            GridItem(
                                                pdf = pdf,
                                                isSelected = selectedFileIds.contains(pdf.id),
                                                thumbSize = thumbSize,
                                                onClick = {
                                                    if (isMultiSelectMode) viewModel.toggleFileSelection(pdf.id)
                                                    else navController.navigate("detail/${pdf.id}")
                                                }
                                            )
                                        }
                                    }
                                } else {
                                    LazyColumn(
                                        state = listState,
                                        contentPadding = PaddingValues(8.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        items(pdfFiles, key = { it.id }) { pdf ->
                                            PdfListItem(
                                                pdf = pdf,
                                                isSelected = selectedFileIds.contains(pdf.id),
                                                onClick = {
                                                    if (isMultiSelectMode) viewModel.toggleFileSelection(pdf.id)
                                                    else navController.navigate("detail/${pdf.id}")
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // 退出多选模式确认对话框
                if (showExitMultiSelectDialog) {
                    AlertDialog(
                        onDismissRequest = { showExitMultiSelectDialog = false },
                        title = { Text("确定退出多选模式？") },
                        text = { Text("已选文件将取消选中。") },
                        confirmButton = { Button(onClick = { showExitMultiSelectDialog = false; viewModel.toggleMultiSelectMode() }) { Text("确定") } },
                        dismissButton = { TextButton(onClick = { showExitMultiSelectDialog = false }) { Text("取消") } }
                    )
                }

                // 保存为收藏对话框
                if (showSaveFavoriteDialog) {
                    AlertDialog(
                        onDismissRequest = { showSaveFavoriteDialog = false },
                        title = { Text("保存为收藏") },
                        text = {
                            Column {
                                Text("将当前筛选条件保存为虚拟文件夹：")
                                Spacer(modifier = Modifier.height(8.dp))
                                OutlinedTextField(value = saveFavoriteName, onValueChange = { saveFavoriteName = it },
                                    label = { Text("收藏名称") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            }
                        },
                        confirmButton = {
                            Button(onClick = {
                                showSaveFavoriteDialog = false
                                val filter = currentFilter
                                if (filter != null && saveFavoriteName.isNotBlank()) {
                                    coroutineScope.launch { AppContainer.favoritesRepository.createFavoriteFolder(name = saveFavoriteName, savedFilter = filter) }
                                }
                            }) { Text("保存") }
                        },
                        dismissButton = { TextButton(onClick = { showSaveFavoriteDialog = false }) { Text("取消") } }
                    )
                }
            }
        }
    }
}

/**
 * 筛选状态栏（显示当前筛选条件）
 * 
 * 功能说明：
 * 1. 显示筛选逻辑（AND/OR）
 * 2. 按类别分组显示选中的标签
 * 3. 包含清除筛选按钮
 * 4. 支持"无标签"特殊筛选
 * 
 * @param currentFilter 当前筛选条件
 * @param onClearFilter 清除筛选回调
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun FilterStatusBar(currentFilter: SavedFilter, onClearFilter: () -> Unit) {
    Surface(tonalElevation = 2.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
            // 第一行：逻辑词 + 清除按钮
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                val logicText = if (currentFilter.filterLogic == FilterLogic.AND) "[且]" else "[或]"
                Text(logicText, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                TextButton(onClick = onClearFilter, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) { Text("清除", style = MaterialTheme.typography.labelMedium) }
            }
            // 按类别分行展示选中的标签
            if (currentFilter.includeNoTag) {
                Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("🏷️ 无标签", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                val grouped = mutableMapOf<String, MutableList<String>>()
                for (key in currentFilter.selectedTagKeys) {
                    val parts = key.split(":", limit = 2)
                    if (parts.size == 2) grouped.getOrPut(parts[0]) { mutableListOf() }.add(parts[1])
                }
                for ((categoryId, tagValues) in grouped) {
                    val category = AppContainer.tagRepository.getCategoryById(categoryId)
                    if (category != null) {
                        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.Top) {
                            val categoryColor = Color(category.color)
                            Text("🏷️ ${category.name}：", style = MaterialTheme.typography.bodyMedium, color = categoryColor, modifier = Modifier.padding(end = 6.dp, top = 5.dp))
                            FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                for (value in tagValues) FilterTagChip(label = value, tagColor = categoryColor)
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 筛选标签胶囊（小圆角标签显示）
 * 
 * @param label 标签文本
 * @param tagColor 标签颜色
 */
@Composable
fun FilterTagChip(label: String, tagColor: Color) {
    Box(Modifier.padding(horizontal = 6.dp, vertical = 3.dp).background(color = tagColor.copy(alpha = 0.2f), shape = MaterialTheme.shapes.small).padding(horizontal = 12.dp, vertical = 6.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = tagColor.copy(alpha = 0.8f))
    }
}

/**
 * 网格模式文件卡片
 * 
 * 功能说明：
 * 1. 显示 PDF 缩略图（根据 thumbSize 调整尺寸）
 * 2. 显示文件名
 * 3. 多选模式下右上角显示 Checkbox
 * 4. 选中时显示边框高亮
 * 
 * @param pdf PDF 文件对象
 * @param isSelected 是否已选中（多选模式）
 * @param thumbSize 缩略图大小（LARGE/MEDIUM/SMALL）
 * @param onClick 点击回调
 */
@Composable
fun GridItem(
    pdf: PdfFile,
    isSelected: Boolean = false,
    thumbSize: com.example.pdfmanager.util.ThumbSize = com.example.pdfmanager.util.ThumbSize.MEDIUM,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().then(if (isSelected) Modifier.border(1.dp, MaterialTheme.colorScheme.primary, MaterialTheme.shapes.small) else Modifier),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Box {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                // 缩略图：根据 thumbSize 动态调整宽高
                PdfThumbnail(
                    pdfFile = pdf,
                    modifier = Modifier.fillMaxWidth().aspectRatio(0.75f),
                    thumbnailWidthDp = when (thumbSize) { ThumbSize.LARGE -> 180.dp; ThumbSize.MEDIUM -> 120.dp; ThumbSize.SMALL -> 90.dp },
                    thumbnailHeightDp = when (thumbSize) { ThumbSize.LARGE -> 255.dp; ThumbSize.MEDIUM -> 170.dp; ThumbSize.SMALL -> 127.dp }
                )
                Text(pdf.name, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(4.dp))
            }
            // 选中状态：右上角 Checkbox
            if (isSelected) {
                Checkbox(checked = true, onCheckedChange = null, modifier = Modifier.align(Alignment.TopEnd).padding(4.dp))
            }
        }
    }
}

/**
 * 列表模式文件行
 * 
 * 功能说明：
 * 1. 左侧显示缩略图（48dp × 64dp）
 * 2. 右侧显示文件名和标签
 * 3. 多选模式下右上角显示 Checkbox
 * 4. 选中时显示边框高亮
 * 
 * @param pdf PDF 文件对象
 * @param isSelected 是否已选中
 * @param onClick 点击回调
 */
@Composable
fun PdfListItem(pdf: PdfFile, isSelected: Boolean = false, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().then(if (isSelected) Modifier.border(1.dp, MaterialTheme.colorScheme.primary, MaterialTheme.shapes.small) else Modifier),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Box {
            Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                PdfThumbnail(pdfFile = pdf, modifier = Modifier.size(width = 48.dp, height = 64.dp), thumbnailWidthDp = 48.dp, thumbnailHeightDp = 64.dp)
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(pdf.name, style = MaterialTheme.typography.bodyLarge)
                    if (pdf.tags.isNotEmpty()) {
                        Text(pdf.tags.joinToString(" ") { it.value }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (isSelected) {
                Checkbox(checked = true, onCheckedChange = null, modifier = Modifier.align(Alignment.TopEnd).padding(4.dp))
            }
        }
    }
}
