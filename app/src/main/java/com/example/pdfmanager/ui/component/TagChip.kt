package com.example.pdfmanager.ui.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.pdfmanager.data.model.PdfTagEntity
import com.example.pdfmanager.data.model.Tag

/**
 * 标签胶囊组件（交互式版本）
 *
 * ====== 功能说明 ======
 * 显示单个标签的胶囊形状 UI 组件，支持选中/未选中两种状态。
 * 使用 Material3 的 FilterChip 实现，风格与系统设计一致。
 *
 * ====== 显示样式 ======
 * - 边框颜色 = 标签类别颜色
 * - 选中时：背景色 = 类别颜色 20% 透明度，文字色 = 黑色
 * - 未选中时：背景透明，文字色 = 类别颜色 80% 透明度
 *
 * ====== 使用场景 ======
 * 用于标签筛选/选择界面，用户可以通过点击切换标签的选中状态，
 * 配合外部状态实现多标签筛选逻辑。
 *
 * ====== 调用位置 ======
 * 本组件目前是通用组件，定义后供各界面按需使用。
 * 注意：AllFilesScreen.kt 中使用了局部的 FilterTagChip（同名但为局部函数），
 * 后续可考虑统一替换为 TagChip 组件。
 *
 * @param tag 标签对象（包含标签值 value 和颜色 color）
 * @param isSelected 是否选中，默认 false
 * @param onClick 点击回调函数，由外部管理选中状态切换
 * @param modifier Compose 修饰符
 * @return 无返回值（Composable 函数直接渲染界面）
 */
@Composable
fun TagChip(
    tag: Tag,
    isSelected: Boolean = false,
    onClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val tagColor = Color(tag.color)
    
    FilterChip(
        selected = isSelected,
        onClick = onClick,
        label = {
            Text(
                text = tag.value,
                style = MaterialTheme.typography.bodySmall
            )
        },
        modifier = modifier,
        border = androidx.compose.foundation.BorderStroke(
            width = 1.dp,
            color = tagColor
        ),
        colors = FilterChipDefaults.filterChipColors(
            // 选中状态：浅色背景 + 黑色文字
            selectedContainerColor = tagColor.copy(alpha = 0.2f),
            selectedLabelColor = Color.Black,
            selectedLeadingIconColor = tagColor,
            // 未选中状态：透明背景 + 淡色文字
            containerColor = Color.Transparent,
            labelColor = tagColor.copy(alpha = 0.8f)
        )
    )
}

/**
 * 标签胶囊组件（只读版本）
 *
 * ====== 功能说明 ======
 * 与 TagChip 不同的是，此组件为纯展示用途，不支持点击交互。
 * 使用 Box + background 替代 FilterChip，样式上更加简洁。
 * 内边距较大（水平 16dp，垂直 8dp），适合在详情页等空间充裕的场景使用。
 *
 * ====== 与 TagChip 的区别 ======
 * | 特性         | TagChip              | TagChipReadOnly       |
 * |-------------|----------------------|-----------------------|
 * | 交互方式     | 可点击，支持选中状态   | 纯展示，不可点击       |
 * | 输入类型     | Tag 对象             | PdfTagEntity 对象     |
 * | 字体样式     | bodySmall            | bodyMedium            |
 * | 内边距       | 较小（由 FilterChip 控制） | 较大（显式设置 16dp×8dp） |
 *
 * ====== 使用场景 ======
 * 用于 PDF 详情页的标签展示区（卡片 2），以只读模式显示文件已有的标签。
 * 用户在此只能查看标签信息，不能进行点击切换操作。
 *
 * ====== 调用位置 ======
 * TagCategoryRow.kt → TagCategoryRowReadOnly 组件内部遍历调用
 *
 * @param tag 标签实体对象（PdfTagEntity，包含 tagValue 和 tagColor）
 * @param modifier Compose 修饰符
 * @return 无返回值（Composable 函数直接渲染界面）
 */
@Composable
fun TagChipReadOnly(
    tag: PdfTagEntity,
    modifier: Modifier = Modifier
) {
    val tagColor = Color(tag.tagColor)
    
    Box(
        modifier = modifier
            .padding(horizontal = 8.dp, vertical = 4.dp)  // ← 从 4.dp, 2.dp 改为 8.dp, 4.dp
            .background(
                color = tagColor.copy(alpha = 0.2f),
                shape = MaterialTheme.shapes.small
            )
            .padding(horizontal = 16.dp, vertical = 8.dp)  // ← 从 8.dp, 4.dp 改为 16.dp, 8.dp
    ) {
        Text(
            text = tag.tagValue,
            style = MaterialTheme.typography.bodyMedium,  // ← 从 bodySmall 改为 bodyMedium
            color = tagColor.copy(alpha = 0.8f)
        )
    }
}
