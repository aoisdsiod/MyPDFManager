package com.example.pdfmanager.data.local

import androidx.room.*
import com.example.pdfmanager.data.model.CategoryTagEntity
import kotlinx.coroutines.flow.Flow

/**
 * DAO（数据访问对象）—— 类别下的具体标签值管理
 *
 * 对应的数据库表：category_tags
 *
 * 该表用于存储每个标签类别（TagCategory）下包含的具体标签值。
 * 这是一种"类别 — 标签值"的一对多关系：
 *   一个 TagCategory（如"重要性"）下可以有多个 CategoryTag（如"高"、"中"、"低"）。
 *
 * 字段说明（对应 CategoryTagEntity 实体）：
 *   - id           ：标签值唯一标识（UUID 字符串）
 *   - category_id  ：所属标签类别的 id，外键关联 tag_categories 表
 *   - value        ：标签值的文本内容，例如"高"、"中"、"低"
 *   - created_at   ：创建时间，用于按添加先后顺序排列
 */
@Dao
interface CategoryTagDao {

    // ═══════════════════════════════════════════════════════════════
    //  查询操作
    // ═══════════════════════════════════════════════════════════════

    /**
     * 根据类别 id 获取该类别下的所有标签值（非响应式，一次性查询）
     *
     * SQL: SELECT * FROM category_tags WHERE category_id = :categoryId ORDER BY created_at ASC
     *   - WHERE category_id = :categoryId ：筛选出属于指定类别的所有标签值
     *   - ORDER BY created_at ASC         ：按创建时间升序排列，最早添加的标签排在最前
     *
     * 用途：在需要获取某个类别下全部标签值的快照时使用，
     *       例如加载类别编辑页面、统计某个类别的标签数量。
     * 调用位置：Repository 层、类别编辑页面、标签选择器的数据加载
     *
     * @param categoryId 标签类别的 id
     * @return List<CategoryTagEntity> 该类别下的所有标签值列表
     */
    @Query("SELECT * FROM category_tags WHERE category_id = :categoryId ORDER BY created_at ASC")
    suspend fun getByCategoryId(categoryId: String): List<CategoryTagEntity>

    /**
     * 根据类别 id 获取该类别下的所有标签值，返回响应式 Flow 流
     *
     * SQL: SELECT * FROM category_tags WHERE category_id = :categoryId ORDER BY created_at ASC
     *   - WHERE category_id = :categoryId ：筛选出属于指定类别的所有标签值
     *   - ORDER BY created_at ASC         ：按创建时间升序排列
     *
     * 用途：在 UI 页面展示某个类别下的标签列表时使用。
     *       Flow 保证当该类别下的标签数据发生变化（增/删/改）时自动推送更新。
     * 调用位置：标签列表页面、ViewModel 层 collect 该 Flow
     *
     * @param categoryId 标签类别的 id
     * @return Flow<List<CategoryTagEntity>> 可观察的标签值列表
     */
    @Query("SELECT * FROM category_tags WHERE category_id = :categoryId ORDER BY created_at ASC")
    fun getByCategoryIdFlow(categoryId: String): Flow<List<CategoryTagEntity>>

    /**
     * 根据类别 id 和标签值内容，查询指定的单个标签值
     *
     * SQL: SELECT * FROM category_tags WHERE category_id = :categoryId AND value = :value
     *   - 复合条件查询：同时匹配 category_id 和 value 两个字段
     *     由于同一个类别下不允许有重复的标签值（业务约束），该查询最多返回一条记录
     *
     * 用途：用于判断某个标签值在指定类别下是否已存在（避免重复创建）、
     *       或获取特定标签值的详情信息。
     * 调用位置：标签值创建前的重复性检查、标签值编辑流程
     *
     * @param categoryId 标签类别的 id
     * @param value      要查询的标签值文本
     * @return CategoryTagEntity? 若不存在则返回 null
     */
    @Query("SELECT * FROM category_tags WHERE category_id = :categoryId AND value = :value")
    suspend fun getByCategoryIdAndValue(categoryId: String, value: String): CategoryTagEntity?

    // ═══════════════════════════════════════════════════════════════
    //  插入 / 更新操作
    // ═══════════════════════════════════════════════════════════════

    /**
     * 插入或替换一条标签值记录
     *
     * SQL: INSERT INTO category_tags ... ON CONFLICT(id) REPLACE
     *   - 使用 OnConflictStrategy.REPLACE 策略：当主键 id 冲突时，
     *     用新数据完全覆盖旧数据
     *
     * 用途：创建新标签值或从远程同步时覆盖已有标签值。
     * 调用位置：标签值创建流程、数据同步逻辑
     *
     * @param tag 待插入/替换的 CategoryTagEntity 实体对象
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(tag: CategoryTagEntity)

    /**
     * 批量插入或替换多条标签值记录
     *
     * SQL: INSERT INTO category_tags ... ON CONFLICT(id) REPLACE（批量执行）
     *   - 使用 OnConflictStrategy.REPLACE 策略
     *   - 一次性提交列表中的所有实体，比逐条插入性能更高
     *
     * 用途：批量导入标签值、从远程同步全部标签数据、或还原备份数据时调用。
     * 调用位置：数据导入、批量同步、应用初始化
     *
     * @param tags 待插入/替换的 CategoryTagEntity 实体对象列表
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(tags: List<CategoryTagEntity>)

    // ═══════════════════════════════════════════════════════════════
    //  删除操作
    // ═══════════════════════════════════════════════════════════════

    /**
     * 删除一条标签值记录（通过实体对象）
     *
     * SQL: DELETE FROM category_tags WHERE id = :tag.id
     *
     * 用途：删除指定类别下的某个具体标签值。
     * 调用位置：标签值删除流程（用户删除单个标签）
     *
     * @param tag 待删除的 CategoryTagEntity 实体对象（主键 id 用于定位）
     */
    @Delete
    suspend fun delete(tag: CategoryTagEntity)

    /**
     * 根据类别 id 和标签值内容直接删除标签值（无需构造实体对象）
     *
     * SQL: DELETE FROM category_tags WHERE category_id = :categoryId AND value = :value
     *   - 复合条件删除：同时匹配类别 id 和标签值文本，精确定位要删除的记录
     *
     * 用途：当明确知道类别和标签值文本时，直接删除特定标签值，无需先查询实体。
     * 调用位置：标签管理 UI 中的删除操作
     *
     * @param categoryId 标签值所属类别的 id
     * @param value      要删除的标签值文本
     */
    @Query("DELETE FROM category_tags WHERE category_id = :categoryId AND value = :value")
    suspend fun deleteByCategoryIdAndValue(categoryId: String, value: String)

    /**
     * 删除指定类别下的所有标签值
     *
     * SQL: DELETE FROM category_tags WHERE category_id = :categoryId
     *   - 批量删除：一次性移除属于某个类别的全部标签值
     *
     * 用途：当用户删除某个标签类别时，同时清理该类别下的所有标签值，
     *       确保数据一致性，避免产生孤儿数据。
     * 调用位置：类别删除流程（在删除 TagCategory 之前调用）、类别数据清空
     *
     * @param categoryId 要清空标签值的类别 id
     */
    @Query("DELETE FROM category_tags WHERE category_id = :categoryId")
    suspend fun deleteByCategoryId(categoryId: String)
}
