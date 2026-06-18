package com.example.pdfmanager.ui.screen.tagmanagement

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.pdfmanager.data.model.TagCategory
import com.example.pdfmanager.data.model.TagValue
import com.example.pdfmanager.data.repository.AppContainer
import com.example.pdfmanager.data.repository.PdfRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 标签管理页面 ViewModel
 *
 * 功能说明：
 * 本 ViewModel 负责管理标签系统的完整业务逻辑，包括：
 * 1. 标签类别的加载、添加、重命名、修改颜色、删除、排序
 * 2. 标签值的添加、重命名、删除
 * 3. 错误消息的展示与清除
 *
 * 数据来源：通过 AppContainer 注入的 tagRepository（Room 数据库）
 * 数据联动：类别和标签值的修改会自动通过 Room 的级联机制同步到 pdf_tags 表
 *
 * 调用位置：
 * - 仅在 TagManagementScreen 中通过 viewModel() 构造，作为该页面的状态管理器
 *
 * @property tagRepository 标签仓库，负责标签类别和标签值的数据库操作
 * @property pdfRepository PDF 仓库，提供 PDF 文件的数据库操作（当前主要用于扩展）
 */
class TagManagementViewModel : ViewModel() {
    private val tagRepository = AppContainer.tagRepository
    private val pdfRepository = AppContainer.pdfRepository

    /**
     * 标签类别列表（按 sortOrder 排序，越小越靠前）
     * 用于 UI 层渲染 CategoryCard 列表
     */
    private val _categories = MutableStateFlow<List<TagCategory>>(emptyList())
    val categories: StateFlow<List<TagCategory>> = _categories.asStateFlow()

    /**
     * 错误消息流
     * 当操作遇到异常或业务规则冲突时，设置此值，UI 层弹出错误对话框
     */
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    /**
     * 初始化：从 Room 数据库加载所有标签类别
     */
    init {
        loadCategories()
    }

    /**
     * 加载所有标签类别
     *
     * 功能说明：
     * 从 tagRepository 获取所有类别，按 sortOrder 升序排列后更新 _categories 流
     * 该函数在初始化时调用一次，每次增删改操作完成后也都调用以刷新 UI
     *
     * 调用位置：
     * - 构造函数 init 块中首次调用
     * - addCategory()、changeColor()、renameCategory() 等所有业务方法中操作成功后调用
     */
    fun loadCategories() {
        viewModelScope.launch {
            try {
                tagRepository.loadCategories()
                // 按 sortOrder 排序（越小越靠前）
                val sortedCategories = tagRepository.getCategories().sortedBy { it.sortOrder }
                _categories.value = sortedCategories
            } catch (e: Exception) {
                _errorMessage.value = "加载标签类别失败: ${e.message}"
            }
        }
    }

    /**
     * 添加新的标签类别
     *
     * 功能说明：
     * 向数据库添加新类别，名称不能重复。
     * 如果名称已存在，tagRepository.addCategory() 返回 null，显示错误消息。
     * 添加成功后立即更新颜色，然后刷新类别列表。
     *
     * 调用位置：
     * - TagManagementScreen 的"新建类别"对话框确认时调用
     *
     * @param name 新类别的名称（不能为空，不能与已有类别重名）
     * @param color 新类别的颜色值（ARGB 格式 Int）
     */
    fun addCategory(name: String, color: Int) {
        viewModelScope.launch {
            try {
                val newCategory = tagRepository.addCategory(name)
                if (newCategory == null) {
                    _errorMessage.value = "类别名称已存在"
                } else {
                    // 更新颜色
                    tagRepository.updateCategory(newCategory.id, newColor = color)
                    loadCategories() // 重新加载
                }
            } catch (e: Exception) {
                _errorMessage.value = "添加类别失败: ${e.message}"
            }
        }
    }

    /**
     * 修改类别颜色
     *
     * 功能说明：
     * 更新指定类别的颜色属性。
     * Room 会自动级联更新 pdf_tags 表中相关记录的 categoryColor 字段。
     *
     * 调用位置：
     * - TagManagementScreen 的"颜色选择器"对话框中选中新颜色时调用
     *
     * @param categoryId 要修改颜色的类别 ID
     * @param newColor 新的颜色值（ARGB 格式 Int）
     */
    fun changeColor(categoryId: String, newColor: Int) {
        viewModelScope.launch {
            try {
                tagRepository.updateCategory(categoryId, newColor = newColor)
                loadCategories() // 重新加载
                // Room 中的 pdf_tags 表会通过 TagRepository 的级联更新自动处理
            } catch (e: Exception) {
                _errorMessage.value = "修改颜色失败: ${e.message}"
            }
        }
    }

