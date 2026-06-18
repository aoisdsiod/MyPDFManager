package com.example.pdfmanager.data.local

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.pdf.PdfDocument
import android.util.Log
import com.example.pdfmanager.data.model.ImagePreviewInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * =============================================================================
 * ZipProcessor —— ZIP 文件处理器（单例对象）
 * =============================================================================
 *
 * 【用途】
 *   提供将 ZIP 文件中的图片提取并转换为 PDF 的核心功能。
 *   支持三种主要操作：
 *     1. extractZipToTempDir  - 解压到临时目录，获取预览信息
 *     2. getZipImageCount     - 仅统计图片数量（不读取图片数据）
 *     3. convertZipToPdf      - 将 ZIP 中的图片直接转为 PDF 文件
 *
 * 【图片排序】
 *   使用自然排序（natural sort），确保 1.jpg, 2.jpg, ..., 10.jpg 正确排序，
 *   而非字典序的 1.jpg, 10.jpg, 2.jpg。
 *
 * 【支持的图片格式】
 *   JPG / JPEG / PNG / WebP
 *
 * 【数据流】
 *   用户选择 ZIP 文件 → ContentResolver 获取 InputStream →
 *   ZipProcessor 处理 → 生成 PDF 文件或临时预览文件
 *
 * 【调用位置】
 *   - ConvertZipToPdfUseCase: ZIP 转 PDF 的核心用例
 *   - ImagePreviewViewModel: 解压后预览图片，供用户选择页码
 *   - ZipPickerDialog: 选择 ZIP 后获取图片数量信息
 */
object ZipProcessor {

    /**
     * 日志标签，用于统一过滤 ZipProcessor 相关的日志输出
     */
    private const val TAG = "ZipProcessor"

    /**
     * =====================================================================
     * 解压 ZIP 到临时目录，返回图片预览信息列表
     * =====================================================================
     *
     * 【功能】
     *   将 ZIP 文件中的所有图片提取到临时目录，同时返回 ImagePreviewInfo 列表
     *   供 UI 层展示预览图。提取时按文件名进行自然排序。
     *
     * 【临时文件命名】
     *   文件被保存为 "序号.扩展名" 格式（如 1.jpg, 2.png），
     *   原始文件名仅保留在 ImagePreviewInfo.fileName 中用于显示。
     *   这样避免了原始文件名中可能包含的特殊字符导致的路径问题。
     *
     * 【调用位置】
     *   - ImagePreviewViewModel.extractZip(): 用户选择 ZIP 后，
     *     先解压到临时目录，然后展示每张图片供用户选择。
     *
     * @param zipInputStream [InputStream] ZIP 文件的输入流
     *                       （由 ContentResolver 打开，调用方负责关闭）
     * @param tempDir        [File] 临时目录，调用方负责创建和清理
     *                       （建议使用 Context.cacheDir 下的子目录）
     * @return [List<ImagePreviewInfo>] 图片预览信息列表
     *         按文件名自然排序，index 从 1 开始（1-based）
     */
    suspend fun extractZipToTempDir(
        zipInputStream: InputStream,
        tempDir: File
    ): List<ImagePreviewInfo> = withContext(Dispatchers.IO) {
        // 存储 (原始文件名, 临时文件绝对路径) 对
        // 使用 Pair 保持两者关联，后续排序后仍能找到对应的文件路径
        val imageEntries = mutableListOf<Pair<String, String>>()

        // 使用 ZipInputStream 逐条读取 ZIP 条目
        ZipInputStream(zipInputStream).use { zis ->
            var entry: ZipEntry?
            // ZipInputStream.nextEntry() 返回下一个条目，没有更多时返回 null
            // 使用 also 技巧将 nextEntry 返回值赋值给 entry 变量后判断
            while (zis.nextEntry.also { entry = it } != null) {
                entry?.let { zipEntry ->
                    val name = zipEntry.name.lowercase()
                    // 只处理支持的图片格式
                    if (name.endsWith(".jpg") || name.endsWith(".jpeg") ||
                        name.endsWith(".png") || name.endsWith(".webp")
                    ) {
                        // 一次性读取整个条目的字节数据
                        val bytes = zis.readBytes()
                        // 确定文件扩展名，默认 .jpg
                        val ext = zipEntry.name.substringAfterLast('.', "jpg")
                        // 使用当前序号作为临时文件名，避免文件名冲突和特殊字符问题
                        val tempFile = File(tempDir, "${imageEntries.size + 1}.${ext}")
                        tempFile.writeBytes(bytes)
                        imageEntries.add(Pair(zipEntry.name, tempFile.absolutePath))
                    }
                }
            }
        }

        // 按文件名自然排序后，分配 1-based index
        return@withContext imageEntries
            .sortedBy { naturalSortKey(it.first) }
            .mapIndexed { idx, pair ->
                val (fileName, filePath) = pair
                ImagePreviewInfo(index = idx + 1, fileName = fileName, filePath = filePath)
            }
    }

