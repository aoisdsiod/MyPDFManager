/*
 * ============================================================================
 * 颜色定义文件（Color.kt）
 * ============================================================================
 *
 * 【文件功能】
 * 本文件定义了 Material 3（Material You）设计规范下的完整颜色 palette。
 * 包含两套独立配色方案：
 *   1. Light Color Scheme（亮色/浅色模式配色）
 *   2. Dark Color Scheme（暗色/深色模式配色）
 *
 * 每套方案都包含以下颜色角色（Color Roles，参见 Material Design 3 Color System）：
 *   - primary / onPrimary / primaryContainer / onPrimaryContainer
 *   - secondary / onSecondary / secondaryContainer / onSecondaryContainer
 *   - tertiary / onTertiary / tertiaryContainer / onTertiaryContainer
 *   - error / onError / errorContainer / onErrorContainer
 *   - background / onBackground
 *   - surface / onSurface / surfaceVariant / onSurfaceVariant
 *   - outline
 *   - inverseOnSurface / inverseSurface / inversePrimary
 *
 * 【调用位置】
 * - app/src/main/java/com/example/pdfmanager/ui/theme/Theme.kt
 *   → 被 LightColorScheme 和 DarkColorScheme 引用，构造 MaterialTheme 的 colorScheme
 *
 * 【使用场景】
 * 整个应用（MainActivity → MyPDFManagerTheme → MaterialTheme）的配色均基于此文件定义的颜色。
 * 所有 Composable 组件通过 MaterialTheme.colorScheme.xxx 访问这些颜色。
 *
 * 【颜色来源】
 * 当前颜色值为 Material 3 构建工具（M3 HCT 色值生成器）生成的默认 palette，基于紫色主色（#6750A4）。
 * 后续可根据品牌色替换 primary，再通过 Material Theme Builder 自动生成整套配色。
 * ============================================================================
 */

package com.example.pdfmanager.ui.theme

import androidx.compose.ui.graphics.Color

// ═══════════════════════════════════════════════════════════════════════════
// 浅色/亮色模式配色（Light Theme Colors）
// ═══════════════════════════════════════════════════════════════════════════

/**
 * 主色（Primary Color）
 * 应用中最突出的颜色，用于 FAB（浮动操作按钮）、选中态、强调文本等关键元素。
 * 亮色模式下为紫色调 #6750A4。
 */
val md_theme_light_primary = Color(0xFF6750A4)

/**
 * 主色上的文字/图标颜色（On Primary Color）
 * 当背景为主色时，其上文字和图标的颜色，通常是白色以确保对比度。
 */
val md_theme_light_onPrimary = Color(0xFFFFFFFF)

/**
 * 主色容器（Primary Container Color）
 * 主色的浅色变体，用于容器背景（例如选中的 Chip、Card 的背景）。
 */
val md_theme_light_primaryContainer = Color(0xFFEADDFF)

/**
 * 主色容器上的文字/图标颜色（On Primary Container Color）
 * 当背景为 primaryContainer 时，其上文字和图标的颜色。
 */
val md_theme_light_onPrimaryContainer = Color(0xFF21005D)

/**
 * 次要色（Secondary Color）
 * 次一级的强调色，用于不太突出的控件（如次要按钮、开关等），丰富视觉层次。
 */
val md_theme_light_secondary = Color(0xFF625B71)

/**
 * 次要色上的文字/图标颜色
 */
val md_theme_light_onSecondary = Color(0xFFFFFFFF)

/**
 * 次要色容器（Secondary Container Color）
 */
val md_theme_light_secondaryContainer = Color(0xFFE8DEF8)

/**
 * 次要色容器上的文字/图标颜色
 */
val md_theme_light_onSecondaryContainer = Color(0xFF1E192B)

/**
 * 第三色（Tertiary Color）
 * 与 primary / secondary 互补的第三种强调色，用于特殊控件或个性化表达。
 */
val md_theme_light_tertiary = Color(0xFF7D5260)

/**
 * 第三色上的文字/图标颜色
 */
val md_theme_light_onTertiary = Color(0xFFFFFFFF)

/**
 * 第三色容器
 */
val md_theme_light_tertiaryContainer = Color(0xFFFFD8E4)

/**
 * 第三色容器上的文字/图标颜色
 */
val md_theme_light_onTertiaryContainer = Color(0xFF31111D)

/**
 * 错误色（Error Color）
 * 表示错误状态的颜色（如表单校验失败、删除操作确认等）。
 */
val md_theme_light_error = Color(0xFFBA1A1A)

/**
 * 错误色上的文字/图标颜色
 */
val md_theme_light_onError = Color(0xFFFFFFFF)

/**
 * 错误色容器（Error Container Color）
 * 错误状态的浅色背景。
 */
val md_theme_light_errorContainer = Color(0xFFFFDAD6)

/**
 * 错误色容器上的文字/图标颜色
 */
val md_theme_light_onErrorContainer = Color(0xFF410002)

/**
 * 背景色（Background Color）
 * 应用最深层的背景颜色，用于 Scaffold、页面最外层。
 */
val md_theme_light_background = Color(0xFFFFFBFE)

/**
 * 背景色上的文字/图标颜色
 */
val md_theme_light_onBackground = Color(0xFF1C1B1F)

/**
 * 表面色（Surface Color）
 * 组件（Card、BottomSheet、Dialog 等）表面的颜色，通常与 background 接近或相同。
 */
val md_theme_light_surface = Color(0xFFFFFBFE)

