package com.example.pdfmanager.ui.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.pdfmanager.data.model.FavoriteFolder
import com.example.pdfmanager.data.model.FilterLogic
import com.example.pdfmanager.data.model.OrganizeFolder
import com.example.pdfmanager.data.model.SavedFilter
import com.example.pdfmanager.data.repository.AppContainer
import com.google.gson.Gson
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * 收藏页面 ViewModel
 *
 * 用途：管理收藏页面的所有 UI 状态与业务逻辑，包括：
 *   - 列表数据流（自建文件夹 + 虚拟文件夹混合列表）的自动观察与刷新
 *   - 文件夹导航（进入/返回子文件夹）
 *   - 新建、重命名、删除、移动文件夹
 *   - 列表项排序（上移/下移）
 *   - 多选模式下虚拟文件夹的选中状态切换
 *
 * 调用位置：
 *   - 在 FavoritesScreen.kt 中通过 viewModel { FavoritesViewModel() } 创建
 *   - 所有 UI 操作（按钮点击、对话框确认等）均回调至此
 *
 * 使用场景：
 *   - 用户浏览收藏页列表时自动触发 Flow 收集
 *   - 用户执行新建/重命名/删除/移动操作后，Flow 自动刷新 UI
 *
 * 修改说明：改为观察 Flow 自动刷新 UI（问题2修复）
 */
class FavoritesViewModel : ViewModel() {

    // ── StateFlow 定义 ──────────────────────────────────────────

    /** 当前显示的列表项（OrganizeFolder 或 FavoriteFolder 的混合列表）
     *  现在由 Flow 自动更新，无需手动调用 loadRoot() */
    private val _items = MutableStateFlow<List<Any>>(emptyList())
    val items: StateFlow<List<Any>> = _items.asStateFlow()

    /** 当前所在的自建文件夹（null = 根目录） */
    private val _currentOrganizeFolder = MutableStateFlow<OrganizeFolder?>(null)
    val currentOrganizeFolder: StateFlow<OrganizeFolder?> = _currentOrganizeFolder.asStateFlow()

    /** 是否需要显示「新建文件夹」对话框 */
    private val _showCreateDialog = MutableStateFlow(false)
    val showCreateDialog: StateFlow<Boolean> = _showCreateDialog.asStateFlow()

    /** 新建文件夹时的名称输入 */
    private val _newFolderName = MutableStateFlow("")
    val newFolderName: StateFlow<String> = _newFolderName.asStateFlow()

    /** 长按菜单目标（OrganizeFolder 或 FavoriteFolder） */
    private val _menuTarget = MutableStateFlow<Any?>(null)
    val menuTarget: StateFlow<Any?> = _menuTarget.asStateFlow()

    // ── 重命名状态 ──────────────────────────────────────────────

    /** 是否显示「重命名」对话框 */
    private val _showRenameDialog = MutableStateFlow(false)
    val showRenameDialog: StateFlow<Boolean> = _showRenameDialog.asStateFlow()

    /** 重命名输入框中的名称文字 */
    private val _renameName = MutableStateFlow("")
    val renameName: StateFlow<String> = _renameName.asStateFlow()

    /** 重命名目标（与 _menuTarget 分离，避免菜单关闭后被清空） */
    private val _renameTarget = MutableStateFlow<Any?>(null)

    /** 用于取消当前 Flow 收集的 Job */
    private var currentCollectionJob: kotlinx.coroutines.Job? = null

    /**
     * 初始化：启动对当前文件夹的 Flow 观察
     * 自动收集数据库变化并刷新列表
     */
    init {
        // 初始加载根目录，并启动 Flow 观察
        observeCurrentFolder()
    }

    // ── Flow 观察 ──────────────────────────────────

