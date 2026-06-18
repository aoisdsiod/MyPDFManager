package com.example.pdfmanager.ui.screen.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material.icons.filled.ViewStream
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 阅读器底部工具栏（Composable 组件）
 *
 * ===== 功能概述 =====
 * 显示在 PDF 阅读器的底部，提供三个核心交互控件：
 * 1. 翻页方式切换按钮（单页模式 ↔ 连续滚动模式）
 * 2. 上一页 / 下一页按钮
 * 3. 当前页码 / 总页数显示
 *
 * ===== 布局结构 =====
 * ┌─────────────────────────────────────────────┐
 * │  [翻页方式切换]  ◀  [页码显示]  ▶            │
 * └─────────────────────────────────────────────┘
 *
 * ===== 调用位置 =====
 * - ReaderScreenV2.kt 第 224 行：底部 AnimatedVisibility 中调用
 * - 传入 pageMode/currentPage/totalPages 等状态和回调
 *
 * ===== 使用场景 =====
 * 仅用于 PDF 阅读器底部工具栏，在现代（新布局）模式下显示。
 * 当 toolbarMode == "full" 且 isToolbarVisible == true 时展示。
 * 如果 toolbarMode == "page_only" 或 "hidden"，此工具栏不显示。
 *
 * @param pageMode        当前翻页方式："single_page"（单页）或 "continuous"（滚动）
 * @param currentPage     当前页码（0-based，显示时会自动 +1 转为用户视角）
 * @param totalPages      总页数
 * @param isLandscape     是否横屏（当前未使用，为后续横屏适配预留）
 * @param onPageModeChange 翻页方式切换回调，参数为新的翻页方式字符串
 * @param onPreviousPage  上一页按钮点击回调
 * @param onNextPage      下一页按钮点击回调
 * @param modifier        可选的 Compose 修饰符
 */
@Composable
fun ReaderBottomBar(
    pageMode: String,
    currentPage: Int,
    totalPages: Int,
    isLandscape: Boolean,
    onPageModeChange: (String) -> Unit,
    onPreviousPage: () -> Unit,
    onNextPage: () -> Unit,
    modifier: Modifier = Modifier
) {
    /**
     * 底部工具栏容器（Surface）
     *
     * 功能：提供一个半透明（alpha=0.95）的圆角背景表面，
     * 带有阴影（4dp 高度），用于承载底部工具栏的所有控件。
     * 颜色跟随 MaterialTheme 的 surfaceVariant 色。
     */
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.95f),
        shadowElevation = 4.dp
    ) {
        /**
         * 工具栏内容行（Row）
         *
         * 功能：水平排列工具栏中的三个控件组：
         * 左：翻页方式切换按钮
         * 中：上一页 / 页码 / 下一页
         * 使用 SpaceEvenly 等间距布局。
         */
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // ===== 翻页方式切换按钮 =====
            /**
             * 翻页方式切换按钮（FilledTonalButton）
             *
             * 功能：在"单页模式"和"滚动模式"之间切换。
             * 点击时调用 onPageModeChange 传入新的模式字符串。
             *
             * 视觉表现：
             * - 当前为单页模式：显示 "单页" 文字 + ViewStream 图标（滚动图标）
             * - 当前为滚动模式：显示 "滚动" 文字 + ViewAgenda 图标（单页图标）
             * - 按钮颜色：当前模式高亮（primaryContainer），另一种模式灰显（surfaceVariant）
             *
             * 调用位置：用户点击触发
             *
             * Icon 说明：
             * - Icons.Filled.ViewAgenda：两行水平条纹图标（代表单页/列表视图）
             * - Icons.Filled.ViewStream：单行水平条纹图标（代表连续滚动视图）
             */
            FilledTonalButton(
                onClick = {
                    val newMode = if (pageMode == "single_page") "continuous" else "single_page"
                    onPageModeChange(newMode)
                },
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = if (pageMode == "continuous")
                        MaterialTheme.colorScheme.primaryContainer  // 滚动模式：高亮
                    else
                        MaterialTheme.colorScheme.surfaceVariant      // 单页模式：灰显
                ),
                modifier = Modifier.height(40.dp)
            ) {
                /**
                 * 按钮图标
                 *
                 * 功能：显示翻页模式的图标指示。
                 * 单页模式→显示 ViewStream（滚动模式对应的图标，表示可切换到的模式）
                 * 滚动模式→显示 ViewAgenda（单页模式对应的图标）
                 * 图标与文字镜像对应，让用户直觉理解点击后切换到哪种模式。
                 */
                Icon(
                    imageVector = if (pageMode == "single_page")
                        Icons.Filled.ViewAgenda   // 单页模式下显示"单页模式"图标
                    else
                        Icons.Filled.ViewStream,   // 滚动模式下显示"滚动模式"图标
                    contentDescription = if (pageMode == "single_page") "单页模式" else "滚动模式",
                    modifier = Modifier.padding(end = 4.dp)
                )
                /**
                 * 按钮文字
                 *
                 * 功能：显示当前模式的中文名称。
                 */
                Text(if (pageMode == "single_page") "单页" else "滚动")
            }

            // 间距控件（4dp 宽）
            Spacer(modifier = Modifier.width(4.dp))

            // ===== 上一页按钮 =====
            /**
             * 上一页按钮（IconButton）
             *
             * 功能：点击跳转到上一页。
             * 当 currentPage == 0（已经是第一页）时，按钮禁用（enabled=false）。
             *
             * 调用位置：
             * - 用户点击触发 → 调用 onPreviousPage 回调
             * - onCreate 回调链：→ ReaderScreenV2.kt 第 232 行
             *   → viewModel.previousPage() + pendingPageJump 设置
             */
            IconButton(
                onClick = onPreviousPage,
                enabled = currentPage > 0
            ) {
                Icon(
                    imageVector = Icons.Filled.KeyboardArrowLeft,
                    contentDescription = "上一页"
                )
            }

            // ===== 页码显示 =====
            /**
             * 页码文字显示
             *
             * 功能：显示 "当前页 / 总页数" 格式的文本。
             * 注意：currentPage 是 0-based（程序内部使用），
             * 显示时转为 1-based（+1）以符合用户的阅读习惯。
             *
             * 示例：第 0 页 / 共 100 页 → 显示 "1 / 100"
             *
             * 字体样式：使用 MaterialTheme 的 bodyMedium
             */
            Text(
                text = "${currentPage + 1} / $totalPages",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 8.dp)
            )

            // ===== 下一页按钮 =====
            /**
             * 下一页按钮（IconButton）
             *
             * 功能：点击跳转到下一页。
             * 当 currentPage == totalPages - 1（已经是最后一页）时，按钮禁用。
             *
             * 调用位置：
             * - 用户点击触发 → 调用 onNextPage 回调
             * - 回调链：→ ReaderScreenV2.kt 第 240 行
             *   → viewModel.nextPage() + pendingPageJump 设置
             */
            IconButton(
                onClick = onNextPage,
                enabled = currentPage < totalPages - 1
            ) {
                Icon(
                    imageVector = Icons.Filled.KeyboardArrowRight,
                    contentDescription = "下一页"
                )
            }
        }
    }
}
