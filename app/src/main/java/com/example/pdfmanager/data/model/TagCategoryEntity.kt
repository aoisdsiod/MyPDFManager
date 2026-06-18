package com.example.pdfmanager.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.ColumnInfo
import java.util.UUID

/**
 * ## TagCategoryEntity — Room 数据库实体，对应 tag_categories 表
 *
 * ### 数据库表结构
 * 表名：`tag_categories`
 *
 * 该表存储标签类别的元信息。标签类别是标签的分组容器，
 * 例如用户可以创建"年份"类别（下含"2023""2024"标签值）、
 * "项目"类别（下含"市场营销""研发"标签值）等。
 *
 * ### 表字段一览
 * | 列名        | 类型    | 约束                 | 说明                            |
 * |-------------|---------|-----------------------|--------------------------------|
 * | id          | TEXT    | PRIMARY KEY, NOT NULL | 类别唯一标识符（UUID）          |
 * | name        | TEXT    | NOT NULL              | 类别名称（如"年份""项目"）      |
 * | color       | INTEGER | NOT NULL              | 类别代表色（ARGB 整数格式）      |
 * | sort_order  | INTEGER | NOT NULL              | 排序权重（越小越靠前）           |
 * | created_at  | INTEGER | NOT NULL              | 创建时间戳（毫秒）              |
 *
 * ### 实体关系
 * - [TagCategoryEntity] 与 [CategoryTagEntity] 存在一对多关系：
 *   一个类别下可以包含多个预先定义的标签值（CategoryTagEntity）。
 *   通过 [CategoryTagEntity.categoryId] 外键关联。
 * - [TagCategoryEntity] 与 [PdfTagEntity] 存在间接关联：
 *   标签值被附加到 PDF 文件上形成 PdfTagEntity，
 *   通过 [PdfTagEntity.categoryId] 完成关联。
 *
 * ### 数据流向
 * ```
 * Room (tag_categories 表)                   Room (category_tags 表)
 *        ↓                                            ↓
 * TagCategoryEntity                      CategoryTagEntity
 *        ↓                                            ↓
 *        └────────────► TagCategory ◄──────────────────┘
 *                    (域模型，包含 tags 列表)
 *                              ↓
 *                  ViewModel / UI 层使用
 * ```
 *
 * ### 转换函数
 * - `fromTagCategory()`: Domain Model → Entity（给 Repository 层使用）
 * - `toTagCategory()`: Entity → Domain Model（给 Repository 层使用）
 *
 * ### 使用场景
 * - TagCategoryDao：所有 CRUD 操作均通过此 DAO 进行
 * - TagRepository：加载和保存标签类别时进行 Entity ↔ Domain Model 互转
 * - 标签管理页面：用户创建/编辑/重命名/排序/删除标签类别
 *
 * @see TagCategory TagCategory 域模型（与此 Entity 互转）
 * @see TagCategoryDao 操作此表的 DAO 接口
 * @see TagRepository 使用此 Entity 的仓库层
 * @see CategoryTagEntity 关联的表（标签值表）
 */
