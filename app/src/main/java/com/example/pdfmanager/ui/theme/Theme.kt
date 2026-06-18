/*
 * ============================================================================
 * 主题定义文件（Theme.kt）
 * ============================================================================
 *
 * 【文件功能】
 * 本文件是 Material 3 主题的入口，负责：
 *   1. 将从 Color.kt 中定义的亮色/暗色颜色常量组装成 Material 3 的 ColorScheme 对象
 *   2. 提供 MyPDFManagerTheme Composable 函数作为应用主题入口
 *   3. 根据用户偏好的主题模式（跟随系统/强制亮色/强制暗色）选择对应的 ColorScheme
 *
 * 【配色方案结构】
 * LightColorScheme（亮色配色方案）：
 *   从 md_theme_light_xxx 颜色常量构建，用于亮色（日间）模式。
 *   色值基于紫色主色（#6750A4）的 Material 3 默认 palette。
 *
 * DarkColorScheme（暗色配色方案）：
 *   从 md_theme_dark_xxx 颜色常量构建，用于暗色（夜间）模式。
 *   亮色与暗色对应色值对比：
 *     - 主色(primary): 亮色 #6750A4（深紫） → 暗色 #D0BCFF（浅紫）
 *     - 背景(background): 亮色 #FFFFFBFE（近白） → 暗色 #1C1B1F（深灰）
 *     - 文本(onBackground): 亮色 #1C1B1F（深灰） → 暗色 #E6E1E5（浅灰）
 *
 * 【主题应用流程】
 *   MainActivity
 *     ↓ 读取 PreferencesManager 中的 themeMode 设置（"follow_system"/"light"/"dark"）
 *     ↓
 *   MyPDFManagerTheme(themeMode = themeMode)
 *     ↓ 根据 themeMode 计算 isDarkTheme，选择对应 ColorScheme
 *     ↓
 *   MaterialTheme(colorScheme = ...)
 *     ↓ 通过 MaterialTheme.colorScheme.xxx 在所有子 Composable 中访问颜色
 *
 * 【调用位置】
 * - app/src/main/java/com/example/pdfmanager/MainActivity.kt（第 84 行）
 *   → setContent { MyPDFManagerTheme(themeMode = themeMode) { ... } }
 *
 * 【使用场景】
 * 整个应用的根主题包装器。所有 Composable 页面都在此 Theme 的 content 块内，
 * 通过 MaterialTheme 提供的颜色、排版、形状系统获取样式信息。
 *
 * 【相关文件】
 * - Color.kt：定义了本文件所使用的全部颜色常量
 * - MainActivity.kt：读取用户设置并传入 themeMode 参数
 * ============================================================================
 */

package com.example.pdfmanager.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

/**
 * 亮色配色方案（Light Color Scheme）
 *
 * 使用 lightColorScheme() 构建 Material 3 亮色配色方案。
 * 所有颜色值来源于 Color.kt 中定义的 md_theme_light_xxx 常量。
 *
 * visibility: private（仅在本文件中使用，外部通过 MaterialTheme.colorScheme 访问）
 */
private val LightColorScheme = lightColorScheme(
    primary = md_theme_light_primary,
    onPrimary = md_theme_light_onPrimary,
    primaryContainer = md_theme_light_primaryContainer,
    onPrimaryContainer = md_theme_light_onPrimaryContainer,
    secondary = md_theme_light_secondary,
    onSecondary = md_theme_light_onSecondary,
    secondaryContainer = md_theme_light_secondaryContainer,
    onSecondaryContainer = md_theme_light_onSecondaryContainer,
    tertiary = md_theme_light_tertiary,
    onTertiary = md_theme_light_onTertiary,
    tertiaryContainer = md_theme_light_tertiaryContainer,
    onTertiaryContainer = md_theme_light_onTertiaryContainer,
    error = md_theme_light_error,
    onError = md_theme_light_onError,
    errorContainer = md_theme_light_errorContainer,
    onErrorContainer = md_theme_light_onErrorContainer,
    background = md_theme_light_background,
    onBackground = md_theme_light_onBackground,
    surface = md_theme_light_surface,
    onSurface = md_theme_light_onSurface,
    surfaceVariant = md_theme_light_surfaceVariant,
    onSurfaceVariant = md_theme_light_onSurfaceVariant,
    outline = md_theme_light_outline,
    inverseOnSurface = md_theme_light_inverseOnSurface,
    inverseSurface = md_theme_light_inverseSurface,
    inversePrimary = md_theme_light_inversePrimary,
)

