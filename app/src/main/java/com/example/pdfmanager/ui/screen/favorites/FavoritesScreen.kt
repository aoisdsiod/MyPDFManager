package com.example.pdfmanager.ui.screen.favorites

import android.util.Log
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.activity.compose.BackHandler
import androidx.navigation.NavController
import com.example.pdfmanager.data.model.FavoriteFolder
import com.example.pdfmanager.data.model.OrganizeFolder
import com.example.pdfmanager.data.model.SavedFilter
import com.example.pdfmanager.data.repository.AppContainer
import com.example.pdfmanager.ui.screen.favorites.OrganizeFolderItem
import com.example.pdfmanager.ui.screen.favorites.FavoriteFolderItem
import com.example.pdfmanager.ui.viewmodel.FavoritesViewModel
import com.google.gson.Gson
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch


/**
 * 收藏页面（主屏幕 Composable）
 *
 * 用途：显示收藏模块的根界面，包含：
 *   - 自建文件夹（OrganizeFolder）与虚拟文件夹（FavoriteFolder）的混合列表
 *   - 新建文件夹、重命名、删除、移动、查看详情等操作入口
 *   - 多选模式切换与文件批量选择
 *
 * 调用位置：
 *   - 由导航框架（NavHost）在导航到 "favorites" 路由时调用
 *   - 参考：NavGraph 中 composable("favorites") { FavoritesScreen(...) }
 *
 * 使用场景：
 *   - 用户从底部导航栏点击"收藏"标签进入
 *   - 用户通过 navigationIcon 返回按钮从子文件夹退回
 *
 * @param navController   导航控制器，用于页面跳转（如进入虚拟文件夹内容页）
 * @param viewModel       收藏页面的 ViewModel，管理所有状态与业务逻辑
 * @param modifier        可选的外部 Modifier，用于父布局定制
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FavoritesScreen(
    navController: NavController,
    viewModel: FavoritesViewModel = viewModel { FavoritesViewModel() },
    modifier: Modifier = Modifier
) {
    // ── 从 ViewModel 收集 UI 状态流 ──────────────────────────────
    /** 当前列表项集合（OrganizeFolder | FavoriteFolder 混合列表） */
    val items by viewModel.items.collectAsStateWithLifecycle()
    /** 当前所在的自建文件夹，null 表示根目录 */
    val currentFolder by viewModel.currentOrganizeFolder.collectAsStateWithLifecycle()
    /** 是否显示「新建文件夹」对话框 */
    val showCreateDialog by viewModel.showCreateDialog.collectAsStateWithLifecycle()
    /** 新建文件夹输入框中的名称文字 */
    val newFolderName by viewModel.newFolderName.collectAsStateWithLifecycle()
    /** 长按菜单的目标对象（OrganizeFolder 或 FavoriteFolder） */
    val menuTarget by viewModel.menuTarget.collectAsStateWithLifecycle()
    /** 是否显示「重命名」对话框 */
    val showRenameDialog by viewModel.showRenameDialog.collectAsStateWithLifecycle()
    /** 重命名输入框中的名称文字 */
    val renameName by viewModel.renameName.collectAsStateWithLifecycle()

    // ── 从全局 AppContainer 获取多选模式状态 ────────────────────
    /** 是否处于多选模式（由 PreferencesManager 持久化） */
    val isMultiSelectMode by AppContainer.preferencesManager.getMultiSelectModeFlow()
        .collectAsStateWithLifecycle(initialValue = false)
    /** 当前已选中的文件 ID 集合 */
    val selectedFileIds by AppContainer.selectedFileIds.collectAsStateWithLifecycle()

    // ── 滚动指示条 ──
    val listState = rememberLazyListState()
    val scrollProgress by remember {
        derivedStateOf {
            val total = listState.layoutInfo.totalItemsCount
            if (total <= 1) return@derivedStateOf 0f
            val first = listState.firstVisibleItemIndex.toFloat()
            (first / (total - 1)).coerceIn(0f, 1f)
        }
    }
    var showBar by remember { mutableStateOf(false) }
    LaunchedEffect(scrollProgress) {
        showBar = true
        delay(1000)
        showBar = false
    }
    val indicatorAlpha by animateFloatAsState(
        targetValue = if (showBar) 0.5f else 0f,
        animationSpec = tween(300)
    )

    // ── 本地 UI 状态 ─────────────────────────────────────────────
    /** 是否显示「确认删除」对话框 */
    var showDeleteConfirm by remember { mutableStateOf(false) }
    /** 是否显示「退出多选模式确认」对话框 */
    var showExitMultiSelectDialog by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    // 移动到对话框状态
    /** 是否显示「移动到」对话框 */
    var showMoveDialog by remember { mutableStateOf(false) }
    /** 待移动的目标虚拟文件夹 */
    var moveTarget by remember { mutableStateOf<FavoriteFolder?>(null) }
    /** 可选的移动目标自建文件夹列表 */
    var organizeFolders by remember { mutableStateOf<List<OrganizeFolder>>(emptyList()) }

    // 查看详情对话框状态
    /** 是否显示「筛选条件详情」对话框 */
    var showDetailDialog by remember { mutableStateOf(false) }
    /** 待查看详情的虚拟文件夹 */
    var detailFolder by remember { mutableStateOf<FavoriteFolder?>(null) }

    // ── 拦截系统返回键 ────────────────────────────────────────────
    // 优先级：多选模式 > 在文件夹内
    BackHandler(enabled = isMultiSelectMode || currentFolder != null) {
        if (isMultiSelectMode) {
            // 多选模式下：弹出退出确认弹窗
            showExitMultiSelectDialog = true
        } else {
            // 非多选模式但在文件夹内：返回上级
            viewModel.navigateUp()
        }
    }

    Scaffold(
        topBar = {
            /**
             * 顶部应用栏
             * 根据当前状态动态显示标题和操作按钮：
             * - 多选模式 + 在文件夹内：显示文件夹名 + 已选个数 + 退出按钮
             * - 多选模式 + 根目录：显示"已选 N 个" + 退出按钮
             * - 非多选模式：显示"收藏" 或 当前文件夹名 + 新建文件夹按钮
             */
            TopAppBar(
                title = {
                    when {
                        isMultiSelectMode && currentFolder != null -> {
                            Text(currentFolder!!.name)
                        }
                        isMultiSelectMode -> {
                            Text("已选 ${selectedFileIds.size} 个")
                        }
                        else -> {
                            Column {
                                Text(currentFolder?.name ?: "收藏")
                                Text("共 ${items.size} 项",
                                    style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                },
                navigationIcon = {
                    when {
                        // 多选模式 + 在文件夹内：左侧显示返回箭头
                        isMultiSelectMode && currentFolder != null -> {
                            IconButton(onClick = { viewModel.navigateUp() }) {
                                Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                            }
                        }
                        // 非多选 + 在文件夹内：返回箭头
                        !isMultiSelectMode && currentFolder != null -> {
                            IconButton(onClick = { viewModel.navigateUp() }) {
                                Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                            }
                        }
                        // 其他情况：左侧无内容
                        else -> {}
                    }
                },
                actions = {
                    if (isMultiSelectMode) {
                        // 多选模式下：右上角显示已选个数 + 退出按钮
                        if (currentFolder != null) {
                            Text(
                                text = "已选 ${selectedFileIds.size} 个",
                                modifier = Modifier.padding(end = 8.dp),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                        TextButton(onClick = { showExitMultiSelectDialog = true }) {
                            Text("退出多选模式")
                        }
                    } else {
                        // 非多选：右上角显示新建文件夹按钮
                        IconButton(onClick = { viewModel.showCreateFolderDialog() }) {
                            Icon(Icons.Default.Add, contentDescription = "新建文件夹")
                        }
                    }
                }
            )
        },
        modifier = modifier
    ) { paddingValues ->
        /**
         * 主列表——使用 LazyColumn 展示所有列表项
         * 列表项有两种类型：
         *   1. OrganizeFolder（自建文件夹）→ 使用 OrganizeFolderItem 渲染
         *   2. FavoriteFolder（虚拟/筛选文件夹）→ 使用 FavoriteFolderItem 渲染
         */
        Box(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize()
            ) {
                items(items, key = { item ->
                    when (item) {
                        is OrganizeFolder -> "organize_${item.id}"
                        is FavoriteFolder -> "favorite_${item.id}"
                        else -> item.hashCode().toString()
                    }
                }) { item ->
                    when (item) {
                        is OrganizeFolder -> {
                            val idx = items.indexOf(item)
                            val totalCount = items.size
                            OrganizeFolderItem(
                                folder = item,
                                isMultiSelectMode = isMultiSelectMode,
                                onClick = { viewModel.openOrganizeFolder(item) },
                                onLongClick = { if (!isMultiSelectMode) viewModel.showMenu(item) },
                                onMoveUp = { viewModel.moveItemUp(item) },
                                onMoveDown = { viewModel.moveItemDown(item) },
                                index = idx,
                                totalCount = totalCount
                            )
                        }

                        is FavoriteFolder -> {
                            val idx = items.indexOf(item)
                            val totalCount = items.size
                            // 计算文件夹选中状态（需要在协程中计算）
                            var isSelected by remember { mutableStateOf(false) }
                            LaunchedEffect(selectedFileIds) {
                                isSelected = viewModel.isFolderSelected(item)
                            }
                            FavoriteFolderItem(
                                folder = item,
                                isMultiSelectMode = isMultiSelectMode,
                                isSelected = isSelected,
                                onClick = {
                                    // 多选模式和正常模式：点击卡片都进入文件夹
                                    navController.navigate("favorites/content/${item.id}")
                                },
                                onLongClick = { viewModel.showMenu(item) },
                                onToggleSelection = {
                                    Log.d(
                                        "FavoritesScreen",
                                        "▶ onToggleSelection 调用! folder=${item.name}, folder.id=${item.id}"
                                    )
                                    viewModel.toggleFolderSelection(item)
                                },
                                onMoveUp = { viewModel.moveItemUp(item) },
                                onMoveDown = { viewModel.moveItemDown(item) },
                                index = idx,
                                totalCount = totalCount
                            )
                        }
                    }
                }
            }


            // 滚动指示条（滚动时显现，停止后渐隐）
            if (indicatorAlpha > 0.01f) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .fillMaxHeight()
                        .width(10.dp)
                        .padding(vertical = 4.dp)
                ) {
                    val thumbPx = with(LocalDensity.current) { 80.dp.toPx() }
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val totalH = size.height
                        val maxY = totalH - thumbPx
                        val y = maxY * scrollProgress
                        drawRoundRect(
                            color = Color.Gray.copy(alpha = indicatorAlpha),
                            topLeft = Offset(0f, y),
                            size = Size(size.width, thumbPx),
                            cornerRadius = CornerRadius(2f, 2f)
                        )
                    }
                }
            }
        }
    }

    /**
     * 新建文件夹对话框
     * 用户输入文件夹名称后点击"创建"提交。
     * 调用 ViewModel.confirmCreateFolder() 执行实际创建。
     */
    if (showCreateDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissCreateDialog() },
            title = { Text("新建文件夹") },
            text = {
                OutlinedTextField(
                    value = newFolderName,
                    onValueChange = { viewModel.onNewFolderNameChange(it) },
                    label = { Text("文件夹名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(onClick = { viewModel.confirmCreateFolder() }) {
                    Text("创建")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissCreateDialog() }) {
                    Text("取消")
                }
            }
        )
    }

    /**
     * 长按菜单对话框
     * 对自建文件夹/虚拟文件夹提供：重命名、移动到（仅虚拟文件夹）、查看详情（仅虚拟文件夹）、删除。
     */
    if (menuTarget != null) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissMenu() },
            title = { Text("操作") },
            text = {
                Column {
                    TextButton(
                        onClick = {
                            viewModel.showRenameDialog(menuTarget!!)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("重命名")
                    }
                    // 移动到（仅虚拟文件夹可用）
                    if (menuTarget is FavoriteFolder) {
                        TextButton(
                            onClick = {
                                viewModel.dismissMenu()
                                moveTarget = menuTarget as FavoriteFolder
                                // 加载所有自建文件夹
                                coroutineScope.launch {
                                    organizeFolders = AppContainer.favoritesRepository.getAllOrganizeFolders()
                                    showMoveDialog = true
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("移动到")
                        }
                        
                        // 查看详情（仅虚拟文件夹可用）
                        TextButton(
                            onClick = {
                                viewModel.dismissMenu()
                                detailFolder = menuTarget as FavoriteFolder
                                showDetailDialog = true
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("查看详情")
                        }
                    }
                    TextButton(
                        onClick = { showDeleteConfirm = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("删除", color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { viewModel.dismissMenu() }) {
                    Text("取消")
                }
            }
        )
    }

    /**
     * 重命名对话框
     * 用户输入新名称后点击"确定"提交。
     * 调用 ViewModel.confirmRename() 执行实际重命名。
     */
    if (showRenameDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissRenameDialog() },
            title = { Text("重命名") },
            text = {
                OutlinedTextField(
                    value = renameName,
                    onValueChange = { viewModel.onRenameNameChange(it) },
                    label = { Text("新名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(onClick = { viewModel.confirmRename() }) {
                    Text("确定")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissRenameDialog() }) {
                    Text("取消")
                }
            }
        )
    }

    /**
     * 删除确认对话框
     * 提示用户删除不可恢复，点击"确定"后调用 ViewModel.confirmDelete() 执行删除。
     */
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("确定删除？") },
            text = { Text("删除后不可恢复。自建文件夹内的虚拟文件夹将移到根目录。") },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteConfirm = false
                        viewModel.confirmDelete(menuTarget!!)
                    }
                ) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("取消")
                }
            }
        )
    }

    /**
     * 退出多选模式确认对话框
     * 退出后清除所有已选文件 ID 并关闭多选模式开关。
     */
    if (showExitMultiSelectDialog) {
        AlertDialog(
            onDismissRequest = { showExitMultiSelectDialog = false },
            title = { Text("确定退出多选模式？") },
            text = { Text("已选文件将取消选中。") },
            confirmButton = {
                Button(
                    onClick = {
                        showExitMultiSelectDialog = false
                        coroutineScope.launch {
                            AppContainer.preferencesManager.setMultiSelectMode(false)
                            AppContainer.clearSelectedFileIds()
                        }
                    }
                ) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showExitMultiSelectDialog = false }) {
                    Text("取消")
                }
            }
        )
    }

    /**
     * 移动到对话框
     * 列出所有自建文件夹供用户选择，将虚拟文件夹移动到目标自建文件夹或根目录。
     * 调用 ViewModel.moveFavoriteFolderToOrganize() 执行移动。
     */
    if (showMoveDialog && moveTarget != null) {
        AlertDialog(
            onDismissRequest = { showMoveDialog = false },
            title = { Text("移动到") },
            text = {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 300.dp)
                ) {
                    // 移动到根目录选项
                    item {
                        TextButton(
                            onClick = {
                                showMoveDialog = false
                                viewModel.moveFavoriteFolderToOrganize(moveTarget!!.id, null)
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("移动到根目录")
                        }
                    }

                    // 分隔线
                    item {
                        HorizontalDivider()
                    }

                    // 自建文件夹列表
                    items(organizeFolders) { folder ->
                        TextButton(
                            onClick = {
                                showMoveDialog = false
                                viewModel.moveFavoriteFolderToOrganize(moveTarget!!.id, folder.id)
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(folder.name)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showMoveDialog = false }) {
                    Text("取消")
                }
            }
        )
    }

    /**
     * 查看详情对话框
     * 展示虚拟文件夹（FavoriteFolder）的筛选条件详情，包括：
     *   1. 筛选逻辑（AND / OR）
     *   2. 是否包含无标签文件
     *   3. 选中的标签列表（从 selectedTagKeys 解析为可读名称）
     * 需要从 TagRepository 加载标签类别数据以将 categoryId 映射为类别名。
     */
    if (showDetailDialog && detailFolder != null) {
        // 加载标签类别数据（用于解析 selectedTagKeys 中的 categoryId）
        var tagCategories by remember { mutableStateOf<List<com.example.pdfmanager.data.model.TagCategory>>(emptyList()) }
        LaunchedEffect(detailFolder) {
            tagCategories = AppContainer.tagRepository.getCategories()
        }

        AlertDialog(
            onDismissRequest = { showDetailDialog = false },
            title = { Text("筛选条件详情") },
            text = {
                Column(
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                ) {
                    // 解析 SavedFilter（空值则使用默认空对象）
                    val savedFilter = if (detailFolder!!.savedFilterJson.isNullOrBlank()) {
                        SavedFilter()
                    } else {
                        try {
                            Gson().fromJson(detailFolder!!.savedFilterJson, SavedFilter::class.java)
                        } catch (e: Exception) {
                            SavedFilter()
                        }
                    }

                    // 1. 显示筛选逻辑
                    Text(
                        text = "筛选逻辑：${if (savedFilter.filterLogic == com.example.pdfmanager.data.model.FilterLogic.AND) "且（AND）" else "或（OR）"}",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )

                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                    // 2. 显示是否包含无标签文件
                    if (savedFilter.includeNoTag) {
                        Text(
                            text = "• 包含无标签文件",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(vertical = 2.dp)
                        )
                    }

                    // 3. 显示选中的标签（解析为可读名称）
                    if (savedFilter.selectedTagKeys.isNotEmpty()) {
                        Text(
                            text = "选中的标签：",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                        )

                        // 显示每个选中的标签（将 categoryId 映射为类别名称）
                        savedFilter.selectedTagKeys.forEach { key ->
                            val parts = key.split(":", limit = 2)
                            if (parts.size == 2) {
                                val catId = parts[0]
                                val tagVal = parts[1]
                                val categoryName = tagCategories.find { it.id == catId }?.name ?: catId
                                Text(
                                    text = "• $categoryName : $tagVal",
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(start = 8.dp, top = 2.dp, bottom = 2.dp)
                                )
                            } else {
                                Text(
                                    text = "• $key",
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(start = 8.dp, top = 2.dp, bottom = 2.dp)
                                )
                            }
                        }
                    } else {
                        Text(
                            text = "未选中任何标签",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDetailDialog = false }) {
                    Text("关闭")
                }
            }
        )
    }
}

