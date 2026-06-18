package com.example.pdfmanager.ui.screen.filter

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.pdfmanager.data.model.TagCategory

/**
 * 筛选页类别行组件 Composable 函数
 *
 * 功能说明：
 * 在筛选页面中渲染一个标签类别行，包含两行内容：
 * 1. 第一行：三态圆框 + 类别名称
 *    - 三态圆框：○（全未选）/ ●（部分选）/ ✓（全选）
 *    - 点击圆框或类别名称触发 onCategoryClick，切换该类别下所有标签的选中状态
 * 2. 第二行：标签值列表（横向可滚动 LazyRow）
 *    - 每个标签显示为胶囊状按钮
 *    - 选中状态：实色填充（使用类别颜色）
 *    - 未选中状态：透明背景 + 轮廓边框
 *    - 点击单个标签触发 onTagClick，切换该标签的选中状态
 *
 * 调用位置：
 * - FilterScreen 的 LazyColumn 中 items() 函数对每个 category 遍历调用
 *
 * @param category 标签类别数据对象，包含类别 id、name、color、tags 列表等
 * @param categoryState 该类别的选中状态（0=全未选 ○，1=部分选 ●，2=全选 ✓）
 *                      由 FilterViewModel.getCategoryState() 计算得出
 * @param selectedTagKeys 当前所有被选中标签的键集合
 *                        格式：Set<String>，每个元素为 "categoryId:value"
 * @param onCategoryClick 点击类别圆框/名称的回调，调用 ViewModel.toggleCategory()
 * @param onTagClick 点击单个标签的回调，参数为标签键 "categoryId:value"，调用 ViewModel.toggleTag()
 * @param modifier 可选的 Modifier
 */
@Composable
fun FilterCategoryRow(
    category: TagCategory,
    categoryState: Int,  // 0=○, 1=●, 2=✓
    selectedTagKeys: Set<String>,
    onCategoryClick: () -> Unit,
    onTagClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    // 将类别的 color 属性解析为 Compose Color 对象
    val categoryColor = Color(category.color)

    Surface(
        modifier = modifier.fillMaxWidth(),
        tonalElevation = 0.dp
    ) {
        androidx.compose.foundation.layout.Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            // ── 第一行：圆框 + 类别名 ──
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                /**
                 * 三态圆框（点击行为与类别名一致）
                 * 状态映射：
                 * - categoryState == 0：显示"○"（全未选），灰色
                 * - categoryState == 1：显示"●"（部分选），类别颜色
                 * - categoryState == 2：显示"✓"（全选），类别颜色
                 */
                Text(
                    text = when (categoryState) {
                        0 -> "○"
                        1 -> "●"
                        else -> "✓"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = if (categoryState == 2) categoryColor else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.clickable { onCategoryClick() }
                )

                Spacer(modifier = Modifier.width(8.dp))

                // 类别名（点击切换该类别下所有标签）
                Text(
                    text = category.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = categoryColor,
                    modifier = Modifier.clickable { onCategoryClick() }
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            // ── 第二行：标签列表（横向滚动） ──
            /**
             * 使用 LazyRow 实现横向可滚动的标签列表
             * 每个标签渲染为 Surface 胶囊：
             * - 选中状态：使用 categoryColor 填充背景，文字白色
             * - 未选中状态：透明背景 + outline 颜色边框，文字默认色
             */
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(category.tags, key = { it.value }) { tagValue ->
                    // 构建标签键：格式为 "categoryId:value"
                    val tagKey = "${category.id}:${tagValue.value}"
                    val isSelected = selectedTagKeys.contains(tagKey)

                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = if (isSelected) categoryColor else Color.Transparent,
                        modifier = Modifier
                            .then(
                                if (!isSelected) {
                                    // 未选中时添加轮廓边框
                                    Modifier.border(
                                        width = 1.dp,
                                        color = MaterialTheme.colorScheme.outline,
                                        shape = MaterialTheme.shapes.small
                                    )
                                } else {
                                    Modifier
                                }
                            )
                            .clickable { onTagClick(tagKey) }
                    ) {
                        Text(
                            text = tagValue.value,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isSelected) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                }
            }
        }
    }
}
