package com.example.pdfmanager.ui.component

import android.net.Uri
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.LocalImageLoader
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import coil.size.Scale
import com.example.pdfmanager.data.local.ThumbnailGenerator
import com.example.pdfmanager.data.model.PdfFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * PDF 缩略图组件
 *
 * ====== 功能说明 ======
 * 用于在列表/网格视图中显示 PDF 文件的封面缩略图。
 * 缩略图由 ThumbnailGenerationService 在后台提前生成并缓存到磁盘，
 * 本组件仅负责 读取缓存 → 显示图片，不负责生成缩略图。
 *
 * ====== 加载流程（三级状态） ======
 * 1. isLoading = true  → 正在异步查询缩略图缓存 → 显示转圈动画
 * 2. loadError = true  → 缓存未命中（缩略图尚未生成） → 显示文件名占位文本
 * 3. thumbnailUri != null → 缓存命中 → 通过 Coil 加载显示图片
 *
 * ====== 容错逻辑 ======
 * - 当发现缩略图文件已存在但数据库标记 thumbnailGenerated ≠ 1 时，自动修正为 1
 * - 当发现缩略图文件不存在但数据库标记 thumbnailGenerated ≠ 0 时，自动重置为 0
 *   （修复因异常中断导致的状态不一致问题）
 *
 * ====== 调用位置 ======
 * - AllFilesScreen.kt           → 全部文件列表/网格视图
 * - DetailScreen.kt             → 详情页顶部缩略图展示
 * - SearchScreen.kt             → 搜索结果列表
 * - FavoriteFolderContentScreen.kt → 收藏夹内容列表
 *
 * ====== 使用场景 ======
 * - 列表视图（每行一个文件）：传 48.dp × 64.dp 的缩略图尺寸
 * - 网格视图（每行多个文件）：根据 ThumbSize 枚举传递对应尺寸（正方形）
 * - 详情页：通常传较大尺寸
 *
 * @param pdfFile PDF 文件数据模型（包含 id、name、uri、thumbnailGenerated 等字段）
 * @param modifier Compose 修饰符，用于调整布局位置、大小等
 * @param thumbnailWidthDp Coil 请求的目标宽度（dp），影响缓存 key，默认 720.dp
 * @param thumbnailHeightDp Coil 请求的目标高度（dp），影响缓存 key，默认 720.dp
 * @return 无返回值（Composable 函数直接渲染界面）
 */
@Composable
fun PdfThumbnail(
    pdfFile: PdfFile,
    modifier: Modifier = Modifier,
    thumbnailWidthDp: androidx.compose.ui.unit.Dp = 720.dp,  // 默认 720.dp（保持向后兼容）
    thumbnailHeightDp: androidx.compose.ui.unit.Dp = 720.dp,  // 默认 720.dp（保持向后兼容）
) {
    val context = LocalContext.current

    // ✅ 直接尝试获取缩略图 URI（不依赖 thumbnailGenerated 标志位）
    var thumbnailUri by remember(pdfFile.id) { mutableStateOf<android.net.Uri?>(null) }
    var loadError by remember(pdfFile.id) { mutableStateOf(false) }
    var isLoading by remember(pdfFile.id) { mutableStateOf(true) }

    // 异步获取缩略图 URI
    LaunchedEffect(pdfFile.id) {
        isLoading = true
        loadError = false

        // 在 IO 线程执行，避免阻塞主线程
        val cachedUri = withContext(Dispatchers.IO) {
            try {
                ThumbnailGenerator.getCachedThumbnail(context, pdfFile.uri)
            } catch (e: Exception) {
                Log.e("PdfThumbnail", "获取缩略图缓存失败: ${pdfFile.name}", e)
                null
            }
        }

        if (cachedUri != null) {
            // ✅ 缩略图文件存在
            thumbnailUri = cachedUri
            isLoading = false

            // ✅ 如果之前 thumbnailGenerated != 1，更新为 1（容错）
            if (pdfFile.thumbnailGenerated != 1) {
                try {
                    com.example.pdfmanager.data.repository.AppContainer.pdfRepository
                        .updateThumbnailGeneratedStatus(pdfFile.id, 1)
                } catch (e: Exception) {
                    Log.w("PdfThumbnail", "更新 thumbnailGenerated 失败（非关键）", e)
                }
            }
        } else {
            // ❌ 缩略图文件不存在
            isLoading = false
            loadError = true

            // ✅ 重置 thumbnailGenerated 为 0
            if (pdfFile.thumbnailGenerated != 0) {
                try {
                    com.example.pdfmanager.data.repository.AppContainer.pdfRepository
                        .updateThumbnailGeneratedStatus(pdfFile.id, 0)
                    Log.w("PdfThumbnail", "缩略图文件不存在，重置 thumbnailGenerated=0: ${pdfFile.name}")
                } catch (e: Exception) {
                    Log.e("PdfThumbnail", "重置 thumbnailGenerated 失败", e)
                }
            }
        }
    }

    // ✅ UI 渲染（根据状态显示）
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        when {
            isLoading -> {
                // 加载中
                CircularProgressIndicator(
                    modifier = Modifier.padding(16.dp),
                    strokeWidth = 2.dp
                )
            }

            loadError -> {
                // 加载失败（缩略图文件不存在）
                Text(
                    text = pdfFile.name.removeSuffix(".pdf").removeSuffix(".PDF"),
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    maxLines = 3,
                    fontSize = 10.sp,
                    modifier = Modifier.padding(8.dp)
                )
            }

            thumbnailUri != null -> {
                // 加载成功，显示缩略图
                val imageLoader = LocalImageLoader.current
                val density = context.resources.displayMetrics.density
                val sizeWidthPx = (thumbnailWidthDp.value * density).toInt()
                val sizeHeightPx = (thumbnailHeightDp.value * density).toInt()

                val painter = rememberAsyncImagePainter(
                    model = ImageRequest.Builder(context)
                        .data(thumbnailUri)
                        .size(sizeWidthPx, sizeHeightPx)
                        .crossfade(true)
                        .build(),
                    imageLoader = imageLoader
                )

                when (val state = painter.state) {
                    is AsyncImagePainter.State.Loading -> {
                        CircularProgressIndicator(
                            modifier = Modifier.padding(16.dp),
                            strokeWidth = 2.dp
                        )
                    }
                    is AsyncImagePainter.State.Error -> {
                        // Coil 加载失败（文件可能损坏）
                        Text(
                            text = pdfFile.name.removeSuffix(".pdf").removeSuffix(".PDF"),
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center,
                            maxLines = 3,
                            fontSize = 10.sp,
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                    else -> {
                        Image(
                            painter = painter,
                            contentDescription = pdfFile.displayName,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit
                        )
                    }
                }
            }
        }
    }
}
