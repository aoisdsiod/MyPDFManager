package com.example.pdfmanager.data.local

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import com.example.pdfmanager.data.repository.AppContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ## 数据库备份管理器
 *
 * 负责 Room 数据库文件的备份与损坏保护，是「数据库管理」页面手动导出、
 * 损坏现场保留、启动完整性检查三个功能的统一实现。
 *
 * ### 功能说明
 * 1. **手动/自动导出**：将指定数据库文件导出到「库文件夹/database/backup」目录，
 *    命名格式为 `年_月_日_backup_秒时间戳.db`，该目录中此类正常备份最多保留 3 份，
 *    超出后自动删除最旧的一份。
 * 2. **损坏现场备份**：检测到数据库损坏时，将损坏文件原样导出到同一目录，
 *    命名格式为 `年_月_日_broken_backup_秒时间戳.db`，**不参与** 3 份限额清理，
 *    用于取证与人工抢救（同时附带 `-wal` / `-shm` 现场文件）。
 * 3. **启动完整性检查**：对指定数据库执行 `PRAGMA quick_check`，结果非 `ok`
 *    说明数据库内部不一致，自动触发损坏现场备份。
 *
 * ### 存储位置
 * 所有备份文件存放在 **库文件夹 `database/backup/`** 子目录中：
 * ```
 * [Library Root]/
 * └── database/
 *     ├── backup/       ← 本类管理的备份目录（不存在时动态创建）
 *     │   ├── 2026_07_06_backup_1750000000.db
 *     │   └── 2026_07_06_broken_backup_1750000000.db
 *     ├── zip/          ← 转换压缩包（MainActivity 创建）
 *     └── share/        ← 分享文件（MainActivity 创建）
 * ```
 *
 * ### 线程安全
 * - 目录定位与文件复制在 [Dispatchers.IO] 上执行，不阻塞主线程
 * - [CorruptionAwareOpenHelperFactory.onCorruption] 回调在数据库打开线程触发，
 *   内部通过 [runBlocking] 同步完成现场备份，确保在默认删除行为前保留证据
 *
 * ### 调用位置
 * - `DatabaseManageViewModel.exportDatabase()` - 手动导出当前库
 * - `PdfManagerDatabase.getDatabase()` - 通过 [CorruptionAwareOpenHelperFactory] 注入
 * - `AppContainer.init()` / `AppContainer.switchLibrary()` - 启动完整性检查
 *
 * @author PDF Manager Development Team
 * @see PdfManagerDatabase 使用本类进行损坏保护的 Room 数据库
 * @see CorruptionAwareOpenHelperFactory 损坏拦截工厂（本文件内定义）
 */
object DatabaseBackupManager {

    /** Log 标签，用于 DatabaseBackupManager 中的日志输出 */
    private const val TAG = "DatabaseBackupManager"

    /** 备份目录名称（位于库文件夹 database/ 下） */
    private const val BACKUP_DIR_NAME = "backup"

    /** 正常备份最大保留份数，超出后删除最旧 */
    private const val MAX_BACKUP_COUNT = 3

    /** 备份文件 MIME 类型（SQLite 数据库文件） */
    private const val DB_MIME_TYPE = "application/octet-stream"

    /** 正常备份文件名的日期格式：年_月_日 */
    private val DATE_FORMAT = SimpleDateFormat("yyyy_MM_dd", Locale.US)

    /**
     * ## 备份操作结果
     *
     * @property success 操作是否成功
     * @property fileName 生成的备份文件名（成功时为非 null）
     * @property message 结果描述（失败原因或成功提示）
     */
    data class BackupResult(
        val success: Boolean,
        val fileName: String? = null,
        val message: String = ""
    )

    // ═══════════════════════════════════════════════════════════════════
    // 一、公开方法
    // ═══════════════════════════════════════════════════════════════════

