package com.example.pdfmanager.data.repository

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.example.pdfmanager.data.local.FileScanner
import com.example.pdfmanager.data.local.PdfFileDao
import com.example.pdfmanager.data.local.PdfManagerDatabase
import com.example.pdfmanager.data.local.PdfTagDao
import com.example.pdfmanager.data.local.PreferencesManager
import com.example.pdfmanager.data.local.ThumbnailGenerator
import com.example.pdfmanager.data.model.PdfFile
import com.example.pdfmanager.data.model.PdfFileEntity
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.sync.Semaphore


/**
 * PDF 数据仓库（核心数据层）
 * 
 * 功能说明：
 * 1. 协调 FileScanner（文件扫描）和 Room 数据库（数据持久化）
 * 2. 提供 StateFlow<List<PdfFile>> 供 UI 层观察（响应式数据流）
 * 3. 实现增量更新逻辑（避免全量扫描，提升性能）
 * 4. 提供 PDF 文件操作方法（增删改查、搜索、筛选）
 * 5. 管理缩略图生成状态
 * 
 * 使用示例：
 * ```kotlin
 * // 在 ViewModel 中获取实例
 * val pdfRepository = AppContainer.pdfRepository
 * 
 * // 观察文件列表变化
 * pdfRepository.pdfFiles.collect { files ->
 *     // 更新 UI
 * }
 * 
 * // 扫描库文件夹
 * viewModelScope.launch {
 *     pdfRepository.scanLibrary()
 * }
 * ```
 * 
 * 数据流架构：
 * ```
 * FileScanner（扫描文件）
 *     ↓
 * PdfRepository._pdfFiles（内存缓存，StateFlow）
 *     ↓
 * Room 数据库（持久化）
 *     ↓
 * UI 层（collectAsStateWithLifecycle）
 * ```
 * 
 * 依赖关系：
 * - 依赖：FileScanner（文件扫描）、PreferencesManager（设置存储）、
 *          SearchIndexRepository（搜索索引）、PdfFileDao（数据库访问）、
 *          PdfTagDao（标签关系访问）
 * - 被依赖：AllFilesViewModel、DetailViewModel、FavoritesViewModel、SettingsViewModel
 * 
 * 线程安全：
 * - 所有数据库操作使用 Dispatchers.IO
 * - StateFlow 的更新使用 atomic 操作
 * - 并发扫描使用 Semaphore 限制并发数
 * 
 * @author PDF Manager Development Team
 * @version 2.0
 * @since 2024-01-01
 */
