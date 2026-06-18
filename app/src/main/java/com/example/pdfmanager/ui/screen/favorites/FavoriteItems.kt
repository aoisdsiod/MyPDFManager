@file:OptIn(ExperimentalFoundationApi::class)

package com.example.pdfmanager.ui.screen.favorites

import android.util.Log
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.example.pdfmanager.data.model.FavoriteFolder
import com.example.pdfmanager.data.model.OrganizeFolder

/**
 * 自建文件夹列表项 Composable
 *
 * 用途：渲染一个自建文件夹（OrganizeFolder）的卡片 UI。
 *       支持单击进入文件夹、长按弹出操作菜单、上移/下移排序，
 *       以及排序时的按钮缩放动画和卡片闪白动画。
 *
 * 交互设计：
 *   - 文件夹名称区域：单击进入文件夹，长按弹出操作菜单
 *   - 右侧区域：上移/下移按钮（多选模式时隐藏）
 *
 * 调用位置：FavoritesScreen.kt 的 LazyColumn items 块中，当 item 类型为 OrganizeFolder 时调用
 * 使用场景：用户浏览收藏页面时，以卡片形式展示所有自建文件夹
 *
 * @param folder        当前自建文件夹数据对象
 * @param isMultiSelectMode 是否处于多选模式（多选模式下隐藏排序按钮）
 * @param onClick       单击卡片时的回调 → 进入该文件夹（调用 ViewModel.openOrganizeFolder）
 * @param onLongClick   长按卡片时的回调 → 弹出操作菜单（调用 ViewModel.showMenu）
 * @param onMoveUp      点击上移按钮时的回调（调用 ViewModel.moveItemUp）
 * @param onMoveDown    点击下移按钮时的回调（调用 ViewModel.moveItemDown）
 * @param index         当前项在列表中的索引
 * @param totalCount    列表总项数
 */