    /**
     * ## 备份当前正在使用的数据库（切换库/导入库前调用）
     *
     * 在切换库文件夹或导入新库之前，自动备份当前正在使用的数据库，
     * 防止切换/导入过程中数据丢失或覆盖。
     *
     * 内部通过 [PdfManagerDatabase.getCurrentDbName] 获取当前库名，
     * 再委托 [exportBackup] 执行实际导出。若当前没有打开的数据库
     * （如首次启动未绑定库文件夹），直接返回成功（无需备份）。
     *
     * ### 调用位置
     * - `AppContainer.switchLibrary()` - 切换库文件夹前
     * - `DatabaseManageViewModel.importDatabase()` - 导入新库前
     *
     * @param context Android Context
     * @return [BackupResult] 操作结果（当前无库时视为成功）
     */
    suspend fun backupCurrentDatabase(context: Context): BackupResult {
        val currentDbName = PdfManagerDatabase.getCurrentDbName()
        if (currentDbName.isEmpty()) {
            Log.d(TAG, "当前没有打开的数据库，跳过自动备份")
            return BackupResult(true, message = "当前无数据库，无需备份")
        }
        return exportBackup(context, currentDbName)
    }

    /**
     * ## 导出数据库备份（手动/自动）
     *
     * 将指定数据库文件复制到「库文件夹/database/backup」目录，命名为
     * `年_月_日_backup_秒时间戳.db`，并清理该目录中超过 3 份的最旧正常备份。
     *
     * ### 执行流程
     * 1. 定位/创建库文件夹下的 `database/backup` 目录（SAF DocumentFile）
     * 2. 若导出的是当前正在使用的库，先执行 `PRAGMA wal_checkpoint(FULL)`
     *    合并 WAL 日志，保证备份内容完整一致
     * 3. 以当前日期 + 秒时间戳生成备份文件名，流式复制数据库文件
     * 4. 清理 `*_backup_*.db`（不含 broken）超过 3 份的最旧文件
     *
     * ### 调用位置
     * - `DatabaseManageViewModel.exportDatabase()` - 用户点击「导出」按钮
     * - `DatabaseBackupManager.backupCurrentDatabase()` - 切换/导入前自动备份
     *
     * @param context Android Context
     * @param dbFileName 数据库文件名（如 "pdf_manager_12345.db"）
     * @return [BackupResult] 操作结果
     */
    suspend fun exportBackup(context: Context, dbFileName: String): BackupResult = withContext(Dispatchers.IO) {
        try {
            // ── 1. 定位/创建备份目录 ────────────────────────────────
            val backupDir = getOrCreateBackupDir(context, dbFileName)
                ?: return@withContext BackupResult(false, message = "未找到库文件夹或无法创建备份目录")

            // ── 2. 导出前合并 WAL（仅对当前使用的库执行）─────────────
            checkpointIfCurrent(context, dbFileName)

            // ── 3. 生成文件名并复制主数据库文件 ─────────────────────
            val timestamp = System.currentTimeMillis() / 1000
            val fileName = "${DATE_FORMAT.format(Date())}_backup_${timestamp}.db"

            val sourceFile = context.getDatabasePath(dbFileName)
            val copied = copyFileToDir(context, sourceFile, backupDir, fileName)
            if (!copied) {
                return@withContext BackupResult(false, message = "复制数据库文件失败: $dbFileName")
            }

            // ── 4. 清理超过限额的最旧正常备份 ───────────────────────
            cleanupOldBackups(backupDir)

            Log.d(TAG, "数据库备份成功: $dbFileName -> $fileName")
            BackupResult(true, fileName = fileName, message = "备份成功: $fileName")
        } catch (e: Exception) {
            Log.e(TAG, "数据库备份失败: $dbFileName", e)
            BackupResult(false, message = "备份失败: ${e.message}")
        }
    }

    /**
     * ## 导出损坏现场备份
     *
     * 将损坏的数据库文件原样导出到「库文件夹/database/backup」目录，命名为
     * `年_月_日_broken_backup_秒时间戳.db`。该文件**不参与**正常备份的 3 份
     * 限额清理，长期保留供取证与人工抢救。
     *
     * 注意：只导出主 `.db` 文件，不附带 `-wal`/`-shm` 临时运行文件
     * （正常关闭后它们已被 SQLite 自动清理，主文件即为完整数据）。
     *
     * ### 调用位置
     * - `CorruptionAwareOpenHelperFactory.onCorruption()` - 打开数据库时检测到损坏
     * - `quickCheckAndBackup()` - 启动完整性检查发现损坏
     * - `DatabaseManageViewModel` - 用户手动触发（预留）
     *
     * @param context Android Context
     * @param dbFileName 数据库文件名（如 "pdf_manager_12345.db"）
     * @return [BackupResult] 操作结果
     */
    suspend fun exportBrokenBackup(context: Context, dbFileName: String): BackupResult = withContext(Dispatchers.IO) {
        exportBrokenBackupInternal(context, dbFileName)
    }

