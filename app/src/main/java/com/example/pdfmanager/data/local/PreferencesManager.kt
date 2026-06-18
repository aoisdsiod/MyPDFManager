package com.example.pdfmanager.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * DataStore 单例（文件级别扩展属性）
 * 所有 PreferencesManager 方法通过 context.dataStore 访问此实例
 */
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * 设置存储管理器（基于 DataStore Preferences）
 * 
 * 功能说明：
 * 1. 使用 Jetpack DataStore 存储键值对（替代 SharedPreferences）
 * 2. 提供 Key-Value 形式的全局设置存取
 * 3. 支持 Flow 响应式观察设置变化
 * 4. 每个设置都提供三个方法：save (suspend)、getFlow (Flow)、get (suspend)
 * 
 * 使用示例：
 * ```kotlin
 * // 保存设置
 * viewModelScope.launch {
 *     preferencesManager.saveLibraryUri("content://...")
 * }
 * 
 * // 观察设置变化
 * preferencesManager.getLibraryUriFlow().collect { uri ->
 *     // 实时响应变化
 * }
 * 
 * // 一次性读取
 * val uri = preferencesManager.getLibraryUri()
 * ```
 * 
 * 存储内容：
 * - LIBRARY_URI（String）：库文件夹 URI
 * - THUMBNAIL_SIZE（Int）：缩略图大小（0=大, 1=中, 2=小）
 * - MULTI_SELECT_MODE（Boolean）：多选分享模式
 * - CONVERSION_MONITOR_FOLDER（String）：转换监控文件夹
 * - CONVERSION_OUTPUT_PATH（String）：转换输出路径
 * - THEME_MODE（String）：主题模式（"light" / "dark" / "follow_system"）
 * - READER_PAGE_MODE（String）：翻页方式（"single_page" / "continuous"）
 * - READER_TOOLBAR_MODE（String）：工具栏显示模式（"full" / "page_only" / "hidden"）
 * - CONVERT_ALL_IMAGES（Boolean）：是否转换所有图片（跳过页码选择）
 * 
 * 依赖关系：
 * - 依赖：Context（用于获取 DataStore 实例）
 * - 被依赖：AppContainer、AllFilesViewModel、MainViewModel、ReaderViewModel
 * 
 * 线程安全：
 * - DataStore 保证所有读写操作的线程安全性
 * - Flow 在 Main 线程安全收集
 * 
 * @author PDF Manager Development Team
 * @version 1.0
 */
class PreferencesManager(private val context: Context) {

    // ── DataStore 键定义 ─────────────────────────────────────
    companion object {
        /** 库文件夹 URI */
        val LIBRARY_URI_KEY = stringPreferencesKey("library_uri")
        /** 缩略图大小（0=大, 1=中, 2=小） */
        val THUMBNAIL_SIZE_KEY = intPreferencesKey("thumbnail_size")
        /** 多选分享模式（true=已开启） */
        val MULTI_SELECT_MODE_KEY = booleanPreferencesKey("multi_select_mode")
        /** PDF 转换监控文件夹 */
        val CONVERSION_MONITOR_FOLDER_KEY = stringPreferencesKey("conversion_monitor_folder")
        /** PDF 转换输出路径 */
        val CONVERSION_OUTPUT_PATH_KEY = stringPreferencesKey("conversion_output_path")
        /** 主题模式（"light" / "dark" / "follow_system"） */
        val THEME_MODE_KEY = stringPreferencesKey("theme_mode")

        // 阅读设置
        /** 翻页方式（"single_page"=单页, "continuous"=滚动） */
        val READER_PAGE_MODE_KEY = stringPreferencesKey("reader_page_mode")
        /** 工具栏显示模式（"full"=正常, "page_only"=仅页码, "hidden"=隐藏） */
        val READER_TOOLBAR_MODE_KEY = stringPreferencesKey("reader_toolbar_mode")
        /** 转换所有图片（true=跳过页码选择） */
        val CONVERT_ALL_IMAGES_KEY = booleanPreferencesKey("convert_all_images")
        /** 数据库文件名 ↔ 库文件夹URI 映射表（JSON 字符串） */
        val DATABASE_MAP_KEY = stringPreferencesKey("database_uri_map")
    }