/**
 * 暗色配色方案（Dark Color Scheme）
 *
 * 使用 darkColorScheme() 构建 Material 3 暗色配色方案。
 * 所有颜色值来源于 Color.kt 中定义的 md_theme_dark_xxx 常量。
 *
 * visibility: private（仅在本文件中使用，外部通过 MaterialTheme.colorScheme 访问）
 */
private val DarkColorScheme = darkColorScheme(
    primary = md_theme_dark_primary,
    onPrimary = md_theme_dark_onPrimary,
    primaryContainer = md_theme_dark_primaryContainer,
    onPrimaryContainer = md_theme_dark_onPrimaryContainer,
    secondary = md_theme_dark_secondary,
    onSecondary = md_theme_dark_onSecondary,
    secondaryContainer = md_theme_dark_secondaryContainer,
    onSecondaryContainer = md_theme_dark_onSecondaryContainer,
    tertiary = md_theme_dark_tertiary,
    onTertiary = md_theme_dark_onTertiary,
    tertiaryContainer = md_theme_dark_tertiaryContainer,
    onTertiaryContainer = md_theme_dark_onTertiaryContainer,
    error = md_theme_dark_error,
    onError = md_theme_dark_onError,
    errorContainer = md_theme_dark_errorContainer,
    onErrorContainer = md_theme_dark_onErrorContainer,
    background = md_theme_dark_background,
    onBackground = md_theme_dark_onBackground,
    surface = md_theme_dark_surface,
    onSurface = md_theme_dark_onSurface,
    surfaceVariant = md_theme_dark_surfaceVariant,
    onSurfaceVariant = md_theme_dark_onSurfaceVariant,
    outline = md_theme_dark_outline,
    inverseOnSurface = md_theme_dark_inverseOnSurface,
    inverseSurface = md_theme_dark_inverseSurface,
    inversePrimary = md_theme_dark_inversePrimary,
)

/**
 * MyPDFManagerTheme - 应用根主题 Composable
 *
 * 【功能描述】
 * 应用的主题入口函数，根据用户设置的主题模式（themeMode）选择合适的配色方案，
 * 并通过 MaterialTheme 提供给整个组件树。当前版本仅配置了颜色系统（colorScheme），
 * 未覆盖排版（typography）和形状（shapes）的自定义。
 *
 * 【调用位置】
 * - app/src/main/java/com/example/pdfmanager/MainActivity.kt（第 84 行）
 *   → setContent { MyPDFManagerTheme(themeMode = themeMode) { ... } }
 *
 * 【使用场景】
 * 作为应用最外层的主题容器，包裹所有界面内容。
 * 所有 Composable 子组件均可通过 MaterialTheme.colorScheme.xxx 访问当前配色。
 *
 * @param themeMode 主题模式字符串，支持以下值：
 *                   - "follow_system"（默认值）：跟随系统深色/浅色设置
 *                   - "dark"：强制使用暗色模式
 *                   - "light"：强制使用亮色模式
 * @param content 主题内容块，所有应用界面组件在此 lambda 中声明
 *
 * @return 无返回值，为 Composable 函数
 */
@Composable
fun MyPDFManagerTheme(
    themeMode: String = "follow_system",
    content: @Composable () -> Unit
) {
    // 根据 themeMode 计算是否使用暗色模式
    val isDarkTheme = when (themeMode) {
        "dark" -> true          // 强制暗色
        "light" -> false        // 强制亮色
        else -> isSystemInDarkTheme()  // 跟随系统设置（默认行为）
    }

    // 选择对应的配色方案
    val colorScheme = if (isDarkTheme) DarkColorScheme else LightColorScheme

    // 通过 MaterialTheme 注入配色方案，使子组件可访问
    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