    /**
     * =====================================================================
     * 统计 ZIP 文件中的图片数量（轻量扫描）
     * =====================================================================
     *
     * 【功能】
     *   仅遍历 ZIP 条目检查是否图片格式，不读取图片数据，性能高效。
     *   适用于需要提前知道图片总数的场景。
     *
     * 【注意】
     *   此方法会消费 zipInputStream（遍历到底），调用方如需再次读取
     *   该 ZIP 流，必须重新获取 InputStream。
     *
     * 【调用位置】
     *   - ZipPickerDialog: 用户选择 ZIP 后，先获取图片数量用于显示
     *     或验证 ZIP 中是否包含有效图片。
     *
     * @param zipInputStream [InputStream] ZIP 文件的输入流
     * @return [Int] ZIP 中的图片数量
     */
    fun getZipImageCount(zipInputStream: InputStream): Int {
        var count = 0
        ZipInputStream(zipInputStream).use { zis ->
            var entry: ZipEntry?
            while (zis.nextEntry.also { entry = it } != null) {
                entry?.let { zipEntry ->
                    val name = zipEntry.name.lowercase()
                    if (name.endsWith(".jpg") || name.endsWith(".jpeg") ||
                        name.endsWith(".png") || name.endsWith(".webp")
                    ) {
                        count++
                    }
                }
            }
        }
        return count
    }

    /**
     * =====================================================================
     * 将 ZIP 中的图片转换为 PDF 文件
     * =====================================================================
     *
     * 【功能】
     *   从 ZIP 流中提取图片，统一缩放到相同宽度，然后逐页写入 PDF 文档。
     *   支持进度回调、图片质量控制和选择性页码导出。
     *
     * 【算法流程】
     *   1. 提取图片并过滤选中页码
     *   2. 第一遍扫描：找出所有图片中的最大宽度
     *   3. 第二遍遍历：将每张图片等比例缩放到统一宽度（maxWidth），
     *      使所有页面宽度一致，高度自适应
     *   4. 写入 PDF 文件
     *
     * 【调用位置】
     *   - ConvertZipToPdfUseCase.invoke(): 用户确认转换后执行
     *
     * @param zipInputStream [InputStream] ZIP 文件的输入流
     * @param outputPdfFile [File] 输出的 PDF 文件路径
     * @param progress [MutableStateFlow<Pair<Int, Int>>] 进度回调
     *                 第一个 Int 是当前处理的页码（1-based），
     *                 第二个 Int 是总页码数
     * @param quality [Int] 图片质量 0-100，默认 85（仅在保存为 JPEG 时有效）
     * @param selectedPages [Set<Int>?] 选中的页码集合（1-based），
     *                       null 表示包含所有页码
     * @return [Boolean] true 表示转换成功，false 表示失败（无图片或异常）
     */
    suspend fun convertZipToPdf(
        zipInputStream: InputStream,
        outputPdfFile: File,
        progress: MutableStateFlow<Pair<Int, Int>>,
        quality: Int = 85,
        selectedPages: Set<Int>? = null
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            // ---- 第一步：从 ZIP 中提取图片数据 ----
            val images = extractImagesFromZip(zipInputStream, selectedPages)
            if (images.isEmpty()) {
                Log.w(TAG, "ZIP 中未找到图片")
                return@withContext false
            }

            val totalPages = images.size
            val pdfDocument = PdfDocument()

            // ---- 第二步：第一遍扫描，找出所有图片中的最大宽度 ----
            // 所有页面最终统一为最大宽度，保持视觉一致性
            var maxWidth = 0
            val bitmaps = mutableListOf<Bitmap>()
            images.forEachIndexed { index, imageBytes ->
                // 将字节数组解码为 Bitmap
                val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
                if (bitmap != null) {
                    bitmaps.add(bitmap)
                    if (bitmap.width > maxWidth) {
                        maxWidth = bitmap.width
                    }
                }
            }

            if (bitmaps.isEmpty()) {
                Log.w(TAG, "ZIP 中没有有效的图片")
                return@withContext false
            }

            Log.d(TAG, "所有图片中的最大宽度: $maxWidth")

            // ---- 第三步：第二遍遍历，逐页生成 PDF ----
            bitmaps.forEachIndexed { index, originalBitmap ->
                // 计算缩放比例，使当前图片宽度 = 最大宽度
                val scale = maxWidth.toFloat() / originalBitmap.width.toFloat()
                val scaledWidth = maxWidth
                // 高度按相同比例缩放，保持宽高比
                val scaledHeight = (originalBitmap.height * scale).toInt()

                // 创建缩放后的 Bitmap（如果不需要缩放则复用原图）
                val scaledBitmap = if (scale != 1.0f) {
                    Bitmap.createScaledBitmap(originalBitmap, scaledWidth, scaledHeight, true)
                } else {
                    originalBitmap
                }

                // 创建 PDF 页面，尺寸与缩放后的图片一致
                val pageInfo = PdfDocument.PageInfo.Builder(scaledWidth, scaledHeight, index + 1).create()
                val page = pdfDocument.startPage(pageInfo)
                val canvas = page.canvas

                // 将缩放后的图片绘制到页面左上角（铺满整页）
                canvas.drawBitmap(scaledBitmap, 0f, 0f, null)

                pdfDocument.finishPage(page)

                // 回收不再需要的 Bitmap 对象，释放内存
                if (scaledBitmap != originalBitmap) {
                    scaledBitmap.recycle()
                }
                originalBitmap.recycle()

                // 更新进度：当前页码 / 总页码
                progress.value = Pair(index + 1, totalPages)
            }

            // ---- 第四步：将 PDF 文档写入文件 ----
            FileOutputStream(outputPdfFile).use { fos ->
                pdfDocument.writeTo(fos)
            }
            pdfDocument.close()

            Log.i(TAG, "PDF 生成完成: ${outputPdfFile.absolutePath}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "ZIP 转 PDF 时发生异常", e)
            false
        }
    }

