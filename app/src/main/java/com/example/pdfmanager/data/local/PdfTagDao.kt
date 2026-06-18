package com.example.pdfmanager.data.local

import androidx.room.*
import com.example.pdfmanager.data.model.PdfTagEntity
import com.example.pdfmanager.data.model.TagFilterItem

/**
 * =============================================================================
 * PdfTagDao —— PDF 标签关系数据访问对象 (Data Access Object)
 * =============================================================================
 *
 * 【用途】
 *   操作 pdf_tags 表，该表存储 PDF 文件与标签之间的多对多关系。
 *   每个记录表示"某个 PDF 文件被打上了某个标签"。
 *
 * 【冗余字段设计】
 *   表中冗余存储了 category_name（类别名称）和 tag_color（标签颜色），
 *   以避免筛选时频繁 JOIN 查询 category_tags 表，提高查询性能。
 *   这意味着更新类别名称/颜色或标签值重命名时，需要级联更新此表。
 *
 * 【数据流】
 *   1. 用户为 PDF 添加标签 → Repository 调用 insert/insertAll 写入
 *   2. 用户删除标签/类别 → Repository 调用 delete 系列方法级联删除
 *   3. 用户在筛选页选择标签 → Repository 调用 getPdfUrisByTagsAnd/Or 筛选
 *   4. 用户重命名标签/类别名称 → Repository 调用 update 系列方法同步更新
 *
 * 【调用位置】
 *   - PdfRepository: 封装所有标签操作，供 ViewModel 层调用
 *   - TagViewModel: 标签管理页面（添加/删除/重命名标签）
 *   - PdfListViewModel: PDF 列表页面的标签筛选功能
 *   - CategoryViewModel: 类别管理页面的级联更新
 */
@Dao
interface PdfTagDao {

    // ═══════════════════════════════════════════════════════════════
    //  查询操作（SELECT）
    // ═══════════════════════════════════════════════════════════════

    /**
     * 获取所有 PDF 标签关系记录
     *
     * 调用位置: PdfRepository.getAllTagsData()
     * 使用场景: 数据迁移、全量导出、调试工具
     *
     * @return 数据库中所有 PdfTagEntity 列表
     */
    @Query("SELECT * FROM pdf_tags")
    suspend fun getAll(): List<PdfTagEntity>

    /**
     * 获取指定 PDF 文件的所有标签
     *
     * 调用位置: PdfRepository.getTagsByPdfUri()
     *            → PdfDetailViewModel（PDF 详情页查看已有标签）
     * 使用场景:
     *   - PDF 详情页展示该文件已添加的所有标签
     *   - 编辑标签时回显已有标签状态
     *
     * @param pdfFileUri PDF 文件的 URI 字符串
     * @return 该 PDF 关联的所有 PdfTagEntity 列表
     */
    @Query("SELECT * FROM pdf_tags WHERE pdf_file_uri = :pdfFileUri")
    suspend fun getByPdfFileUri(pdfFileUri: String): List<PdfTagEntity>

    /**
     * 获取所有有标签的 PDF URI（去重）
     *
     * 调用位置: PdfRepository.getAllTaggedPdfUris()
     * 使用场景: 首页统计信息（"已标记 PDF 数量"展示）
     *
     * @return 所有被标记过标签的 PDF URI 列表（无重复）
     */
    @Query("SELECT DISTINCT pdf_file_uri FROM pdf_tags")
    suspend fun getAllTaggedPdfUris(): List<String>

    /**
     * 检查指定 PDF 是否有标签（计数查询）
     *
     * 调用位置: PdfRepository.hasTags()
     *            → PdfListAdapter（列表项中判断是否显示标签标记图标）
     * 使用场景:
     *   - PDF 列表项上显示标签标记（有标签时显示图标）
     *   - 判断 PDF 是否已被分类
     *
     * @param pdfFileUri PDF 文件的 URI 字符串
     * @return 该 PDF 关联的标签数量（0 表示无标签）
     */
    @Query("SELECT COUNT(*) FROM pdf_tags WHERE pdf_file_uri = :pdfFileUri")
    suspend fun countByPdfFileUri(pdfFileUri: String): Int

