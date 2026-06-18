package com.example.pdfmanager.ui.screen.allfiles

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.pdfmanager.data.local.FileScanner
import com.example.pdfmanager.data.local.PreferencesManager
import com.example.pdfmanager.data.model.PdfFile
import com.example.pdfmanager.data.model.Tag
import com.example.pdfmanager.data.model.SavedFilter
import com.example.pdfmanager.data.model.FilterLogic
import com.example.pdfmanager.data.repository.PdfRepository
import com.example.pdfmanager.data.repository.AppContainer
import com.example.pdfmanager.util.ThumbSize
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers


/**
 * 全部文件页面 ViewModel
 * 
 * 功能说明：
 * 1. 管理文件列表状态（从 PdfRepository 获取 PDF 文件列表）
 * 2. 管理视图模式（网格/列表）和缩略图大小
 * 3. 管理扫描流程（首次全量扫描、增量扫描、手动刷新）
 * 4. 管理筛选条件（应用/清除筛选，按标签过滤）
 * 5. 管理多选分享（选中/全选/清空/分享）
 * 6. 协调引导页显示逻辑（需绑定库文件夹）
 * 
 * 使用示例：
 * ```kotlin
 * // 在 MainActivity 中初始化
 * val viewModel = ViewModelProvider(this, AllFilesViewModel.Factory())[AllFilesViewModel::class.java]
 * 
 * // 观察文件列表
 * viewModel.pdfFiles.collect { files ->
 *     // 更新 UI
 * }
 * 
 * // 切换到网格模式
 * viewModel.toggleViewMode()
 * 
 * // 刷新文件列表
 * viewModel.refresh()
 * ```
 * 
 * 数据流：
 * ```
 * PdfRepository._pdfFiles（StateFlow）  ──── 文件列表数据源
 *         ↓ collect
 * AllFilesViewModel._allPdfFiles ──── 完整文件列表（未筛选）
 *         ↓ applyCurrentFilter()
 * AllFilesViewModel._pdfFiles  ──── 最终显示的列表（已过筛选）
 *         ↓ 
 * AllFilesScreen UI ──── Composable 展示
 * ```
 * 
 * 生命周期：
 * - 1. initialize() → 冷启动/热启动时调用
 * - 2. incrementalScan() → Activity.onResume() 时调用
 * - 3. refresh() → 用户手动下拉刷新时调用
 * 
 * 依赖关系：
 * - 依赖：PdfRepository、PreferencesManager、AppContainer
 * - 被依赖：AllFilesScreen（Composable UI）
 * 
 * @author PDF Manager Development Team
 * @version 1.0
 */