    /**
     * 修改类别名称
     *
     * 功能说明：
     * 更新指定类别的名称属性，新名称不能与已有类别重复。
     * 如果重名则 tagRepository.updateCategory() 返回 false，显示错误消息。
     * Room 会自动级联更新 pdf_tags 表中相关记录的 categoryName 字段。
     *
     * 调用位置：
     * - TagManagementScreen 的"修改类别名称"对话框确认时调用
     *
     * @param categoryId 要修改名称的类别 ID
     * @param newName 新的类别名称（不能为空，不能与已有类别重名）
     */
    fun renameCategory(categoryId: String, newName: String) {
        viewModelScope.launch {
            try {
                val success = tagRepository.updateCategory(categoryId, newName = newName)
                if (!success) {
                    _errorMessage.value = "类别名称已存在"
                    return@launch
                }
                loadCategories() // 重新加载
                // Room 中的 pdf_tags 表会通过 TagRepository 的级联更新自动处理
            } catch (e: Exception) {
                _errorMessage.value = "修改名称失败: ${e.message}"
            }
        }
    }

    /**
     * 删除标签类别
     *
     * 功能说明：
     * 删除指定类别及其所有标签值。
     * Room 中的 pdf_tags 表会通过 CASCADE 级联删除自动清理与该类别相关的所有记录。
     *
     * 调用位置：
     * - TagManagementScreen 的"删除类别确认"对话框确认时调用
     *
     * @param categoryId 要删除的类别 ID
     */
    fun deleteCategory(categoryId: String) {
        viewModelScope.launch {
            try {
                tagRepository.deleteCategory(categoryId)
                loadCategories() // 重新加载
                // Room 中的 pdf_tags 表会通过 CASCADE 自动删除相关记录
            } catch (e: Exception) {
                _errorMessage.value = "删除类别失败: ${e.message}"
            }
        }
    }

    /**
     * 上移类别（减小 sortOrder）
     *
     * 功能说明：
     * 提高指定类别在列表中的排序优先级。
     * 通过 tagRepository.moveCategoryUp() 交换当前类别与前一个类别的 sortOrder 值。
     *
     * 调用位置：
     * - TagManagementScreen 中 CategoryCard 的上移按钮被点击时调用
     *
     * @param categoryId 要上移的类别 ID
     */
    fun moveCategoryUp(categoryId: String) {
        viewModelScope.launch {
            try {
                tagRepository.moveCategoryUp(categoryId)
                loadCategories()
            } catch (e: Exception) {
                _errorMessage.value = "上移失败: ${e.message}"
            }
        }
    }

    /**
     * 下移类别（增大 sortOrder）
     *
     * 功能说明：
     * 降低指定类别在列表中的排序优先级。
     * 通过 tagRepository.moveCategoryDown() 交换当前类别与后一个类别的 sortOrder 值。
     *
     * 调用位置：
     * - TagManagementScreen 中 CategoryCard 的下移按钮被点击时调用
     *
     * @param categoryId 要下移的类别 ID
     */
    fun moveCategoryDown(categoryId: String) {
        viewModelScope.launch {
            try {
                tagRepository.moveCategoryDown(categoryId)
                loadCategories()
            } catch (e: Exception) {
                _errorMessage.value = "下移失败: ${e.message}"
            }
        }
    }

    /**
     * 添加标签值到指定类别
     *
     * 功能说明：
     * 为指定类别添加一个新的标签值。
     * 执行前先验证：类别必须存在、标签值不能与已有值重复。
     * 通过 tagRepository.addTagValue() 写入 category_tags 表。
     *
     * 调用位置：
     * - TagManagementScreen 的"添加标签值"对话框确认时调用
     *
     * @param categoryId 要添加标签值的目标类别 ID
     * @param tagValue 新的标签值文本（不能为空，不能与同类别下已有值重名）
     */
    fun addTagValue(categoryId: String, tagValue: String) {
        viewModelScope.launch {
            try {
                val category = tagRepository.getCategoryById(categoryId)
                if (category == null) {
                    _errorMessage.value = "类别不存在"
                    return@launch
                }

                // 检查标签值是否已存在
                if (category.tags.any { it.value == tagValue }) {
                    _errorMessage.value = "标签值已存在"
                    return@launch
                }

                // 添加到 Room（TagRepository.addTagValue() 会写入 category_tags 表）
                val success = tagRepository.addTagValue(categoryId, tagValue)
                if (success) {
                    loadCategories() // 重新加载
                } else {
                    _errorMessage.value = "添加标签值失败"
                }
            } catch (e: Exception) {
                _errorMessage.value = "添加标签值失败: ${e.message}"
            }
        }
    }

