package com.example.pdfmanager.ui.screen.filter

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.pdfmanager.data.model.FilterLogic
import com.example.pdfmanager.data.model.SavedFilter
import com.example.pdfmanager.data.model.TagCategory
import com.example.pdfmanager.data.repository.AppContainer
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.text.style.TextAlign

/**
 * 全屏筛选页面 Composable 函数
 *
 * 功能说明：
 * 本页面是标签筛选的全屏界面，让用户通过标签类别和标签值来筛选 PDF 文件列表。
 * 主要包含三个区域：
 * 1. 顶部标题栏 — 左侧"筛选"标题，右侧"且/或"切换按钮
 * 2. 中间可滚动的类别列表 — 每个类别显示类别名和标签值，支持多选
 *    - 普通类别：遍历 ViewModel 中的 allCategories，每个渲染为 FilterCategoryRow
 *    - 分隔线："── 特殊筛选 ──"
 *    - "无标签"特殊类别：NoTagRow 组件，选中时自动取消所有其他标签
 * 3. 底部固定操作栏 — "重置所有" + "确认筛选"按钮
 *
 * 交互逻辑：
 * - 且/或（AND/OR）切换：改变跨类别筛选的逻辑关系
 * - 无标签（NoTag）：只能单独选中，与普通标签互斥
 * - 三态圆框：○ 全未选 / ● 部分选 / ✓ 全选
 * - 确认后生成 SavedFilter，通过 AppContainer 传递到 AllFilesScreen 应用过滤
 *
 * 调用位置：
 * - 由路由导航系统在用户点击"筛选"按钮时调用
 * - 通常在 AllFilesScreen 中通过导航跳转至本页面
 *
 * @param onBack 返回上一页的回调函数，由导航系统传入
 * @param onConfirm 确认筛选的回调函数，返回 SavedFilter 对象
 * @param viewModel 筛选页 ViewModel，通过 Factory 自动注入 tagRepository
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilterScreen(
    onBack: () -> Unit,
    onConfirm: (SavedFilter) -> Unit,
    viewModel: FilterViewModel = viewModel(factory = FilterViewModel.Factory())
) {
    // ── 收集 ViewModel 状态 ──
    val categories by viewModel.categories.collectAsStateWithLifecycle()
    val selectedTagKeys by viewModel.selectedTagKeys.collectAsStateWithLifecycle()
    val filterLogic by viewModel.filterLogic.collectAsStateWithLifecycle()
    val includeNoTag by viewModel.includeNoTag.collectAsStateWithLifecycle()

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    // ── 页面骨架（Scaffold） ──
    Scaffold(
        topBar = {
            // 顶部标题栏
            TopAppBar(
                title = { Text("筛选") },
                actions = {
                    // 且/或切换按钮
                    OutlinedButton(
                        onClick = { viewModel.toggleFilterLogic() },
                        modifier = Modifier.padding(end = 8.dp)
                    ) {
                        Text(
                            text = if (filterLogic == FilterLogic.AND) "[且]" else "[或]",
                            fontWeight = FontWeight.Bold
                        )
                    }
                },
                scrollBehavior = scrollBehavior
            )
        },
        bottomBar = {
            // 底部固定栏：重置 + 确认按钮
            Surface(tonalElevation = 4.dp) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally)
                ) {
                    // "重置所有"按钮：调用 ViewModel.resetAll() 清空所有选择
                    OutlinedButton(
                        onClick = { viewModel.resetAll() },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("重置所有")
                    }
                    // "确认筛选"按钮：生成 SavedFilter，设置到全局 AppContainer，然后返回
                    Button(
                        onClick = {
                            val savedFilter = viewModel.confirmFilter()
                            AppContainer.setPendingFilterResult(savedFilter)
                            onBack()
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("确认筛选")
                    }
                }
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // 可滚动的类别列表
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                // 遍历普通类别，每个渲染为 FilterCategoryRow
                items(categories, key = { it.id }) { category ->
                    FilterCategoryRow(
                        category = category,
                        categoryState = viewModel.getCategoryState(category),
                        selectedTagKeys = selectedTagKeys,
                        onCategoryClick = { viewModel.toggleCategory(category) },
                        onTagClick = { tagKey -> viewModel.toggleTag(tagKey) }
                    )
                }

                // 分隔线："特殊筛选"
                item {
                    Spacer(modifier = Modifier.height(16.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "── 特殊筛选 ──",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            textAlign = TextAlign.Center
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }

                // "无标签"特殊类别（独立于普通类别之外，互斥逻辑）
                item {
                    NoTagRow(
                        includeNoTag = includeNoTag,
                        onClick = { viewModel.toggleNoTag() }
                    )
                }
            }
        }
    }
}

/**
 * "无标签"特殊类别行 Composable 函数
 *
 * 功能说明：
 * 显示一个特殊筛选选项——"无标签"，用于筛选出没有任何标签的 PDF 文件。
 *
 * 交互规则（与普通标签互斥）：
 * - 选中"无标签"时，自动取消所有其他标签的选中状态
 * - 选中任何普通标签时，自动取消"无标签"的选中状态
 * - "且/或"逻辑切换对"无标签"不生效（因为只能单选）
 * -- includeNoTag = false：显示 ○（未选中状态）
 * -- includeNoTag = true：显示 ✓（选中状态）
 *
 * 调用位置：
 * - FilterScreen 的 LazyColumn 末尾的 item() 中渲染
 *
 * @param includeNoTag 当前是否选中"无标签"筛选
 * @param onClick 点击切换"无标签"状态的回调函数
 * @param modifier 可选的 Modifier
 */
@Composable
fun NoTagRow(
    includeNoTag: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        tonalElevation = 0.dp
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                // 三态圆框（"无标签"只有两种状态：○ 或 ✓）
                Text(
                    text = if (includeNoTag) "✓" else "○",
                    style = MaterialTheme.typography.titleMedium,
                    color = if (includeNoTag) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.clickable { onClick() }
                )

                Spacer(modifier = Modifier.width(8.dp))

                // "无标签"文字
                Text(
                    text = "无标签（仅显示无标签文件）",
                    style = MaterialTheme.typography.titleMedium,
                    color = if (includeNoTag) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.clickable { onClick() }
                )
            }
        }
    }
}
