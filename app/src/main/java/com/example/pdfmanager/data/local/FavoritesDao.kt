package com.example.pdfmanager.data.local

import androidx.room.*
import com.example.pdfmanager.data.model.FavoriteFolder
import com.example.pdfmanager.data.model.OrganizeFolder
import com.example.pdfmanager.data.model.FolderPdfOrder
import kotlinx.coroutines.flow.Flow

/**
 * DAO（数据访问对象）—— 收藏夹与整理文件夹模块
 *
 * 对应的数据库表：
 *   - favorite_folders ：收藏夹文件夹表
 *   - organize_folders ：整理文件夹表
 *   - folder_pdf_order ：文件夹内 PDF 排序顺序表
 *
 * 该 DAO 封装了以下三类业务数据的全部 CRUD 操作：
 *   1. FavoriteFolder（收藏夹文件夹）：用户收藏的文件夹节点
 *   2. OrganizeFolder（整理文件夹）：用户手动整理生成的文件夹层级
 *   3. FolderPdfOrder（文件夹内 PDF 排序顺序）：记录每个文件夹内 PDF 的自定义排列顺序
 */
@Dao
interface FavoritesDao {

    // ═══════════════════════════════════════════════════════════════
    //  FavoriteFolder —— 收藏夹文件夹
    // ═══════════════════════════════════════════════════════════════

    /**
     * 插入一条收藏夹文件夹记录
     *
     * SQL: INSERT INTO favorite_folders ...
     *
     * 用途：用户新建收藏夹文件夹时调用，将新文件夹持久化到数据库。
     * 调用位置：收藏夹创建流程（如 FavoritesRepository 或 ViewModel）
     *
     * @param folder 待插入的 FavoriteFolder 实体对象（id 通常由 Room 自动生成或客户端预先指定）
     */
    @Insert
    suspend fun insertFavoriteFolder(folder: FavoriteFolder)

    /**
     * 更新一条收藏夹文件夹记录
     *
     * SQL: UPDATE favorite_folders SET ... WHERE id = :folder.id
     *
     * 用途：用户修改收藏夹文件夹属性（如重命名、调整排序、更换所属父目录等）时调用。
     * 调用位置：收藏夹编辑/重命名流程
     *
     * @param folder 包含更新后全部字段的 FavoriteFolder 实体对象（Room 根据主键 id 匹配更新）
     */
    @Update
    suspend fun updateFavoriteFolder(folder: FavoriteFolder)

    /**
     * 删除一条收藏夹文件夹记录
     *
     * SQL: DELETE FROM favorite_folders WHERE id = :folder.id
     *
     * 用途：用户删除收藏夹中的文件夹时调用。
     * 调用位置：收藏夹删除流程
     *
     * @param folder 待删除的 FavoriteFolder 实体对象（Room 根据主键 id 定位删除）
     */
    @Delete
    suspend fun deleteFavoriteFolder(folder: FavoriteFolder)

    /**
     * 查询全部收藏夹文件夹，返回响应式 Flow 流
     *
     * SQL: SELECT * FROM favorite_folders ORDER BY sortOrder ASC, createdAt DESC
     *   - sortOrder ASC      ：先按用户自定义排序号升序排列
     *   - createdAt DESC     ：排序号相同时，按创建时间降序（最新的在前）
     *
     * 用途：在收藏夹主界面加载所有文件夹列表，Flow 保证数据变更后自动推送新列表。
     * 调用位置：收藏夹列表页面（ViewModel 层 collect 该 Flow）
     *
     * @return Flow<List<FavoriteFolder>> 可观察的收藏夹文件夹列表
     */
    @Query("SELECT * FROM favorite_folders ORDER BY sortOrder ASC, createdAt DESC")
    fun getAllFavoriteFoldersFlow(): Flow<List<FavoriteFolder>>

    /**
     * 根据 ID 查询单个收藏夹文件夹
     *
     * SQL: SELECT * FROM favorite_folders WHERE id = :id
     *
     * 用途：根据文件夹 id 获取详情，用于导航、判断是否存在等场景。
     * 调用位置：文件夹详情页、导航逻辑
     *
     * @param id 收藏夹文件夹的唯一标识
     * @return FavoriteFolder? 若不存在则返回 null
     */
    @Query("SELECT * FROM favorite_folders WHERE id = :id")
    suspend fun getFavoriteFolderById(id: String): FavoriteFolder?

