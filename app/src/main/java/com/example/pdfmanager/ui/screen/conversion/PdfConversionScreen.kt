package com.example.pdfmanager.ui.screen.conversion

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.activity.ComponentActivity
import com.example.pdfmanager.R
import com.example.pdfmanager.data.model.ConversionProgress
import com.example.pdfmanager.ui.component.ConversionProgressDialog
import com.example.pdfmanager.ui.component.LibraryFolderPickerDialog
import com.example.pdfmanager.ui.viewmodel.ConversionViewModel
import kotlinx.coroutines.launch

/**
 * 【PDF 转换页面（二级页面，全屏）】
 *
 * ── 功能说明 ──
 * 本页面是 ZIP 文件转 PDF 的核心操作页面。
 * 用户在此页面完成以下操作流程：
 *   1. 选择/更改监控文件夹（ZIP 文件的来源）
 *   2. 选择/更改 PDF 输出位置（目标文件夹）
 *   3. 查看通过扫描发现的所有可转换 ZIP 文件列表
 *   4. 以多选（转换所有图片模式）或单选方式选择要转换的文件
 *   5. 点击底部按钮开始转换
 *
 * ── 调用位置 ──
 * - 通过 Navigation 导航图路由 "pdf_conversion" 跳转到此页面
 *
 * ── 使用场景 ──
 * 用户在"库"页面或其他入口点击"PDF 转换"功能后进入此页。
 * 适用于需要将多个 ZIP 压缩包（内含图片）批量转换为 PDF 文档的场景。
 *
 * ── 布局结构 ──
 *   ┌─ TopAppBar: 返回箭头 + 标题"PDF 转换"
 *   ├─ 卡片1: ZIP 额外监控文件夹（显示当前路径，提供"更改"和"清除"按钮）
 *   ├─ 卡片2: PDF 输出位置（显示当前路径，提供"更改"和"恢复默认"按钮）
 *   ├─ 卡片3: 可转换文件列表（每个文件含 Checkbox/Radio + 名称 + 大小 + 重名警告）
 *   └─ BottomBar: "开始转换"按钮
 *
 * ── 交互逻辑 ──
 * 1. 页面加载时自动调用 scanZipFiles() 扫描监控文件夹中的 ZIP 文件
 * 2. 用户可通过系统 SAF 对话框更改监控文件夹
 * 3. 用户可通过自定义 LibraryFolderPickerDialog 更改输出位置
 * 4. "默认转换所有图片"开关控制多选/单选模式
 * 5. 多选模式下使用 Checkbox，支持同时转换多个 ZIP
 * 6. 单选模式下使用 RadioButton，每次只能选一个 ZIP（进入页码选择页）
 * 7. 选中文件后点击"开始转换"触发转换流程
 * 8. 转换过程中显示 ConversionProgressDialog 进度弹窗
 * 9. 转换完成后显示结果弹窗（成功数 + 失败列表）
 * 10. 若为单选模式（不转换所有图片），先解压 ZIP 并导航到 PageSelectionScreen 让用户选页
 *
 * @param navController 导航控制器，用于返回上一页或跳转到页面选择页
 * @param modifier      修饰符，用于外部定制样式
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfConversionScreen(
    navController: NavController,
    modifier: Modifier = Modifier
) {
    // ── ViewModel 初始化 ───────────────────────────────────────────────
    // 使用 activity 级 ViewModel，与 PageSelectionScreen 共享同一个 ConversionViewModel 实例
    // activity 级作用域确保两个导航页面之间状态不丢失
    val viewModel: ConversionViewModel = viewModel(
        factory = ConversionViewModel.Factory,
        viewModelStoreOwner = LocalContext.current as ComponentActivity
    )
    val context = LocalContext.current

    // ── 从 ViewModel 收集 StateFlow 状态 ───────────────────────────────
    // 监控文件夹 URI（ZIP 文件来源）
    val monitorFolderUri by viewModel.monitorFolderUri.collectAsStateWithLifecycle()
    // 输出路径 URI（生成 PDF 的存放位置，null 表示使用库根目录）
    val outputPathUri by viewModel.outputPathUri.collectAsStateWithLifecycle()
    // 扫描到的 ZIP 文件列表
    val zipFiles by viewModel.zipFiles.collectAsStateWithLifecycle()
    // 用户选中的文件名集合
    val selectedFiles by viewModel.selectedFiles.collectAsStateWithLifecycle()
    // 是否正在扫描中
    val isScanning by viewModel.isScanning.collectAsStateWithLifecycle()
    // 当前转换进度（null 表示未在转换）
    val conversionProgress by viewModel.conversionProgress.collectAsStateWithLifecycle()
    // 转换结果（成功数, 失败文件名列表）
    val conversionResult by viewModel.conversionResult.collectAsStateWithLifecycle()
    // 是否"默认转换所有图片"（true=多选，false=单选+页码选择）
    val convertAllImages by viewModel.convertAllImages.collectAsStateWithLifecycle()
    // 解压后的图片预览信息（null 表示未解压）（单选模式下使用）
    val extractedImages by viewModel.extractedImages.collectAsStateWithLifecycle()

    val scrollState = rememberScrollState()

    // ── SAF 文件夹选择器（监控文件夹选择） ─────────────────────────────
    // 使用 Android 系统的 Storage Access Framework 的 OpenDocumentTree 协议，
    // 让用户从系统文件选择器中选择一个文件夹作为 ZIP 监控目录
    val monitorFolderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            // 持久化 URI 权限：确保应用重启后仍能访问该文件夹
            context.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            // 保存到 ViewModel 并重新扫描
            viewModel.saveMonitorFolder(uri)
        }
    }

    // 自定义文件夹输出位置选择器状态
    var showFolderPicker by remember { mutableStateOf(false) }
    var libraryUri by remember { mutableStateOf<Uri?>(null) }

    // 【初始化】加载库文件夹的 URI（用于打开自定义文件夹选择器）
    LaunchedEffect(Unit) {
        val uriString = viewModel.getLibraryUri()
        if (uriString != null) {
            libraryUri = Uri.parse(uriString)
        }
    }

    /**
     * 【局部函数】获取路径的显示名称
     *
     * 将 URI 字符串解码为人类可读的文件夹名称。
     * URI 中的 lastPathSegment 经过 URL 编码（如空格被编码为 %20），
     * 此函数将其解码为原始文本。
     *
     * @param uriString URI 字符串（可能为 null 或空）
     * @return 解码后的路径名称，"未选择"表示未设置，"路径无效"表示解析失败
     */
    fun getFullPath(uriString: String?): String {
        if (uriString.isNullOrEmpty()) return "未选择"
        return try {
            val uri = Uri.parse(uriString)
            val decoded = uri.lastPathSegment?.let { android.net.Uri.decode(it) }
            if (!decoded.isNullOrEmpty()) decoded else uri.toString()
        } catch (e: Exception) {
            "路径无效"
        }
    }

    // 监控文件夹路径显示名称（用于界面展示）
    var monitorFolderName by remember { mutableStateOf(getFullPath(monitorFolderUri)) }
    // 输出位置路径显示名称（用于界面展示）
    var outputPathName by remember { mutableStateOf(if (outputPathUri.isNullOrEmpty()) "库根目录" else getFullPath(outputPathUri)) }

    // 【监听】监控文件夹 URI 变化时刷新显示名称
    LaunchedEffect(monitorFolderUri) {
        monitorFolderName = getFullPath(monitorFolderUri)
    }

    // 【监听】输出路径 URI 变化时刷新显示名称
    LaunchedEffect(outputPathUri) {
        outputPathName = if (outputPathUri.isNullOrEmpty()) "库根目录" else getFullPath(outputPathUri)
    }

    // 【初始化】进入页面时自动扫描 ZIP 文件
    LaunchedEffect(Unit) {
        viewModel.scanZipFiles()
    }

    // 【监听】解压完成（extractedImages 不为 null）后，自动导航到页码选择页
    // 此行为仅在"默认转换所有图片"关闭（单选模式）时触发
    LaunchedEffect(extractedImages) {
        if (extractedImages != null) {
            // 跳转到 PageSelectionScreen，让用户选择要转换的页码
            navController.navigate("page_selection")
        }
    }

    // ── 转换结果弹窗 ───────────────────────────────────────────────────
    // 当 conversionResult 不为 null 时弹窗显示转换完成信息
    conversionResult?.let { result ->
        val successCount = result.first    // 成功转换的文件数
        val failedFiles = result.second    // 转换失败的文件名列表
        AlertDialog(
            onDismissRequest = { viewModel.clearConversionResult() },
            title = { Text("转换完成") },
            text = {
                Column {
                    if (failedFiles.isEmpty()) {
                        // 全部成功
                        Text("已转换 ${successCount} 个文件为 PDF，并添加到库中。")
                    } else {
                        // 部分失败：显示成功数和失败文件列表
                        Text("转换完成，成功 ${successCount} 个，失败 ${failedFiles.size} 个。")
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("失败文件：")
                        failedFiles.forEach { fileName ->
                            Text("• $fileName", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = { viewModel.clearConversionResult() }) {
                    Text("确定")
                }
            }
        )
    }

    // ── 页面主体（Scaffold 布局） ─────────────────────────────────────
    Scaffold(
        // ── 顶部工具栏 ───────────────────────────────────────────────
        topBar = {
            TopAppBar(
                title = { Text("PDF 转换") },
                navigationIcon = {
                    // 返回按钮：回到上一页
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(
                            imageVector = androidx.compose.material.icons.Icons.Default.ArrowBack,
                            contentDescription = "返回"
                        )
                    }
                }
            )
        },
        // ── 底部操作栏 ───────────────────────────────────────────────
        bottomBar = {
            Column {
                Divider()
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    // 【开始转换按钮】
                    Button(
                        onClick = {
                            if (convertAllImages) {
                                // 多选模式：直接转换所有选中的 ZIP 文件
                                viewModel.startConversion()
                            } else {
                                // 单选模式：只转换选中的那一个（先解压，进页码选择页）
                                val selectedName = selectedFiles.firstOrNull() ?: return@Button
                                viewModel.startSingleConversion(selectedName)
                            }
                        },
                        // 按钮启用条件：已选中文件且当前不在转换中
                        enabled = selectedFiles.isNotEmpty() && conversionProgress == null,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            if (convertAllImages)
                                "开始转换 (${selectedFiles.size}/${zipFiles.size})"
                            else
                                "开始转换"
                        )
                    }
                }
            }
        }
    ) { paddingValues ->
        // ── 主内容区（可滚动 Column） ──────────────────────────────────
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(scrollState)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ═══════════════════════════════════════════════════════════
            // 卡片1：监控文件夹
            // ═══════════════════════════════════════════════════════════
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("zip 额外监控文件夹", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    // 显示当前选中的路径
                    Text(
                        text = "当前路径：$monitorFolderName",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // 【更改按钮】使用系统 SAF 选择新文件夹
                        Button(
                            onClick = { monitorFolderLauncher.launch(null) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("更改")
                        }
                        // 【清除按钮】清除监控文件夹设置（仅已设置时显示）
                        if (!monitorFolderUri.isNullOrEmpty()) {
                            Button(
                                onClick = { viewModel.clearMonitorFolder() },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("清除")
                            }
                        }
                    }
                }
            }

            // ═══════════════════════════════════════════════════════════
            // 卡片2：PDF 输出位置
            // ═══════════════════════════════════════════════════════════
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("PDF 输出位置", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    // 显示当前输出路径
                    Text(
                        text = "当前路径：$outputPathName",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // 【更改按钮】显示自定义文件夹选择器
                        Button(
                            onClick = {
                                // 需要先确保库 URI 已加载
                                if (libraryUri != null) {
                                    showFolderPicker = true
                                }
                            },
                            modifier = Modifier.weight(1f),
                            enabled = libraryUri != null
                        ) {
                            Text("更改")
                        }
                        // 【恢复默认按钮】恢复输出位置为库根目录（仅已设置时显示）
                        if (!outputPathUri.isNullOrEmpty()) {
                            Button(
                                onClick = { viewModel.clearOutputPath() },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("恢复默认")
                            }
                        }
                    }

                    // 库文件夹未选择时的错误提示
                    if (libraryUri == null) {
                        Text(
                            text = "⚠️ 请先选择库文件夹",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }

            // ═══════════════════════════════════════════════════════════
            // 卡片3：发现的可转换文件列表
            // ═══════════════════════════════════════════════════════════
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    // 标题行 + 操作按钮
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("可转换文件", style = MaterialTheme.typography.titleMedium)
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // 【"默认转换所有图片"开关】
                            // true = 多选模式（Checkbox，跳过页码选择，直接批量转换）
                            // false = 单选模式（RadioButton，进入页码选择页选页）
                            Text(
                                text = "默认转换所有图片",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Switch(
                                checked = convertAllImages,
                                onCheckedChange = { viewModel.toggleConvertAllImages() },
                                modifier = Modifier.scale(0.8f)
                            )

                            // 【重新扫描按钮】
                            IconButton(
                                onClick = { viewModel.scanZipFiles() },
                                enabled = !isScanning
                            ) {
                                if (isScanning) {
                                    // 扫描中显示加载指示器
                                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                                } else {
                                    Icon(
                                        imageVector = androidx.compose.material.icons.Icons.Default.Refresh,
                                        contentDescription = "重新扫描"
                                    )
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))

                    // 文件列表区域
                    if (zipFiles.isEmpty()) {
                        // 没有文件：显示空状态提示
                        Text(
                            text = if (isScanning) "正在扫描..." else "没有发现 zip 文件",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(vertical = 16.dp)
                        )
                    } else {
                        // 遍历 ZIP 文件列表，每个文件一行
                        zipFiles.forEach { zipFile ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { viewModel.toggleFileSelection(zipFile.name) }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // 选择控件：多选模式用 Checkbox，单选模式用 RadioButton
                                if (convertAllImages) {
                                    Checkbox(
                                        checked = selectedFiles.contains(zipFile.name),
                                        onCheckedChange = { viewModel.toggleFileSelection(zipFile.name) }
                                    )
                                } else {
                                    RadioButton(
                                        selected = selectedFiles.contains(zipFile.name),
                                        onClick = { viewModel.toggleFileSelection(zipFile.name) }
                                    )
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                // 文件名 + 状态信息
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(zipFile.name, style = MaterialTheme.typography.bodyMedium)

                                    // 重名警告：如果该 ZIP 文件名与库中已有 PDF 同名，显示警告
                                    if (zipFile.isDuplicate) {
                                        Text(
                                            text = "⚠️ 与库中文件重名",
                                            color = MaterialTheme.colorScheme.error,
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }

                                    // 文件大小
                                    Text(
                                        text = formatFileSize(zipFile.size),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // ── 转换进度弹窗 ───────────────────────────────────────────────
        // 当 conversionProgress 不为 null 时显示进度对话框
        if (conversionProgress != null) {
            ConversionProgressDialog(
                progress = conversionProgress,
                onDismiss = { /* 不允许取消，所以 onDismiss 为空操作 */ }
            )
        }

        // ── 自定义文件夹选择器（输出路径选择） ─────────────────────────
        // 当用户点击输出位置的"更改"按钮时弹出
        if (showFolderPicker) {
            val lu = libraryUri  // 本地捕获，避免委托属性的 smart cast 问题
            if (lu != null) {
                LibraryFolderPickerDialog(
                    libraryUri = lu,
                    currentOutputUri = outputPathUri?.let { Uri.parse(it) },
                    onFolderSelected = { selectedUri ->
                        // 用户选中文件夹后保存到 ViewModel
                        viewModel.saveOutputPath(selectedUri)
                        showFolderPicker = false
                    },
                    onDismiss = { showFolderPicker = false }
                )
            }
        }
    }
}

/**
 * 格式化文件大小为人类可读形式
 *
 * 根据文件大小自动选择 B / KB / MB 单位，显示为 1 位小数（KB/MB 时）。
 *
 * @param size 文件大小（字节）
 * @return 格式化后的字符串，如 "1.5 MB"、"234.0 KB"、"512 B"
 */
fun formatFileSize(size: Long): String {
    val kb = size / 1024.0
    val mb = kb / 1024.0
    return when {
        mb >= 1 -> "%.1f MB".format(mb)
        kb >= 1 -> "%.1f KB".format(kb)
        else -> "$size B"
    }
}