class PdfRepository(
    private val context: Context,
    private val fileScanner: FileScanner,
    private val preferencesManager: PreferencesManager,
    private val searchIndexRepository: SearchIndexRepository,
    private val pdfFileDao: PdfFileDao,
    private val pdfTagDao: PdfTagDao
) {
    companion object {
        /**
         * 日志标签
         * 用途：在 Logcat 中过滤 PdfRepository 的日志
         */
        private const val TAG = "PdfRepository"
    }
    
    // ── 内存缓存（StateFlow，可观察）────────────────────────────────────
    
    /**
     * PDF 文件列表（可变状态）
     * 用途：内存缓存，存储当前库文件夹的所有 PDF 文件
     * 更新时机：scanLibrary()、quickIncrementalScan()、incrementalScan()、addFile()、removeFile()
     */
    private val _pdfFiles = MutableStateFlow<List<PdfFile>>(emptyList())
    
    /**
     * PDF 文件列表（不可变状态，供 UI 层观察）
     * 
     * 观察位置：
     * - AllFilesViewModel - 更新文件列表 UI
     * - DetailViewModel - 获取单个文件详情
     * - FavoritesViewModel - 获取收藏文件列表
     * 
     * 使用示例：
     * ```kotlin
     * // 在 ViewModel 中观察
     * viewModelScope.launch {
     *     pdfRepository.pdfFiles.collect { files ->
     *         _uiState.value = files
     *     }
     * }
     * ```
     */
    val pdfFiles: StateFlow<List<PdfFile>> = _pdfFiles.asStateFlow()
    
    // ── 扫描进度（StateFlow，可观察）────────────────────────────────────
    
    /**
     * 扫描进度（可变状态）
     * 用途：在扫描过程中实时更新进度（已扫描数量、当前文件名）
     * 更新时机：scanLibrary() 执行期间
     */
    private val _scanProgress = MutableStateFlow<ScanProgress?>(null)
    
    /**
     * 扫描进度（不可变状态，供 UI 层观察）
     * 
     * 观察位置：
     * - AllFilesViewModel - 转发给 AllFilesScreen 显示进度条
     * - AllFilesScreen - 显示扫描进度（如"已扫描 50 个文件"）
     */
    val scanProgress: StateFlow<ScanProgress?> = _scanProgress.asStateFlow()
    
    /**
     * 扫描进度数据类
     * 
     * @property scannedCount 已扫描的文件数量
     * @property currentFileName 当前正在扫描的文件名（用于显示"正在扫描 XXX.pdf"）
     */
    data class ScanProgress(
        val scannedCount: Int,
        val currentFileName: String = ""
    )
    
    /**
     * 增量扫描结果数据类
     * 
     * 使用场景：
     * - quickIncrementalScan() 返回此对象，通知 UI 扫描结果
     * - AllFilesViewModel.refresh() 根据 hasChanges 决定是否需要刷新 UI
     * 
     * @property hasChanges 是否有变化（true 表示文件列表发生变化）
     * @property addedCount 新增文件数量
     * @property deletedCount 删除文件数量
     * @property movedCount 移动文件数量（保留原标签和备注）
     */
    data class ScanResult(
        val hasChanges: Boolean,
        val addedCount: Int = 0,
        val deletedCount: Int = 0,
        val movedCount: Int = 0
    )
    
    // ── 扫描库文件夹（全量扫描）────────────────────────────────────
    
    /**
     * 扫描库文件夹，更新 PDF 文件列表（全量扫描）
     * 
     * 功能说明：
     * 1. 从 PreferencesManager 获取库文件夹 URI
     * 2. 调用 FileScanner.scanLibrary() 递归扫描（优先 File API，兜底 SAF）
     * 3. 增量更新 _pdfFiles（每 5 个更新一次 UI，避免频繁重组）
     * 4. 持久化到 Room 数据库
     * 5. 更新扫描进度（_scanProgress）
     * 
     * 调用位置：
     * - AllFilesViewModel.initialize() - 首次启动时调用（Room 无数据时）
     * - AllFilesViewModel.refresh() - 用户手动刷新时调用
     * - SettingsViewModel - "重新扫描"按钮点击时调用
     * 
     * 使用场景：
     * - 首次绑定库文件夹后，需要全量扫描
     * - 用户手动下拉刷新
     * - 怀疑文件列表不准确时，强制重新扫描
     * 
     * 性能优化：
     * - 增量更新 UI（每 5 个文件更新一次）
     * - 使用 Dispatchers.IO 在后台线程执行
     * - 扫描完成后批量写入 Room 数据库
     * 
     * @return 扫描到的文件数量（0 表示库文件夹未绑定或扫描失败）
     */
    suspend fun scanLibrary(): Int = withContext(Dispatchers.IO) {
        val libraryUriString = preferencesManager.getLibraryUri()
            ?: return@withContext 0
        val libraryUri = Uri.parse(libraryUriString)
        
        Log.d(TAG, "开始扫描库文件夹: $libraryUri")
        
        // 重置进度
        _scanProgress.value = ScanProgress(0, "")
        
        // 清空现有文件列表（开始增量显示）
        _pdfFiles.value = emptyList()
        
        val scannedFiles = mutableListOf<PdfFile>()
        var updateCounter = 0
        
        // 调用 scanLibrary，通过 onProgress 回调增量收集文件
        fileScanner.scanLibrary(libraryUri) { scannedCount, currentFileName, pdfFile ->
            // 更新进度
            _scanProgress.value = ScanProgress(scannedCount, currentFileName)
            // 增量更新文件列表（每 5 个更新一次 UI，避免频繁重组）
            if (pdfFile != null) {
                scannedFiles.add(pdfFile)
                updateCounter++
                if (updateCounter % 5 == 0) {
                    _pdfFiles.value = scannedFiles.sortedBy { it.name.lowercase() }
                }
            }
        }
        
        // 最后再更新一次（确保全部显示）
        _pdfFiles.value = scannedFiles.sortedBy { it.name.lowercase() }
        
        // 持久化到 Room 数据库
        saveToRoom(scannedFiles)
        
        // 清除进度
        _scanProgress.value = null
        
        Log.d(TAG, "扫描完成，共 ${scannedFiles.size} 个文件")
        return@withContext scannedFiles.size
    }
    
    // ── 快速增量扫描（用于"扫描库文件夹"按钮）────────────────────────────
    
    /**
     * 快速增量扫描（用于"扫描库文件夹"按钮和 onResume 触发）
     * 
     * 功能说明：
     * 1. 使用 scanLibraryFast() 快速列出所有 PDF 文件（不生成缩略图）
     * 2. 与内存中的列表对比，检测新增/删除/移动
     * 3. PDF 文件是只读的，不检查文件内容变化
     * 4. 速度远快于全量扫描（避免重新读取 .meta 文件）
     * 
     * 检测逻辑：
     * - 新增：当前文件列表中不存在该 ID，且按文件名匹配失败
     * - 删除：数据库中有，但文件夹里没有（排除已匹配为"移动"的文件）
     * - 移动：ID 不匹配，但文件名匹配（保留旧文件的元数据）
     * 
     * 调用位置：
     * - AllFilesViewModel.incrementalScan() - onResume 时调用
     * - AllFilesViewModel.refresh() - 用户手动刷新时调用
     * - SettingsViewModel.onClickRescan() - "重新扫描"按钮点击时调用
     * 
     * 使用场景：
     * - 用户从其他应用添加了 PDF 文件
     * - 用户删除了 PDF 文件
     * - 用户移动了 PDF 文件到其他子文件夹
     * 
     * 性能对比：
     * - 全量扫描：扫描 + 读取 .meta + 写入 Room（约 2-5 秒/100 个文件）
     * - 快速增量扫描：仅列出文件 + 内存对比（约 0.5-1 秒/100 个文件）
     * 
     * @return ScanResult 包含是否有变化、新增/删除/移动数量
     */
    suspend fun quickIncrementalScan(): ScanResult = withContext(Dispatchers.IO) {
        val libraryUriString = preferencesManager.getLibraryUri()
            ?: return@withContext ScanResult(false)
        val libraryUri = Uri.parse(libraryUriString)

        // 1. 快速列出当前所有 PDF 文件
        val currentFiles = fileScanner.scanLibraryFast(libraryUri)
        val oldFiles = _pdfFiles.value

        if (oldFiles.isEmpty()) {
            // 内存中无数据（不应发生，因为 restoreFromRoom 已恢复），降级为全量扫描
            Log.w(TAG, "quickIncrementalScan: 内存无数据，降级为全量扫描")
            val scannedFiles = fileScanner.scanLibrary(libraryUri)
            val updatedFiles = mergeFileLists(emptyList(), scannedFiles)
            _pdfFiles.value = updatedFiles.sortedBy { it.name.lowercase() }
            saveToRoom(updatedFiles)
            return@withContext ScanResult(true, addedCount = updatedFiles.size, deletedCount = 0, movedCount = 0)
        }

        val oldMap = oldFiles.associateBy { it.id }
        val currentIdSet = currentFiles.map { it.id }.toSet()
        val mergedList = mutableListOf<PdfFile>()
        val matchedAsMovedOldIds = mutableSetOf<String>()  // 记录已被匹配为"移动"的旧文件 ID
        var hasChanges = false
        var addedCount = 0
        var movedCount = 0

        // 2. 遍历当前文件，检测新增/移动
        for (currentFile in currentFiles) {
            val oldFile = oldMap[currentFile.id]
            if (oldFile != null) {
                // ID 匹配：文件未变化，保留旧对象（含 tags/notes/thumbnailPath）
                mergedList.add(oldFile)
            } else {
                // ID 不匹配：可能是新文件，也可能是移动的文件
                // 尝试按文件名匹配（检测移动）
                val candidateOldFile = oldFiles.find {
                    it.name == currentFile.name
                    && it.id !in matchedAsMovedOldIds
                }
                if (candidateOldFile != null) {
                    // ✅ 判定为"移动文件"：直接更新数据库记录的 ID 和 URI，保留所有元数据
                    matchedAsMovedOldIds.add(candidateOldFile.id)
                    Log.d(TAG, "quickIncrementalScan: 移动文件 ${currentFile.name}, 旧lastReadPage=${candidateOldFile.lastReadPage}, 旧tags=${candidateOldFile.tags.size}")
                    // 更新 pdf_files：新 ID + 新 URI，保留 notes/lastReadPage/isFavorite 等
                    pdfFileDao.updateIdAndUri(candidateOldFile.id, currentFile.id, currentFile.uri.toString())
                    // 同步更新 pdf_tags 中的 URI 指向，使标签跟随文件移动
                    pdfTagDao.updatePdfFileUri(candidateOldFile.uri.toString(), currentFile.uri.toString())
                    Log.d(TAG, "quickIncrementalScan: 已更新文件记录 ID/URI: ${currentFile.name}")
                    
                    // 内存中同样使用旧文件的元数据 + 新 ID/URI
                    val movedFile = candidateOldFile.copy(
                        id = currentFile.id,
                        uri = currentFile.uri,
                        displayName = currentFile.displayName,
                        size = currentFile.size,
                        lastModified = currentFile.lastModified
                    )
                    Log.d(TAG, "quickIncrementalScan: copy后lastReadPage=${movedFile.lastReadPage}, tags=${movedFile.tags.size}")
                    mergedList.add(movedFile)
                    hasChanges = true
                    movedCount++
                    Log.d(TAG, "quickIncrementalScan: 检测到移动文件: ${currentFile.name}")
                } else {
                    // 真正的新文件：直接添加（tags/notes 为空）
                    mergedList.add(currentFile)
                    hasChanges = true
                    addedCount++
                    Log.d(TAG, "quickIncrementalScan: 新增文件 ${currentFile.displayName}")
                }
            }
        }

        // 3. 检查是否有文件被删除（数据库中有，但文件夹里没有）
        // 排除已匹配为移动的文件（它们不是被删除，只是移动了）
        val deletedFiles = oldFiles.filter {
            it.id !in currentIdSet && it.id !in matchedAsMovedOldIds
        }
        val deletedCount = deletedFiles.size
        if (deletedCount > 0) {
            hasChanges = true
            Log.d(TAG, "quickIncrementalScan: 发现 $deletedCount 个文件被删除，准备从数据库删除")
            
            // 从 Room 数据库删除这些文件
            for (deletedFile in deletedFiles) {
                try {
                    pdfFileDao.deleteById(deletedFile.id)
                    // 同时删除关联的标签
                    pdfTagDao.deleteByPdfFileUri(deletedFile.uri.toString())
                    Log.d(TAG, "quickIncrementalScan: 已从数据库删除 ${deletedFile.displayName}")
                } catch (e: Exception) {
                    Log.e(TAG, "quickIncrementalScan: 删除 ${deletedFile.displayName} 失败", e)
                }
            }
        }

        if (hasChanges || mergedList.size != oldFiles.size) {
            _pdfFiles.value = mergedList.sortedBy { it.name.lowercase() }
            saveToRoom(mergedList)
            Log.d(TAG, "quickIncrementalScan: 完成，共 ${mergedList.size} 个文件，新增 $addedCount 个，删除 $deletedCount 个，移动 $movedCount 个")
            return@withContext ScanResult(true, addedCount = addedCount, deletedCount = deletedCount, movedCount = movedCount)
        }

        Log.d(TAG, "quickIncrementalScan: 无变化")
        return@withContext ScanResult(false)
    }
    
    // ── 增量扫描（用于 FileObserver 或 onResume 触发）────────────────────────────
    
    /**
     * 增量扫描（用于 FileObserver 或 onResume 触发）
     * 
     * 功能说明：
     * 1. 如果指定了 changedFilePaths，只更新变化的文件（高效）
     * 2. 如果未指定 changedFilePaths，执行全量对比（降级方案）
     * 3. 只更新变化的文件，不重新扫描整个库
     * 
     * 调用位置：
     * - FileObserver.onEvent() - 文件变化时自动触发
     * - AllFilesViewModel.onResume() - Activity 恢复时调用
     * 
     * 使用场景：
     * - 用户在文件管理器中删除/重命名了 PDF 文件
     * - FileObserver 检测到文件变化
     * 
     * @param changedFilePaths 变化的文件路径列表（可选，如果为空则全量对比）
     * @return 是否有变化（true 表示文件列表已更新）
     */
    suspend fun incrementalScan(changedFilePaths: List<String>? = null): Boolean = withContext(Dispatchers.IO) {
        val libraryUriString = preferencesManager.getLibraryUri()
            ?: return@withContext false
        val libraryUri = Uri.parse(libraryUriString)
        
        val oldFiles = _pdfFiles.value
        
        if (changedFilePaths == null) {
            // 全量对比：重新扫描整个库
            val scannedFiles = fileScanner.scanLibrary(libraryUri)
            val updatedFiles = mergeFileLists(oldFiles, scannedFiles)
            
            if (updatedFiles != oldFiles) {
                _pdfFiles.value = updatedFiles
                saveToRoom(updatedFiles)
                return@withContext true
            }
        } else {
            // 增量更新：只更新变化的文件
            val updatedList = oldFiles.toMutableList()
            var hasChanges = false
            
            for (changedPath in changedFilePaths) {
                // 检查是新增/修改还是删除
                val file = java.io.File(changedPath)
                if (file.exists() && file.isFile && file.extension.equals("pdf", ignoreCase = true)) {
                    // 新增或修改
                    val scannedFile = fileScanner.scanSingleFile(Uri.fromFile(file))
                    if (scannedFile != null) {
                        val index = updatedList.indexOfFirst { it.id == scannedFile.id }
                        if (index >= 0) {
                            updatedList[index] = scannedFile
                        } else {
                            updatedList.add(scannedFile)
                        }
                        hasChanges = true
                    }
                } else {
                    // 删除（需要从 URI 生成 ID）
                    val uri = Uri.fromFile(file)
                    val id = UUID.nameUUIDFromBytes(uri.toString().toByteArray()).toString()
                    val index = updatedList.indexOfFirst { it.id == id }
                    if (index >= 0) {
                        updatedList.removeAt(index)
                        hasChanges = true
                    }
                }
            }
            
            if (hasChanges) {
                _pdfFiles.value = updatedList.sortedBy { it.name.lowercase() }
                saveToRoom(updatedList)
                return@withContext true
            }
        }
        
        return@withContext false
    }
    
    // ── 私有方法 ─────────────────────────────────────
    
    /**
     * 合并文件列表（增量更新）
     * 
     * 功能说明：
     * 1. 遍历新扫描的文件列表（newList）
     * 2. 如果 ID 匹配（文件未变化），保留旧对象（包含 tags/notes/thumbnailPath）
     * 3. 如果 ID 不匹配（新文件），使用新对象
     * 4. 按文件名排序（忽略大小写）
     * 
     * 调用位置：
     * - PdfRepository.incrementalScan() - 增量扫描时调用
     * 
     * 使用场景：
     * - 增量扫描后，合并新旧文件列表
     * - 保留旧文件的元数据（标签、备注、缩略图路径）
     * 
     * @param oldList 旧文件列表（来自内存缓存）
     * @param newList 新扫描的文件列表（来自 FileScanner）
     * @return 合并后的文件列表（按文件名排序）
     */
    private fun mergeFileLists(oldList: List<PdfFile>, newList: List<PdfFile>): List<PdfFile> {
        val oldMap = oldList.associateBy { it.id }
        val mergedList = mutableListOf<PdfFile>()
        
        for (newFile in newList) {
            val oldFile = oldMap[newFile.id]
            if (oldFile != null) {
                // ID 匹配：保留旧对象（包含 tags/notes/thumbnailPath）
                mergedList.add(oldFile)
            } else {
                // 文件是新的，使用新对象
                mergedList.add(newFile)
            }
        }
        
        // 按文件名排序
        return mergedList.sortedBy { it.name.lowercase() }
    }
    
    // ── 文件查询方法 ─────────────────────────────────────
    
    /**
     * 获取单个 PDF 文件（根据 ID）
     * 
     * 功能说明：
     * 1. 从内存列表（_pdfFiles）中查找
     * 2. 返回 PdfFile 对象（包含 tags、notes、thumbnailPath 等完整信息）
     * 
     * 调用位置：
     * - DetailViewModel.initialize() - 初始化详情页时调用
     * - DetailViewModel.updateNotes() - 保存备注后刷新时调用
     * - ShareViewModel - 获取文件信息用于分享
     * 
     * 使用场景：
     * - 用户点击文件列表中的某个 PDF，跳转到详情页
     * - 需要获取单个文件的完整信息
     * 
     * @param id PDF 文件 ID（基于 URI 确定性生成）
     * @return PdfFile 对象（如果未找到则返回 null）
     */
    suspend fun getFileById(id: String): PdfFile? = withContext(Dispatchers.IO) {
        return@withContext _pdfFiles.value.find { it.id == id }
    }
    
    // ── 文件更新方法 ─────────────────────────────────────
    
    /**
     * 更新 PDF 文件（更新 Room 数据库和内存缓存）
     * 
     * 功能说明：
     * 1. 更新内存中的文件列表（_pdfFiles）
     * 2. 同时保存到 Room 数据库（持久化）
     * 3. 用于保存用户编辑的 notes、lastReadPage、isFavorite 等字段
     * 
     * 调用位置：
     * - DetailViewModel.saveNotes() - 保存备注时调用
     * - DetailViewModel.updateLastReadPage() - 更新最后阅读页码时调用
     * - DetailViewModel.toggleFavorite() - 切换收藏状态时调用
     * 
     * 使用场景：
     * - 用户在详情页编辑备注
     * - 用户阅读 PDF 后返回，需要记录最后阅读页码
     * - 用户点击收藏按钮
     * 
     * @param pdfFile 更新后的 PdfFile 对象（包含最新 notes / lastReadPage）
     */
    suspend fun updateFile(pdfFile: PdfFile): Unit = withContext(Dispatchers.IO) {
        Log.d(TAG, "updateFile: 开始更新文件 ${pdfFile.displayName}, uri=${pdfFile.uri}, lastReadPage=${pdfFile.lastReadPage}")
        
        // 1. 更新内存中的文件列表
        val updatedList = _pdfFiles.value.toMutableList()
        val index = updatedList.indexOfFirst { it.id == pdfFile.id }
        if (index >= 0) {
            updatedList[index] = pdfFile
            _pdfFiles.value = updatedList
        }
        
        // 2. 同时保存到 Room 数据库
        try {
            val entity = PdfFileEntity.fromPdfFile(pdfFile, "")
            pdfFileDao.update(entity)
            Log.d(TAG, "updateFile: 已保存到数据库，lastReadPage=${pdfFile.lastReadPage}")
        } catch (e: Exception) {
            Log.e(TAG, "updateFile: 保存到数据库失败", e)
        }
        
        Log.d(TAG, "updateFile: 更新完成")
    }
    
    // ── 缩略图相关方法 ─────────────────────────────────────
    
    /**
     * 更新单个 PDF 文件的缩略图路径和生成状态（仅更新内存，不写磁盘）
     * 
     * 功能说明：
     * 1. 更新内存缓存中指定文件的 thumbnailPath 和 thumbnailGenerated 字段
     * 2. 用于 ThumbnailGenerationService.flushBatch() 调用，实现渐进式刷新 UI
     * 3. 不写 Room 数据库（避免频繁 IO，提升性能）
     * 
     * 调用位置：
     * - ThumbnailGenerationService.flushBatch() - 批量生成缩略图后调用
     * 
     * 使用场景：
     * - 后台生成缩略图完成后，渐进式更新 UI（用户可以看到缩略图一张张出现）
     * 
     * @param pdfId PDF 文件 ID
     * @param thumbnailPath 缩略图相对路径（相对于应用私有目录）
     */
    fun updateThumbnailPath(pdfId: String, thumbnailPath: String) {
        val updatedList = _pdfFiles.value.toMutableList()
        val index = updatedList.indexOfFirst { it.id == pdfId }
        if (index >= 0) {
            val updatedFile = updatedList[index].copy(
                thumbnailPath = thumbnailPath,
                thumbnailGenerated = 1  // ← 同时更新标志位
            )
            updatedList[index] = updatedFile
            _pdfFiles.value = updatedList
            Log.d(TAG, "updateThumbnailPath: 已更新 $pdfId 的缩略图路径: $thumbnailPath")
        }
    }
    
    /**
     * 为文件列表中的每个文件检查并设置缩略图路径（同步，用于扫描后立即执行）
     * 
     * @param files 文件列表
     * @return 设置了缩略图路径的文件列表
     */
    suspend fun checkAndSetThumbnailPaths(files: List<PdfFile>): List<PdfFile> = withContext(Dispatchers.IO) {
        val updatedList = mutableListOf<PdfFile>()
        
        for (file in files) {
            if (file.thumbnailPath != null) {
                // 已经有缩略图路径，保留
                updatedList.add(file)
            } else {
                // 检查是否存在缩略图
                val thumbnailPath = ThumbnailGenerator.getThumbnailPath(context, file.uri)
                if (thumbnailPath != null) {
                    // 存在缩略图，设置路径
                    updatedList.add(file.copy(thumbnailPath = thumbnailPath))
                } else {
                    // 不存在缩略图，保留原文件
                    updatedList.add(file)
                }
            }
        }
        
        return@withContext updatedList
    }
    
    // ── 文件删除方法 ─────────────────────────────────────
    
    /**
     * 删除 PDF 文件（从文件系统、内存缓存和 Room 数据库中删除）
     * 
     * 功能说明：
     * 1. 尝试使用 File API 删除（如果是 file:// 协议）
     * 2. 如果 File API 失败，回退到 SAF API 删除
     * 3. 从内存列表（_pdfFiles）中移除
     * 4. 从搜索索引中移除
     * 5. 从 Room 数据库中移除
     * 
     * 调用位置：
     * - DetailViewModel.deleteFile() - 用户点击"删除"按钮时调用
     * - AllFilesViewModel.deleteSelectedFiles() - 多选删除时调用
     * 
     * 使用场景：
     * - 用户确认删除某个 PDF 文件
     * - 用户多选后批量删除
     * 
     * @param pdfFile 要删除的 PDF 文件对象
     */
    suspend fun deletePdfFile(pdfFile: PdfFile): Unit = withContext(Dispatchers.IO) {
        try {
            // 删除 PDF 文件
            val file = java.io.File(pdfFile.uri.path!!)
            val success = if (file.exists()) {
                file.delete()
            } else {
                // SAF 方式
                val docFile = DocumentFile.fromSingleUri(context, pdfFile.uri)
                docFile?.delete() ?: false
            }
            
            if (success) {
                // 从内存列表和搜索索引中移除
                removeFile(pdfFile.id)
                // 从 Room 数据库中移除
                deleteFromRoom(pdfFile.id)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete PDF file: ${pdfFile.displayName}", e)
        }
    }
    
    /**
     * 切换 PDF 文件的收藏状态
     * 
     * 功能说明：
     * 1. 复制 PdfFile 对象，反转 isFavorite 字段
     * 2. 调用 updateFile() 更新内存和数据库
     * 
     * 调用位置：
     * - DetailViewModel.toggleFavorite() - 用户点击收藏按钮时调用
     * - DetailScreen - 收藏按钮点击事件
     * 
     * 使用场景：
     * - 用户在详情页收藏/取消收藏 PDF 文件
     * 
     * @param pdfFile 要切换收藏状态的 PDF 文件对象
     */
    suspend fun toggleFavorite(pdfFile: PdfFile) = withContext(Dispatchers.IO) {
        val updatedPdfFile = pdfFile.copy(isFavorite = !pdfFile.isFavorite)
        updateFile(updatedPdfFile)
    }
    
    /**
     * 获取收藏的 PDF 文件列表
     * 
     * 功能说明：
     * 1. 从内存缓存（_pdfFiles）中过滤出 isFavorite = true 的文件
     * 2. 返回 List<PdfFile>（包含完整信息）
     * 
     * 调用位置：
     * - FavoritesViewModel.loadFavorites() - 加载收藏文件列表时调用
     * - FavoritesScreen - 显示收藏文件列表
     * 
     * 使用场景：
     * - 用户切换到"收藏"Tab
     * - 用户打开收藏页面
     * 
     * @return 收藏的 PDF 文件列表（按文件名排序）
     */
    suspend fun getFavoritePdfFiles(): List<PdfFile> = withContext(Dispatchers.IO) {
        return@withContext _pdfFiles.value.filter { it.isFavorite }
    }
    
    /**
     * 搜索 PDF 文件（按文件名、备注、标签搜索）
     * 
     * 功能说明：
     * 1. 如果 query 为空或空白，返回所有文件
     * 2. 搜索范围：文件名（name）、显示名（displayName）、备注（notes）、标签值（tags.value）
     * 3. 不区分大小写（使用 lowercase() 转换）
     * 4. 模糊匹配（使用 contains()）
     * 
     * 调用位置：
     * - SearchViewModel.search() - 用户搜索时调用
     * - SearchScreen - 搜索框文本变化时实时调用
     * 
     * 使用场景：
     * - 用户在搜索页输入关键词
     * - 需要快速定位某个 PDF 文件
     * 
     * 性能优化：
     * - 直接在内存中搜索（_pdfFiles），无需访问数据库
     * - 搜索延迟 < 10ms（即使 1000 个文件）
     * 
     * @param query 搜索关键词（不区分大小写）
     * @return 匹配的 PDF 文件列表（按文件名排序）
     */
    suspend fun searchPdfFiles(query: String): List<PdfFile> = withContext(Dispatchers.IO) {
        if (query.isBlank()) {
            return@withContext _pdfFiles.value
        }
        
        val lowerQuery = query.lowercase()
        return@withContext _pdfFiles.value.filter { pdfFile ->
            pdfFile.name.lowercase().contains(lowerQuery) ||
            pdfFile.displayName.lowercase().contains(lowerQuery) ||
            pdfFile.notes.lowercase().contains(lowerQuery) ||
            pdfFile.tags.any { it.value.lowercase().contains(lowerQuery) }
        }
    }
    
    /**
     * 获取库文件夹 URI（从 PreferencesManager 读取）
     * 
     * 功能说明：
     * 1. 从 PreferencesManager 读取库文件夹 URI
     * 2. 返回 String?（可能为 null，表示未绑定库文件夹）
     * 
     * 调用位置：
     * - AllFilesViewModel.initialize() - 检查库文件夹是否已绑定
     * - PdfRepository.scanLibrary() - 获取库文件夹 URI
     * - PdfRepository.quickIncrementalScan() - 获取库文件夹 URI
     * 
     * 使用场景：
     * - 应用启动时，检查是否需要显示引导页
     * - 扫描前，获取库文件夹 URI
     * 
     * @return 库文件夹 URI（String），如果未绑定则返回 null
     */
    suspend fun getLibraryUri(): String? {
        return preferencesManager.getLibraryUri()
    }
    
    /**
     * 清除所有数据（用于更换库文件夹）
     * 
     * 功能说明：
     * 1. 清空内存缓存（_pdfFiles）
     * 2. 不清除 Room 数据库（数据库会随库文件夹切换而切换）
     * 
     * 调用位置：
     * - AppContainer.switchLibrary() - 切换库文件夹时调用
     * - SettingsViewModel.onClickChangeLibrary() - 用户点击"更改库文件夹"时调用
     * 
     * 使用场景：
     * - 用户更换库文件夹
     * - 需要清空当前内存中的文件列表
     */
    fun clear() {
        _pdfFiles.value = emptyList()
    }
    
    /**
     * 更新缓存（用于标签管理页面同步内存数据）
     * 
     * 功能说明：
     * 1. 直接用新的文件列表替换内存缓存（_pdfFiles）
     * 2. 不写 Room 数据库（标签管理页面会单独保存）
     * 
     * 调用位置：
     * - TagManagerViewModel.onSave() - 标签管理页面保存后调用
     * 
     * 使用场景：
     * - 用户在标签管理页面编辑了标签值
     * - 需要同步更新内存中的文件列表（PDF 的 tags 字段）
     * 
     * @param updatedList 更新后的 PDF 文件列表
     */
    fun updateCache(updatedList: List<PdfFile>) {
        _pdfFiles.value = updatedList
    }

    /**
     * 获取所有 PDF 文件列表（拷贝）
     * 
     * 功能说明：
     * 1. 返回内存缓存（_pdfFiles）的拷贝（避免外部修改影响内存状态）
     * 2. 用于需要操作文件列表但不需要观察变化的场景
     * 
     * 调用位置：
     * - ShareViewModel - 获取所有文件用于分享
     * - SettingsViewModel - 获取所有文件用于批量操作
     * 
     * 使用场景：
     * - 需要遍历所有文件（如批量生成缩略图）
     * - 需要文件列表的快照（不随内存变化）
     * 
     * @return 所有 PDF 文件列表（新 List 实例）
     */
    fun getAllFiles(): List<PdfFile> {
        return _pdfFiles.value
    }

    /**
     * 获取所有 PDF 文件名（不含扩展名）
     * 
     * 功能说明：
     * 1. 从 Room 数据库读取所有文件名（name 字段）
     * 2. 不加载完整对象（避免内存占用）
     * 
     * 调用位置：
     * - ConversionViewModel - 检查转换后的文件是否重名
     * 
     * 使用场景：
     * - 重名检测（如 ZIP 转 PDF 时，检查是否已存在同名文件）
     * - 避免加载完整对象（只需要的文件名）
     * 
     * 性能优化：
     * - 只查询 name 列（不加载整个实体）
     * - 返回 List<String>（内存占用小）
     * 
     * @return 所有 PDF 文件名列表（不含扩展名，如 "document"）
     */
    suspend fun getAllFileNames(): List<String> = withContext(Dispatchers.IO) {
        return@withContext pdfFileDao.getAllFileNames()
    }

    /**
     * 增量添加单个 PDF 文件（用于 zip 转 PDF 等实时刷新场景）
     * 
     * 功能说明：
     * 1. 将新文件追加到 _pdfFiles 列表（按 id 去重）
     * 2. 如果已存在则替换为新对象（保留内存中的旧数据）
     * 3. 同步更新 SearchIndexRepository 索引（用于搜索）
     * 4. 同步更新 Room 数据库（持久化）
     * 
     * 调用位置：
     * - ConversionViewModel.onConversionComplete() - ZIP 转 PDF 完成后调用
     * - ShareViewModel.onCopyComplete() - 复制 PDF 完成后调用
     * 
     * 使用场景：
     * - 用户在转换页面完成 ZIP 转 PDF
     * - 需要实时刷新文件列表（用户可以看到新文件出现）
     * 
     * 性能优化：
     * - 只更新单个文件（避免全量扫描）
     * - 使用 update() 操作符（线程安全）
     * 
     * @param pdfFile 要添加的 PDF 文件（id 必须基于 URI 确定性生成）
     */
    suspend fun addFile(pdfFile: PdfFile) {
        _pdfFiles.update { currentList ->
            // 按 id 去重，如果已存在则替换为新对象
            val mutable = currentList.toMutableList()
            val index = mutable.indexOfFirst { it.id == pdfFile.id }
            if (index >= 0) {
                mutable[index] = pdfFile
            } else {
                mutable.add(pdfFile)
            }
            mutable.sortedBy { it.name.lowercase() }
        }
        // 同步更新搜索索引
        searchIndexRepository.update(pdfFile)
        // 同步更新 Room 数据库
        saveToRoom(listOf(pdfFile))
        Log.d(TAG, "addFile: 已添加 ${pdfFile.displayName} 到内存列表和搜索索引")
    }

    /**
     * 从内存列表和搜索索引中移除单个 PDF 文件
     * 
     * 功能说明：
     * 1. 从内存缓存（_pdfFiles）中移除指定 ID 的文件
     * 2. 从搜索索引（SearchIndexRepository）中移除
     * 3. 不写 Room 数据库（由调用方负责）
     * 
     * 调用位置：
     * - PdfRepository.deletePdfFile() - 删除文件后调用
     * - ConversionViewModel.onConversionComplete() - 转换完成后调用
     * 
     * 使用场景：
     * - 删除文件后，实时更新 UI
     * - 转换完成后，添加新文件到列表
     * 
     * @param id 要移除的 PDF 文件 ID
     */
    fun removeFile(id: String) {
        _pdfFiles.update { currentList ->
            currentList.filterNot { it.id == id }
        }
        searchIndexRepository.remove(id)
        Log.d(TAG, "removeFile: 已移除 id=$id 的文件")
    }
    
    /**
     * 后台生成缺失的缩略图（异步）
     * 
     * 功能说明：
     * 1. 在后台线程生成缩略图（使用 ThumbnailGenerator.generate()）
     * 2. 使用 Semaphore 限制并发数（默认 2，避免过多占用 CPU）
     * 3. 生成完成后，更新内存缓存中的 thumbnailPath 和 thumbnailGenerated 字段
     * 4. 不写 Room 数据库（避免频繁 IO，提升性能）
     * 
     * 调用位置：
     * - SettingsViewModel.onClickGenerateThumbnails() - 用户点击"生成缩略图"按钮时调用
     * 
     * 使用场景：
     * - 用户首次使用，需要为所有 PDF 生成缩略图
     * - 用户手动触发缩略图生成
     * 
     * 性能优化：
     * - 并发数限制为 2（避免过多占用 CPU）
     * - 使用 Semaphore 控制并发
     * - 渐进式更新 UI（用户可以看到缩略图一张张出现）
     * 
     * @param files 需要生成缩略图的文件列表
     * @param scope CoroutineScope 用于启动后台任务
     */
    fun generateMissingThumbnails(files: List<PdfFile>, scope: kotlinx.coroutines.CoroutineScope) {
        if (files.isEmpty()) return
        
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            Log.d(TAG, "后台生成缩略图：共 ${files.size} 个")
            
            val semaphore = kotlinx.coroutines.sync.Semaphore(2) // 并发数 2，避免过多占用 CPU
            
            val jobs = files.map { pdfFile ->
                launch {
                    semaphore.acquire()
                    try {
                        // 生成缩略图
                        val success = ThumbnailGenerator.generate(context, pdfFile.uri)
                        if (success) {
                            // 获取缩略图路径
                            val path = ThumbnailGenerator.getThumbnailPath(context, pdfFile.uri)
                            if (path != null) {
                                // 更新 PdfFile 对象的 thumbnailPath 字段
                                updateThumbnailPath(pdfFile.id, path)
                                Log.d(TAG, "缩略图生成完成：${pdfFile.displayName}")
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "生成缩略图失败：${pdfFile.displayName}", e)
                    } finally {
                        semaphore.release()
                    }
                }
            }
            
            jobs.joinAll()
            
            Log.d(TAG, "后台生成缩略图完成")
        }
    }

    // ── Room 数据库支持 ──────────────────────────────────────────────────

    /**
     * 从 Room 数据库恢复文件列表（冷启动时调用，避免全盘扫描）
     * 
     * 功能说明：
     * 1. 从 Room 数据库读取所有 PdfFileEntity
     * 2. 转换为 PdfFile 对象（包含 tags、notes、thumbnailPath 等）
     * 3. 更新内存缓存（_pdfFiles）
     * 4. 如果数据库为空，返回 null（触发全量扫描）
     * 
     * 调用位置：
     * - AllFilesViewModel.initialize() - 冷启动时调用
     * - AppContainer.switchLibrary() - 切换库文件夹后调用
     * 
     * 使用场景：
     * - 应用冷启动（进程被系统杀死后恢复）
     * - 切换库文件夹后，恢复新库的数据库
     * 
     * 性能优化：
     * - 避免全盘扫描（全盘扫描需要 2-5 秒/100 个文件）
     * - 从数据库恢复只需 0.1-0.3 秒/100 个文件
     * 
     * @return 恢复的文件列表（如果数据库为空则返回 null）
     */
    suspend fun restoreFromRoom(): List<PdfFile>? = withContext(Dispatchers.IO) {
        try {
            val entities = pdfFileDao.getAll()
            if (entities.isNotEmpty()) {
                val files = entities.map { it.toPdfFile() }
                _pdfFiles.value = files.sortedBy { it.name.lowercase() }
                Log.d(TAG, "restoreFromRoom: 恢复了 ${files.size} 个文件")
                files
            } else {
                Log.d(TAG, "restoreFromRoom: 数据库为空")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "restoreFromRoom: 失败", e)
            null
        }
    }

    /**
     * 保存指定文件列表到 Room 数据库
     * 
     * 功能说明：
     * 1. 将 List<PdfFile> 转换为 List<PdfFileEntity>
     * 2. 批量插入 Room 数据库（使用 REPLACE 策略）
     * 3. 不更新内存缓存（由调用方负责）
     * 
     * 调用位置：
     * - PdfRepository.scanLibrary() - 全量扫描后调用
     * - PdfRepository.quickIncrementalScan() - 增量扫描后调用
     * - PdfRepository.addFile() - 增量添加文件后调用
     * 
     * 使用场景：
     * - 扫描完成后，持久化文件列表
     * - 增量更新后，持久化变化的文件
     * 
     * 性能优化：
     * - 批量插入（insertAll），减少数据库操作次数
     * - 使用 REPLACE 策略（冲突时替换）
     * 
     * @param files 要保存的 PDF 文件列表
     */
    suspend fun saveToRoom(files: List<PdfFile>) = withContext(Dispatchers.IO) {
        try {
            val entities = files.map { PdfFileEntity.fromPdfFile(it, "") }
            pdfFileDao.insertAll(entities)
            Log.d(TAG, "saveToRoom: 保存了 ${files.size} 个文件")
        } catch (e: Exception) {
            Log.e(TAG, "saveToRoom: 失败", e)
        }
    }

    /**
     * 从 Room 数据库中删除指定文件
     * 
     * 功能说明：
     * 1. 从 pdf_files 表中删除指定 ID 的记录
     * 2. 同时删除关联的标签（pdf_tags 表）
     * 3. 不更新内存缓存（由调用方负责）
     * 
     * 调用位置：
     * - PdfRepository.deletePdfFile() - 删除文件后调用
     * - PdfRepository.quickIncrementalScan() - 检测到文件被删除后调用
     * 
     * 使用场景：
     * - 用户删除 PDF 文件
     * - 增量扫描发现文件被删除
     * 
     * @param fileId 要删除的 PDF 文件 ID
     */
    private suspend fun deleteFromRoom(fileId: String) = withContext(Dispatchers.IO) {
        try {
            pdfFileDao.deleteById(fileId)
            // 同时删除关联的标签
            pdfTagDao.deleteByPdfFileUri(fileId)
            Log.d(TAG, "deleteFromRoom: 已删除 id=$fileId 的文件")
        } catch (e: Exception) {
            Log.e(TAG, "deleteFromRoom: 失败", e)
        }
    }

    /**
     * 根据筛选条件查询符合条件的 PDF URI 集合
     * 
     * 功能说明：
     * 1. 如果 includeNoTag = true，返回所有没有标签的 PDF
     * 2. 如果 selectedTagKeys 为空，返回空集合
     * 3. 按类别分组，根据 filterLogic（AND/OR）计算交集或并集
     * 
     * 调用位置：
     * - AllFilesViewModel.applyCurrentFilter() - 应用筛选条件时调用
     * 
     * 使用场景：
     * - 用户在筛选页选择标签，确认后应用筛选
     * - 需要高亮显示符合条件的 PDF 文件
     * 
     * 筛选逻辑：
     * - AND：文件必须满足所有类别中的至少一个标签
     * - OR：文件满足任一类别中的至少一个标签
     * - 同类别内多标签始终为"或"
     * 
     * @param savedFilter 筛选条件（selectedTagKeys / filterLogic / includeNoTag）
     * @return 符合条件的 pdfFileUri 集合（用于过滤文件列表）
     */
    suspend fun getFileUrisByFilter(savedFilter: com.example.pdfmanager.data.model.SavedFilter): Set<String> = withContext(Dispatchers.IO) {
        if (savedFilter.includeNoTag) {
            // "无标签"：返回所有在 pdf_tags 表中没有记录的 PDF
            return@withContext pdfTagDao.getPdfUrisWithNoTags().toSet()
        }

        val selectedTagKeys = savedFilter.selectedTagKeys
        if (selectedTagKeys.isEmpty()) {
            return@withContext emptySet()
        }

        // 按类别分组：categoryId -> Set<tagValue>
        val selectedByCategory = mutableMapOf<String, MutableSet<String>>()
        for (key in selectedTagKeys) {
            val parts = key.split(":", limit = 2)
            if (parts.size == 2) {
                selectedByCategory.getOrPut(parts[0]) { mutableSetOf() }.add(parts[1])
            }
        }

        if (selectedByCategory.isEmpty()) {
            return@withContext emptySet()
        }

        return@withContext if (savedFilter.filterLogic == com.example.pdfmanager.data.model.FilterLogic.AND) {
            // 且：文件必须满足所有类别中的至少一个标签
            val iter = selectedByCategory.entries.iterator()
            var result: Set<String>? = null
            for ((categoryId, tagValues) in iter) {
                val uris = mutableSetOf<String>()
                for (tagValue in tagValues) {
                    uris.addAll(pdfTagDao.getPdfUrisByCategoryAndTag(categoryId, tagValue))
                }
                if (result == null) {
                    result = uris
                } else {
                    result = result.intersect(uris)
                }
                if (result.isEmpty()) break
            }
            result ?: emptySet()
        } else {
            // 或：文件满足任一类别中的至少一个标签
            val result = mutableSetOf<String>()
            for ((categoryId, tagValues) in selectedByCategory) {
                for (tagValue in tagValues) {
                    result.addAll(pdfTagDao.getPdfUrisByCategoryAndTag(categoryId, tagValue))
                }
            }
            result
        }
    }

    // ── 缩略图相关方法 ─────────────────────────────────────

    /**
     * 获取所有未生成缩略图的文件
     * 
     * 功能说明：
     * 1. 从 Room 数据库查询 thumbnailGenerated = 0 的文件
     * 2. 转换为 PdfFile 对象
     * 
     * 调用位置：
     * - SettingsViewModel.onClickGenerateThumbnails() - 用户点击"生成缩略图"按钮时调用
     * 
     * 使用场景：
     * - 首次使用，需要为所有 PDF 生成缩略图
     * - 用户手动触发缩略图生成
     * 
     * @return 未生成缩略图的 PDF 文件列表
     */
    suspend fun getFilesWithoutThumbnail(): List<PdfFile> = withContext(Dispatchers.IO) {
        val entities = pdfFileDao.getFilesWithoutThumbnail()
        return@withContext entities.map { it.toPdfFile() }
    }

    /**
     * 更新文件的缩略图生成状态
     * 
     * 功能说明：
     * 1. 更新 Room 数据库中指定文件的 thumbnailGenerated 字段
     * 2. 不更新内存缓存（由调用方负责）
     * 
     * 调用位置：
     * - ThumbnailGenerationService.onGenerationComplete() - 缩略图生成完成后调用
     * 
     * 使用场景：
     * - 缩略图生成成功（status = 1）
     * - 缩略图生成失败（status = 2）
     * 
     * @param fileId 文件 ID
     * @param status 状态值（0=未生成, 1=已生成, 2=生成失败）
     */
    suspend fun updateThumbnailGeneratedStatus(fileId: String, status: Int) = withContext(Dispatchers.IO) {
        pdfFileDao.updateThumbnailGenerated(fileId, status)
    }
}
