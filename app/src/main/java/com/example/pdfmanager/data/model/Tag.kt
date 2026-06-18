package com.example.pdfmanager.data.model

import java.util.UUID

/**
 * ## 标签数据类
 *
 * 代表一个已附加到具体 PDF 文件上的标签实例。
 * 当用户为某个 PDF 选择某个类别下的某个标签值时，系统创建一个 [Tag] 实例，
 * 并通过关系表（如 [pdf_tags]）将其与 [PdfFile] 关联。
 *
 * 该模型冗余存储了 `categoryName` 和 `color` 字段，目的是在展示标签列表时
 * 避免每次都查询 [TagCategory] 表，以提升 UI 渲染性能（以空间换时间）。
 *
 * ### 数据库映射
 * 通过 Room 的 [@Entity] 或自定义序列化映射到 [tags] 表，
 * 其中 `id` 为主键，通过外键关联到 [pdf_tag_relations] 中间表。
 *
 * @property id           唯一标识符（UUID 字符串），默认随机生成
 * @property categoryId   所属标签类别的 ID（对应 [TagCategory.id]），
 *                        用于在筛选时按类别分组展示
 * @property categoryName 所属类别的名称（冗余存储，如 "年份""作者"），
 *                        在标签列表页直接展示，避免跨表查询
 * @property value        标签值文本（用户自由输入，如 "2024" "张三" "机密"），
 *                        用于标签显示和全文搜索匹配
 * @property color        该标签所属类别的代表色（ARGB 整数格式），
 *                        冗余存储以直接用于标签 Chip 的背景色，
 *                        （默认值 0xFF607D8B = 蓝灰色）
 * @property createdAt    标签创建时间戳（毫秒，调用 [System.currentTimeMillis] 获取），
 *                        用于按时间排序和管理历史
 */
data class Tag(
    val id: String = UUID.randomUUID().toString(),
    val categoryId: String = "",
    val categoryName: String = "",
    val value: String = "",
    val color: Int = 0xFF607D8B.toInt(),
    val createdAt: Long = System.currentTimeMillis()
)
