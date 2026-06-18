package com.example.pdfmanager.data.model

import java.util.UUID

/**
 * ## 标签类别数据类
 *
 * 代表一组相关标签的类别容器，用于对标签进行分组管理。
 * 例如用户可以创建 "年份" 类别，然后在其中添加 "2023""2024""2025" 等标签值；
* 或创建 "作者" 类别，添加 "张三""李四" 等标签值。
 *
 * 每个类别有一个固定的代表色，该类别下所有标签（[Tag]）共享此颜色，
 * 以在 UI 上形成视觉分组效果。类别不可嵌套，为一层扁平结构。
 *
 * 数据库对应 [tag_categories] 表，主键为 [id]，
 * 通过外键关联到 [tag_values]（预定义的标签值列表）和 [tags]（已使用的标签实例）。
 *
 * @property id        唯一标识符（UUID 字符串），默认随机生成
 * @property name      类别名称（如 "年份""作者""项目""优先级""类型"），用于类别标题展示
 * @property color     该类别的代表色（ARGB 整数格式），
 *                     该类别下所有标签共用此颜色，默认值为 0xFF607D8B（蓝灰色）
 * @property tags      该类别下预定义的标签值列表（[TagValue] 对象列表），
 *                     在标签选择页展示给用户快速选取，而非手动输入
 *                     （注：原设计为 `List<String>`，后改为 [TagValue] 以记录每个标签值的创建时间）
 * @property createdAt 类别创建时间戳（毫秒，调用 [System.currentTimeMillis] 获取），
 *                     用于类别列表的排序显示
 * @property sortOrder 类别排序权重（数值越小越靠前），
 *                     用于用户在类别管理中通过拖拽/按钮自定义排列顺序
 */
data class TagCategory(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val color: Int = 0xFF607D8B.toInt(),
    val tags: List<TagValue> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val sortOrder: Int = 0
) {
    companion object {
        /**
         * ## 调色板（12 色预定义颜色列表）
         *
         * 在创建新类别时，供用户从这 12 种颜色中选择作为 [TagCategory.color]。
         * 颜色取自 Material Design 调色板 A400 级别，涵盖红、橙、黄、绿、蓝、紫、粉等色系。
         *
         * 选择策略：按 [COLOR_PALETTE] 列表循环分配，避免相邻类别颜色相同。
         * 每创建一个新类别，自动从列表中取下一个可用颜色。
         *
         * ### 颜色说明
         * | 索引 | 颜色     | 值           |
         * |------|----------|--------------|
         * | 0    | 红色     | 0xFFFF5252   |
         * | 1    | 橙色     | 0xFFFF6D00   |
         * | 2    | 黄色     | 0xFFFFD600   |
         * | 3    | 绿色     | 0xFF00E676   |
         * | 4    | 浅蓝色   | 0xFF00B0FF   |
         * | 5    | 蓝色     | 0xFF2979FF   |
         * | 6    | 紫色     | 0xFF651FFF   |
         * | 7    | 粉紫色   | 0xFFD500F9   |
         * | 8    | 粉红色   | 0xFFFF4081   |
         * | 9    | 棕色     | 0xFF795548   |
         * | 10   | 蓝灰色   | 0xFF607D8B   |
         * | 11   | 深灰色   | 0xFF424242   |
         */
        val COLOR_PALETTE = listOf(
            0xFFFF5252.toInt(), // 红
            0xFFFF6D00.toInt(), // 橙
            0xFFFFD600.toInt(), // 黄
            0xFF00E676.toInt(), // 绿
            0xFF00B0FF.toInt(), // 浅蓝
            0xFF2979FF.toInt(), // 蓝
            0xFF651FFF.toInt(), // 紫
            0xFFD500F9.toInt(), // 粉紫
            0xFFFF4081.toInt(), // 粉红
            0xFF795548.toInt(), // 棕
            0xFF607D8B.toInt(), // 蓝灰
            0xFF424242.toInt()  // 深灰
        )
    }
}
