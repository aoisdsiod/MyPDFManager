package com.example.pdfmanager.data.repository

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * ## 分享仓库
 *
 * ─── 功能职责 ─────────────────────────────────────────────────
 * 负责将 PDF 文件复制到分享目标文件夹中，支持单文件和多文件批量复制。
 * 完全基于 Android Storage Access Framework（SAF）实现，通过 [DocumentFile] API
 * 操作文件，保证对 Android 10+ 分区存储（Scoped Storage）的兼容性。
 *
 * 分享的文件统一存放在用户库目录下的 `share/`（或 `database/share/`）文件夹中，
 * 供外部 App 通过 SAF 访问和读取。
 *
 * ─── 数据流 ───────────────────────────────────────────────────
 * ```
 * UI 层（文件详情页 / 批量分享页面）
 *         │
 *         ▼
 *   ShareRepository
 *         │
 *         ├── 获取 share/ 路径  ──→  [getShareFolderUri]
 *         │                            │
 *         │                            ▼
 *         │                     AppContainer.preferencesManager
 *         │                     （读取用户库 SAF Uri）
 *         │
 *         ├── 文件复制          ──→  ContentResolver
 *         │                            │
 *         │                            ▼
 *         │                     openInputStream / openOutputStream
 *         │                     （SAF 流式读写）
 *         │
 *         └── 目标管理          ──→  DocumentFile API
 *                                      │
 *                                      ▼
 *                               createFile / createDirectory
 *                               （SAF 文件/目录创建）
 * ```
 *
 * ─── 调用位置 ─────────────────────────────────────────────────
 * - **文件详情页**：用户点击"分享此文件"按钮时，调用 [copySingleFile] 或 [copySingleFileToTarget]。
 * - **批量分享页**：用户选择多个文件并点击"一键分享"时，调用 [copyMultipleFiles]。
 * - **ShareTargetPicker**：用户选择或创建目标文件夹时，调用 [getShareSubFolders] 和 [createShareSubFolder]。
 * - **MainActivity**：初始化时确保 share/ 目录存在。
 *
 * ─── 线程模型 ─────────────────────────────────────────────────
 * 所有文件 I/O 操作均通过 [withContext(Dispatchers.IO)] 切换到 IO 线程池执行，
 * 不会阻塞主线程。调用方需要在协程作用域内调用 suspend 方法。
 *
 * ─── 文件名冲突处理 ──────────────────────────────────────────
 * 当目标文件夹中已存在同名文件时，自动在文件名后追加 `_1`、`_2` 等数字后缀，
 * 保证不覆盖已有文件。具体逻辑见 [getNonConflictName]。
 *
 * @param context Application 级别的 [Context]，避免 Activity/Fragment 引用导致内存泄漏。
 *                SAF 操作通过 ContentResolver 完成，Application Context 足够使用。
 *
 * @property context Application Context，用于获取 ContentResolver 和创建 DocumentFile。
 * @see com.example.pdfmanager.data.local.AppContainer 依赖注入容器，持有 preferencesManager 提供用户库 Uri
 * @see androidx.documentfile.provider.DocumentFile SAF 文件抽象层
 * @see android.content.ContentResolver 文件内容读写入口
 */
