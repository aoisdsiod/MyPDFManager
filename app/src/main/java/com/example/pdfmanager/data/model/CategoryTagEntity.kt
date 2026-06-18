package com.example.pdfmanager.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.ColumnInfo
import java.util.UUID

/**
 * ## CategoryTagEntity — Room 数据库实体，对应 category_tags 表
 *
 * ### 数据库表结构
 * 表名：`category_tags`
 *
 * 该表存储标签类别下预先定义的标签值（"候选项"）。
 * 例如在"年份"类别下，可以预先定义"2023""2024""2025"等标签值。
 * 用户在给 PDF 打标签时，可以直接从这些预定义值中快速选择，
 * 无需手动输入。
 *
 * ### 表字段一览
 * | 列名        | 类型    | 约束                 | 说明                                      |
 * |-------------|---------|-----------------------|-------------------------------------------|
 * | id          | TEXT    | PRIMARY KEY, NOT NULL | 标签值唯一标识符（UUID）                   |
 * | category_id | TEXT    | NOT NULL              | 所属类别的 ID（外键，关联 tag_categories） |
 * | value       | TEXT    | NOT NULL              | 标签值文本（如"2024"、"机密"）            |
 * | created_at  | INTEGER | NOT NULL              | 标签值创建时间戳（毫秒）                  |
 *
 * ### 实体关系
 * - [CategoryTagEntity] 通过 [categoryId] 外键关联到 [TagCategoryEntity.id]，
 *   构成一对多的关系（一个类别下有多个标签值）。
 * - [CategoryTagEntity] 与 [TagValue] 域模型对应，
 *   但 TagValue 不包含 categoryId（因为 TagValue 总是从属于某个 TagCategory）。
 *
 * ### 三种标签相关模型的关系
 * ```
 * TagCategory（标签类别，如"年份"）
 *     │
 *     ├── CategoryTagEntity / TagValue（预定义标签值，如"2024""2025"）
 *     │   └── 用户在类别编辑器中管理这些预定义值
 *     │
 * Tag（已使用的标签实例）
 *     └── 用户为某个 PDF 文件选择一个标签值后产生
 *         - 存储在 pdf_tags 表中（通过 PdfTagEntity）
 *         - 冗余存储 categoryName 和 color（提升查询性能）
 * ```
 *
 * ### 转换函数
 * - `fromTagValue()`: TagValue → Entity（给 Repository 层使用）
 * - `toTagValue()`: Entity → TagValue（给 Repository 层使用）
 *
 * ### 使用场景
 * - CategoryTagDao：所有 CRUD 操作均通过此 DAO 进行
 * - TagRepository：加载和保存标签类别时关联操作此表
 * - 标签管理页：用户添加/删除/重命名预定义标签值
 *
 * @see TagValue TagValue 域模型（与此 Entity 互转，不包含 categoryId）
 * @see TagCategoryEntity 关联的表（标签类别表）
 * @see CategoryTagDao 操作此表的 DAO 接口
 * @see TagRepository 使用此 Entity 的仓库层
 */
