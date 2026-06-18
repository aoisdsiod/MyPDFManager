package com.example.pdfmanager.data.repository

import android.util.Log
import com.example.pdfmanager.data.local.PdfManagerDatabase
import com.example.pdfmanager.data.local.TagCategoryDao
import com.example.pdfmanager.data.local.CategoryTagDao
import com.example.pdfmanager.data.local.PdfTagDao
import com.example.pdfmanager.data.model.TagCategory
import com.example.pdfmanager.data.model.TagCategoryEntity
import com.example.pdfmanager.data.model.CategoryTagEntity
import com.example.pdfmanager.data.model.TagValue
import com.example.pdfmanager.data.model.PdfTagEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * 标签数据仓库（Room 版本）
 * 
 * 功能说明：
 * 1. 管理标签类别（CRUD：创建、读取、更新、删除）
 * 2. 管理标签值（添加、重命名、删除）
 * 3. 管理 PDF 与标签的关系（添加标签、移除标签、查询标签）
 * 4. 提供标签数据源（供筛选界面、详情页使用）
 * 5. 使用 Room 数据库存储（替代 categories.json）
 * 
 * 使用示例：
 * ```kotlin
 * // 在 ViewModel 中获取实例
 * val tagRepository = AppContainer.tagRepository
 * 
 * // 加载标签类别
 * viewModelScope.launch {
 *     tagRepository.loadCategories()
 * }
 * 
 * // 观察标签类别变化
 * tagRepository.categories.collect { categories ->
 *     // 更新 UI
 * }
 * ```
 * 
 * 数据流架构：
 * ```
 * Room 数据库（tag_categories、category_tags、pdf_tags 表）
 *     ↓
 * TagRepository._categories（内存缓存，StateFlow）
 *     ↓
 * UI 层（collectAsStateWithLifecycle）
 * ```
 * 
 * 依赖关系：
 * - 依赖：PdfManagerDatabase（Room 数据库）、TagCategoryDao、CategoryTagDao、PdfTagDao
 * - 被依赖：TagManagerViewModel、DetailViewModel、AllFilesViewModel、FilterViewModel
 * 
 * 线程安全：
 * - 所有数据库操作使用 Dispatchers.IO
 * - StateFlow 的更新使用 atomic 操作
 * 
 * @author PDF Manager Development Team
 * @version 2.0
 * @since 2024-01-01
 */