    /**
     * =====================================================================
     * 从 ZIP 流中提取图片数据（内部方法）
     * =====================================================================
     *
     * 【功能】
     *   遍历 ZIP 文件的所有条目，过滤出支持的图片格式，可选地只提取
     *   指定页码的图片。提取后的图片数据按文件名自然排序返回。
     *
     * 【调用位置】
     *   仅被 convertZipToPdf() 方法内部调用
     *
     * @param zipInputStream [InputStream] ZIP 文件的输入流
     * @param selectedPages [Set<Int>?] 选中的页码集合（1-based），
     *                       null 表示提取所有图片
     * @return [List<ByteArray>] 图片字节数据列表，已按文件名排序
     */
    private fun extractImagesFromZip(zipInputStream: InputStream, selectedPages: Set<Int>? = null): List<ByteArray> {
        val imageEntries = mutableListOf<Pair<String, ByteArray>>()

        ZipInputStream(zipInputStream).use { zis ->
            var entry: ZipEntry?
            var currentIndex = 0  // 仅用于计数图片条目，与 selectedPages 配合
            while (zis.nextEntry.also { entry = it } != null) {
                entry?.let { zipEntry ->
                    val name = zipEntry.name.lowercase()
                    if (name.endsWith(".jpg") || name.endsWith(".jpeg") ||
                        name.endsWith(".png") || name.endsWith(".webp")
                    ) {
                        currentIndex++
                        // 如果指定了选中页码，只读取选中的图片
                        // selectedPages 为 null 表示全选
                        if (selectedPages == null || selectedPages.contains(currentIndex)) {
                            val bytes = zis.readBytes()
                            imageEntries.add(Pair(zipEntry.name, bytes))
                        }
                    }
                }
            }
        }

        // 按文件名自然排序，确保页面顺序合理
        return imageEntries.sortedBy { naturalSortKey(it.first) }.map { it.second }
    }

    /**
     * =====================================================================
     * 自然排序键生成函数（内部方法）
     * =====================================================================
     *
     * 【功能】
     *   将文件名中的数字部分补零到 10 位，使得字符串排序时数字按数值排序。
     *   例如："2.jpg" → "0000000002.jpg"，"10.jpg" → "0000000010.jpg"，
     *   排序后 2 < 10，而非字典序的 "10" < "2"。
     *
     * 【原理】
     *   使用正则表达式 (\d+) 匹配文件名中所有连续数字，
     *   将每个数字转为 Long 后再用 0 填充到 10 位。
     *   10 位的长度足够覆盖 0 ~ 9,999,999,999 的范围。
     *
     * 【调用位置】
     *   被 extractZipToTempDir() 和 extractImagesFromZip() 内部调用
     *
     * @param filename [String] 原始文件名，如 "page_2.jpg"
     * @return [String] 排序键，如 "page_0000000002.jpg"
     */
    private fun naturalSortKey(filename: String): String {
        val regex = Regex("(\\d+)")
        return regex.replace(filename) { matchResult ->
            matchResult.value.toLong().toString().padStart(10, '0')
        }
    }
}
