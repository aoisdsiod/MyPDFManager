package com.example.pdfmanager.ui.screen.filter

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.pdfmanager.data.model.FilterLogic
import com.example.pdfmanager.data.model.SavedFilter
import com.example.pdfmanager.data.model.TagCategory
import com.example.pdfmanager.data.repository.AppContainer
import com.example.pdfmanager.data.repository.TagRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 筛选页 ViewModel
 *
 * 功能说明：
 * 本 ViewModel 负责管理标签筛选功能的全部业务状态和逻辑，包括：
 * 1. 加载所有标签类别（通过 TagRepository）
 * 2. 管理标签的选中状态（selectedTagKeys）
 * 3. 管理跨类别的筛选逻辑（AND/OR 切换）
 * 4. 管理"无标签"特殊类别（与普通标签互斥）
 * 5. 提供类别三态判断（全未选/部分选/全选）
 * 6. 生成 SavedFilter 供外部应用过滤
 * 7. 支持恢复上次筛选状态
 *
 * 调用位置：
 * - 仅在 FilterScreen 中通过 viewModel(factory = FilterViewModel.Factory()) 构造
 * - 作为 FilterScreen 唯一的 ViewModel 使用
 *
 * @property tagRepository 标签仓库，提供类别数据的加载和访问
 */
