package com.example.pdfmanager.data.repository

import android.content.Context
import com.example.pdfmanager.data.local.FileScanner
import com.example.pdfmanager.data.local.PdfManagerDatabase
import com.example.pdfmanager.data.local.PreferencesManager
import com.example.pdfmanager.data.model.SavedFilter
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import android.util.Log

/**
 * 全局单例容器（依赖注入容器）
 * 
 * 功能说明：
 * 1. 持有所有 Repository 和工具类实例（单例模式）
 * 2. 确保 AllFilesViewModel 和 DetailViewModel 共享同一套数据
 * 3. 管理应用级状态（选中文件、底部导航、筛选结果、缩略图生成进度）
 * 4. 支持多库文件夹切换（每个库对应独立的 Room 数据库）
 * 
 * 使用示例：
 * ```kotlin
 * // 在 Application 类中初始化
 * AppContainer.init(context)
 * 
 * // 在 ViewModel 中访问
 * val pdfRepository = AppContainer.pdfRepository
 * val preferencesManager = AppContainer.preferencesManager
 * ```
 * 
 * 依赖关系：
 * - 依赖：PreferencesManager（设置存储）、PdfManagerDatabase（Room数据库）
 * - 被依赖：AllFilesViewModel、DetailViewModel、MainScreen 等所有核心组件
 * 
 * 线程安全：
 * - 使用 lateinit 延迟初始化
 * - initialized 标志位防止重复初始化
 * - StateFlow 保证状态观察的线程安全
 * 
 * @author PDF Manager Development Team
 * @version 1.0
 */
object AppContainer {
    private var initialized = false

    // ── 应用上下文（保存 Application Context，供 ViewModel Factory 使用）──────────────────────
    
    /**
     * 应用上下文（Application Context）
     * 用途：提供全局 Context，避免内存泄漏
     * 访问权限：只读（private set）
     */
    lateinit var appContext: Context
        private set

    // ── 核心 Repository 实例 ─────────────────────────────────────
    
    /**
     * 偏好设置管理器
     * 用途：存储库文件夹URI、缩略图大小、主题模式等全局设置
     * 调用位置：AllFilesViewModel.init()、SettingsScreen、MainActivity
     */
    lateinit var preferencesManager: PreferencesManager
        private set

    /**
     * 文件扫描器
     * 用途：递归扫描库文件夹，收集 PDF 文件（支持 File API 和 SAF API）
     * 调用位置：PdfRepository.scanLibrary()、PdfRepository.quickIncrementalScan()
     */
    lateinit var fileScanner: FileScanner
        private set

    /**
     * PDF 数据仓库
     * 用途：管理 PDF 文件列表（内存缓存 + Room 持久化）
     * 调用位置：AllFilesViewModel、DetailViewModel、FavoritesViewModel
     */
    lateinit var pdfRepository: PdfRepository
        private set

    /**
     * 标签数据仓库
     * 用途：管理标签类别和标签值（CRUD 操作）
     * 调用位置：TagManagerViewModel、DetailViewModel、AllFilesViewModel
     */
    lateinit var tagRepository: TagRepository
        private set

    /**
     * 搜索索引仓库
     * 用途：内存索引，加速文件名搜索
     * 调用位置：SearchViewModel、AllFilesViewModel
     */
    lateinit var searchIndexRepository: SearchIndexRepository
        private set

    /**
     * 分享仓库
     * 用途：复制 PDF 文件到其他文件夹（多选分享功能）
     * 调用位置：AllFilesViewModel.shareSelectedFiles()
     */
    lateinit var shareRepository: ShareRepository
        private set

    /**
     * Room 数据库实例
     * 用途：持久化 PDF 文件、标签、收藏等数据
     * 调用位置：PdfRepository、TagRepository、FavoritesRepository
     */
    lateinit var database: PdfManagerDatabase
        private set

    /**
     * 收藏仓库
     * 用途：管理收藏文件夹和 PDF 排序
     * 调用位置：FavoritesViewModel、FavoritesScreen
     */
    lateinit var favoritesRepository: FavoritesRepository
        private set

    /**
     * 转换仓库
     * 用途：管理 PDF 转换任务（ZIP 转 PDF、图片转 PDF）
     * 调用位置：ConversionViewModel、ConversionScreen
     */
    lateinit var conversionRepository: ConversionRepository
        private set