class AllFilesViewModel(
    private val pdfRepository: PdfRepository,
    private val preferencesManager: PreferencesManager
) : ViewModel() {

    // ── 状态：文件列表（最终显示的列表，已过筛选）─────────────────────────

    /**
     * 最终显示的文件列表（经过筛选后的结果）
     * 
     * 更新时机：
     * - applyCurrentFilter() - 筛选条件变化时
     * - applyFilter() - 用户设置新筛选条件时
     * - clearFilter() - 用户清除筛选时
     */
    private val _pdfFiles = MutableStateFlow<List<PdfFile>>(emptyList())
    /** 供 UI 层观察的文件列表 */
    val pdfFiles: StateFlow<List<PdfFile>> = _pdfFiles

    // ── 状态：视图模式（网格/列表）─────────────────────────────

    /** 是否使用网格视图（true=网格, false=列表） */
    private val _isGridView = MutableStateFlow(true)
    val isGridView: StateFlow<Boolean> = _isGridView

    // ── 状态：加载中 ─────────────────────────────────────

    /** 是否正在加载（用于显示进度指示器） */
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    // ── 状态：缩略图大小 ─────────────────────────────────────

    /** 缩略图大小（LARGE/MEDIUM/SMALL） */
    private val _thumbSize = MutableStateFlow(ThumbSize.MEDIUM)
    val thumbSize: StateFlow<ThumbSize> = _thumbSize

    // ── 状态：引导页 ─────────────────────────────────────

    /** 是否需要显示引导页（true=未绑定库文件夹，需引导用户选择） */
    private val _needsLibrarySetup = MutableStateFlow(false)
    val needsLibrarySetup: StateFlow<Boolean> = _needsLibrarySetup

    // ── 扫描进度 ─────────────────────────────────────

    /** 扫描进度（当前正在扫描的文件名和数量） */
    private val _scanProgress = MutableStateFlow<ScanProgress?>(null)
    val scanProgress: StateFlow<ScanProgress?> = _scanProgress.asStateFlow()

    /**
     * 扫描进度数据类
     * 
     * @property scannedCount 已扫描的文件数量
     * @property currentFileName 当前正在扫描的文件名
     */
    data class ScanProgress(
        val scannedCount: Int,
        val currentFileName: String = ""
    )

    // ── 初始化锁（防止 Activity 重建后重复初始化）─────────────────────────

    /**
     * 防止 Activity 重建后重复初始化
     * 处理场景：旋转屏幕、返回栈重建等 Activity 重建场景
     */
    private val _hasInitialized = MutableStateFlow(false)

    // ── 筛选相关状态 ─────────────────────────────────────

    /** 完整文件列表（未经过筛选） */
    private val _allPdfFiles = MutableStateFlow<List<PdfFile>>(emptyList())
    /** 当前筛选条件（null 表示未筛选） */
    private val _currentFilter = MutableStateFlow<SavedFilter?>(null)
    /** 筛选是否激活 */
    private val _isFilterActive = MutableStateFlow(false)
    val isFilterActive: StateFlow<Boolean> = _isFilterActive.asStateFlow()
    val currentFilter: StateFlow<SavedFilter?> = _currentFilter.asStateFlow()

    // ── 多选分享相关状态 ─────────────────────────────────────

    /** 是否处于多选模式 */
    private val _isMultiSelectMode = MutableStateFlow(false)
    val isMultiSelectMode: StateFlow<Boolean> = _isMultiSelectMode.asStateFlow()
    /** 选中的文件 ID 集合 */
    private val _selectedFileIds = MutableStateFlow<Set<String>>(emptySet())
    val selectedFileIds: StateFlow<Set<String>> = _selectedFileIds.asStateFlow()

    // ── 初始化方法（核心启动入口）─────────────────────────────────────

    /**
     * 初始化 ViewModel（仅在首次调用时执行）
     * 
     * 功能说明：
     * 1. 观察缩略图大小设置变化（从 PreferencesManager Flow）
     * 2. 观察多选模式状态变化
     * 3. 观察筛选结果变化（来自 FilterScreen）
     * 4. 观察扫描进度（转发给 UI）
     * 5. 检查库文件夹是否已绑定（决定是否显示引导页）
     * 6. 从 Room 数据库恢复文件列表（冷启动）
     * 7. 如果数据库为空，执行首次全量扫描
     * 8. 持续观察 PdfRepository 文件列表变化（实时刷新）
     * 
     * 调用位置：
     * - MainActivity.onCreate() - Activity 创建时调用
     * - AllFilesScreen.LaunchedEffect - Composable 首次组合时调用
     * 
     * 使用场景：
     * - 应用冷启动（进程被系统杀死后恢复）
     * - Activity 重建（旋转屏幕等）
     * 
     * 线程安全：
     * - 所有 collect 操作在 viewModelScope 中执行
     * - _hasInitialized 防止重复初始化
     */
    fun initialize() {
        if (_hasInitialized.value) return
        _hasInitialized.value = true

        // ── 1. 观察缩略图大小设置变化 ──
        viewModelScope.launch {
            preferencesManager.getThumbnailSizeFlow().collect { intValue ->
                _thumbSize.value = when (intValue) {
                    0 -> ThumbSize.LARGE
                    2 -> ThumbSize.SMALL
                    else -> ThumbSize.MEDIUM
                }
            }
        }

        // ── 2. 观察多选模式状态 ──
        viewModelScope.launch {
            AppContainer.preferencesManager.getMultiSelectModeFlow().collect { enabled ->
                _isMultiSelectMode.value = enabled
            }
        }

        // ── 3. 观察筛选结果变化（来自 FilterScreen） ──
        viewModelScope.launch {
            AppContainer.pendingFilterResult.collect { filter ->
                if (filter != null) {
                    applyFilter(filter)
                    AppContainer.consumePendingFilterResult() // 消费后清除，避免重复处理
                }
            }
        }

        // ── 4. 观察扫描进度 ──
        viewModelScope.launch {
            pdfRepository.scanProgress.collect { progress ->
                _scanProgress.value = progress?.let {
                    ScanProgress(it.scannedCount, it.currentFileName)
                }
            }
        }

        // ── 5. 核心启动逻辑 ──
        viewModelScope.launch {
            // 检查库文件夹是否已绑定
            val libraryUri = pdfRepository.getLibraryUri()
            if (libraryUri == null) {
                _needsLibrarySetup.value = true  // 显示引导页
                return@launch
            }

            // 库已绑定，确保引导页隐藏
            _needsLibrarySetup.value = false

            // 加载并初始化标签类别
            AppContainer.tagRepository.loadCategories()

            // 从 Room 恢复文件列表（冷启动优化，避免全量扫描）
            val restoredFiles = pdfRepository.restoreFromRoom()

            if (restoredFiles != null) {
                // 恢复成功：立即显示 UI，不做扫描
                _allPdfFiles.value = restoredFiles
                applyCurrentFilter()
                AppContainer.searchIndexRepository.buildIndex(restoredFiles)
                _isLoading.value = false
                Log.d("AllFilesViewModel", "从 Room 恢复 ${restoredFiles.size} 个文件，立即显示（无扫描）")
            } else {
                // 恢复失败（首次使用）：需要全量扫描
                _isLoading.value = false
                try {
                    Log.d("AllFilesViewModel", "Room 无数据，执行首次全量扫描")
                    pdfRepository.scanLibrary()
                    val allFiles = pdfRepository.pdfFiles.value
                    _allPdfFiles.value = allFiles
                    applyCurrentFilter()
                    AppContainer.searchIndexRepository.buildIndex(allFiles)

                    // 检查并设置缩略图路径
                    val updatedFiles = pdfRepository.checkAndSetThumbnailPaths(allFiles)
                    _allPdfFiles.value = updatedFiles
                    applyCurrentFilter()
                } catch (e: Exception) {
                    Log.e("AllFilesViewModel", "全量扫描失败", e)
                } finally {
                    _isLoading.value = false
                }
            }
        }

        // ── 6. 实时观察 PdfRepository 文件列表变化 ──
        //     同时监听库切换刷新信号（libraryRefreshTick），切换后自动
        //     重新绑定到新的 PdfRepository 实例（因为旧实例的 StateFlow 已失效）
        viewModelScope.launch {
            AppContainer.libraryRefreshTick.flatMapLatest {
                // 每次 tick 变化时自动取消旧收集，重新从当前 PdfRepository 获取
                AppContainer.pdfRepository.pdfFiles
            }.collect { newList ->
                _allPdfFiles.value = newList
                applyCurrentFilter()
            }
        }

        // ── 7. 监听库切换信号，重新检查引导页状态 ──
        //     首次绑定库文件夹后，_needsLibrarySetup 需要从 true 切换为 false
        viewModelScope.launch {
            AppContainer.libraryRefreshTick.collect {
                val libraryUri = AppContainer.pdfRepository.getLibraryUri()
                if (libraryUri != null) {
                    _needsLibrarySetup.value = false
                    Log.d("AllFilesViewModel", "libraryRefreshTick: 库已绑定，隐藏引导页")
                }
            }
        }
    }

    // ── 视图模式切换 ─────────────────────────────────────

    /**
     * 切换视图模式（网格 ↔ 列表）
     * 
     * 调用位置：
     * - AllFilesScreen.toggleButton.onClick() - 用户点击切换按钮时调用
     */
    fun toggleViewMode() {
        _isGridView.value = !_isGridView.value
    }

    // ── 缩略图大小设置 ─────────────────────────────────────

    /**
     * 设置缩略图大小
     * 
     * 功能说明：
     * 1. 更新内存中的缩略图大小状态
     * 2. 持久化到 PreferencesManager
     * 
     * 调用位置：
     * - AllFilesScreen.settingsMenu - 用户选择缩略图大小时调用
     * 
     * @param size 缩略图大小（ThumbSize.LARGE/SMALL/MEDIUM）
     */
    fun setThumbSize(size: ThumbSize) {
        _thumbSize.value = size
        // 持久化到 PreferencesManager
        viewModelScope.launch {
            val intValue = when (size) {
                ThumbSize.LARGE -> 0
                ThumbSize.SMALL -> 2
                else -> 1 // MEDIUM
            }
            preferencesManager.saveThumbnailSize(intValue)
        }
    }

    // ── 扫描方法 ─────────────────────────────────────

    /**
     * onResume 增量扫描（轻量级）
     * 
     * 功能说明：
     * 1. 每次 Activity.onResume() 时调用
     * 2. 快速 diff 文件列表（只检测新增/删除/移动）
     * 3. 不触发全量扫描（速度远快于全量扫描）
     * 4. 更新搜索结果索引
     * 
     * 调用位置：
     * - MainActivity.onResume() - Activity 恢复时调用
     * 
     * 使用场景：
     * - 用户从其他应用返回（可能修改了文件）
     * - 后台 FileObserver 触发了变化
     */
    fun incrementalScan() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                Log.d("AllFilesViewModel", "onResume 轻量增量扫描")
                pdfRepository.quickIncrementalScan()
                _allPdfFiles.value = pdfRepository.pdfFiles.value
                applyCurrentFilter()
                AppContainer.searchIndexRepository.buildIndex(_allPdfFiles.value)
            } catch (e: Exception) {
                Log.e("AllFilesViewModel", "增量扫描失败", e)
            }
        }
    }

    /**
     * 用户手动刷新（如下拉刷新）
     * 
     * 功能说明：
     * 1. 只做轻量 diff（检测新增/删除/移动）
     * 2. 不触发全量扫描
     * 3. 显示 loading 状态
     * 4. 更新搜索结果索引
     * 
     * 调用位置：
     * - AllFilesScreen.pullRefresh - 用户下拉刷新时调用
     * - AllFilesScreen.refreshButton.onClick() - 用户点击刷新按钮时调用
     * 
     * 使用场景：
     * - 用户手动刷新文件列表
     */
    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            _isLoading.value = true
            try {
                Log.d("AllFilesViewModel", "用户手动刷新，开始轻量增量扫描")
                pdfRepository.quickIncrementalScan()
                val updated = pdfRepository.pdfFiles.value
                _allPdfFiles.value = updated
                applyCurrentFilter()
                AppContainer.searchIndexRepository.buildIndex(updated)
                
                Log.d("AllFilesViewModel", "手动刷新完成，共 ${updated.size} 个文件")
            } catch (e: Exception) {
                Log.e("AllFilesViewModel", "手动刷新失败", e)
            } finally {
                _isLoading.value = false
            }
        }
    }

    // ── 筛选方法 ─────────────────────────────────────

    /**
     * 应用筛选条件（从筛选页确认后调用）
     * 
     * 功能说明：
     * 1. 设置当前筛选条件
     * 2. 设置筛选激活标志
     * 3. 重新应用筛选（过滤文件列表）
     * 
     * 调用位置：
     * - AllFilesViewModel.initialize() - 观察 pendingFilterResult 时调用
     * - FilterScreen.onConfirm() - 用户确认筛选条件后调用
     * 
     * @param savedFilter 筛选条件（包含 selectedTagKeys、filterLogic、includeNoTag）
     */
    fun applyFilter(savedFilter: SavedFilter) {
        viewModelScope.launch {
            _currentFilter.value = savedFilter
            _isFilterActive.value = savedFilter.selectedTagKeys.isNotEmpty() || savedFilter.includeNoTag
            applyCurrentFilter()
        }
    }

    /**
     * 清除筛选条件
     * 
     * 功能说明：
     * 1. 清除当前筛选条件（设为 null）
     * 2. 设置筛选未激活
     * 3. 恢复显示所有文件
     * 
     * 调用位置：
     * - AllFilesScreen.clearFilterButton.onClick() - 用户点击清除筛选按钮时调用
     */
    fun clearFilter() {
        _currentFilter.value = null
        _isFilterActive.value = false
        _pdfFiles.value = _allPdfFiles.value
    }

    /**
     * 重新应用当前筛选条件（用于文件列表变化时自动调用）
     * 
     * 功能说明：
     * 1. 如果没有筛选条件，显示所有文件
     * 2. 如果有筛选条件，调用 PdfRepository.getFileUrisByFilter() 进行交叉查询
     * 3. 只显示在匹配 URI 集合中的文件
     * 
     * 调用位置：
     * - AllFilesViewModel.initialize() - 初始化时调用
     * - AllFilesViewModel.incrementalScan() - 增量扫描后调用
     * - AllFilesViewModel.refresh() - 手动刷新后调用
     * - AllFilesViewModel.applyFilter() - 应用筛选后调用
     * 
     * 线程安全：
     * - 需要在协程中调用（getFileUrisByFilter 是 suspend 函数）
     */
    private suspend fun applyCurrentFilter() {
        val filter = _currentFilter.value
        if (filter == null) {
            _pdfFiles.value = _allPdfFiles.value  // 无筛选，显示所有
            return
        }
        // 从数据库交叉查询匹配的 URI 集合（使用 AppContainer 最新实例，避免切换后旧实例数据库已关闭）
        val matchedUris = AppContainer.pdfRepository.getFileUrisByFilter(filter)
        _pdfFiles.value = _allPdfFiles.value.filter { it.uri.toString() in matchedUris }
    }

    // ── 筛选逻辑（本地过滤，已废弃，改用 getFileUrisByFilter）─────────────────────────

    /**
     * 根据筛选条件过滤文件列表（本地过滤实现）
     * 
     * 功能说明：
     * 1. 如果 includeNoTag，只显示没有任何标签的文件
     * 2. 如果 filterLogic == AND，跨类别条件取交集
     * 3. 如果 filterLogic == OR，跨类别条件取并集
     * 4. 同类别内多标签始终为"或"
     * 
     * 注意：此方法已不再被调用，改用 PdfRepository.getFileUrisByFilter() 
     *       进行数据库层过滤。保留供调试和回退使用。
     * 
     * @param files 待筛选的文件列表
     * @param savedFilter 筛选条件
     * @return 筛选后的文件列表
     */
    private fun filterFiles(files: List<PdfFile>, savedFilter: SavedFilter): List<PdfFile> {
        if (savedFilter.includeNoTag) {
            return files.filter { it.tags.isEmpty() }
        }

        val selectedTagKeys = savedFilter.selectedTagKeys
        if (selectedTagKeys.isEmpty()) {
            return files
        }

        // 解析选中的标签：categoryId -> Set<tagValue>
        val selectedByCategory = mutableMapOf<String, MutableSet<String>>()
        for (key in selectedTagKeys) {
            val parts = key.split(":", limit = 2)
            if (parts.size == 2) {
                val categoryId = parts[0]
                val tagValue = parts[1]
                selectedByCategory.getOrPut(categoryId) { mutableSetOf() }.add(tagValue)
            }
        }

        if (selectedByCategory.isEmpty()) {
            return files
        }

        return if (savedFilter.filterLogic == FilterLogic.AND) {
            // AND：文件必须满足所有类别中的至少一个标签
            files.filter { pdfFile ->
                selectedByCategory.all { (categoryId, selectedValues) ->
                    pdfFile.tags.any { tag ->
                        tag.categoryId == categoryId && tag.value in selectedValues
                    }
                }
            }
        } else {
            // OR：文件满足任一类别中的至少一个标签
            files.filter { pdfFile ->
                selectedByCategory.any { (categoryId, selectedValues) ->
                    pdfFile.tags.any { tag ->
                        tag.categoryId == categoryId && tag.value in selectedValues
                    }
                }
            }
        }
    }

    // ── 多选分享方法 ─────────────────────────────────────

    /**
     * 切换多选分享模式
     * 
     * 功能说明：
     * 1. 反转多选模式状态
     * 2. 持久化到 PreferencesManager（全局控制）
     * 3. 关闭模式时自动清空选中状态（同步到 AppContainer）
     * 
     * 调用位置：
     * - AllFilesScreen.multiSelectButton.onClick() - 用户点击多选按钮时调用
     */
    fun toggleMultiSelectMode() {
        val newMode = !_isMultiSelectMode.value
        _isMultiSelectMode.value = newMode
        // 持久化到 PreferencesManager（全局控制）
        viewModelScope.launch {
            AppContainer.preferencesManager.setMultiSelectMode(newMode)
        }
        // 关闭时清空选中状态（同步到 AppContainer）
        if (!newMode) {
            clearSelection()
        }
    }

    /**
     * 切换某个文件的选中/取消选中
     * 
     * 功能说明：
     * 1. 如果文件已选中，从集合中移除
     * 2. 如果文件未选中，添加到集合
     * 3. 同步到 AppContainer（供 SettingsScreen 等跨页面访问）
     * 
     * 调用位置：
     * - AllFilesScreen.fileItem.onClick() - 用户点击文件项时调用（多选模式下）
     * 
     * @param fileId PDF 文件 ID
     */
    fun toggleFileSelection(fileId: String) {
        val current = _selectedFileIds.value
        val newSelection = if (current.contains(fileId)) {
            current - fileId
        } else {
            current + fileId
        }
        _selectedFileIds.value = newSelection
        // 同步到 AppContainer（供 SettingsScreen 等跨页面访问）
        AppContainer.selectedFileIds.value = newSelection
    }

    /**
     * 全选当前列表中的所有文件
     * 
     * 功能说明：
     * 1. 获取当前显示的（已筛选后的）文件列表的所有 ID
     * 2. 选中所有文件
     * 3. 同步到 AppContainer
     * 
     * 调用位置：
     * - AllFilesScreen.selectAllButton.onClick() - 用户点击全选按钮时调用
     */
    fun selectAll() {
        val allIds = _pdfFiles.value.map { it.id }.toSet()
        _selectedFileIds.value = allIds
        // 同步到 AppContainer
        AppContainer.selectedFileIds.value = allIds
    }

    /**
     * 清空所有选中
     * 
     * 功能说明：
     * 1. 清空选中的文件 ID 集合
     * 2. 同步到 AppContainer
     * 
     * 调用位置：
     * - AllFilesViewModel.toggleMultiSelectMode() - 关闭多选模式时调用
     * - AllFilesScreen.clearButton.onClick() - 用户点击清空按钮时调用
     */
    fun clearSelection() {
        _selectedFileIds.value = emptySet()
        // 同步到 AppContainer
        AppContainer.selectedFileIds.value = emptySet()
    }

    /**
     * 分享选中的文件到目标文件夹
     * 
     * 功能说明：
     * 1. 从 AppContainer.selectedFileIds 获取全局选中的文件 ID
     * 2. 从 PdfRepository 获取完整文件信息
     * 3. 调用 ShareRepository.copyMultipleFiles() 复制文件
     * 
     * 调用位置：
     * - SettingsScreen.shareButton.onClick() - 用户点击分享按钮时调用
     * 
     * @param targetFolderUri 目标文件夹的 SAF Uri
     * @return 复制成功的文件数量
     */
    suspend fun shareSelectedFiles(targetFolderUri: Uri): Int {
        // 使用 AppContainer.selectedFileIds（全局状态），而不是 _selectedFileIds
        val selectedIds = AppContainer.selectedFileIds.value
        if (selectedIds.isEmpty()) return 0

        // 从 pdfRepository 获取完整文件列表（确保数据最新）
        val allFiles = AppContainer.pdfRepository.pdfFiles.value
        val sourceUris = allFiles
            .filter { it.id in selectedIds }
            .map { it.uri }
        
        val copied = AppContainer.shareRepository.copyMultipleFiles(sourceUris, targetFolderUri)
        return copied
    }

    // ── ViewModel Factory（用于依赖注入）─────────────────────────────

    /**
     * AllFilesViewModel 的 Factory（用于 ViewModelProvider）
     * 
     * 功能说明：
     * 1. 从 AppContainer 获取 PdfRepository 和 PreferencesManager 实例
     * 2. 无需手动传递依赖
     * 
     * 使用示例：
     * ```kotlin
     * val viewModel = ViewModelProvider(this, AllFilesViewModel.Factory())[AllFilesViewModel::class.java]
     * ```
     */
    class Factory : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return AllFilesViewModel(
                pdfRepository = AppContainer.pdfRepository,
                preferencesManager = AppContainer.preferencesManager
            ) as T
        }
    }
}
