package com.example.pdfmanager.ui.screen.database

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController

/**
 * 【数据库管理页面】
 *
 * ── 功能说明 ──
 * 本页面让用户管理所有 Room 数据库文件（每个库文件夹对应一个独立的 .db 文件）。
 * 用户在此页面可以：
 *   1. 查看所有数据库文件的列表（文件名、大小、关联的库文件夹名称）
 *   2. 识别当前正在使用的数据库
 *   3. 切换到其他数据库
 *   4. 导出指定的数据库文件到外部存储（备份）
 *   5. 从外部文件导入数据库（恢复）
 *
 * ── 调用位置 ──
 * - 通过 Navigation 导航图路由跳转到此页面（例如从设置页的"数据库管理"入口进入）
 *
 * ── 布局结构 ──
 *   ┌─ TopAppBar: 返回箭头 + 标题"数据库管理"
 *   ├─ 主体（可滚动 Column）:
 *   │    ├─ 加载中：居中 CircularProgressIndicator
 *   │    ├─ 空状态："暂无数据库文件"提示
 *   │    └─ 数据库卡片列表：
 *   │         └─ 每张 Card 包含：
 *   │              ├─ 第一行：📄 图标 + 数据库文件名
 *   │              ├─ 第二行：文件大小（KB/MB 格式化）
 *   │              ├─ 第三行：关联的库文件夹名称（已知时显示）
 *   │              ├─ 【当前使用中】Badge（isCurrent = true 时显示）
 *   │              └─ 操作按钮行：
 *   │                   ├─ "导出"按钮（OutlinedButton）
 *   │                   └─ "切换到此库"按钮（isCurrent 时禁用）
 *   └─ 底部：Spacer + "导入数据库"按钮（全宽 FilledButton）
 *
 * ── 交互逻辑 ──
 * 1. 页面加载时自动调用 loadDatabases() 扫描所有数据库文件
 * 2. 点击"切换到此库"→ 弹出确认对话框 → 确认后切换
 * 3. 点击"导出"→ 弹出 SAF 创建文档选择器 → 选择位置后复制文件
 * 4. 点击"导入数据库"→ 先弹出 SAF 打开文档选择器选择源文件 → 然后弹出
 *    SAF 文件夹选择器选择目标库文件夹 → 复制文件并切换
 * 5. 操作成功或失败通过 Toast 提示用户
 * 6. 列表顶部显示当前正在使用的数据库（isCurrent = true）
 *
 * @param navController 导航控制器，用于返回上一页
 * @param modifier      修饰符，用于外部定制样式
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DatabaseManageScreen(
    navController: NavController,
    modifier: Modifier = Modifier
) {
    // ── ViewModel 初始化 ───────────────────────────────────────────────
    val viewModel: DatabaseManageViewModel = viewModel(
        factory = DatabaseManageViewModel.Factory
    )
    val context = LocalContext.current

    // ── 从 ViewModel 收集 StateFlow 状态 ───────────────────────────────
    // 数据库文件列表
    val databases by viewModel.databases.collectAsStateWithLifecycle()
    // 是否正在加载中
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    // Toast 消息
    val toastMessage by viewModel.toastMessage.collectAsStateWithLifecycle()

    val scrollState = rememberScrollState()

    // ── 对话框状态 ─────────────────────────────────────────────────────
    // 切换数据库确认对话框：存储要切换的数据库文件名，null 表示不显示
    var switchConfirmDbName by remember { mutableStateOf<String?>(null) }

    // ── SAF 文件选择器状态 ─────────────────────────────────────────────
    // 导出文件选择器：当用户点击"导出"时，用此 launcher 创建新文档
    // 临时存储当前要导出的数据库文件名
    var pendingExportDbName by remember { mutableStateOf<String?>(null) }

    // 导入文件选择器：当用户点击"导入数据库"时，先选择源文件
    // 选中源文件后，再弹出文件夹选择器选择目标库文件夹
    var pendingImportSourceUri by remember { mutableStateOf<Uri?>(null) }

    /**
     * 【SAF 创建文档选择器（导出操作）】
     *
     * 使用 ACTION_CREATE_DOCUMENT 让用户选择导出文件的保存位置和文件名。
     * 返回的 URI 将被用于写入数据库文件内容。
     */
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/x-sqlite3")
    ) { uri: Uri? ->
        if (uri != null && pendingExportDbName != null) {
            val dbName = pendingExportDbName!!
            // 执行导出操作
            viewModel.exportDatabase(context, dbName, uri)
            pendingExportDbName = null
        }
    }

    /**
     * 【SAF 打开文档选择器（导入源文件选择）】
     *
     * 使用 ACTION_OPEN_DOCUMENT 让用户选择一个 .db 文件作为导入源。
     * 选中后持久化读取权限，并保存 URI 供后续导入使用。
     */
    val importSourceLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            // 持久化读取权限，确保导入时能正常读取文件内容
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            // 保存源 URI，触发文件夹选择器
            pendingImportSourceUri = uri
        }
    }

    /**
     * 【SAF 文件夹选择器（导入目标库文件夹选择）】
     *
     * 在用户选中导入源文件后弹出，让用户选择一个库文件夹作为目标。
     * 选择后执行导入操作。
     */
    val importFolderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null && pendingImportSourceUri != null) {
            // 持久化文件夹读写权限
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            val sourceUri = pendingImportSourceUri!!
            pendingImportSourceUri = null
            // 执行导入操作
            viewModel.importDatabase(context, sourceUri, uri)
        } else {
            // 用户取消了文件夹选择，清除暂存的源 URI
            pendingImportSourceUri = null
        }
    }

    // ── 副作用处理 ─────────────────────────────────────────────────────

    // 【初始化】页面加载时自动扫描数据库列表
    LaunchedEffect(Unit) {
        viewModel.loadDatabases()
    }

    // 【监听 Toast 消息】当 toastMessage 非 null 时显示 Toast 并消费
    LaunchedEffect(toastMessage) {
        toastMessage?.let { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            viewModel.consumeToastMessage()
        }
    }

    // 【监听导入源 URI】当 pendingImportSourceUri 被设置时弹出文件夹选择器
    LaunchedEffect(pendingImportSourceUri) {
        if (pendingImportSourceUri != null) {
            // 传入 null 表示让用户自由选择一个文件夹
            importFolderLauncher.launch(null)
        }
    }

    // ── 切换确认对话框 ─────────────────────────────────────────────────
    switchConfirmDbName?.let { dbName ->
        // 在列表中查找对应的数据库信息，用于在对话框中显示详情
        val targetInfo = databases.find { it.dbFileName == dbName }
        AlertDialog(
            onDismissRequest = { switchConfirmDbName = null },
            title = {
                Text("确认切换")
            },
            text = {
                Column {
                    Text("确定要切换到以下数据库吗？")
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = targetInfo?.dbFileName ?: dbName,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold
                    )
                    targetInfo?.let { info ->
                        if (info.displayPath != null) {
                            Text(
                                text = "库文件夹: ${info.displayPath}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            text = "大小: ${formatDbFileSize(info.fileSize)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "切换后当前库文件夹将关闭，并打开该数据库对应的库文件夹。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        // 执行切换
                        viewModel.switchToDatabase(context, dbName)
                        switchConfirmDbName = null
                    }
                ) {
                    Text("确认切换")
                }
            },
            dismissButton = {
                TextButton(onClick = { switchConfirmDbName = null }) {
                    Text("取消")
                }
            }
        )
    }

    // ── 页面主体（Scaffold 布局） ─────────────────────────────────────
    Scaffold(
        // ── 顶部工具栏 ───────────────────────────────────────────────
        topBar = {
            TopAppBar(
                title = { Text("数据库管理") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "返回"
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        Box(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // ── 加载状态：居中显示加载指示器 ─────────────────────────
            if (isLoading && databases.isEmpty()) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center)
                )
            }
            // ── 空状态：没有发现任何数据库文件 ───────────────────────
            else if (databases.isEmpty() && !isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Storage,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                        Text(
                            text = "暂无数据库文件",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "您可以导入一个已有的数据库文件来开始管理",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )

                        // 导入数据库按钮（空状态时同样可用）
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = {
                                importSourceLauncher.launch(arrayOf(
                                    "application/octet-stream",
                                    "application/x-sqlite3",
                                    "*/*"
                                ))
                            },
                            enabled = !isLoading
                        ) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("导入数据库")
                        }
                    }
                }
            }
            // ── 数据库文件列表 ────────────────────────────────────────
            else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(scrollState)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // 列表标题：显示数据库总数
                    Text(
                        text = "共 ${databases.size} 个数据库",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )

                    // 遍历每个数据库，生成信息卡片
                    databases.forEach { dbInfo ->
                        DatabaseInfoCard(
                            info = dbInfo,
                            onExport = {
                                // 保存当前要导出的文件名，启动 SAF 创建文档
                                pendingExportDbName = dbInfo.dbFileName
                                // 使用原始文件名作为默认导出文件名（去掉 .db 后缀加上时间戳）
                                val defaultName = dbInfo.dbFileName
                                    .substringBeforeLast(".db") + "_backup.db"
                                exportLauncher.launch(defaultName)
                            },
                            onSwitch = {
                                // 弹出切换确认对话框
                                switchConfirmDbName = dbInfo.dbFileName
                            }
                        )
                    }

                    // ── 底部：导入数据库按钮 ──────────────────────────
                    Spacer(modifier = Modifier.height(8.dp))

                    Button(
                        onClick = {
                            // 启动 SAF 文件选择器，过滤只显示 .db 文件
                            // 使用 "application/octet-stream" 作为 MIME 类型
                            // 也可以使用 "application/x-sqlite3" 但兼容性不如通用类型
                            importSourceLauncher.launch(arrayOf(
                                "application/octet-stream",
                                "application/x-sqlite3",
                                "*/*"
                            ))
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isLoading
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("导入数据库")
                    }

                    // 底部留白，确保内容不被遮挡
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }

            // ── 全局加载指示器（覆盖层，操作进行时显示） ─────────────
            if (isLoading && databases.isNotEmpty()) {
                // 半透明覆盖层 + 加载指示器
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    contentAlignment = Alignment.TopCenter
                ) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