    /**
     * ## 从最近的正常备份恢复数据库
     *
     * 当数据库损坏时，从「库文件夹/database/backup」目录中查找**最近一份**正常备份
     * （`*_backup_*.db`，不含 broken），复制回应用私有数据库位置，覆盖损坏文件。
     * 恢复成功后同时清理 `-wal`/`-shm` 残留（备份是 checkpoint 后的完整快照，
     * 旧 wal 可能与新主文件不匹配，必须删除）。
     *
     * ### 执行流程
     * 1. 定位 backup 目录，筛选出所有正常备份文件（不含 broken）
     * 2. 按文件名排序（时间戳递增），取最近一份
     * 3. 流式复制到 `context.getDatabasePath(dbFileName)` 覆盖损坏文件
     * 4. 删除目标位置的 `-wal` / `-shm` 残留文件
     *
     * ### 调用位置
     * - `CorruptionAwareOpenHelperFactory.onCorruption()` - 损坏回调中先备份现场再尝试恢复
     * - `quickCheckAndBackup()` - 启动完整性检查发现损坏后尝试恢复
     *
     * @param context Android Context
     * @param dbFileName 数据库文件名（如 "pdf_manager_12345.db"）
     * @return [BackupResult] 操作结果（success=true 表示已用备份覆盖损坏文件）
     */
    suspend fun restoreLatestBackup(context: Context, dbFileName: String): BackupResult = withContext(Dispatchers.IO) {
        try {
            val backupDir = getOrCreateBackupDir(context, dbFileName)
                ?: return@withContext BackupResult(false, message = "未找到库文件夹或无法创建备份目录")

            // ── 1. 筛选正常备份并按时间排序，取最近一份 ──────────────
            val latestBackup = backupDir.listFiles()
                .filter { file ->
                    file.isFile &&
                        file.name?.endsWith(".db") == true &&
                        file.name?.contains("_backup_") == true &&
                        file.name?.contains("broken") != true
                }
                .sortedBy { it.name }
                .lastOrNull()
                ?: return@withContext BackupResult(false, message = "backup 目录中没有可用的正常备份")

            // ── 2. 复制备份覆盖损坏的数据库文件 ──────────────────────
            val targetFile = context.getDatabasePath(dbFileName)
            val restored = copyFromDirToFile(context, latestBackup, targetFile)
            if (!restored) {
                return@withContext BackupResult(false, message = "复制备份失败: ${latestBackup.name}")
            }

            // ── 3. 清理 wal/shm 残留（备份是完整快照，旧日志必须删除）──
            val walFile = File("${targetFile.absolutePath}-wal")
            val shmFile = File("${targetFile.absolutePath}-shm")
            if (walFile.exists()) walFile.delete()
            if (shmFile.exists()) shmFile.delete()

            Log.d(TAG, "已从备份恢复数据库: ${latestBackup.name} -> $dbFileName")
            BackupResult(true, fileName = latestBackup.name, message = "已从备份恢复: ${latestBackup.name}")
        } catch (e: Exception) {
            Log.e(TAG, "从备份恢复数据库失败: $dbFileName", e)
            BackupResult(false, message = "恢复失败: ${e.message}")
        }
    }