class FilterViewModel(
    private val tagRepository: TagRepository = AppContainer.tagRepository
) : ViewModel() {

    // ── 所有标签类别列表（从 Room 数据库加载） ──
    private val _categories = MutableStateFlow<List<TagCategory>>(emptyList())
    val categories: StateFlow<List<TagCategory>> = _categories.asStateFlow()

    // ── 选中的标签键集合 ──
    /**
     * 格式："categoryId:value"
     * 例如："cat_uuid:技术文档" 表示类别 cat_uuid 下的"技术文档"标签被选中
     * 使用 Set 保证每个标签唯一选中
     */
    private val _selectedTagKeys = MutableStateFlow<Set<String>>(emptySet())
    val selectedTagKeys: StateFlow<Set<String>> = _selectedTagKeys.asStateFlow()

    // ── 跨类别筛选逻辑 ──
    /**
     * FilterLogic.AND：选中的多个标签必须全部匹配（交集）
     * FilterLogic.OR：选中的多个标签任意匹配即可（并集）
     * 每次打开筛选页默认 AND，不持久化
     */
    private val _filterLogic = MutableStateFlow(FilterLogic.AND)
    val filterLogic: StateFlow<FilterLogic> = _filterLogic.asStateFlow()

    // ── 是否仅显示无标签文件 ──
    /**
     * 选中时自动清空所有普通标签的选中状态
     * 取消时不影响其他标签
     */
    private val _includeNoTag = MutableStateFlow(false)
    val includeNoTag: StateFlow<Boolean> = _includeNoTag.asStateFlow()

    /**
     * 初始化：从 tagRepository 加载所有标签类别数据
     * 使用 collect 持续监听 Room 数据库的变化，实现数据自动刷新
     */
    init {
        loadCategories()
    }

    /**
     * 加载所有标签类别
     *
     * 功能说明：
     * 从 tagRepository 加载标签类别数据，并持续监听 Room 数据库的变化。
     * 当数据库中的类别发生变化时（如其他页面修改了标签），
     * collect 会重新发射新数据，自动刷新 UI。
     *
     * 调用位置：
     * - 构造函数 init 块中首次调用
     */
    private fun loadCategories() {
        viewModelScope.launch {
            tagRepository.loadCategories()
            tagRepository.categories.collect { cats ->
                _categories.value = cats
            }
        }
    }

    /**
     * 切换单个标签的选中状态
     *
     * 功能说明：
     * 如果标签已被选中则取消选中，否则加入选中集合。
     * 无论是选中还是取消选中普通标签，都自动关闭"无标签"选项。
     *
     * 调用位置：
     * - FilterScreen 中 FilterCategoryRow 的 onTagClick 回调触发
     *
     * @param tagKey 标签键，格式为 "categoryId:value"，例如 "cat1:技术文档"
     */
    fun toggleTag(tagKey: String) {
        val current = _selectedTagKeys.value.toMutableSet()
        if (current.contains(tagKey)) {
            current.remove(tagKey)
            // 取消选中标签时，关闭"无标签"
            _includeNoTag.value = false
        } else {
            current.add(tagKey)
            // 选中普通标签时，关闭"无标签"
            _includeNoTag.value = false
        }
        _selectedTagKeys.value = current
    }

    /**
     * 切换某类别下所有标签的选中状态
     *
     * 功能说明：
     * 点击类别名/圆框时触发，在该类别的全部标签上执行三态切换：
     * ○（全未选）→ ✓（全选）
     * ●（部分选）→ ✓（全选）
     * ✓（全选）→ ○（全不选）
     *
     * 调用位置：
     * - FilterScreen 中 FilterCategoryRow 的 onCategoryClick 回调触发
     *
     * @param category 被点击的标签类别对象，使用其 id 和 tags 属性计算
     */
    fun toggleCategory(category: TagCategory) {
        val allTagKeys = category.tags.map { "${category.id}:${it.value}" }.toSet()
        val selected = _selectedTagKeys.value
        val currentlySelectedInCategory = selected.intersect(allTagKeys)

        val newSelected = selected.toMutableSet()
        if (currentlySelectedInCategory.size == allTagKeys.size) {
            // 全选 → 全不选
            newSelected.removeAll(allTagKeys)
        } else {
            // 全未选或部分选 → 全选
            newSelected.addAll(allTagKeys)
        }
        _selectedTagKeys.value = newSelected
        _includeNoTag.value = false
    }

    /**
     * 获取某类别当前选中状态
     *
     * 功能说明：
     * 判断指定类别下有多少标签被选中，返回对应的三态值。
     * 用于 FilterCategoryRow 渲染三态圆框的显示样式。
     *
     * 调用位置：
     * - FilterScreen 的 LazyColumn 中，为每个 category 计算 categoryState 参数
     *
     * @param category 要查询的标签类别
     * @return Int 0 = ○（全未选），1 = ●（部分选），2 = ✓（全选）
     */
    fun getCategoryState(category: TagCategory): Int {
        val allTagKeys = category.tags.map { "${category.id}:${it.value}" }.toSet()
        if (allTagKeys.isEmpty()) return 0
        val selected = _selectedTagKeys.value
        val currentlySelectedInCategory = selected.intersect(allTagKeys)
        return when (currentlySelectedInCategory.size) {
            0 -> 0  // ○
            allTagKeys.size -> 2  // ✓
            else -> 1  // ●
        }
    }

    /**
     * 切换且/或筛选逻辑
     *
     * 功能说明：
     * 在 AND 和 OR 之间切换。切换后 UI 顶部的按钮文字随之变化：
     * AND → 显示"[且]"，OR → 显示"[或]"
     *
     * 调用位置：
     * - FilterScreen 顶部栏中的"且/或"切换按钮的 onClick 回调
     */
    fun toggleFilterLogic() {
        _filterLogic.value = if (_filterLogic.value == FilterLogic.AND) FilterLogic.OR else FilterLogic.AND
    }

    /**
     * 切换"无标签"特殊类别
     *
     * 功能说明：
     * 切换"无标签"筛选选项的选中状态。
     * 选中时：自动清空所有普通标签的选中状态（_selectedTagKeys = emptySet()）
     * 取消时：仅取消自身，不影响其他普通标签的状态
     * 互斥逻辑：不能同时选中"无标签"和任何普通标签
     *
     * 调用位置：
     * - FilterScreen 中 NoTagRow 的 onClick 回调
     */
    fun toggleNoTag() {
        if (_includeNoTag.value) {
            _includeNoTag.value = false
        } else {
            _includeNoTag.value = true
            _selectedTagKeys.value = emptySet()
        }
    }

    /**
     * 重置所有筛选条件
     *
     * 功能说明：
     * 将所有筛选状态恢复为默认值：
     * - 清空选中的标签键集合
     * - 筛选逻辑恢复为 AND
     * - 关闭"无标签"选项
     *
     * 调用位置：
     * - FilterScreen 底部栏的"重置所有"按钮的 onClick 回调
     */
    fun resetAll() {
        _selectedTagKeys.value = emptySet()
        _filterLogic.value = FilterLogic.AND
        _includeNoTag.value = false
    }

    /**
     * 确认筛选并生成 SavedFilter
     *
     * 功能说明：
     * 将当前所有筛选条件（选中标签、筛选逻辑、无标签）打包为 SavedFilter 数据对象，
     * 供 AllFilesScreen 在应用过滤时使用。
     *
     * 调用位置：
     * - FilterScreen 底部栏的"确认筛选"按钮的 onClick 回调中调用
     *
     * @return SavedFilter 包含选中标签键列表、筛选逻辑、无标签标记的数据对象
     */
    fun confirmFilter(): SavedFilter {
        return SavedFilter(
            selectedTagKeys = _selectedTagKeys.value.toList(),
            filterLogic = _filterLogic.value,
            includeNoTag = _includeNoTag.value
        )
    }

    /**
     * 恢复筛选状态
     *
     * 功能说明：
     * 从保存的 SavedFilter 中恢复所有筛选条件。
     * 用于重新打开筛选页时，恢复用户上次的选择。
     *
     * 调用位置：
     * - 外部（如 AllFilesScreen）在导航到 FilterScreen 之前，
     *   调用此方法将上次的筛选结果恢复到 ViewModel 中
     *
     * @param savedFilter 之前保存的筛选条件对象，若为 null 则不执行任何操作
     */
    fun restoreFilter(savedFilter: SavedFilter?) {
        if (savedFilter == null) return
        _selectedTagKeys.value = savedFilter.selectedTagKeys.toSet()
        _filterLogic.value = savedFilter.filterLogic
        _includeNoTag.value = savedFilter.includeNoTag
    }

    /**
     * FilterViewModel 的工厂类
     *
     * 功能说明：
     * 提供自定义的 ViewModel 创建方式，允许手动注入 tagRepository 依赖。
     * 使用 AppContainer.tagRepository 单例创建 FilterViewModel 实例。
     *
     * 调用位置：
     * - FilterScreen 中 viewModel(factory = FilterViewModel.Factory()) 调用
     */
    class Factory : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return FilterViewModel(AppContainer.tagRepository) as T
        }
    }
}
