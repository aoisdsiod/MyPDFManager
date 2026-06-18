package com.example.pdfmanager.ui.screen.tagmanagement

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.pdfmanager.data.model.TagCategory
import com.example.pdfmanager.data.model.TagValue
import com.example.pdfmanager.ui.component.ColorPicker
import kotlinx.coroutines.flow.first
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.graphicsLayer
import android.util.Log


/**
 * 标签管理页面 Composable 函数
 *
 * 功能说明：
 * 本页面提供标签类别和标签值的完整 CRUD（增删改查）管理功能。
 * 标签系统采用"类别 + 值"的二级结构：每个类别包含多个标签值，
 * 类别有名称、颜色和排序顺序属性。
 *
 * 具体功能：
 * 1. 显示所有标签类别卡片，每个卡片内显示该类别的标签值列表
 * 2. 新建类别 — 填写名称、选择颜色
 * 3. 编辑类别名称和颜色
 * 4. 删除类别（同时删除所有关联的标签值和 PDF 标签关系）
 * 5. 上移/下移类别顺序（与收藏页面排序一致）
 * 6. 向类别中添加标签值
 * 7. 重命名标签值
 * 8. 删除标签值
 * 9. 系统返回键处理（BackHandler）
 *
 * 调用位置：
 * - 由 SettingsScreen 的"管理标签"按钮通过 onNavigateToTagManagement 回调导航至此
 * - 路由栈中的路由名由外部导航图定义
 *
 * @param onBack 返回上一页的回调函数，由导航系统传入
 * @param modifier 可选的 Modifier，用于外部自定义布局
 * @param viewModel 标签管理 ViewModel，通过依赖注入自动创建
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TagManagementScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: TagManagementViewModel = viewModel()
) {
    // ── ViewModel 状态收集 ──
    val categories by viewModel.categories.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()

    // ── 对话框控制状态 ──
    var showAddCategoryDialog by remember { mutableStateOf(false) }
    var showEditCategoryDialog by remember { mutableStateOf(false) }
    var showDeleteCategoryDialog by remember { mutableStateOf(false) }
    var showColorPicker by remember { mutableStateOf(false) }
    var showAddTagValueDialog by remember { mutableStateOf(false) }
    var showDeleteTagValueDialog by remember { mutableStateOf(false) }
    var showRenameTagValueDialog by remember { mutableStateOf(false) }
    var selectedCategory by remember { mutableStateOf<TagCategory?>(null) }
    var selectedTagValue by remember { mutableStateOf<String?>(null) }
    var tagValueToRename by remember { mutableStateOf<String?>(null) }

    // 加载标签值（按类别分组）
    var tagsByCategory by remember { mutableStateOf<Map<String, List<TagValue>>>(emptyMap()) }

    /**
     * 每次进入该页面时重新加载标签类别数据
     * 确保在详情页等外部页面添加的标签能够实时显示
     */
    LaunchedEffect(Unit) {
        viewModel.loadCategories()
    }

    /**
     * 当 categories 列表变化时，重新按类别分组加载标签值
     * 调用 ViewModel.getTagsByCategory() 获取分组结果
     */
    LaunchedEffect(categories) {
        tagsByCategory = viewModel.getTagsByCategory()
    }

    // 添加 BackHandler 处理系统返回手势（实体键或手势导航）
    BackHandler {
        onBack()
    }

    // ── 页面骨架 ──
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("管理标签") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Text("←")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
        ) {
            // ── 懒加载列表：显示所有类别卡片 + 新建类别卡片 ──
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                    /**
                     * 遍历所有类别，为每个类别渲染 CategoryCard
                     * 传递类别数据、标签值列表、索引位置及各种操作回调
                     */
                    items(categories) { category ->
                        val idx = categories.indexOf(category)
                        val total = categories.size
                        CategoryCard(
                            category = category,
                            tags = tagsByCategory[category.id] ?: emptyList(),
                            index = idx,
                            totalCount = total,
                            onEditColor = {
                                selectedCategory = category
                                showColorPicker = true
                            },
                            onEditName = {
                                selectedCategory = category
                                showEditCategoryDialog = true
                            },
                            onDelete = {
                                selectedCategory = category
                                showDeleteCategoryDialog = true
                            },
                            onMoveUp = {
                                viewModel.moveCategoryUp(category.id)
                            },
                            onMoveDown = {
                                viewModel.moveCategoryDown(category.id)
                            },
                            onAddTag = {
                                selectedCategory = category
                                showAddTagValueDialog = true
                            },
                            onDeleteTag = { tagValue ->
                                selectedCategory = category
                                selectedTagValue = tagValue
                                showDeleteTagValueDialog = true
                            },
                            onRenameTag = { tagValue ->
                                selectedCategory = category
                                tagValueToRename = tagValue
                                showRenameTagValueDialog = true
                            }
                        )
                    }

                    // 新建类别卡片
                    item {
                        AddCategoryCard(
                            onClick = { showAddCategoryDialog = true }
                        )
                    }
                }
            }
        }

        // ── 添加类别对话框 ──
        /**
         * 弹出对话框让用户输入新类别名称并选择颜色
         * 默认颜色为红色（0xFFF44336）
         * 点击确认后调用 ViewModel.addCategory()
         */
        if (showAddCategoryDialog) {
        AddEditCategoryDialog(
            title = "新建类别",
            initialName = "",
            initialColor = 0xFFF44336.toInt(), // 默认红色
            onConfirm = { name, color ->
                viewModel.addCategory(name, color)
                showAddCategoryDialog = false
            },
            onDismiss = { showAddCategoryDialog = false }
        )
    }

    // ── 编辑类别名称对话框 ──
    /**
     * 弹出对话框让用户修改类别名称和颜色
     * 初始值使用 selectedCategory 的当前 name 和 color
     * 确认后调用 ViewModel.renameCategory() 和 ViewModel.changeColor()
     */
    if (showEditCategoryDialog) {
        AddEditCategoryDialog(
            title = "修改类别名称",
            initialName = selectedCategory?.name ?: "",
            initialColor = selectedCategory?.color ?: 0xFFF44336.toInt(),
            onConfirm = { name, color ->
                Log.d("TagManagement", "onConfirm: name=$name, color=$color, selectedCategory=${selectedCategory?.name}")
                selectedCategory?.let {
                    Log.d("TagManagement", "调用 renameCategory(${it.id}, $name)")
                    viewModel.renameCategory(it.id, name)
                    viewModel.changeColor(it.id, color)
                } ?: run {
                    Log.e("TagManagement", "selectedCategory 为 null！")
                }
                showEditCategoryDialog = false
            },
            onDismiss = { showEditCategoryDialog = false }
        )
    }

    // ── 删除类别确认对话框 ──
    /**
     * 确认是否删除指定类别及其所有标签值
     * 确认后调用 ViewModel.deleteCategory()
     */
    if (showDeleteCategoryDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteCategoryDialog = false },
            title = { Text("删除确认") },
            text = { Text("确定要删除类别'${selectedCategory?.name}'及其所有标签吗？") },
            confirmButton = {
                Button(
                    onClick = {
                        selectedCategory?.let {
                            viewModel.deleteCategory(it.id)
                        }
                        showDeleteCategoryDialog = false
                    }
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(
                    onClick = { showDeleteCategoryDialog = false }
                ) { Text("取消") }
            }
        )
    }

    // ── 颜色选择器对话框 ──
    /**
     * 弹出 ColorPicker 组件让用户为类别选择新颜色
     * 选中颜色后调用 ViewModel.changeColor() 持久化
     */
    if (showColorPicker) {
        AlertDialog(
            onDismissRequest = { showColorPicker = false },
            title = { Text("选择颜色") },
            text = {
                ColorPicker(
                    selectedColor = selectedCategory?.color ?: 0xFFF44336.toInt(),
                    onColorSelected = { color ->
                        selectedCategory?.let {
                            viewModel.changeColor(it.id, color)
                        }
                        showColorPicker = false
                    }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { showColorPicker = false }
                ) { Text("取消") }
            }
        )
    }

    // ── 添加标签值对话框 ──
    /**
     * 弹出对话框让用户输入新的标签值文本
     * 输入不能为空，确认后调用 ViewModel.addTagValue()
     * 标签值将添加到 selectedCategory 指定的类别中
     */
    if (showAddTagValueDialog) {
        var tagValue by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { showAddTagValueDialog = false },
            title = { Text("添加标签值到「${selectedCategory?.name}」") },
            text = {
                Column {
                    OutlinedTextField(
                        value = tagValue,
                        onValueChange = { tagValue = it },
                        label = { Text("标签值") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (tagValue.isNotBlank()) {
                            selectedCategory?.let {
                                viewModel.addTagValue(it.id, tagValue)
                            }
                            showAddTagValueDialog = false
                        }
                    },
                    enabled = tagValue.isNotBlank()
                ) { Text("确认") }
            },
            dismissButton = {
                TextButton(onClick = { showAddTagValueDialog = false }) { Text("取消") }
            }
        )
    }

    // ── 删除标签值确认对话框 ──
    /**
     * 确认是否从指定类别中删除选中的标签值
     * 确认后调用 ViewModel.deleteTagValue()
     */
    if (showDeleteTagValueDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteTagValueDialog = false },
            title = { Text("删除确认") },
            text = { Text("确定要删除标签值「$selectedTagValue」吗？") },
            confirmButton = {
                Button(
                    onClick = {
                        selectedCategory?.let { category ->
                            selectedTagValue?.let { tagValue ->
                                viewModel.deleteTagValue(category.id, tagValue)
                            }
                        }
                        showDeleteTagValueDialog = false
                    }
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteTagValueDialog = false }) { Text("取消") }
            }
        )
    }

    // ── 错误消息显示 ──
    /**
     * 当 ViewModel 中的 errorMessage 不为 null 时，弹出错误对话框
     * 显示具体的错误描述，点击确定后调用 ViewModel.clearErrorMessage() 清除
     */
    errorMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { viewModel.clearErrorMessage() },
            title = { Text("错误") },
            text = { Text(message) },
            confirmButton = {
                Button(
                    onClick = { viewModel.clearErrorMessage() }
                ) { Text("确定") }
            }
        )
    }

    // ── 修改标签值对话框 ──
    /**
     * 弹出对话框让用户输入新的标签值以替换旧值
     * 预填当前值作为初始文本，确认新值不能为空且不能与旧值相同
     * 确认后调用 ViewModel.renameTagValue()
     */
    if (showRenameTagValueDialog) {
        var newValue by remember { mutableStateOf(tagValueToRename ?: "") }

        AlertDialog(
            onDismissRequest = { showRenameTagValueDialog = false },
            title = { Text("修改标签值") },
            text = {
                Column {
                    Text("将「$tagValueToRename」修改为：")
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = newValue,
                        onValueChange = { newValue = it },
                        label = { Text("新标签值") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newValue.isNotBlank() && newValue != tagValueToRename) {
                            selectedCategory?.let {
                                viewModel.renameTagValue(it.id, tagValueToRename!!, newValue)
                            }
                            showRenameTagValueDialog = false
                        }
                    },
                    enabled = newValue.isNotBlank() && newValue != tagValueToRename
                ) { Text("确认") }
            },
            dismissButton = {
                TextButton(onClick = { showRenameTagValueDialog = false }) { Text("取消") }
            }
        )
    }
}

