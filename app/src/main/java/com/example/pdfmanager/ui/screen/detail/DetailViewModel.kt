package com.example.pdfmanager.ui.screen.detail

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.pdfmanager.data.local.FileScanner
import com.example.pdfmanager.data.local.PdfManagerDatabase
import com.example.pdfmanager.data.local.PreferencesManager
import com.example.pdfmanager.data.model.PdfFile
import com.example.pdfmanager.data.model.TagCategory
import com.example.pdfmanager.data.model.PdfTagEntity
import com.example.pdfmanager.data.repository.AppContainer
import com.example.pdfmanager.data.repository.PdfRepository
import com.example.pdfmanager.data.repository.ShareRepository
import com.example.pdfmanager.data.repository.TagRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import com.example.pdfmanager.data.model.Tag


/**
 * 详情页面 ViewModel
 *
 * 功能说明：
 *   负责管理单个 PDF 文件的详情页面数据，包括：
 *   - 从 Room 数据库加载 PDF 文件实体并转换为 UI 层数据模型 [PdfFile]
 *   - 管理标签的增删改操作（通过 [TagRepository] 持久化到 Room）
 *   - 管理笔记/备注的编辑保存（通过 [PdfRepository] 和 [PdfFileDao] 双重写入）
 *   - 管理最后阅读页码的保存（通过 [PdfRepository] 和 [PdfFileDao] 双重写入）
 *   - 提供文件分享功能（复制文件到 Share 目录或指定目标路径）
 *
 * 调用位置：
 *   由 [com.example.pdfmanager.ui.screen.detail.DetailScreen] 中的 ViewModelProvider 创建并持有，
 *   通过 DetailViewModel.Factory(fileId) 工厂模式实例化。
 *
 * 使用场景：
 *   用户进入某个 PDF 文件的详情页面时，该 ViewModel 被创建，用于加载文件元数据、
 *   展示标签、管理笔记、以及提供分享操作入口。
 *
 * 数据流说明：
 *   ViewModel 内部通过 MutableStateFlow 暴露 UI 状态（_pdfFile → pdfFile），
 *   UI 层通过 collectAsState() 收集这些 StateFlow 以驱动界面刷新。
 *   所有的写操作（添加标签、保存笔记、保存页码）均在 viewModelScope.launch 中
 *   以协程方式异步执行，通过 Repository → Room DAO → SQLite 的链路持久化。
 *
 * @property fileId         当前 PDF 文件的唯一标识（主键），在构造时传入
 * @property pdfRepository  用于更新 PdfFile 的 Repository 实例
 * @property tagRepository  用于管理标签增删查的 Repository 实例
 * @property shareRepository 用于文件分享/复制操作的 Repository 实例
 * @property preferencesManager 首选项管理器，用于读写应用配置
 * @property pdfFileDao     Room DAO，用于直接操作 PdfFileEntity 表的持久化
 */