    // ── 全局状态（跨页面共享）────────────────────────────────────
    
    /**
     * 全局选中的文件ID列表
     * 用途：供 SettingsScreen 等跨页面访问多选状态
     * 观察位置：AllFilesScreen（高亮选中项）、SettingsScreen（显示选中数量）
     */
    val selectedFileIds = MutableStateFlow<Set<String>>(emptySet())

    /**
     * 底部导航当前 Tab
     * 用途：供跨页面同步底部导航状态（如从设置页返回时保持当前Tab）
     * 观察位置：MainScreen
     */
    val selectedTab = MutableStateFlow(1)  // 默认"全部文件" Tab

    /**
     * 筛选结果暂存
     * 用途：FilterScreen 确认后设置，AllFilesScreen 消费后清除
     * 流程：FilterScreen → 设置值 → AllFilesViewModel 观察 → 应用筛选 → 清除值
     */
    private val _pendingFilterResult = MutableStateFlow<SavedFilter?>(null)
    val pendingFilterResult: StateFlow<SavedFilter?> = _pendingFilterResult.asStateFlow()

    /**
     * 缩略图生成进度
     * 用途：供 SettingsScreen 等观察缩略图生成进度
     * 格式：(已生成数量, 总数量)
     */
    val thumbnailProgress = MutableStateFlow<Pair<Int, Int>?>(null)

    /**
     * 缩略图生成状态
     * 用途：供 SettingsScreen 按钮状态使用（防止重复触发）
     */
    val isThumbnailGenerationRunning = MutableStateFlow(false)
    
    /**
     * 缩略图生成结果
     * 用途：通知 UI 缩略图生成完成（显示 Toast 等）
     */
    val thumbnailGenerationResult = MutableStateFlow<ThumbnailGenerationResult?>(null)

    /**
     * 数据库切换刷新信号
     * 用途：每次切换数据库后递增，供 AllFilesViewModel 观察并重新绑定
     *       新的 PdfRepository 实例（因为旧实例的 StateFlow 已失效）
     */
    val libraryRefreshTick = MutableStateFlow(0L)

    /**
     * 缩略图生成结果数据类
     * 
     * @property generated 成功生成的数量
     * @property total 总数量
     * @property failed 失败的数量
     */
    data class ThumbnailGenerationResult(
        val generated: Int,
        val total: Int,
        val failed: Int
    )

    // ── 筛选结果管理方法 ─────────────────────────────────────
    
    /**
     * 设置待处理的筛选结果
     * 
     * 调用位置：
     * - FilterScreen.onConfirm() - 用户确认筛选条件后调用
     * 
     * 使用场景：
     * - 用户从筛选页返回文件列表页时，传递筛选条件
     * 
     * @param filter 筛选条件（null 表示清除筛选）
     */
    fun setPendingFilterResult(filter: SavedFilter?) {
        _pendingFilterResult.value = filter
    }

    /**
     * 消费待处理的筛选结果（获取并清除）
     * 
     * 调用位置：
     * - AllFilesViewModel.initialize() - 观察 pendingFilterResult 时调用
     * 
     * @return 筛选条件（如果有），同时清除 _pendingFilterResult
     */
    fun consumePendingFilterResult(): SavedFilter? {
        val result = _pendingFilterResult.value
        _pendingFilterResult.value = null
        return result
    }

    // ── 选中文件管理方法 ─────────────────────────────────────
    
    /**
     * 清空全局选中的文件ID列表
     * 
     * 调用位置：
     * - AllFilesViewModel.clearSelection() - 关闭多选模式时调用
     * - SettingsScreen - 完成分享后调用
     */
    fun clearSelectedFileIds() {
        selectedFileIds.value = emptySet()
    }

    // ── 初始化方法 ─────────────────────────────────────
    
