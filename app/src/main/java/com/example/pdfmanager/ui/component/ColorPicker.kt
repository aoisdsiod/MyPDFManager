package com.example.pdfmanager.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize


/**
 * 颜色选择器组件
 *
 * ## 功能描述
 * 渲染一个 2 行 × 6 列的圆形色块选择面板，共 12 种预设颜色。
 * 用户点击某个色块即可选中该颜色，选中的色块中央会显示白色勾号（✓）作为视觉反馈。
 *
 * ## 调用位置
 * - [com.example.pdfmanager.ui.screen.favorites.CreateFolderDialog]：在创建虚拟文件夹（收藏夹）的对话框中，
 *   用于让用户选择文件夹的颜色标记。
 * - [com.example.pdfmanager.ui.screen.favorites.EditFolderDialog]：在编辑虚拟文件夹信息的对话框中，
 *   用于让用户修改文件夹的颜色标记。
 *
 * ## 使用场景
 * - 用户创建或编辑虚拟文件夹（收藏夹）时，需要为文件夹选择一个标识颜色
 * - 选择的颜色会被持久化到数据库的 FavoriteFolder.color 字段中
 * - 在收藏夹列表中，每个文件夹卡片会以该颜色作为背景或主题色
 *
 * @param selectedColor 当前选中的颜色值（ARGB 格式的 Int）。颜色值使用 0xAARRGGBB 格式，
 *                      例如 0xFFFF9800 表示橙色。如果不为 null 且匹配某个色块，
 *                      该色块会显示选中态的勾号图标。
 * @param onColorSelected 用户点击选择颜色时的回调函数。参数为被点击的颜色 Int 值。
 *                        调用方（如 CreateFolderDialog）应在此回调中更新状态并持久化保存。
 * @param modifier 外部布局修饰符，可选参数，用于控制整体列布局的外边距、对齐等样式。
 *                 默认为 Modifier，不做额外修饰。
 */
@Composable
fun ColorPicker(
    selectedColor: Int,
    onColorSelected: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    // 12 种预设颜色列表（Material Design 调色板）
    // 按照 2 行 × 6 列排列：前 6 个为第一行，后 6 个为第二行
    val colors = listOf(
        0xFFF44336.toInt(), // 红色（Red 500）
        0xFF1A237E.toInt(), // 深靛蓝色（Indigo 900），注释写"粉色"实为深蓝色近似黑色
        0xFFFF9800.toInt(), // 橙色（Orange 500）
        0xFFFFEB3B.toInt(), // 黄色（Yellow 500）
        0xFF4CAF50.toInt(), // 绿色（Green 500）
        0xFF009688.toInt(), // 青色（Teal 500）
        0xFF2196F3.toInt(), // 蓝色（Blue 500）
        0xFF3F51B5.toInt(), // 靛蓝色（Indigo 500）
        0xFF9C27B0.toInt(), // 紫色（Purple 500）
        0xFF4DB6AC.toInt(), // 浅绿色（Teal 300/Light Green-ish）
        0xFF9E9E9E.toInt(), // 灰色（Grey 500）
        0xFF9575CD.toInt()  // 淡紫蓝色（Deep Purple 300）
    )
    
    Column(modifier = modifier) {
        // ── 第一行：颜色列表前 6 个色块 ──
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            colors.take(6).forEach { color ->
                ColorItem(
                    color = color,
                    isSelected = color == selectedColor,
                    onClick = { onColorSelected(color) }
                )
            }
        }
        
        Spacer(modifier = Modifier.height(8.dp))
        
        // ── 第二行：颜色列表后 6 个色块 ──
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            colors.drop(6).forEach { color ->
                ColorItem(
                    color = color,
                    isSelected = color == selectedColor,
                    onClick = { onColorSelected(color) }
                )
            }
        }
    }
}

/**
 * 单个圆形颜色项
 *
 * ## 功能描述
 * 渲染一个 40dp × 40dp 的圆形色块。如果该色块被选中，则色块中央显示一个白色
 * 勾号字符（Unicode: ✓）作为选中标识。色块可点击，点击后触发传入的回调通知父组件。
 *
 * ## 调用位置
 * - [ColorPicker]：在 ColorPicker 的 Row 布局中，通过 forEach 循环为每个颜色值创建 ColorItem。
 *
 * ## 使用场景
 * - 作为 ColorPicker 的内部子组件，展示单个可选颜色
 * - 适合扩展为独立的颜色展示控件（如标签色块、分类标记等）
 *
 * @param color 色块的颜色值（ARGB 格式的 Int），例如 0xFF4CAF50 表示绿色
 * @param isSelected 该色块是否为当前选中项。为 true 时显示白色 ✓ 标记
 * @param onClick 点击色块时的回调函数。调用方将在此回调中通过 onColorSelected 更新选中颜色
 * @param modifier 外部布局修饰符，可选参数，用于控制单个色块的外层布局。默认为 Modifier
 */
@Composable
fun ColorItem(
    color: Int,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .size(40.dp)               // 固定尺寸 40dp × 40dp 的圆形
            .clip(CircleShape),         // 裁剪为圆形
        color = Color(color),           // 色块背景色
        onClick = onClick               // 点击回调
    ) {
        // 选中态：在色块中央显示白色勾号
        if (isSelected) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "✓",
                    color = Color.White
                )
            }
        }
    }
}
