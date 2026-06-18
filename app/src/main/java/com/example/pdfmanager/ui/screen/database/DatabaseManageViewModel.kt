package com.example.pdfmanager.ui.screen.database

import android.content.Context
import android.net.Uri
import android.util.Log
import android.os.Environment
import android.provider.DocumentsContract
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.pdfmanager.data.local.PdfManagerDatabase
import com.example.pdfmanager.data.local.PreferencesManager
import com.example.pdfmanager.data.repository.AppContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Log 标签，用于 DatabaseManageViewModel 中的日志输出 */
private const val TAG = "DatabaseManageViewModel"

/**
 * 【单个数据库文件的信息数据类】
 *
 * 用于在 UI 列表中展示每个数据库文件的详细信息。
 * 数据来源于扫描 databases/ 目录下的 .db 文件 + PreferencesManager 中的映射记录。
 *
 * @property dbFileName  数据库文件名，例如 "pdf_manager_12345.db"
 * @property libraryUri  从映射表中查到的库文件夹 URI，null 表示没有对应映射（残留数据库）
 * @property fileSize    数据库文件的大小（字节数）
 * @property isCurrent   是否为当前正在使用的数据库
 * @property displayPath 如果 libraryUri 已知，则解析为人可读的路径名称；否则为 null
 */
data class DatabaseInfo(
    val dbFileName: String,
    val libraryUri: String?,
    val fileSize: Long,
    val isCurrent: Boolean,
    val displayPath: String?
)

/**
 * 【数据库管理 ViewModel】
 *
 * ── 功能说明 ──
 * 本 ViewModel 负责管理应用产生的所有 Room 数据库文件，提供以下核心能力：
 *   1. 扫描 databases/ 目录，列出所有已有的 .db 文件
 *   2. 读取 PreferencesManager 中的 dbFileName ↔ libraryUri 映射表
 *   3. 比对当前使用的数据库 URI，标记"当前使用中"状态
 *   4. 切换到另一个数据库（调用 AppContainer.switchLibrary()）
 *   5. 导出数据库文件到用户指定的外部位置（通过 SAF）
 *   6. 从外部导入数据库文件并绑定到指定的库文件夹 URI
 *
 * ── 使用场景 ──
 * 用户可能在多个库文件夹之间切换，每个库文件夹对应一个独立的 Room 数据库文件。
 * 此 ViewModel 让用户能够管理这些数据库文件：查看列表、切换、导出备份、导入恢复。
 *
 * ── 工作流程 ──
 * 1. loadDatabases() 在初始化时调用
 * 2. UI 根据 databases StateFlow 渲染列表
 * 3. 用户点击"切换到此库"→ switchToDatabase() → 确认对话框 → 切换
 * 4. 用户点击"导出"→ exportDatabase() → SAF 创建文档 → 复制文件
 * 5. 用户点击"导入数据库"→ importDatabase() → SAF 选择源 → 选择目标库文件夹 → 复制文件
 *
 * @author PDF Manager Development Team
 */
class DatabaseManageViewModel : ViewModel() {

    // ═══════════════════════════════════════════════════════════════════
    // 一、UI 状态定义
    // ═══════════════════════════════════════════════════════════════════

    /**
     * 【数据库文件列表】
     *
     * 存放扫描 databases/ 目录后得到的所有数据库文件信息。
     * 空列表表示没有发现任何数据库文件。
     */
    private val _databases = MutableStateFlow<List<DatabaseInfo>>(emptyList())
    val databases: StateFlow<List<DatabaseInfo>> = _databases.asStateFlow()

    /**
     * 【是否正在加载中】
     *
     * true 表示正在扫描 databases/ 目录或正在执行操作（导出/导入/切换），
     * UI 应显示加载指示器。
     */
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    /**
     * 【当前使用的数据库对应的库文件夹 URI】
     *
     * 从 PreferencesManager 中读取的当前库文件夹 URI。
     * 用于比对每个数据库文件的 libraryUri，以确定哪个是当前数据库。
     * null 表示尚未绑定库文件夹。
     */
    private val _currentDbUri = MutableStateFlow<String?>(null)
    val currentDbUri: StateFlow<String?> = _currentDbUri.asStateFlow()

