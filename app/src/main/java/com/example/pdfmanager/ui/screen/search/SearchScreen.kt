package com.example.pdfmanager.ui.screen.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.example.pdfmanager.data.model.PdfFile
import com.example.pdfmanager.ui.component.PdfThumbnail
import kotlinx.coroutines.delay

/**
 * 搜索页面（全屏二级页面）
 *
 * ## 功能说明
 * 1. 提供全屏搜索界面，顶部显示搜索栏，自动聚焦并弹出软键盘
 * 2. 用户输入搜索关键词（支持数字文件名搜索），实时显示匹配结果
 * 3. 搜索结果以列表形式展示，样式与 [AllFilesScreen] 的列表模式保持一致
 * 4. 点击搜索结果项跳转到 PDF 详情页（`detail/{fileId}`），此时底部导航隐藏
 * 5. 清空搜索框或未输入时显示空状态提示"输入数字搜索文件名"
 * 6. 搜索结果为空时显示"未找到匹配的文件"
 *
 * ## UI 结构
 * ```
 * Scaffold
 * └── TopAppBar
 *     ├── 返回按钮（ArrowBack Icon）
 *     └── OutlinedTextField（搜索输入框，自动聚焦）
 * └── Body
 *     ├── 空状态（query 为空）：居中提示"输入数字搜索文件名"
 *     ├── 无结果状态（results 为空）：居中提示"未找到匹配的文件"
 *     └── 搜索结果列表（LazyColumn）
 *         └── SearchResultItem（卡片式列表项×N）
 *             ├── PdfThumbnail（缩略图，48×64dp）
 *             ├── 文件名（bodyLarge 样式）
 *             └── 标签行（bodySmall 样式，灰色）
 * ```
 *
 * ## 交互流程
 * 1. 页面加载 → 自动聚焦搜索框（150ms 延迟后）→ 弹出软键盘
 * 2. 用户输入 → [SearchViewModel.onQueryChange] 触发防抖搜索（300ms）
 * 3. 搜索结果更新 → LazyColumn 渲染搜索结果列表
 * 4. 点击结果项 → [NavController.navigate] 跳转到 `detail/{pdf.id}` 详情页
 * 5. 点击返回按钮 → [onBack] 回调返回上一页
 *
 * ## 调用位置
 * - [AppNavGraph.kt]（第87-90行）：在 composable("search") 路由中创建并传入 [onBack] 和 [navController]
 * - [AllFilesScreen.kt]（第300行）：用户点击搜索图标按钮时通过 `navController.navigate("search")` 导航至此
 *
 * ## 使用场景
 * - 用户在"全部文件"页面点击搜索图标（🔍），进入全屏搜索页
 * - 用户输入 PDF 文件编号（纯数字），实时筛选匹配的文件
 * - 用户点击搜索结果打开文件详情进行查看或编辑
 *
 * @param onBack 返回上一页的回调函数（由 [AppNavGraph.kt] 传入 `navController.popBackStack()`）
 * @param navController 导航控制器，用于跳转到详情页（`detail/{fileId}`）
 * @param viewModel 搜索页 ViewModel，通过 [SearchViewModel.Factory] 创建，管理搜索关键词和结果状态
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    onBack: () -> Unit,
    navController: NavController,
    viewModel: SearchViewModel = viewModel(factory = SearchViewModel.Factory())
) {
    // ── 收集 ViewModel 中的状态 ──
    // query：当前搜索关键词（String），由用户输入驱动
    // results：当前搜索结果列表（List<PdfFile>），由 ViewModel 防抖搜索后更新
    val query by viewModel.query.collectAsStateWithLifecycle()
    val results by viewModel.results.collectAsStateWithLifecycle()

    // FocusRequester：用于编程式请求输入框焦点
    val focusRequester = remember { FocusRequester() }
    // SoftwareKeyboardController：用于编程式弹出/隐藏软键盘
    val keyboardController = LocalSoftwareKeyboardController.current

    // ── 自动聚焦搜索框并弹出软键盘 ──
    // 使用 LaunchedEffect(Unit) 在组合进入时执行一次
    // 延迟 150ms 确保 UI 已渲染完毕，然后请求焦点并弹出键盘
    LaunchedEffect(Unit) {
        delay(150)
        focusRequester.requestFocus()
        keyboardController?.show()
    }

    // ── 页面脚手架 ──
    Scaffold(
        // 顶部应用栏：返回按钮 + 搜索输入框
        topBar = {
            TopAppBar(
                navigationIcon = {
                    // 返回按钮：点击触发 onBack 回调（popBackStack）
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
                title = {
                    // 搜索输入框：受控组件，值由 ViewModel 的 query 驱动
                    OutlinedTextField(
                        value = query,
                        onValueChange = { viewModel.onQueryChange(it) },
                        placeholder = { Text("输入数字搜索文件名") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester)
                    )
                }
            )
        }
    ) { paddingValues ->
        // ── 页面主体内容 ──
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when {
                // 状态 1：搜索框为空 → 显示空状态提示
                query.isEmpty() -> {
                    Text(
                        text = "输入数字搜索文件名",
                        textAlign = TextAlign.Center,
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
                // 状态 2：搜索框有内容但无匹配结果 → 显示无结果提示
                results.isEmpty() -> {
                    Text(
                        text = "未找到匹配的文件",
                        textAlign = TextAlign.Center,
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
                // 状态 3：有搜索结果 → 显示列表
                else -> {
                    // 使用 LazyColumn 展示结果列表，与 AllFilesScreen 列表模式样式一致
                    LazyColumn(
                        contentPadding = PaddingValues(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // 使用 pdf.id 作为 key 保证高效 diff
                        items(results, key = { it.id }) { pdf ->
                            SearchResultItem(
                                pdf = pdf,
                                // 点击跳转到详情页，路由格式：detail/{fileId}
                                onClick = { navController.navigate("detail/${pdf.id}") }
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 搜索结果列表项（卡片式，与 [AllFilesScreen] 的列表模式样式保持一致）
 *
 * ## 功能说明
 * 1. 以卡片形式展示单个 PDF 文件的搜索结果
 * 2. 左侧显示 PDF 缩略图（[PdfThumbnail] 组件，尺寸 48dp × 64dp）
 * 3. 右侧显示文件名（主标题）和标签列表（副标题，灰色）
 * 4. 点击整个卡片触发跳转到详情页
 *
 * ## UI 结构
 * ```
 * Card（可点击，1dp 阴影）
 * └── Row
 *     ├── PdfThumbnail（48dp × 64dp）
 *     ├── Spacer（12dp 间距）
 *     └── Column
 *         ├── Text（pdf.name，bodyLarge 样式）—— 文件名
 *         └── Text（标签连接字符串，bodySmall 样式，灰色）—— 只有当标签非空时显示
 * ```
 *
 * ## 调用位置
 * - [SearchScreen.kt]（第127-130行）：在 [LazyColumn] 的 items 闭包中为每个搜索结果创建
 *
 * ## 使用场景
 * - 用户在搜索页输入关键词后，每个匹配的文件以该列表项呈现
 *
 * @param pdf PDF 文件数据模型，包含文件名、标签、URI 等信息
 * @param onClick 点击卡片的回调函数，由调用方传入 `navController.navigate("detail/${pdf.id}")`
 */
@Composable
fun SearchResultItem(
    pdf: PdfFile,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier.padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 左侧：PDF 缩略图（复用 PdfThumbnail 组件）
            PdfThumbnail(
                pdfFile = pdf,
                modifier = Modifier.size(width = 48.dp, height = 64.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            // 右侧：文件名 + 标签
            Column {
                // 文件名：使用 bodyLarge 样式突出显示
                Text(text = pdf.name, style = MaterialTheme.typography.bodyLarge)
                // 标签：只有当文件有标签时才显示，标签之间用空格分隔
                if (pdf.tags.isNotEmpty()) {
                    Text(
                        text = pdf.tags.joinToString(" ") { it.value },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