    /**
     * 根据当前所在文件夹，观察对应的 Flow 并自动更新 _items
     *
     * 逻辑说明：
     *   使用 flatMapLatest 操作符——当 _currentOrganizeFolder 发射新值时，
     *   自动取消上一个 inner flow 的收集，切换为新的 flow。
     *   - 若当前文件夹为 null（根目录），观察 getRootItemsFlow()
     *   - 若当前文件夹非 null，观察 getSubItemsFlow(folder.id)
     *
     * 调用位置：init 块中调用一次；同时也被 openOrganizeFolder / navigateUp 隐式触发
     *           （因为 _currentOrganizeFolder 变化后 flatMapLatest 会自动切换）
     */
    private fun observeCurrentFolder() {
        currentCollectionJob?.cancel()
        currentCollectionJob = viewModelScope.launch {
            _currentOrganizeFolder
                .flatMapLatest { folder: OrganizeFolder? ->
                    if (folder == null) {
                        AppContainer.favoritesRepository.getRootItemsFlow()
                    } else {
                        AppContainer.favoritesRepository.getSubItemsFlow(folder.id)
                    }
                }
                .collect { items ->
                    _items.value = items
                }
        }
    }

    // ── 文件夹导航 ──────────────────────────────────

    /**
     * 返回到根目录
     *
     * 调用位置：FavoritesScreen 暂时未直接调用
     * 使用场景：当 navigateUp 发现当前文件夹无有效 parentId 时内部调用
     */
    fun loadRoot() {
        _currentOrganizeFolder.value = null
        // Flow 会自动切换到根目录的观察
    }

    /**
     * 打开指定的自建文件夹（进入子文件夹）
     *
     * 调用位置：FavoritesScreen.kt 中 OrganizeFolderItem 的 onClick 回调
     * 使用场景：用户点击自建文件夹卡片进入该文件夹
     *
     * @param folder 要打开的自建文件夹对象
     */
    fun openOrganizeFolder(folder: OrganizeFolder) {
        _currentOrganizeFolder.value = folder
        // Flow 会自动切换到该文件夹的观察
    }

    /**
     * 导航到上一级目录
     *
     * 调用位置：FavoritesScreen.kt 中 BackHandler 和 navigationIcon 的 onClick 回调
     * 使用场景：
     *   - 用户点击顶部返回箭头
     *   - 用户按下系统返回键（非多选模式且在文件夹内）
     *
     * @return true 表示成功执行了导航（有上级目录），false 表示已在根目录
     */
    fun navigateUp(): Boolean {
        val current = _currentOrganizeFolder.value ?: return false
        val parentId = current.parentFolderId
        return if (parentId != null) {
            viewModelScope.launch {
                val parent = AppContainer.favoritesRepository.getOrganizeFolderById(parentId)
                if (parent != null) {
                    _currentOrganizeFolder.value = parent
                } else {
                    loadRoot()
                }
            }
            true
        } else {
            loadRoot()
            true
        }
    }

    // ── 新建文件夹 ──────────────────────────────────

    /**
     * 显示「新建文件夹」对话框
     * 同时清空上次输入的文件夹名称
     *
     * 调用位置：FavoritesScreen.kt 中 TopAppBar actions 区域"新建文件夹"按钮的 onClick
     */
    fun showCreateFolderDialog() {
        _newFolderName.value = ""
        _showCreateDialog.value = true
    }

    /**
     * 关闭「新建文件夹」对话框
     *
     * 调用位置：FavoritesScreen.kt 中 AlertDialog 的 onDismissRequest 和"取消"按钮
     */
    fun dismissCreateDialog() {
        _showCreateDialog.value = false
    }

    /**
     * 实时更新新建文件夹的名称输入
     *
     * 调用位置：FavoritesScreen.kt 中 OutlinedTextField 的 onValueChange
     *
     * @param name 用户当前输入的名称文字
     */
    fun onNewFolderNameChange(name: String) {
        _newFolderName.value = name
    }

    /**
     * 确认创建文件夹
     * 校验名称非空后，通过 Repository 创建 OrganizeFolder，
     * 创建成功后 Flow 自动刷新列表。
     *
     * 调用位置：FavoritesScreen.kt 中"创建"按钮的 onClick
     */
    fun confirmCreateFolder() {
        val name = _newFolderName.value
        if (name.isBlank()) return
        _showCreateDialog.value = false
        viewModelScope.launch {
            val parentId = _currentOrganizeFolder.value?.id
            AppContainer.favoritesRepository.createOrganizeFolder(name, parentId)
            // Flow 会自动刷新列表，无需手动调用 loadRoot()
        }
    }

