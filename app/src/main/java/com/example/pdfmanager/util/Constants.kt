package com.example.pdfmanager.util

/**
 * ## 缩略图大小枚举
 *
 * 定义 PDF 文件预览缩略图的三种尺寸档位，用于控制 PDF 浏览网格布局中
 * 每行显示的缩略图数量及单个缩略图的尺寸。
 *
 * 对应技术执行书 §3.4 "三档设置"。
 *
 * ### 档位说明
 * - [LARGE] —— 大缩略图，每行显示 **2 个**，单张预加载范围前后各 8 个
 * - [MEDIUM] —— 中缩略图，每行显示 **3 个**（默认）, 单张预加载范围前后各 16 个
 * - [SMALL] —— 小缩略图，每行显示 **4 个**，单张预加载范围前后各 24 个
 *
 * ### 调用位置（按引用频率排序）
 * | 文件 | 引用方式 |
 * |------|----------|
 * | `AllFilesScreen.kt` | 控制"所有文件"页面的网格列数、缩略图宽高、预加载范围 |
 * | `AllFilesViewModel.kt` | 保存当前选择的缩略图档位，提供切换逻辑 |
 * | `FavoriteFolderContentScreen.kt` | 控制收藏夹内容页面的网格列数与缩略图尺寸 |
 * | `SettingsScreen.kt` | 在设置页面中提供"缩略图尺寸"选择器（大/中/小） |
 * | `PdfThumbnail.kt` | 根据枚举值决定缩略图的加载尺寸参数 |
 * | `PreferencesManager.kt` | 持久化存储用户选择的缩略图档位（整数索引） |
 */
enum class ThumbSize {
    /** 大缩略图 —— 每行显示 2 个，缩略图尺寸约 180dp × 255dp */
    LARGE,

    /** 中缩略图（默认） —— 每行显示 3 个，缩略图尺寸约 120dp × 170dp */
    MEDIUM,

    /** 小缩略图 —— 每行显示 4 个，缩略图尺寸约 90dp × 127dp */
    SMALL
}

/**
 * ## 全局应用常量
 *
 * 集中管理整个应用共享的常量值，避免魔法数字和重复字符串。
 * 使用 `object` 单例模式，所有常量通过 `Constants.XXX` 方式访问。
 *
 * ### 调用位置
 * | 常量 | 调用文件 | 用途 |
 * |------|----------|------|
 * | `SKIP_FOLDER_ZIP` | `FileScanner.kt` | 扫描时跳过名为 "zip" 的文件夹 |
 * | `SKIP_FOLDER_SHARE` | `FileScanner.kt` | 扫描时跳过名为 "share" 的文件夹 |
 * | `EXT_PDF` | `FileScanner.kt` | 识别 PDF 文件扩展名过滤 |
 */
object Constants {

    /**
     * ## SKIP_FOLDER_ZIP —— 跳过的文件夹名称：zip
     *
     * 在文件扫描（[FileScanner]）过程中，名称为 "zip" 的文件夹会被整体跳过，
     * 不进入该文件夹扫描其中的 PDF 文件。
     *
     * ### 使用场景
     * - `FileScanner.kt` 第 133、174、334、401 行：在遍历目录子项时，
     *   若文件夹名称匹配此常量则跳过（`file.name != Constants.SKIP_FOLDER_ZIP`）
     * - `FileScanner.kt` 第 529 行：`isInSkipFolder()` 方法用该常量检测路径
     *   中是否包含应跳过的文件夹（路径包含 `"/zip/"` 则视为需要跳过）
     *
     * ### 设计原因
     * "zip" 文件夹通常用于存放压缩包，其目录结构可能很深且文件量大，
     * 跳过该目录可以显著提升扫描性能。
     */
    const val SKIP_FOLDER_ZIP = "zip"

    /**
     * ## SKIP_FOLDER_SHARE —— 跳过的文件夹名称：share
     *
     * 在文件扫描（[FileScanner]）过程中，名称为 "share" 的文件夹会被整体跳过，
     * 不进入该文件夹扫描其中的 PDF 文件。
     *
     * ### 使用场景
     * - `FileScanner.kt` 第 133、174、334、401 行：在遍历目录子项时，
     *   若文件夹名称匹配此常量则跳过（`file.name != Constants.SKIP_FOLDER_SHARE`）
     * - `FileScanner.kt` 第 530 行：`isInSkipFolder()` 方法用该常量检测路径
     *   中是否包含应跳过的文件夹（路径包含 `"/share/"` 则视为需要跳过）
     *
     * ### 设计原因
     * "share" 文件夹通常用于存放应用间共享的临时文件，其中可能包含大量
     * 非用户期望管理的 PDF 文件，跳过该目录可以减少不必要的扫描开销。
     */
    const val SKIP_FOLDER_SHARE = "share"

    /**
     * ## EXT_PDF —— PDF 文件扩展名
     *
     * 用于在文件扫描和过滤过程中识别 PDF 格式文件。
     *
     * ### 使用场景
     * `FileScanner.kt` 多处使用该常量进行扩展名匹配：
     * - `file.name.endsWith(Constants.EXT_PDF, ignoreCase = true)`（第 136、179、340、407、497 行）
     * - 扫描文件夹时过滤出 PDF 文件，非 PDF 格式文件会被忽略
     *
     * ### 设计原因
     * 将 `.pdf` 字面量抽取为常量，便于统一修改扩展名匹配规则，
     * 同时避免硬编码字符串散落在代码各处。
     */
    const val EXT_PDF = ".pdf"
}
