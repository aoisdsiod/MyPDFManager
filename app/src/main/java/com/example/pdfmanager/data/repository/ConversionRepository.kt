package com.example.pdfmanager.data.repository

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import com.example.pdfmanager.data.local.PreferencesManager
import com.example.pdfmanager.data.model.PdfFile
import com.example.pdfmanager.data.model.ZipFileInfo
import com.example.pdfmanager.data.model.ConversionProgress
import com.example.pdfmanager.data.local.ZipProcessor

/**
 * 转换仓库 —— ZIP 转 PDF 的核心管理类
 *
 * 【功能说明】
 * 1. 扫描 ZIP 文件：从库内 zip/ 目录和用户指定的额外监控文件夹中扫描所有 .zip 文件
 * 2. 执行转换：遍历 ZIP 文件列表，逐个调用 ZipProcessor 完成 ZIP→PDF 转换
 * 3. 进度报告：通过 MutableStateFlow 向 UI 层实时报告转换进度（当前文件、页数进度）
 * 4. 资源清理：转换成功后将临时文件复制到目标目录，删除原始 ZIP 文件
 * 5. 缩略图生成：转换完成后立即为新生成的 PDF 生成缩略图
 * 6. 后处理入库：将转换后的 PDF 文件信息通过 PdfRepository 添加到内存列表，刷新 UI
 *
 * 【数据流】
 * UI (ConversionScreen) → ConversionRepository.scanZipFiles() → 返回 ZipFileInfo 列表
 * UI → ConversionRepository.convertZipFiles() → 实时进度 via StateFlow → 转换完成 → 入库
 *
 * 【调用位置】
 * - com.example.pdfmanager.ui.conversion.ConversionScreen (扫描 + 转换入口)
 * - com.example.pdfmanager.data.repository.AppContainer (持有单例)
 */
class ConversionRepository(private val context: Context) {

    /** 日志标签，用于 Logcat 过滤 */
    private val TAG = "ConversionRepository"

    /** 首选项管理器，用于读写用户偏好设置（库路径、监控文件夹等） */
    private val preferencesManager = PreferencesManager(context)

    /**
     * 扫描所有来源中的 .zip 文件
     *
     * 【扫描来源】
     * 1. 库内 zip/ 目录：优先查找 database/zip/（新路径），不存在则回退到 zip/（旧路径兼容）
     * 2. 额外监控文件夹：用户通过 SAF（Storage Access Framework）选择的文件夹（递归扫描）
     *
     * 【执行时机】
     * - 用户进入"ZIP 转换"页面时调用
     * - 用户点击"刷新"按钮时调用
     *
     * @return ZipFileInfo 列表，按文件名排序
     */
    suspend fun scanZipFiles(): List<ZipFileInfo> = withContext(Dispatchers.IO) {
        val result = mutableListOf<ZipFileInfo>()

        // ====== 1. 扫描库内 zip/ 目录（新路径：database/zip/，兼容旧路径：zip/） ======
        val libraryUriString = preferencesManager.getLibraryUri()
        if (!libraryUriString.isNullOrEmpty()) {
            val libraryUri = Uri.parse(libraryUriString)
            val libraryDoc = DocumentFile.fromTreeUri(context, libraryUri)

            // 优先使用新路径：database/zip/
            var zipDir: DocumentFile? = null
            val databaseDir = libraryDoc?.findFile("database")
            if (databaseDir != null && databaseDir.isDirectory) {
                zipDir = databaseDir.findFile("zip")
            }

            // 兼容旧版本：如果新路径不存在，检查根目录的 zip/ 文件夹
            if (zipDir == null) {
                zipDir = libraryDoc?.findFile("zip")
            }

            if (zipDir != null && zipDir.isDirectory) {
                zipDir.listFiles().forEach { file ->
                    if (file.isFile && file.name?.endsWith(".zip") == true) {
                        result.add(
                            ZipFileInfo(
                                name = file.name ?: "unknown.zip",
                                uri = file.uri,
                                size = file.length(),
                                lastModified = file.lastModified()
                            )
                        )
                    }
                }
            }
        }

        // ====== 2. 扫描额外监控文件夹（用户通过 SAF 自行选择） ======
        val monitorUriString = preferencesManager.getConversionMonitorFolder()
        if (!monitorUriString.isNullOrEmpty()) {
            val monitorUri = Uri.parse(monitorUriString)
            val monitorDoc = DocumentFile.fromTreeUri(context, monitorUri)
            if (monitorDoc != null) {
                scanDirectoryForZip(monitorDoc, result)
            }
        }

        // 按文件名排序，方便用户查找
        result.sortBy { it.name }
        Log.i(TAG, "Scanned ${result.size} zip files")
        result
    }