/**
 * 标签类别卡片 Composable 函数
 *
 * 功能说明：
 * 每个类别对应一张卡片，包含三个区域：
 * 1. 顶部行：左侧颜色指示块 + 类别名称，右侧编辑/删除操作按钮
 * 2. 中部区域：该类别的标签值列表，每个标签呈胶囊状显示，右侧有修改/删除按钮
 * 3. 底部行：左侧"添加标签"按钮，右侧上移/下移排序按钮
 *
 * 动画效果：
 * - index 变化时卡片有闪白动画（透明度从 1→0.5→1）
 * - 上移/下移按钮按下时对应按钮有缩放动画（1→0.5→1）
 *
 * 调用位置：
 * - 由 TagManagementScreen 的 LazyColumn 中 items() 函数对每个 category 调用
 *
 * @param category 要显示的标签类别数据对象（含 id、name、color、sortOrder 等）
 * @param tags 该类别下的全部标签值列表
 * @param index 当前类别在列表中的索引位置，用于控制排序按钮的显示与动画
 * @param totalCount 类别总数量，用于判断是否为最后一个（隐藏下移按钮）
 * @param onEditColor 编辑颜色回调，触发颜色选择器对话框
 * @param onEditName 编辑名称回调，触发编辑类别对话框
 * @param onDelete 删除类别回调，触发删除确认对话框
 * @param onMoveUp 上移类别回调，调用 ViewModel.moveCategoryUp()
 * @param onMoveDown 下移类别回调，调用 ViewModel.moveCategoryDown()
 * @param onAddTag 添加标签值回调，触发添加标签值对话框
 * @param onDeleteTag 删除指定标签值回调，触发删除标签值确认对话框
 * @param onRenameTag 重命名指定标签值回调，触发修改标签值对话框
 * @param modifier 可选的 Modifier
 */
