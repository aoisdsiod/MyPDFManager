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
 * 分享目标选择器（ModalBottomSheet 底部弹出菜单）
 *
 * ========== 功能概述 ==========
 * 以 Material3 ModalBottomSheet（底部弹窗）的形式，列出 share/ 文件夹下所有一级子文件夹，
 * 让用户选择一个目标文件夹后将文件分享到该目录。
 * 同时支持在 share/ 下新建一个一级子文件夹。
 *
 * ========== UI 结构 ==========
 * 1. 顶部标题："选择分享目标"
 * 2. "share/ 根目录"行 —— 选中即表示分享到 share/ 根目录
 * 3. 分隔线
 * 4. 子文件夹列表（LazyColumn 可滚动）
 *    - 每行左侧是文件夹图标，右侧是文件夹名称
 *    - 点击时切换选中状态（再次点击取消选中）
 * 5. "新建子文件夹"行 —— 点击弹出 AlertDialog 输入名称后创建
 * 6. 底部按钮：取消（OutlinedButton）+ 确认（Button，仅选中后启用）
 *
 * ========== 交互逻辑 ==========
 * - 选中/取消：点击同一项切换选中（单选逻辑，但代码中实际允许多选——需注意此行为）
 *   → 实际上重复点击同一文件夹会切换 null/<uri>，不同文件夹之间不会互斥取消，但
 *     最后点击的文件夹 uri 会被记录，确认时只传递最后选中的 uri。
 * - 新建子文件夹：通过 ShareRepository.createShareSubFolder() 创建，成功后刷新列表
 * - 确认按钮：仅在 selectedFolderUri != null 时可用
 * - 取消按钮：直接关闭 BottomSheet
 *
 * ========== 调用位置 ==========
 * - DetailScreen.kt 第 323 行：文件详情页"分享此文件"按钮
 *   （见 DetailScreen.kt 第 48 行注释：集成 ShareTargetPicker BottomSheet）
 *   入口变量名 showShareTargetPicker，配合 DetailViewModel.shareToDirectory() 使用
 * - SettingsScreen.kt 第 605 行：设置页面"一键分享"功能
 *   入口变量名 showShareTargetPicker（见第 598-605 行）
 *   两个页面共用此组件，逻辑一致
 *
 * ========== 相关组件 ==========
 * - ExportShareTargetPicker（同包下的另一个组件）：使用 SAF 系统文件选择器让用户选择导出目录，
 *   与本组件的区别是本组件仅限 share/ 目录下的子文件夹，而 ExportShareTargetPicker 可自由选择
 *
 * ========== 数据来源 ==========
 * - shareRepository = AppContainer.shareRepository（单例）
 * - getShareSubFolders()：获取 share/ 下的所有子文件夹，返回 List<Pair<文件夹名, URI>>
 * - getShareFolderUri()：获取 share/ 根目录的 URI
 * - createShareSubFolder(name)：在 share/ 下创建指定名称的子文件夹，返回新文件夹 URI
 *
 * @param onTargetSelected 用户确认选择目标目录后的回调
 *                          参数 targetUri: String — 选中文件夹的 URI 字符串
 *                          调用方收到后执行文件复制/分享操作
 * @param onDismiss 用户点击取消或点击弹窗外部区域关闭时的回调
 *                   用于重置调用方的 showShareTargetPicker 状态为 false
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareTargetPicker(
    onTargetSelected: (targetUri: String) -> Unit,
    onDismiss: () -> Unit
) {
    // ========== 单例仓库引用 ==========
    /** ShareRepository 单例，用于获取/创建 share/ 目录下的子文件夹 */
    val shareRepository = com.example.pdfmanager.data.repository.AppContainer.shareRepository
    /** 协程作用域，用于新建文件夹时的异步操作 */
    val coroutineScope = rememberCoroutineScope()

    // ========== 状态变量 ==========
    /** 当前选中的文件夹 URI（null 表示未选中任何文件夹） */
    var selectedFolderUri by remember { mutableStateOf<String?>(null) }

    /**
     * 子文件夹列表，每个元素为 (文件夹名称, URI字符串) 的 Pair
     * 初始为空列表，组件挂载后通过 LaunchedEffect 异步加载
     */
    var subFolders by remember { mutableStateOf(emptyList<Pair<String, String>>()) }

    /** 是否显示"新建子文件夹"对话框 */
    var showCreateDialog by remember { mutableStateOf(false) }

    /** 新建文件夹输入框中的文本 */
    var newFolderName by remember { mutableStateOf("") }

    // ========== 组件挂载时：加载子文件夹列表 ==========
    /**
     * LaunchedEffect(Unit)：组件首次进入 Composition 时执行一次
     * 异步调用 shareRepository.getShareSubFolders() 获取 share/ 下的所有子文件夹，
     * 将其 name 和 uri.toString() 转换为 Pair 列表存入 subFolders 状态
     */
    LaunchedEffect(Unit) {
        coroutineScope.launch {
            subFolders = shareRepository.getShareSubFolders().map { (name, uri) ->
                name to uri.toString()
            }
        }
    }

    // ========== ModalBottomSheet 主体 ==========
    /**
     * Material3 的 ModalBottomSheet 组件
     * skipPartiallyExpanded = true：展开时直接全屏高度，不保留中间折叠态
     */
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            // ----- 标题 -----
            Text(
                text = "选择分享目标",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(vertical = 8.dp)
            )

            // ====== 异步加载 share/ 根目录 URI ======
            /** share/ 根目录的 URI 字符串（异步获取） */
            var shareUri by remember { mutableStateOf<String?>(null) }
            LaunchedEffect(Unit) {
                shareUri = shareRepository.getShareFolderUri()?.toString()
            }

            // ----- "share/ 根目录"行 -----
            /**
             * 可点击行，选中后 selectedFolderUri 设为 shareUri
             * 再次点击则取消选中（设为 null）
             * 选中时图标和文字颜色变为 primary（主题色），否则为 onSurface
             */
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        selectedFolderUri = if (selectedFolderUri == shareUri) null else shareUri
                    }
                    .then(
                        // 注：两分支 Modifier 相同，此处 padding 未根据选中状态变化
                        // 当前代码逻辑上恒等于 Modifier.padding(vertical = 12.dp)
                        if (selectedFolderUri == shareUri)
                            Modifier.padding(vertical = 12.dp)
                        else
                            Modifier.padding(vertical = 12.dp)
                    ),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 文件夹图标（选中时主题色，否则默认色）
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
                // 文件夹名称（颜色跟随选中状态）
                Text(
                    text = "share/ 根目录",
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (selectedFolderUri == shareUri)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.onSurface
                )
            }

            // ----- 分隔线 -----
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            // ====== 子文件夹列表（LazyColumn 可滚动） ======
            LazyColumn(
                modifier = Modifier.weight(1f, fill = false)
            ) {
                items(subFolders) { (name, uri) ->
                    /**
                     * 每个子文件夹的可点击行
                     * 交互逻辑同"share/ 根目录"行：点击切换选中/取消
                     */
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                selectedFolderUri = if (selectedFolderUri == uri) null else uri
                            }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
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

            // ----- "新建子文件夹"行 -----
            /**
             * 点击后弹出 AlertDialog，让用户输入文件夹名称
             * 输入非空文本并点击"创建"后，调用 shareRepository.createShareSubFolder()
             * 创建成功后重新加载子文件夹列表
             */
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showCreateDialog = true }
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "新建子文件夹",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            // ====== 底部按钮区 ======
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                /**
                 * 取消按钮（OutlinedButton）
                 * 点击时调用 onDismiss 关闭 BottomSheet
                 */
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("取消")
                }

                /**
                 * 确认按钮（Button）
                 * enabled = selectedFolderUri != null：仅在有选中目标时才可点击
                 * 点击时调用 onTargetSelected(uri) 将选中的文件夹 URI 传回调用方
                 */
                Button(
                    onClick = {
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

        // ====== 新建子文件夹对话框（AlertDialog） ======
        /**
         * 当 showCreateDialog = true 时渲染此 AlertDialog
         * 包含一个 OutlinedTextField 用于输入文件夹名称
         *
         * 确认逻辑：
         * - 验证 newFolderName 非空
         * - 调用 shareRepository.createShareSubFolder(newFolderName)
         * - 成功后关闭对话框、清空输入框、重新加载子文件夹列表
         *
         * 取消逻辑：
         * - 关闭对话框、清空输入框
         */
        if (showCreateDialog) {
            AlertDialog(
                onDismissRequest = { showCreateDialog = false },
                title = { Text("新建子文件夹") },
                text = {
                    OutlinedTextField(
                        value = newFolderName,
                        onValueChange = { newFolderName = it },
                        label = { Text("文件夹名称") },
                        singleLine = true
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            if (newFolderName.isNotBlank()) {
                                coroutineScope.launch {
                                    val newUri = shareRepository.createShareSubFolder(newFolderName)
                                    showCreateDialog = false
                                    newFolderName = ""
                                    if (newUri != null) {
                                        // 创建成功，重新加载列表
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
