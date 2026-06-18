package com.example.pdfmanager.data.repository

import com.example.pdfmanager.data.local.FavoritesDao
import com.example.pdfmanager.data.model.FavoriteFolder
import com.example.pdfmanager.data.model.OrganizeFolder
import com.example.pdfmanager.data.model.FolderPdfOrder
import com.example.pdfmanager.data.model.SavedFilter
import kotlinx.coroutines.flow.*
import com.google.gson.Gson
import java.util.UUID

/**
 * ## 收藏仓库
 *
 * ─── 功能职责 ─────────────────────────────────────────────────
 * 管理两类文件夹的 CRUD 操作和它们的层级关系：
 *
 * 1. **虚拟文件夹（[FavoriteFolder]）**：基于保存的筛选条件（[SavedFilter]）动态聚合 PDF 文件。
 *    虚拟文件夹不实际持有文件，而是通过 JSON 序列化的筛选条件在查询时实时匹配。
 *    可以归类到自建文件夹下（通过 [belongToFolderId]），也可以独立存在于根目录。
 *
 * 2. **自建文件夹（[OrganizeFolder]）**：用户手动创建的组织目录，支持无限层级嵌套。
 *    作为容器，内部可以包含虚拟文件夹和子自建文件夹。
 *
 * ─── 数据流 ───────────────────────────────────────────────────
 * ```
 * 上层 ViewModel / Fragment
 *          │
 *          ▼
 *   FavoritesRepository
 *          │                        ┌────────────────────┐
 *          ├── Flow 方法 ──────────→│  Room DAO 响应式查询  │
 *          │  (观察实时变化)         │  (Flow<List<T>>)    │
 *          │                        └────────────────────┘
 *          │                        ┌────────────────────┐
 *          ├── 挂起方法 ──────────→│  一次性数据库查询    │
 *          │  (suspend)             │  (DAO.suspend fun)  │
 *          │                        └────────────────────┘
 *          │                        ┌────────────────────┐
 *          └── 写入操作 ──────────→│  Room 写入/删除      │
 *             (插入/更新/删除)      └────────────────────┘
 * ```
 *
 * ─── 调用位置 ─────────────────────────────────────────────────
 * - **[AppContainer]**：构造时接收 [FavoritesDao] 实例，创建仓库单例。
 * - **FavoritesViewModel**：管理收藏夹/虚拟文件夹的列表展示、创建、编辑、删除。
 * - **OrganizeViewModel**：管理自建文件夹的层级结构和排序。
 * - **SearchViewModel / AllFilesViewModel**：在将文件加入收藏时调用 [createFavoriteFolder]。
 *
 * ─── 合并 Flow 机制 ──────────────────────────────────────────
 * 该类使用 [combine] 将自建文件夹 Flow 和虚拟文件夹 Flow 合并为单一列表输出，
 * 并按 [sortOrder] 字段排序，以保证 UI 展示顺序与用户拖拽排列一致。
 *
 * @property dao [FavoritesDao] Room 数据访问对象，所有数据库操作最终委托给 DAO。
 * @property gson [Gson] JSON 序列化工具，用于 [SavedFilter] ↔ JSON 字符串互转，
 *              以便存储在 [FavoriteFolder.savedFilterJson] 字段中。
 *
 * @see FavoritesDao Room 持久层
 * @see FavoriteFolder 虚拟文件夹实体
 * @see OrganizeFolder 自建文件夹实体
 * @see SavedFilter 保存的筛选条件（JSON 序列化存储）
 */