    /**
     * 初始化 AppContainer（单例模式）
     * 
     * 功能说明：
     * 1. 创建 PreferencesManager 实例
     * 2. 创建 FileScanner 实例
     * 3. 根据库文件夹 URI 决定数据库名称（多库隔离）
     * 4. 创建 Room 数据库实例
     * 5. 创建所有 Repository 实例
     * 
     * 调用位置：
     * - PdfManagerApp.onCreate() - Application 创建时调用
     * 
     * 使用场景：
     * - 应用首次启动
     * - 进程被系统杀死后恢复
     * 
     * @param context Context（会自动转换为 Application Context）
     */
    fun init(context: Context) {
        if (initialized) return
        appContext = context.applicationContext

        preferencesManager = PreferencesManager(appContext)
        fileScanner = FileScanner(appContext)
        
        // 获取之前保存的库文件夹 URI，决定使用哪个数据库
        val libraryUri = runBlocking { preferencesManager.getLibraryUri() }
        val dbName = if (libraryUri != null) {
            "pdf_manager_${libraryUri.hashCode()}.db"
        } else {
            "pdf_manager.db"
        }
        
        database = PdfManagerDatabase.getDatabase(appContext, dbName)
        // 记录映射：当前库文件夹 URI → 数据库文件名
        if (libraryUri != null) {
            runBlocking { preferencesManager.saveDatabaseUriMapping(dbName, libraryUri) }
        }
        tagRepository = TagRepository(database)
        // 先初始化 searchIndexRepository，再传入 pdfRepository
        searchIndexRepository = SearchIndexRepository()
        pdfRepository = PdfRepository(appContext, fileScanner, preferencesManager, searchIndexRepository, database.pdfFileDao(), database.pdfTagDao())
        shareRepository = ShareRepository(appContext)
        favoritesRepository = FavoritesRepository(database.favoritesDao())
        conversionRepository = ConversionRepository(appContext)

        initialized = true
    }

    // ── 切换库文件夹 ─────────────────────────────────────
    
    /**
     * 切换库文件夹（关闭旧数据库，打开/创建新数据库）
     * 
     * 功能说明：
     * 1. 根据新库文件夹 URI 生成数据库名称
     * 2. 关闭当前 Room 数据库
     * 3. 重新初始化所有 Repository（使用新数据库）
     * 4. 触发扫描新库文件夹
     * 
     * 调用位置：
     * - SettingsScreen.onChangeLibrary() - 用户点击"更改库文件夹"时调用
     * - MainActivity.onActivityResult() - SAF 选择完成后调用
     * 
     * 使用场景：
     * - 用户更换 PDF 库文件夹
     * - 首次绑定库文件夹
     * 
     * @param context Context（用于访问数据库文件）
     * @param newLibraryUri 新的库文件夹 URI（SAF URI）
     */
    fun switchLibrary(context: Context, newLibraryUri: String) {
        val dbName = "pdf_manager_${newLibraryUri.hashCode()}.db"
        
        // 检查数据库文件是否已存在
        val dbFile = context.getDatabasePath(dbName)
        val dbExists = dbFile.exists()
        
        Log.d("AppContainer", "切换库文件夹：数据库名=$dbName，已存在=$dbExists")
        
        // 1. 关闭旧数据库
        PdfManagerDatabase.closeDatabase()
        initialized = false
        
        // 2. 更新当前库 URI（确保重启后也能正确加载）
        runBlocking { preferencesManager.saveLibraryUri(newLibraryUri) }
        
        // 3. 重新初始化（Room 会自动处理：存在则打开，不存在则新建）
        appContext = context.applicationContext
        database = PdfManagerDatabase.getDatabase(appContext, dbName)
        // 记录新库的映射
        runBlocking { preferencesManager.saveDatabaseUriMapping(dbName, newLibraryUri) }
        tagRepository = TagRepository(database)
        searchIndexRepository = SearchIndexRepository()
        pdfRepository = PdfRepository(appContext, fileScanner, preferencesManager, searchIndexRepository, database.pdfFileDao(), database.pdfTagDao())
        favoritesRepository = FavoritesRepository(database.favoritesDao())
        // conversionRepository 和 shareRepository 不需要重新初始化（不依赖数据库）
        
        initialized = true
        
        // 4. 从新数据库恢复文件列表到内存，并触发刷新信号
        MainScope().launch {
            pdfRepository.restoreFromRoom()
            // 5. 触发库切换刷新信号，通知 AllFilesViewModel 重新绑定到新 PdfRepository
            //    同时切换后 AllFilesScreen 中 _needsLibrarySetup 会被重新检查
            libraryRefreshTick.value = System.currentTimeMillis()
        }
        
        Log.d("AppContainer", "已切换到新库文件夹，数据库: $dbName (${if (dbExists) "打开已有数据库" else "创建新数据库"})")
    }
}