    /**
     * 【操作结果消息】
     *
     * 非 null 时携带一条要显示给用户的消息（成功或失败），
     * UI 观察此 Flow 并用 Toast 展示。
     * 展示后应置为 null。
     */
    private val _toastMessage = MutableStateFlow<String?>(null)
    val toastMessage: StateFlow<String?> = _toastMessage.asStateFlow()

    // ═══════════════════════════════════════════════════════════════════
    // 二、核心方法
    // ═══════════════════════════════════════════════════════════════════

    /**
     * 【加载数据库列表】
     *
     * 扫描 databases/ 目录，读取映射表，比对当前库 URI，组装 DatabaseInfo 列表。
     *
     * 执行流程：
     * 1. 设置 _isLoading = true，清空列表
     * 2. 获取 AppContainer.appContext，定位 databases/ 目录
     * 3. 获取 PreferencesManager 并读取全部映射 + 当前库 URI
     * 4. 遍历 databases/ 目录下所有以 "pdf_manager_" 开头且以 ".db" 结尾的文件
     * 5. 对每个文件，从映射表中查找对应的 libraryUri
     * 6. 比对当前库 URI，设置 isCurrent 标记
     * 7. 如果 libraryUri 已知，通过 DocumentFile 解析成 displayPath
     * 8. 按 isCurrent 排序（当前库排最前），更新 _databases
     * 9. 设置 _isLoading = false
     *
     * 调用位置：
     * - 页面初始化时在 LaunchedEffect 中调用
     * - 导入/导出/切换操作完成后重新调用以刷新列表
     */
    fun loadDatabases() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                // ── 获取应用上下文和 databases/ 目录 ─────────────────
                val context = AppContainer.appContext
                val databasesDir = File(context.applicationInfo.dataDir, "databases")
                if (!databasesDir.exists() || !databasesDir.isDirectory) {
                    _databases.value = emptyList()
                    _isLoading.value = false
                    return@launch
                }

                // ── 读取偏好设置中的映射表 ──────────────────────────
                val prefsManager = PreferencesManager(context)
                val mappings = prefsManager.getAllDatabaseMappings()
                val currentLibraryUri = prefsManager.getLibraryUri()

                // 保存当前库 URI 以便 UI 使用
                _currentDbUri.value = currentLibraryUri

                // ── 遍历所有数据库文件 ───────────────────────────────
                val dbFiles = databasesDir.listFiles()
                    ?.filter { file ->
                        file.isFile &&
                            file.name.startsWith("pdf_manager_") &&
                            file.name.endsWith(".db")
                    }
                    ?: emptyList()

                // ── 组装 DatabaseInfo 列表 ──────────────────────────
                val infoList = dbFiles.map { file ->
                    val dbName = file.name
                    val mappedUri = mappings[dbName]
                    val isCurrent = (mappedUri != null && mappedUri == currentLibraryUri)

                    // 如果映射 URI 已知，尝试解析为完整目录路径
                    val displayPath = if (mappedUri != null) {
                        try {
                            uriToDisplayPath(context, mappedUri)
                        } catch (e: Exception) {
                            Log.w(TAG, "无法解析库文件夹路径: $mappedUri", e)
                            null
                        }
                    } else {
                        null
                    }

                    DatabaseInfo(
                        dbFileName = dbName,
                        libraryUri = mappedUri,
                        fileSize = file.length(),
                        isCurrent = isCurrent,
                        displayPath = displayPath
                    )
                }

                // 按 isCurrent 降序排列（当前库排在最前面）
                _databases.value = infoList.sortedByDescending { it.isCurrent }