@Composable
fun OrganizeFolderItem(
    folder: OrganizeFolder,
    isMultiSelectMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onMoveUp: () -> Unit = {},
    onMoveDown: () -> Unit = {},
    index: Int = 0,
    totalCount: Int = 0
) {
    // ── 动画状态 ─────────────────────────────────────────────────
    /** 上移按钮的缩放动画（点击时缩小再恢复） */
    val moveUpScale = remember { Animatable(1f) }
    /** 下移按钮的缩放动画（点击时缩小再恢复） */
    val moveDownScale = remember { Animatable(1f) }
    /** 上移按钮动画触发器（每次 +1 触发 LaunchedEffect） */
    val moveUpTrigger = remember { mutableStateOf(0) }
    /** 下移按钮动画触发器（每次 +1 触发 LaunchedEffect） */
    val moveDownTrigger = remember { mutableStateOf(0) }
    /** 卡片透明度动画——当 index 变化时产生闪白效果 */
    val cardAlpha = remember { Animatable(1f) }

    /**
     * 监听 index 变化 → 卡片闪白效果
     * 当列表排序发生变化时，通过透明度从 1→0.5→1 的动画产生视觉反馈。
     */
    LaunchedEffect(index) {
        cardAlpha.snapTo(1f)
        cardAlpha.animateTo(0.5f, animationSpec = tween(250))
        cardAlpha.animateTo(1f, animationSpec = tween(500))
    }

    /**
     * 监听 moveUpTrigger 变化 → 上移按钮缩放动画
     * 按钮被点击时先缩小到 0.5 再恢复为 1.0，产生点击反馈。
     */
    LaunchedEffect(moveUpTrigger.value) {
        if (moveUpTrigger.value > 0) {
            moveUpScale.snapTo(1f)
            moveUpScale.animateTo(0.5f, animationSpec = tween(250))
            moveUpScale.animateTo(1.0f, animationSpec = tween(350))
        }
    }

    /**
     * 监听 moveDownTrigger 变化 → 下移按钮缩放动画
     * 按钮被点击时先缩小到 0.5 再恢复为 1.0，产生点击反馈。
     */
    LaunchedEffect(moveDownTrigger.value) {
        if (moveDownTrigger.value > 0) {
            moveDownScale.snapTo(1f)
            moveDownScale.animateTo(0.5f, animationSpec = tween(250))
            moveDownScale.animateTo(1.0f, animationSpec = tween(350))
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .graphicsLayer {
                alpha = cardAlpha.value
            }
            .combinedClickable(
                onClick = onClick,
                onLongClick = if (!isMultiSelectMode) onLongClick else { {} }
            ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // ── 左侧区域：文件夹图标 + 名称（处理点击） ──
            Row(
                modifier = Modifier
                    .weight(1f),

                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.CreateNewFolder,
                    contentDescription = null,
                    modifier = Modifier.padding(end = 12.dp)
                )
                Text(
                    text = folder.name,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
            }

            // ── 右侧区域：上移/下移按钮（多选模式时隐藏） ──
            if (!isMultiSelectMode) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 上箭头（上移）
                    if (index > 0) {
                        IconButton(
                            onClick = {
                                moveUpTrigger.value += 1
                                onMoveUp()
                            },
                            modifier = Modifier
                                .size(28.dp)
                                .graphicsLayer {
                                    scaleX = moveUpScale.value
                                    scaleY = moveUpScale.value
                                }
                        ) {
                            Icon(
                                imageVector = Icons.Default.KeyboardArrowUp,
                                contentDescription = "上移",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        Spacer(modifier = Modifier.size(28.dp))
                    }

                    // 下箭头（下移）
                    if (index < totalCount - 1) {
                        IconButton(
                            onClick = {
                                moveDownTrigger.value += 1
                                onMoveDown()
                            },
                            modifier = Modifier
                                .size(28.dp)
                                .graphicsLayer {
                                    scaleX = moveDownScale.value
                                    scaleY = moveDownScale.value
                                }
                        ) {
                            Icon(
                                imageVector = Icons.Default.KeyboardArrowDown,
                                contentDescription = "下移",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        Spacer(modifier = Modifier.size(28.dp))
                    }
                }
            }
        }
    }
}

/**
 * 虚拟文件夹列表项 Composable
 *
 * 用途：渲染一个虚拟文件夹/筛选文件夹（FavoriteFolder）的卡片 UI。
 *       支持单击进入文件夹内容页、长按弹出操作菜单、上移/下移排序，
 *       以及多选模式下的 Checkbox 选中/取消。
 *
 * 交互设计：
 *   - 文件夹名称区域：单击进入文件夹，长按弹出操作菜单
 *   - 多选模式下显示 Checkbox，点击 Checkbox 区域切换选中状态
 *
 * 调用位置：FavoritesScreen.kt 的 LazyColumn items 块中，当 item 类型为 FavoriteFolder 时调用
 * 使用场景：用户浏览收藏页面时，以卡片形式展示所有虚拟文件夹
 *
 * @param folder            当前虚拟文件夹数据对象
 * @param isMultiSelectMode 是否处于多选模式（多选模式下显示 Checkbox，隐藏排序按钮）
 * @param isSelected        当前虚拟文件夹的选中状态（用于 Checkbox 的显示）
 * @param onClick           单击卡片时的回调 → 进入文件夹内容页（导航到 favorites/content/{id}）
 * @param onLongClick       长按卡片时的回调 → 弹出操作菜单（调用 ViewModel.showMenu）
 * @param onToggleSelection 点击 Checkbox 时的回调 → 切换文件夹选中状态（调用 ViewModel.toggleFolderSelection）
 * @param onMoveUp          点击上移按钮时的回调（调用 ViewModel.moveItemUp）
 * @param onMoveDown        点击下移按钮时的回调（调用 ViewModel.moveItemDown）
 * @param index             当前项在列表中的索引
 * @param totalCount        列表总项数
 */
@Composable
fun FavoriteFolderItem(
    folder: FavoriteFolder,
    isMultiSelectMode: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onToggleSelection: () -> Unit,
    onMoveUp: () -> Unit = {},
    onMoveDown: () -> Unit = {},
    index: Int = 0,
    totalCount: Int = 0
) {
    // ── 动画状态 ─────────────────────────────────────────────────
    /** 上移按钮的缩放动画（点击时缩小再恢复） */
    val moveUpScale = remember { Animatable(1f) }
    /** 下移按钮的缩放动画（点击时缩小再恢复） */
    val moveDownScale = remember { Animatable(1f) }
    /** 上移按钮动画触发器（每次 +1 触发 LaunchedEffect） */
    val moveUpTrigger = remember { mutableStateOf(0) }
    /** 下移按钮动画触发器（每次 +1 触发 LaunchedEffect） */
    val moveDownTrigger = remember { mutableStateOf(0) }
    /** 卡片透明度动画——当 index 变化时产生闪白效果 */
    val cardAlpha = remember { Animatable(1f) }

    /**
     * 监听 index 变化 → 卡片闪白效果
     * 当列表排序发生变化时，通过透明度从 1→0.5→1 的动画产生视觉反馈。
     */
    LaunchedEffect(index) {
        cardAlpha.snapTo(1f)
        cardAlpha.animateTo(0.5f, animationSpec = tween(250))
        cardAlpha.animateTo(1f, animationSpec = tween(500))
    }

    /**
     * 监听 moveUpTrigger 变化 → 上移按钮缩放动画
     * 按钮被点击时先缩小到 0.5 再恢复为 1.0，产生点击反馈。
     */
    LaunchedEffect(moveUpTrigger.value) {
        if (moveUpTrigger.value > 0) {
            moveUpScale.snapTo(1f)
            moveUpScale.animateTo(0.5f, animationSpec = tween(250))
            moveUpScale.animateTo(1.0f, animationSpec = tween(350))
        }
    }

    /**
     * 监听 moveDownTrigger 变化 → 下移按钮缩放动画
     * 按钮被点击时先缩小到 0.5 再恢复为 1.0，产生点击反馈。
     */
    LaunchedEffect(moveDownTrigger.value) {
        if (moveDownTrigger.value > 0) {
            moveDownScale.snapTo(1f)
            moveDownScale.animateTo(0.5f, animationSpec = tween(250))
            moveDownScale.animateTo(1.0f, animationSpec = tween(350))
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .graphicsLayer {
                alpha = cardAlpha.value
            }
            .combinedClickable(
                onClick = onClick,
                onLongClick = if (!isMultiSelectMode) onLongClick else { {} }
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()

                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // ── 左侧区域：收藏图标 + 名称（显示名称） ──
            Row(
                modifier = Modifier
                    .weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Favorite,
                    contentDescription = null,
                    modifier = Modifier.padding(end = 12.dp)
                )
                Text(
                    text = folder.name,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
            }

            // ── 中间区域：多选模式下的 Checkbox ──
            if (isMultiSelectMode) {
                Box(
                    modifier = Modifier
                        .clickable {
                            Log.d("FavoriteFolderItem", "▶ checkbox Box clickable 触发! folder=${folder.name}, isSelected=$isSelected, folder.id=${folder.id}")
                            onToggleSelection()
                        }
                        .padding(start = 8.dp, top = 4.dp, bottom = 4.dp, end = 4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Checkbox(
                        checked = isSelected,
                        onCheckedChange = null,
                        modifier = Modifier
                    )
                }
            }

            // ── 右侧区域：上移/下移按钮（多选模式时隐藏） ──
            if (!isMultiSelectMode) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 上箭头（上移）
                    if (index > 0) {
                        IconButton(
                            onClick = {
                                moveUpTrigger.value += 1
                                onMoveUp()
                            },
                            modifier = Modifier
                                .size(28.dp)
                                .graphicsLayer {
                                    scaleX = moveUpScale.value
                                    scaleY = moveUpScale.value
                                }
                        ) {
                            Icon(
                                imageVector = Icons.Default.KeyboardArrowUp,
                                contentDescription = "上移",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        Spacer(modifier = Modifier.size(28.dp))
                    }

                    // 下箭头（下移）
                    if (index < totalCount - 1) {
                        IconButton(
                            onClick = {
                                moveDownTrigger.value += 1
                                onMoveDown()
                            },
                            modifier = Modifier
                                .size(28.dp)
                                .graphicsLayer {
                                    scaleX = moveDownScale.value
                                    scaleY = moveDownScale.value
                                }
                        ) {
                            Icon(
                                imageVector = Icons.Default.KeyboardArrowDown,
                                contentDescription = "下移",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        Spacer(modifier = Modifier.size(28.dp))
                    }
                }
            }
        }
    }
}
