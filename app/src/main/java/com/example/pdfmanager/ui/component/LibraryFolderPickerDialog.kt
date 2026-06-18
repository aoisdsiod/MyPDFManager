package com.example.pdfmanager.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import androidx.compose.ui.platform.LocalContext
import android.net.Uri
import android.util.Log

/** Log 标签，用于 LibraryFolderPickerDialog 中的异常日志输出 */
private const val TAG = "LibraryFolderPickerDialog"

/**
 * 自定义库文件夹选择器（AlertDialog 弹窗）
 *
 * ========== 功能概述 ==========
 * 在 AlertDialog 中展示一个受限的文件浏览器，让用户在库文件夹（libraryUri）的范围内
 * 浏览并选择一个子文件夹作为输出目标。不同于 SAF 系统文件选择器，此组件：
 * 1. 限定在库文件夹范围内，不会跳出到外部存储
 * 2. 仅显示文件夹（不显示文件），专注于目录选择
 * 3. 显示当前路径（相对于库根目录），让用户清楚自己所在的层级位置
 * 4. 支持进入子文件夹（点击文件夹行）和返回上级（"返回上级"按钮）
 *
 * ========== UI 结构 ==========
 * 1. 标题："选择输出文件夹"
 * 2. 当前路径显示行（文件夹图标 + "当前路径：/xxx"）
 * 3. 返回上级按钮（仅在非根目录时显示）
 *    - 左侧 ArrowBack 图标 + "..（上级目录）"
 *    - 下方 Divider 分隔线
 * 4. 子文件夹列表（LazyColumn 可滚动，占满剩余高度）
 *    - 加载中：显示 CircularProgressIndicator
 *    - 无子文件夹：显示"此文件夹没有子文件夹"
 *    - 有子文件夹：每行含 Folder 图标 + 文件夹名称，点击可进入
 * 5. 底部按钮：取消（TextButton）+ 选择此文件夹（Button）
 *
 * ========== 交互逻辑 ==========
 * - 进入子文件夹：点击任意子文件夹行 → currentFolderUri 更新为子文件夹的 URI
 *   → LaunchedEffect 检测到 currentFolderUri 变化 → 重新加载该目录下的子文件夹列表
 * - 返回上级：点击"返回上级"行 → 通过 DocumentFile.parentFile 获取上级目录 URI
 *   → 更新 currentFolderUri（若已在根目录则隐藏此按钮）
 * - 选择确认：点击"选择此文件夹"按钮 → 回调 onFolderSelected(currentFolderUri)
 *   返回当前浏览目录的 URI（而非选中后再确认某一个文件夹，选中即"导航到该目录"）
 * - 取消：关闭弹窗，不做任何选择
 *
 * ========== 调用位置 ==========
 * - PdfConversionScreen.kt 第 403 行：PDF 转换功能中，选择转换后的输出文件夹
 *   传入当前配置的输出文件夹 URI（currentOutputUri），让用户重新选择或确认
 *
 * ========== 注意事项 ==========
 * - currentOutputUri 参数当前未被用于高亮显示（据 unused_code_report.md 记录），
 *   预期用途是显示当前已选文件夹的位置，但目前代码中未实现此功能
 * - 使用 DocumentFile 处理 URI 文件系统，需要 android.documentfile 依赖
 * - 不处理 Android SAF 权限持久化问题，调用方需自行确保对 libraryUri 的访问权限
 *
 * @param libraryUri 库文件夹根目录的 URI（DocumentFile 的 tree URI）
 *                   用户只能在此目录范围内浏览和选择子文件夹
 * @param currentOutputUri 当前已配置的输出文件夹 URI（可选）
 *                          传入后理论上可用于高亮显示，但当前实现中未使用此参数
 *                          保留此参数便于未来扩展
 * @param onFolderSelected 用户点击"选择此文件夹"后的回调
 *                         参数 Uri：当前浏览的文件夹的 URI
 *                         调用方收到后将该 URI 保存为新的输出路径
 * @param onDismiss 用户点击取消或点击弹窗外部时的回调
 *                  用于重置调用方的对话框显示状态
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryFolderPickerDialog(
    libraryUri: Uri,
    currentOutputUri: Uri? = null,
    onFolderSelected: (Uri) -> Unit,
    onDismiss: () -> Unit
) {
    /** Android Context，用于通过 DocumentFile 访问文件系统 */
    val context = LocalContext.current

    // ========== 状态变量 ==========

    /**
     * 当前浏览的文件夹 URI
     * 初始值为 libraryUri（库根目录）
     * 当 libraryUri 发生变化时（key = libraryUri），状态会重置为新的 libraryUri
     */
    var currentFolderUri by remember(libraryUri) { mutableStateOf(libraryUri) }

    /**
     * 当前目录下的子文件夹列表
     * 当 currentFolderUri 变化时重置
     * 通过 DocumentFile.listFiles().filter { isDirectory } 获取
     * 按名称字母顺序排序（不区分大小写）
     */
    var subFolders by remember(currentFolderUri) { mutableStateOf<List<DocumentFile>>(emptyList()) }

    /**
     * 当前路径文本（相对于库根目录）
     * 根目录显示 "/"，子目录显示 "/文件夹名" 或 "/父/子"
     */
    var currentPath by remember { mutableStateOf("") }

    /** 是否可返回上级目录（当前不在根目录时 = true） */
    var canGoBack by remember { mutableStateOf(false) }

    /** 是否正在加载文件夹列表 */
    var isLoading by remember { mutableStateOf(true) }

    // ========== 切换目录时：重新加载子文件夹列表 ==========

    /**
     * LaunchedEffect 监听 currentFolderUri 变化
     * 每次切换目录时重新列出该目录下的所有子文件夹
     *
     * 执行流程：
     * 1. 设置 isLoading = true
     * 2. 通过 DocumentFile.fromTreeUri() 获取当前目录的 DocumentFile 对象
     * 3. 调用 listFiles().filter { it.isDirectory } 获取子文件夹列表
     * 4. 按名称不区分大小写排序（sortedBy { it.name?.lowercase() }）
     * 5. 计算相对于 libraryUri 的路径字符串
     *    - 如果 currentPathSegment == libraryPath（根目录），显示 "/"
     *    - 否则提取相对路径部分（移除 libraryPath 前缀）
     * 6. 判断是否可以返回上级：currentFolderUri != libraryUri
     * 7. 异常时记录 Log.e 并置空列表
     * 8. finally 块设置 isLoading = false
     */
    LaunchedEffect(currentFolderUri) {
        isLoading = true
        try {
            val currentFolder = DocumentFile.fromTreeUri(context, currentFolderUri)
            if (currentFolder != null) {
                // 列出所有子文件夹（只保留 isDirectory = true 的条目）
                val folders = currentFolder.listFiles().filter { it.isDirectory }
                subFolders = folders.sortedBy { it.name?.lowercase() }

                // 计算当前路径（相对于库根目录）
                val libraryFolder = DocumentFile.fromTreeUri(context, libraryUri)
                val libraryPath = libraryFolder?.uri?.lastPathSegment ?: ""
                val currentPathSegment = currentFolder.uri.lastPathSegment ?: ""

                currentPath = if (currentPathSegment == libraryPath) {
                    "/"
                } else {
                    // 提取相对路径：移除 libraryPath 前缀，确保以 "/" 开头
                    val relativePath = currentPathSegment.removePrefix(libraryPath)
                    if (relativePath.startsWith("/")) relativePath else "/$relativePath"
                }

                // 判断是否可返回上级（当前不是库根目录）
                canGoBack = (currentFolderUri != libraryUri)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to list subfolders", e)
            subFolders = emptyList()
        } finally {
            isLoading = false
        }
    }

    // ========== AlertDialog 主体 ==========

    /**
     * Material3 AlertDialog
     * 固定高度 400.dp 以容纳可滚动的文件夹列表
     */
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("选择输出文件夹")
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().height(400.dp)
            ) {
                // ----- 当前路径显示 -----
                /**
                 * 显示格式："当前路径：/xxx"
                 * 根目录时显示 "/"，子目录显示 "/子文件夹名"
                 */
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Folder,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "当前路径：$currentPath",
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1
                    )
                }

                // ----- 返回上级按钮（仅在非根目录时显示） -----
                /**
                 * 点击后通过 DocumentFile.parentFile 获取上级目录的 URI，
                 * 然后更新 currentFolderUri 触发 LaunchedEffect 重新加载
                 */
                if (canGoBack) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                // 返回上级目录
                                val currentFolder = DocumentFile.fromTreeUri(context, currentFolderUri)
                                val parentUri = currentFolder?.parentFile?.uri
                                if (parentUri != null) {
                                    currentFolderUri = parentUri
                                }
                            }
                            .padding(vertical = 12.dp, horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "返回上级",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "..（上级目录）",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Divider()
                }

                // ====== 子文件夹列表区域（三种状态） ======

                // 状态 1：加载中 → 居中显示 CircularProgressIndicator
                if (isLoading) {
                    Box(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }
                // 状态 2：无子文件夹 → 居中显示提示文本
                else if (subFolders.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "此文件夹没有子文件夹",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                // 状态 3：有子文件夹 → LazyColumn 列表
                else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().weight(1f)
                    ) {
                        items(subFolders) { folder ->
                            /**
                             * 每个子文件夹的可点击行
                             * 点击后更新 currentFolderUri 为子文件夹 URI，
                             * 触发 LaunchedEffect 重新加载该目录下的列表
                             *
                             * 每行之间以 Divider 分隔
                             */
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        // 进入该子文件夹
                                        val folderUri = folder.uri
                                        if (folderUri != null) {
                                            currentFolderUri = folderUri
                                        }
                                    }
                                    .padding(vertical = 12.dp, horizontal = 16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Folder,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    text = folder.name ?: "未命名文件夹",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                            Divider()
                        }
                    }
                }
            }
        },
        // ====== 底部按钮 ======
        confirmButton = {
            /**
             * "选择此文件夹"按钮
             * 点击后回调 onFolderSelected(currentFolderUri)，
             * 将当前浏览的目录 URI 返回给调用方
             * 注意：返回的是当前所在目录而非列表中被点击选中的项
             */
            Button(
                onClick = {
                    onFolderSelected(currentFolderUri)
                }
            ) {
                Text("选择此文件夹")
            }
        },
        dismissButton = {
            /**
             * "取消"按钮
             * 点击后调用 onDismiss 关闭弹窗
             */
            TextButton(
                onClick = onDismiss
            ) {
                Text("取消")
            }
        }
    )
}
