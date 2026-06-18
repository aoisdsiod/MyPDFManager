package com.example.pdfmanager.data.model

import androidx.room.ColumnInfo

/**
 * 标签筛选项 POJO（Plain Old Java Object）数据类
 *
 * 用于在 PDF 列表筛选界面中展示所有已被使用的标签，供用户勾选筛选。
 * 该类由 Room 的原始查询（RawQuery 或自定义 DAO 查询）直接映射返回，
 * 查询 SQL 类似：
 *   SELECT DISTINCT tags.tag_value, tags.category_id, categories.name AS category_name, categories.color AS tag_color
 *   FROM tags
 *   INNER JOIN categories ON tags.category_id = categories.id
 *
 * 使用场景：
 *   - TagFilterDialog 或 BottomSheet 中展示筛选选项列表
 *   - 用户可多选标签进行 PDF 文档的筛选
 *   - 已选标签组合作为筛选条件传入 Repository 查询
 *
 * @property tagValue 标签的具体值/名称
 *             SQL 列名：tag_value
 *             示例："紧急"，"财务报告"，"个人"，"2024年度"
 *             格式：String 类型，用户自定义的标签文本
 *             用于展示在筛选列表中供用户勾选，同时作为查询条件匹配 PDF 文档
 *
 * @property categoryId 标签所属分类的唯一标识 ID
 *             SQL 列名：category_id
 *             示例："cat_001"，"default_category_work"
 *             格式：String 类型，UUID 或自定义主键
 *             用于关联 category_name 和 tag_color，也用于按分类归类显示标签
 *
 * @property categoryName 标签所属分类的名称
 *             SQL 列名：category_name
 *             示例："工作"，"个人"，"财务"，"学习"
 *             格式：String 类型
 *             用于在界面上将标签按分类分组展示，方便用户快速定位
 *
 * @property tagColor 标签颜色（ARGB 颜色整数值）
 *             SQL 列名：tag_color
 *             示例：0xFF2196F3（蓝色），0xFF4CAF50（绿色）
 *             格式：Int 类型，标准 Android ARGB 颜色值
 *             用于在标签项前面显示一个小圆点或色块作为视觉标识
 *             可配合 ContextCompat.getColor() 或直接使用 Color.valueOf() 解析
 */
data class TagFilterItem(
    @ColumnInfo(name = "tag_value")
    val tagValue: String,

    @ColumnInfo(name = "category_id")
    val categoryId: String,

    @ColumnInfo(name = "category_name")
    val categoryName: String,

    @ColumnInfo(name = "tag_color")
    val tagColor: Int
)