    // ── 长按菜单 ──────────────────────────────────

    /**
     * 显示长按操作菜单（将目标存入 _menuTarget）
     *
     * 调用位置：FavoritesScreen.kt 中 OrganizeFolderItem / FavoriteFolderItem 的 onLongClick 回调
     * 使用场景：用户长按任意列表项时触发
     *
     * @param target 被长按的列表项（OrganizeFolder 或 FavoriteFolder）
     */
    fun showMenu(target: Any) {
        _menuTarget.value = target
    }

    /**
     * 关闭长按操作菜单（清空 _menuTarget）
     *
     * 调用位置：FavoritesScreen.kt 中 AlertDialog 的 onDismissRequest 和"取消"按钮
     */
    fun dismissMenu() {
        _menuTarget.value = null
    }

    // ── 重命名 ──────────────────────────────────

    /**
     * 显示「重命名」对话框
     * 将目标存入独立字段 _renameTarget（与 _menuTarget 分离），避免菜单关闭后目标丢失。
     *
     * 调用位置：FavoritesScreen.kt 中长按菜单的"重命名"按钮的 onClick
     * 使用场景：用户在操作菜单中选择"重命名"
     *
     * @param target 要重命名的对象（OrganizeFolder 或 FavoriteFolder）
     */
    fun showRenameDialog(target: Any) {
        _menuTarget.value = null
        _renameTarget.value = target  // ← 存入独立字段，不再依赖 _menuTarget
        val name = when (target) {
            is OrganizeFolder -> target.name
            is FavoriteFolder -> target.name
            else -> ""
        }
        _renameName.value = name
        _showRenameDialog.value = true
    }

    /**
     * 关闭重命名弹窗并清空所有相关状态
     *
     * 调用位置：FavoritesScreen.kt 中重命名对话框的 onDismissRequest 和"取消"按钮
     */
    fun dismissRenameDialog() {
        _showRenameDialog.value = false
        _renameName.value = ""
        _renameTarget.value = null
    }

    /**
     * 实时更新重命名的名称输入
     *
     * 调用位置：FavoritesScreen.kt 中重命名对话框 OutlinedTextField 的 onValueChange
     *
     * @param name 用户当前输入的新名称
     */
    fun onRenameNameChange(name: String) {
        _renameName.value = name
    }

    /**
     * 确认重命名
     * 根据目标类型（OrganizeFolder / FavoriteFolder）调用对应 Repository 方法更新名称。
     *
     * 调用位置：FavoritesScreen.kt 中重命名对话框"确定"按钮的 onClick
     */
    fun confirmRename() {
        val target = _renameTarget.value ?: return  // ← 从 _renameTarget 读取
        val newName = _renameName.value
        if (newName.isBlank()) return
        _showRenameDialog.value = false
        _renameTarget.value = null  // ← 用后清空
        viewModelScope.launch {
            when (target) {
                is OrganizeFolder -> {
                    AppContainer.favoritesRepository.updateOrganizeFolder(target.copy(name = newName))
                }
                is FavoriteFolder -> {
                    AppContainer.favoritesRepository.updateFavoriteFolder(target.copy(name = newName))
                }
            }
            // Flow 会自动刷新列表，无需手动调用 refreshCurrentList()
        }
    }

    // ── 删除 ──────────────────────────────────

    /**
     * 确认删除目标对象
     * 根据目标类型调用对应 Repository 方法执行删除。
     * 自建文件夹内的虚拟文件夹会被移到根目录（由 Repository 处理）。
     *
     * 调用位置：FavoritesScreen.kt 中删除确认对话框"确定"按钮的 onClick
     *
     * @param target 要删除的对象（OrganizeFolder 或 FavoriteFolder）
     */
    fun confirmDelete(target: Any) {
        _menuTarget.value = null
        viewModelScope.launch {
            when (target) {
                is OrganizeFolder -> {
                    AppContainer.favoritesRepository.deleteOrganizeFolder(target.id)
                }
                is FavoriteFolder -> {
                    AppContainer.favoritesRepository.deleteFavoriteFolder(target.id)
                }
            }
            // Flow 会自动刷新列表，无需手动调用 refreshCurrentList()
        }
    }