@Composable
fun CategoryCard(
    category: TagCategory,
    tags: List<TagValue>,
    index: Int = 0,
    totalCount: Int = 0,
    onEditColor: () -> Unit,
    onEditName: () -> Unit,
    onDelete: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onAddTag: () -> Unit,
    onDeleteTag: (String) -> Unit,
    onRenameTag: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    // ── 按钮动画状态（Animatable 实现缩放动画） ──
    val moveUpScale = remember { Animatable(1f) }
    val moveDownScale = remember { Animatable(1f) }
    // 按钮动画触发器：值递增触发 LaunchedEffect 重新执行
    val moveUpTrigger = remember { mutableStateOf(0) }
    val moveDownTrigger = remember { mutableStateOf(0) }
    // 卡片移动动画状态（index 变化时闪白效果）
    val cardAlpha = remember { Animatable(1f) }

    /**
     * 监听 index 变化 → 卡片闪白效果
     * 透明度动画：1 → 0.5（250ms）→ 1（500ms）
     */
    LaunchedEffect(index) {
        cardAlpha.snapTo(1f)
        cardAlpha.animateTo(0.5f, animationSpec = tween(250))
        cardAlpha.animateTo(1f, animationSpec = tween(500))
    }

    /**
     * 监听 moveUpTrigger 变化 → 上移按钮缩放动画
     * 缩放动画：1 → 0.5（250ms）→ 1.0（350ms）
     */
    LaunchedEffect(moveUpTrigger.value) {
        if (moveUpTrigger.value > 0) {
            moveUpScale.snapTo(1f)
            moveUpScale.animateTo(0.5f, animationSpec = tween(250))
            moveUpScale.animateTo(1.0f, animationSpec = tween(350))
        }
    }

    /**
     * 监听 moveDownTrigger 变化 → 下移按钮缩放动画
     * 缩放动画：1 → 0.5（250ms）→ 1.0（350ms）
     */
    LaunchedEffect(moveDownTrigger.value) {
        if (moveDownTrigger.value > 0) {
            moveDownScale.snapTo(1f)
            moveDownScale.animateTo(0.5f, animationSpec = tween(250))
            moveDownScale.animateTo(1.0f, animationSpec = tween(350))
        }
    }

    Card(
        modifier = modifier.fillMaxWidth().graphicsLayer {
            alpha = cardAlpha.value
        },
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            // ── 顶部行：类别名称 + 右侧操作按钮 ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 左侧：颜色指示器 + 类别名称
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Surface(
                        modifier = Modifier.size(16.dp),
                        color = Color(category.color),
                        shape = MaterialTheme.shapes.small
                    ) {}
                    Spacer(modifier = Modifier.size(8.dp))
                    Text(
                        text = category.name,
                        style = MaterialTheme.typography.titleMedium
                    )
                }

                // 右侧：操作按钮（编辑、删除）
                Row(
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 编辑名称按钮
                    IconButton(
                        onClick = onEditName,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Text("✏️", fontSize = 12.sp)
                    }

                    // 删除按钮
                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Text("🗑️", fontSize = 12.sp)
                    }
                }
            }

            // ── 标签值列表 ──
            /**
             * 遍历 category.tags 列表，每个标签值渲染为一行
             * 布局：左侧颜色胶囊（半透明背景 + 实色边框 + 实色文字） + 右侧修改/删除按钮
             * 颜色使用该类别的 category.color
             */
            if (tags.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    tags.forEach { tagValueObj ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            // 左侧：标签胶囊
                            Surface(
                                color = Color(category.color).copy(alpha = 0.2f),
                                shape = MaterialTheme.shapes.small,
                                border = BorderStroke(1.dp, Color(category.color)),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(
                                    text = tagValueObj.value,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                                    color = Color(category.color)
                                )
                            }

                            Spacer(modifier = Modifier.size(4.dp))

                            // 右侧：修改按钮 + 删除按钮
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                IconButton(
                                    onClick = { onRenameTag(tagValueObj.value) },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Text("✏️", fontSize = 10.sp)
                                }

                                IconButton(
                                    onClick = { onDeleteTag(tagValueObj.value) },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Text("✕", fontSize = 10.sp)
                                }
                            }
                        }
                    }
                }
            }

            // ── 底部：添加标签按钮 + 排序按钮 ──
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 左侧：添加标签按钮（宽间距）
                Button(
                    onClick = onAddTag,
                    contentPadding = PaddingValues(horizontal = 64.dp, vertical = 8.dp)

                ) {
                    Text("+ 添加标签", fontSize = 14.sp)
                }

                // 弹性空间：将排序按钮推到右侧
                Spacer(modifier = Modifier.weight(1f))

                // 右侧：排序按钮（上移、下移）靠右放置
                Row(
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 上移按钮：不在首位时显示
                    if (index > 0) {
                        IconButton(
                            onClick = {
                                moveUpTrigger.value += 1
                                onMoveUp()
                            },
                            modifier = Modifier.size(28.dp).graphicsLayer {
                                scaleX = moveUpScale.value
                                scaleY = moveUpScale.value
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Default.KeyboardArrowUp,
                                contentDescription = "上移",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        Spacer(modifier = Modifier.size(28.dp))
                    }

                    // 下移按钮：不在末尾时显示
                    if (index < totalCount - 1) {
                        IconButton(
                            onClick = {
                                moveDownTrigger.value += 1
                                onMoveDown()
                            },
                            modifier = Modifier.size(28.dp).graphicsLayer {
                                scaleX = moveDownScale.value
                                scaleY = moveDownScale.value
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Default.KeyboardArrowDown,
                                contentDescription = "下移",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        Spacer(modifier = Modifier.size(28.dp))
                    }
                }
            }
        }
    }
}