/**
 * 表面色上的文字/图标颜色
 */
val md_theme_light_onSurface = Color(0xFF1C1B1F)

/**
 * 表面色变体（Surface Variant Color）
 * 比 surface 略深的表面颜色，用于部分组件的背景（如列表项）。
 */
val md_theme_light_surfaceVariant = Color(0xFFE7E0EC)

/**
 * 表面色变体上的文字/图标颜色
 */
val md_theme_light_onSurfaceVariant = Color(0xFF49454F)

/**
 * 轮廓/描边色（Outline Color）
 * 用于元素边框、分割线等，语义强度介于 surface 和 text 之间。
 */
val md_theme_light_outline = Color(0xFF79747E)

/**
 * 反色表面上的文字颜色（Inverse On Surface Color）
 * 在反色表面（如 Snackbar 背景）上使用的文字颜色。
 */
val md_theme_light_inverseOnSurface = Color(0xFFF4EFF4)

/**
 * 反色表面（Inverse Surface Color）
 * 与当前表面色相反的色调，用于需要高对比度的元素（如 Snackbar、Tooltip 的背景）。
 */
val md_theme_light_inverseSurface = Color(0xFF313033)

/**
 * 反色主色（Inverse Primary Color）
 * 主色的反色版本，在深色背景上高亮显示时使用。
 */
val md_theme_light_inversePrimary = Color(0xFFD0BCFF)


// ═══════════════════════════════════════════════════════════════════════════
// 深色/暗色模式配色（Dark Theme Colors）
// ═══════════════════════════════════════════════════════════════════════════

/**
 * 主色（Dark Theme）
 * 暗色模式下的主色，比亮色模式更亮，以确保在深色背景上的可读性。
 */
val md_theme_dark_primary = Color(0xFFD0BCFF)

/**
 * 主色上的文字/图标颜色（Dark Theme）
 */
val md_theme_dark_onPrimary = Color(0xFF381E72)

/**
 * 主色容器（Dark Theme）
 * 暗色模式下主色的深色变体。
 */
val md_theme_dark_primaryContainer = Color(0xFF4F378B)

/**
 * 主色容器上的文字/图标颜色（Dark Theme）
 */
val md_theme_dark_onPrimaryContainer = Color(0xFFEADDFF)

/**
 * 次要色（Dark Theme）
 */
val md_theme_dark_secondary = Color(0xFFCCC2DC)

/**
 * 次要色上的文字/图标颜色（Dark Theme）
 */
val md_theme_dark_onSecondary = Color(0xFF332D41)

/**
 * 次要色容器（Dark Theme）
 */
val md_theme_dark_secondaryContainer = Color(0xFF4A4458)

/**
 * 次要色容器上的文字/图标颜色（Dark Theme）
 */
val md_theme_dark_onSecondaryContainer = Color(0xFFE8DEF8)

/**
 * 第三色（Dark Theme）
 */
val md_theme_dark_tertiary = Color(0xFFEFB8C8)

/**
 * 第三色上的文字/图标颜色（Dark Theme）
 */
val md_theme_dark_onTertiary = Color(0xFF492532)

/**
 * 第三色容器（Dark Theme）
 */
val md_theme_dark_tertiaryContainer = Color(0xFF633B48)

/**
 * 第三色容器上的文字/图标颜色（Dark Theme）
 */
val md_theme_dark_onTertiaryContainer = Color(0xFFFFD8E4)

/**
 * 错误色（Dark Theme）
 * 暗色模式下错误色更亮，确保在深色背景上的辨识度。
 */
val md_theme_dark_error = Color(0xFFFFB4AB)

/**
 * 错误色上的文字/图标颜色（Dark Theme）
 */
val md_theme_dark_onError = Color(0xFF690005)

/**
 * 错误色容器（Dark Theme）
 */
val md_theme_dark_errorContainer = Color(0xFF93000A)

/**
 * 错误色容器上的文字/图标颜色（Dark Theme）
 */
val md_theme_dark_onErrorContainer = Color(0xFFFFDAD6)

/**
 * 背景色（Dark Theme）
 * 暗色模式下背景为接近黑色的深灰色。
 */
val md_theme_dark_background = Color(0xFF1C1B1F)

/**
 * 背景色上的文字/图标颜色（Dark Theme）
 */
val md_theme_dark_onBackground = Color(0xFFE6E1E5)

/**
 * 表面色（Dark Theme）
 */
val md_theme_dark_surface = Color(0xFF1C1B1F)

/**
 * 表面色上的文字/图标颜色（Dark Theme）
 */
val md_theme_dark_onSurface = Color(0xFFE6E1E5)

/**
 * 表面色变体（Dark Theme）
 */
val md_theme_dark_surfaceVariant = Color(0xFF49454F)

/**
 * 表面色变体上的文字/图标颜色（Dark Theme）
 */
val md_theme_dark_onSurfaceVariant = Color(0xFFCAC4D0)

/**
 * 轮廓/描边色（Dark Theme）
 */
val md_theme_dark_outline = Color(0xFF938F99)

/**
 * 反色表面上的文字颜色（Dark Theme）
 */
val md_theme_dark_inverseOnSurface = Color(0xFF1C1B1F)

/**
 * 反色表面（Dark Theme）
 */
val md_theme_dark_inverseSurface = Color(0xFFE6E1E5)

/**
 * 反色主色（Dark Theme）
 */
val md_theme_dark_inversePrimary = Color(0xFF6750A4)
