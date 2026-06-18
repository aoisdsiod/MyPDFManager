package com.example.pdfmanager.data.model

import androidx.room.Entity
import androidx.room.ColumnInfo

/**
 * ## 数据实体：PDF-标签关联关系
 *
 * --- 映射数据库表 ---
 * 表名：`pdf_tags`
 *
 * --- 表作用 ---
 * 存储"每份 PDF 文件"与"标签"之间的多对多关联关系。
 * 当用户为某 PDF 添加标签时，在此表中写入一行；删除标签时删除对应行。
 *
 * --- 主键策略 ---
 * 使用联合主键（复合主键）：`(pdf_file_uri, category_id, tag_value)`
 * 即同一份 PDF 的同一个分类下不允许出现重复的标签值。
 *
 * --- 冗余字段说明 ---
 * 表中冗余存储了 `category_name` 和 `tag_color`，
 * 这两个字段原本来自 `tag_categories`（标签分类表），
 * 冗余在此是为了在标签筛选/展示时**避免跨表 JOIN 查询**，提升读取性能。
 */
@Entity(
    tableName = "pdf_tags",
    primaryKeys = ["pdf_file_uri", "category_id", "tag_value"]
)
data class PdfTagEntity(
    /**
     * PDF 文件在本地存储中的唯一资源标识符（URI 字符串）。
     *
     * - 类型：`String`（非空）
     * - 对应列名：`pdf_file_uri`
     * - 内容示例：`"content://media/external/file/12345"` 或 `"file:///storage/emulated/0/Documents/sample.pdf"`
     * - 联合主键组成部分：与 `categoryId` + `tagValue` 共同构成唯一行
     *
     * 作用：标识这份关联关系属于哪一份 PDF 文件。
     */
    @ColumnInfo(name = "pdf_file_uri")
    val pdfFileUri: String,

    /**
     * 标签分类的唯一 ID。
     *
     * - 类型：`String`（非空）
     * - 对应列名：`category_id`
     * - 取值来源：`tag_categories` 表的 `id` 字段
     * - 联合主键组成部分：与 `pdfFileUri` + `tagValue` 共同构成唯一行
     *
     * 作用：标识该标签属于哪个分类（如"重要性"、"类型"、"状态"），
     * 便于按分类维度对标签进行分组管理。
     */
    @ColumnInfo(name = "category_id")
    val categoryId: String,

    /**
     * 标签的具体取值（字符串值）。
     *
     * - 类型：`String`（非空）
     * - 对应列名：`tag_value`
     * - 内容示例：`"重要"`、`"待阅读"`、`"工作文档"`、`"个人"` 等
     * - 联合主键组成部分：与 `pdfFileUri` + `categoryId` 共同构成唯一行
     *
     * 作用：存储标签的实际文本内容，是用户在界面上看到和选择的标签文本。
     */
    @ColumnInfo(name = "tag_value")
    val tagValue: String,

    // ============================================================
    // 以下两个为冗余字段（从 tag_categories 表冗余而来）
    // 冗余目的：在筛选/展示标签时无需 JOIN 查询，减少 I/O 开销
    // 更新时机：当 tag_categories 表中的对应分类信息有变动时，
    // 需要同步更新本表中所有关联行的这些冗余字段。
    // ============================================================

    /**
     * 标签分类的显示名称（冗余字段）。
     *
     * - 类型：`String`（非空）
     * - 对应列名：`category_name`
     * - 取值来源：`tag_categories` 表的 `name` 字段
     * - 默认值：无（必填，冗余写入）
     *
     * 作用：在标签展示时可以直接显示分类名称，无需 JOIN 查分类表。
     * 例如展示为"分类：[重要性] → 标签：[重要]"。
     */
    @ColumnInfo(name = "category_name")
    val categoryName: String,

    /**
     * 标签在 UI 上显示时使用的颜色（冗余字段）。
     *
     * - 类型：`Int`（非空）
     * - 对应列名：`tag_color`
     * - 取值来源：`tag_categories` 表的 `color` 字段
     * - 格式说明：Android 中的颜色整数值（ARGB 格式）
     *   例如 `0xFF4CAF50` 表示绿色
     *
     * 作用：在标签展示时可以直接使用颜色值，无需 JOIN 查分类表。
     * 不同分类的标签以不同颜色展示，帮助用户快速区分标签类别。
     */
    @ColumnInfo(name = "tag_color")
    val tagColor: Int
)