/**
 * 新建类别卡片 Composable 函数
 *
 * 功能说明：
 * 显示在类别列表末尾的可点击占位卡片
 * 点击后触发 showAddCategoryDialog 弹出新建类别对话框
 *
 * 调用位置：
 * - 由 TagManagementScreen 的 LazyColumn 中 item() 函数在列表末尾渲染
 *
 * @param onClick 点击回调，打开新建类别对话框
 * @param modifier 可选的 Modifier
 */
@Composable
fun AddCategoryCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        onClick = onClick
    ) {
        Text(
            text = "+ 新建类别",
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

/**
 * 添加/编辑类别对话框 Composable 函数
 *
 * 功能说明：
 * 包含类别名称输入框和颜色选择器的对话框。
 * 用于"新建类别"和"修改类别名称"两个场景（通过 title 参数区分）。
 * 输入框限制为单行，颜色选择器使用 ColorPicker 组件。
 * 只有名称非空时确认按钮才可点击。
 *
 * 调用位置：
 * - showAddCategoryDialog = true 时，用于新建类别
 * - showEditCategoryDialog = true 时，用于编辑类别名称和颜色
 *
 * @param title 对话框标题文字（如"新建类别"或"修改类别名称"）
 * @param initialName 初始类别名称（新建时为空字符，编辑时为当前名称）
 * @param initialColor 初始颜色值（默认为红色 0xFFF44336）
 * @param onConfirm 确认回调，参数为 (name, color)，由调用方处理持久化
 * @param onDismiss 取消/关闭回调
 * @param modifier 可选的 Modifier
 */
@Composable
fun AddEditCategoryDialog(
    title: String,
    initialName: String,
    initialColor: Int,
    onConfirm: (name: String, color: Int) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    var name by remember(initialName) { mutableStateOf(initialName) }
    var selectedColor by remember(initialName) { mutableStateOf(initialColor) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("类别名称") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true  // 限制为单行输入
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text("选择颜色：")

                Spacer(modifier = Modifier.height(8.dp))

                ColorPicker(
                    selectedColor = selectedColor,
                    onColorSelected = { selectedColor = it }
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(name, selectedColor) },
                enabled = name.isNotBlank()
            ) { Text("确认") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