    /**
     * AND 逻辑标签筛选：选中的标签必须同时属于同一 PDF
     *
     * 算法：GROUP BY pdf_file_uri 后，统计该 PDF 拥有选中标签的数量，
     *       如果等于选中标签总数，说明该 PDF 同时包含了所有选中标签。
     *
     * 调用位置: PdfRepository.getPdfUrisByTagsAnd()
     *            → PdfListViewModel.applyFilterAnd()
     * 使用场景: 用户在筛选界面勾选多个标签并选择"且"模式时调用。
     *           例如：选中"工作"和"重要"，只显示同时拥有这两个标签的 PDF。
     *
     * @param tagValues 选中的标签值列表，如 ["工作", "重要", "紧急"]
     * @param tagCount  选中标签的数量，等于 tagValues.size
     *                  （用于 HAVING 子句中与 COUNT 比较）
     * @return 同时拥有所有选中标签的 PDF URI 列表
     */
    @Query("SELECT pdf_file_uri FROM pdf_tags WHERE tag_value IN (:tagValues) GROUP BY pdf_file_uri HAVING COUNT(DISTINCT tag_value) = :tagCount")
    suspend fun getPdfUrisByTagsAnd(tagValues: List<String>, tagCount: Int): List<String>

    /**
     * OR 逻辑标签筛选：选中的标签满足任意一个即可
     *
     * 调用位置: PdfRepository.getPdfUrisByTagsOr()
     *            → PdfListViewModel.applyFilterOr()
     * 使用场景: 用户在筛选界面勾选多个标签并选择"或"模式时调用。
     *           例如：选中"工作"和"个人"，只要有其中任何一个标签的 PDF 都显示。
     *
     * @param tagValues 选中的标签值列表
     * @return 拥有任意一个选中标签的 PDF URI 列表（去重）
     */
    @Query("SELECT DISTINCT pdf_file_uri FROM pdf_tags WHERE tag_value IN (:tagValues)")
    suspend fun getPdfUrisByTagsOr(tagValues: List<String>): List<String>

    /**
     * 根据单个标签值获取所有 PDF URI（单标签筛选）
     *
     * 调用位置: PdfRepository.getPdfUrisByTagValue()
     *            → PdfListViewModel.filterBySingleTag()
     * 使用场景: 用户只选择了一个标签进行筛选时调用，
     *           等价于 OR 模式且 tagValues.size == 1 的特化查询。
     *
     * @param tagValue 标签值，如 "工作"
     * @return 拥有该标签的 PDF URI 列表（去重）
     */
    @Query("SELECT DISTINCT pdf_file_uri FROM pdf_tags WHERE tag_value = :tagValue")
    suspend fun getPdfUrisByTagValue(tagValue: String): List<String>

    /**
     * 根据类别 ID + 标签值获取所有 PDF URI
     *
     * 调用位置: PdfRepository.getPdfUrisByCategoryAndTag()
     *            → PdfListViewModel.filterByCategoryAndTag()
     * 使用场景: 用户在类别浏览模式下点击某个具体标签时调用。
     *           例如：在"工作"类别下点击"重要"标签，只显示该类别中标记为重要的 PDF。
     *
     * @param categoryId 类别 ID
     * @param tagValue   标签值
     * @return 该类别下拥有该标签的 PDF URI 列表（去重）
     */
    @Query("SELECT DISTINCT pdf_file_uri FROM pdf_tags WHERE category_id = :categoryId AND tag_value = :tagValue")
    suspend fun getPdfUrisByCategoryAndTag(categoryId: String, tagValue: String): List<String>

    /**
     * 获取所有去重后的标签值及其所属类别信息
     *
     * 调用位置: PdfRepository.getAllTagsForFilter()
     *            → TagFilterViewModel（筛选界面加载所有可用的标签项）
     * 使用场景: 筛选界面需要列出所有被使用的标签，供用户勾选。
     *           返回的数据包含类别名称和颜色，用于分组显示。
     *
     * @return TagFilterItem 列表，每个元素包含 tag_value、category_id、
     *         category_name、tag_color，按类别名称升序排列
     */
    @Query("SELECT DISTINCT tag_value, category_id, category_name, tag_color FROM pdf_tags ORDER BY category_name ASC")
    suspend fun getAllTagsForFilter(): List<TagFilterItem>

