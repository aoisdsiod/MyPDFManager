package com.example.pdfmanager.ui.screen.conversion

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.activity.ComponentActivity
import coil.compose.AsyncImage
import com.example.pdfmanager.data.model.ImagePreviewInfo
import com.example.pdfmanager.ui.viewmodel.ConversionViewModel
import androidx.compose.material.icons.Icons
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.painter.ColorPainter
import android.util.Log

/**
 * 【页码选择页面】
 *
 * ── 功能说明 ──
 * 当用户关闭"默认转换所有图片"开关（单选模式）并在 PdfConversionScreen 中选中一个 ZIP 文件后，
 * 系统会先解压该 ZIP 文件到临时目录，然后导航到本页面。
 * 用户在此页面预览 ZIP 中包含的所有图片，并逐个选择要转换为 PDF 的图片页码，
 * 最后点击"开始转换"生成 PDF。
 *
 * ── 调用位置 ──
 * - 通过 Navigation 导航图路由 "page_selection" 跳转到此页面
 * - 在 PdfConversionScreen.kt 中当 extractedImages 不为 null 时触发导航
 *
 * ── 使用场景 ──
 * 用户只需将 ZIP 中的部分图片转换为 PDF，而不是全部。
 * 例如：一个 ZIP 中包含 20 张扫描图片，用户只选第 1、3、5~10 页生成 PDF。
 *
 * ── 布局结构 ──
 *   ┌─ TopAppBar: 返回箭头 + 标题"选择页码（共 N 页）"
 *   ├─ 主体: 4 列网格布局，每个格子是一张图片卡片
 *   │    └─ 卡片: 缩略图 + 页码角标（左上）+ 选中图标（右上）
 *   └─ BottomBar（固定底部）:
 *        └─ Row: [取消] [全选/取消全选] [开始转换 (N/M)]
 *
 * ── 交互逻辑 ──
 * 1. 页面加载时显示 ZIP 中所有图片的缩略图网格
 * 2. 默认全选所有图片（_selectedPages = null 表示全选）
 * 3. 用户点击单张图片可切换其选中状态
 * 4. 点击"全选"→选择所有图片，"取消全选"→清空所有选择
 * 5. 已选中图片卡片有主题色边框 + 右上为绿色勾选图标
 * 6. 未选中图片卡片无边框 + 右上为白色空心圆图标
 * 7. 选中数量为 0 时"开始转换"按钮禁用
 * 8. 点击"开始转换"后触发 ViewModel 的 onPageSelectionConfirm()
 * 9. 转换进度开始后自动 popBackStack() 返回 PdfConversionScreen
 * 10. 点击"取消"或返回箭头会清理预览状态并返回
 *
 * @param navController 导航控制器，用于返回 PdfConversionScreen
 * @param modifier      修饰符，用于外部定制样式
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PageSelectionScreen(
    navController: androidx.navigation.NavController,
    modifier: Modifier = Modifier
) {
    // ── ViewModel 初始化 ───────────────────────────────────────────────
    // 使用 activity 级作用域，与 PdfConversionScreen 共享同一个 ConversionViewModel 实例
    // 这样 extractedImages 等状态可以在两个页面之间共享
    val viewModel: ConversionViewModel = viewModel(
        factory = ConversionViewModel.Factory,
        viewModelStoreOwner = LocalContext.current as ComponentActivity
    )

    // ── 状态收集 ──────────────────────────────────────────────────────
    // ZIP 解压后的图片预览信息列表（null 表示未解压或已清理）
    val extractedImages by viewModel.extractedImages.collectAsStateWithLifecycle()
    // 用户选中的页码集合（null 表示全选，emptySet 表示全不选）
    val selectedPages by viewModel.selectedPages.collectAsStateWithLifecycle()
    // 当前转换进度（不为 null 时说明转换已开始）
    val conversionProgress by viewModel.conversionProgress.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // ── 本地计算属性 ────────────────────────────────────────────────────
    // 图片列表，如果 extractedImages 为 null 则使用空列表
    val imageList = extractedImages ?: emptyList()
    // 本地捕获 selectedPages，避免委托属性的 smart cast 问题
    val sp = selectedPages
    // 已选中的图片数量：null 表示全选 → 总数；否则按集合大小
    val selectedCount = sp?.size ?: imageList.size

    // 【监听】转换进度状态变化：一旦转换开始（conversionProgress != null），自动返回上一页
    LaunchedEffect(conversionProgress) {
        Log.i("PageSelectionScreen", "conversionProgress 变化: $conversionProgress")
        // 当进度状态被设置（非 null）时，说明 onPageSelectionConfirm() 已启动转换
        if (conversionProgress != null) {
            Log.i("PageSelectionScreen", "检测到转换进度，执行 popBackStack()")
            // 返回 PdfConversionScreen，该页面会显示 ConversionProgressDialog
            navController.popBackStack()
        }
    }

    // 【日志】追踪选中状态和图片列表的变化，辅助调试
    LaunchedEffect(selectedPages, extractedImages) {
        Log.i("PageSelectionScreen",
            "状态更新: selectedPages=${sp?.size}, imageList.size=${imageList.size}, selectedCount=$selectedCount")
    }

    // ── Scaffold 页面框架 ─────────────────────────────────────────────
    Scaffold(
        // ── 顶部工具栏 ───────────────────────────────────────────────
        topBar = {
            TopAppBar(
                title = { Text("选择页码（共 ${imageList.size} 页）") },
                navigationIcon = {
                    // 返回按钮：清理预览状态后返回 PdfConversionScreen
                    IconButton(onClick = {
                        viewModel.clearPreview()
                        navController.popBackStack()
                    }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        },
        // ── 底部固定操作栏 ───────────────────────────────────────────
        bottomBar = {
            Surface(
                tonalElevation = 4.dp,    // 色调高度，提供背景层次感
                shadowElevation = 4.dp     // 阴影高度，与上方内容区分
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 【取消按钮】取消选择，清理预览状态并返回
                    Button(
                        onClick = {
                            viewModel.clearPreview()
                            navController.popBackStack()
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    ) {
                        Text("取消")
                    }

                    // 【全选 / 取消全选按钮】切换全选状态
                    TextButton(
                        onClick = {
                            if (sp == null) {
                                // 当前全选 → 取消全选（切换为 none）
                                viewModel.deselectAllPages()
                            } else {
                                // 当前非全选 → 全选
                                viewModel.selectAllPages()
                            }
                        }
                    ) {
                        // 按钮文字根据当前状态动态显示
                        Text(if (sp == null) "取消全选" else "全选")
                    }

                    // 【开始转换按钮】确认当前选中页码，开始生成 PDF
                    Button(
                        onClick = {
                            Log.i("PageSelectionScreen",
                                "开始转换按钮被点击, sp=${sp?.size}, selectedCount=$selectedCount")
                            // 调用 ViewModel 方法启动转换
                            // popBackStack 会在 LaunchedEffect(conversionProgress) 中自动执行
                            viewModel.onPageSelectionConfirm()
                        },
                        // 必须至少选中一页才能点击
                        enabled = selectedCount > 0
                    ) {
                        Text("开始转换 ($selectedCount/${imageList.size})")
                    }
                }
            }
        }
    ) { paddingValues ->
        // ── 主体内容 ──────────────────────────────────────────────────
        if (imageList.isEmpty()) {
            // 图片列表为空时显示加载中
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else {
            // 4 列网格布局，展示所有图片缩略图
            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                modifier = modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),  // 卡片水平间距
                verticalArrangement = Arrangement.spacedBy(4.dp),    // 卡片垂直间距
                contentPadding = PaddingValues(bottom = 90.dp)       // 底部留空，避免被底部栏遮挡
            ) {
                // 使用 ImagePreviewInfo.index 作为 key，确保高效重组
                items(imageList, key = { it.index }) { info ->
                    PageSelectionCard(
                        info = info,
                        // 选中判定：sp == null 表示全选，否则检查 info.index 是否在 sp 集合中
                        isSelected = sp == null || (sp != null && info.index in sp),
                        onClick = { viewModel.togglePageSelection(info.index) }
                    )
                }
            }
        }
    }
}

/**
 * 【单张图片的选择卡片】
 *
 * ── 功能说明 ──
 * 表示 PageSelectionScreen 中网格里的单个图片卡片。
 * 包含：图片缩略图显示、左上角页码角标、右上角选中状态图标。
 *
 * ── 调用位置 ──
 * - 本文件中 PageSelectionScreen 的 LazyVerticalGrid 的 items 块中调用
 *
 * ── 交互逻辑 ──
 * 1. 未选中时：卡片无边框，右上角显示白色半透明空心圆图标
 * 2. 选中时：卡片显示主题色 3dp 边框，右上角显示绿色勾选图标
 * 3. 点击卡片任意区域切换选中状态（通过 onClick 回调）
 * 4. 点击反馈：选中/取消选中时边框和图标即时更新
 *
 * @param info      图片预览信息（包括文件路径、文件名、页码索引等）
 * @param isSelected 当前是否选中该图片
 * @param onClick    点击卡片时的回调（切换该图片的选中状态）
 */