    /**
     * ## 启动完整性检查
     *
     * 对指定数据库执行 `PRAGMA quick_check`，用于 App 每次启动时主动探测
     * 数据库内部一致性。若结果非 `ok`（或数据库无法打开），自动执行：
     * 1. [exportBrokenBackup] 保留损坏现场
     * 2. [restoreLatestBackup] 尝试从最近的正常备份恢复
     * 并返回 false。
     *
     * ### 执行流程
     * 1. 直接打开数据库文件（不经过 Room，避免触发迁移逻辑）
     * 2. 执行 `PRAGMA quick_check`，读取第一行第一列
     * 3. 结果为 `ok` → 返回 true
     * 4. 结果非 `ok` → 备份 broken 现场 + 尝试从最近备份恢复，返回 false
     * 5. 打开失败（文件损坏无法打开）→ 捕获异常，同样备份 + 恢复，返回 false
     *
     * ### 调用位置
     * - `AppContainer.init()` - App 冷启动时对当前库执行
     * - `AppContainer.switchLibrary()` - 切换库文件夹后对新库执行
     *
     * @param context Android Context
     * @param dbFileName 数据库文件名（如 "pdf_manager_12345.db"）
     * @return true=数据库健康；false=检测到损坏（已自动备份现场并尝试恢复）
     */
    suspend fun quickCheckAndBackup(context: Context, dbFileName: String): Boolean = withContext(Dispatchers.IO) {
        val dbFile = context.getDatabasePath(dbFileName)
        if (!dbFile.exists()) {
            Log.d(TAG, "quick_check: 数据库文件不存在（首次使用），跳过检查: $dbFileName")
            return@withContext true
        }

        try {
            // 直接打开 SQLite 文件执行 quick_check（不经 Room，避免触发迁移逻辑）
            // 注意：Room 默认使用 WAL 模式，若 -shm/-wal 文件不存在，只读打开会因
            // 无法创建 -shm 而失败（被误判为损坏），因此使用 READWRITE 打开
            SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE)
                .use { db ->
                    val result = db.rawQuery("PRAGMA quick_check", null).use { cursor ->
                        if (cursor.moveToFirst()) cursor.getString(0) else "no-result"
                    }
                    if (result == "ok") {
                        Log.d(TAG, "quick_check: 数据库健康 ($dbFileName)")
                        return@withContext true
                    }
                    Log.w(TAG, "quick_check: 数据库内部不一致: $result ($dbFileName)")
                }
        } catch (e: Exception) {
            Log.e(TAG, "quick_check: 数据库无法打开，疑似损坏 ($dbFileName)", e)
        }

        // 检测到损坏：先保留现场，再尝试从最近备份恢复
        val brokenBackup = exportBrokenBackup(context, dbFileName)
        val restore = restoreLatestBackup(context, dbFileName)
        Log.w(TAG, "quick_check: 检测到损坏，broken 现场已备份，恢复结果=${restore.message}")