    /**
     * 获取没有任何标签的 PDF URI 列表
     *
     * 使用子查询：从 pdf_files 表中筛选出 uri 不在 pdf_tags 表中的记录。
     *
     * 调用位置: PdfRepository.getPdfUrisWithNoTags()
     *            → PdfListViewModel（"未标记"筛选器）
     * 使用场景: 用户想要查看所有尚未打标签的 PDF，方便统一管理。
     *
     * @return 没有任何标签关联的 PDF URI 列表
     */
    @Query("""
        SELECT pdf_files.uri FROM pdf_files 
        WHERE pdf_files.uri NOT IN (SELECT DISTINCT pdf_file_uri FROM pdf_tags)
    """)
    suspend fun getPdfUrisWithNoTags(): List<String>

    // ═══════════════════════════════════════════════════════════════
    //  插入/更新操作（INSERT / UPDATE）
    // ═══════════════════════════════════════════════════════════════

    /**
     * 插入单条 PDF 标签关系
     *
     * OnConflictStrategy.REPLACE: 如果已有相同主键的记录，则覆盖更新。
     * 这可以防止重复插入同一 PDF + 同一标签。
     *
     * 调用位置: PdfRepository.addTagToPdf()
     *            → TagEditViewModel（用户为 PDF 添加标签）
     *
     * @param pdfTag 待插入的标签关系实体
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(pdfTag: PdfTagEntity)

    /**
     * 批量插入多条 PDF 标签关系
     *
     * 调用位置: PdfRepository.addTagsToPdf()
     *            → TagEditViewModel（用户批量添加多个标签）
     *            → DataMigrationHelper.importTags()（数据导入）
     * 使用场景:
     *   - 用户为 PDF 同时添加多个标签（一次操作）
     *   - 从备份文件恢复标签数据
     *
     * @param pdfTags 待插入的标签关系实体列表
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(pdfTags: List<PdfTagEntity>)

    // ═══════════════════════════════════════════════════════════════
    //  删除操作（DELETE）
    // ═══════════════════════════════════════════════════════════════

    /**
     * 删除指定 PDF 的单个标签
     *
     * 调用位置: PdfRepository.removeTagFromPdf()
     *            → TagEditViewModel（用户移除 PDF 的某个标签）
     * 使用场景: 用户在 PDF 详情页或标签编辑页，点击移除某个标签。
     *
     * @param pdfFileUri PDF 文件的 URI
     * @param categoryId 标签所属的类别 ID
     * @param tagValue   要删除的标签值
     */
    @Query("DELETE FROM pdf_tags WHERE pdf_file_uri = :pdfFileUri AND category_id = :categoryId AND tag_value = :tagValue")
    suspend fun deleteByPdfFileUriAndTag(pdfFileUri: String, categoryId: String, tagValue: String)

    /**
     * 删除指定 PDF 的所有标签
     *
     * 调用位置: PdfRepository.clearAllTagsForPdf()
     *            → TagEditViewModel（用户清空 PDF 的所有标签）
     *            → PdfDeleteHandler（删除 PDF 时同时清除标签关系）
     * 使用场景:
     *   - 用户从 PDF 详情页选择"清除所有标签"
     *   - 删除 PDF 文件时级联删除其标签关系
     *
     * @param pdfFileUri PDF 文件的 URI
     */
    @Query("DELETE FROM pdf_tags WHERE pdf_file_uri = :pdfFileUri")
    suspend fun deleteByPdfFileUri(pdfFileUri: String)

    /**
     * 删除指定类别下的所有标签关系（级联删除）
     *
     * 调用位置: PdfRepository.deleteTagsByCategoryId()
     *            → CategoryViewModel.deleteCategory()
     * 使用场景: 用户删除一个类别时，需要同时删除该类别下所有标签与 PDF 的关联。
     *           这是级联删除的一部分。
     *
     * @param categoryId 要删除的类别 ID
     */
    @Query("DELETE FROM pdf_tags WHERE category_id = :categoryId")
    suspend fun deleteByCategoryId(categoryId: String)

