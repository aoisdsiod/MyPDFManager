package com.example.pdfmanager.data.local

import androidx.room.*
import com.example.pdfmanager.data.model.TagCategoryEntity
import kotlinx.coroutines.flow.Flow

/**
 * DAO（数据访问对象）—— 标签类别管理
 *
 * 对应的数据库表：tag_categories
 *
 * 该表用于存储标签的类别（分类）信息。例如用户可能创建"重要性"、"文档类型"、"项目"等类别，
 * 每个类别下可以有多个具体的标签值（标签值通过 CategoryTagDao 关联）。
 *
 * 字段说明（对应 TagCategoryEntity 实体）：
 *   - id          ：类别唯一标识（UUID 字符串）
 *   - name        ：类别名称，例如"重要性"、"文档类型"
 *   - color       ：类别在 UI 上显示的颜色（Int 格式的颜色值）
 *   - sort_order  ：排序序号，控制类别在界面上的显示顺序
 */
@Dao
interface TagCategoryDao {

    // ═══════════════════════════════════════════════════════════════
    //  查询操作
    // ═══════════════════════════════════════════════════════════════

    /**
     * 获取全部标签类别列表（非响应式，一次性查询）
     *
     * SQL: SELECT * FROM tag_categories ORDER BY sort_order ASC
     *   - ORDER BY sort_order ASC ：按用户自定义排序序号升序排列，序号越小越靠前
     *
     * 用途：在需要当前全部类别快照的场景中使用，例如一次性加载配置、数据统计或导出。
     * 调用位置：Repository 层、后台任务、一次性数据加载
     *
     * @return List<TagCategoryEntity> 当前数据库中的全部标签类别列表
     */
    @Query("SELECT * FROM tag_categories ORDER BY sort_order ASC")
    suspend fun getAll(): List<TagCategoryEntity>

    /**
     * 获取全部标签类别列表，返回响应式 Flow 流
     *
     * SQL: SELECT * FROM tag_categories ORDER BY sort_order ASC
     *   - ORDER BY sort_order ASC ：按用户自定义排序序号升序排列
     *
     * 用途：在标签类别管理页面展示列表，Flow 保证当数据库中的类别数据发生变化时，
     *       自动推送更新后的列表，无需手动刷新。
     * 调用位置：标签类别列表页面、ViewModel 层 collect 该 Flow
     *
     * @return Flow<List<TagCategoryEntity>> 可观察的标签类别列表
     */
    @Query("SELECT * FROM tag_categories ORDER BY sort_order ASC")
    fun getAllFlow(): Flow<List<TagCategoryEntity>>

    /**
     * 根据 ID 查询单个标签类别
     *
     * SQL: SELECT * FROM tag_categories WHERE id = :id
     *
     * 用途：根据类别 id 获取详情，用于编辑类别属性、判断类别是否存在等场景。
     * 调用位置：类别编辑页面、导航逻辑
     *
     * @param id 标签类别的唯一标识
     * @return TagCategoryEntity? 若不存在则返回 null
     */
    @Query("SELECT * FROM tag_categories WHERE id = :id")
    suspend fun getById(id: String): TagCategoryEntity?

    /**
     * 获取标签类别的总数
     *
     * SQL: SELECT COUNT(*) FROM tag_categories
     *   - COUNT(*) 是 SQL 聚合函数，返回表中的总行数
     *
     * 用途：用于统计展示、分页计算或判断是否可以继续添加新类别（如有数量上限）。
     * 调用位置：统计面板、类别管理界面的数量显示
     *
     * @return Int 当前标签类别的总数
     */
    @Query("SELECT COUNT(*) FROM tag_categories")
    suspend fun getCount(): Int

    // ═══════════════════════════════════════════════════════════════
    //  插入 / 更新操作
    // ═══════════════════════════════════════════════════════════════