@Composable
fun PageSelectionCard(
    info: ImagePreviewInfo,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    // 选中时边框颜色为主题主色，未选中时透明（无边框）
    val borderColor = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent
    // 选中时边框宽度 3dp，未选中时 0dp
    val borderWidth = if (isSelected) 3.dp else 0.dp

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f) // 正方形卡片，宽高比 1:1
            .clickable { onClick() },
        shape = RoundedCornerShape(6.dp),       // 圆角 6dp
        border = BorderStroke(borderWidth, borderColor), // 动态边框
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp) // 轻微阴影
    ) {
        Box(
            modifier = Modifier.fillMaxSize()
        ) {
            // 【图片缩略图】使用 Coil 的 AsyncImage 异步加载本地图片
            AsyncImage(
                model = info.filePath,               // 图片本地文件路径
                contentDescription = info.fileName,   // 无障碍描述
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,      // 裁剪填充（保持比例，裁掉多余部分）
                placeholder = ColorPainter(Color(0xFFE0E0E0)), // 加载中时显示浅灰色
                error = ColorPainter(Color(0xFFD0D0D0))         // 加载失败时显示灰色
            )

            // 【页码角标】左上角半透明黑色背景 + 白色页码数字
            Surface(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(2.dp),
                shape = RoundedCornerShape(3.dp),
                color = Color.Black.copy(alpha = 0.55f) // 半透明黑色背景
            ) {
                Text(
                    text = "${info.index}",       // 显示 1-based 页码
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White
                )
            }

            // 【选中图标】右上角显示选择状态图标
            Icon(
                imageVector = if (isSelected)
                    Icons.Default.CheckCircle          // 选中：绿色勾选圆圈
                else
                    Icons.Default.RadioButtonUnchecked, // 未选中：白色空心圆
                contentDescription = if (isSelected) "已选中" else "未选中",
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(3.dp),
                tint = if (isSelected)
                    MaterialTheme.colorScheme.primary   // 选中：主题主色
                else
                    Color.White.copy(alpha = 0.7f)      // 未选中：白色半透明
            )
        }
    }
}
