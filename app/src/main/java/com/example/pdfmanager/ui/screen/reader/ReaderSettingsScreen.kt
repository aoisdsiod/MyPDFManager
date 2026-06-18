/*
 * ============================================================================
 * 阅读设置页面（ReaderSettingsScreen.kt）
 * ============================================================================
 *
 * 【文件功能】
 * 本文件定义了 PDF 阅读器的设置页面，使用 Jetpack Compose 构建。
 * 用户可通过此页面配置阅读器的以下偏好：
 *   1. 翻页方式（Page Mode）：单页模式（single_page）或滚动模式（continuous）
 *   2. 工具栏显示模式（Toolbar Mode）：正常显示/仅显示页码/完全隐藏
 *
 * 所有设置项通过 AppContainer.preferencesManager 的 Flow API 读取和持久化，
 * 数据存储在 SharedPreferences 中，设置变更后通过 Flow 自动通知 ReaderScreenV2 更新。
 *
 * 【调用位置】
 * - app/src/main/java/com/example/pdfmanager/ui/navigation/AppNavGraph.kt（第 162 行）
 *   → composable("reader_settings") { ReaderSettingsScreen(navController = navController) }
 *   通过导航库从任一页面跳转到 "reader_settings" 路由即可打开此页面。
 *   通常从 ReaderScreenV2 顶栏的菜单或详情页（DetailScreen）的入口进入。
 *
 * 【使用场景】
 * - 用户在阅读 PDF 时想切换翻页方式（单页 → 滚动或反之）
 * - 用户想调整工具栏的显示行为（全显示/仅页码/完全隐藏）
 * - 用户在应用设置中配置阅读偏好
 *
 * 【数据流】
 *   SharedPreferences（持久化存储）
 *     ↓ PreferencesManager.getReaderPageModeFlow() / getReaderToolbarModeFlow()
 *     ↓ Flow<String>
 *     ↓ collectAsStateWithLifecycle() → State<String>（Compose 可观察状态）
 *     ↓ UI 渲染 RadioButton 选中状态
 *   用户点击 RadioButton → preferencesManager.setReaderPageMode()/setReaderToolbarMode() → 持久化
 *
 * 【相关文件】
 * - ReaderViewModel.kt：在阅读页面中观察相同的 Preferences Flow，自动响应设置变更
 * - app/src/main/java/com/example/pdfmanager/data/local/PreferencesManager.kt：偏好设置存储层
 * - ReaderScreenV2.kt：阅读器主页面，根据 pageMode 和 toolbarMode 调整 UI 行为
 * ============================================================================
 */

package com.example.pdfmanager.ui.screen.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.example.pdfmanager.data.repository.AppContainer
import kotlinx.coroutines.launch
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack

/**
 * ReaderSettingsScreen - 阅读器设置页面 Composable
 *
 * 【功能描述】
 * 提供阅读器的偏好设置界面，包含翻页方式和工具栏显示模式的 RadioButton 选择器。
 * 用户的每次选择会立即通过 PreferencesManager 持久化到 SharedPreferences，
 * 并可通过 Flow 实时同步到阅读器主页面（ReaderScreenV2）。
 *
 * 【页面结构】
 * ┌─────────────────────────────────────────┐
 * │  ← 返回         阅读设置                 │  ← TopAppBar
 * ├─────────────────────────────────────────┤
 * │  ┌─────────────────────────────────┐    │
 * │  │ 翻页方式                         │    │  ← Card 1
 * │  │ ○ 单页   ● 滚动                  │    │
 * │  └─────────────────────────────────┘    │
 * │  ┌─────────────────────────────────┐    │
 * │  │ 工具栏显示                        │    │  ← Card 2
 * │  │ ● 正常显示  ○ 仅显示页码  ○ 隐藏  │    │
 * │  └─────────────────────────────────┘    │
 * └─────────────────────────────────────────┘
 *
 * 【调用位置】
 * - app/src/main/java/com/example/pdfmanager/ui/navigation/AppNavGraph.kt（第 162 行）
 *
 * 【使用场景】
 * - 点击阅读器底栏设置按钮 → 导航至此页面
 * - 从应用设置入口进入阅读偏好配置
 *
 * @param navController NavController 实例，用于页面导航（返回上一页）
 * @param modifier 可选的 Modifier，用于外部调整布局
 *
 * @return 无返回值，为 Composable 函数
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderSettingsScreen(
    navController: NavController,
    modifier: Modifier = Modifier
) {
    // 获取 Application Context（用于 SharedPreferences）
    val context = LocalContext.current
    // 创建协程作用域，用于执行设置持久化操作
    val coroutineScope = rememberCoroutineScope()

    // ── 通过 Flow 观察当前的翻页方式设置 ──
    // collectAsStateWithLifecycle 自动管理生命周期，页面不可见时停止收集
    val pageMode by AppContainer.preferencesManager.getReaderPageModeFlow()
        .collectAsStateWithLifecycle(initialValue = "single_page")

    // ── 通过 Flow 观察当前的工具栏显示模式设置 ──
    val toolbarMode by AppContainer.preferencesManager.getReaderToolbarModeFlow()
        .collectAsStateWithLifecycle(initialValue = "full")

    // ── 页面布局 ──
    Scaffold(
        topBar = {
            // 顶部标题栏：显示"阅读设置"，左侧返回按钮
            TopAppBar(
                title = { Text("阅读设置") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { paddingValues ->
        // ── 主内容区域（可纵向滚动） ──
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ═══════════════════════════════════════════════════════════════
            // 卡片 1：翻页方式设置
            // ═══════════════════════════════════════════════════════════════
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    // 区域标题
                    Text("翻页方式", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))

                    // 两个选项横向排列
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // "single_page"（单页模式）和 "continuous"（滚动模式）两个选项
                        listOf("single_page" to "单页", "continuous" to "滚动").forEach { (value, label) ->
                            Row(
                                modifier = Modifier
                                    .selectable(
                                        selected = pageMode == value,
                                        onClick = {
                                            // 点击 → 持久化新设置
                                            coroutineScope.launch {
                                                AppContainer.preferencesManager.setReaderPageMode(value)
                                            }
                                        }
                                    )
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // RadioButton（点击事件同上）
                                RadioButton(
                                    selected = pageMode == value,
                                    onClick = {
                                        coroutineScope.launch {
                                            AppContainer.preferencesManager.setReaderPageMode(value)
                                        }
                                    }
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(label)
                            }
                        }
                    }
                }
            }

            // ═══════════════════════════════════════════════════════════════
            // 卡片 2：工具栏显示模式设置
            // ═══════════════════════════════════════════════════════════════
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    // 区域标题
                    Text("工具栏显示", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))

                    // 三个选项横向排列（可横向滚动，防止在小屏上换行）
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // "full"（正常显示）、"page_only"（仅显示页码）、"hidden"（完全隐藏）
                        listOf(
                            "full" to "正常显示",
                            "page_only" to "仅显示页码",
                            "hidden" to "完全隐藏"
                        ).forEach { (value, label) ->
                            Row(
                                modifier = Modifier
                                    .selectable(
                                        selected = toolbarMode == value,
                                        onClick = {
                                            coroutineScope.launch {
                                                AppContainer.preferencesManager.setReaderToolbarMode(value)
                                            }
                                        }
                                    )
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = toolbarMode == value,
                                    onClick = {
                                        coroutineScope.launch {
                                            AppContainer.preferencesManager.setReaderToolbarMode(value)
                                        }
                                    }
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(label)
                            }
                        }
                    }
                }
            }

            // 底部留白
            Spacer(Modifier.height(32.dp))
        }
    }
}