/**
 * 【数据库信息卡片组件】
 *
 * ── 功能说明 ──
 * 以 Card 形式展示单个数据库文件的详细信息，包括文件名、大小、关联库文件夹、
 * 当前使用状态，以及导出和切换操作按钮。
 *
 * ── 调用位置 ──
 * - DatabaseManageScreen.kt 的遍历列表中调用
 *
 * ── 布局结构 ──
 * ┌──────────────────────────────────┐
 * │ 📄 pdf_manager_xxxxx.db          │
 * │ 大小: 1.2 MB                     │
 * │ 库文件夹: MyPDFs              │
 * │ ┌──────────┐ ┌──────────────┐   │
 * │ │  导出    │ │ 切换到此库   │   │
 * │ └──────────┘ └──────────────┘   │
 * │          [当前使用中]            │ ← Badge，条件显示
 * └──────────────────────────────────┘
 *
 * @param info     数据库文件信息
 * @param onExport 点击"导出"按钮时的回调
 * @param onSwitch 点击"切换到此库"按钮时的回调（当前库时按钮禁用）
 */
@Composable
private fun DatabaseInfoCard(
    info: DatabaseInfo,
    onExport: () -> Unit,
    onSwitch: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        // 当前使用的数据库用主色边框突出显示
        colors = if (info.isCurrent) {
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.15f)
            )
        } else {
            CardDefaults.cardColors()
        }
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // ═══════════════════════════════════════════════════════════
            // 第一行：数据库图标 + 文件名
            // ═══════════════════════════════════════════════════════════
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 📄 数据库文件图标
                Text(
                    text = "\uD83D\uDCC4",
                    style = MaterialTheme.typography.titleLarge
                )
                Spacer(modifier = Modifier.width(10.dp))
                // 数据库文件名（支持单行溢出省略）
                Text(
                    text = info.dbFileName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            // ═══════════════════════════════════════════════════════════
            // 第二行：文件大小 + 关联的库文件夹
            // ═══════════════════════════════════════════════════════════
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                // 文件大小（格式化为人类可读形式）
                Text(
                    text = "大小: ${formatDbFileSize(info.fileSize)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // 关联的库文件夹名称（如果已知）
                if (info.displayPath != null) {
                    Text(
                        text = "库文件夹: ${info.displayPath}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                } else if (info.libraryUri != null) {
                    // 库 URI 已知但解析名称失败，显示 URI
                    Text(
                        text = "库文件夹 URI: ${info.libraryUri}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                } else {
                    // 没有映射关系：显示"未关联"
                    Text(
                        text = "未关联库文件夹",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // ═══════════════════════════════════════════════════════════
            // 第三行：操作按钮行
            // ═══════════════════════════════════════════════════════════
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 左侧：导出 + 切换按钮组
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // 【导出按钮】以 SAF 方式创建文档，备份数据库文件
                    OutlinedButton(
                        onClick = onExport,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Text("导出", style = MaterialTheme.typography.bodySmall)
                    }

                    // 【切换到此库按钮】仅非当前库时显示
                    if (!info.isCurrent) {
                        Button(
                            onClick = onSwitch,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                        ) {
                            Text("切换到此库", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }

                // 右侧：当前使用中 Badge
                if (info.isCurrent) {
                    Surface(
                        color = MaterialTheme.colorScheme.primary,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.padding(start = 8.dp)
                    ) {
                        Text(
                            text = "当前使用中",
                            color = MaterialTheme.colorScheme.onPrimary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                }
            }
        }
    }
}

/**
 * 【格式化数据库文件大小为人类可读形式】
 *
 * 根据文件大小自动选择 B / KB / MB 单位。
 * 与 PdfConversionScreen.kt 中的 formatFileSize() 函数功能相同，
 * 此处独立定义以避免跨包引用。
 *
 * @param size 文件大小（字节）
 * @return 格式化后的字符串，如 "1.2 MB"、"234.0 KB"、"512 B"
 */
private fun formatDbFileSize(size: Long): String {
    val kb = size / 1024.0
    val mb = kb / 1024.0
    return when {
        mb >= 1 -> "%.1f MB".format(mb)
        kb >= 1 -> "%.1f KB".format(kb)
        else -> "$size B"
    }
}
