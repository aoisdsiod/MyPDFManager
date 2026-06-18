package com.example.pdfmanager.ui.screen.allfiles

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 库文件夹选择引导屏幕（欢迎页）
 *
 * ## 功能描述
 * 这是应用首次启动或未选择库文件夹时显示的引导界面。它向用户展示欢迎信息，
 * 并提供一个"选择库文件夹"按钮，引导用户选择 PDF 文件的存放路径。
 *
 * ## 调用位置
 * - [com.example.pdfmanager.ui.screen.allfiles.AllFilesScreen]：当尚未选择库文件夹时，
 *   会优先显示本屏幕而非文件列表。由 AppContainer.isLibrarySelected 状态控制切换逻辑。
 *
 * ## 使用场景
 * - 应用首次安装启动时，用户还未选择 PDF 管理文件夹
 * - 用户主动清除了库文件夹设置后
 * - 用户添加的库文件夹路径异常或不可达时
 *
 * @param onRequestLibrary 点击"选择库文件夹"按钮时的回调函数。
 *                         上层调用方（通常是 AllFilesScreen）会在此回调中弹出
 *                         系统文件夹选择器（如 SAF 的 ACTION_OPEN_DOCUMENT_TREE），
 *                         让用户选择一个目录作为 PDF 库文件夹。
 *                         选择结果最终会通过 AppContainer.setLibraryUri() 持久化到 SharedPreferences 中。
 * @param modifier 可选的 Modifier，用于在外层组合该组件时控制布局、边距等样式。
 *                 默认为 Modifier，不做额外修饰。
 */
@Composable
fun LibrarySetupScreen(
    onRequestLibrary: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "欢迎使用 良辰的数据库\n这是一个PDF文件管理器\n更多功能请自行探索",
            style = MaterialTheme.typography.headlineMedium
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "请选择一个库文件夹",
            style = MaterialTheme.typography.bodyLarge
        )
        Spacer(modifier = Modifier.height(32.dp))
        Button(
            onClick = onRequestLibrary,
            modifier = Modifier.padding(horizontal = 32.dp)
        ) {
            Text("选择库文件夹")
        }
    }
}