    /**
     * 查询指定父文件夹下的所有子收藏夹文件夹，返回响应式 Flow 流
     *
     * SQL: SELECT * FROM favorite_folders WHERE belongToFolderId = :parentId ORDER BY sortOrder ASC
     *   - belongToFolderId = :parentId ：筛选出父文件夹为指定 id 的子文件夹
     *   - ORDER BY sortOrder ASC       ：按用户自定义排序号升序排列
     *
     * 用途：展开收藏夹的某一层级，展示该文件夹下的子文件夹列表。
     * 调用位置：收藏夹层级展开、树形列表展示页面
     *
     * @param parentId 父文件夹的 id
     * @return Flow<List<FavoriteFolder>> 可观察的子文件夹列表
     */
    @Query("SELECT * FROM favorite_folders WHERE belongToFolderId = :parentId ORDER BY sortOrder ASC")
    fun getFavoriteFoldersByParentFlow(parentId: String): Flow<List<FavoriteFolder>>

    // ═══════════════════════════════════════════════════════════════
    //  OrganizeFolder —— 整理文件夹
    // ═══════════════════════════════════════════════════════════════

    /**
     * 插入一条整理文件夹记录
     *
     * SQL: INSERT INTO organize_folders ...
     *
     * 用途：用户创建新的整理文件夹（根级或子级）时调用。
     * 调用位置：整理文件夹创建流程
     *
     * @param folder 待插入的 OrganizeFolder 实体对象
     */
    @Insert
    suspend fun insertOrganizeFolder(folder: OrganizeFolder)

    /**
     * 更新一条整理文件夹记录
     *
     * SQL: UPDATE organize_folders SET ... WHERE id = :folder.id
     *
     * 用途：用户修改整理文件夹属性（名称、排序、父文件夹等）时调用。
     * 调用位置：整理文件夹编辑/重命名流程
     *
     * @param folder 包含更新后全部字段的 OrganizeFolder 实体对象
     */
    @Update
    suspend fun updateOrganizeFolder(folder: OrganizeFolder)

    /**
     * 删除一条整理文件夹记录
     *
     * SQL: DELETE FROM organize_folders WHERE id = :folder.id
     *
     * 用途：用户删除单个整理文件夹时调用。
     * 注意：仅删除该记录本身，如果有关联的子文件夹需要级联删除，应在业务层处理。
     * 调用位置：整理文件夹删除流程
     *
     * @param folder 待删除的 OrganizeFolder 实体对象
     */
    @Delete
    suspend fun deleteOrganizeFolder(folder: OrganizeFolder)

    /**
     * 根据 ID 直接删除整理文件夹（无需构造实体对象）
     *
     * SQL: DELETE FROM organize_folders WHERE id = :id
     *
     * 用途：当只有 id 而没有完整实体对象时调用，避免不必要的查询。
     * 调用位置：批量删除、根据 id 删除的场景
     *
     * @param id 待删除的整理文件夹 id
     */
    @Query("DELETE FROM organize_folders WHERE id = :id")
    suspend fun deleteOrganizeFolderById(id: String)

    /**
     * 查询所有根级整理文件夹（parentFolderId 为 null 的顶级文件夹），返回响应式 Flow 流
     *
     * SQL: SELECT * FROM organize_folders WHERE parentFolderId IS NULL ORDER BY sortOrder ASC
     *   - parentFolderId IS NULL ：筛选出顶级文件夹（没有父文件夹）
     *   - ORDER BY sortOrder ASC ：按用户自定义排序号升序排列
     *
     * 用途：在整理文件夹根层级展示所有顶级文件夹列表。
     * 调用位置：整理文件夹首页、根目录列表页面
     *
     * @return Flow<List<OrganizeFolder>> 可观察的根级整理文件夹列表
     */
    @Query("SELECT * FROM organize_folders WHERE parentFolderId IS NULL ORDER BY sortOrder ASC")
    fun getRootOrganizeFoldersFlow(): Flow<List<OrganizeFolder>>