    // ── 移动虚拟文件夹到自建文件夹 ──────────────────────────────────

    /**
     * 将虚拟文件夹移动到目标自建文件夹
     *
     * 调用位置：FavoritesScreen.kt 中"移动到"对话框的各个选项按钮的 onClick
     * 使用场景：用户长按虚拟文件夹 → 选择"移动到" → 选择目标文件夹
     *
     * @param favoriteFolderId         虚拟文件夹 ID
     * @param targetOrganizeFolderId 目标自建文件夹 ID（null 表示移到根目录）
     */
    fun moveFavoriteFolderToOrganize(favoriteFolderId: String, targetOrganizeFolderId: String?) {
        viewModelScope.launch {
            val folder = AppContainer.favoritesRepository.getFavoriteFolderById(favoriteFolderId)
            if (folder != null) {
                AppContainer.favoritesRepository.updateFavoriteFolder(
                    folder.copy(belongToFolderId = targetOrganizeFolderId)
                )
            }
        }
    }

    // ── 排序（上移/下移）───────────────────────────

    /**
     * 上移一位
     * 与上一项交换 sortOrder，排序后 Flow 自动刷新列表。
     *
     * 调用位置：FavoritesScreen.kt 中 OrganizeFolderItem / FavoriteFolderItem 的 onMoveUp 回调
     * 使用场景：用户点击列表项右侧的"上移"箭头按钮
     *
     * @param item 要上移的列表项
     */
    fun moveItemUp(item: Any) {
        val list = _items.value
        val index = list.indexOf(item)
        if (index <= 0) return
        viewModelScope.launch {
            swapOrder(item, list[index - 1])
            // Flow 会自动刷新列表
        }
    }

    /**
     * 下移一位
     * 与下一项交换 sortOrder，排序后 Flow 自动刷新列表。
     *
     * 调用位置：FavoritesScreen.kt 中 OrganizeFolderItem / FavoriteFolderItem 的 onMoveDown 回调
     * 使用场景：用户点击列表项右侧的"下移"箭头按钮
     *
     * @param item 要下移的列表项
     */
    fun moveItemDown(item: Any) {
        val list = _items.value
        val index = list.indexOf(item)
        if (index >= list.size - 1) return
        viewModelScope.launch {
            swapOrder(item, list[index + 1])
            // Flow 会自动刷新列表
        }
    }

    /**
     * 交换两个列表项的 sortOrder 值
     * 通过分别更新两者的 sortOrder 实现位置互换。
     *
     * 调用位置：moveItemUp() 和 moveItemDown() 内部调用
     *
     * @param item1 第一个列表项
     * @param item2 第二个列表项
     */
    private suspend fun swapOrder(item1: Any, item2: Any) {
        val order1 = when (item1) {
            is OrganizeFolder -> item1.sortOrder
            is FavoriteFolder -> item1.sortOrder
            else -> return
        }
        val order2 = when (item2) {
            is OrganizeFolder -> item2.sortOrder
            is FavoriteFolder -> item2.sortOrder
            else -> return
        }

        when (item1) {
            is OrganizeFolder -> {
                AppContainer.favoritesRepository.updateOrganizeFolder(item1.copy(sortOrder = order2))
            }
            is FavoriteFolder -> {
                AppContainer.favoritesRepository.updateFavoriteFolder(item1.copy(sortOrder = order2))
            }
        }

        when (item2) {
            is OrganizeFolder -> {
                AppContainer.favoritesRepository.updateOrganizeFolder(item2.copy(sortOrder = order1))
            }
            is FavoriteFolder -> {
                AppContainer.favoritesRepository.updateFavoriteFolder(item2.copy(sortOrder = order1))
            }
        }
    }

    // ── 多选模式：切换文件夹选中状态 ──────────────────────────────────

