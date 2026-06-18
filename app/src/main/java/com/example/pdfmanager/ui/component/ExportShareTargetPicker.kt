package com.example.pdfmanager.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * 【导出分享专用目标选择器（BottomSheet 底部弹窗）】
 *
 * ── 功能说明 ──
 * 本组件是一个以 ModalBottomSheet 形式呈现的文件夹选择界面，专门用于导出分享场景。
 * 它展示 share/ 目录下的所有子文件夹列表（包含根目录本身），
 * 用户点击某个文件夹可将其选中或取消选中，点击底部"确认"按钮触发导出回调。
 *
 * ── 调用位置 ──
 * - com.example.pdfmanager.ui.screen.library.LibraryScreen.kt → 点击"导出分享"按钮时弹出
 *
 * ── 使用场景 ──
 * 用户需要在 PDF 管理应用中，将文件快速导出并分享到指定的目标文件夹。
 * 例如：将 PDF 文件发送到 share/ 目录下的某个分类子目录中。
 *
 * ── 交互逻辑 ──
 * 1. 页面加载时，通过 shareRepository 异步获取 share/ 根目录 URI 和子文件夹列表
 * 2. 用户点击任一文件夹（根目录或子文件夹），该文件夹被高亮选中（再次点击取消）
 * 3. 只有选中了文件夹后，"确认"按钮才可点击
 * 4. 点击"确认"触发 onTargetSelected 回调，将选中的文件夹 URI 传回调用方
 * 5. 点击"取消"或下拉关闭 BottomSheet，触发 onDismiss 回调
 * 6. 底部还隐藏有新建子文件夹对话框，点击"+"按钮可创建新子文件夹（UI 以 Row(Modifier.clickable) 形式隐式调用，
 *    当前代码中新建功能通过 AlertDialog 实现，但触发新建的 "+" 按钮并未显式渲染在 UI 上，
 *    实际可通过在文件夹列表下方添加一个"新建文件夹"行来触发 showCreateDialog = true）
 *
 * @param onTargetSelected 用户选择目标文件夹后的回调函数，参数为选中文件夹的 URI 字符串
 * @param onDismiss        关闭 BottomSheet 时的回调函数（点击取消或下滑时触发）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportShareTargetPicker(
    onTargetSelected: (targetUri: String) -> Unit,
    onDismiss: () -> Unit
) {
    // 通过全局容器获取 ShareRepository 实例，用于读取/创建 share/ 目录下的子文件夹
    val shareRepository = com.example.pdfmanager.data.repository.AppContainer.shareRepository
    val coroutineScope = rememberCoroutineScope()

    // 【状态定义】

    // 当前选中的文件夹 URI（null 表示未选中任何文件夹）
    var selectedFolderUri by remember { mutableStateOf<String?>(null) }

    // share/ 目录下的子文件夹列表，每个元素为 (文件夹名称, URI字符串) 的键值对
    var subFolders by remember { mutableStateOf(emptyList<Pair<String, String>>()) }

    // 是否显示"新建子文件夹"对话框
    var showCreateDialog by remember { mutableStateOf(false) }

    // "新建子文件夹"对话框中输入的文件夹名称
    var newFolderName by remember { mutableStateOf("") }

    // 【初始化】组件首次渲染时，异步加载 share/ 下的所有子文件夹列表
    LaunchedEffect(Unit) {
        coroutineScope.launch {
            // 调用 Repository 获取子文件夹列表，将 DocumentFile URI 转为字符串
            subFolders = shareRepository.getShareSubFolders().map { (name, uri) ->
                name to uri.toString()
            }
        }
    }

    // ── 主体：ModalBottomSheet 底部弹窗 ─────────────────────────────────
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true) // 跳过半展开，直接全屏展开
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            // 【标题】
            Text(
                text = "选择文件夹导出分享",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(vertical = 8.dp)
            )

            // 【异步加载】share/ 根目录的 URI
            var shareUri by remember { mutableStateOf<String?>(null) }
            LaunchedEffect(Unit) {
                shareUri = shareRepository.getShareFolderUri()?.toString()
            }

            // ── share/ 根目录行 ─────────────────────────────────────────
            // 用户点击"share/ 根目录"可将其作为导出目标
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        // 点击切换选中状态：如果已是当前选中则取消，否则选中
                        selectedFolderUri = if (selectedFolderUri == shareUri) null else shareUri
                    }
                    .then(
                        if (selectedFolderUri == shareUri)
                            Modifier.padding(vertical = 12.dp)
                        else
                            Modifier.padding(vertical = 12.dp)
                    ),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 文件夹图标（选中时颜色为主题主色）
                Icon(
                    imageVector = Icons.Default.Folder,
                    contentDescription = null,
                    tint = if (selectedFolderUri == shareUri)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                // 根目录名称
                Text(
                    text = "share/ 根目录",
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (selectedFolderUri == shareUri)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.onSurface
                )
            }

            // 分隔线
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            // ── 子文件夹列表 ───────────────────────────────────────────
            // 使用 LazyColumn 展示所有子文件夹，支持滚动
            LazyColumn(
                modifier = Modifier.weight(1f, fill = false)
            ) {
                items(subFolders) { (name, uri) ->
                    // 每个子文件夹行
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                // 点击切换选中状态
                                selectedFolderUri = if (selectedFolderUri == uri) null else uri
                            }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 文件夹图标
                        Icon(
                            imageVector = Icons.Default.Folder,
                            contentDescription = null,
                            tint = if (selectedFolderUri == uri)
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        // 文件夹名称
                        Text(
                            text = name,
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (selectedFolderUri == uri)
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }

            // ── 底部按钮区：取消 + 确认 ─────────────────────────────────
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 【取消按钮】关闭 BottomSheet
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("取消")
                }
                // 【确认按钮】确认选择的目标文件夹
                // 只有选中了某个文件夹后才可点击
                Button(
                    onClick = {
                        // 将选中的文件夹 URI 通过回调传回调用方
                        selectedFolderUri?.let { uri ->
                            onTargetSelected(uri)
                        }
                    },
                    enabled = selectedFolderUri != null,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("确认")
                }
            }
        }

        // ── 新建子文件夹对话框 ─────────────────────────────────────────
        // 当 showCreateDialog = true 时弹出，用户可输入名称创建新子文件夹
        if (showCreateDialog) {
            AlertDialog(
                onDismissRequest = { showCreateDialog = false },
                title = { Text("新建子文件夹") },
                text = {
                    // 文本输入框，接收用户输入的新文件夹名称
                    OutlinedTextField(
                        value = newFolderName,
                        onValueChange = { newFolderName = it },
                        label = { Text("文件夹名称") },
                        singleLine = true
                    )
                },
                confirmButton = {
                    // 【创建按钮】确认创建子文件夹
                    Button(
                        onClick = {
                            if (newFolderName.isNotBlank()) {
                                coroutineScope.launch {
                                    // 调用 Repository 在 share/ 下创建新子文件夹
                                    val newUri = shareRepository.createShareSubFolder(newFolderName)
                                    showCreateDialog = false
                                    newFolderName = ""
                                    if (newUri != null) {
                                        // 创建成功后重新加载子文件夹列表
                                        subFolders = shareRepository.getShareSubFolders()
                                            .map { (name, uri) -> name to uri.toString() }
                                    }
                                }
                            }
                        }
                    ) {
                        Text("创建")
                    }
                },
                dismissButton = {
                    // 【取消按钮】关闭对话框，不创建
                    TextButton(
                        onClick = {
                            showCreateDialog = false
                            newFolderName = ""
                        }
                    ) {
                        Text("取消")
                    }
                }
            )
        }
    }
}
