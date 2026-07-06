package com.example.pdfmanager.ui.screen.detail

import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.pdfmanager.data.model.PdfFile
import com.example.pdfmanager.ui.component.PdfThumbnail
import com.example.pdfmanager.ui.component.ShareTargetPicker
import com.example.pdfmanager.ui.component.TagCategoryRowReadOnly
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.border



/**
 * PDF 文件详情页面（Composable 入口）
 *
 * ========== 功能说明 ==========
 * 1. 展示 PDF 文件的基本信息（文件名、缩略图、上次阅读进度）
 * 2. 提供"开始阅读"、"继续阅读"、"分享此文件"三个操作按钮
 * 3. 以只读方式展示文件已有的标签（按类别分组）
 * 4. 提供一个"编辑标签"模块，支持点击切换已定义标签值
 * 5. 允许用户输入/修改备注文本，并在离开页面时自动保存
 * 6. 支持"添加标签"对话框：选择类别 → 输入新标签值 → 创建并关联到当前文件
 * 7. 集成分享目标选择器（ShareTargetPicker BottomSheet），分享到指定目录
 *
 * ========== 生命周期处理 ==========
 * - 通过 Lifecycle ON_RESUME 事件监听，每次从阅读器等页面返回时自动重新加载文件数据
 *   （包括 lastReadPage 的最新值）
 * - 通过 BackHandler（系统返回键）拦截，在退出前自动保存未保存的备注修改
 *
 * ========== 导航跳转 ==========
 * - "开始阅读"按钮 → 导航到 reader/{fileId}?forceStart=true（强制从第 1 页开始）
 * - "继续阅读"按钮 → 导航到 reader/{fileId}（从上次阅读位置继续）
 * - 返回按钮 → 调用 onBack()（navController.popBackStack()）
 *
 * ========== 内部组件结构 ==========
 * - DetailScreen 仅做布局框架（Scaffold + TopAppBar）
 * - 实际内容由私有组件 DetailContent 渲染
 * - DetailContent 内部包含 4 个 Card：
 *   Card 1: 缩略图 + 操作按钮
 *   Card 2: 标签展示（只读）
 *   Card 3: 备注编辑
 *   Card 4: 标签编辑（可点击切换）
 *
 * @param fileId 文件唯一标识符（UUID 字符串），从导航路由参数 "detail/{fileId}" 中获取
 *               对应 PdfFile.id 字段
 * @param onBack 返回回调函数，通常为 navController.popBackStack()
 *               当用户点击顶部导航栏返回按钮或按系统返回键时触发
 * @param navController Jetpack Navigation 导航控制器
 *               用于跳转到阅读器页面（reader/{fileId}）以及管理返回栈
 * @param viewModel 详情页 ViewModel，通过 DetailViewModel.Factory(fileId) 创建
 *               负责管理文件数据、标签数据、备注保存等业务逻辑
 *               使用默认参数由 Compose 自动注入
 *
 * ========== 调用位置 ==========
 * - AppNavGraph.kt 第 78-82 行：
 *   composable("detail/{fileId}") { ... DetailScreen(fileId, onBack, navController) }
 * - 入口来源：
 *   1. 文件列表页（AllFilesScreen）点击某个 PDF 文件行
 *   2. 收藏页（FavoritesScreen）点击某个文件
 *   3. 搜索结果页点击某个文件
 *
 * ========== 使用场景 ==========
 * 用户在查看 PDF 文件列表时，点击任意文件卡片或行，导航到此页面查看文件详情。
 * 在此页面用户可以：
 * - 查看文件缩略图和基本信息
 * - 查看/编辑文件的标签
 * - 添加新的自定义标签值
 * - 编辑文件备注
 * - 开始阅读或继续之前的阅读进度
 * - 分享文件到其他目录
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable

fun DetailScreen(
    fileId: String,
    onBack: () -> Unit,
    navController: androidx.navigation.NavController,
    viewModel: DetailViewModel = viewModel(
        factory = DetailViewModel.Factory(fileId)
    )
) {
    // 从 ViewModel 中获取 PdfFile 数据的 StateFlow，并转换为 Compose 状态
    // 当 ViewModel 中的 _pdfFile 更新时，UI 会自动重组
    val pdfFile by viewModel.pdfFile.collectAsStateWithLifecycle()

    // 备注文本的本地状态（仅在当前 Composable 中维护）
    // 初始化值在 LaunchedEffect(pdfFile) 中从 pdfFile.notes 同步
    var notes by remember { mutableStateOf("") }

    // 标记备注是否有未保存的修改
    // 当用户修改备注内容时设为 true；保存成功后（或加载新文件时）重置为 false
    var hasChanges by remember { mutableStateOf(false) }

    // 协程作用域，用于启动一次性协程（如分享操作）
    val coroutineScope = rememberCoroutineScope()

    // 当前 Compose 上下文，用于 Android Toast 和 ContentResolver 操作
    val context = LocalContext.current

    // 分享目标选择器（BottomSheet）的显示状态
    // false=隐藏，true=显示
    var showSharePicker by remember { mutableStateOf(false) }

    /**
     * 生命周期事件监听器
     *
     * 功能：每次 Activity 从暂停状态恢复（ON_RESUME）时重新加载文件数据。
     * 这确保了当用户从阅读器页面返回时，页面能自动刷新 lastReadPage 的最新值。
     *
     * 特别说明：
     * - 当用户点击"开始阅读"/"继续阅读"跳转到 ReaderScreenV2 后，
     *   阅读器内部会在翻页时更新 Room 数据库中的 lastReadPage。
     * - 用户从阅读器返回此页面时，触发 ON_RESUME 事件 → loadFile() → 重新从 Room 读取最新数据。
     *
     * 调用位置：
     * - 仅在 DetailScreen 函数内部第 61-71 行注册
     *
     * 使用场景：
     * - 详情页进入前台状态时自动刷新数据
     * - 从阅读器页面返回后更新"上次阅读"的页码显示
     */
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.loadFile()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    /**
     * LaunchedEffect(fileId)：首次 Composable 进入组合时加载文件数据
     *
     * 功能：根据 fileId 从 Room 数据库加载对应的 PdfFile 实体，包括：
     * - 文件基本信息（名称、URI、大小等）
     * - 关联的标签列表
     * - 上次阅读页码（lastReadPage）
     *
     * @param fileId 监听 fileId 的变化，若 fileId 改变（路由参数变化）则重新加载
     *
     * 调用位置：
     * - 仅在 DetailScreen 函数内部第 73-75 行调用 viewModel.loadFile()
     *
     * 使用场景：
     * - 页面首次进入时
     * - 导航路由参数 fileId 变化时（少见，但框架自动处理）
     */
    LaunchedEffect(fileId) {
        viewModel.loadFile()
    }

    /**
     * LaunchedEffect(pdfFile)：监听 pdfFile 数据变化，同步备注本地状态
     *
     * 功能：当 ViewModel 加载完 PdfFile 数据后，将 pdfFile.notes 同步到本地 notes 状态
     * 同时重置 hasChanges 标志为 false
     *
     * 设计原因：
     * - notes 需要在 Composable 中可编辑（OutlinedTextField 的 value 绑定）
     * - 但初始值来自 Room 数据库，需要通过此 Effect 同步
     * - 只有首次加载或重新加载时才同步，后续编辑由用户操作驱动
     *
     * 调用位置：
     * - 仅在 DetailScreen 函数内部第 77-82 行注册
     *
     * 使用场景：
     * - 首次加载文件数据完成时
     * - 从阅读器返回后重新加载文件完成时
     */
    LaunchedEffect(pdfFile) {
        pdfFile?.let {
            notes = it.notes
            hasChanges = false
        }
    }

    /**
     * BackHandler：拦截系统返回键事件
     *
     * 功能：当系统返回键被按下时，先检查是否有未保存的备注修改。
     * 如果有（hasChanges == true），先自动保存备注，再执行 onBack() 导航返回。
     *
     * 调用位置：
     * - 仅在 DetailScreen 函数内部第 84-90 行注册
     *
     * 使用场景：
     * - 用户按 Android 系统返回键（物理键或手势）时
     * - 防止用户意外丢失未保存的备注内容
     */
    BackHandler {
        if (hasChanges) {
            viewModel.updateNotes(notes)
            hasChanges = false
        }
        onBack()
    }

    // Scaffold：Material 3 页面布局框架
    // 包含 TopAppBar 顶部导航栏
    // 内容区域根据 pdfFile 是否为空，显示 DetailContent 或加载动画
    Scaffold(
        topBar = {
            // 顶部导航栏：显示文件名 + 返回按钮
            TopAppBar(
                // 标题：显示文件 displayName，加载中时显示"加载中..."
                title = { Text(pdfFile?.displayName ?: "加载中...") },
                navigationIcon = {
                    // 返回按钮（箭头图标）：点击时先保存未保存的备注，再执行 onBack()
                    IconButton(onClick = {
                        if (hasChanges) {
                            viewModel.updateNotes(notes)
                            hasChanges = false
                        }
                        onBack()
                    }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { paddingValues ->
        pdfFile?.let { file ->
            /**
             * 渲染详情页的实际内容区域
             *
             * @param file 当前 PDF 文件数据对象（非空）
             * @param notes 当前备注文本（本地状态）
             * @param hasChanges 是否有未保存的修改标记
             * @param onNotesChanged 备注文本变更回调（将 hasChanges 设为 true）
             * @param onSaveNotes 保存备注回调（调用 ViewModel.updateNotes）
             * @param viewModel DetailViewModel 实例
             * @param onAddTag 添加标签回调
             * @param onRemoveTag 移除标签回调
             * @param onShare 分享按钮点击回调（显示 ShareTargetPicker）
             * @param navController 导航控制器
             * @param fileId 文件 ID
             * @param modifier 应用 Scaffold padding 的修饰符
             */
            DetailContent(
                file = file,
                notes = notes,
                hasChanges = hasChanges,
                onNotesChanged = { newNotes ->
                    notes = newNotes
                    hasChanges = true
                },
                onSaveNotes = {
                    if (hasChanges) {
                        viewModel.updateNotes(notes)
                        hasChanges = false
                    }
                },
                viewModel = viewModel,
                onAddTag = { categoryId, value -> viewModel.addTag(categoryId, value) },
                onRemoveTag = { categoryId, tagValue -> viewModel.removeTag(categoryId, tagValue) },
                onShare = { showSharePicker = true },
                navController = navController,
                fileId = fileId,
                modifier = Modifier.padding(paddingValues)
            )
        } ?: Box(
            // pdfFile 为 null 时显示加载动画（CircularProgressIndicator）
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator()
        }
    }

    /**
     * 分享目标选择器 BottomSheet
     *
     * 当 showSharePicker 为 true 时显示。
     * 与设置页面"一键分享"功能共用同一个 ShareTargetPicker 组件。
     *
     * 功能：
     * 1. 列出 share/ 目录下的一级子文件夹
     * 2. 用户选择目标文件夹后，调用 viewModel.shareCurrentFileToTarget() 复制文件
     * 3. 复制完成后通过 Toast 提示"已分享 N 个文件"
     *
     * @param onTargetSelected 目标选择回调（参数为选择的目标文件夹 URI 字符串）
     * @param onDismiss BottomSheet 关闭回调
     *
     * 调用位置：
     * - 仅在 DetailScreen 函数内部第 144-157 行调用
     *
     * 使用场景：
     * - 用户点击"分享此文件"按钮后弹出选择器
     */
    if (showSharePicker) {
        ShareTargetPicker(
            onTargetSelected = { targetUri ->
                showSharePicker = false
                coroutineScope.launch {
                    pdfFile?.let { file ->
                        val copied = viewModel.shareCurrentFileToTarget(Uri.parse(targetUri))
                        Toast.makeText(context, "已分享 $copied 个文件", Toast.LENGTH_SHORT).show()
                    }
                }
            },
            onDismiss = { showSharePicker = false }
        )
    }
}

/**
 * 详情页内容区域（私有 Composable）
 *
 * ========== 功能说明 ==========
 * 渲染详情页的完整内容，包含 4 个 Material 3 Card 模块：
 *
 * 1️⃣ 卡片 1 - 缩略图 + 操作按钮（第 220-294 行）
 *    - 左侧：PdfThumbnail 缩略图（0.75 宽高比）+ 上次阅读页码信息
 *    - 右侧：三个按钮 "分享此文件"、"开始阅读"、"继续阅读"
 *      - "继续阅读"仅在 lastReadPage > 0 时显示
 *      - "继续阅读"使用 secondaryContainer 主题色
 *
 * 2️⃣ 卡片 2 - 标签展示（只读）（第 297-328 行）
 *    - 以只读模式显示文件已有的标签
 *    - 标签按类别分组，每组用 TagCategoryRowReadOnly 组件展示
 *    - 按类别的 sortOrder 字段排序
 *    - 每个类别内的标签按 tagValue 降序排列
 *
 * 3️⃣ 卡片 3 - 备注编辑（第 331-349 行）
 *    - 一个 OutlinedTextField，绑定 notes 状态
 *    - 用户修改内容时通过 onNotesChanged 回调标记 hasChanges = true
 *    - 点击空白区域可触发焦点清除和自动保存（focusManager.clearFocus() + onSaveNotes()）
 *
 * 4️⃣ 卡片 4 - 标签编辑（第 352-439 行）
 *    - 列出所有预定义标签类别（从 Room 加载）
 *    - 每种类别显示颜色指示器 + 类别名称
 *    - 类别下的标签值以 FlowRow（自动换行）展示
 *    - 已选中的标签高亮显示（使用类别颜色 + 20% 透明度背景）
 *    - 点击标签可在选中/取消之间切换
 *    - 底部有"添加标签"按钮，弹出 AlertDialog 添加新标签值
 *
 * 5️⃣ 添加标签对话框（第 445-541 行）
 *    - 类别选择：RadioButton 列表（带滚动限制 200dp）
 *    - 标签值输入：OutlinedTextField
 *    - 输入验证：必须选择类别且标签值不能为空
 *    - 确认后调用 viewModel.addNewTagValue() 创建并关联新标签
 *
 * @param file 当前 PDF 文件数据对象（包含 URI、名称、标签、备注等信息）
 * @param notes 当前编辑的备注文本（双向绑定到 OutlinedTextField）
 * @param hasChanges 备注是否有未保存修改（用于决定是否需要保存）
 * @param onNotesChanged 备注文本变更回调（由 DetailScreen 传入，设置 hasChanges = true）
 * @param onSaveNotes 保存备注回调（由 DetailScreen 传入，调用 viewModel.updateNotes）
 * @param viewModel DetailViewModel 实例（提供分类数据、标签数据、类别查询等）
 * @param onAddTag 添加标签到当前文件 {@code (categoryId, value) -> Unit}
 * @param onRemoveTag 从当前文件移除标签 {@code (categoryId, tagValue) -> Unit}
 * @param onShare 分享按钮点击回调（显示 ShareTargetPicker BottomSheet）
 * @param navController 导航控制器（用于跳转到阅读器页面）
 * @param fileId 文件唯一标识符（传递给阅读器路由参数）
 * @param modifier Modifier 修饰符（来自 DetailScreen 的 Scaffold padding）
 *
 * ========== 调用位置 ==========
 * - 仅在 DetailScreen.kt 第 111-132 行（DetailScreen 函数内部）调用
 * - 该函数被定义为 private，因此仅限本文件内使用
 *
 * ========== 使用场景 ==========
 * 作为 DetailScreen 布局中的内容区域，在 PdfFile 数据加载完成后渲染。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DetailContent(
    file: PdfFile,
    notes: String,
    hasChanges: Boolean,
    onNotesChanged: (String) -> Unit,
    onSaveNotes: () -> Unit,
    viewModel: DetailViewModel,
    onAddTag: (String, String) -> Unit,
    onRemoveTag: (String, String) -> Unit,
    onShare: () -> Unit,
    navController: androidx.navigation.NavController,
    fileId: String,
    modifier: Modifier = Modifier
) {
    // Android 上下文（用于 ContentResolver 操作）
    val context = LocalContext.current
    val notesFocusRequester = remember { FocusRequester() }
    val coroutineScope = rememberCoroutineScope()

    // 焦点管理器（用于点击空白区域时清除输入框焦点）
    val focusManager = LocalFocusManager.current

    // 空白点击的交互源（用于点击空白区域但不显示水波纹效果）
    val interactionSource = remember { MutableInteractionSource() }

    /**
     * 从 ViewModel 中收集所有标签类别（StateFlow）
     * 用于"编辑标签"卡片中展示预定义标签值列表
     * 数据来源：TagRepository.categories（Room 数据库中的 tag_categories 表）
     */
    val categories by viewModel.allCategories.collectAsStateWithLifecycle()

    /**
     * 当前 PDF 文件的标签列表（StateFlow）
     * 用于"标签展示"和"编辑标签"两个卡片
     * 数据来源：TagRepository.getTagsForPdf()（Room 数据库中的 pdf_tags 表）
     */
    val pdfTags by viewModel.pdfTags.collectAsStateWithLifecycle()

    // ── "添加新标签"对话框的状态变量 ──
    var showAddTagDialog by remember { mutableStateOf(false) }   // 对话框显示/隐藏
    var selectedCategoryIdForNewTag by remember { mutableStateOf("") }  // 选中的类别 ID
    var newTagValue by remember { mutableStateOf("") }           // 输入的新标签值
    var addTagError by remember { mutableStateOf("") }          // 输入验证错误信息

    /**
     * LaunchedEffect：读取 PDF 文件的总页数
     *
     * 功能：通过 Android PdfRenderer API 打开文件并获取 pageCount，
     * 用于在缩略图下方显示"上次读到 第 X/Y 页"信息。
     *
     * 实现原理：
     * 1. 使用 ContentResolver.openFileDescriptor() 获取文件描述符
     * 2. 创建 PdfRenderer 实例
     * 3. 通过 renderer.pageCount 获取总页数
     * 4. 关闭 renderer 和 pfd 释放资源
     *
     * 异常处理：
     * - 如果文件不可读、损坏或无法解析，捕获异常并将 totalPages 设为 null
     * - 此时页码显示为"第 X/? 页"
     *
     * @param file.id 监听 file.id 变化，文件切换时重新读取
     *
     * 调用位置：
     * - 仅在 DetailContent 函数内部第 190-203 行（LaunchedEffect 中）
     *
     * 使用场景：
     * - 每次打开详情页时读取一次
     * - 展示阅读进度时使用
     */
    var totalPages by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(file.id) {
        try {
            val pfd = context.contentResolver.openFileDescriptor(file.uri, "r")
            if (pfd != null) {
                val renderer = PdfRenderer(pfd)
                totalPages = renderer.pageCount
                renderer.close()
                pfd.close()
            }
        } catch (e: Exception) {
            android.util.Log.e("DetailContent", "读取总页数失败", e)
            totalPages = null
        }
    }

    /**
     * 主内容列（可垂直滚动 + 点击空白区域清除焦点）
     *
     * 布局行为：
     * 1. 整个内容区域可垂直滚动（verticalScroll）
     * 2. 点击空白（非输入框、非按钮）区域 → clearFocus()（关闭软键盘）+ onSaveNotes()（自动保存备注）
     * 3. 子组件之间的间距为 12dp
     *
     * @param indication = null 点击空白时无点击水波纹
     */
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .clickable(
                indication = null,
                interactionSource = interactionSource
            ) {
                // 点击空白区域 → 仅关闭键盘（不保存，避免 Enter 键误触发）
                focusManager.clearFocus()
            }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ═══════════════════════════════════════════════
        // 卡片1：缩略图 + 操作按钮
        // ═══════════════════════════════════════════════
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            /**
             * 上次阅读页码（从 ViewModel 实时获取）
             * 数据来自 Room 数据库 PdfFileEntity.lastReadPage 字段
             * 当 Lifecycle ON_RESUME 触发 loadFile() 后自动更新
             */
            val lastReadPage by viewModel.lastReadPage.collectAsStateWithLifecycle()

            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.Top
            ) {
                // ── 左侧区域：缩略图 + 上次阅读信息 ──
                Column(
                    modifier = Modifier.fillMaxWidth(0.33f),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    /**
                     * PdfThumbnail：PDF 文件缩略图组件
                     *
                     * 功能：显示 PDF 文件第一页的缩略图
                     * - 如果缩略图已生成（thumbnailGenerated == 1），显示本地图片文件
                     * - 如果未生成，显示默认的 PDF 图标占位
                     * - 如果生成失败，显示错误图标
                     *
                     * @param pdfFile 当前 PDF 文件对象（用于读取 uri 和 thumbnailPath）
                     * @param modifier 0.75 宽高比（4:3 竖版布局），宽度填满父容器
                     *
                     * 组件位置：ui/component/PdfThumbnail.kt
                     */
                    PdfThumbnail(
                        pdfFile = file,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(0.75f)
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    // 上次阅读页码信息（缩略图下方）
                    // 仅当 lastReadPage > 0（文件曾被打开阅读过）时显示
                    if (lastReadPage > 0) {
                        Text(
                            text = "上次读到\n第 ${lastReadPage}/${totalPages ?: "?"} 页",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                Spacer(modifier = Modifier.width(16.dp))

                // ── 右侧区域：操作按钮 ──
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // "分享此文件"按钮：打开 ShareTargetPicker BottomSheet
                    Button(
                        onClick = onShare,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("分享此文件")
                    }

                    // "开始阅读"按钮：强制从第 1 页开始阅读
                    // 导航路由：reader/{fileId}?forceStart=true
                    // 传递 forceStart=true 参数，阅读器忽略 lastReadPage 从第 1 页打开
                    Button(
                        onClick = {
                            // 检查并保存未保存的备注（用户可能正在编辑但未失焦）
                            onSaveNotes()
                            // 检查文件是否存在（URI 是否有效）
                            if (viewModel.checkFileExists(context, file.uri)) {
                                navController.navigate("reader/$fileId?forceStart=true")
                            } else {
                                Toast.makeText(context, "此文件已移动或删除，请重新扫描", Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("开始阅读")
                    }

                    /**
                     * "继续阅读"按钮（条件显示）
                     *
                     * 显示条件：lastReadPage > 0（文件曾被打开阅读过）
                     * 导航路由：reader/{fileId}（不带 forceStart 参数）
                     * 阅读器会自动从 lastReadPage 位置打开
                     *
                     * 视觉样式：
                     * - 使用 secondaryContainer 主题色（与主按钮区分）
                     * - 按钮上方有 8dp 间距
                     */
                    if (lastReadPage > 0) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = {
                                // 检查并保存未保存的备注（用户可能正在编辑但未失焦）
                                onSaveNotes()
                                // 检查文件是否存在（URI 是否有效）
                                if (viewModel.checkFileExists(context, file.uri)) {
                                    navController.navigate("reader/$fileId")
                                } else {
                                    Toast.makeText(context, "此文件已移动或删除，请重新扫描", Toast.LENGTH_SHORT).show()
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        ) {
                            Text("继续阅读")
                        }
                    }
                }
            }
        }

        // ═══════════════════════════════════════════════
        // 卡片2：标签展示（只读）
        // ═══════════════════════════════════════════════
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("标签", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))

                // 无标签时显示占位提示文本
                if (pdfTags.isEmpty()) {
                    Text("暂无标签", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    // 按 categoryId 对标签进行分组
                    val groupedTags = pdfTags.groupBy { it.categoryId }

                    // 按类别的 sortOrder 字段排序（确保分类显示顺序与设置页一致）
                    val sortedCategoryIds = groupedTags.keys.sortedBy { categoryId ->
                        val category = viewModel.getCategoryById(categoryId)
                        category?.sortOrder ?: Int.MAX_VALUE
                    }

                    // 按排序后的顺序遍历每个类别
                    for (categoryId in sortedCategoryIds) {
                        val tags = groupedTags[categoryId] ?: continue
                        if (tags.isNotEmpty()) {
                            val category = viewModel.getCategoryById(categoryId)
                            if (category != null) {
                                /**
                                 * TagCategoryRowReadOnly：只读模式的标签类别行组件
                                 *
                                 * 功能：显示类别的名称（带颜色指示器）+ 该类别下的所有标签
                                 * 此为只读模式，标签不可点击切换
                                 *
                                 * @param category 标签类别对象（包含名称、颜色、排序字段）
                                 * @param tags 该类别的标签列表，按 tagValue 降序排序
                                 *
                                 * 组件位置：ui/component/TagCategoryRow.kt（TagCategoryRowReadOnly 函数）
                                 */
                                TagCategoryRowReadOnly(
                                    category = category,
                                    tags = tags.sortedByDescending { it.tagValue }
                                )
                            }
                        }
                    }
                }
            }
        }

        // ═══════════════════════════════════════════════
        // 卡片3：备注编辑
        // ═══════════════════════════════════════════════
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("备注", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))

                /**
                 * 备注输入框（OutlinedTextField）
                 *
                 * 绑定到 notes 状态，最小显示 3 行
                 * 用户输入时通过 onNotesChanged 回调标记 hasChanges = true
                 * 在以下时机自动保存：
                 * - 点击空白区域（Column 的 clickable 回调中调用 onSaveNotes）
                 * - 点击返回按钮（TopAppBar 的 ArrowBack）
                 * - 按系统返回键（BackHandler）
                 *
                 * @param value 当前备注文本（双向绑定）
                 * @param onValueChange 文本变更回调
                 * @param placeholder 占位提示文本 "添加备注..."
                 * @param minLines 最小行数 3
                 */
                OutlinedTextField(
                    value = notes,
                    onValueChange = { newNotes ->
                        onNotesChanged(newNotes)
                        // 搜狗 IME 按回车自动关键盘 → 等 100ms 键盘关完再重开
                        if (newNotes.length == notes.length + 1 && newNotes.endsWith("\n")) {
                            coroutineScope.launch {
                                delay(100)
                                notesFocusRequester.requestFocus()
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(notesFocusRequester),
                    placeholder = { Text("添加备注...") },
                    minLines = 3,
                    singleLine = false,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.None)

                )
            }
        }

        // ═══════════════════════════════════════════════
        // 卡片4：标签编辑（可点击切换标签值）
        // ═══════════════════════════════════════════════
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("编辑标签", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))

                // 无标签类别时显示提示（需要在设置页面先创建类别和标签值）
                if (categories.isEmpty()) {
                    Text("暂无标签类别，请先在设置中创建")
                } else {
                    // 获取当前文件已关联的所有标签值的集合
                    // 用于判断每个预定义标签值是否已被选中
                    // 格式：Set<Pair(tagValue, categoryId)>
                    val existingTagValues = pdfTags.map { it.tagValue to it.categoryId }.toSet()

                    // 按类别的 sortOrder 字段排序（与设置页顺序一致）
                    categories.sortedBy { it.sortOrder }.forEach { category ->
                        // 获取该类别的预定义标签值，按创建时间排序
                        val predefinedTagValues = category.tags.sortedBy { it.createdAt }

                        // 仅显示有预定义标签值的类别（跳过空类别）
                        if (predefinedTagValues.isNotEmpty()) {
                            // ── 类别名称 + 颜色指示器 ──
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(vertical = 4.dp)
                            ) {
                                // 颜色小方块（12x12dp），代表该类别的主题色
                                Surface(
                                    modifier = Modifier.size(12.dp),
                                    color = Color(category.color),
                                    shape = MaterialTheme.shapes.small
                                ) {}
                                Spacer(modifier = Modifier.size(4.dp))
                                Text(
                                    text = category.name,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }

                            /**
                             * 预定义标签值列表（可点击切换）
                             *
                             * 使用 FlowRow 实现自动换行布局
                             * 每个标签是一个 Surface（可点击），样式根据选中状态变化：
                             *
                             * ┌─────────────────────┬──────────────────────┐
                             * │      状态           │        样式          │
                             * ├─────────────────────┼──────────────────────┤
                             * │ 未选中 (isSelected  │ 背景：surface         │
                             * │ = false)            │ 文字：onSurface       │
                             * ├─────────────────────┼──────────────────────┤
                             * │ 已选中 (isSelected  │ 背景：category.color  │
                             * │ = true)             │        + 20% 透明度   │
                             * │                     │ 文字：category.color  │
                             * └─────────────────────┴──────────────────────┘
                             *
                             * 点击行为：
                             * - 已选中 → 调用 onRemoveTag 取消关联
                             * - 未选中 → 调用 onAddTag 建立关联
                             *
                             * 对齐方式：
                             * - 水平间距 4dp
                             * - 垂直间距 4dp
                             * - 左侧 16dp 缩进（与类别名称对齐）
                             */
                            FlowRow(
                                modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                predefinedTagValues.forEach { tagValue ->
                                    val isSelected = existingTagValues.contains(tagValue.value to category.id)

                                    Surface(
                                        onClick = {
                                            if (isSelected) {
                                                onRemoveTag(category.id, tagValue.value)
                                            } else {
                                                onAddTag(category.id, tagValue.value)
                                            }
                                        },
                                        shape = MaterialTheme.shapes.small,
                                        color = if (isSelected) {
                                            Color(category.color).copy(alpha = 0.2f)
                                        } else {
                                            MaterialTheme.colorScheme.surface
                                        },
                                        modifier = Modifier
                                            .padding(end = 6.dp, bottom = 6.dp)
                                    ) {
                                        Text(
                                            text = tagValue.value,
                                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                            color = if (isSelected) {
                                                Color(category.color)
                                            } else {
                                                MaterialTheme.colorScheme.onSurface
                                            },
                                            textAlign = TextAlign.Center
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // "添加标签"按钮（位于卡片底部，全宽）
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = { showAddTagDialog = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("添加标签")
            }
        }

        // 底部留白 100dp，确保内容区域底部有空白可点击以清除焦点和自动保存备注
        Spacer(modifier = Modifier.height(100.dp))
    }

    // ───────────────────────────────────────────────────
    // "添加新标签"对话框（AlertDialog）
    // ───────────────────────────────────────────────────
    /**
     * 功能：创建一个新的标签值（TagValue），并立即关联到当前 PDF 文件
     *
     * 流程：
     * 1. 用户选择一个类别（RadioButton 列表）
     * 2. 输入一个新的标签值（OutlinedTextField）
     * 3. 点击"确认"按钮触发验证：
     *    a. 检查是否已选择类别 → 未选择则显示 "请选择类别"
     *    b. 检查标签值是否为空/空白 → 为空则显示 "请输入标签值"
     * 4. 验证通过后调用 viewModel.addNewTagValue(categoryId, tagValue)
     *    - 内部先调用 TagRepository.addTagValue() 创建标签值
     *    - 再调用 TagRepository.addTagToPdf() 关联到当前文件
     *    - 最后刷新标签列表和类别列表（UI 自动更新）
     * 5. 重置所有对话框状态变量
     *
     * 对话框特点：
     * - 类别列表最多 200dp 高度，可垂直滚动（避免类别过多撑爆对话框）
     * - 错误提示以红色文字显示在输入框下方
     * - "取消"按钮也会重置所有状态
     *
     * @param showAddTagDialog 控制对话框显示/隐藏
     * @param selectedCategoryIdForNewTag 当前选中的类别 ID
     * @param newTagValue 用户输入的新标签值
     * @param addTagError 验证错误信息（空字符串表示无错误）
     *
     * 调用位置：
     * - 仅在 DetailContent 函数内部第 445-541 行
     *
     * 使用场景：
     * - 用户需要为当前文件添加一个预设列表中不存在的自定义标签值时
     */
    if (showAddTagDialog) {
        AlertDialog(
            onDismissRequest = {
                // 关闭对话框时重置所有状态
                showAddTagDialog = false
                selectedCategoryIdForNewTag = ""
                newTagValue = ""
                addTagError = ""
            },
            title = { Text("添加新标签") },
            text = {
                Column {
                    // ── 类别选择区域 ──
                    Text("选择类别：")
                    Spacer(modifier = Modifier.height(4.dp))

                    // 类别列表容器（限制最大高度 200dp，支持滚动）
                    Box(
                        modifier = Modifier.heightIn(max = 200.dp)  // 最大高度限制
                    ) {
                        Column(
                            modifier = Modifier.verticalScroll(rememberScrollState())
                        ) {
                            // 遍历所有类别，每行显示 RadioButton + 类别名称
                            categories.forEach { category ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { selectedCategoryIdForNewTag = category.id }
                                        .padding(vertical = 4.dp)
                                ) {
                                    RadioButton(
                                        selected = selectedCategoryIdForNewTag == category.id,
                                        onClick = { selectedCategoryIdForNewTag = category.id }
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(category.name)
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // ── 标签值输入区域 ──
                    Text("标签值：")
                    Spacer(modifier = Modifier.height(4.dp))
                    OutlinedTextField(
                        value = newTagValue,
                        onValueChange = { newTagValue = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("输入新标签值") }
                    )

                    // 输入验证错误提示（红色文字）
                    if (addTagError.isNotEmpty()) {
                        Text(addTagError, color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                // "确认"按钮：验证输入后添加新标签
                Button(
                    onClick = {
                        // 验证：必须选择类别
                        if (selectedCategoryIdForNewTag.isEmpty()) {
                            addTagError = "请选择类别"
                            return@Button
                        }
                        // 验证：标签值不能为空
                        if (newTagValue.isBlank()) {
                            addTagError = "请输入标签值"
                            return@Button
                        }

                        // 调用 ViewModel 添加新标签值并关联到当前文件
                        viewModel.addNewTagValue(selectedCategoryIdForNewTag, newTagValue)

                        // 重置状态（关闭对话框）
                        showAddTagDialog = false
                        selectedCategoryIdForNewTag = ""
                        newTagValue = ""
                        addTagError = ""
                    }
                ) {
                    Text("确认")
                }
            },
            dismissButton = {
                // "取消"按钮：重置所有状态后关闭
                Button(
                    onClick = {
                        showAddTagDialog = false
                        selectedCategoryIdForNewTag = ""
                        newTagValue = ""
                        addTagError = ""
                    }
                ) {
                    Text("取消")
                }
            }
        )
    }


}