    // ── 库文件夹 URI ─────────────────────────────────────
    
    /**
     * 保存库文件夹 URI
     * 
     * 调用位置：MainActivity.onActivityResult() SAF 选择完成后
     * @param uri 库文件夹的 SAF URI
     */
    suspend fun saveLibraryUri(uri: String) {
        context.dataStore.edit { preferences ->
            preferences[LIBRARY_URI_KEY] = uri
        }
    }

    /**
     * 观察库文件夹 URI 变化（Flow）
     * 
     * 调用位置：MainViewModel - 实时监测库文件夹变化
     * @return Flow<String?>（null 表示未绑定库文件夹）
     */
    fun getLibraryUriFlow(): Flow<String?> {
        return context.dataStore.data.map { preferences ->
            preferences[LIBRARY_URI_KEY]
        }
    }

    /**
     * 读取库文件夹 URI（一次性）
     * 
     * 调用位置：AllFilesViewModel.initialize(), PdfRepository.scanLibrary()
     * @return String?（null 表示未绑定库文件夹）
     */
    suspend fun getLibraryUri(): String? {
        return context.dataStore.data.first()[LIBRARY_URI_KEY]
    }

    // ── 缩略图大小 ─────────────────────────────────────
    
    /**
     * 保存缩略图大小设置
     * 
     * 调用位置：AllFilesViewModel.setThumbSize()
     * @param size 0=大, 1=中, 2=小
     */
    suspend fun saveThumbnailSize(size: Int) {
        context.dataStore.edit { preferences ->
            preferences[THUMBNAIL_SIZE_KEY] = size
        }
    }

    /**
     * 观察缩略图大小变化（Flow）
     * 
     * 调用位置：AllFilesViewModel.initialize() - 实时监测设置变化
     * @return Flow<Int>（默认 1=中）
     */
    fun getThumbnailSizeFlow(): Flow<Int> {
        return context.dataStore.data.map { preferences ->
            preferences[THUMBNAIL_SIZE_KEY] ?: 1
        }
    }

    /**
     * 读取缩略图大小（一次性）
     * 
     * 调用位置：PdfThumbnail - 加载缩略图时根据大小决定图片尺寸
     * @return Int（默认 1=中）
     */
    suspend fun getThumbnailSize(): Int {
        return context.dataStore.data.first()[THUMBNAIL_SIZE_KEY] ?: 1
    }

    // ── 清除设置 ─────────────────────────────────────
    
    /**
     * 清除所有 DataStore 设置
     * 
     * 使用场景：切换库文件夹时清空旧设置
     */
    suspend fun clearAll() {
        context.dataStore.edit { preferences ->
            preferences.clear()
        }
    }

    // ── 多选分享模式 ─────────────────────────────────────
    