    /**
     * 切换虚拟文件夹的选中状态
     *
     * 逻辑说明：
     *   1. 解析该文件夹的 savedFilterJson 得到 SavedFilter
     *   2. 根据 SavedFilter 计算该文件夹下匹配的所有文件 ID
     *   3. 如果所有匹配文件都已选中 → 取消选中；否则 → 选中这些文件
     *
     * 调用位置：FavoritesScreen.kt 中 FavoriteFolderItem 的 onToggleSelection 回调
     * 使用场景：多选模式下用户点击虚拟文件夹的 Checkbox
     *
     * @param folder 要切换选中状态的虚拟文件夹
     */
    fun toggleFolderSelection(folder: FavoriteFolder) {
        Log.d("FavoritesViewModel", "▶ toggleFolderSelection 开始! folder=${folder.name}, folder.id=${folder.id}")
        viewModelScope.launch {
            // 解析 savedFilterJson，空值则使用默认空 SavedFilter
            val savedFilter = if (folder.savedFilterJson.isNullOrBlank()) {
                Log.d("FavoritesViewModel", "  savedFilterJson 为空，使用默认 SavedFilter()")
                SavedFilter()
            } else {
                try {
                    val parsed = Gson().fromJson(folder.savedFilterJson, SavedFilter::class.java)
                    Log.d("FavoritesViewModel", "  savedFilterJson 解析成功: selectedTagKeys=${parsed.selectedTagKeys}, includeNoTag=${parsed.includeNoTag}, filterLogic=${parsed.filterLogic}")
                    parsed
                } catch (e: Exception) {
                    Log.e("FavoritesViewModel", "  savedFilterJson 解析失败: ${e.message}")
                    SavedFilter()
                }
            }

            // 2. 根据 savedFilter 计算该文件夹下的文件 ID 列表
            val fileIds = getFileIdsByFilter(savedFilter)
            Log.d("FavoritesViewModel", "  计算出的 fileIds=$fileIds (size=${fileIds.size})")

            // 3. 切换选中状态
            val currentSelection = AppContainer.selectedFileIds.value
            Log.d("FavoritesViewModel", "  当前 selectedFileIds=$currentSelection (size=${currentSelection.size})")
            
            val isCurrentlySelected = fileIds.isNotEmpty() && fileIds.all { currentSelection.contains(it) }
            Log.d("FavoritesViewModel", "  isCurrentlySelected=$isCurrentlySelected")

            val newSelection = if (isCurrentlySelected) {
                // 如果已经全部选中，则取消选中这些文件
                val result = currentSelection - fileIds
                Log.d("FavoritesViewModel", "  取消选中，newSelection=$result (size=${result.size})")
                result
            } else {
                // 如果未全部选中，则添加这些文件
                val result = currentSelection + fileIds
                Log.d("FavoritesViewModel", "  添加选中，newSelection=$result (size=${result.size})")
                result
            }

            AppContainer.selectedFileIds.value = newSelection
            Log.d("FavoritesViewModel", "  ✅ selectedFileIds 已更新为 $newSelection")
        }
    }

