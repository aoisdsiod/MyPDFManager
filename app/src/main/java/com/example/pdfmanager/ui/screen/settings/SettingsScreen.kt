package com.example.pdfmanager.ui.screen.settings

import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.pdfmanager.data.local.PreferencesManager
import com.example.pdfmanager.data.repository.AppContainer
import com.example.pdfmanager.ui.component.ShareTargetPicker
import com.example.pdfmanager.ui.screen.allfiles.AllFilesViewModel
import com.example.pdfmanager.util.ThumbSize
import kotlinx.coroutines.launch
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import android.content.ComponentName
import android.content.ServiceConnection
import android.os.IBinder
import com.example.pdfmanager.service.ThumbnailGenerationService
import com.example.pdfmanager.service.ThumbnailGenerationBinder
import android.util.Log
import android.content.Intent
import com.example.pdfmanager.ui.component.ExportShareTargetPicker
import android.content.ClipData
import android.content.ClipboardManager

/**
 * 设置页面 Composable 函数
 *
 * 功能说明：
 * 本页面是应用的核心设置界面，整合了 8 大设置模块，以卡片形式纵向排列。
 * 用户可通过该页面自定义应用的各种行为和外观。
 *
 * 包含的 8 个设置模块：
 * 1. 主题模式 — 切换白天/黑夜/跟随系统三种主题
 * 2. 预览图大小 — 调整文件列表中 PDF 缩略图的显示尺寸（大/中/小）
 * 3. 标签管理 — 入口按钮，跳转到标签类别和标签值的管理页面
 * 4. 阅读设置 — 入口按钮，跳转到阅读器行为的配置页面
 * 5. 分享设置 — 多选分享模式的开关、一键分享选中文件、导出分享到 TXT
 * 6. PDF 转换 — 入口按钮，跳转到 zip 转 PDF 的配置页面
 * 7. 库管理 — 显示当前 PDF 库文件夹路径，并支持更改库文件夹
 * 8. 数据库操作 — 增量扫描库文件夹同步数据库、生成缺失的缩略图
 *
 * 调用位置：
 * - 由导航系统在用户点击"设置"入口时调用（路由跳转）
 * - 通常从 AllFilesScreen 或其他主界面通过 NavController.navigate("settings") 导航至此
 *
 * @param navController 导航控制器，用于导航到其他页面（如阅读设置、PDF 转换设置）
 * @param onRequestLibrary 请求更改库文件夹的回调函数，触发 SAF 文件选择器
 * @param onNavigateToTagManagement 导航到标签管理页面的回调函数
 * @param modifier 可选的 Modifier，用于外部自定义布局
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    navController: NavController,
    onRequestLibrary: () -> Unit = {},
    onNavigateToTagManagement: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    // ── 上下文与全局依赖注入 ──
    val context = LocalContext.current
    val preferencesManager = AppContainer.preferencesManager
    val coroutineScope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    // AllFilesViewModel：用于执行文件分享操作
    val allFilesViewModel: AllFilesViewModel = viewModel(
        factory = AllFilesViewModel.Factory()
    )

    // 当前选中的文件 ID 集合（多选模式下使用）
    val selectedFileIds by AppContainer.selectedFileIds.collectAsStateWithLifecycle()

    // 缩略图尺寸（初始为 MEDIUM，启动时从 PreferencesManager 恢复）
    var selectedSize by remember { mutableStateOf(ThumbSize.MEDIUM) }

    // ── 库路径显示 ──
    // 从 PreferencesManager 获取保存的库文件夹 URI，解码后显示给用户
    val libraryUri by preferencesManager.getLibraryUriFlow()
        .collectAsState(initial = "")

    // 根据 URI 解码生成可读的库路径显示文本
    val displayPath = remember(libraryUri) {
        if (!libraryUri.isNullOrEmpty()) {
            val uri = Uri.parse(libraryUri)
            val decoded = uri.lastPathSegment?.let { android.net.Uri.decode(it) }
            if (!decoded.isNullOrEmpty()) "当前库：$decoded" else "当前库：${uri.toString()}"
        } else {
            "未绑定"
        }
    }

    // ── 分享设置状态 ──
    val isMultiSelectMode by preferencesManager.getMultiSelectModeFlow()
        .collectAsState(initial = false)
    var showSharePicker by remember { mutableStateOf(false) }
    var showExportPicker by remember { mutableStateOf(false) }
    var showExitMultiSelectDialog by remember { mutableStateOf(false) }

    // 导出分享覆盖确认状态
    var showExportOverwriteDialog by remember { mutableStateOf(false) }
    var pendingExportFolder by remember { mutableStateOf<DocumentFile?>(null) }

    // 导出成功后是否复制到剪贴板的状态
    var showExportCopyDialog by remember { mutableStateOf(false) }
    var pendingExportContent by remember { mutableStateOf<String?>(null) }

    // ── 主题模式状态 ──
    val themeMode by preferencesManager.getThemeModeFlow()
        .collectAsState(initial = "follow_system")
    var showThemeDropdown by remember { mutableStateOf(false) }

    // ── 数据库操作状态 ──
    var showMigrateProgress by remember { mutableStateOf(false) }
    var showClearConfirm by remember { mutableStateOf(false) }
    var isScanning by remember { mutableStateOf(false) }

    // ── 缩略图生成状态（来自全局 AppContainer） ──
    val thumbnailProgress by AppContainer.thumbnailProgress.collectAsStateWithLifecycle()
    val isGenerating by AppContainer.isThumbnailGenerationRunning.collectAsStateWithLifecycle()
    val generationResult by AppContainer.thumbnailGenerationResult.collectAsStateWithLifecycle()

    /**
     * 页面初始化副作用：从 PreferencesManager 读取已保存的缩略图尺寸设置
     * 将 int 值映射为 ThumbSize 枚举
     */
    LaunchedEffect(Unit) {
        val saved = preferencesManager.getThumbnailSize()
        selectedSize = when (saved) {
            0 -> ThumbSize.LARGE
            2 -> ThumbSize.SMALL
            else -> ThumbSize.MEDIUM
        }
    }

    // ── 主界面布局：可纵向滚动的 Column ──
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("设置", style = MaterialTheme.typography.headlineMedium)

        // ==================== 1. 主题模式卡片 ====================
        /**
         * 主题模式设置卡片
         * 使用 DropdownMenu 提供三种选项：跟随系统、白天、黑夜
         * 选中后立即写入 PreferencesManager 持久化存储
         */
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("主题模式", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                Box {
                    OutlinedButton(
                        onClick = { showThemeDropdown = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            when (themeMode) {
                                "light" -> "白天"
                                "dark" -> "黑夜"
                                else -> "跟随系统"
                            }
                        )
                    }
                    DropdownMenu(
                        expanded = showThemeDropdown,
                        onDismissRequest = { showThemeDropdown = false }
                    ) {
                        listOf(
                            "follow_system" to "跟随系统",
                            "light" to "白天",
                            "dark" to "黑夜"
                        ).forEach { (value, label) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = {
                                    coroutineScope.launch {
                                        preferencesManager.setThemeMode(value)
                                    }
                                    showThemeDropdown = false
                                }
                            )
                        }
                    }
                }
            }
        }

        // ==================== 2. 预览图大小卡片 ====================
        /**
         * 预览图大小设置卡片
         * 提供大/中/小三档 RadioButton 横向排列
         * 选中后将 int 值写入 PreferencesManager 持久化存储
         */
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("预览图大小", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    listOf(
                        ThumbSize.LARGE to "大",
                        ThumbSize.MEDIUM to "中",
                        ThumbSize.SMALL to "小"
                    ).forEach { (size, label) ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 4.dp)
                        ) {
                            RadioButton(
                                selected = selectedSize == size,
                                onClick = {
                                    selectedSize = size
                                    coroutineScope.launch {
                                        val intValue = when (size) {
                                            ThumbSize.LARGE -> 0
                                            ThumbSize.SMALL -> 2
                                            else -> 1
                                        }
                                        preferencesManager.saveThumbnailSize(intValue)
                                    }
                                }
                            )
                            Text(label)
                        }
                    }
                }
            }
        }

        // ==================== 3. 标签管理卡片 ====================
        /**
         * 标签管理入口卡片
         * 点击"管理标签"按钮导航到 TagManagementScreen
         */
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("标签管理", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "管理标签类别和标签值",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = onNavigateToTagManagement,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("管理标签")
                }
            }
        }

        // ==================== 4. 阅读设置卡片 ====================
        /**
         * 阅读设置入口卡片
         * 点击"打开"按钮导航到 reader_settings 路由页面
         */
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("阅读设置", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "配置阅读器行为",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = { navController.navigate("reader_settings") },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("打开")
                }
            }
        }

        // ==================== 5. 分享设置卡片 ====================
        /**
         * 分享设置卡片
         * - Switch 控制多选分享模式的开关（关闭时若已有选中文件则弹出确认对话框）
         * - "一键分享"按钮：将已选中的文件分享到目标应用（需先开启多选模式）
         * - "导出分享"按钮：将文件信息导出为 TXT 文件，支持覆盖确认和复制到剪贴板
         */
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("分享设置", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))

                // 开启多选分享模式
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("开启多选分享模式", style = MaterialTheme.typography.bodyMedium)
                    Switch(
                        checked = isMultiSelectMode,
                        onCheckedChange = { newValue ->
                            if (!newValue && selectedFileIds.isNotEmpty()) {
                                showExitMultiSelectDialog = true
                            } else {
                                coroutineScope.launch {
                                    preferencesManager.setMultiSelectMode(newValue)
                                }
                            }
                        }
                    )
                }

                // 一键分享（选中文件）
                Button(
                    onClick = {
                        if (selectedFileIds.isEmpty()) {
                            android.widget.Toast.makeText(
                                context,
                                "请先在文件列表中选择文件",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        } else {
                            showSharePicker = true
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = isMultiSelectMode
                ) {
                    Text("一键分享（选中文件）")
                }

                Spacer(modifier = Modifier.height(8.dp))

                // 导出分享
                Button(
                    onClick = { showExportPicker = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("导出分享")
                }
            }
        }

        // ==================== 6. PDF 转换卡片 ====================
        /**
         * PDF 转换设置入口卡片
         * 点击"打开"按钮导航到 pdf_conversion 路由页面
         * 用于管理 zip 压缩文件转换为 PDF 的相关设置
         */
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("PDF 转换", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "管理 zip 转 PDF 设置",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = { navController.navigate("pdf_conversion") },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("打开")
                }
            }
        }

        // ==================== 7. 库管理卡片 ====================
        /**
         * 库管理卡片
         * 显示当前 PDF 库文件夹的路径（支持中文路径解码）
         * 通过 onRequestLibrary 回调触发 SAF 文件选择器让用户更改库文件夹
         */
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("库管理", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = displayPath,
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = onRequestLibrary,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("更改库文件夹")
                }
            }
        }

        // ==================== 8. 数据库操作卡片 ====================
        /**
         * 数据库操作卡片
         * 包含两个功能按钮：
         * 1. "扫描库文件夹" — 执行增量扫描，对比库文件夹与数据库记录：
         *    - 新增的 PDF 文件加入数据库
         *    - 已不存在的文件从数据库删除
         *    - 返回扫描结果摘要（新增/删除/移动数量）
         * 2. "生成缩略图" — 查询数据库中所有没有缩略图的文件，
         *    启动 ThumbnailGenerationService 前台服务进行批量生成，
         *    通过 ServiceConnection 绑定服务并传递文件列表
         */
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("数据库操作", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "增量扫描库文件夹，添加新增的 PDF 文件，删除已不存在的文件",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(modifier = Modifier.height(12.dp))

                // 按钮1：增量扫描库文件夹
                Button(
                    onClick = {
                        coroutineScope.launch {
                            isScanning = true
                            try {
                                val libraryUri = preferencesManager.getLibraryUri()
                                if (libraryUri != null) {
                                    // 增量扫描：对比库文件夹与数据库，添加新文件并删除不存在的文件
                                    val scanResult = com.example.pdfmanager.data.repository.AppContainer.pdfRepository.quickIncrementalScan()
                                    val moveInfo = if (scanResult.movedCount > 0) "，移动 ${scanResult.movedCount} 个文件" else ""
                                    val message = if (scanResult.hasChanges) {
                                        "扫描完成：新增 ${scanResult.addedCount} 个文件，删除 ${scanResult.deletedCount} 个文件$moveInfo"
                                    } else {
                                        "扫描完成：库文件夹与数据库一致"
                                    }
                                    android.widget.Toast.makeText(
                                        context,
                                        message,
                                        android.widget.Toast.LENGTH_SHORT
                                    ).show()
                                } else {
                                    android.widget.Toast.makeText(
                                        context,
                                        "请先设置库文件夹",
                                        android.widget.Toast.LENGTH_SHORT
                                    ).show()
                                }
                            } catch (e: Exception) {
                                android.widget.Toast.makeText(
                                    context,
                                    "扫描失败：${e.message}",
                                    android.widget.Toast.LENGTH_LONG
                                ).show()
                            } finally {
                                isScanning = false
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isScanning
                ) {
                    Text(if (isScanning) "扫描中..." else "扫描库文件夹")
                }

                Spacer(modifier = Modifier.height(8.dp))

                // 按钮2：生成缩略图
                Button(
                    onClick = {
                        // 在协程中查询数据库，获取未生成缩略图的文件
                        coroutineScope.launch {
                            val filesNeedingThumbnails = try {
                                AppContainer.pdfRepository.getFilesWithoutThumbnail()
                            } catch (e: Exception) {
                                Log.e("SettingsScreen", "查询未生成缩略图的文件失败", e)
                                emptyList()
                            }

                            if (filesNeedingThumbnails.isEmpty()) {
                                android.widget.Toast.makeText(
                                    context,
                                    "所有文件已有缩略图",
                                    android.widget.Toast.LENGTH_SHORT
                                ).show()
                                return@launch
                            }

                            // 启动前台服务进行缩略图生成
                            val intent = Intent(context, ThumbnailGenerationService::class.java)
                            context.startForegroundService(intent)

                            // 绑定服务并传递需要生成缩略图的文件列表
                            val connection = object : ServiceConnection {
                                override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                                    val binder = service as? ThumbnailGenerationBinder
                                    binder?.getService()?.startGeneration(filesNeedingThumbnails)
                                }

                                override fun onServiceDisconnected(name: ComponentName?) {}
                            }

                            context.bindService(intent, connection, android.content.Context.BIND_AUTO_CREATE)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isGenerating
                ) {
                    Text(if (isGenerating) "生成缩略图中..." else "生成缩略图")
                }

                Spacer(modifier = Modifier.height(8.dp))

                // 按钮3：查看数据库管理
                Button(
                    onClick = { navController.navigate("database_manage") },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("查看数据库")
                }

            }
        }
    }

    /**
     * 监听缩略图生成完成事件
     * 当 generationResult 变化时（不为 null），弹出 Toast 显示生成结果
     * 并在显示后重置 generationResult 为 null 以便下次使用
     *
     * 调用位置：由 Composable 重组机制自动触发
     */
    LaunchedEffect(generationResult) {
        val result = generationResult
        if (result != null) {
            val message = if (result.failed > 0) {
                "生成完成：新增 ${result.generated} 个缩略图，失败 ${result.failed} 个"
            } else {
                "生成完成：新增 ${result.generated} 个缩略图"
            }
            android.widget.Toast.makeText(
                context,
                message,
                android.widget.Toast.LENGTH_SHORT
            ).show()
            AppContainer.thumbnailGenerationResult.value = null // 重置
        }
    }


    // ── 退出多选模式确认对话框 ──
    /**
     * 当关闭多选模式时，如果已有选中的文件，弹出此对话框确认
     * 确认后清除所有选中文件 ID 并关闭多选模式
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
                            preferencesManager.setMultiSelectMode(false)
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

    // ── 分享目标选择器 BottomSheet ──
    /**
     * ShareTargetPicker：弹出底部菜单，让用户选择分享目标应用
     * 选中后通过 AllFilesViewModel.shareSelectedFiles() 执行文件分享
     */
    if (showSharePicker) {
        ShareTargetPicker(
            onTargetSelected = { targetUri ->
                showSharePicker = false
                coroutineScope.launch {
                    val copied = allFilesViewModel.shareSelectedFiles(Uri.parse(targetUri))
                    android.widget.Toast.makeText(
                        context,
                        "已分享 $copied 个文件",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            },
            onDismiss = { showSharePicker = false }
        )
    }

    // ── 导出分享文件夹选择器 ──
    /**
     * ExportShareTargetPicker：弹出 SAF 文件选择器让用户选择导出目标文件夹
     * 选中后检查是否已存在"导出分享.txt"，若存在则弹出覆盖确认对话框，否则直接导出
     */
    if (showExportPicker) {
        ExportShareTargetPicker(
            onTargetSelected = { targetUri ->
                showExportPicker = false
                try {
                    val folder = DocumentFile.fromTreeUri(context, Uri.parse(targetUri))
                    if (folder != null && folder.exists() && folder.isDirectory) {
                        // 检查是否已有导出分享.txt
                        val existingFile = com.example.pdfmanager.util.ExportUtils.checkExistingExportFile(folder)
                        if (existingFile != null) {
                            // 已有文件，显示覆盖确认弹窗
                            pendingExportFolder = folder
                            showExportOverwriteDialog = true
                        } else {
                            //没有文件直接导出
                            coroutineScope.launch {
                                val result = com.example.pdfmanager.util.ExportUtils.exportPdfInfoToTxt(context, folder)
                                if (result.success) {
                                    pendingExportContent = result.content
                                    showExportCopyDialog = true
                                } else {
                                    android.widget.Toast.makeText(
                                        context,
                                        result.message,
                                        android.widget.Toast.LENGTH_LONG
                                    ).show()
                                }
                            }

                        }
                    }
                } catch (e: Exception) {
                    android.widget.Toast.makeText(
                        context,
                        "导出失败：${e.message}",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            },
            onDismiss = { showExportPicker = false }
        )
    }

    // ── 导出分享覆盖确认弹窗 ──
    /**
     * 当目标文件夹中已存在"导出分享.txt"时，弹出此对话框让用户确认是否覆盖
     * 确认后调用 ExportUtils.exportPdfInfoToTxt() 重新生成，成功后弹出复制到剪贴板对话框
     */
    if (showExportOverwriteDialog) {
        AlertDialog(
            onDismissRequest = { showExportOverwriteDialog = false },
            title = { Text("确认覆盖") },
            text = { Text("已有导出分享.txt，是否覆盖？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showExportOverwriteDialog = false
                        pendingExportFolder?.let { folder ->
                            coroutineScope.launch {
                                val result = com.example.pdfmanager.util.ExportUtils.exportPdfInfoToTxt(context, folder)
                                if (result.success) {
                                    pendingExportContent = result.content
                                    showExportCopyDialog = true
                                } else {
                                    android.widget.Toast.makeText(
                                        context,
                                        result.message,
                                        android.widget.Toast.LENGTH_LONG
                                    ).show()
                                }
                            }

                        }
                    }
                ) {
                    Text("覆盖")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showExportOverwriteDialog = false }
                ) {
                    Text("取消")
                }
            }
        )
    }

    // ── 导出成功复制到剪贴板弹窗 ──
    /**
     * 导出 TXT 成功后，询问用户是否将内容同时复制到系统剪贴板
     * 使用 ClipboardManager 的 setPrimaryClip() 实现复制功能
     */
    if (showExportCopyDialog && pendingExportContent != null) {
        AlertDialog(
            onDismissRequest = {
                showExportCopyDialog = false
                pendingExportContent = null
            },
            title = { Text("导出成功") },
            text = { Text("内容已导出为 TXT 文件，是否同时复制到剪贴板？") },
            confirmButton = {
                Button(
                    onClick = {

                        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = ClipData.newPlainText("导出分享", pendingExportContent)
                        clipboard.setPrimaryClip(clip)
                        showExportCopyDialog = false
                        pendingExportContent = null
                        android.widget.Toast.makeText(
                            context,
                            "已复制到剪贴板",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                ) { Text("复制") }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showExportCopyDialog = false
                        pendingExportContent = null
                    }
                ) { Text("不用了") }
            }
        )
    }


}