class DetailViewModel(
    private val fileId: String,
    private val pdfRepository: PdfRepository,
    private val tagRepository: TagRepository,
    private val shareRepository: ShareRepository,
    private val preferencesManager: PreferencesManager,
    private val pdfFileDao: com.example.pdfmanager.data.local.PdfFileDao
) : ViewModel() {

    // ────────────────────────────────────────────────────────────────
    // 公共状态属性（UI 层通过 collectAsState() 收集以驱动界面刷新）
    // ────────────────────────────────────────────────────────────────

    /**
     * 当前 PDF 文件数据（内部可变状态，UI 不可直接修改）
     *
     * 功能说明：
     *   持有当前页面的 PdfFile 对象，包含文件 URI、名称、大小、标签、笔记、最后阅读页码等信息。
     *   初始为 null，在首次加载或重新加载时赋值。
     *
     * 调用位置：
     *   - loadFile() 方法中从 Room 读取实体后赋值
     *   - updateNotes() 中 copy(notes = ...) 后重新赋值
     *   - saveLastReadPage() 中 copy(lastReadPage = ...) 后重新赋值
     *
     * 使用场景：
     *   整个详情页面的核心数据源，UI 层通过 _pdfFile 的 StateFlow 观察数据变化。
     *
     * 数据流：
     *   Room (SQLite) → PdfFileDao.getById() → loadFile() → _pdfFile → UI collectAsState()
     */
    private val _pdfFile = MutableStateFlow<PdfFile?>(null)

    /**
     * 当前 PDF 文件数据（对外只读 StateFlow）
     *
     * 功能说明：
     *   _pdfFile 的不可变外部暴露版本，UI 层通过 .collectAsState() 收集以驱动界面刷新。
     *
     * 调用位置：
     *   DetailScreen 中通过 viewModel.pdfFile.collectAsState() 收集。
     *
     * @return StateFlow<PdfFile?> 当数据未加载时为 null，加载后包含完整 PdfFile 对象
     */
    val pdfFile: StateFlow<PdfFile?> = _pdfFile.asStateFlow()

    /**
     * 上次阅读页码（内部可变状态）
     *
     * 功能说明：
     *   记录用户上次阅读到的页码，用于在重新打开 PDF 时跳转到该页。
     *   初始值从 Room 数据库中的 PdfFileEntity.lastReadPage 字段读取。
     *
     * 调用位置：
     *   - loadFile() 中从 Room 实体读取后赋值
     *   - saveLastReadPage() 中更新后赋值
     *
     * 使用场景：
     *   PDF 阅读器页面初始化时读取此值以实现"续读"功能。
     *
     * 数据流：
     *   Room (SQLite) → PdfFileDao.getById() → loadFile() → _lastReadPage → UI collectAsState()
     *   saveLastReadPage() → _lastReadPage.value = page → UI collectAsState()
     */
    private val _lastReadPage = MutableStateFlow(0)

    /**
     * 上次阅读页码（对外只读 StateFlow）
     *
     * 功能说明：
     *   _lastReadPage 的不可变外部暴露版本，UI 层收集此值以在阅读器初始化时跳转到指定页。
     *
     * 调用位置：
     *   阅读器页面在 onInit 时通过 viewModel.lastReadPage.collectAsState() 获取。
     *
     * @return StateFlow<Int> 默认值为 0，表示从未阅读过
     */
    val lastReadPage: StateFlow<Int> = _lastReadPage.asStateFlow()

    /**
     * 所有标签类别列表（从 TagRepository 直接暴露）
     *
     * 功能说明：
     *   持有系统中定义的所有标签类别（如"学科"、"重要性"、"状态"等），
     *   每个类别下包含多个可选标签值。数据由 TagRepository 在首次加载时从 Room 读取，
     *   并在 addNewTagValue() 等操作后重新加载以保持最新。
     *
     * 调用位置：
     *   - loadFile() 中调用 tagRepository.loadCategories() 触发加载
     *   - addNewTagValue() 中添加新标签值后重新调用 tagRepository.loadCategories() 刷新
     *   - DetailScreen 中通过 viewModel.allCategories.collectAsState() 收集
     *
     * 使用场景：
     *   标签选择界面（如底部弹出面板）中展示所有可用的类别和标签值，
     *   供用户选择后将标签分配给当前 PDF。
     *
     * @return StateFlow<List<TagCategory>> 标签类别列表，每个类别包含类别名、颜色和标签值列表
     */
    val allCategories: StateFlow<List<TagCategory>> = tagRepository.categories

    /**
     * 当前 PDF 的标签列表（内部可变状态）
     *
     * 功能说明：
     *   存储已分配给当前 PDF 的所有标签（PdfTagEntity），
     *   每个标签包含类别 ID、标签值、类别名称和类别颜色等信息。
     *   在 loadFile() 和标签增删操作后刷新。
     *
     * 调用位置：
     *   - loadFile() 中通过 tagRepository.getTagsForPdf() 加载
     *   - addTag() / addNewTagValue() / removeTag() 操作后刷新
     *
     * 使用场景：
     *   详情页面标签区域展示当前 PDF 已绑定的所有标签，
     *   以及添加/移除标签时的即时刷新。
     *
     * 数据流：
     *   Room (pdf_tag 表和 tag 表) → TagRepository.getTagsForPdf() → _pdfTags → UI
     */
    private val _pdfTags = MutableStateFlow<List<PdfTagEntity>>(emptyList())

    /**
     * 当前 PDF 的标签列表（对外只读 StateFlow）
     *
     * 功能说明：
     *   _pdfTags 的不可变外部暴露版本，UI 层收集以展示已分配的标签。
     *
     * 调用位置：
     *   DetailScreen 中通过 viewModel.pdfTags.collectAsState() 收集。
     *
     * @return StateFlow<List<PdfTagEntity>> 标签列表，每个实体包含 categoryId、tagValue、categoryName、color
     */
    val pdfTags: StateFlow<List<PdfTagEntity>> = _pdfTags.asStateFlow()

    // ────────────────────────────────────────────────────────────────
    // 公共方法
    // ────────────────────────────────────────────────────────────────

    /**
     * 加载当前 PDF 文件数据
     *
     * 功能说明：
     *   从 Room 数据库加载 PdfFileEntity 数据，包括：
     *   1. 调用 tagRepository.loadCategories() 刷新所有标签类别数据
     *   2. 通过 pdfFileDao.getById(fileId) 查询当前文件的 Room 实体
     *   3. 从实体中读取 lastReadPage 赋值给 _lastReadPage
     *   4. 通过 tagRepository.getTagsForPdf() 获取该文件绑定的标签列表
     *   5. 将实体和标签转换为 _pdfFile 供 UI 展示
     *
     * 调用位置：
     *   DetailScreen 的 LaunchedEffect / init 中调用，确保页面初始化时数据加载完成。
     *
     * 使用场景：
     *   每次进入详情页面时调用，用于从数据库恢复页面状态。
     *
     * 数据流：
     *   Room (PdfFileEntity 表, tag 关联表) → pdfFileDao.getById() + TagRepository → _pdfFile + _pdfTags → UI
     *
     * @return Unit 无返回值，通过修改 _pdfFile、_pdfTags、_lastReadPage 等 StateFlow 间接驱动 UI
     */
    fun loadFile() {
        viewModelScope.launch {
            // 步骤 1：刷新标签类别数据
            tagRepository.loadCategories()

            // 步骤 2：从 Room 数据库查询当前文件实体
            val entity = pdfFileDao.getById(fileId)

            // 步骤 3：实体非空时加载标签和阅读进度
            if (entity != null) {
                Log.d("DetailViewModel", "loadFile: entity.lastReadPage=${entity.lastReadPage}, entity.uri=${entity.uri}, entity.id=${entity.id}")
                // 3a：获取该 PDF URI 关联的所有标签
                val tags = tagRepository.getTagsForPdf(entity.uri)
                _pdfTags.value = tags

                // 3b：恢复上次阅读页码（续读功能）
                _lastReadPage.value = entity.lastReadPage

                // 3c：将 Room 实体（PdfFileEntity）转换为 UI 层数据模型（PdfFile）
                //     同时将 PdfTagEntity 列表映射为 Tag 列表
                val pdfFile = entity.toPdfFile(tags.map { Tag(it.categoryId, it.tagValue) })
                _pdfFile.value = pdfFile

                Log.d("DetailViewModel", "loadFile: 从 Room 读取 lastReadPage=${pdfFile.lastReadPage}, uri=${pdfFile.uri}")
            } else {
                Log.w("DetailViewModel", "loadFile: pdfFile 为 null, fileId=$fileId")
            }
        }
    }

    /**
     * 添加新标签值到类别，并分配给当前 PDF
     *
     * 功能说明：
     *   创建一个全新的标签值（系统中尚不存在的值）并将其同时：
     *   1. 通过 tagRepository.addTagValue() 持久化到对应类别下
     *   2. 通过 tagRepository.addTagToPdf() 将该标签分配给当前 PDF
     *   这是一个"创建并分配"的复合操作。
     *
     * 调用位置：
     *   DetailScreen 中的标签选择 UI（通常是输入框+确认按钮的交互），
     *   用户在类别下输入新标签值并确认时调用。
     *
     * 使用场景：
     *   当用户在标签选择界面中输入的标签值在当前类别中不存在时，
     *   系统自动创建该新标签值并绑定到当前文件。
     *
     * 数据流：
     *   用户输入 → addNewTagValue(categoryId, tagValue)
     *     → TagRepository.addTagValue() → Room (tag 表) [创建新标签值]
     *     → TagRepository.addTagToPdf() → Room (pdf_tag 关联表) [分配给文件]
     *     → TagRepository.getTagsForPdf() 重新加载 → _pdfTags → UI 刷新
     *     → TagRepository.loadCategories() 重新加载 → allCategories → UI 刷新
     *
     * @param categoryId 类别 ID，标识要将新标签值添加到哪个类别下
     * @param tagValue   新标签值，用户输入的字符串，如"数学"、"高优先级"等
     */
    fun addNewTagValue(categoryId: String, tagValue: String) {
        viewModelScope.launch {
            // 步骤 1：获取当前 PDF 的 URI
            val current = _pdfFile.value ?: return@launch
            val pdfFileUri = current.uri.toString()

            // 步骤 2：将新标签值添加到类别中（持久化到 Room 的 tag 表）
            val success = tagRepository.addTagValue(categoryId, tagValue)
            if (!success) {
                Log.w("DetailViewModel", "addNewTagValue: 添加标签值失败 $tagValue")
                return@launch
            }

            // 步骤 3：获取类别信息（类别名和颜色），用于构建标签关联记录
            val category = tagRepository.getCategoryById(categoryId)
            if (category == null) {
                Log.w("DetailViewModel", "addNewTagValue: 类别不存在 $categoryId")
                return@launch
            }

            // 步骤 4：将新标签分配给当前 PDF（持久化到 Room 的 pdf_tag 关联表）
            tagRepository.addTagToPdf(pdfFileUri, categoryId, tagValue, category.name, category.color)

            // 步骤 5：刷新本地标签列表和类别数据，保持 UI 与数据库同步
            val updatedTags = tagRepository.getTagsForPdf(pdfFileUri)
            _pdfTags.value = updatedTags
            tagRepository.loadCategories() // 重新加载类别（包含新的标签值）

            Log.d("DetailViewModel", "addNewTagValue: 成功添加新标签 $tagValue to $pdfFileUri")
        }
    }

    /**
     * 添加已有标签到当前 PDF
     *
     * 功能说明：
     *   将一个系统中已存在的标签值（通过类别 ID 和标签值定位）分配给当前 PDF 文件，
     *   操作通过 TagRepository.addTagToPdf() 在 Room 的 pdf_tag 关联表中创建一条记录。
     *   与 addNewTagValue() 不同，此方法不创建新标签值，仅建立关联。
     *
     * 调用位置：
     *   DetailScreen 中的标签选择 UI（通常是列表点击或勾选事件），
     *   用户从已有标签值列表中选择一个添加到当前文件时调用。
     *
     * 使用场景：
     *   用户在标签选择界面中点击某个已有的标签值，将其绑定到当前 PDF 文件。
     *
     * 数据流：
     *   用户点击 → addTag(categoryId, value)
     *     → TagRepository.getCategoryById() 获取类别信息
     *     → TagRepository.addTagToPdf() → Room (pdf_tag 关联表) [建立关联]
     *     → TagRepository.getTagsForPdf() 重新加载 → _pdfTags → UI 刷新
     *
     * @param categoryId 类别 ID，标识标签所属的类别
     * @param value      标签值，要分配给当前 PDF 的具体标签内容
     */
    fun addTag(categoryId: String, value: String) {
        viewModelScope.launch {
            // 步骤 1：获取当前 PDF 的 URI
            val current = _pdfFile.value ?: return@launch
            val pdfFileUri = current.uri.toString()

            // 步骤 2：获取类别信息（类别名和颜色），用于构建标签关联记录
            val category = tagRepository.getCategoryById(categoryId)
            if (category == null) {
                Log.w("DetailViewModel", "addTag: 类别不存在 $categoryId")
                return@launch
            }

            // 步骤 3：将标签与当前 PDF 建立关联（写入 Room 的 pdf_tag 关联表）
            tagRepository.addTagToPdf(pdfFileUri, categoryId, value, category.name, category.color)

            // 步骤 4：刷新标签列表，保持 UI 与数据库同步
            val updatedTags = tagRepository.getTagsForPdf(pdfFileUri)
            _pdfTags.value = updatedTags

            Log.d("DetailViewModel", "addTag: 成功添加标签 $value ($categoryId)")
        }
    }

    /**
     * 从当前 PDF 移除标签
     *
     * 功能说明：
     *   解除当前 PDF 文件与指定标签的关联关系，
     *   通过 TagRepository.removeTagFromPdf() 从 Room 的 pdf_tag 关联表中删除对应记录。
     *   此操作仅移除关联关系，不会删除标签值本身。
     *
     * 调用位置：
     *   DetailScreen 中当前 PDF 标签展示区域（如标签卡片上的"×"删除按钮），
     *   用户点击删除标签时调用。
     *
     * 使用场景：
     *   用户认为某个标签不再适用于当前 PDF 文件时，点击标签上的移除按钮取消关联。
     *
     * 数据流：
     *   用户点击删除 → removeTag(categoryId, tagValue)
     *     → TagRepository.removeTagFromPdf() → Room (pdf_tag 关联表) [删除关联记录]
     *     → TagRepository.getTagsForPdf() 重新加载 → _pdfTags → UI 刷新
     *
     * @param categoryId 标签所属的类别 ID
     * @param tagValue   要移除的标签值
     */
    fun removeTag(categoryId: String, tagValue: String) {
        viewModelScope.launch {
            // 步骤 1：获取当前 PDF 的 URI
            val current = _pdfFile.value ?: return@launch
            val pdfFileUri = current.uri.toString()

            // 步骤 2：从 Room 的 pdf_tag 关联表中删除该标签与 PDF 的关联记录
            tagRepository.removeTagFromPdf(pdfFileUri, categoryId, tagValue)

            // 步骤 3：刷新标签列表，保持 UI 与数据库同步
            val updatedTags = tagRepository.getTagsForPdf(pdfFileUri)
            _pdfTags.value = updatedTags

            Log.d("DetailViewModel", "removeTag: 成功移除标签 $tagValue ($categoryId)")
        }
    }

    /**
     * 更新当前 PDF 的笔记/备注内容
     *
     * 功能说明：
     *   保存用户在详情页面编辑的笔记/备注内容，采用双重写入策略确保数据一致性：
     *   1. 通过 pdfRepository.updateFile() 更新内存中的 PdfFile 对象（上层抽象）
     *   2. 通过 pdfFileDao.update() 直接写入 Room 数据库（底层持久化）
     *   内存中的 _pdfFile 也通过 copy(notes = ...) 同步更新以驱动 UI 刷新。
     *
     * 调用位置：
     *   DetailScreen 中的笔记编辑框（TextField），在用户完成编辑并确认时调用。
     *
     * 使用场景：
     *   用户在 PDF 详情页的笔记区域输入或修改文字笔记，
     *   用于记录个人阅读心得、摘要或其他附加信息。
     *
     * 数据流：
     *   用户输入 → updateNotes(notes)
     *     → _pdfFile.value = current.copy(notes = notes) [更新内存状态 → UI 刷新]
     *     → pdfRepository.updateFile(updated) [更新上层抽象层]
     *     → pdfFileDao.getById() + pdfFileDao.update() [双重写入 Room 持久化]
     *
     * @param notes 笔记内容字符串，用户输入的完整笔记文本
     */
    fun updateNotes(notes: String) {
        viewModelScope.launch {
            val current = _pdfFile.value ?: return@launch
            Log.d("DetailViewModel", "updateNotes: 开始保存备注，fileId=${current.id}, notes=$notes")

            // 步骤 1：通过 copy() 更新内存中的 PdfFile 对象 → 触发 UI 观察者刷新
            val updated = current.copy(notes = notes)
            _pdfFile.value = updated

            // 步骤 2：通过 Repository 更新上层缓存数据
            pdfRepository.updateFile(updated)

            // 步骤 3：通过 DAO 直接写入 Room 数据库进行持久化
            val entity = pdfFileDao.getById(current.id)
            if (entity != null) {
                val updatedEntity = entity.copy(notes = notes)
                pdfFileDao.update(updatedEntity)
            }

            Log.d("DetailViewModel", "updateNotes: 备注保存完成")
        }
    }

    /**
     * 保存最后阅读页码
     *
     * 功能说明：
     *   记录用户最近一次阅读到的页码，以便下次打开 PDF 时能跳转到该位置继续阅读。
     *   采用双重写入策略确保数据一致性：
     *   1. 更新 _pdfFile 和 _lastReadPage 内存状态（立即驱动 UI 刷新）
     *   2. 通过 pdfRepository.updateFile() 更新上层抽象层
     *   3. 通过 pdfFileDao.update() 直接写入 Room 数据库持久化
     *
     * 调用位置：
     *   PDF 阅读器页面（PdfViewerScreen），在页面销毁、切换页面或定时保存时调用。
     *
     * 使用场景：
     *   - 用户阅读过程中翻页时触发自动保存
     *   - 用户退出阅读器页面时触发保存
     *   - 应用进入后台时触发保存
     *   目的是实现"续读"功能，让用户下次打开时从上次位置继续阅读。
     *
     * 数据流：
     *   阅读器翻页 → saveLastReadPage(page)
     *     → _pdfFile.value = current.copy(lastReadPage = page) [更新内存 → UI 刷新]
     *     → _lastReadPage.value = page [更新页码专用状态 → UI 刷新]
     *     → pdfRepository.updateFile(updated) [更新上层抽象层]
     *     → pdfFileDao.getById() + pdfFileDao.update() [双重写入 Room 持久化]
     *
     * @param page 当前阅读到的页码（从 0 开始计数）
     */
    fun saveLastReadPage(page: Int) {
        viewModelScope.launch {
            val current = _pdfFile.value ?: return@launch
            Log.d("DetailViewModel", "saveLastReadPage: 开始保存页码，fileId=${current.id}, page=$page")

            // 步骤 1：更新内存中的 PdfFile 对象 → 触发 UI 观察者刷新
            val updated = current.copy(lastReadPage = page)
            _pdfFile.value = updated

            // 步骤 2：更新专用的页码 StateFlow → 阅读器界面可以立即获取新页码
            _lastReadPage.value = page

            // 步骤 3：通过 Repository 更新上层缓存数据
            pdfRepository.updateFile(updated)

            // 步骤 4：通过 DAO 直接写入 Room 数据库进行持久化
            val entity = pdfFileDao.getById(current.id)
            if (entity != null) {
                val updatedEntity = entity.copy(lastReadPage = page)
                pdfFileDao.update(updatedEntity)
            }

            Log.d("DetailViewModel", "saveLastReadPage: 页码保存完成")
        }
    }

    /**
     * 分享当前文件到 share/ 根目录
     *
     * 功能说明：
     *   将当前 PDF 文件复制到应用共享目录（默认 share/ 目录），
     *   其他应用可以通过 FileProvider 访问该目录中的文件。
     *   此操作通过 ShareRepository.copySingleFile() 完成。
     *   这是一个挂起函数（suspend），必须在协程作用域内调用。
     *
     * 调用位置：
     *   DetailScreen 中的"分享"按钮，用户点击时触发。
     *   通常在协程（如 lifecycleScope.launch）中调用。
     *
     * 使用场景：
     *   用户需要将 PDF 文件分享给其他应用（如微信、邮件等）时，
     *   先将文件复制到可被外部访问的共享目录，然后通过 Intent 发送。
     *
     * 数据流：
     *   用户点击分享按钮
     *     → shareCurrentFile()（挂起函数）
     *     → AppContainer.shareRepository.copySingleFile(current.uri)
     *     → 文件复制操作（可能涉及 ContentResolver 或 IO 操作）
     *     → 返回成功复制的文件数量
     *
     * @return Int 成功复制的文件数量，如果当前 PDF 为 null 或复制失败则返回 0
     */
    suspend fun shareCurrentFile(): Int {
        val current = _pdfFile.value ?: return 0
        return AppContainer.shareRepository.copySingleFile(current.uri)
    }

    /**
     * 分享当前文件到指定目标目录（配合 ShareTargetPicker 使用）
     *
     * 功能说明：
     *   将当前 PDF 文件复制到用户通过 SAF（Storage Access Framework）
     *   选择的指定目标目录中。支持通过 SAF Uri 指定目标位置，
     *   适用于 Android 10+ 的 Scoped Storage 环境。
     *   这是一个挂起函数（suspend），必须在协程作用域内调用。
     *
     * 调用位置：
     *   DetailScreen 中的"分享到..."按钮，用户选择目标文件夹后触发。
     *   配合 ShareTargetPicker（目录选择器）使用，用户通过 SAF 选定目录后传入其 Uri。
     *
     * 使用场景：
     *   用户需要将 PDF 文件复制到手机上的特定目录（如 Downloads、Documents 等），
     *   通过系统文件选择器选定目标位置后执行复制操作。
     *
     * 数据流：
     *   用户点击"分享到..." → 系统文件选择器打开 → 用户选择目标文件夹
     *     → 获得 targetFolderUri → shareCurrentFileToTarget(targetFolderUri)
     *     → AppContainer.shareRepository.copySingleFileToTarget(current.uri, targetFolderUri)
     *     → 通过 ContentResolver 进行跨目录文件复制
     *     → 返回成功复制的文件数量
     *
     * @param targetFolderUri 目标文件夹的 SAF Uri，通过系统文件选择器获得
     * @return Int 成功复制的文件数量，如果当前 PDF 为 null 或复制失败则返回 0
     */
    suspend fun shareCurrentFileToTarget(targetFolderUri: Uri): Int {
        val current = _pdfFile.value ?: return 0
        return AppContainer.shareRepository.copySingleFileToTarget(current.uri, targetFolderUri = targetFolderUri)
    }

    /**
     * 根据类别 ID 获取标签类别信息（非挂起，直接返回）
     *
     * 功能说明：
     *   通过 tagRepository.getCategoryById() 从内存缓存中直接查询
     *   指定 ID 对应的 TagCategory 对象。此方法不涉及数据库 I/O 操作，
     *   非挂起函数，可以在非协程环境中直接调用。
     *
     * 调用位置：
     *   DetailScreen 中的 UI 逻辑，例如根据类别 ID 获取类别颜色用于标签渲染，
     *   或在标签选择界面中展示类别名称。
     *
     * 使用场景：
     *   - 根据标签实体的 categoryId 获取对应的类别名称和颜色，用于 UI 展示
     *   - 在添加标签时预先校验类别是否存在
     *
     * @param categoryId 要查询的标签类别 ID
     * @return TagCategory? 对应的标签类别对象，如果 ID 无效则返回 null
     */
    fun getCategoryById(categoryId: String): TagCategory? {
        return tagRepository.getCategoryById(categoryId)
    }

    // ────────────────────────────────────────────────────────────────
    // ViewModel 工厂（用于依赖注入）
    // ────────────────────────────────────────────────────────────────

    /**
     * DetailViewModel 的工厂类
     *
     * 功能说明：
     *   实现 ViewModelProvider.Factory 接口，用于在 Compose 中
     *   通过 ViewModelProvider 创建带参数构造函数（fileId）的 DetailViewModel 实例。
     *   无需 Dagger/Hilt 等依赖注入框架即可完成 ViewModel 的实例化。
     *
     * 调用位置：
     *   DetailScreen 中通过 viewModel() 或 ViewModelProvider 创建 ViewModel 时使用：
     *   ```
     *   val viewModel: DetailViewModel = viewModel(factory = DetailViewModel.Factory(fileId))
     *   ```
     *
     * 使用场景：
     *   在 Compose 页面中需要创建带自定义参数的 ViewModel 时使用，
     *   通过工厂模式将 AppContainer 中的依赖（Repository、DAO 等）注入到 ViewModel 中。
     *
     * @property fileId 要加载的 PDF 文件 ID，在创建时传入，用于构造 DetailViewModel
     */

    // ── 文件存在性检查 ─────────────────────────────────────

    /**
     * 检查 PDF 文件是否仍然存在于文件系统中
     *
     * 功能说明：
     * 1. 尝试通过 ContentResolver 打开文件输入流
     * 2. 如果能打开，说明文件存在且可访问
     * 3. 如果抛出异常，说明文件已被移动、删除或 URI 失效
     *
     * 调用位置：
     * - DetailScreen - 用户点击"开始阅读"或"继续阅读"按钮前调用
     *
     * 使用场景：
     * - 导入旧数据库后，URI 可能已失效
     * - 用户手动删除了文件但数据库未更新
     *
     * @param context Context（用于获取 ContentResolver）
     * @param uri 要检查的 PDF 文件 URI
     * @return true 表示文件存在且可访问，false 表示文件不可用
     */
    fun checkFileExists(context: Context, uri: Uri): Boolean {
        return try {
            context.contentResolver.openInputStream(uri)?.close() ?: false
            true
        } catch (e: Exception) {
            false
        }
    }

    class Factory(
        private val fileId: String
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return DetailViewModel(
                fileId = fileId,
                pdfRepository = AppContainer.pdfRepository,
                tagRepository = AppContainer.tagRepository,
                shareRepository = AppContainer.shareRepository,
                preferencesManager = AppContainer.preferencesManager,
                pdfFileDao = AppContainer.database.pdfFileDao()
            ) as T
        }
    }
}
