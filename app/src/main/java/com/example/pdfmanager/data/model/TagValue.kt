package com.example.pdfmanager.data.model

/**
 * ## 标签值数据类（带创建时间）
 *
 * 代表 [TagCategory.tags] 中预定义的一个标签值条目。
 * 与 [Tag] 不同，[TagValue] 仅表示类别下可供选择的"候选项"，
 * 而 [Tag] 表示用户实际"已选取"并关联到某个 [PdfFile] 的标签实例。
 *
 * 此类在 [TagCategory] 编辑界面中使用：用户可以在类别管理中添加/删除预定义标签值，
 * 这些值以 [TagValue] 的形式被序列化存储（通常通过 Gson 转为 JSON 存入数据库中
 * [tag_categories] 表的某个字段）。当用户为某个 PDF 打标签时，系统展示这些预定义值供快速选择。
 *
 * 设计演进：从最初的 `List<String>`（仅文本）升级为 [TagValue]（文本 + 创建时间），
 * 以支持按创建时间排序和追踪标签值添加历史。
 *
 * @property value     标签值文本（如 "2024""机密""设计评审"），用于标签选择列表展示
 * @property createdAt 该标签值的创建时间戳（毫秒，调用 [System.currentTimeMillis] 获取），
 *                     用于在标签选择列表中按添加先后排序展示（新添加的排在末尾或靠前）
 */
data class TagValue(
    val value: String = "",
    val createdAt: Long = System.currentTimeMillis()
)