        // 上报损坏事件（MainActivity 观察后弹窗提醒用户）
        reportCorruptionEvent(dbFileName, brokenBackup, restore)
        // 返回 false 表示检测到损坏（无论恢复成功与否，均由上层决定是否提示用户）
        false
    }

    /**
     * ## 上报数据库损坏事件
     *
     * 将损坏检测结果写入 AppContainer 的全局事件流，供 MainActivity
     * 观察并弹出提醒对话框。事件包含损坏库名、现场备份文件名与恢复结果。
     *
     * 可见性说明：internal 供同文件的 [CorruptionAwareOpenHelperFactory] 调用。
     *
     * @param dbFileName 发生损坏的数据库文件名
     * @param brokenBackup broken 现场备份结果
     * @param restore 从最近备份恢复的结果
     */
    internal fun reportCorruptionEvent(
        dbFileName: String,
        brokenBackup: BackupResult,
        restore: BackupResult
    ) {
        AppContainer.reportDatabaseCorruption(
            AppContainer.DatabaseCorruptionEvent(
                dbFileName = dbFileName,
                brokenBackupFileName = brokenBackup.fileName,
                restored = restore.success
            )
        )
    }

    // ═══════════════════════════════════════════════════════════════════
    // 二、私有方法
    // ═══════════════════════════════════════════════════════════════════

    /**
     * ## 损坏现场备份内部实现
     *
     * 供 [exportBrokenBackup] 与损坏拦截工厂共用。仅复制主数据库文件，
     * 使用 `broken` 命名，不附带 `-wal`/`-shm` 临时文件。
     */
    private suspend fun exportBrokenBackupInternal(context: Context, dbFileName: String): BackupResult {
        return try {
            val backupDir = getOrCreateBackupDir(context, dbFileName)
                ?: return BackupResult(false, message = "未找到库文件夹或无法创建备份目录")

            val timestamp = System.currentTimeMillis() / 1000
            val fileName = "${DATE_FORMAT.format(Date())}_broken_backup_${timestamp}.db"

            val sourceFile = context.getDatabasePath(dbFileName)
            val copied = copyFileToDir(context, sourceFile, backupDir, fileName)
            if (!copied) {
                return BackupResult(false, message = "复制损坏数据库文件失败: $dbFileName")
            }

            Log.d(TAG, "损坏现场备份成功: $dbFileName -> $fileName")
            BackupResult(true, fileName = fileName, message = "损坏现场已备份: $fileName")
        } catch (e: Exception) {
            Log.e(TAG, "损坏现场备份失败: $dbFileName", e)
            BackupResult(false, message = "损坏现场备份失败: ${e.message}")
        }
    }

    /**
     * ## 定位或创建备份目录
     *
     * 通过 SAF 在「库文件夹/database/」下查找名为 `backup` 的目录，不存在则创建。
     * 返回 null 表示库文件夹未绑定、URI 无效或目录创建失败。
     *
     * ### 库文件夹 URI 的解析优先级
     * 1. **数据库映射表反查**（[PreferencesManager.getAllDatabaseMappings]）：
     *    按 `dbFileName` 查它对应的库文件夹 URI。覆盖"导入损坏数据库时库文件夹
     *    尚未绑定"的场景——`importDatabase` 会先保存 dbName→库URI 映射，
     *    损坏检测随后触发，此时即使当前库未绑定也能正确定位备份目录。
     * 2. **当前绑定的库文件夹**（[PreferencesManager.getLibraryUri]）：
     *    正常使用场景的回退，映射表无记录时使用当前库。
     *
     * @param context Android Context
     * @param dbFileName 数据库文件名（如 "pdf_manager_12345.db"）
     * @return 备份目录的 [DocumentFile]，失败返回 null
     */
    private suspend fun getOrCreateBackupDir(context: Context, dbFileName: String): DocumentFile? = withContext(Dispatchers.IO) {
        try {
            val prefsManager = PreferencesManager(context)

            // ── 1. 优先从数据库映射表反查该库对应的库文件夹 URI ──────
            // 覆盖导入损坏库时库文件夹尚未绑定/绑定了别的库的场景
            val libraryUriString = prefsManager.getAllDatabaseMappings()[dbFileName]
                ?: prefsManager.getLibraryUri()
                ?: return@withContext null

            val rootDoc = DocumentFile.fromTreeUri(context, Uri.parse(libraryUriString))
                ?: return@withContext null

            // 查找或创建 database/ 目录（与 MainActivity.createRequiredFolders 同款写法）
            val databaseDoc = rootDoc.findFile("database")
                ?: rootDoc.createDirectory("database")
                ?: return@withContext null

            // 查找或创建 database/backup/ 目录
            databaseDoc.findFile(BACKUP_DIR_NAME)
                ?: databaseDoc.createDirectory(BACKUP_DIR_NAME)
        } catch (e: Exception) {
            Log.e(TAG, "定位备份目录失败", e)
            null
        }
    }

    /**
     * ## 合并 WAL 日志（仅对当前使用的库）
     *
     * 执行 `PRAGMA wal_checkpoint(FULL)` 将 WAL 中的内容合并进主数据库文件，
     * 保证导出的备份包含全部最新数据。
     *
     * 仅当 `dbFileName` 是当前打开的数据库时才执行——通过
     * [PdfManagerDatabase.getCurrentDbName] 判断，避免对非当前库调用
     * `getDatabase()` 造成切换副作用。
     *
     * @param context Android Context
     * @param dbFileName 数据库文件名
     */
    private fun checkpointIfCurrent(context: Context, dbFileName: String) {
        try {
            if (PdfManagerDatabase.getCurrentDbName() != dbFileName) return
            val db = PdfManagerDatabase.getDatabase(context, dbFileName)
            val sqliteDb = db.openHelper.writableDatabase
            sqliteDb.execSQL("PRAGMA wal_checkpoint(FULL)")
            Log.d(TAG, "已对 $dbFileName 执行 WAL checkpoint")
        } catch (e: Exception) {
            // checkpoint 失败不阻断导出（WAL 中数据可能缺失，但主文件仍可复制）
            Log.w(TAG, "WAL checkpoint 失败，继续导出: $dbFileName", e)
        }
    }

    /**
     * ## 复制单个文件到 SAF 目录
     *
     * 使用 [DocumentFile.createFile] 在目标目录创建文件，再通过
     * [android.content.ContentResolver.openOutputStream] 流式写入源文件内容。
     *
     * @param context Android Context
     * @param source 源文件（应用私有目录中的数据库文件）
     * @param dir 目标 SAF 目录
     * @param targetName 目标文件名
     * @return 是否复制成功
     */
    private fun copyFileToDir(
        context: Context,
        source: File,
        dir: DocumentFile,
        targetName: String
    ): Boolean {
        if (!source.exists()) {
            Log.w(TAG, "源文件不存在: ${source.absolutePath}")
            return false
        }
        return try {
            val targetDoc = dir.createFile(DB_MIME_TYPE, targetName) ?: return false
            context.contentResolver.openOutputStream(targetDoc.uri)?.use { outputStream ->
                source.inputStream().use { inputStream ->
                    inputStream.copyTo(outputStream)
                }
            } ?: return false
            true
        } catch (e: Exception) {
            Log.e(TAG, "复制文件失败: $targetName", e)
            false
        }
    }

    /**
     * ## 从 SAF 备份文件复制回应用私有目录
     *
     * 用于损坏恢复：把备份目录中的 [DocumentFile] 备份文件读取出来，
     * 覆盖写入应用私有数据库位置（[File]）。
     *
     * @param context Android Context
     * @param sourceDoc 源备份文件（SAF 目录中的 DocumentFile）
     * @param targetFile 目标文件（应用私有目录中的数据库文件）
     * @return 是否复制成功
     */
    private fun copyFromDirToFile(
        context: Context,
        sourceDoc: DocumentFile,
        targetFile: File
    ): Boolean {
        return try {
            // 确保父目录存在
            targetFile.parentFile?.mkdirs()
            context.contentResolver.openInputStream(sourceDoc.uri)?.use { inputStream ->
                targetFile.outputStream().use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            } ?: return false
            true
        } catch (e: Exception) {
            Log.e(TAG, "从备份恢复文件失败: ${sourceDoc.name} -> ${targetFile.absolutePath}", e)
            false
        }
    }

    /**
     * ## 清理超限的最旧正常备份
     *
     * 遍历备份目录，筛选出 `*_backup_*.db` 且**不含** `broken` 的正常备份文件，
     * 按文件名（含日期与时间戳，字典序即时间序）升序排列，删除最旧的直到
     * 剩余份数不超过 [MAX_BACKUP_COUNT]。
     *
     * broken 现场备份不参与此清理，长期保留。
     *
     * @param dir 备份目录
     */
    private fun cleanupOldBackups(dir: DocumentFile) {
        try {
            val backups = dir.listFiles()
                .filter { file ->
                    file.isFile &&
                        file.name?.endsWith(".db") == true &&
                        file.name?.contains("_backup_") == true &&
                        file.name?.contains("broken") != true
                }
                .sortedBy { it.name }

            // 超出限额时删除最旧（排序靠前的）文件
            val toDelete = backups.size - MAX_BACKUP_COUNT
            if (toDelete > 0) {
                backups.take(toDelete).forEach { file ->
                    val deleted = file.delete()
                    Log.d(TAG, "清理旧备份: ${file.name} (删除${if (deleted) "成功" else "失败"})")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "清理旧备份失败", e)
        }
    }
}