    /**
     * 查询指定父文件夹下的所有子整理文件夹，返回响应式 Flow 流
     *
     * SQL: SELECT * FROM organize_folders WHERE parentFolderId = :parentId ORDER BY sortOrder ASC
     *   - parentFolderId = :parentId ：筛选出归属于指定父文件夹的子文件夹
     *   - ORDER BY sortOrder ASC     ：按用户自定义排序号升序排列
     *
     * 用途：展开整理文件夹的层级，加载子文件夹列表。
     * 调用位置：整理文件夹层级展开、树形列表展示页面
     *
     * @param parentId 父文件夹的 id
     * @return Flow<List<OrganizeFolder>> 可观察的子文件夹列表
     */
    @Query("SELECT * FROM organize_folders WHERE parentFolderId = :parentId ORDER BY sortOrder ASC")
    fun getSubOrganizeFoldersFlow(parentId: String): Flow<List<OrganizeFolder>>

    /**
     * 根据 ID 查询单个整理文件夹
     *
     * SQL: SELECT * FROM organize_folders WHERE id = :id
     *
     * 用途：根据 id 获取整理文件夹详情，用于导航、编辑或判断是否存在。
     * 调用位置：文件夹详情页、编辑流程
     *
     * @param id 整理文件夹的唯一标识
     * @return OrganizeFolder? 若不存在则返回 null
     */
    @Query("SELECT * FROM organize_folders WHERE id = :id")
    suspend fun getOrganizeFolderById(id: String): OrganizeFolder?

    /**
     * 查询全部整理文件夹（非响应式，一次性查询）
     *
     * SQL: SELECT * FROM organize_folders ORDER BY sortOrder ASC
     *   - ORDER BY sortOrder ASC ：按用户自定义排序号升序排列
     *
     * 用途：在需要获取完整的文件夹列表快照时使用（例如导出、计算统计），
     *       与返回 Flow 的方法不同，此方法仅返回当前时刻的数据。
     * 调用位置：数据同步、统计计算、后台任务
     *
     * @return List<OrganizeFolder> 当前数据库中的全部整理文件夹列表
     */
    @Query("SELECT * FROM organize_folders ORDER BY sortOrder ASC")
    suspend fun getAllOrganizeFolders(): List<OrganizeFolder>

    // ═══════════════════════════════════════════════════════════════
    //  FolderPdfOrder —— 文件夹内 PDF 排序顺序
    // ═══════════════════════════════════════════════════════════════

    /**
     * 插入或替换文件夹内 PDF 的排序顺序记录
     *
     * SQL: INSERT INTO folder_pdf_order ... ON CONFLICT(id) REPLACE
     *   - 使用 OnConflictStrategy.REPLACE 策略：当主键冲突时，用新数据覆盖旧数据
     *
     * 用途：当用户自定义了某个文件夹内 PDF 的排序顺序时，保存该顺序信息。
     *       排序顺序通常以 JSON 字符串格式存储在 pdfOrder 字段中。
     * 调用位置：文件夹内 PDF 排序变更流程
     *
     * @param order 包含文件夹 id 和排序数据的 FolderPdfOrder 实体对象
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdatePdfOrder(order: FolderPdfOrder)

    /**
     * 查询指定文件夹的 PDF 排序顺序
     *
     * SQL: SELECT pdfOrder FROM folder_pdf_order WHERE folderId = :folderId
     *
     * 用途：进入文件夹时，获取该文件夹内 PDF 的自定义排序数据。
     *       返回的字符串通常是 JSON 格式的 PDF id 列表或排序配置。
     * 调用位置：文件夹内容展示页、排序恢复逻辑
     *
     * @param folderId 文件夹的 id
     * @return String? 排序数据（JSON 字符串），若未自定义过排序则返回 null
     */
    @Query("SELECT pdfOrder FROM folder_pdf_order WHERE folderId = :folderId")
    suspend fun getPdfOrderForFolder(folderId: String): String?

    /**
     * 删除指定文件夹的 PDF 排序顺序记录
     *
     * SQL: DELETE FROM folder_pdf_order WHERE folderId = :folderId
     *
     * 用途：当用户重置文件夹排序、删除文件夹或清除自定义排序时调用。
     * 调用位置：排序重置流程、文件夹删除流程
     *
     * @param folderId 要清除排序记录的文件夹 id
     */
    @Query("DELETE FROM folder_pdf_order WHERE folderId = :folderId")
    suspend fun deletePdfOrderForFolder(folderId: String)
}