                Log.d(TAG, "数据库列表加载完成，共 ${infoList.size} 个文件")
            } catch (e: Exception) {
                Log.e(TAG, "加载数据库列表失败", e)
                _toastMessage.value = "加载数据库列表失败: ${e.localizedMessage}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * 【切换到指定的数据库】
     *
     * 将当前 Room 数据库切换到目标数据库文件对应的库文件夹。
     * 切换前会检查库文件夹 URI 是否有效（存在映射）。
     *
     * 执行流程：
     * 1. 从 _databases 中查找对应的 DatabaseInfo
     * 2. 检查 libraryUri 是否为 null（无映射则无法切换）
     * 3. 设置 _isLoading = true，显示加载状态
     * 4. 调用 AppContainer.switchLibrary(context, libraryUri)
     * 5. 切换完成后，刷新数据库列表
     * 6. 发送成功 Toast 消息
     *
     * 调用位置：
     * - UI 中用户点击"切换到此库"按钮后的确认对话框的确认回调中调用
     *
     * @param context Android Context（用于 AppContainer.switchLibrary）
     * @param dbName  要切换到的数据库文件名（如 "pdf_manager_12345.db"）
     */
    fun switchToDatabase(context: Context, dbName: String) {
        // 在数据库中查找目标文件
        val targetDb = _databases.value.find { it.dbFileName == dbName } ?: run {
            _toastMessage.value = "未找到数据库文件: $dbName"
            return
        }
        val targetLibraryUri = targetDb.libraryUri ?: run {
            _toastMessage.value = "该数据库没有关联的库文件夹，无法切换"
            return
        }

        viewModelScope.launch {
            _isLoading.value = true
            try {
                // ── 执行切换 ─────────────────────────────────────────
                // AppContainer.switchLibrary() 会关闭旧数据库、打开新数据库、
                // 重新初始化所有 Repository，并触发 Activity 重建
                // 该方法内部包含 DataStore 和 Activity.recreate() 等主线程操作
                AppContainer.switchLibrary(context, targetLibraryUri)

                Log.i(TAG, "已切换到数据库: $dbName，库文件夹URI: $targetLibraryUri")
                _toastMessage.value = "已切换到数据库: ${targetDb.displayPath ?: dbName}"

                // 切换完成后刷新列表
                loadDatabases()
            } catch (e: Exception) {
                Log.e(TAG, "切换数据库失败: $dbName", e)
                _toastMessage.value = "切换数据库失败: ${e.localizedMessage}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * 【导出数据库文件】
     *
     * 将指定的数据库文件复制到用户通过 SAF 选择的目标位置。
     * 导出前会执行 SQLite WAL checkpoint 以确保数据完整性。
     *
     * 执行流程：
     * 1. 获取数据库文件在 databases/ 目录中的完整路径
     * 2. 检查该文件是否当前正在使用（如果正在使用，先执行 checkpoint）
     * 3. 使用 android.database.sqlite.SQLiteDatabase 对当前数据库执行 checkpoint
     * 4. 通过 Context ContentResolver 打开目标 URI 的输出流
     * 5. 读取源文件的输入流并复制到输出流
     * 6. 关闭所有流，发送成功消息
     *
     * 关于 WAL checkpoint 的说明：
     * Room 默认使用 WAL（Write-Ahead Logging）模式，部分数据可能还在 WAL 文件中
     * 而未合并到主数据库文件。执行 checkpoint 可以强制合并 WAL，确保导出的文件
     * 包含全部数据。注意不要对源文件路径直接打开 SQLiteDatabase，而是通过
     * PdfManagerDatabase 的 getOpenHelper().writableDatabase 获取当前实例。
     *
     * 调用位置：
     * - UI 中用户点击"导出"按钮 → SAF 创建文档 → 回调中调用此方法
     *
     * @param context    Android Context
     * @param dbFileName 要导出的数据库文件名
     * @param targetUri  目标文件的 SAF URI（由 ACTION_CREATE_DOCUMENT 返回）
     */
    fun exportDatabase(context: Context, dbFileName: String, targetUri: Uri) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                // ── 定位源数据库文件的完整路径 ───────────────────────
                val dbPath = context.getDatabasePath(dbFileName).absolutePath

                // ── 检查是否为当前使用的数据库，若是则执行 checkpoint ──
                // 确保 WAL 日志合并到主文件，导出的数据是最新的
                withContext(Dispatchers.IO) {
                    if (_databases.value.any { it.dbFileName == dbFileName && it.isCurrent }) {
                        try {
                            // 通过 Room 的 OpenHelper 获取可写数据库并执行 checkpoint
                            val db = PdfManagerDatabase.getDatabase(context, dbFileName)
                            val sqliteDb = db.openHelper.writableDatabase
                            // 执行 WAL checkpoint，强制将 WAL 内容合并到主数据库文件
                            sqliteDb.execSQL("PRAGMA wal_checkpoint(FULL)")
                            Log.d(TAG, "已对 $dbFileName 执行 WAL checkpoint")
                        } catch (e: Exception) {
                            Log.w(TAG, "执行 WAL checkpoint 失败，将继续导出: $dbFileName", e)
                            // checkpoint 失败不是致命错误，继续尝试导出
                        }
                    }

                    // ── 复制文件内容到目标 URI ───────────────────────
                    val sourceFile = File(dbPath)
                    if (!sourceFile.exists()) {
                        throw IllegalStateException("源数据库文件不存在: $dbPath")
                    }

                    context.contentResolver.openOutputStream(targetUri)?.use { outputStream ->
                        sourceFile.inputStream().use { inputStream ->
                            inputStream.copyTo(outputStream)
                        }
                    } ?: throw IllegalStateException("无法打开目标文件输出流")
                }

                Log.i(TAG, "数据库导出成功: $dbFileName -> $targetUri")
                _toastMessage.value = "数据库导出成功: $dbFileName"
            } catch (e: Exception) {
                Log.e(TAG, "导出数据库失败: $dbFileName", e)
                _toastMessage.value = "导出失败: ${e.localizedMessage}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * 【导入数据库文件】
     *
     * 从外部 SAF URI 复制数据库文件到应用的 databases/ 目录，
     * 并保存映射关系，然后切换到目标库文件夹。
     *
     * 执行流程：
     * 1. 根据目标库文件夹 URI 生成对应的数据库文件名
     * 2. 计算 hashCode 作为数据库文件名的一部分（与 AppContainer 规则一致）
     * 3. 检查该文件名是否已存在（若存在则覆盖，需提醒用户）
     * 4. 通过 ContentResolver 打开源 URI 的输入流
     * 5. 复制文件内容到 databases/ 目录下的目标文件
     * 6. 调用 PreferencesManager.saveDatabaseUriMapping() 保存映射
     * 7. 调用 AppContainer.switchLibrary() 切换到新导入的数据库
     * 8. 刷新数据库列表
     *
     * 文件名生成规则（与 AppContainer.switchLibrary 保持一致）：
     * "pdf_manager_{targetLibraryUri.hashCode()}.db"
     *
     * 调用位置：
     * - UI 中用户点击"导入数据库"按钮 → SAF 选择源文件 → 返回后再次弹出
     *   库文件夹选择器 → 回调中调用此方法（传入 sourceUri 和 targetLibraryUri）
     *
     * @param context          Android Context
     * @param sourceUri        源数据库文件的 SAF URI（由 ACTION_OPEN_DOCUMENT 返回）
     * @param targetLibraryUri 目标库文件夹的 SAF URI（用户选择将此数据库绑定到哪个库）
     */
    fun importDatabase(context: Context, sourceUri: Uri, targetLibraryUri: Uri) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                // ── 生成目标数据库文件名（与 AppContainer 规则一致） ──
                val targetLibraryUriStr = targetLibraryUri.toString()
                val dbName = "pdf_manager_${targetLibraryUriStr.hashCode()}.db"

                Log.i(TAG, "开始导入数据库: 源=$sourceUri, 目标库=$targetLibraryUriStr, 文件名=$dbName")

                withContext(Dispatchers.IO) {
                    // ── 获取 databases/ 目录路径 ─────────────────────
                    val dbPath = context.getDatabasePath(dbName).absolutePath
                    val dbFile = File(dbPath)

                    // ── 如果目标文件已存在，先删除（覆盖导入） ────────
                    if (dbFile.exists()) {
                        Log.w(TAG, "目标数据库文件已存在，将覆盖: $dbName")
                        // 如果这是当前正在使用的数据库，先关闭它再删除
                        if (_databases.value.any { it.dbFileName == dbName && it.isCurrent }) {
                            PdfManagerDatabase.closeDatabase()
                            // 标记 AppContainer 为未初始化状态
                            // 注意：关闭后 AppContainer 的 database 引用失效，
                            // 导入完成后 switchLibrary 会重新初始化
                        }
                        dbFile.delete()
                    }

                    // ── 创建父目录（确保 databases/ 目录存在） ────────
                    dbFile.parentFile?.mkdirs()

                    // ── 从源 URI 复制文件内容到目标路径 ───────────────
                    context.contentResolver.openInputStream(sourceUri)?.use { inputStream ->
                        dbFile.outputStream().use { outputStream ->
                            inputStream.copyTo(outputStream)
                        }
                    } ?: throw IllegalStateException("无法打开源文件: $sourceUri")

                    Log.d(TAG, "数据库文件已复制到: $dbPath (大小: ${dbFile.length()} 字节)")
                }

                // ── 保存映射关系 ──────────────────────────────────────
                val prefsManager = PreferencesManager(context)
                prefsManager.saveDatabaseUriMapping(dbName, targetLibraryUri.toString())
                Log.d(TAG, "映射已保存: $dbName -> $targetLibraryUri")

                // ── 切换到新导入的数据库 ─────────────────────────────
                // AppContainer.switchLibrary() 包含 DataStore 写入和 Activity.recreate()
                // 需要在主线程执行
                AppContainer.switchLibrary(context, targetLibraryUri.toString())

                Log.i(TAG, "数据库导入并切换成功: $dbName")
                _toastMessage.value = "数据库导入成功，已切换到新库"

                // 刷新数据库列表
                loadDatabases()
            } catch (e: Exception) {
                Log.e(TAG, "导入数据库失败", e)
                _toastMessage.value = "导入失败: ${e.localizedMessage}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * 【消费 Toast 消息】
     *
     * UI 在展示完 Toast 后调用此方法将消息清空，
     * 避免在同一页面多次显示相同的 Toast。
     *
     * 调用位置：
     * - 数据库管理屏幕的 LaunchedEffect 中，当 toastMessage 非 null 时
     *   展示 Toast，然后调用此方法清空
     */
    fun consumeToastMessage() {
        _toastMessage.value = null
    }

    // ═══════════════════════════════════════════════════════════════════
    // 四、工具方法
    // ═══════════════════════════════════════════════════════════════════

    /**
     * 将 SAF content:// URI 转换为可读的文件系统路径
     *
     * 功能说明：
     * 1. 如果是 file:// 协议，直接取路径
     * 2. 如果是 content:// 协议，解析 DocumentsContract ID
     * 3. 仅支持 primary: 前缀（内部存储）
     *
     * @param context Context
     * @param uriString SAF URI 字符串
     * @return 完整目录路径（如 "/storage/emulated/0/PDF/教材"），无法解析则返回 URI 本身
     */
    private fun uriToDisplayPath(context: Context, uriString: String): String {
        val uri = Uri.parse(uriString)
        if (uri.scheme == "file") {
            return uri.path ?: uriString
        }
        if (uri.scheme == "content") {
            try {
                val docId = DocumentsContract.getTreeDocumentId(uri)
                if (docId.startsWith("primary:")) {
                    val path = Uri.decode(docId.removePrefix("primary:"))
                    return "${Environment.getExternalStorageDirectory().absolutePath}/$path"
                }
                // 非 primary 存储，返回原始 URI
                return uriString
            } catch (e: Exception) {
                Log.w(TAG, "解析 URI 失败: $uriString", e)
                return uriString
            }
        }
        return uriString
    }

    // ═══════════════════════════════════════════════════════════════════
    // 五、ViewModel Factory
    // ═══════════════════════════════════════════════════════════════════

    companion object {
        /**
         * 【ViewModel 工厂】
         *
         * 用于创建 DatabaseManageViewModel 实例。
         * 此 ViewModel 不依赖外部参数，只需默认构造即可。
         * 使用 AppContainer.appContext 来获取全局依赖。
         */
        val Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return DatabaseManageViewModel() as T
            }
        }
    }
}
