package com.example.pdfmanager.ui.screen.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 页码角标（Compose 组件）
 *
 * 在阅读器界面中显示当前页码/总页数的半透明标签。
 * 点击该角标可以触发显示或隐藏底部工具栏。
 *
 * ===== 显示格式 =====
 * 显示为 "当前页 + 1 / 总页数" 的文本标签。
 * 例如：第 0 页（0-based）共 10 页 → 显示 "1 / 10"
 *
 * ===== 外观风格 =====
 * - 圆角矩形背景（16dp 圆角）
 * - 半透明 surfaceVariant 颜色（alpha=0.9）
 * - 12sp 字号的 bodySmall 字体
 *
 * ===== 调用位置 =====
 * - ReaderScreenV2.kt 第 251 行：在阅读器界面的 Slot 中调用
 *
 * ===== 使用场景 =====
 * 在 PDF 阅读器的阅读界面中覆盖显示，始终浮于内容之上。
 * 让用户随时了解阅读进度，并提供一个可点击的热区来呼出工具栏。
 *
 * @param currentPage 当前页码（0-based），显示时会自动 +1
 * @param totalPages  文档总页数
 * @param onClick     点击角标时的回调，通常用于显示/隐藏工具栏
 * @param modifier    Compose Modifier 修饰符（可选），用于自定义布局位置
 */
@Composable
fun PageNumberBadge(
    currentPage: Int,
    totalPages: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            // 使用 clip 裁剪圆角（防止背景溢出）
            .clip(RoundedCornerShape(16.dp))
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f),
                shape = RoundedCornerShape(16.dp)
            )
            // 点击事件绑定
            .clickable { onClick() }
            // 内边距：左右 12dp，上下 6dp
            .padding(horizontal = 12.dp, vertical = 6.dp),
        // 文本居中对齐
        contentAlignment = Alignment.Center
    ) {
        Text(
            // 显示格式：当前页（1-based）/ 总页数
            text = "${currentPage + 1} / $totalPages",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp
        )
    }
}
