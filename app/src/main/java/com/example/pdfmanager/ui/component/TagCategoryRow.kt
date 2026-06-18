package com.example.pdfmanager.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.pdfmanager.data.model.PdfTagEntity
import com.example.pdfmanager.data.model.TagCategory

/**
 * 标签类别行组件（只读版本）
 *
 * ====== 功能说明 ======
 * 将同一类别下的多个标签按行展示，左侧显示类别名称（带 emoji 表情符号和颜色），
 * 右侧使用 FlowRow 流式布局排列该类别下的所有只读标签胶囊。
 *
 * ====== 布局结构 ======
 * ┌──────────────────────────────────────┐
 * │ 📅 年份：  [2024] [2025] [2026]      │
 * │ 👤 作者：  [张三] [李四]              │
 * │ 📁 项目：  [项目A] [项目B] [项目C]    │
 * └──────────────────────────────────────┘
 * - 左侧：类别名称，固定宽度（由内容撑开），带 8dp 右侧间距
 * - 右侧：FlowRow 流式布局（weight=1f 占满剩余空间），标签间距 4dp
 *
 * ====== 使用场景 ======
 * 专用于 PDF 详情页（卡片 2 - 标签展示区），以只读模式展示已有标签。
 * 不显示 [+] 添加按钮，标签不可点击。
 *
 * ====== 调用位置 ======
 * DetailScreen.kt → 在详情页的标签区域，按类别分类后逐类调用。
 * 调用方式：
 *   TagCategoryRowReadOnly(
 *       category = category,
 *       tags = tags.sortedByDescending { it.tagValue }
 *   )
 *
 * ====== 设计细节 ======
 * - 如果 tags 列表为空，组件直接 return 不渲染任何内容
 * - 类别表情（emoji）根据 category.name 硬编码映射：
 *   - "年份" → 📅
 *   - "作者" → 👤
 *   - "项目" → 📁
 *   - 其他   → 🏷️（兜底）
 * - 类别文字颜色使用 TagCategory.color 字段
 *
 * @param category 标签类别对象（包含 name、color、sortOrder 等字段）
 * @param tags 该类别下的标签列表（PdfTagEntity 列表），
 *             调用方应预先按期望顺序排序（如按 tagValue 降序）
 * @param modifier Compose 修饰符
 * @return 无返回值（Composable 函数直接渲染界面）
 *         当 tags 为空列表时，直接返回不渲染
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TagCategoryRowReadOnly(
    category: TagCategory,
    tags: List<PdfTagEntity>,
    modifier: Modifier = Modifier
) {
    if (tags.isEmpty()) return
    
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top
    ) {
        // 类别名称 + 表情符号
        val categoryEmoji = when (category.name) {
            "年份" -> "📅"
            "作者" -> "👤"
            "项目" -> "📁"
            else -> "🏷️"
        }
        
        Text(
            text = "$categoryEmoji ${category.name}：",
            style = MaterialTheme.typography.bodyMedium,
            color = androidx.compose.ui.graphics.Color(category.color),
            modifier = Modifier
                .padding(end = 8.dp, top = 8.dp)
        )
        
        // 标签列表（只读）
        FlowRow(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            tags.forEach { tag ->
                TagChipReadOnly(
                    tag = tag
                )
            }
        }
    }
}