/**
 * ## 损坏感知的 OpenHelper 工厂
 *
 * 包装 Room 默认的 [FrameworkSQLiteOpenHelperFactory]，通过覆写
 * [SupportSQLiteOpenHelper.Callback.onCorruption] 在数据库损坏被检测到时：
 * 1. **先导出 broken 现场备份**（保留证据，可人工抢救）
 * 2. **尝试从最近的正常备份恢复数据库**（自动覆盖损坏文件）
 * 3. 恢复成功则跳过默认删除逻辑；恢复失败才走默认删库重建，保证 App 可用
 *
 * ### 为什么需要它
 * `androidx.sqlite:sqlite-framework` 的 [FrameworkSQLiteOpenHelperFactory]
 * 没有公开注入 `DatabaseErrorHandler` 的构造，但其内部 [FrameworkSQLiteOpenHelper]
 * 会把框架的损坏回调委托给 [SupportSQLiteOpenHelper.Callback.onCorruption]。
 * 因此通过包装 Room 传入的 Callback 并覆写该方法，即可在删库前保留现场并恢复。
 *
 * ### 与 fallbackToDestructiveMigration 的区别
 * - 旧的 `fallbackToDestructiveMigration()`：损坏时静默删库，无现场保留、无自动恢复
 * - 本工厂：损坏时先备份现场，再自动从最近备份恢复，避免数据无感丢失
 *
 * ### 调用位置
 * - `PdfManagerDatabase.getDatabase()` - 通过 `.openHelperFactory()` 注入
 *
 * @property context Android Context（用于定位备份目录）
 * @property dbName 数据库文件名（如 "pdf_manager_12345.db"）
 * @author PDF Manager Development Team
 */
