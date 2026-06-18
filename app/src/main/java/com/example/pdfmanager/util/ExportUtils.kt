package com.example.pdfmanager.util

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import com.example.pdfmanager.data.repository.AppContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * ## 导出工具类（单例）
 *
 * 负责将指定文件夹中的 PDF 文件对应的数据库信息（标签、备注等）导出为
 * TXT 格式的分享文件。导出结果文件的文件名固定为 **"导出分享.txt"**，
 * 存放在用户通过 SAF（Storage Access Framework）选择的文件夹中。
 *
 * ---
 * ### 功能概述
 * 1. **检查** —— 检查目标文件夹是否已存在导出文件（[checkExistingExportFile]）
 * 2. **导出** —— 扫描文件夹中的 PDF 文件，匹配数据库记录，生成包含
 *    文件名、标签（按类别分组）、备注信息的文本内容，并写入 TXT 文件
 *    （[exportPdfInfoToTxt]）
 * 3. **覆盖** —— 若目标文件夹已有"导出分享.txt"，会先删除再创建（覆盖写入）
 *
 * ---
 * ### 调用位置
 * 仅在 `SettingsScreen.kt` 中被调用：
 * | 场景 | 函数 | 行号 |
 * |------|------|------|
 * | 用户选择导出目录后，检查是否已有导出文件 | [checkExistingExportFile] | 第 634 行 |
 * | 确认覆盖后执行导出的协程 | [exportPdfInfoToTxt] | 第 642 行、第 685 行 |
 * | 覆盖确认弹窗的描述文案 | [EXPORT_FILE_NAME] | 第 672-673 行（注释提及） |
 *
 * ---
 * ### 文件生成逻辑
 * 1. 获取用户选中文件夹内所有 PDF 文件
 * 2. 从 [AppContainer] 的 `pdfRepository` 获取所有数据库文件记录
 * 3. 从 [AppContainer] 的 `tagRepository` 获取标签类别
 * 4. 按文件名（不含扩展名）进行匹配
 * 5. 对匹配到的文件，组装文本内容（含标签分类、备注）
 * 6. 通过 `ContentResolver` 写入到 SAF 创建的 TXT 文件中
 */
object ExportUtils {

    /**
     * 导出文件名常量
     *
     * 固定为 **"导出分享.txt"**，不可更改。
     * - 若目标文件夹已存在此文件，导出前会先删除再创建（覆盖）
     * - 在 `SettingsScreen.kt` 的覆盖确认弹窗中引用此文件名
     */
    private const val EXPORT_FILE_NAME = "导出分享.txt"

    /**
     * ## 导出结果数据类
     *
     * 封装导出操作的返回结果，供调用方（UI 层）判断导出是否成功并获取相应信息。
     *
     * @property success 导出是否成功
     *   - `true`：导出成功，文件已写入
     *   - `false`：导出失败，可通过 [message] 获取失败原因
     *
     * @property message 结果描述消息
     *   - 成功时："已导出 N 个文件的信息到 导出分享.txt"
     *   - 失败时：具体失败原因，如"所选文件夹中没有PDF文件"、"数据库中没有任何文件记录"、
     *     "未找到匹配的数据库记录"、"无法覆盖已有文件，请检查文件夹权限"、"创建文件失败"、
     *     "导出失败：${异常信息}"
     *
     * @property exportedCount 成功导出的文件数量（仅在成功时有意义）
     *   - 通过此数值可在 UI 上展示导出摘要
     *
     * @property content 导出的完整文本内容（仅在成功时有意义）
     *   - 用于"复制到剪贴板"功能（见 `SettingsScreen.kt` 第 644 行）
     *   - 格式示例：
     *     ```
     *     ——————————————————
     *     文件名（不含后缀）
     *
     *     类别1：标签A、标签B
     *     类别2：标签C
     *
     *     备注信息：
     *     备注内容
     *     ——————————————————
     *     ```
     */
    data class ExportResult(
        val success: Boolean,
        val message: String,
        val exportedCount: Int = 0,
        val content: String = ""
    )

    /**
     * ## 检查目标文件夹是否已有导出文件
     *
     * 在用户执行导出操作前，先检查所选文件夹中是否已存在名为
     * [EXPORT_FILE_NAME]（"导出分享.txt"）的文件。
     *
     * ### 调用场景（SettingsScreen.kt）
     * 第 634 行，在用户选择导出目录后立即调用：
     * ```
     * val existingFile = ExportUtils.checkExistingExportFile(folder)
     * if (existingFile != null) {
     *     // 已有文件 → 显示覆盖确认弹窗
     *     showExportOverwriteDialog = true
     * } else {
     *     // 没有文件 → 直接导出
     *     coroutineScope.launch { exportPdfInfoToTxt(...) }
     * }
     * ```
     *
     * @param folder 用户通过 SAF 选择的目标文件夹（[DocumentFile] 类型）
     *   - 必须是一个已存在的目录
     *   - 由 `DocumentFile.fromTreeUri(context, Uri.parse(targetUri))` 创建
     *
     * @return 查找结果
     *   - 若文件夹中存在名为 "导出分享.txt" 的文件，返回该文件的 [DocumentFile] 对象
     *   - 若不存在、或 [folder.listFiles()] 返回 null，则返回 `null`
     */
    fun checkExistingExportFile(folder: DocumentFile): DocumentFile? {
        return folder.listFiles()?.find {
            it.isFile && it.name == EXPORT_FILE_NAME
        }
    }

