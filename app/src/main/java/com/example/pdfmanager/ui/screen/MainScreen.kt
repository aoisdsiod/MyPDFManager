package com.example.pdfmanager.ui.screen

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.example.pdfmanager.data.repository.AppContainer
import com.example.pdfmanager.ui.screen.allfiles.AllFilesScreen
import com.example.pdfmanager.ui.screen.favorites.FavoritesScreen
import com.example.pdfmanager.ui.screen.settings.SettingsScreen
import com.example.pdfmanager.ui.screen.tagmanagement.TagManagementScreen

/**
 * 主屏幕 - 底部三 Tab 导航容器
 * 
 * 功能说明：
 * 1. 提供底部导航栏（收藏、全部文件、设置三个 Tab）
 * 2. 管理 Tab 切换和页面内容（通过 AppContainer.selectedTab 状态）
 * 3. 处理返回键逻辑（非"全部文件" Tab 回到"全部文件"，"全部文件" Tab 弹出退出确认）
 * 4. 管理标签管理页面的显示/隐藏（通过 SettingsScreen 跳转）
 * 
 * Tab 布局：
 * - Tab 0「收藏」：FavoritesScreen（导航需传入 navController）
 * - Tab 1「全部文件」：AllFilesScreen（核心页面）
 * - Tab 2「设置」：SettingsScreen（可跳转到标签管理页面）
 * 
 * 交互逻辑：
 * - 返回键：Tab 0/2 → 切回 Tab 1；Tab 1 → 弹出退出确认对话框
 * - 底部导航栏：点击切换 Tab
 * - 设置页"标签管理"按钮：显示 TagManagementScreen（全屏覆盖）
 * 
 * 调用位置：
 * - AppNavGraph.kt "main" route 的 Composable 内容
 * 
 * @param navController 导航控制器（用于跳转到详情页）
 * @param onRequestLibrary SAF 库文件夹选择回调（传递给 AllFilesScreen 和 SettingsScreen）
 */
@Composable
fun MainScreen(
    navController: NavController,
    onRequestLibrary: () -> Unit = {}
) {
    // 从 AppContainer 获取当前选中的 Tab（跨页面共享状态）
    val selectedTab by AppContainer.selectedTab.collectAsStateWithLifecycle()
    var showTagManagement by remember { mutableStateOf(false) }
    var showExitDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current

    // ── 返回键处理逻辑 ──
    BackHandler {
        when (selectedTab) {
            0, 2 -> AppContainer.selectedTab.value = 1  // 非"全部文件" Tab 返回"全部文件"
            1 -> showExitDialog = true                     // "全部文件" Tab 弹出退出确认
        }
    }

    // ── 退出确认对话框 ──
    if (showExitDialog) {
        AlertDialog(
            onDismissRequest = { showExitDialog = false },
            title = { Text("退出应用") },
            text = { Text("是否确认退出 数据库 ？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showExitDialog = false
                        (context as? android.app.Activity)?.finish()  // 结束后台 Activity
                    }
                ) { Text("确认") }
            },
            dismissButton = {
                TextButton(onClick = { showExitDialog = false }) { Text("取消") }
            }
        )
    }

    // ── 标签管理页面（全屏覆盖） ──
    if (showTagManagement) {
        TagManagementScreen(
            onBack = { showTagManagement = false }
        )
    } else {
        // ── 主界面（底部导航 + 内容区域） ──
        Scaffold(
            bottomBar = {
                NavigationBar {
                    // Tab 0: 收藏
                    NavigationBarItem(
                        selected = selectedTab == 0,
                        onClick = { AppContainer.selectedTab.value = 0 },
                        icon = { Icon(Icons.Default.Bookmark, contentDescription = null) },
                        label = { Text("收藏") }
                    )
                    // Tab 1: 全部文件（默认 Tab）
                    NavigationBarItem(
                        selected = selectedTab == 1,
                        onClick = { AppContainer.selectedTab.value = 1 },
                        icon = { Icon(Icons.Default.Folder, contentDescription = null) },
                        label = { Text("全部文件") }
                    )
                    // Tab 2: 设置
                    NavigationBarItem(
                        selected = selectedTab == 2,
                        onClick = { AppContainer.selectedTab.value = 2 },
                        icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                        label = { Text("设置") }
                    )
                }
            }
        ) { paddingValues ->
            // 根据选中的 Tab 显示对应的页面
            when (selectedTab) {
                0 -> FavoritesScreen(
                    navController = navController,
                    modifier = Modifier.padding(paddingValues)
                )
                1 -> AllFilesScreen(
                    onRequestLibrary = onRequestLibrary,
                    navController = navController,
                    modifier = Modifier.padding(paddingValues)
                )
                2 -> SettingsScreen(
                    navController = navController,
                    onRequestLibrary = onRequestLibrary,
                    onNavigateToTagManagement = { showTagManagement = true },
                    modifier = Modifier.padding(paddingValues)
                )
            }
        }
    }
}