@Entity(tableName = "tag_categories")
data class TagCategoryEntity(
    /**
     * 标签类别唯一标识符
     *
     * - **类型**: String
     * - **格式**: UUID 字符串（例："a1b2c3d4-e5f6-7890-abcd-ef1234567890"）
     * - **生成方式**: 创建 TagCategory 域模型时由 UUID.randomUUID() 生成
     * - **不可空**: 是，主键不可为空
     * - **默认值**: 无（必须在创建时提供）
     *
     * 【用途】
     * - 作为 tag_categories 表的主键，唯一标识一个标签类别
     * - 用于 TagCategoryDao 的 getById()、deleteById() 等操作
     * - 作为外键被 CategoryTagEntity.categoryId 引用
     * - 作为外键被 PdfTagEntity.categoryId 引用
     *
     * 【关联查询】
     * - CategoryTagDao.getByCategoryId(categoryId) — 查询该类别下的所有标签值
     * - PdfTagDao.getByCategoryId(categoryId) — 查询该类别下所有 PDF 关联
     */
    @PrimaryKey
    val id: String,

    /**
     * 标签类别名称
     *
     * - **类型**: String
     * - **格式**: 纯文本字符串
     *   - 常见值："年份"、"月份"、"项目"、"作者"、"优先级"、"类型"、"状态"
     * - **不可空**: 是
     * - **默认值**: 无
     * - **唯一性约束**: 逻辑层面保证（不允许创建同名类别，见 TagRepository.addCategory()）
     *
     * 【用途】
     * - 标签列表页按类别分组展示时作为组标题
     * - 标签筛选器中的类别标签
     * - 用于用户识别不同的标签分组
     *
     * 【验证规则】
     * - 不允许为空字符串（由 UI 层和 TagRepository 共同校验）
     * - 同一数据库中不允许有同名类别（由 TagRepository.addCategory() 检测）
     *
     * @see TagCategoryDao.updateName 更新类别名称的 DAO 方法
     */
    @ColumnInfo(name = "name")
    val name: String,

    /**
     * 该类别的代表色（ARGB 整数格式）
     *
     * - **类型**: Int
     * - **格式**: Android ARGB 颜色整型
     *   - 例：0xFFFF5252 = 红色，0xFF2979FF = 蓝色
     *   - 默认值：0xFF607D8B（蓝灰色）
     * - **不可空**: 是
     * - **默认值**: 无（创建时从 TagCategory.COLOR_PALETTE 分配）
     *
     * 【用途】
     * - 该类别下所有标签在 UI 上共享此颜色（视觉分组效果）
     * - 标签 Chip/Button 的背景色或边框色
     * - 类别图标的颜色
     *
     * 【颜色分配策略】
     * - 创建新类别时从 TagCategory.COLOR_PALETTE（12 色调色板）中自动分配
     * - 优先分配未使用的颜色
     * - 如果 12 色已全部使用，则循环取色
     * - 允许用户手动修改颜色（通过 TagRepository.updateCategory()）
     *
     * 【冗余存储说明】
     * - Tag 域模型中同样存储了 color 字段（冗余），
     *   目的是在展示标签列表时减少跨表查询
     *
     * @see TagCategory.COLOR_PALETTE 预定义调色板
     * @see TagCategoryDao.updateColor 更新颜色的 DAO 方法
     */
    @ColumnInfo(name = "color")
    val color: Int,

    /**
     * 类别排序权重
     *
     * - **类型**: Int
     * - **格式**: 非负整数，数值越小排序越靠前
     *   - 例：0 = 最靠前，1 = 第二位，以此类推
     * - **不可空**: 是
     * - **默认值**: 无（创建时自动计算：当前最大 sortOrder + 1）
     *
     * 【用途】
     * - 标签管理列表的排序依据（ORDER BY sort_order ASC）
     * - 标签选择器中的类别排列顺序
     * - 用户通过上移/下移操作调整顺序（交换 sortOrder 值）
     *
     * 【排序操作】
     * - 上移：当前类别的 sortOrder 与上一类别交换
     * - 下移：当前类别的 sortOrder 与下一类别交换
     * - 操作后自动调用 TagRepository.saveCategories() 持久化
     *
     * @see TagCategoryDao.updateSortOrder 更新排序权重的 DAO 方法
     * @see TagRepository.moveCategoryUp 上移类别操作
     * @see TagRepository.moveCategoryDown 下移类别操作
     */
    @ColumnInfo(name = "sort_order")
    val sortOrder: Int,

    /**
     * 类别创建时间（毫秒时间戳）
     *
     * - **类型**: Long
     * - **格式**: System.currentTimeMillis() 格式的毫秒级时间戳
     *   - 例：1718611200000 = 2024-06-17 08:00:00 GMT
     * - **不可空**: 是
     * - **默认值**: 无（创建时由 System.currentTimeMillis() 自动设置）
     *
     * 【用途】
     * - 标签管理页中按创建时间排序展示（次要排序依据）
     * - 信息展示：显示类别创建时间
     * - 数据分析：统计标签系统的使用历史
     */
    @ColumnInfo(name = "created_at")
    val createdAt: Long
) {
    companion object {
        /**
         * ## fromTagCategory — Domain Model 转 Entity
         *
         * 将业务层使用的域模型 [TagCategory] 转换为 Room 数据库实体 [TagCategoryEntity]。
         * 此方法在 Repository 层中被调用，用于将标签类别数据持久化到 Room 数据库。
         *
         * ### 转换说明
         * - 所有字段直接一对一映射（名称和类型均相同）
         * - TagCategory.tags 字段（List<TagValue>）不存储在 TagCategoryEntity 中，
         *   而是通过 CategoryTagEntity 独立存储（一个类别对应多条标签值记录）
         *
         * ### 调用位置
         * - `TagRepository.saveCategories()`: 保存所有标签类别时调用
         *   ```
         *   val categoryEntity = TagCategoryEntity.fromTagCategory(category)
         *   tagCategoryDao.insert(categoryEntity)
         *   ```
         * - `TagRepository.addCategory()`: 添加单个标签类别时调用
         *   ```
         *   val entity = TagCategoryEntity.fromTagCategory(newCategory)
         *   tagCategoryDao.insert(entity)
         *   ```
         *
         * ### 使用场景
         * - 标签管理页保存后，批量写入 Room 数据库
         * - 用户新增单个标签类别
         * - 用户修改类别属性（名称/颜色/排序）后更新数据库
         *
         * @param tc TagCategory 域模型对象（包含类别元信息和标签值列表）
         * @return TagCategoryEntity Room 数据库实体
         *
         * @see TagCategory TagCategory 域模型
         * @see TagRepository.saveCategories 调用此方法的仓库函数
         * @see TagRepository.addCategory 调用此方法的仓库函数
         */
        fun fromTagCategory(tc: TagCategory): TagCategoryEntity {
            return TagCategoryEntity(
                id = tc.id,
                name = tc.name,
                color = tc.color,
                sortOrder = tc.sortOrder,
                createdAt = tc.createdAt
            )
        }
    }

    /**
     * ## toTagCategory — Entity 转 Domain Model
     *
     * 将 Room 数据库实体 [TagCategoryEntity] 转换为业务层使用的域模型 [TagCategory]。
     * 此方法在 Repository 层中被调用，用于将数据库中的数据提供给 ViewModel 和 UI 层使用。
     *
     * ### 转换说明
     * - tags 参数接收该类别下的所有 [CategoryTagEntity] 列表
     * - 每个 [CategoryTagEntity] 通过 it.toTagValue() 转为 [TagValue] 域模型
     * - 其余字段直接一对一映射
     *
     * ### 调用位置
     * - `TagRepository.loadCategories()`: 加载所有标签类别时调用
     *   ```
     *   val categoryEntities = tagCategoryDao.getAll()
     *   for (categoryEntity in categoryEntities) {
     *       val tagEntities = categoryTagDao.getByCategoryId(categoryEntity.id)
     *       val tagCategory = categoryEntity.toTagCategory(tagEntities)
     *       result.add(tagCategory)
     *   }
     *   ```
     * - `TagRepository.updateCategory()`: 更新类别后刷新内存缓存时调用
     *   ```
     *   val tagEntities = categoryTagDao.getByCategoryId(categoryId)
     *   updatedList[index] = updatedEntity.toTagCategory(tagEntities)
     *   ```
     *
     * ### 使用场景
     * - 应用启动，从 Room 数据库加载标签类别到内存缓存
     * - 更新类别后，刷新内存中的该类别数据
     *
     * @param tags 该类别下所有标签值的实体列表（从 CategoryTagDao 获取）
     * @return TagCategory 域模型对象（包含标签值列表）
     *
     * @see TagCategory TagCategory 域模型
     * @see CategoryTagEntity.toTagValue 子实体转 TagValue 域模型
     * @see TagRepository.loadCategories 调用此方法的仓库函数
     * @see TagRepository.updateCategory 调用此方法的仓库函数
     */
    fun toTagCategory(tags: List<CategoryTagEntity>): TagCategory {
        return TagCategory(
            id = id,
            name = name,
            color = color,
            tags = tags.map { it.toTagValue() },  // 将每个子实体转为 TagValue
            createdAt = createdAt,
            sortOrder = sortOrder
        )
    }
}