    /**
     * 从指定类别删除标签值
     *
     * 功能说明：
     * 从 category_tags 表中删除指定标签值记录。
     * Room 会自动从 pdf_tags 表删除所有关联此标签值的记录（级联删除）。
     *
     * 调用位置：
     * - TagManagementScreen 的"删除标签值确认"对话框确认时调用
     *
     * @param categoryId 标签值所属的类别 ID
     * @param tagValue 要删除的标签值文本
     */
    fun deleteTagValue(categoryId: String, tagValue: String) {
        viewModelScope.launch {
            try {
                // 从类别中删除标签值（会更新 category_tags 表）
                val success = tagRepository.deleteTagValue(categoryId, tagValue)
                if (success) {
                    loadCategories() // 重新加载
                    // Room 中的 pdf_tags 表会通过 TagRepository.updateCategoryTagValues() 自动处理
                } else {
                    _errorMessage.value = "删除标签值失败"
                }
            } catch (e: Exception) {
                _errorMessage.value = "删除标签值失败: ${e.message}"
            }
        }
    }

    /**
     * 修改标签值（重命名）
     *
     * 功能说明：
     * 将指定类别下的旧标签值修改为新标签值。
     * 执行前先验证：新值不能为空、不能与同类别下其他值重复。
     * 通过 tagRepository.renameTagValue() 同时更新 category_tags 表和 pdf_tags 表。
     *
     * 调用位置：
     * - TagManagementScreen 的"修改标签值"对话框确认时调用
     *
     * @param categoryId 标签值所属的类别 ID
     * @param oldValue 当前标签值文本（要修改的旧值）
     * @param newValue 新标签值文本（不能为空，不能与已有值重名）
     */
    fun renameTagValue(categoryId: String, oldValue: String, newValue: String) {
        viewModelScope.launch {
            try {
                if (newValue.isBlank()) {
                    _errorMessage.value = "标签值不能为空"
                    return@launch
                }

                val category = tagRepository.getCategoryById(categoryId)
                if (category == null) {
                    _errorMessage.value = "类别不存在"
                    return@launch
                }

                // 检查新值是否已存在
                if (category.tags.any { it.value == newValue && it.value != oldValue }) {
                    _errorMessage.value = "标签值已存在"
                    return@launch
                }

                // 重命名标签值（会更新 category_tags 表和 pdf_tags 表）
                val success = tagRepository.renameTagValue(categoryId, oldValue, newValue)
                if (success) {
                    loadCategories() // 重新加载
                } else {
                    _errorMessage.value = "修改标签值失败"
                }
            } catch (e: Exception) {
                _errorMessage.value = "修改标签值失败: ${e.message}"
            }
        }
    }

    /**
     * 获取按类别分组的标签值
     *
     * 功能说明：
     * 将当前 _categories 列表中的每个类别映射为 (categoryId → tags) 的 Map 结构。
     * 在后台线程（Dispatchers.Default）执行避免阻塞主线程。
     *
     * 调用位置：
     * - TagManagementScreen 的 LaunchedEffect(categories) 中调用，用于渲染标签值列表
     *
     * @return Map<String, List<TagValue>> 键为类别 ID，值为该类别下的标签值列表
     */
    suspend fun getTagsByCategory(): Map<String, List<TagValue>> = withContext(Dispatchers.Default) {
        val categoriesList = _categories.value
        return@withContext categoriesList.associate { it.id to it.tags }
    }

    /**
     * 清除错误消息
     *
     * 功能说明：
     * 将 _errorMessage 重置为 null，UI 层据此关闭错误对话框。
     *
     * 调用位置：
     * - TagManagementScreen 的错误对话框的"确定"按钮和 onDismissRequest 中调用
     */
    fun clearErrorMessage() {
        _errorMessage.value = null
    }
}