    /**
     * 设置多选分享模式
     * 
     * 调用位置：AllFilesViewModel.toggleMultiSelectMode()
     * @param enabled true=开启多选, false=关闭
     */
    suspend fun setMultiSelectMode(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[MULTI_SELECT_MODE_KEY] = enabled
        }
    }

    /**
     * 观察多选模式状态变化（Flow）
     * 
     * 调用位置：AllFilesViewModel.initialize() - 实时监测模式变化
     * @return Flow<Boolean>（默认 false）
     */
    fun getMultiSelectModeFlow(): Flow<Boolean> {
        return context.dataStore.data.map { preferences ->
            preferences[MULTI_SELECT_MODE_KEY] ?: false
        }
    }

    /**
     * 读取多选模式状态（一次性）
     * 
     * @return Boolean（默认 false）
     */
    suspend fun getMultiSelectMode(): Boolean {
        return context.dataStore.data.first()[MULTI_SELECT_MODE_KEY] ?: false
    }

    // ── PDF 转换设置（转换监控文件夹）─────────────────────────────
    
    /**
     * 保存转换监控文件夹 URI
     * 
     * 调用位置：ConversionViewModel.onSelectMonitorFolder()
     * @param uri 监控文件夹的 SAF URI
     */
    suspend fun saveConversionMonitorFolder(uri: String) {
        context.dataStore.edit { preferences ->
            preferences[CONVERSION_MONITOR_FOLDER_KEY] = uri
        }
    }

    /**
     * 观察转换监控文件夹变化（Flow）
     * 
     * @return Flow<String?>（null 表示未设置）
     */
    fun getConversionMonitorFolderFlow(): Flow<String?> {
        return context.dataStore.data.map { preferences ->
            preferences[CONVERSION_MONITOR_FOLDER_KEY]
        }
    }

    /**
     * 读取转换监控文件夹 URI（一次性）
     * 
     * @return String?（null 表示未设置）
     */
    suspend fun getConversionMonitorFolder(): String? {
        return context.dataStore.data.first()[CONVERSION_MONITOR_FOLDER_KEY]
    }

    // ── PDF 转换设置（转换输出路径）─────────────────────────────
    
    /**
     * 保存转换输出路径 URI
     * 
     * 调用位置：ConversionViewModel.onSelectOutputPath()
     * @param uri 输出文件夹的 SAF URI
     */
    suspend fun saveConversionOutputPath(uri: String) {
        context.dataStore.edit { preferences ->
            preferences[CONVERSION_OUTPUT_PATH_KEY] = uri
        }
    }

    /**
     * 观察转换输出路径变化（Flow）
     * 
     * @return Flow<String?>（null 表示未设置）
     */
    fun getConversionOutputPathFlow(): Flow<String?> {
        return context.dataStore.data.map { preferences ->
            preferences[CONVERSION_OUTPUT_PATH_KEY]
        }
    }

    /**
     * 读取转换输出路径 URI（一次性）
     * 
     * @return String?（null 表示未设置）
     */
    suspend fun getConversionOutputPath(): String? {
        return context.dataStore.data.first()[CONVERSION_OUTPUT_PATH_KEY]
    }

    // ── 主题模式 ─────────────────────────────────────
    
    /**
     * 设置主题模式
     * 
     * 调用位置：SettingsScreen.onThemeChange()
     * @param mode "light"=浅色, "dark"=深色, "follow_system"=跟随系统
     */
    suspend fun setThemeMode(mode: String) {
        context.dataStore.edit { preferences ->
            preferences[THEME_MODE_KEY] = mode
        }
    }

    /**
     * 观察主题模式变化（Flow）
     * 
     * 调用位置：MainActivity.onResume() - 实时监测主题变化
     * @return Flow<String>（默认 "follow_system"）
     */
    fun getThemeModeFlow(): Flow<String> {
        return context.dataStore.data.map { preferences ->
            preferences[THEME_MODE_KEY] ?: "follow_system"
        }
    }

    /**
     * 读取主题模式（一次性）
     * 
     * @return String（默认 "follow_system"）
     */
    suspend fun getThemeMode(): String {
        return context.dataStore.data.first()[THEME_MODE_KEY] ?: "follow_system"
    }

    // ── 转换设置（是否转换所有图片）─────────────────────────────
    
    /**
     * 设置是否跳过页码选择（直接转换所有页）
     * 
     * 调用位置：ConversionSettingsScreen.onToggleConvertAll()
     * @param enabled true=转换所有页（跳过选择）, false=选择特定页
     */
    suspend fun setConvertAllImages(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[CONVERT_ALL_IMAGES_KEY] = enabled
        }
    }

    /**
     * 观察转换所有图片设置变化（Flow）
     * 
     * @return Flow<Boolean>（默认 true，兼容旧版）
     */
    fun getConvertAllImagesFlow(): Flow<Boolean> {
        return context.dataStore.data.map { preferences ->
            preferences[CONVERT_ALL_IMAGES_KEY] ?: true  // 默认 true，兼容旧版
        }
    }

    /**
     * 读取转换所有图片设置（一次性）
     * 
     * @return Boolean（默认 true）
     */
    suspend fun getConvertAllImages(): Boolean {
        return context.dataStore.data.first()[CONVERT_ALL_IMAGES_KEY] ?: true
    }

    // ── 阅读设置（翻页方式）─────────────────────────────
    
    /**
     * 设置翻页方式
     * 
     * 调用位置：ReaderScreen.onPageModeChange()
     * @param mode "single_page"=单页显示, "continuous"=滚动模式
     */
    suspend fun setReaderPageMode(mode: String) {
        context.dataStore.edit { preferences ->
            preferences[READER_PAGE_MODE_KEY] = mode
        }
    }

    /**
     * 观察翻页方式变化（Flow）
     * 
     * 调用位置：ReaderViewModel.initialize()
     * @return Flow<String>（默认 "single_page"）
     */
    fun getReaderPageModeFlow(): Flow<String> {
        return context.dataStore.data.map { preferences ->
            preferences[READER_PAGE_MODE_KEY] ?: "single_page"
        }
    }

    /**
     * 设置工具栏显示模式
     * 
     * 调用位置：ReaderScreen.onToolbarModeChange()
     * @param mode "full"=正常显示, "page_only"=仅显示页码, "hidden"=完全隐藏
     */
    suspend fun setReaderToolbarMode(mode: String) {
        context.dataStore.edit { preferences ->
            preferences[READER_TOOLBAR_MODE_KEY] = mode
        }
    }

    /**
     * 观察工具栏显示模式变化（Flow）
     * 
     * 调用位置：ReaderViewModel.initialize()
     * @return Flow<String>（默认 "full"）
     */
    fun getReaderToolbarModeFlow(): Flow<String> {
        return context.dataStore.data.map { preferences ->
            preferences[READER_TOOLBAR_MODE_KEY] ?: "full"
        }
    }

    // ── 数据库映射管理（db文件名 → 库文件夹URI）─────────────────────────

    /**
     * 保存数据库文件名与库文件夹 URI 的映射关系
     *
     * 调用位置：
     * - AppContainer.init() - 初始化时记录当前库
     * - AppContainer.switchLibrary() - 切换库时记录
     *
     * @param dbName 数据库文件名（如 "pdf_manager_12345.db"）
     * @param libraryUri 库文件夹 SAF URI
     */
    suspend fun saveDatabaseUriMapping(dbName: String, libraryUri: String) {
        val map = getAllDatabaseMappings().toMutableMap()
        map[dbName] = libraryUri
        context.dataStore.edit { prefs ->
            prefs[DATABASE_MAP_KEY] = com.google.gson.Gson().toJson(map)
        }
    }

    /**
     * 获取全部数据库映射
     *
     * @return Map<dbFileName, libraryUri>（可能为空）
     */
    suspend fun getAllDatabaseMappings(): Map<String, String> {
        val json = context.dataStore.data.first()[DATABASE_MAP_KEY] ?: return emptyMap()
        return try {
            @Suppress("UNCHECKED_CAST")
            com.google.gson.Gson().fromJson(json, Map::class.java) as? Map<String, String> ?: emptyMap()
        } catch (e: Exception) {
            emptyMap()
        }
    }

    /**
     * 移除一条数据库映射
     *
     * @param dbName 要移除的数据库文件名
     */
    suspend fun removeDatabaseUriMapping(dbName: String) {
        val map = getAllDatabaseMappings().toMutableMap()
        map.remove(dbName)
        context.dataStore.edit { prefs ->
            prefs[DATABASE_MAP_KEY] = com.google.gson.Gson().toJson(map)
        }
    }
}