    /**
     * 递归扫描目录中的 .zip 文件
     *
     * 【递归策略】
     * - 遇到 .zip 文件则添加到结果列表
     * - 遇到子目录则递归进入继续扫描（支持多层级嵌套）
     *
     * 【调用位置】
     * - scanZipFiles() 中扫描额外监控文件夹时调用
     *
     * @param dir    当前要扫描的 DocumentFile 目录
     * @param result 结果列表，扫描结果会追加到此列表中
     */
    private fun scanDirectoryForZip(dir: DocumentFile, result: MutableList<ZipFileInfo>) {
        dir.listFiles().forEach { file ->
            when {
                // 情况1：是 .zip 文件，直接加入结果列表
                file.isFile && file.name?.endsWith(".zip") == true -> {
                    result.add(
                        ZipFileInfo(
                            name = file.name ?: "unknown.zip",
                            uri = file.uri,
                            size = file.length(),
                            lastModified = file.lastModified()
                        )
                    )
                }
                // 情况2：是目录，递归扫描
                file.isDirectory -> {
                    scanDirectoryForZip(file, result)
                }
            }
        }
    }

    /**
     * 执行 ZIP→PDF 转换
     *
     * 【转换流程】
     * 1. 确定输出目录（用户指定或库根目录）
     * 2. 检查磁盘空间是否充足
     * 3. 逐文件转换：
     *    a. 创建输出 PDF 文件（在目标目录下）
     *    b. 读取 ZIP 文件输入流
     *    c. 创建临时文件用于存放转换结果
     *    d. 收集 ZIP 解压进度并同步到 UI 层
     *    e. 调用 ZipProcessor 执行实际转换
     *    f. 成功则将临时文件复制到目标 URI
     *    g. 删除原始 ZIP 文件
     *    h. 生成缩略图
     *    i. 构造 PdfFile 对象，调用 PdfRepository.addFile() 入库
     * 4. 全部完成后清除进度状态
     *
     * 【调用位置】
     * - com.example.pdfmanager.ui.conversion.ConversionScreen (用户点击"开始转换"时)
     *
     * @param zipFiles     要转换的 ZIP 文件信息列表（由 scanZipFiles 返回）
     * @param outputDirUri 输出目录 URI，null 表示使用库根目录作为输出位置
     * @param progress     进度状态流，用于向 UI 层报告当前转换进度
     * @return Pair<成功数, 失败文件名列表>，例如 Pair(3, listOf("file2.zip", "file5.zip"))
     */
    suspend fun convertZipFiles(
        zipFiles: List<ZipFileInfo>,
        outputDirUri: Uri?,
        progress: MutableStateFlow<ConversionProgress?>
    ): Pair<Int, List<String>> = withContext(Dispatchers.IO) {
        val totalFiles = zipFiles.size
        var successCount = 0
        val failedFiles = mutableListOf<String>()

        // ====== 确定输出目录 ======
        // 优先级：用户指定目录 > 库根目录
        val outputDir = if (outputDirUri != null) {
            DocumentFile.fromTreeUri(context, outputDirUri)
        } else {
            val libraryUriString = preferencesManager.getLibraryUri()
            if (!libraryUriString.isNullOrEmpty()) {
                DocumentFile.fromTreeUri(context, Uri.parse(libraryUriString))
            } else {
                null
            }
        }

        // 输出目录为空，所有文件全部标记为失败
        if (outputDir == null) {
            Log.e(TAG, "Output directory is null")
            return@withContext Pair(0, zipFiles.map { it.name })
        }

        // ====== 检查磁盘空间 ======
        if (!hasEnoughSpace(zipFiles)) {
            Log.e(TAG, "Not enough disk space")
            return@withContext Pair(0, zipFiles.map { it.name })
        }

        // ====== 逐文件转换 ======
        zipFiles.forEachIndexed { index, zipFileInfo ->
            val fileName = zipFileInfo.name

            // 更新进度：开始处理当前文件（当前文件名、文件序号）
            progress.update {
                ConversionProgress(
                    currentFileName = fileName,
                    fileIndex = index + 1,
                    totalFiles = totalFiles
                )
            }

            try {
                // ---- 步骤1：创建输出 PDF 文件（将 .zip 后缀替换为 .pdf） ----
                val pdfFileName = fileName.replace(".zip", ".pdf", ignoreCase = true)
                var outputPdfFile = outputDir.findFile(pdfFileName)
                if (outputPdfFile == null) {
                    outputPdfFile = outputDir.createFile("application/pdf", pdfFileName)
                }

                if (outputPdfFile == null) {
                    Log.e(TAG, "Failed to create output file: $pdfFileName")
                    failedFiles.add(fileName)
                    return@forEachIndexed
                }

                // ---- 步骤2：打开 ZIP 文件的输入流 ----
                val contentResolver = context.contentResolver
                val zipInputStream = contentResolver.openInputStream(zipFileInfo.uri)
                if (zipInputStream == null) {
                    Log.e(TAG, "Failed to open zip file: $fileName")
                    failedFiles.add(fileName)
                    return@forEachIndexed
                }

                // ---- 步骤3：创建临时文件用于中转转换结果 ----
                val tempFile = java.io.File(context.cacheDir, "temp_conversion_$index.pdf")

                // ---- 步骤4：设置 ZIP 解压进度监听器 ----
                // zipProgress 中的 Pair<当前页数, 总页数>
                val zipProgress = MutableStateFlow(Pair(0, 0))

                // 启动协程，将 ZIP 解压进度映射到 UI 层主进度流
                val scope = CoroutineScope(Dispatchers.IO)
                val job = scope.launch {
                    zipProgress.collect { (current, total) ->
                        progress.update { currentProgress ->
                            currentProgress?.copy(
                                currentPage = current,
                                totalPages = total
                            )
                        }
                    }
                }

                // ---- 步骤5：调用 ZipProcessor 执行实际转换 ----
                // ZipProcessor.convertZipToPdf 负责解压 ZIP 并合成 PDF
                val success = ZipProcessor.convertZipToPdf(
                    zipInputStream = zipInputStream,
                    outputPdfFile = tempFile,
                    progress = zipProgress
                )

                job.cancel()  // 停止进度监听协程

                if (success) {
                    // ---- 步骤6：转换成功，将临时文件写入目标 URI ----
                    Log.i(TAG, "转换成功: $fileName, 准备写入输出文件")
                    val outputStream = contentResolver.openOutputStream(outputPdfFile.uri, "wt")
                    if (outputStream != null) {
                        Log.d(TAG, "输出流已打开: $pdfFileName")
                        tempFile.inputStream().use { input ->
                            outputStream.use { output ->
                                input.copyTo(output)
                            }
                        }
                        Log.i(TAG, "文件已写入: $pdfFileName")
                        successCount++
                        Log.i(TAG, "successCount=$successCount")

                        // ---- 步骤7：删除原始 ZIP 文件（使用 DocumentFile API 兼容 SAF URI） ----
                        try {
                            val docFile = DocumentFile.fromSingleUri(context, zipFileInfo.uri)
                            val deleted = docFile?.delete() ?: false
                            if (deleted) {
                                Log.i(TAG, "Deleted original zip: ${zipFileInfo.name}")
                            } else {
                                Log.e(TAG, "Failed to delete original zip: ${zipFileInfo.name}")
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error deleting original zip: ${zipFileInfo.name}", e)
                        }

                        // ---- 步骤8：生成缩略图并构造 PdfFile 对象入库 ----
                        val outputUri = outputPdfFile.uri
                        val id = UUID.nameUUIDFromBytes(outputUri.toString().toByteArray()).toString()
                        val displayName = outputPdfFile.name ?: pdfFileName
                        val name = PdfFile.extractNameFromDisplayName(displayName)

                        // 立即为生成的 PDF 生成缩略图，确保 UI 列表能立即显示
                        Log.i(TAG, "开始为 $displayName 生成缩略图...")
                        val thumbnailPath = com.example.pdfmanager.data.local.ThumbnailGenerator.getThumbnailPath(context, outputUri)
                        if (thumbnailPath == null) {
                            // 缩略图不存在，触发生成
                            Log.i(TAG, "缩略图不存在，正在生成...")
                            val generated = com.example.pdfmanager.data.local.ThumbnailGenerator.generate(context, outputUri)
                            if (generated) {
                                Log.i(TAG, "已生成缩略图: $displayName")
                            } else {
                                Log.e(TAG, "缩略图生成失败: $displayName")
                            }
                        } else {
                            Log.d(TAG, "缩略图已存在: $displayName, path: $thumbnailPath")
                        }

                        // 获取最终缩略图路径（可能刚刚生成）
                        val finalThumbnailPath = com.example.pdfmanager.data.local.ThumbnailGenerator.getThumbnailPath(context, outputUri)

                        // 构造 PdfFile 对象并添加到内存列表，UI 自动刷新
                        val newPdf = PdfFile(
                            id = id,
                            name = name,
                            displayName = displayName,
                            uri = outputUri,
                            size = outputPdfFile.length(),
                            lastModified = System.currentTimeMillis(),
                            thumbnailPath = finalThumbnailPath,
                            tags = emptyList(),
                            notes = "",
                            isFavorite = false,
                            thumbnailGenerated = 1  // 标记缩略图已生成，避免 UI 再次触发生成
                        )
                        Log.i(TAG, "准备调用 addFile(): $displayName")
                        AppContainer.pdfRepository.addFile(newPdf)
                        Log.i(TAG, "Added converted PDF to repository: $displayName, thumbnail: $finalThumbnailPath")
                    } else {
                        Log.e(TAG, "Failed to open output stream for: $pdfFileName")
                        failedFiles.add(fileName)
                    }
                } else {
                    Log.e(TAG, "Failed to convert: $fileName")
                    failedFiles.add(fileName)
                }

                // ---- 步骤9：清理临时资源 ----
                tempFile.delete()
                zipInputStream.close()

            } catch (e: Exception) {
                Log.e(TAG, "Error converting $fileName", e)
                failedFiles.add(fileName)
            }
        }

        // 全部处理完成，清除进度状态（UI 层据此隐藏进度条）
        progress.update { null }
        Log.i(TAG, "Conversion complete: $successCount success, ${failedFiles.size} failed")
        Pair(successCount, failedFiles)
    }

    /**
     * 检查磁盘空间是否足够进行转换
     *
     * 【估算算法】
     * - 计算所有 ZIP 文件的总大小
     * - 估算输出 PDF 文件总大小的 ≈ 总大小 × 0.8（经验系数，实际 PDF 通常比 ZIP 略小）
     * - 通过 StatFs 获取应用外部存储目录的可用空间
     *
     * 【调用位置】
     * - convertZipFiles() 方法开始转换前调用
     *
     * @param zipFiles 待转换的 ZIP 文件列表（用于计算总大小）
     * @return true=空间充足, false=空间不足
     */
    private fun hasEnoughSpace(zipFiles: List<ZipFileInfo>): Boolean {
        val totalSize = zipFiles.sumOf { it.size }
        // 估算转换后的 PDF 大小（PDF 通常比原始 ZIP 小约 20%）
        val estimatedOutputSize = (totalSize * 0.8).toLong()

        val outputDir = context.getExternalFilesDir(null) ?: return true
        val stat = android.os.StatFs(outputDir.path)
        val availableBytes = stat.availableBlocksLong * stat.blockSizeLong

        return availableBytes > estimatedOutputSize
    }

    /**
     * 获取额外监控文件夹的显示名称（用于 UI 展示）
     *
     * 【调用位置】
     * - ConversionScreen 设置面板中展示当前监控文件夹名称
     *
     * @return 文件夹名称字符串（如 "我的监控文件夹"），失败时返回错误提示
     */
    suspend fun getMonitorFolderDisplayName(): String = withContext(Dispatchers.IO) {
        val uriString = preferencesManager.getConversionMonitorFolder()
        if (uriString.isNullOrEmpty()) {
            "未选择"
        } else {
            try {
                val uri = Uri.parse(uriString)
                val doc = DocumentFile.fromTreeUri(context, uri)
                doc?.name ?: "未知文件夹"
            } catch (e: Exception) {
                "路径无效"
            }
        }
    }

    /**
     * 获取输出位置的显示名称（用于 UI 展示）
     *
     * 【调用位置】
     * - ConversionScreen 设置面板中展示当前输出路径名称
     *
     * @return 文件夹名称字符串（如 "PDF输出目录"），未设置时返回"库根目录"
     */
    suspend fun getOutputPathDisplayName(): String = withContext(Dispatchers.IO) {
        val uriString = preferencesManager.getConversionOutputPath()
        if (uriString.isNullOrEmpty()) {
            "库根目录"
        } else {
            try {
                val uri = Uri.parse(uriString)
                val doc = DocumentFile.fromTreeUri(context, uri)
                doc?.name ?: "未知文件夹"
            } catch (e: Exception) {
                "路径无效"
            }
        }
    }
}