class ShareRepository(
    private val context: Context,
) {

    companion object {
        /**
         * 日志标签，用于 [android.util.Log] 输出调试和错误信息。
         */
        private const val TAG = "ShareRepository"

        /**
         * 分享文件夹的名称常量。
         * 用于在用户库根目录下查找或引用 share/ 文件夹。
         * 兼容旧版本路径：根目录下的 share/；新版本路径：database/share/。
         */
        private const val SHARE_FOLDER_NAME = "share"
    }

    /**
     * ## 复制单个 PDF 文件到 share/ 根目录
     *
     * 最常用的分享方式：将文件复制到默认的 share/ 文件夹。
     * 其他 App（如文件管理器）可通过 SAF 访问此目录下的文件。
     *
     * ─── 调用场景 ──────────────────────────────────────────────
     * - 文件详情页的"分享此文件"按钮（快速分享到默认位置）。
     *
     * ─── 执行流程 ──────────────────────────────────────────────
     * 1. 通过 [getShareFolderUri] 获取 share/ 文件夹 SAF Uri。
     * 2. 通过 [DocumentFile.fromTreeUri] 获取文件夹的 DocumentFile 引用。
     * 3. 调用 [copyFileWithConflictSuffix] 执行实际复制（含文件名冲突处理）。
     *
     * @param sourcePdfUri 源 PDF 文件的 SAF Uri。[ContentResolver] 通过此 Uri 打开输入流。
     *                     通常来自文件列表或详情页传递的 SAF Uri。
     * @return 复制成功的文件数量（0 = 失败，1 = 成功）。
     *         当 share/ 文件夹不存在、文件操作失败或权限不足时返回 0。
     * @see getShareFolderUri
     * @see copyFileWithConflictSuffix
     */
    suspend fun copySingleFile(sourcePdfUri: Uri): Int = withContext(Dispatchers.IO) {
        // 获取 share/ 文件夹（不自动创建，绑定库时已由 MainActivity 创建）
        val shareFolderUri = getShareFolderUri() ?: return@withContext 0
        val shareFolder = DocumentFile.fromTreeUri(context, shareFolderUri) ?: return@withContext 0

        var copied = 0
        if (copyFileWithConflictSuffix(sourcePdfUri, shareFolder)) {
            copied++
        }
        Log.i(TAG, "copySingleFile: 已复制 $copied 个文件")
        copied
    }

    /**
     * ## 复制单个 PDF 文件到指定目标目录
     *
     * 允许用户选择任意目标文件夹（通过 [ShareTargetPicker]），
     * 将文件复制到用户指定的位置，而不是默认的 share/ 目录。
     *
     * ─── 调用场景 ──────────────────────────────────────────────
     * - 文件详情页的"分享此文件"按钮，配合 [ShareTargetPicker] 使用。
     *   用户先选择或创建目标文件夹，然后该文件将被复制到所选位置。
     *
     * ─── 执行流程 ──────────────────────────────────────────────
     * 1. 通过 [DocumentFile.fromTreeUri] 或 [DocumentFile.fromSingleUri] 解析目标文件夹 Uri。
     * 2. 校验 Uri 是否为目录，若非目录则记录错误并返回 0。
     * 3. 调用 [copyFileWithConflictSuffix] 执行实际复制。
     *
     * @param sourcePdfUri 源 PDF 文件的 SAF Uri。
     * @param targetFolderUri 目标文件夹的 SAF Uri。必须是一个目录的 Uri，
     *                        由 [ShareTargetPicker] 或系统 SAF 文件选择器提供。
     * @return 复制成功的文件数量（0 = 失败，1 = 成功）。
     *         当目标不是目录、文件操作失败或权限不足时返回 0。
     * @see copyFileWithConflictSuffix
     */
    suspend fun copySingleFileToTarget(
        sourcePdfUri: Uri,
        targetFolderUri: Uri
    ): Int = withContext(Dispatchers.IO) {
        val targetFolder = DocumentFile.fromTreeUri(context, targetFolderUri)
            ?: DocumentFile.fromSingleUri(context, targetFolderUri)
            ?: return@withContext 0

        if (!targetFolder.isDirectory) {
            Log.e(TAG, "copySingleFileToTarget: targetFolderUri 不是目录")
            return@withContext 0
        }

        var copied = 0
        if (copyFileWithConflictSuffix(sourcePdfUri, targetFolder)) {
            copied++
        }
        Log.i(TAG, "copySingleFileToTarget: 已复制 $copied 个文件到 ${targetFolder.name}")
        copied
    }

    /**
     * ## 复制多个 PDF 文件到目标文件夹
     *
     * 支持批量分享：遍历传入的文件 Uri 列表，逐个复制到目标位置。
     * 每个文件独立复制，部分文件失败不影响其余文件的复制。
     *
     * ─── 调用场景 ──────────────────────────────────────────────
     * - 批量选择页面中的"一键分享"功能。
     * - 目标为 share/ 目录或 share/ 下的子文件夹。
     * - 用户选择了多个文件后，点击分享按钮时调用。
     *
     * ─── 注意事项 ──────────────────────────────────────────────
     * - 每个文件独立执行 [copyFileWithConflictSuffix]，相互之间无依赖。
     * - 如果某文件因权限、IO 错误等原因复制失败，会跳过该文件继续处理下一个。
     * - 返回值是成功复制的总数，调用方可根据返回值与源文件数量对比判断是否全部成功。
     *
     * @param sourceFiles 源 PDF 文件的 SAF Uri 列表。每个 Uri 对应一个 PDF 文件。
     * @param targetFolderUri 目标文件夹的 SAF Uri。必须是一个目录的 Uri。
     *                        不可为 null，否则方法会提前返回 0。
     * @return 复制成功的文件总数。范围 0..sourceFiles.size。
     *         与 [sourceFiles.size] 相等表示全部复制成功。
     *         小于 [sourceFiles.size] 表示部分文件复制失败。
     * @see copyFileWithConflictSuffix
     */
    suspend fun copyMultipleFiles(
        sourceFiles: List<Uri>,
        targetFolderUri: Uri
    ): Int = withContext(Dispatchers.IO) {
        val targetFolder = DocumentFile.fromTreeUri(context, targetFolderUri)
            ?: DocumentFile.fromSingleUri(context, targetFolderUri)
            ?: return@withContext 0

        if (!targetFolder.isDirectory) {
            Log.e(TAG, "copyMultipleFiles: targetFolderUri 不是目录")
            return@withContext 0
        }

        var copied = 0
        for (pdfUri in sourceFiles) {
            if (copyFileWithConflictSuffix(pdfUri, targetFolder)) {
                copied++
            }
        }
        Log.i(TAG, "copyMultipleFiles: 已复制 $copied 个 PDF 文件")
        copied
    }

    /**
     * ## 获取 share/ 文件夹的 SAF Uri
     *
     * 在用户库目录下查找 share/ 文件夹并返回其 SAF Uri。
     * 优先使用新路径 `database/share/`，兼容旧版本路径 `share/`（根目录）。
     *
     * ─── 调用场景 ──────────────────────────────────────────────
     * - [copySingleFile] 内部调用，获取默认分享目标目录。
     * - [getShareSubFolders] 内部调用，在 share/ 下查找子文件夹。
     * - [createShareSubFolder] 内部调用，在 share/ 下新建子文件夹。
     * - [ShareTargetPicker] 中用于展示 share/ 子文件夹列表给用户选择。
     *
     * ─── 路径查找逻辑 ──────────────────────────────────────────
     * 1. 从 [AppContainer.preferencesManager] 读取用户库根目录 SAF Uri。
     * 2. 查找 `database/` 子目录。
     * 3. 如果 `database/` 存在，在其中查找 `share/` 子目录。
     * 4. 如果新路径不存在，回退到根目录下查找 `share/` 文件夹（兼容旧版本）。
     * 5. 如果仍然不存在，返回 null。
     *
     * @return share/ 目录的 SAF Uri，可能存在但无内容。
     *         - null：share/ 文件夹不存在（例如用户库尚未建立，或 share/ 被删除）。
     *         - Uri：指向 share/ 文件夹，可进一步进行文件操作。
     * @see AppContainer.preferencesManager.getLibraryUri
     * @see DocumentFile.fromTreeUri
     */
    suspend fun getShareFolderUri(): Uri? = withContext(Dispatchers.IO) {
        val libraryUriString = AppContainer.preferencesManager.getLibraryUri() ?: return@withContext null
        val libraryUri = Uri.parse(libraryUriString)
        val libraryDoc = DocumentFile.fromTreeUri(context, libraryUri) ?: return@withContext null

        // 优先使用新路径：database/share/
        var shareDoc: DocumentFile? = null
        val databaseDir = libraryDoc.findFile("database")
        if (databaseDir != null && databaseDir.isDirectory) {
            shareDoc = databaseDir.findFile("share")
        }

        // 兼容旧版本：如果新路径不存在，检查根目录的 share/ 文件夹
        if (shareDoc == null) {
            shareDoc = libraryDoc.findFile(SHARE_FOLDER_NAME)
        }

        if (shareDoc == null || !shareDoc.isDirectory) return@withContext null
        return@withContext shareDoc.uri
    }

    /**
     * ## 获取 share/ 下的子文件夹列表
     *
     * ─── 调用场景 ──────────────────────────────────────────────
     * - [ShareTargetPicker] 中用于展示 share/ 目录下的子文件夹列表，
     *   供用户选择作为分享目标。
     *
     * @return List<Pair<String, Uri>> 子文件夹列表。每个元素是文件夹名和 SAF Uri 的配对。
     *         如果 share/ 目录不存在或没有子文件夹，返回空列表。
     *         文件夹名可能为"未命名"（当 SAF 无法获取名称时）。
     * @see getShareFolderUri
     */
    suspend fun getShareSubFolders(): List<Pair<String, Uri>> = withContext(Dispatchers.IO) {
        val shareUri = getShareFolderUri() ?: return@withContext emptyList()
        val shareDoc = DocumentFile.fromTreeUri(context, shareUri) ?: return@withContext emptyList()
        return@withContext shareDoc.listFiles()
            .filter { it.isDirectory }
            .map { (it.name ?: "未命名") to it.uri }
    }

    /**
     * ## 在 share/ 下新建子文件夹
     *
     * 用于用户在分享前创建个性化的分类文件夹。
     *
     * ─── 调用场景 ──────────────────────────────────────────────
     * - [ShareTargetPicker] 中用户点击"新建文件夹"按钮时调用。
     * - 用户希望按主题/日期/项目分类组织分享的文件时使用。
     *
     * ─── 注意事项 ──────────────────────────────────────────────
     * - 文件夹名称由用户输入，可以是中文、英文、数字等。
     * - 如果 share/ 目录不存在，创建失败并返回 null。
     * - 创建成功后，文件夹会立即出现在 [getShareSubFolders] 的返回结果中。
     * - 同名字覆盖：如果 share/ 下已存在同名文件夹，SAF 的行为取决于底层文件系统，
     *   通常会创建成功但实际可能是覆盖。调用方应做好 UI 侧的前置校验。
     *
     * @param name 新建文件夹的名称。建议校验非空、不含非法文件名字符。
     * @return 新建文件夹的 SAF Uri。创建失败（权限不足、磁盘满等）返回 null。
     * @see getShareFolderUri
     * @see DocumentFile.createDirectory
     */
    suspend fun createShareSubFolder(name: String): Uri? = withContext(Dispatchers.IO) {
        val shareUri = getShareFolderUri() ?: return@withContext null
        val shareDoc = DocumentFile.fromTreeUri(context, shareUri) ?: return@withContext null
        val newFolder = shareDoc.createDirectory(name) ?: return@withContext null
        Log.i(TAG, "createShareSubFolder: 已创建 $name")
        return@withContext newFolder.uri
    }

    // ═══════════════════════════════════════════════════════════
    //  私有方法（内部工具函数）
    // ═══════════════════════════════════════════════════════════

    /**
     * ## 复制单个文件到目标文件夹（含文件名冲突处理）
     *
     * 核心的文件复制方法，通过 [ContentResolver] 的输入/输出流实现文件读写。
     * 文件名冲突时自动追加数字后缀，避免覆盖已有文件。
     *
     * ─── 执行流程 ──────────────────────────────────────────────
     * 1. 通过 [DocumentFile.fromSingleUri] 获取源文件的 DocumentFile，提取文件名（sourceDoc.name）。
     * 2. 调用 [getNonConflictName] 解决文件名冲突，获得最终文件名。
     * 3. 通过 [targetFolder.createFile] 在目标目录创建新文件（SAF 方式）。
     * 4. 通过 [ContentResolver.openInputStream] 打开源文件输入流。
     * 5. 通过 [ContentResolver.openOutputStream] 打开目标文件输出流。
     * 6. 使用 [input.copyTo(output)] 执行字节流复制。
     * 7. 自动关闭输入/输出流（use 扩展函数）。
     *
     * ─── 异常处理 ──────────────────────────────────────────────
     * - [IOException]：IO 读写异常（磁盘满、文件损坏、路径无效等），记录错误并返回 false。
     * - [SecurityException]：SAF 权限不足，记录错误并返回 false。
     * - 其他未预期异常会向上传播，由调用方（coroutine 协程）的异常处理器捕获。
     *
     * @param sourceUri 源文件的 SAF Uri。必须指向一个存在的文件。
     * @param targetFolder 目标文件夹的 [DocumentFile] 引用。必须是一个目录，且应用对其有写入权限。
     * @return `true` = 复制成功；`false` = 复制失败（原因包括文件不存在、IO 错误、权限不足等）。
     * @see getNonConflictName 文件名冲突解析逻辑
     * @see android.content.ContentResolver.openInputStream
     * @see android.content.ContentResolver.openOutputStream
     */
    private fun copyFileWithConflictSuffix(sourceUri: Uri, targetFolder: DocumentFile): Boolean {
        val contentResolver = context.contentResolver
        val sourceDoc = DocumentFile.fromSingleUri(context, sourceUri) ?: return false
        val fileName = sourceDoc.name ?: return false

        val finalName = getNonConflictName(fileName, targetFolder)
        val mimeType = sourceDoc.type ?: "application/octet-stream"

        return try {
            val targetFile = targetFolder.createFile(mimeType, finalName) ?: return false
            contentResolver.openInputStream(sourceUri)?.use { input ->
                contentResolver.openOutputStream(targetFile.uri)?.use { output ->
                    input.copyTo(output)
                }
            }
            Log.d(TAG, "复制成功: $finalName")
            true
        } catch (e: IOException) {
            Log.e(TAG, "复制失败: $fileName", e)
            false
        } catch (e: SecurityException) {
            Log.e(TAG, "无权限复制: $fileName", e)
            false
        }
    }

    /**
     * ## 生成不冲突的文件名
     *
     * 检查目标文件夹中是否已存在同名文件。
     * 如果存在，在文件名后缀前追加 `_1`、`_2` 等递增数字直到不冲突。
     *
     * ─── 冲突解析逻辑 ──────────────────────────────────────────
     * 原始文件名为 "document.pdf"：
     * - 目标目录无 "document.pdf"            → 返回 "document.pdf"
     * - 目标目录已有 "document.pdf"           → 返回 "document_1.pdf"
     * - 目标目录已有 "document.pdf" 和 "document_1.pdf" → 返回 "document_2.pdf"
     * - 依次类推，直到找到不存在的文件名。
     *
     * ─── 边界情况 ──────────────────────────────────────────────
     * - 无扩展名的文件（如 "README"）：后缀追加在末尾 → "README_1"
     * - 文件名包含多个点（如 "my.report.pdf"）：只在最后一个点前插入后缀 → "my.report_1.pdf"
     *
     * @param originalName 原始文件名（含扩展名）。
     * @param targetFolder 目标文件夹的 [DocumentFile] 引用。用于获取已有文件列表。
     * @return 不重名的文件名。理论上死循环的概率极低（int 后缀溢出的概率可忽略不计）。
     * @see DocumentFile.listFiles 用于获取目标文件夹中已有的文件名集合
     */
    private fun getNonConflictName(originalName: String, targetFolder: DocumentFile): String {
        val existingNames = targetFolder.listFiles().mapNotNull { it.name }.toSet()
        if (!existingNames.contains(originalName)) return originalName

        val dotIndex = originalName.lastIndexOf('.')
        val baseName = if (dotIndex >= 0) originalName.substring(0, dotIndex) else originalName
        val extension = if (dotIndex >= 0) originalName.substring(dotIndex) else ""

        var suffix = 1
        while (true) {
            val newName = "${baseName}_${suffix}${extension}"
            if (!existingNames.contains(newName)) return newName
            suffix++
        }
    }
}