    /**
     * ## 导出 PDF 文件信息到 TXT 文件（挂起函数）
     *
     * 核心导出方法，流程如下：
     *
     * 1. **扫描 PDF 文件** —— 列出目标文件夹中所有扩展名为 `.pdf`（不区分大小写）的文件
     * 2. **获取数据库记录** —— 从 [AppContainer.pdfRepository] 获取数据库中所有文件记录
     * 3. **获取标签类别** —— 从 [AppContainer.tagRepository] 获取所有标签类别名称
     * 4. **按文件名匹配** —— 对每个 PDF 文件（按不含后缀的文件名），在数据库中查找同名记录
     * 5. **组装内容** —— 对匹配到的文件，生成包含以下信息的文本块：
     *    - 文件名（不含扩展名）
     *    - 标签（按类别分组，同一类别标签用"、"连接，不同类别分行显示）
     *    - 备注信息（若不为空）
     * 6. **写入文件** —— 通过 SAF 的 `ContentResolver` 写入 TXT 文件
     *    - 若目标文件夹已有同名文件，先删除再创建（覆盖写入）
     *
     * ### 线程调度
     * - 使用 `withContext(Dispatchers.IO)` 将全部操作切换到 IO 线程执行
     * - 避免在主线程执行文件 I/O 和数据库查询操作
     *
     * ### 调用场景（SettingsScreen.kt）
     * 在两个地方被调用：
     * - 第 642 行：用户选择目录后且无已有文件时，直接导出
     * - 第 685 行：用户在覆盖确认弹窗中点击"确认"后，覆盖导出
     *
     * 调用方使用协程启动（`coroutineScope.launch { ... }`）：
     * ```
     * val result = ExportUtils.exportPdfInfoToTxt(context, folder)
     * if (result.success) {
     *     pendingExportContent = result.content
     *     showExportCopyDialog = true
     * } else {
     *     Toast.makeText(context, result.message, Toast.LENGTH_SHORT).show()
     * }
     * ```
     *
     * @param context Android [Context] 实例
     *   - 用于通过 `contentResolver.openOutputStream()` 写入文件
     *   - 由 `SettingsScreen.kt` 传入当前 Composable 的 `LocalContext.current`
     *
     * @param folder 用户选定的目标文件夹（[DocumentFile]）
     *   - 由 SAF 返回的 URI 创建，必须是一个可写目录
     *
     * @return [ExportResult] 导出结果
     *   - **成功**：`ExportResult(true, "已导出 N 个文件的信息到 导出分享.txt", N, content)`
     *   - **失败场景**：
     *     | 条件 | message |
     *     |------|---------|
     *     | 文件夹中无 PDF 文件 | "所选文件夹中没有PDF文件" |
     *     | 数据库无记录 | "数据库中没有任何文件记录" |
     *     | 无文件匹配成功 | "未找到匹配的数据库记录" |
     *     | 无法删除已有文件 | "无法覆盖已有文件，请检查文件夹权限" |
     *     | 创建文件失败 | "创建文件失败" |
     *     | 发生异常 | "导出失败：${异常信息}" |
     *
     * @throws 不会主动抛出异常，所有异常都会被捕获并包装为 [ExportResult] 返回
     */
    suspend fun exportPdfInfoToTxt(context: Context, folder: DocumentFile): ExportResult = withContext(Dispatchers.IO) {
        try {
            // ════════════════════════════════════════════════════════════════
            // 第 1 步：列出文件夹中所有 PDF 文件
            // ════════════════════════════════════════════════════════════════
            val pdfFilesInFolder = folder.listFiles()?.filter {
                it.isFile && it.name?.endsWith(".pdf", ignoreCase = true) == true
            } ?: emptyList()

            // 如果文件夹中没有 PDF 文件，提前返回失败结果
            if (pdfFilesInFolder.isEmpty()) {
                return@withContext ExportResult(false, "所选文件夹中没有PDF文件")
            }

            // ════════════════════════════════════════════════════════════════
            // 第 2 步：从数据库获取所有文件记录（用于按文件名匹配）
            // ════════════════════════════════════════════════════════════════
            val dbFiles = AppContainer.pdfRepository.getAllFiles()

            // 如果数据库为空，提前返回失败结果
            if (dbFiles.isEmpty()) {
                return@withContext ExportResult(false, "数据库中没有任何文件记录")
            }

            // ════════════════════════════════════════════════════════════════
            // 第 3 步：获取所有标签类别（用于在输出中显示类别名称）
            // ════════════════════════════════════════════════════════════════
            val categories = AppContainer.tagRepository.getCategories()

            // ════════════════════════════════════════════════════════════════
            // 第 4 步：遍历 PDF 文件，与数据库记录匹配并构建导出内容
            // ════════════════════════════════════════════════════════════════
            val content = StringBuilder()
            var matchedCount = 0

            for (pdfFile in pdfFilesInFolder) {
                // 获取文件名（可能为 null，此时跳过该文件）
                val folderFileName = pdfFile.name ?: continue
                // 去除扩展名，仅保留文件名主体进行匹配
                val folderFileNameWithoutExt = folderFileName.substringBeforeLast(".")

                // 在数据库记录中查找匹配项：按文件名（不含后缀）不区分大小写匹配
                val matchedFile = dbFiles.find { dbFile ->
                    val dbFileName = dbFile.name.substringBeforeLast(".")
                    dbFileName.equals(folderFileNameWithoutExt, ignoreCase = true)
                }

                if (matchedFile != null) {
                    matchedCount++

                    // ── 文件间分割线 ──
                    content.append("——————————————————\n")

                    // ── 文件名（不含扩展名） ──
                    content.append(folderFileNameWithoutExt).append("\n")

                    // 段间空行
                    content.append("\n")

                    // ── 标签信息（从数据库查询，不依赖内存中的 matchedFile.tags）──
                    // 通过 matchedFile.uri 重新查询该文件的标签，确保数据最新
                    val tagEntities = AppContainer.tagRepository.getTagsForPdf(matchedFile.uri.toString())

                    if (tagEntities.isNotEmpty()) {
                        // 按类别 ID 分组标签，reversed() 使类别顺序与创建顺序一致
                        val tagsByCategory = tagEntities.groupBy { it.categoryId }
                        for ((categoryId, tags) in tagsByCategory.entries.reversed()) {
                            // 根据 categoryId 查找类别名称，若找不到则回退显示 categoryId 本身
                            val categoryName = categories.find { it.id == categoryId }?.name ?: categoryId
                            // 格式：类别名称：标签值1、标签值2
                            content.append("$categoryName：")
                            content.append(tags.joinToString("、") { it.tagValue })
                            content.append("\n")
                        }
                    }

                    // 段间空行
                    content.append("\n")

                    // ── 备注信息 ──
                    if (matchedFile.notes.isNotBlank()) {
                        content.append("备注信息：\n${matchedFile.notes}\n")
                    }
                }
            }

            // ── 末尾分割线 ──
            content.append("——————————————————")

            // 如果没有文件匹配成功，返回失败结果
            if (matchedCount == 0) {
                return@withContext ExportResult(false, "未找到匹配的数据库记录")
            }

            // ════════════════════════════════════════════════════════════════
            // 第 5 步：创建/覆盖 TXT 文件
            // ════════════════════════════════════════════════════════════════

            // 若已存在同名文件则先删除，确保覆盖写入
            val existingFile = folder.listFiles()?.find { it.name == EXPORT_FILE_NAME }
            if (existingFile != null) {
                val deleted = existingFile.delete()
                if (!deleted) {
                    // 删除失败（通常是权限问题），返回失败结果
                    return@withContext ExportResult(false, "无法覆盖已有文件，请检查文件夹权限")
                }
            }

            // 通过 SAF 创建新文件（MIME 类型：text/plain）
            val txtFile = folder.createFile("text/plain", EXPORT_FILE_NAME)
            if (txtFile != null) {
                // ── 写入内容到文件 ──
                val outputStream = context.contentResolver.openOutputStream(txtFile.uri)
                outputStream?.write(content.toString().toByteArray(Charsets.UTF_8))
                outputStream?.flush()
                outputStream?.close()

                // 导出成功，返回成功结果
                return@withContext ExportResult(
                    success = true,
                    message = "已导出 $matchedCount 个文件的信息到 $EXPORT_FILE_NAME",
                    exportedCount = matchedCount,
                    content = content.toString()
                )
            } else {
                // 创建文件失败（SAF 返回 null）
                return@withContext ExportResult(false, "创建文件失败")
            }
        } catch (e: Exception) {
            // 捕获所有异常（包括 IO 异常、数据库查询异常等），返回失败结果
            return@withContext ExportResult(false, "导出失败：${e.message}")
        }
    }
}