class FavoritesRepository(
    private val dao: FavoritesDao
) {
    private val gson = Gson()

    // ═══════════════════════════════════════════════════════════
    //  Flow 方法（响应式数据流，自动观察数据库变化并推送最新数据）
    //  UI 层通过 collect() 监听，任何数据库变更自动触发重绘。
    // ═══════════════════════════════════════════════════════════

    /**
     * ## 获取所有虚拟文件夹的响应式数据流
     *
     * 返回 Room DAO 提供的 Flow，底层是一个 `SELECT * FROM favorite_folders ORDER BY sortOrder`。
     * 当 favorite_folders 表中的任何数据发生变化时（插入/更新/删除），自动发射新列表。
     *
     * ─── 调用场景 ──────────────────────────────────────────────
     * - [getRootItemsFlow] / [getSubItemsFlow] 内部合并时调用。
     * - 需要独立观察所有虚拟文件夹的场合（如全局收藏统计）。
     *
     * @return Flow<List<FavoriteFolder>> 虚拟文件夹列表的响应式流。
     *         列表按 sortOrder 升序排列。初始值为数据库当前全量数据。
     * @see FavoritesDao.getAllFavoriteFoldersFlow
     */
    fun getAllFavoriteFoldersFlow(): Flow<List<FavoriteFolder>> {
        return dao.getAllFavoriteFoldersFlow()
    }

    /**
     * ## 获取根级自建文件夹的响应式数据流
     *
     * 返回 `WHERE parentFolderId IS NULL` 的 Room Flow。
     * 只包含顶层的自建文件夹（不包含任何子文件夹）。
     *
     * ─── 调用场景 ──────────────────────────────────────────────
     * - [getRootItemsFlow] 内部合并时调用，获取根目录下的自建文件夹。
     *
     * @return Flow<List<OrganizeFolder>> 根级自建文件夹的响应式流。
     *         按 sortOrder 升序排列。
     * @see FavoritesDao.getRootOrganizeFoldersFlow
     */
    fun getRootOrganizeFoldersFlow(): Flow<List<OrganizeFolder>> {
        return dao.getRootOrganizeFoldersFlow()
    }

    /**
     * ## 获取指定自建文件夹的子文件夹响应式数据流
     *
     * 返回 `WHERE parentFolderId = :parentId` 的 Room Flow。
     * 用于实现自建文件夹的无限层级展开。
     *
     * ─── 调用场景 ──────────────────────────────────────────────
     * - [getSubItemsFlow] 内部合并时调用。
     * - 用户展开自建文件夹查看其内容的展开动画中使用。
     *
     * @param parentId 父自建文件夹的 ID。传入非空字符串查询特定父目录下的子文件夹；
     *                 传入 null 或空字符串不会返回有效结果（应由 [getRootOrganizeFoldersFlow] 处理根级数据）。
     * @return Flow<List<OrganizeFolder>> 子文件夹列表的响应式流。
     *         按 sortOrder 升序排列。无子文件夹时返回空列表。
     * @see FavoritesDao.getSubOrganizeFoldersFlow
     */
    fun getSubOrganizeFoldersFlow(parentId: String): Flow<List<OrganizeFolder>> {
        return dao.getSubOrganizeFoldersFlow(parentId)
    }

    /**
     * ## 获取指定自建文件夹下所属虚拟文件夹的响应式数据流
     *
     * 返回 `WHERE belongToFolderId = :parentId` 的 Room Flow。
     *
     * ─── 调用场景 ──────────────────────────────────────────────
     * - [getSubItemsFlow] 内部合并时调用。
     * - 展开自建文件夹时，同时展示其内部的虚拟文件夹。
     *
     * @param parentId 所属自建文件夹的 ID。传入非空字符串查询特定目录下的虚拟文件夹；
     *                 传入 null 查询不属于任何自建文件夹（根目录）的虚拟文件夹。
     * @return Flow<List<FavoriteFolder>> 虚拟文件夹列表的响应式流。
     *         按 sortOrder 升序排列。无匹配项时返回空列表。
     * @see FavoritesDao.getFavoriteFoldersByParentFlow
     */
    fun getFavoriteFoldersByParentFlow(parentId: String): Flow<List<FavoriteFolder>> {
        return dao.getFavoriteFoldersByParentFlow(parentId)
    }

    /**
     * ## 获取根目录合并列表的响应式数据流
     *
     * 将根级自建文件夹和根目录下的虚拟文件夹合并为一个列表，
     * 并按 [sortOrder] 统一排序，以呈现给用户一个完整的根目录视图。
     *
     * ─── 合并规则 ──────────────────────────────────────────────
     * 1. 通过 [combine] 同时订阅 [getRootOrganizeFoldersFlow] 和 [getAllFavoriteFoldersFlow]。
     * 2. 自建文件夹全部加入列表。
     * 3. 虚拟文件夹中只筛选出 `belongToFolderId == null`（根目录级别）的加入列表。
     * 4. 所有元素按 sortOrder 升序排列。
     * 5. 任意数据源变化时自动重新合并并发射。
     *
     * ─── 调用场景 ──────────────────────────────────────────────
     * - FavoritesFragment / OrganizeFragment 的列表 UI 通过 collect 订阅此方法。
     * - 展示分类管理首页的"根目录"内容时使用。
     *
     * @return Flow<List<Any>> 包含 [OrganizeFolder] 和 [FavoriteFolder] 的混合列表。
     *         列表元素类型是 Any，UI 层通过 `when (item) { is OrganizeFolder -> ...; is FavoriteFolder -> ... }` 区分渲染。
     * @see kotlinx.coroutines.flow.combine
     */
    fun getRootItemsFlow(): Flow<List<Any>> {
        return getRootOrganizeFoldersFlow().combine(getAllFavoriteFoldersFlow()) { organizeList, favoriteList ->
            buildList {
                addAll(organizeList)
                addAll(favoriteList.filter { it.belongToFolderId == null })
            }.sortedBy { item ->
                when (item) {
                    is OrganizeFolder -> item.sortOrder
                    is FavoriteFolder -> item.sortOrder
                    else -> 0
                }
            }
        }
    }

    /**
     * ## 获取指定自建文件夹下子列表的响应式数据流
     *
     * 将指定父目录下的子自建文件夹和所属虚拟文件夹合并为一个列表，
     * 按 sortOrder 排序，实现文件夹展开后的内容展示。
     *
     * ─── 合并规则 ──────────────────────────────────────────────
     * 1. 通过 [combine] 同时订阅 [getSubOrganizeFoldersFlow] 和 [getFavoriteFoldersByParentFlow]。
     * 2. 父目录下的子自建文件夹全部加入列表。
     * 3. 父目录下所属的虚拟文件夹全部加入列表。
     * 4. 所有元素按 sortOrder 升序排列。
     *
     * ─── 调用场景 ──────────────────────────────────────────────
     * - 用户在 OrganizeFragment 中点击展开某个自建文件夹时触发（如 ExpandableListView 或 LazyColumn 展开项）。
     * - 展示分类管理非根目录层级的内容时使用。
     *
     * @param parentId 父自建文件夹的 ID。不能为空，否则无法定位子内容。
     * @return Flow<List<Any>> 包含 [OrganizeFolder] 和 [FavoriteFolder] 的混合列表。
     *         空文件夹返回空列表。
     * @see kotlinx.coroutines.flow.combine
     */
    fun getSubItemsFlow(parentId: String): Flow<List<Any>> {
        return getSubOrganizeFoldersFlow(parentId).combine(getFavoriteFoldersByParentFlow(parentId)) { organizeList, favoriteList ->
            buildList {
                addAll(organizeList)
                addAll(favoriteList)
            }.sortedBy { item ->
                when (item) {
                    is OrganizeFolder -> item.sortOrder
                    is FavoriteFolder -> item.sortOrder
                    else -> 0
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  挂起方法（一次性查询，不持续观察）
    //  通过 `.first()` 从对应 Flow 中获取当前快照，或直接调用 DAO 挂起方法。
    // ═══════════════════════════════════════════════════════════

    /**
     * ## 一次性获取所有虚拟文件夹
     *
     * ─── 调用场景 ──────────────────────────────────────────────
     * - [createFavoriteFolder] 内部计算同级文件夹最大 sortOrder 时调用。
     * - 数据迁移、备份等不关心实时变化的场景。
     *
     * @return List<FavoriteFolder> 当前数据库中的所有虚拟文件夹。
     * @see getAllFavoriteFoldersFlow
     */
    suspend fun getAllFavoriteFolders(): List<FavoriteFolder> {
        return getAllFavoriteFoldersFlow().first()
    }

    /**
     * ## 一次性获取根级自建文件夹
     *
     * ─── 调用场景 ──────────────────────────────────────────────
     * - [createOrganizeFolder] 内部计算同级文件夹最大 sortOrder 时调用。
     * - 不关心实时变化的根级文件夹查询。
     *
     * @return List<OrganizeFolder> 当前数据库中所有根级自建文件夹。
     * @see getRootOrganizeFoldersFlow
     */
    suspend fun getRootOrganizeFolders(): List<OrganizeFolder> {
        return getRootOrganizeFoldersFlow().first()
    }

    /**
     * ## 一次性获取指定自建文件夹的子文件夹
     *
     * ─── 调用场景 ──────────────────────────────────────────────
     * - [createOrganizeFolder] 内部计算指定父目录下兄弟文件夹最大 sortOrder 时调用。
     *
     * @param parentId 父自建文件夹 ID。
     * @return List<OrganizeFolder> 指定父目录下的子文件夹列表。
     * @see getSubOrganizeFoldersFlow
     */
    suspend fun getSubOrganizeFolders(parentId: String): List<OrganizeFolder> {
        return getSubOrganizeFoldersFlow(parentId).first()
    }

    /**
     * ## 根据 ID 获取虚拟文件夹
     *
     * ─── 调用场景 ──────────────────────────────────────────────
     * - [deleteFavoriteFolder] 内部删除前获取文件夹实体作为 DAO 删除参数。
     * - 详情页展示文件夹属性时调用。
     *
     * @param id 虚拟文件夹 ID（[FavoriteFolder.id]）。
     * @return FavoriteFolder? 找到返回实体，未找到返回 null。
     * @see FavoritesDao.getFavoriteFolderById
     */
    suspend fun getFavoriteFolderById(id: String): FavoriteFolder? {
        return dao.getFavoriteFolderById(id)
    }

    /**
     * ## 根据 ID 获取自建文件夹
     *
     * ─── 调用场景 ──────────────────────────────────────────────
     * - [deleteOrganizeFolder] 内部删除前获取文件夹实体作为 DAO 删除参数。
     * - 详情页展示文件夹属性时调用。
     *
     * @param id 自建文件夹 ID（[OrganizeFolder.id]）。
     * @return OrganizeFolder? 找到返回实体，未找到返回 null。
     * @see FavoritesDao.getOrganizeFolderById
     */
    suspend fun getOrganizeFolderById(id: String): OrganizeFolder? {
        return dao.getOrganizeFolderById(id)
    }

    /**
     * ## 一次性获取所有自建文件夹（含子文件夹）
     *
     * ─── 调用场景 ──────────────────────────────────────────────
     * - 后台数据同步、数据导出、全量递归遍历文件夹树等场景。
     *
     * @return List<OrganizeFolder> 当前数据库中所有自建文件夹（包含全部层级）。
     * @see FavoritesDao.getAllOrganizeFolders
     */
    suspend fun getAllOrganizeFolders(): List<OrganizeFolder> {
        return dao.getAllOrganizeFolders()
    }

    // ═══════════════════════════════════════════════════════════
    //  写入操作（插入 / 更新 / 删除）
    //  所有写入操作均通过 DAO 委托给 Room 数据库。
    // ═══════════════════════════════════════════════════════════

    /**
     * ## 创建虚拟文件夹
     *
     * 基于保存的筛选条件创建虚拟文件夹。
     * 自动生成 UUID、计算排序位置、记录创建时间，然后写入数据库。
     *
     * ─── 调用场景 ──────────────────────────────────────────────
     * - 用户在 FavoritesFragment 中点击"保存为收藏"时调用。
     * - 用户在搜索/文件列表中通过菜单将当前筛选条件保存为虚拟文件夹时调用。
     *
     * ─── 创建流程 ──────────────────────────────────────────────
     * 1. 查询同级（根目录或指定父目录下）的虚拟文件夹列表。
     * 2. 计算当前最大 sortOrder，新文件夹的 sortOrder = max + 1，保证新文件夹排在列表末尾。
     * 3. 使用 [Gson] 将 [SavedFilter] 序列化为 JSON 字符串。
     * 4. 分配新的 UUID 作为 ID。
     * 5. 调用 [FavoritesDao.insertFavoriteFolder] 写入数据库。
     * 6. Room 的 Flow 机制自动通知 UI 更新。
     *
     * @param name 虚拟文件夹的显示名称。应简短且用户可识别。
     * @param savedFilter 保存的筛选条件对象，包含搜索关键词、标签过滤、排序方式等。
     *                    将被 Gson 序列化为 JSON 存储在数据库中。
     * @param belongToFolderId 所属自建文件夹 ID（可选）。
     *                         - null（默认）：虚拟文件夹位于根目录。
     *                         - 非空值：虚拟文件夹归入指定的自建文件夹下。
     * @see FavoriteFolder
     * @see SavedFilter
     * @see FavoritesDao.insertFavoriteFolder
     */
    suspend fun createFavoriteFolder(
        name: String,
        savedFilter: SavedFilter,
        belongToFolderId: String? = null
    ) {
        val siblings = if (belongToFolderId == null)
            getAllFavoriteFolders().filter { it.belongToFolderId == null }
        else
            getAllFavoriteFolders().filter { it.belongToFolderId == belongToFolderId }
        val maxOrder = siblings.maxOfOrNull { it.sortOrder } ?: -1

        val folder = FavoriteFolder(
            id = UUID.randomUUID().toString(),
            name = name,
            savedFilterJson = gson.toJson(savedFilter),
            belongToFolderId = belongToFolderId,
            sortOrder = maxOrder + 1,
            createdAt = System.currentTimeMillis()
        )
        dao.insertFavoriteFolder(folder)
    }

    /**
     * ## 更新虚拟文件夹
     *
     * ─── 调用场景 ──────────────────────────────────────────────
     * - 用户编辑虚拟文件夹的名称后保存时调用。
     * - 用户拖拽排序后更新 sortOrder 时调用。
     * - 用户修改虚拟文件夹的筛选条件后更新 savedFilterJson 时调用。
     *
     * @param folder 更新后的 [FavoriteFolder] 实体。DAO 基于 id 匹配更新数据库中的对应行。
     * @see FavoritesDao.updateFavoriteFolder
     */
    suspend fun updateFavoriteFolder(folder: FavoriteFolder) {
        dao.updateFavoriteFolder(folder)
    }

    /**
     * ## 删除虚拟文件夹
     *
     * 同时清理该文件夹下的 PDF 排序记录（如果有），再删除文件夹本身。
     *
     * ─── 调用场景 ──────────────────────────────────────────────
     * - 用户在编辑模式下点击"删除"按钮时调用。
     * - 长按虚拟文件夹弹出菜单选择"删除"时调用。
     *
     * ─── 删除流程 ──────────────────────────────────────────────
     * 1. 删除该文件夹对应的 [FolderPdfOrder] 排序记录（[FavoritesDao.deletePdfOrderForFolder]）。
     * 2. 从数据库查询完整的 [FavoriteFolder] 实体（DAO 的删除方法需要实体而非 ID）。
     * 3. 如果实体存在，调用 [FavoritesDao.deleteFavoriteFolder] 执行删除。
     *
     * @param id 要删除的虚拟文件夹 ID。
     * @see FavoritesDao.deletePdfOrderForFolder
     * @see FavoritesDao.deleteFavoriteFolder
     */
    suspend fun deleteFavoriteFolder(id: String) {
        dao.deletePdfOrderForFolder(id)
        val folder = dao.getFavoriteFolderById(id)
        if (folder != null) {
            dao.deleteFavoriteFolder(folder)
        }
    }

    /**
     * ## 创建自建文件夹
     *
     * 用户手动创建的目录，支持层级嵌套。
     * 自动计算同级 sortOrder 并分配 UUID。
     *
     * ─── 调用场景 ──────────────────────────────────────────────
     * - 用户在 OrganizeFragment 中点击"新建文件夹"按钮时调用。
     * - 用户在任意层级下创建子文件夹（传入 parentFolderId）时调用。
     *
     * ─── 创建流程 ──────────────────────────────────────────────
     * 1. 查询同级文件夹（根级或指定父目录下）列表。
     * 2. 计算最大 sortOrder，新文件夹排在同级末尾。
     * 3. 创建 [OrganizeFolder] 实体并写入数据库。
     *
     * @param name 文件夹显示名称。
     * @param parentFolderId 父自建文件夹 ID（可选）。
     *                       - null（默认）：在根目录创建。
     *                       - 非空值：在指定父文件夹下创建子文件夹。
     * @param sortOrder 初始排序序号。默认 0，实际会被自动计算覆盖以排到末尾。
     * @see OrganizeFolder
     * @see FavoritesDao.insertOrganizeFolder
     */
    suspend fun createOrganizeFolder(
        name: String,
        parentFolderId: String? = null,
        sortOrder: Int = 0
    ) {
        val siblings = if (parentFolderId == null)
            getRootOrganizeFolders()
        else
            getSubOrganizeFolders(parentFolderId)
        val maxOrder = siblings.maxOfOrNull { it.sortOrder } ?: -1

        val folder = OrganizeFolder(
            id = UUID.randomUUID().toString(),
            name = name,
            parentFolderId = parentFolderId,
            sortOrder = maxOrder + 1
        )
        dao.insertOrganizeFolder(folder)
    }

    /**
     * ## 更新自建文件夹
     *
     * ─── 调用场景 ──────────────────────────────────────────────
     * - 用户重命名自建文件夹后保存时调用。
     * - 用户拖拽排序后更新 sortOrder 时调用。
     *
     * @param folder 更新后的 [OrganizeFolder] 实体。DAO 基于 id 匹配更新。
     * @see FavoritesDao.updateOrganizeFolder
     */
    suspend fun updateOrganizeFolder(folder: OrganizeFolder) {
        dao.updateOrganizeFolder(folder)
    }

    /**
     * ## 删除自建文件夹
     *
     * 删除自建文件夹时，其内部所属的虚拟文件夹不会被级联删除，
     * 而是将它们移回根目录（belongToFolderId 置 null），避免用户数据丢失。
     *
     * ─── 调用场景 ──────────────────────────────────────────────
     * - 用户在 OrganizeFragment 中删除一个包含子项目的自建文件夹时调用。
     *
     * ─── 删除流程 ──────────────────────────────────────────────
     * 1. 查询所有属於该自建文件夹的虚拟文件夹列表（[getFavoriteFoldersByParentFlow].first()）。
     * 2. 遍历列表，使用 [copy(belongToFolderId = null)] 将每个虚拟文件夹的所属父目录置空，
     *    使其回归根目录，再调用 [updateFavoriteFolder] 更新到数据库。
     * 3. 删除该文件夹对应的 [FolderPdfOrder] 排序记录。
     * 4. 从数据库查询完整的 [OrganizeFolder] 实体并执行删除。
     *
     * @param id 要删除的自建文件夹 ID。
     * @see FavoritesDao.deletePdfOrderForFolder
     * @see FavoritesDao.deleteOrganizeFolder
     */
    suspend fun deleteOrganizeFolder(id: String) {
        // 删除自建文件夹时，内部的虚拟文件夹移动到根目录
        val subFavorites = getFavoriteFoldersByParentFlow(id).first()
        subFavorites.forEach { folder ->
            updateFavoriteFolder(folder.copy(belongToFolderId = null))
        }
        dao.deletePdfOrderForFolder(id)
        val folder = dao.getOrganizeFolderById(id)
        if (folder != null) {
            dao.deleteOrganizeFolder(folder)
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  PDF 排序（虚拟文件夹内部文件的用户自定义排序）
    //  使用逗号分隔的字符串存储 PDF ID 顺序，存储在 [FolderPdfOrder] 表中。
    // ═══════════════════════════════════════════════════════════

    /**
     * ## 保存虚拟文件夹内 PDF 文件的排序顺序
     *
     * 将虚拟文件夹内部的 PDF 排序记录持久化，
     * 使用逗号分隔的字符串存储文件 ID 列表。
     *
     * ─── 调用场景 ──────────────────────────────────────────────
     * - 用户在虚拟文件夹内部手动拖拽排序后，释放拖拽时调用。
     *
     * ─── 存储格式 ──────────────────────────────────────────────
     * `folderId + " → " + "pdfId1,pdfId2,pdfId3,..."`
     * 查询时再通过 [split(",")] 解析回 List<String>。
     *
     * @param folderId 虚拟文件夹 ID。
     * @param pdfIds 用户拖拽排序后的 PDF ID 有序列表。
     *               列表的顺序即用户希望的展示顺序。
     * @see FolderPdfOrder
     * @see FavoritesDao.insertOrUpdatePdfOrder
     */
    suspend fun savePdfOrder(folderId: String, pdfIds: List<String>) {
        val order = FolderPdfOrder(
            folderId = folderId,
            pdfOrder = pdfIds.joinToString(",")
        )
        dao.insertOrUpdatePdfOrder(order)
    }

    /**
     * ## 获取虚拟文件夹内 PDF 文件的排序顺序
     *
     * 从数据库读取之前保存的排序记录，解析为文件 ID 列表。
     * 如果没有排序记录（从未排序），返回空列表，上层应使用默认排序。
     *
     * ─── 调用场景 ──────────────────────────────────────────────
     * - 进入虚拟文件夹列表页面时，读取用户之前保存的排序顺序。
     * - 如果返回空列表，上层 UI 应使用默认排序（如按文件名、日期等）。
     *
     * @param folderId 虚拟文件夹 ID。
     * @return List<String> PDF 文件 ID 的有序列表，按照用户之前的排序排列。
     *         如果从来没有对该文件夹排序过，返回空列表。
     * @see FavoritesDao.getPdfOrderForFolder
     */
    suspend fun getPdfOrder(folderId: String): List<String> {
        val orderStr = dao.getPdfOrderForFolder(folderId) ?: return emptyList()
        return orderStr.split(",").filter { it.isNotBlank() }
    }
}