class TagRepository(
    private val database: PdfManagerDatabase
) {
    companion object {
        private const val TAG = "TagRepository"
    }
    
    private val tagCategoryDao: TagCategoryDao = database.tagCategoryDao()
    private val categoryTagDao: CategoryTagDao = database.categoryTagDao()
    private val pdfTagDao: PdfTagDao = database.pdfTagDao()
    
    // 标签类别列表（可观察）
    private val _categories = MutableStateFlow<List<TagCategory>>(emptyList())
    val categories: StateFlow<List<TagCategory>> = _categories.asStateFlow()
    
    /**
     * 加载标签类别（从 Room 数据库读取）
     * 
     * 功能说明：
     * 1. 从 tag_categories 表读取所有类别
     * 2. 对每个类别，从 category_tags 表读取标签值
     * 3. 转换为 TagCategory 对象列表
     * 4. 按 sortOrder 排序
     * 5. 更新内存缓存（_categories）
     * 
     * 调用位置：
     * - AllFilesViewModel.initialize() - 初始化时调用
     * - TagManagerViewModel.loadCategories() - 标签管理页加载时调用
     * 
     * 使用场景：
     * - 应用启动，需要加载标签数据
     * - 标签管理页打开，需要显示所有类别
     * 
     * @throws Exception 如果数据库读取失败，捕获并记录日志
     */
    suspend fun loadCategories() = withContext(Dispatchers.IO) {
        try {
            val categoryEntities = tagCategoryDao.getAll()
            val result = mutableListOf<TagCategory>()
            
            for (categoryEntity in categoryEntities) {
                val tagEntities = categoryTagDao.getByCategoryId(categoryEntity.id)
                val tagCategory = categoryEntity.toTagCategory(tagEntities)
                result.add(tagCategory)
            }
            
            val sorted = result.sortedBy { it.sortOrder }
            _categories.value = sorted
            Log.d(TAG, "加载了 ${result.size} 个标签类别")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load categories", e)
            _categories.value = emptyList()
        }
    }
    
    /**
     * 保存标签类别（写入 Room 数据库）
     * 
     * 功能说明：
     * 1. 遍历内存缓存中的所有类别
     * 2. 将 TagCategory 转换为 TagCategoryEntity 写入 tag_categories 表
     * 3. 先删除旧的 category_tags 记录，再插入新的（全量替换）
     * 4. 使用 Room 的 REPLACE 策略自动处理插入/更新
     * 
     * 调用位置：
     * - TagRepository.moveCategoryUp() - 上移类别后调用
     * - TagRepository.moveCategoryDown() - 下移类别后调用
     * - TagRepository.addTagValue() - 添加标签值后调用
     * - TagRepository.renameTagValue() - 重命名标签值后调用
     * - TagRepository.deleteTagValue() - 删除标签值后调用
     * - TagManagerViewModel.onSave() - 标签管理页保存时调用
     * 
     * 使用场景：
     * - 标签管理页保存修改
     * - 排序变化后自动保存
     * - 标签值变化后自动保存
     */
    suspend fun saveCategories() = withContext(Dispatchers.IO) {
        try {
            // Room 的 REPLACE 策略会自动处理插入/更新
            val categories = _categories.value
            for (category in categories) {
                val categoryEntity = TagCategoryEntity.fromTagCategory(category)
                tagCategoryDao.insert(categoryEntity)
                
                // 先删除旧的标签，再插入新的
                categoryTagDao.deleteByCategoryId(category.id)
                val tagEntities = category.tags.map { tagValue ->
                    CategoryTagEntity.fromTagValue(category.id, tagValue)
                }
                categoryTagDao.insertAll(tagEntities)
            }
            Log.d(TAG, "保存了 ${categories.size} 个标签类别")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save categories", e)
        }
    }
    
    // ── 标签类别 CRUD 方法 ─────────────────────────────────────
    
    /**
     * 添加标签类别
     * 
     * 功能说明：
     * 1. 检查名称是否已存在（不允许重名）
     * 2. 从调色板分配一个未使用的颜色（如果所有颜色已用完，循环取色）
     * 3. 计算新的 sortOrder（当前最大值 + 1）
     * 4. 写入 Room 数据库
     * 5. 更新内存缓存
     * 
     * 调用位置：
     * - TagManagerViewModel.addCategory() - 用户点击"添加类别"按钮时调用
     * - TagRepository.initDefaultCategories() - 首次使用时预置默认类别
     * 
     * 使用场景：
     * - 用户创建新的标签类别（如"科目"、"类型"）
     * - 首次启动时预置默认类别（"年份"、"月份"）
     * 
     * @param name 类别名称（不允许重名）
     * @return 新创建的 TagCategory（如果名称已存在则返回 null）
     */
    suspend fun addCategory(name: String): TagCategory? {
        // 检查名称是否已存在
        if (_categories.value.any { it.name == name }) {
            Log.w(TAG, "Category name already exists: $name")
            return null
        }
        
        // 从调色板分配一个未使用的颜色
        val usedColors = _categories.value.map { it.color }
        val availableColors = TagCategory.COLOR_PALETTE.filter { !usedColors.contains(it) }
        val color = if (availableColors.isNotEmpty()) {
            availableColors.first()
        } else {
            // 如果所有颜色都用完了，循环取色（可预测）
            TagCategory.COLOR_PALETTE[usedColors.size % TagCategory.COLOR_PALETTE.size]
        }
        
        // 计算新的 sortOrder（最大值 + 1）
        val maxSortOrder = _categories.value.maxOfOrNull { it.sortOrder } ?: -1
        val newSortOrder = maxSortOrder + 1
        
        val newCategory = TagCategory(
            name = name,
            color = color,
            sortOrder = newSortOrder
        )
        
        // 写入 Room
        val entity = TagCategoryEntity.fromTagCategory(newCategory)
        tagCategoryDao.insert(entity)
        
        // 更新内存
        val updatedList = _categories.value.toMutableList()
        updatedList.add(newCategory)
        _categories.value = updatedList
        
        Log.d(TAG, "添加类别: ${newCategory.name} (${newCategory.id})")
        return newCategory
    }
    
    /**
     * 初始化默认标签类别（如果还没有任何类别）
     * 
     * 功能说明：
     * 1. 检查当前是否有类别（_categories 是否为空）
     * 2. 如果为空，自动添加"年份"和"月份"两个默认类别
     * 3. 用于首次使用时预置常用类别
     * 
     * 调用位置：
     * - TagManagerViewModel.initialize() - 首次进入标签管理页时调用
     * - AppContainer.init() - 首次启动时自动调用
     * 
     * 使用场景：
     * - 用户首次使用标签功能（还没有任何类别）
     */
    suspend fun initDefaultCategories() {
        if (_categories.value.isEmpty()) {
            Log.d(TAG, "初始化默认标签类别")
            addCategory("年份")
            addCategory("月份")
        }
    }
    
    /**
     * 更新标签类别（名称和/或颜色）
     * 
     * 功能说明：
     * 1. 如果修改名称，检查新名称是否与其他类别冲突
     * 2. 使用直接的 UPDATE 查询（比 INSERT OR REPLACE 更可靠）
     * 3. 验证写入是否成功（读取后检查）
     * 4. 同步更新内存缓存
     * 
     * 调用位置：
     * - TagManagerViewModel.updateCategory() - 用户编辑类别后调用
     * 
     * 使用场景：
     * - 用户修改类别名称
     * - 用户修改类别颜色
     * 
     * @param categoryId 类别 ID
     * @param newName 新名称（可选，不传则不修改名称）
     * @param newColor 新颜色（可选，不传则不修改颜色）
     * @return 是否成功（名称冲突或类别不存在时返回 false）
     */
    suspend fun updateCategory(categoryId: String, newName: String? = null, newColor: Int? = null): Boolean = withContext(Dispatchers.IO) {
        // 检查新名称是否与其他类别冲突
        if (newName != null) {
            val allEntities = tagCategoryDao.getAll()
            val nameExists = allEntities.any { it.name == newName && it.id != categoryId }
            if (nameExists) {
                Log.w(TAG, "Category name already exists: $newName")
                return@withContext false
            }
        }
        
        // 使用直接的 UPDATE 查询（比 INSERT OR REPLACE 更可靠）
        if (newName != null) {
            tagCategoryDao.updateName(categoryId, newName)
            Log.d(TAG, "更新名称: $newName ($categoryId)")
        }
        
        if (newColor != null) {
            tagCategoryDao.updateColor(categoryId, newColor)
            Log.d(TAG, "更新颜色: $newColor ($categoryId)")
        }
        
        // 验证写入是否成功
        val updatedEntity = tagCategoryDao.getById(categoryId)
        if (updatedEntity == null) {
            Log.e(TAG, "更新失败: 类别不存在 $categoryId")
            return@withContext false
        }
        
        // 同步更新内存
        val updatedList = _categories.value.toMutableList()
        val index = updatedList.indexOfFirst { it.id == categoryId }
        if (index >= 0) {
            val tagEntities = categoryTagDao.getByCategoryId(categoryId)
            updatedList[index] = updatedEntity.toTagCategory(tagEntities)
            _categories.value = updatedList
        }
        
        Log.d(TAG, "更新类别成功: ${updatedEntity.name} (${updatedEntity.id})")
        return@withContext true
    }


    /**
     * 删除标签类别（级联删除 category_tags 和 pdf_tags）
     * 
     * 功能说明：
     * 1. 检查类别是否存在
     * 2. 从 Room 数据库删除（级联删除关联的 category_tags 和 pdf_tags）
     * 3. 更新内存缓存
     * 
     * 调用位置：
     * - TagManagerViewModel.deleteCategory() - 用户删除类别时调用
     * 
     * 使用场景：
     * - 用户删除某个标签类别（同时删除该类别的所有标签和关联）
     * 
     * @param categoryId 类别 ID
     * @return 是否成功（类别不存在时返回 false）
     */
    suspend fun deleteCategory(categoryId: String): Boolean {
        val updatedList = _categories.value.toMutableList()
        val index = updatedList.indexOfFirst { it.id == categoryId }
        
        if (index < 0) {
            Log.w(TAG, "Category not found: $categoryId")
            return false
        }
        
        // 写入 Room（级联删除 category_tags 和 pdf_tags）
        tagCategoryDao.deleteById(categoryId)
        
        updatedList.removeAt(index)
        _categories.value = updatedList
        
        Log.d(TAG, "删除类别: $categoryId")
        return true
    }
    
    // ── 排序（上移/下移）────────────────────────────
    
    /**
     * 上移标签类别一位（减小 sortOrder，使排序更靠前）
     * 
     * 功能说明：
     * 1. 查找当前类别在列表中的索引
     * 2. 检查是否可以上移（不能是第一个）
     * 3. 交换当前类别和上一个类别的 sortOrder
     * 4. 更新内存缓存
     * 5. 保存到 Room 数据库
     * 
     * 调用位置：
     * - TagManagerViewModel.moveUp() - 用户点击"上移"按钮时调用
     * 
     * 使用场景：
     * - 用户调整类别排序，将某个类别上移
     * 
     * @param categoryId 要上移的类别 ID
     * @return 是否成功（已是第一个时返回 false）
     */
    suspend fun moveCategoryUp(categoryId: String): Boolean {
        val list = _categories.value
        val index = list.indexOfFirst { it.id == categoryId }
        if (index <= 0) return false
        
        val current = list[index]
        val above = list[index - 1]
        
        // 交换 sortOrder
        val updatedList = list.toMutableList()
        updatedList[index] = current.copy(sortOrder = above.sortOrder)
        updatedList[index - 1] = above.copy(sortOrder = current.sortOrder)
        _categories.value = updatedList
        
        saveCategories()
        Log.d(TAG, "上移类别: ${current.name}")
        return true
    }
    
    /**
     * 下移标签类别一位（增大 sortOrder，使排序更靠后）
     * 
     * 功能说明：
     * 1. 查找当前类别在列表中的索引
     * 2. 检查是否可以下移（不能是最后一个）
     * 3. 交换当前类别和下一个类别的 sortOrder
     * 4. 更新内存缓存
     * 5. 保存到 Room 数据库
     * 
     * 调用位置：
     * - TagManagerViewModel.moveDown() - 用户点击"下移"按钮时调用
     * 
     * 使用场景：
     * - 用户调整类别排序，将某个类别下移
     * 
     * @param categoryId 要下移的类别 ID
     * @return 是否成功（已是最后一个时返回 false）
     */
    suspend fun moveCategoryDown(categoryId: String): Boolean {
        val list = _categories.value
        val index = list.indexOfFirst { it.id == categoryId }
        if (index < 0 || index >= list.size - 1) return false
        
        val current = list[index]
        val below = list[index + 1]
        
        // 交换 sortOrder
        val updatedList = list.toMutableList()
        updatedList[index] = current.copy(sortOrder = below.sortOrder)
        updatedList[index + 1] = below.copy(sortOrder = current.sortOrder)
        _categories.value = updatedList
        
        saveCategories()
        Log.d(TAG, "下移类别: ${current.name}")
        return true
    }
    
    /**
     * 获取标签类别 by ID
     * 
     * 功能说明：
     * 1. 从内存缓存中查找指定 ID 的类别
     * 2. 返回 TagCategory（如果未找到则返回 null）
     * 
     * 调用位置：
     * - TagRepository.addTagValue() - 检查类别是否存在
     * - TagRepository.renameTagValue() - 检查类别是否存在
     * - DetailViewModel.getCategoryName() - 获取类别名称显示
     * 
     * 使用场景：
     * - 需要获取特定类别的信息（如名称、颜色）
     * - 标签管理页编辑类别时
     * 
     * @param categoryId 类别 ID
     * @return TagCategory（如果未找到则返回 null）
     */
    fun getCategoryById(categoryId: String): TagCategory? {
        return _categories.value.find { it.id == categoryId }
    }
    
    /**
     * 获取所有标签类别（快照拷贝）
     * 
     * 功能说明：
     * 1. 返回内存缓存的不可变拷贝
     * 2. 避免外部修改影响内存状态
     * 
     * 调用位置：
     * - TagManagerViewModel.loadCategories() - 加载类别列表
     * - FilterViewModel.loadCategories() - 加载筛选界面的类别
     * 
     * 使用场景：
     * - 需要遍历所有类别（如筛选界面、标签管理页）
     * - 需要类别的快照（不随内存变化）
     * 
     * @return 标签类别列表（新 List 实例）
     */
    fun getCategories(): List<TagCategory> {
        return _categories.value.toList()
    }
    
    /**
     * 清除所有数据（用于切换库文件夹）
     * 
     * 功能说明：
     * 1. 清空内存缓存（_categories）
     * 2. 不清除 Room 数据库（数据库会随库文件夹切换而切换）
     * 
     * 调用位置：
     * - AppContainer.switchLibrary() - 切换库文件夹时调用
     * 
     * 使用场景：
     * - 用户更换库文件夹，需要清空内存中的标签数据
     */
    fun clear() {
        _categories.value = emptyList()
    }
    
    // ==================== 标签值管理方法 ====================
    
    /**
     * 添加标签值到指定类别
     * 
     * 功能说明：
     * 1. 检查类别是否存在
     * 2. 检查标签值是否已存在（不允许重复）
     * 3. 创建 TagValue 对象（含创建时间）
     * 4. 更新内存缓存
     * 5. 保存到 Room 数据库
     * 
     * 调用位置：
     * - TagManagerViewModel.addTagValue() - 用户点击"添加标签值"时调用
     * - DetailViewModel.onAddNewTag() - 用户在详情页添加新标签时调用
     * 
     * 使用场景：
     * - 用户创建新的标签值（如在"科目"类别下添加"语文"）
     * - 用户在详情页选择"添加新标签"时
     * 
     * @param categoryId 类别 ID（标签值所属类别）
     * @param tagValue 标签值（不允许重名）
     * @return 是否成功（类别不存在或标签值已存在时返回 false）
     */
    suspend fun addTagValue(categoryId: String, tagValue: String): Boolean {
        // 检查是否已存在
        val category = getCategoryById(categoryId)
        if (category == null) {
            Log.w(TAG, "Category not found: $categoryId")
            return false
        }
        
        if (category.tags.any { it.value == tagValue }) {
            Log.w(TAG, "Tag value already exists: $tagValue")
            return false
        }
        
        // 添加到内存
        val updatedList = _categories.value.toMutableList()
        val index = updatedList.indexOfFirst { it.id == categoryId }
        if (index < 0) return false
        
        val newTags = updatedList[index].tags.toMutableList()
        newTags.add(TagValue(value = tagValue, createdAt = System.currentTimeMillis()))
        updatedList[index] = updatedList[index].copy(tags = newTags)
        _categories.value = updatedList
        
        // 写入 Room
        saveCategories()
        
        Log.d(TAG, "添加标签值: $tagValue to category $categoryId")
        return true
    }
    
    /**
     * 重命名标签值（级联更新 pdf_tags 表）
     * 
     * 功能说明：
     * 1. 检查类别是否存在
     * 2. 检查新值是否与其他标签冲突
     * 3. 更新内存缓存
     * 4. 保存到 Room 数据库（更新 category_tags 表）
     * 5. 级联更新 pdf_tags 表中的 tag_value
     * 
     * 调用位置：
     * - TagManagerViewModel.renameTagValue() - 用户重命名标签值时调用
     * 
     * 使用场景：
     * - 用户修改某个标签值的名称（如将"数学"改为"数学分析"）
     * - 级联更新所有使用了该标签的 PDF
     * 
     * @param categoryId 类别 ID
     * @param oldValue 旧标签值
     * @param newValue 新标签值（不允许与同类别其他标签冲突）
     * @return 是否成功（类别不存在或新值冲突时返回 false）
     */
    suspend fun renameTagValue(categoryId: String, oldValue: String, newValue: String): Boolean {
        // 检查新值是否已存在
        val category = getCategoryById(categoryId)
        if (category == null) {
            Log.w(TAG, "Category not found: $categoryId")
            return false
        }
        
        if (category.tags.any { it.value == newValue && it.value != oldValue }) {
            Log.w(TAG, "New tag value already exists: $newValue")
            return false
        }
        
        // 更新内存
        val updatedList = _categories.value.toMutableList()
        val index = updatedList.indexOfFirst { it.id == categoryId }
        if (index < 0) return false
        
        val newTags = updatedList[index].tags.map { tagValue ->
            if (tagValue.value == oldValue) {
                tagValue.copy(value = newValue)
            } else {
                tagValue
            }
        }
        updatedList[index] = updatedList[index].copy(tags = newTags)
        _categories.value = updatedList
        
        // 写入 Room（级联更新 pdf_tags）
        saveCategories()
        // 级联更新 pdf_tags 中的 tag_value
        val pdfTagDao = database.pdfTagDao()
        pdfTagDao.updateTagValue(categoryId, oldValue, newValue)
        
        Log.d(TAG, "重命名标签值: $oldValue -> $newValue")
        return true
    }
    
    /**
     * 从类别中删除标签值（级联删除 pdf_tags 关联）
     * 
     * 功能说明：
     * 1. 检查类别是否存在
     * 2. 更新内存缓存（移除指定的标签值）
     * 3. 保存到 Room 数据库
     * 4. 同步删除 pdf_tags 表中与该标签相关的所有记录
     * 
     * 调用位置：
     * - TagManagerViewModel.deleteTagValue() - 用户删除标签值时调用
     * 
     * 使用场景：
     * - 用户删除某个标签值（如删除"过时"这个标签）
     * - 级联删除所有使用了该标签的 PDF 关联
     * 
     * @param categoryId 类别 ID
     * @param tagValue 要删除的标签值
     * @return 是否成功（类别不存在时返回 false）
     */
    suspend fun deleteTagValue(categoryId: String, tagValue: String): Boolean {
        // 更新内存
        val updatedList = _categories.value.toMutableList()
        val index = updatedList.indexOfFirst { it.id == categoryId }
        if (index < 0) return false
        
        val newTags = updatedList[index].tags.filterNot { it.value == tagValue }
        updatedList[index] = updatedList[index].copy(tags = newTags)
        _categories.value = updatedList
        
        // 写入 Room（级联删除 pdf_tags）
        saveCategories()


        // 同步删除 pdf_tags 表中的关联记录
        pdfTagDao.deleteByCategoryIdAndTagValue(categoryId, tagValue)
        
        Log.d(TAG, "删除标签值: $tagValue from category $categoryId")
        return true
    }
    
    /**
     * 获取按类别分组的标签（用于筛选界面）
     * 
     * 功能说明：
     * 1. 遍历所有类别
     * 2. 按 categoryId 分组，返回 Map<categoryId, List<TagValue>>
     * 
     * 调用位置：
     * - FilterViewModel.loadTags() - 加载筛选界面的标签列表
     * 
     * 使用场景：
     * - 筛选界面需要按类别显示所有标签
     * - 用户选择某个类别下的标签作为筛选条件
     * 
     * @return Map<categoryId, List<TagValue>> 按类别分组的标签
     */
    suspend fun getTagsByCategory(): Map<String, List<TagValue>> = withContext(Dispatchers.IO) {
        val result = mutableMapOf<String, List<TagValue>>()
        val categories = _categories.value
        
        for (category in categories) {
            result[category.id] = category.tags
        }
        
        return@withContext result
    }
    
    // ==================== PDF 标签关系操作方法 ====================
    
    /**
     * 给 PDF 添加标签（在 pdf_tags 表中创建关联记录）
     * 
     * 功能说明：
     * 1. 检查 PDF 是否已有该标签（避免重复）
     * 2. 如果不存在，创建 PdfTagEntity 并插入 pdf_tags 表
     * 3. 冗余存储 categoryName 和 tagColor（方便查询，避免联表）
     * 
     * 调用位置：
     * - DetailViewModel.addTag() - 用户在详情页为 PDF 添加标签时调用
     * - AllFilesViewModel.batchTagFiles() - 用户批量添加标签时调用
     * 
     * 使用场景：
     * - 用户为 PDF 文件添加标签（如为某篇论文添加"必读"标签）
     * - 用户批量选中多个 PDF 后添加相同标签
     * 
     * @param pdfFileUri PDF 文件 URI（用于关联）
     * @param categoryId 标签类别 ID
     * @param tagValue 标签值
     * @param categoryName 类别名称（冗余存储，方便查询）
     * @param tagColor 标签颜色（冗余存储，方便显示）
     */
    suspend fun addTagToPdf(
        pdfFileUri: String,
        categoryId: String,
        tagValue: String,
        categoryName: String,
        tagColor: Int
    ) = withContext(Dispatchers.IO) {
        val existing = pdfTagDao.getByPdfFileUri(pdfFileUri)
        val alreadyExists = existing.any { it.categoryId == categoryId && it.tagValue == tagValue }
        
        if (!alreadyExists) {
            val entity = PdfTagEntity(
                pdfFileUri = pdfFileUri,
                categoryId = categoryId,
                tagValue = tagValue,
                categoryName = categoryName,
                tagColor = tagColor
            )
            pdfTagDao.insert(entity)
            Log.d(TAG, "addTagToPdf: 成功添加标签 $tagValue to $pdfFileUri")
        } else {
            Log.d(TAG, "addTagToPdf: 标签已存在 $tagValue")
        }
    }
    
    /**
     * 从 PDF 移除标签（删除 pdf_tags 表中的关联记录）
     * 
     * 功能说明：
     * 1. 根据 pdfFileUri + categoryId + tagValue 唯一确定要删除的记录
     * 2. 从 pdf_tags 表删除指定的关联记录
     * 
     * 调用位置：
     * - DetailViewModel.removeTag() - 用户在详情页移除标签时调用
     * - AllFilesViewModel.batchUntagFiles() - 用户批量移除标签时调用
     * 
     * 使用场景：
     * - 用户从 PDF 文件中移除某个标签
     * 
     * @param pdfFileUri PDF 文件 URI
     * @param categoryId 标签类别 ID
     * @param tagValue 标签值
     */
    suspend fun removeTagFromPdf(
        pdfFileUri: String,
        categoryId: String,
        tagValue: String
    ) = withContext(Dispatchers.IO) {
        pdfTagDao.deleteByPdfFileUriAndTag(pdfFileUri, categoryId, tagValue)
        Log.d(TAG, "removeTagFromPdf: 成功移除标签 $tagValue from $pdfFileUri")
    }
    
    /**
     * 获取 PDF 文件的所有标签（从 pdf_tags 表查询）
     * 
     * 功能说明：
     * 1. 根据 pdfFileUri 查询 pdf_tags 表
     * 2. 返回所有关联的 PdfTagEntity（含 categoryName、tagColor 等冗余信息）
     * 
     * 调用位置：
     * - DetailViewModel.loadTags() - 加载 PDF 文件的标签列表
     * - PdfRepository.getFileById() - 获取 PDF 文件完整信息时查询标签
     * 
     * 使用场景：
     * - 详情页显示 PDF 文件的所有标签
     * - 搜索时根据标签筛选 PDF 文件
     * 
     * @param pdfFileUri PDF 文件 URI
     * @return 该 PDF 的所有标签列表
     */
    suspend fun getTagsForPdf(pdfFileUri: String): List<PdfTagEntity> {
        return pdfTagDao.getByPdfFileUri(pdfFileUri)
    }
    
    /**
     * 获取所有 PDF 标签关系（全表查询）
     * 
     * 功能说明：
     * 1. 查询 pdf_tags 表的所有记录
     * 2. 返回 List<PdfTagEntity>
     * 
     * 调用位置：
     * - PdfRepository.restoreFromRoom() - 从数据库恢复时重建标签索引
     * - TagRepository.updateCategoryTagValues() - 获取旧值列表
     * 
     * 使用场景：
     * - 需要重建标签索引
     * - 需要统计或导出所有标签关系
     * 
     * @return 所有 PdfTagEntity 列表
     */
    suspend fun getAllPdfTags(): List<PdfTagEntity> {
        return pdfTagDao.getAll()
    }
    
    /**
     * 更新类别下的所有标签值（同步更新 pdf_tags 表）
     * 
     * 功能说明：
     * 1. 对比旧值列表和新值列表
     * 2. 删除那些不在新列表中的旧标签值关联
     * 3. 用于标签管理页编辑标签值列表时，同步清理 pdf_tags 中的孤立关联
     * 
     * 调用位置：
     * - TagManagerViewModel.onSaveCategory() - 标签管理页保存类别修改时调用
     * 
     * 使用场景：
     * - 用户编辑类别下的标签值列表
     * - 删除某个标签值后，同步删除所有 PDF 与该标签的关联
     * 
     * @param categoryId 类别 ID
     * @param oldValues 旧的标签值列表（用于对比哪些值被删除了）
     * @param newValues 新的标签值列表（保留这些值的关联）
     */
    suspend fun updateCategoryTagValues(
        categoryId: String,
        oldValues: List<String>,
        newValues: List<String>
    ) = withContext(Dispatchers.IO) {
        Log.d(TAG, "updateCategoryTagValues: categoryId=$categoryId")
        
        // 删除旧标签值的关系（只删除那些不在新列表中的）
        oldValues.forEach { oldValue ->
            if (!newValues.contains(oldValue)) {
                pdfTagDao.deleteByCategoryIdAndTagValue(categoryId, oldValue)
                Log.d(TAG, "updateCategoryTagValues: 删除旧标签值 $oldValue")
            }
        }
        
        Log.d(TAG, "updateCategoryTagValues: 完成")
    }
}