class CorruptionAwareOpenHelperFactory(
    private val context: Context,
    private val dbName: String
) : SupportSQLiteOpenHelper.Factory {

    companion object {
        /** Log 标签 */
        private const val TAG = "CorruptionAwareOpenHelperFactory"
    }

    /**
     * ## 创建 OpenHelper
     *
     * 包装传入的配置回调：所有生命周期方法转发给原始 Room 回调，
     * 仅 [SupportSQLiteOpenHelper.Callback.onCorruption] 增加现场备份逻辑。
     *
     * @param configuration Room 提供的打开配置（含原始 Room 回调）
     * @return 包装后的 OpenHelper 实例
     */
    override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
        val originalCallback = configuration.callback

        // 包装回调：拦截损坏事件，先备份现场再执行默认删除逻辑
        val wrappedCallback = object : SupportSQLiteOpenHelper.Callback(originalCallback.version) {
            override fun onCreate(db: SupportSQLiteDatabase) = originalCallback.onCreate(db)
            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) =
                originalCallback.onUpgrade(db, oldVersion, newVersion)
            override fun onOpen(db: SupportSQLiteDatabase) = originalCallback.onOpen(db)
            override fun onConfigure(db: SupportSQLiteDatabase) = originalCallback.onConfigure(db)
            override fun onDowngrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) =
                originalCallback.onDowngrade(db, oldVersion, newVersion)

            /**
             * 损坏回调：先同步导出 broken 现场备份，再尝试从最近的正常备份
             * 恢复数据库，并向 AppContainer 上报损坏事件供 UI 弹窗提醒。
             *
             * - **恢复成功**：数据库文件已由备份覆盖，**不再调用默认删除逻辑**
             *   （否则会把刚恢复的文件删掉），本次打开流程会以异常结束，
             *   上层重试时即可读到恢复后的数据。
             * - **恢复失败**（无备份或复制失败）：交给默认实现
             *   （默认行为会删除数据库文件并重建空库），保证 App 可用。
             *
             * 该回调运行在数据库打开的线程（非主线程），同步阻塞可接受。
             */
            override fun onCorruption(db: SupportSQLiteDatabase) {
                Log.w(TAG, "检测到数据库损坏: $dbName，开始备份现场并尝试恢复")
                var brokenBackup = DatabaseBackupManager.BackupResult(false, message = "备份未执行")
                var restore = DatabaseBackupManager.BackupResult(false, message = "恢复未执行")
                try {
                    // 1. 同步导出现场备份（runBlocking 保证删库前完成）
                    brokenBackup = runBlocking {
                        DatabaseBackupManager.exportBrokenBackup(context, dbName)
                    }

                    // 2. 尝试从最近的正常备份恢复
                    restore = runBlocking {
                        DatabaseBackupManager.restoreLatestBackup(context, dbName)
                    }
                    if (restore.success) {
                        // 恢复成功：数据库文件已被备份覆盖，跳过默认删除逻辑
                        Log.w(TAG, "已从备份恢复数据库，跳过默认删库重建: ${restore.fileName}")
                        DatabaseBackupManager.reportCorruptionEvent(dbName, brokenBackup, restore)
                        return
                    }
                    Log.w(TAG, "无可用备份或恢复失败，走默认删库重建: ${restore.message}")
                } catch (e: Exception) {
                    Log.e(TAG, "损坏现场备份/恢复异常，走默认删库重建", e)
                }
                // 恢复失败（或无备份）：上报事件后交给默认实现（删除数据库文件，下次打开重建空库）
                DatabaseBackupManager.reportCorruptionEvent(dbName, brokenBackup, restore)
                super.onCorruption(db)
            }
        }

        // 用包装后的回调重建配置（Builder 通过静态工厂获取）
        val wrappedConfiguration = SupportSQLiteOpenHelper.Configuration.builder(configuration.context)
            .name(configuration.name)
            .callback(wrappedCallback)
            .build()

        // 交由默认工厂创建 OpenHelper（内部会使用包装回调）
        return FrameworkSQLiteOpenHelperFactory().create(wrappedConfiguration)
    }
}
