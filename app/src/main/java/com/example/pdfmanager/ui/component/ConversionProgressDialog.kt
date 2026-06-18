package com.example.pdfmanager.ui.component

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.pdfmanager.data.model.ConversionProgress

/**
 * 转换进度弹窗（AlertDialog）
 *
 * ========== 功能概述 ==========
 * 在 PDF 转换（图片→PDF / PDF→图片等）过程中，以 AlertDialog 弹窗形式实时显示
 * 转换进度信息，为用户提供可视化的进度反馈。
 *
 * ========== UI 结构 ==========
 * 1. 标题："正在转换..."
 * 2. 内容区域（Column，垂直排列）：
 *    a. "当前文件：xxx" — 显示当前正在处理的文件名
 *    b. 页面级进度（当 totalPages > 0 时显示）：
 *       - "第 n/m 页" 文本
 *       - LinearProgressIndicator（进度 = currentPage / totalPages）
 *    c. 文件级进度：
 *       - "文件进度：n/m" 文本
 *       - LinearProgressIndicator（进度 = fileIndex / totalFiles）
 *    d. 提示文本："请不要离开此页面"（灰色）
 * 3. 无确认按钮（confirmButton 为空 Lambda {}，不渲染任何按钮）
 * 4. 无取消按钮（dismissButton = null，不渲染取消按钮）
 *
 * ========== 交互逻辑 ==========
 * - 弹窗没有任何用户可操作的按钮，仅用于展示进度
 * - 外部点击弹窗区域触发 onDismissRequest = onDismiss
 *   （但用户在转换过程中通常不会主动关闭，onDismiss 用于转换完成或异常后的自动关闭）
 * - 弹窗显示与隐藏完全由调用方控制：progress != null 时显示，progress == null 时 return
 * - progress 参数由 ViewModel 或其他数据源持续更新，触发 Composable 重组刷新 UI
 *
 * ========== 调用位置 ==========
 * - PdfConversionScreen.kt 第 393 行：PDF 转换功能执行过程中
 *   调用方传入 PdfConversionViewModel 中的 conversionProgress 状态，
 *   当 conversionProgress != null 时显示此弹窗
 *
 * ========== 数据模型 ==========
 * ConversionProgress（com.example.pdfmanager.data.model.ConversionProgress）包含：
 * - currentFileName: String — 当前正在处理的文件名
 * - currentPage: Int — 当前文件已处理的页数
 * - totalPages: Int — 当前文件的总页数（为 0 时不显示页面进度）
 * - fileIndex: Int — 当前已处理的文件数（从 1 开始）
 * - totalFiles: Int — 文件总数
 *
 * @param progress 当前转换进度对象（ConversionProgress?）
 *                 值为 null 时不渲染任何内容（函数直接 return）
 *                 调用方在转换过程中持续更新此值，
 *                 转换完成或失败后设为 null 以关闭弹窗
 * @param onDismiss 用户触发关闭弹窗时的回调
 *                  当前 UI 未提供关闭按钮，但点击弹窗外部区域会触发此回调
 *                  调用方应在此回调中停止转换或重置状态
 */
@Composable
fun ConversionProgressDialog(
    progress: ConversionProgress?,
    onDismiss: () -> Unit,
) {
    /**
     * 核心逻辑：progress == null 时不渲染任何内容
     * 调用方控制弹窗显示的方式即为：
     * - 转换中：传入非 null 的 ConversionProgress 对象
     * - 转换完成/失败：将 progress 设为 null，组件直接 return 不渲染
     */
    if (progress == null) return

    // ========== AlertDialog 主体 ==========

    /**
     * Material3 AlertDialog
     *
     * 设计说明：
     * - 无确认按钮（confirmButton = {}）：空 Lambda 表示不渲染任何按钮
     * - 无取消按钮（dismissButton = null）：完全移除取消按钮
     * - 这样的设计是为了防止用户在转换过程中误操作关闭弹窗而中断转换
     * - 转换完成后由调用方将 progress 设为 null 来关闭弹窗
     */
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("正在转换...") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // ----- 当前文件名称 -----
                /**
                 * 显示当前正在处理的 PDF 文件名称
                 * 当转换多文件时此值会随当前文件的切换而变化
                 */
                Text(
                    text = "当前文件：${progress.currentFileName}",
                    style = MaterialTheme.typography.bodyMedium
                )

                // ----- 页面级进度条（仅当总页数 > 0 时显示） -----
                /**
                 * 条件：progress.totalPages > 0
                 * 显示当前文件的页面转换进度：
                 * - "第 currentPage / totalPages 页" 文本
                 * - LinearProgressIndicator：进度 = currentPage.toFloat() / totalPages
                 *
                 * 适用场景：
                 * - 图片→PDF 转换时，每处理一页更新一次
                 * - 单页 PDF→图片时，totalPages = 1
                 * - 当 totalPages = 0（无法获取总页数）时隐藏此区域，避免除以零
                 */
                if (progress.totalPages > 0) {
                    Column {
                        Text(
                            text = "第 ${progress.currentPage}/${progress.totalPages} 页",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        LinearProgressIndicator(
                            progress = progress.currentPage.toFloat() / progress.totalPages,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                // ----- 文件级进度条（始终显示） -----
                /**
                 * 显示整个批处理任务的文件处理进度：
                 * - "文件进度：fileIndex / totalFiles" 文本
                 * - LinearProgressIndicator：进度 = fileIndex.toFloat() / totalFiles
                 *
                 * 适用场景：
                 * - 多文件批量转换时精确显示整体进度
                 * - 单文件转换时 fileIndex = totalFiles = 1，进度为 100%
                 */
                Text(
                    text = "文件进度：${progress.fileIndex}/${progress.totalFiles}",
                    style = MaterialTheme.typography.bodySmall
                )
                LinearProgressIndicator(
                    progress = progress.fileIndex.toFloat() / progress.totalFiles,
                    modifier = Modifier.fillMaxWidth()
                )

                // ----- 提示信息 -----
                /**
                 * 提示用户不要在转换过程中离开当前页面
                 * 使用 onSurfaceVariant 颜色以降低视觉干扰
                 */
                Text(
                    text = "请不要离开此页面",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {},
        dismissButton = null
    )
}