@Entity(
    tableName = "category_tags"
)
data class CategoryTagEntity(
    /**
     * 标签值唯一标识符
     *
     * - **类型**: String
     * - **格式**: UUID 字符串（例："f1e2d3c4-b5a6-7890-abcd-ef1234567890"）
     * - **生成方式**: 在 fromTagValue() 中由 UUID.randomUUID() 生成
     * - **不可空**: 是，主键不可为空
     * - **默认值**: 无（必须在创建时提供）
     *
     * 【用途】
     * - 作为 category_tags 表的主键，唯一标识一个标签值记录
     * - 用于 CategoryTagDao 的删除操作
     *
     * 【注意】
     * - 此 ID 主要用于 Room 的内部记录管理
     * - 业务层（域模型）的 TagValue 不包含此 ID
     * - 如果将来需要反查用户通过哪个标签值添加了标签，可通过此 ID 追踪
     */
    @PrimaryKey
    val id: String,

    /**
     * 所属标签类别的 ID（外键）
     *
     * - **类型**: String
     * - **格式**: UUID 字符串，与 TagCategoryEntity.id 格式一致
     * - **不可空**: 是
     * - **默认值**: 无（必须在创建时提供）
     *
     * 【用途】
     * - 与 TagCategoryEntity 建立一对多的外键关系
     * - CategoryTagDao.getByCategoryId() 根据此字段查询某类别下的所有标签值
     * - CategoryTagDao.deleteByCategoryId() 根据此字段级联删除某类别的所有标签值
     * - 删除类别时，通过此字段定位需要清理的标签值记录
     *
     * 【查询示例】
     * ```sql
     * -- 查询某类别下的所有标签值
     * SELECT * FROM category_tags WHERE category_id = :categoryId ORDER BY created_at ASC
     *
     * -- 删除某类别下的所有标签值
     * DELETE FROM category_tags WHERE category_id = :categoryId
     * ```
     *
     * @see CategoryTagDao.getByCategoryId 按类别 ID 查询标签值
     * @see CategoryTagDao.deleteByCategoryId 按类别 ID 删除标签值
     */
    @ColumnInfo(name = "category_id")
    val categoryId: String,

    /**
     * 标签值文本
     *
     * - **类型**: String
     * - **格式**: 纯文本字符串
     *   - 常见值："2024"、"机密"、"设计评审"、"张三"、"市场营销"
     * - **不可空**: 是
     * - **默认值**: 无
     * - **唯一性约束**: 同一类别内不允许重复（由 TagRepository.addTagValue() 检测）
     *
     * 【用途】
     * - 标签选择列表中的展示文本
     * - 用户添加标签时直接选中的值
     * - 标签值重命名操作的 UI 展示
     * - 在给 PDF 打标签时，通过此值区分不同的标签
     *
     * 【验证规则】
     * - 不允许为空字符串（由 UI 层和 TagRepository 共同校验）
     * - 同一 categoryId 下不允许存在相同的 value（由 TagRepository 检测）
     *
     * 【与 PdfTagEntity.tagValue 的关系】
     * - PdfTagEntity.tagValue 存储的是最终的标签值文本（与 CategoryTagEntity.value 相同）
     * - 但 PdfTagEntity 不通过外键引用 CategoryTagEntity，而是直接存储文本值
     * - 当用户重命名某个标签值时，需要级联更新 PdfTagEntity 中对应的 tagValue
     *
     * @see CategoryTagDao.getByCategoryIdAndValue 按类别和值精确查询
     * @see CategoryTagDao.deleteByCategoryIdAndValue 按类别和值精确删除
     */
    @ColumnInfo(name = "value")
    val value: String,

    /**
     * 标签值的创建时间（毫秒时间戳）
     *
     * - **类型**: Long
     * - **格式**: System.currentTimeMillis() 格式的毫秒级时间戳
     *   - 例：1718611200000 = 2024-06-17 08:00:00 GMT
     * - **不可空**: 是
     * - **默认值**: 无（创建时由 System.currentTimeMillis() 自动设置）
     *
     * 【用途】
     * - 标签值列表按创建时间排序（ORDER BY created_at ASC）
     * - 新添加的标签值排在列表末尾（或靠前，取决于 UI 设计）
     * - 用户查看标签值添加历史
     *
     * 【排序说明】
     * - CategoryTagDao 的查询默认按 created_at ASC（升序）排列
     * - UI 层展示时可能进一步按需求反转排序
     */
    @ColumnInfo(name = "created_at")
    val createdAt: Long
) {
    /**
     * ## toTagValue — Entity 转 TagValue 域模型
     *
     * 将 Room 数据库实体 [CategoryTagEntity] 转换为简单的 [TagValue] 域模型。
     * [TagValue] 仅包含 value 和 createdAt 两个字段，不包含 id 和 categoryId。
     *
     * ### 转换说明
     * - id 字段：不转换，TagValue 不需要主键
     * - categoryId 字段：不转换，TagValue 不知道自己的所属类别
     *   （TagValue 总是通过 TagCategory.tags 列表访问，上下文已隐含类别信息）
     * - value 字段：直接映射到 TagValue.value
     * - createdAt 字段：直接映射到 TagValue.createdAt
     *
     * ### 为什么使用 TagValue 而不是 CategoryTagEntity
     * - TagCategory 域模型中需要存储标签值列表（tags: List<TagValue>）
     * - TagValue 更轻量，只包含业务关心的字段，不包含数据库主键
     * - TagValue 不依赖任何数据库框架，可以在非 Android 环境中测试
     *
     * ### 调用位置
     * - `TagCategoryEntity.toTagCategory()`: 将所有子实体统一转为 TagValue
     *   ```kotlin
     *   // TagCategoryEntity 中的调用
     *   fun toTagCategory(tags: List<CategoryTagEntity>): TagCategory {
     *       return TagCategory(
     *           ...
     *           tags = tags.map { it.toTagValue() },  // ← 在此处调用
     *           ...
     *       )
     *   }
     *   ```
     * - 最终在 `TagRepository.loadCategories()` 中被使用：
     *   ```kotlin
     *   val tagEntities = categoryTagDao.getByCategoryId(categoryEntity.id)
     *   val tagCategory = categoryEntity.toTagCategory(tagEntities)
     *   ```
     *
     * ### 使用场景
     * - 从 Room 数据库加载标签类别时，将子标签值转为 TagValue 域模型
     * - 为 UI 层提供轻量级的标签值数据对象
     *
     * @return TagValue 域模型（包含 value 和 createdAt）
     *
     * @see TagValue TagValue 域模型
     * @see TagCategoryEntity.toTagCategory 调用此方法的父实体转换函数
     * @see TagRepository.loadCategories 最终使用此数据的仓库函数
     */
    fun toTagValue(): TagValue {
        return TagValue(value = value, createdAt = createdAt)
    }

    companion object {
        /**
         * ## fromTagValue — TagValue 转 Entity
         *
         * 将轻量级域模型 [TagValue] 转换为 Room 数据库实体 [CategoryTagEntity]。
         * 此方法在 Repository 层中被调用，用于将标签值数据持久化到 Room 数据库。
         *
         * ### 转换说明
         * - id 字段：通过 UUID.randomUUID() 随机生成（CategoryTagEntity 的主键）
         * - categoryId 字段：由调用方传入（表示该标签值所属的类别）
         * - value 字段：从 TagValue.value 直接映射
         * - createdAt 字段：从 TagValue.createdAt 直接映射
         *
         * ### 调用位置
         * - `TagRepository.saveCategories()`: 保存所有标签类别时调用
         *   ```kotlin
         *   // 遍历该类别的所有 TagValue，转换为 CategoryTagEntity
         *   val tagEntities = category.tags.map { tagValue ->
         *       CategoryTagEntity.fromTagValue(category.id, tagValue)
         *   }
         *   // 先删除旧记录再批量插入
         *   categoryTagDao.deleteByCategoryId(category.id)
         *   categoryTagDao.insertAll(tagEntities)
         *   ```
         *
         * ### 使用场景
         * - 标签管理页保存时，全量替换某类别下的所有标签值记录
         *   （先 deleteByCategoryId 删除旧的，再 insertAll 插入新的）
         * - 保存机制采用"全量替换"而非"增量更新"策略，
         *   以简化逻辑并保证数据一致性
         *
         * @param categoryId 标签值所属的类别 ID（对应 TagCategoryEntity.id）
         * @param tagValue TagValue 域模型（包含标签值文本和创建时间）
         * @return CategoryTagEntity Room 数据库实体（含随机生成的 ID）
         *
         * @see TagValue TagValue 域模型
         * @see TagRepository.saveCategories 调用此方法的仓库函数
         * @see CategoryTagDao.insertAll 批量插入 DAO 方法
         * @see CategoryTagDao.deleteByCategoryId 批量删除 DAO 方法
         */
        fun fromTagValue(categoryId: String, tagValue: TagValue): CategoryTagEntity {
            return CategoryTagEntity(
                id = UUID.randomUUID().toString(),  // 随机生成唯一标识
                categoryId = categoryId,             // 所属类别 ID
                value = tagValue.value,              // 标签值文本
                createdAt = tagValue.createdAt       // 标签值创建时间
            )
        }
    }
}