    /**
     * 根据 SavedFilter 计算符合条件的文件 ID 列表
     *
     * 逻辑说明：
     *   1. 获取所有 PDF 文件
     *   2. 获取所有 PDF-标签关系，构建 pdfUri → Set<tagKey> 映射
     *   3. 遍历文件，根据 filterLogic（AND/OR）和 includeNoTag 筛选
     *
     * 调用位置：
     *   - toggleFolderSelection() 内部调用
     *   - isFolderSelected() 内部调用
     *
     * @param savedFilter 筛选条件对象（包含 selectedTagKeys、includeNoTag、filterLogic）
     * @return 匹配筛选条件的文件 ID 集合
     */
    private suspend fun getFileIdsByFilter(savedFilter: SavedFilter): Set<String> {
        Log.d("FavoritesViewModel", "▶ getFileIdsByFilter 开始")
        // 获取所有文件
        val allFiles = AppContainer.pdfRepository.getAllFiles()
        Log.d("FavoritesViewModel", "  所有文件数量: ${allFiles.size}")

        // 如果没有选中任何标签，且不包含无标签文件，则返回空列表
        if (savedFilter.selectedTagKeys.isEmpty() && !savedFilter.includeNoTag) {
            Log.d("FavoritesViewModel", "  selectedTagKeys 为空且 includeNoTag=false，返回空")
            return emptySet()
        }

        // 获取所有 PDF 的标签关系（从 pdf_tags 表）
        val allPdfTags = AppContainer.tagRepository.getAllPdfTags()
        Log.d("FavoritesViewModel", "  所有 PDF 标签关系数量: ${allPdfTags.size}")

        // 构建 pdfUri -> Set<tagKey> 的映射
        val fileTagKeysMap = mutableMapOf<String, MutableSet<String>>()
        allPdfTags.forEach { pdfTag ->
            val tagKey = "${pdfTag.categoryId}:${pdfTag.tagValue}"
            val tagKeys = fileTagKeysMap.getOrPut(pdfTag.pdfFileUri) { mutableSetOf() }
            tagKeys.add(tagKey)
        }
        Log.d("FavoritesViewModel", "  fileTagKeysMap 大小: ${fileTagKeysMap.size}")

        // 筛选文件
        val filteredFiles = allFiles.filter { file ->
            val fileTagKeys = fileTagKeysMap[file.uri.toString()] ?: emptySet()

            // 检查是否匹配选中的标签
            val matchesTags = if (savedFilter.selectedTagKeys.isEmpty()) {
                true
            } else {
                if (savedFilter.filterLogic == FilterLogic.AND) {
                    // AND 逻辑：文件必须包含所有选中的标签键
                    savedFilter.selectedTagKeys.all { fileTagKeys.contains(it) }
                } else {
                    // OR 逻辑：文件只需包含任意一个选中的标签键
                    savedFilter.selectedTagKeys.any { fileTagKeys.contains(it) }
                }
            }

            // 检查是否包含无标签文件
            val matchesNoTag = if (savedFilter.includeNoTag) {
                fileTagKeys.isEmpty()
            } else {
                true
            }

            matchesTags && matchesNoTag
        }

        Log.d("FavoritesViewModel", "  ✅ 筛选结果: ${filteredFiles.size} 个文件")
        return filteredFiles.map { it.id }.toSet()
    }

    /**
     * 检查虚拟文件夹是否被选中（即该文件夹下的所有文件是否都被选中）
     *
     * 逻辑说明：
     *   1. 解析 savedFilterJson 得到 SavedFilter
     *   2. 调用 getFileIdsByFilter 计算匹配文件 ID
     *   3. 检查所有匹配文件是否都在 selectedFileIds 中
     *
     * 调用位置：FavoritesScreen.kt 中 LaunchedEffect(selectedFileIds) 块内
     * 使用场景：多选模式下实时更新虚拟文件夹的 Checkbox 选中状态
     *
     * @param folder 要检查的虚拟文件夹
     * @return true 表示该文件夹下所有文件都已选中；false 表示未全选
     */
    suspend fun isFolderSelected(folder: FavoriteFolder): Boolean {
        Log.d("FavoritesViewModel", "▶ isFolderSelected 开始! folder=${folder.name}, folder.id=${folder.id}")
        // 解析 savedFilterJson，空值则使用默认空 SavedFilter
        val savedFilter = if (folder.savedFilterJson.isNullOrBlank()) {
            Log.d("FavoritesViewModel", "  savedFilterJson 为空，使用默认 SavedFilter()")
            SavedFilter()
        } else {
            try {
                val parsed = Gson().fromJson(folder.savedFilterJson, SavedFilter::class.java)
                Log.d("FavoritesViewModel", "  savedFilterJson 解析成功: selectedTagKeys=${parsed.selectedTagKeys}, includeNoTag=${parsed.includeNoTag}")
                parsed
            } catch (e: Exception) {
                Log.e("FavoritesViewModel", "  savedFilterJson 解析失败: ${e.message}")
                SavedFilter()
            }
        }
        val fileIds = getFileIdsByFilter(savedFilter)
        Log.d("FavoritesViewModel", "  计算出的 fileIds=$fileIds (size=${fileIds.size})")
        val selectedFileIds = AppContainer.selectedFileIds.value
        Log.d("FavoritesViewModel", "  当前 selectedFileIds=$selectedFileIds (size=${selectedFileIds.size})")
        val result = fileIds.isNotEmpty() && fileIds.all { selectedFileIds.contains(it) }
        Log.d("FavoritesViewModel", "  ✅ isFolderSelected 返回 $result")
        return result
    }
}