    /**
     * 插入或替换一条标签类别记录
     *
     * SQL: INSERT INTO tag_categories ... ON CONFLICT(id) REPLACE
     *   - 使用 OnConflictStrategy.REPLACE 策略：当主键 id 冲突时，
     *     用新数据完全覆盖旧数据（相当于先删后插）
     *
     * 用途：创建新标签类别时插入，或从远程同步时批量替换已有类别。
     * 调用位置：类别创建流程、数据同步逻辑
     *
     * @param category 待插入/替换的 TagCategoryEntity 实体对象
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(category: TagCategoryEntity)

    /**
     * 更新一条标签类别记录（需提供完整的实体对象）
     *
     * SQL: UPDATE tag_categories SET ... WHERE id = :category.id
     *   - Room 通过主键 id 定位记录，并将实体对象中所有非 null 字段更新到数据库
     *
     * 用途：批量更新类别多个属性时使用，调用方需要构造包含更新后全部字段的实体对象。
     * 调用位置：类别编辑保存流程
     *
     * @param category 包含更新后全部字段的 TagCategoryEntity 实体对象
     */
    @Update
    suspend fun update(category: TagCategoryEntity)

    /**
     * 仅更新标签类别的名称
     *
     * SQL: UPDATE tag_categories SET name = :name WHERE id = :id
     *   - 精确更新：只修改 name 字段，其他字段保持不变
     *
     * 用途：用户仅修改类别名称时调用，避免构造完整的实体对象。
     * 调用位置：类别重命名流程
     *
     * @param id   待更新类别的 id
     * @param name 新的类别名称
     */
    @Query("UPDATE tag_categories SET name = :name WHERE id = :id")
    suspend fun updateName(id: String, name: String)

    /**
     * 仅更新标签类别的颜色
     *
     * SQL: UPDATE tag_categories SET color = :color WHERE id = :id
     *   - 精确更新：只修改 color 字段，其他字段保持不变
     *
     * 用途：用户更改类别在 UI 上的显示颜色时调用。
     * 调用位置：类别颜色选择器流程
     *
     * @param id    待更新类别的 id
     * @param color 新的颜色值（Int 格式，通常为 ARGB 颜色值）
     */
    @Query("UPDATE tag_categories SET color = :color WHERE id = :id")
    suspend fun updateColor(id: String, color: Int)

    /**
     * 仅更新标签类别的排序序号
     *
     * SQL: UPDATE tag_categories SET sort_order = :sortOrder WHERE id = :id
     *   - 精确更新：只修改 sort_order 字段，其他字段保持不变
     *
     * 用途：用户拖拽排序后，更新类别在列表中的位置时调用。
     * 调用位置：类别排序/拖拽排序流程
     *
     * @param id        待更新类别的 id
     * @param sortOrder 新的排序序号（数值越小越靠前）
     */
    @Query("UPDATE tag_categories SET sort_order = :sortOrder WHERE id = :id")
    suspend fun updateSortOrder(id: String, sortOrder: Int)

    // ═══════════════════════════════════════════════════════════════
    //  删除操作
    // ═══════════════════════════════════════════════════════════════

    /**
     * 删除一条标签类别记录（通过实体对象）
     *
     * SQL: DELETE FROM tag_categories WHERE id = :category.id
     *
     * 用途：从数据库中移除一个标签类别，通常需要业务层同时清理该类别下的所有标签值。
     * 调用位置：类别删除流程
     *
     * @param category 待删除的 TagCategoryEntity 实体对象（主键 id 用于定位）
     */
    @Delete
    suspend fun delete(category: TagCategoryEntity)

    /**
     * 根据 ID 直接删除标签类别（无需构造实体对象）
     *
     * SQL: DELETE FROM tag_categories WHERE id = :id
     *
     * 用途：当只有类别 id 而没有完整实体对象时调用，简化调用方代码。
     * 调用位置：批量删除、根据 id 删除的场景
     *
     * @param id 待删除的类别 id
     */
    @Query("DELETE FROM tag_categories WHERE id = :id")
    suspend fun deleteById(id: String)
}