    /**
     * 删除指定类别下某个标签值的所有关联（级联删除）
     *
     * 调用位置: PdfRepository.deleteTagValue()
     *            → CategoryViewModel.deleteTagValue()
     * 使用场景: 用户删除某个类别下的一个具体标签值时，
     *           需要同时删除所有 PDF 与该标签值的关联。
     *
     * @param categoryId 类别 ID
     * @param tagValue   要删除的标签值
     */
    @Query("DELETE FROM pdf_tags WHERE category_id = :categoryId AND tag_value = :tagValue")
    suspend fun deleteByCategoryIdAndTagValue(categoryId: String, tagValue: String)

    // ═══════════════════════════════════════════════════════════════
    //  级联更新操作（UPDATE）
    // ═══════════════════════════════════════════════════════════════

    /**
     * 级联更新：标签值重命名
     *
     * 当 category_tags 表中的标签值被重命名时，同步更新 pdf_tags 表中
     * 所有引用该标签值的记录，保持数据一致性。
     *
     * 调用位置: PdfRepository.updateTagValue()
     *            → CategoryViewModel.renameTagValue()
     * 使用场景: 用户将标签"工作"重命名为"办公"，此方法会将所有 PDF 的
     *           标签值从"工作"更新为"办公"。
     *
     * @param categoryId 类别 ID
     * @param oldValue   原来的标签值
     * @param newValue   新的标签值
     */
    @Query("UPDATE pdf_tags SET tag_value = :newValue WHERE category_id = :categoryId AND tag_value = :oldValue")
    suspend fun updateTagValue(categoryId: String, oldValue: String, newValue: String)

    /**
     * 级联更新：类别名称变更
     *
     * 当 category_tags 表中的类别名称被修改时，同步更新 pdf_tags 表中
     * 该类别下所有记录的 category_name 字段。
     *
     * 由于 pdf_tags 表冗余存储了 category_name，此处需要同步更新。
     *
     * 调用位置: PdfRepository.updateCategoryName()
     *            → CategoryViewModel.renameCategory()
     * 使用场景: 用户将类别"个人"重命名为"私人"，更新所有相关标签关系的类别名。
     *
     * @param categoryId 类别 ID
     * @param newName    新的类别名称
     */
    @Query("UPDATE pdf_tags SET category_name = :newName WHERE category_id = :categoryId")
    suspend fun updateCategoryName(categoryId: String, newName: String)

    /**
     * 级联更新：类别颜色变更
     *
     * 当类别颜色被修改时，同步更新 pdf_tags 表中该类别下所有记录的 tag_color 字段。
     * 这使得筛选界面的标签颜色始终与当前类别颜色一致。
     *
     * 调用位置: PdfRepository.updateTagColorByCategoryId()
     *            → CategoryViewModel.updateCategoryColor()
     * 使用场景: 用户更改类别的颜色后，所有该类别下的标签显示颜色同步更新。
     *
     * @param categoryId 类别 ID
     * @param newColor   新的颜色值（ARGB 格式的 Int）
     */
    @Query("UPDATE pdf_tags SET tag_color = :newColor WHERE category_id = :categoryId")
    suspend fun updateTagColorByCategoryId(categoryId: String, newColor: Int)

    /**
     * 级联更新：PDF 文件 URI 变更（文件移动后更新标签关联）
     *
     * 当文件被移动（URI 改变但文件名不变）时，同步更新 pdf_tags 表中
     * 指向该文件的所有记录的 pdf_file_uri 字段。这样标签就能跟随文件移动。
     *
     * 调用位置: PdfRepository.quickIncrementalScan() — 移动文件检测处
     * 使用场景: 用户将 PDF 文件移动到其他子文件夹后触发增量扫描，
     *           检测到文件名相同但 URI 不同时，更新标签关联到新 URI。
     *
     * @param oldUri 旧的 PDF 文件 URI
     * @param newUri 新的 PDF 文件 URI
     */
    @Query("UPDATE pdf_tags SET pdf_file_uri = :newUri WHERE pdf_file_uri = :oldUri")
    suspend fun updatePdfFileUri(oldUri: String, newUri: String)
}
