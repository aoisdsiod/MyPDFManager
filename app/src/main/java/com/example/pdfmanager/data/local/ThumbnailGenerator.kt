package com.example.pdfmanager.data.local

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.example.pdfmanager.data.local.PreferencesManager
import com.example.pdfmanager.util.Constants

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext


/**
 * 缩略图生成器（单例对象）—— PDF 缩略图生成和缓存管理的核心组件
 *
 * 【功能说明】
 * 1. 渲染 PDF 首页为 Bitmap（256px 宽度，高度按比例缩放）
 * 2. 保存到 app 私有目录（WebP 格式，质量 80，空间/速度平衡）
 * 3. 双层缓存策略：
 *    - 主存储：context.filesDir/thumbnails/<encoded_library_uri>/<fileName>.webp
 *      优势：与库结构对应的目录组织，便于管理
 *    - 快速缓存：context.cacheDir/thumbnails/thumb_<hash>.webp
 *      优势：普通文件 I/O，<1ms 检查存在性，<10ms 读取
 * 4. 缓存有效性验证：比较 PDF 文件的 lastModified 与缩略图的 lastModified
 *
 * 【触发时机（只有以下两个地方会调用 generate()）】
 * 1. 设置页面的"生成缩略图"按钮 → ThumbnailGenerationService 批量生成
 * 2. ZIP→PDF 转换完成后（ConversionRepository.convertZipFiles() 中单个生成）
 *
 * 【注意：哪些地方不会调用】
 * - 扫描阶段（ScanService / PdfFetcher）不会调用 generate()，只记录 PDF 文件信息
 * - UI 加载阶段（PdfAdapter / ImageLoader）不会调用 generate()，只显示占位图或从缓存读取
 * - 不会在 UI 主线程触发，避免卡顿
 *
 * 【存储策略演进】
 * 旧版：使用 SAF（Storage Access Framework）路径保存，I/O 慢（一次读取 >100ms）
 * 新版：仅使用 app 私有目录（/data/data/com.example.pdfmanager/files/thumbnails/），I/O 极快
 *
 * 【调用位置】
 * - com.example.pdfmanager.service.ThumbnailGenerationService (批量生成)
 * - com.example.pdfmanager.data.repository.ConversionRepository (转换后生成)
 * - com.example.pdfmanager.data.local.PdfFetcher / com.example.pdfmanager.ui.MainViewModel (读取缓存)
 */
object ThumbnailGenerator {

    /** 日志标签，用于 Logcat 过滤 */
    private const val TAG = "ThumbnailGenerator"

    /** 缩略图宽度尺寸：256px，高度按 PDF 比例自动缩放 */
    private const val THUMBNAIL_SIZE = 256

    /**
     * 为指定 PDF 文件生成缩略图并保存到私有目录
     *
     * 【执行流程】
     * 1. 检查私有目录中是否已有缓存文件
     * 2. 如果缓存存在，验证其有效性（比较 PDF 文件和缩略图的 lastModified）
     *    - 有效：直接返回 true（跳过生成）
     *    - 过期：删除旧缓存，重新生成
     * 3. 使用 PdfRenderer 渲染 PDF 首页为 Bitmap
     * 4. 将 Bitmap 保存到主存储（filesDir）和快速缓存（cacheDir）
     *
     * 【调用位置】
     * - ThumbnailGenerationService: 批量生成缩略图时对每个 PDF 调用
     * - ConversionRepository.convertZipFiles(): 转换完成单个 PDF 后调用
     *
     * @param context Android 上下文
     * @param pdfUri  PDF 文件的 Content URI（通过 SAF 获取）
     * @return true=生成/缓存命中成功, false=生成失败
     */
    suspend fun generate(context: Context, pdfUri: Uri): Boolean {
        return withContext(Dispatchers.IO) {
            // 获取库目录 URI（用于确定私有目录中的子文件夹路径）
            val libraryUriString = PreferencesManager(context).getLibraryUri()
                ?: return@withContext false

            // ====== 1. 检查私有目录中的缓存是否已存在 ======
            val privateFile = getPrivateThumbnailFile(context, libraryUriString, pdfUri)

            if (privateFile.exists()) {
                // 验证缓存是否有效：比较 PDF 文件修改时间和缩略图文件修改时间
                if (isCacheValid(context, pdfUri, privateFile)) {
                    Log.d(TAG, "generate: cache hit, skip: $pdfUri")
                    return@withContext true
                } else {
                    // 缓存已过期（PDF 文件被修改过），删除旧缓存重新生成
                    Log.w(TAG, "generate: cache expired, re-rendering: $pdfUri")
                    privateFile.delete()
                }
            }

            // ====== 2. 渲染缩略图 ======
            // 使用 Android 官方的 PdfRenderer API 渲染 PDF 首页
            val bitmap = renderPdfPage(context, pdfUri) ?: return@withContext false

            // ====== 3. 保存到缓存（主存储 + 快速缓存） ======
            saveThumbnail(context, pdfUri, bitmap)
        }
    }


    /**
     * 获取缩略图在 app 私有目录中的 File 对象
     *
     * 【目录结构】
     * /data/data/com.example.pdfmanager/files/
     *   └── thumbnails/
     *       └── <encoded_library_uri>/        ← 编码后的库 URI 作为子文件夹
     *           └── <fileName>.webp           ← 基于 PDF 文件名生成的缩略图文件名
     *
     * 【设计原因】
     * - 使用 libraryUri 编码作为子文件夹：支持多文库场景，不同库的缩略图隔离存储
     * - 使用私有目录而非 SAF：避免 SAF 的慢速 I/O 开销
     *
     * 例如：
     * libraryUri = "content://com.android.externalstorage.documents/tree/1234-5678:PDF"
     * encodedUri = "content_com_android_externalstorage_documents_tree_1234-5678_PDF"
     * 缩略图路径 = /data/data/.../files/thumbnails/content_com_..._PDF/document.webp
     *
     * @param context    Android 上下文
     * @param libraryUri 文库目录 URI 字符串（从 PreferencesManager 获取）
     * @param pdfUri     PDF 文件 URI（用于从原文件名生成缩略图文件名）
     * @return 缩略图文件的 File 对象（父目录会自动创建）
     */
    fun getPrivateThumbnailFile(context: Context, libraryUri: String, pdfUri: Uri): File {
        // 将 URI 中的特殊字符替换为下划线，得到安全的文件夹名（如 content_com_android_...）
        val encodedUri = encodeUriForFilename(libraryUri)
        val thumbnailDir = File(context.filesDir, "thumbnails/$encodedUri")
        if (!thumbnailDir.exists()) {
            thumbnailDir.mkdirs()
        }

        // 基于 PDF 原始文件名生成缩略图文件名（如 "document.webp"）
        val fileName = getThumbnailFileName(context, pdfUri)
        // 注意：getThumbnailFileName() 已返回带 .webp 后缀的文件名，此处无需追加
        return File(thumbnailDir, fileName)
    }

    /**
     * 把 URI 编码成安全的文件夹名字符串
     *
     * 【编码规则】
     * - 保留字母（a-zA-Z）和数字（0-9）
     * - 其他所有字符（:, /, . 等）全部替换为下划线 "_"
     *
     * 示例：
     * 输入："content://com.android.externalstorage.documents/tree/1234-5678:PDF"
     * 输出："content_com_android_externalstorage_documents_tree_1234-5678_PDF"
     *
     * @param uri 原始 URI 字符串
     * @return 编码后的安全字符串（仅含字母、数字、下划线）
     */
    private fun encodeUriForFilename(uri: String): String {
        return uri.replace(Regex("[^a-zA-Z0-9]"), "_")
    }

    /**
     * 检查缓存文件是否存在并有效（供 PdfFetcher 和 ViewModel 调用）
     *
     * 只从 app 私有目录读取，不走 SAF，确保高速响应
     *
     * 【调用位置】
     * - com.example.pdfmanager.data.local.PdfFetcher (加载 PDF 列表时获取缩略图)
     * - com.example.pdfmanager.ui.MainViewModel (获取缩略图路径用于 UI 展示)
     *
     * @param context Android 上下文
     * @param pdfUri  PDF 文件 URI
     * @return 缩略图的 Uri 对象（指向私有目录文件），缓存不存在或过期则返回 null
     */
    suspend fun getCachedThumbnail(context: Context, pdfUri: Uri): Uri? {
        return withContext(Dispatchers.IO) {
            // 获取库目录 URI
            val libraryUriString = PreferencesManager(context).getLibraryUri()
                ?: return@withContext null

            // 只从私有目录读取（速度快）
            val privateFile = getPrivateThumbnailFile(context, libraryUriString, pdfUri)
            if (privateFile.exists()) {
                // 验证缓存有效性
                if (isCacheValid(context, pdfUri, privateFile)) {
                    Log.d(TAG, "getCachedThumbnail: private cache hit: ${privateFile.name}")
                    // 将 File 转为 Uri（content:// 格式）
                    return@withContext Uri.fromFile(privateFile)
                } else {
                    privateFile.delete()
                    Log.w(TAG, "getCachedThumbnail: cache expired, deleted: ${privateFile.name}")
                }
            }

            // 私有目录没有缓存，返回 null
            Log.d(TAG, "getCachedThumbnail: cache miss: $pdfUri")
            null
        }
    }


    /**
     * 获取缩略图在文件系统中的绝对路径
     *
     * 如果缩略图不存在或已过期，则返回 null
     *
     * 【调用位置】
     * - PdfFetcher (构建 PdfFile 对象时填充 thumbnailPath 字段)
     * - ConversionRepository.convertZipFiles() (转换完成后获取缩略图路径用于入库)
     * - SearchViewModel / MainViewModel (获取路径用于 UI 层加载图片)
     *
     * @param context Android 上下文
     * @param pdfUri  PDF 文件 URI
     * @return 缩略图绝对路径（如 "/data/data/.../files/thumbnails/.../doc.webp"），不存在则 null
     */
    suspend fun getThumbnailPath(context: Context, pdfUri: Uri): String? {
        val libraryUriString = PreferencesManager(context).getLibraryUri()
            ?: return null
        val privateFile = getPrivateThumbnailFile(context, libraryUriString, pdfUri)
        if (privateFile.exists() && isCacheValid(context, pdfUri, privateFile)) {
            // 返回私有目录的绝对路径
            return privateFile.absolutePath
        }
        return null
    }

    /**
     * 验证缓存是否有效 —— 通过比较 PDF 文件和缓存文件的最后修改时间
     *
     * 【验证逻辑】
     * - 获取 PDF 原文件的 lastModified 时间戳
     * - 如果缩略图的 lastModified >= PDF 的 lastModified，说明缓存是最新的 → 有效
     * - 如果缩略图的 lastModified < PDF 的 lastModified，说明 PDF 被修改过 → 缓存过期
     *
     * 此重载版本接收 DocumentFile 类型的缓存文件参数
     *
     * @param context    Android 上下文
     * @param pdfUri     PDF 文件 URI
     * @param cachedFile 缓存文件的 DocumentFile 对象（用于比较修改时间）
     * @return true=缓存有效, false=缓存过期或无法获取 PDF 修改时间
     */
    private fun isCacheValid(context: Context, pdfUri: Uri, cachedFile: DocumentFile): Boolean {
        return try {
            val pdfDocFile = DocumentFile.fromSingleUri(context, pdfUri)
            val pdfLastModified = pdfDocFile?.lastModified() ?: 0L
            cachedFile.lastModified() >= pdfLastModified
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 验证缓存是否有效 —— 通过比较 PDF 文件和缓存文件的最后修改时间
     *
     * 此重载版本接收 java.io.File 类型的缓存文件参数
     * （与接收 DocumentFile 的重载功能相同，但文件访问方式不同）
     *
     * @param context    Android 上下文
     * @param pdfUri     PDF 文件 URI
     * @param cachedFile 缓存文件的 java.io.File 对象
     * @return true=缓存有效, false=缓存过期或无法获取 PDF 修改时间
     */
    private fun isCacheValid(context: Context, pdfUri: Uri, cachedFile: File): Boolean {
        return try {
            val pdfDocFile = DocumentFile.fromSingleUri(context, pdfUri)
            val pdfLastModified = pdfDocFile?.lastModified() ?: 0L
            cachedFile.lastModified() >= pdfLastModified
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 渲染 PDF 首页为 Bitmap，保持宽高比，最大宽度为 256px
     *
     * 【渲染原理】
     * 使用 Android 官方 PdfRenderer API（API 21+）：
     * 1. 通过 ContentResolver 打开 PDF 文件的 ParcelFileDescriptor
     * 2. 创建 PdfRenderer 实例
     * 3. 检查是否有页面（pageCount > 0）
     * 4. 打开第一页（index=0）
     * 5. 按比例计算高度：保持 PDF 原始宽高比
     *    - 公式：height = THUMBNAIL_SIZE × pageHeight / pageWidth
     * 6. 创建 ARGB_8888 格式的 Bitmap
     * 7. 使用 RENDER_MODE_FOR_DISPLAY 模式渲染（针对屏幕显示优化）
     *
     * 【API 注意事项】
     * - PdfRenderer 需要 android.permission.INTERACT_ACROSS_USERS 权限（系统自动授予）
     * - 渲染完成后必须 close() 释放 native 资源
     * - 此方法在 Dispatchers.IO 上执行，不会阻塞主线程
     *
     * @param context Android 上下文
     * @param uri     PDF 文件的 Content URI
     * @return 渲染好的 Bitmap（最大 256px 宽），失败返回 null
     */
    private suspend fun renderPdfPage(context: Context, uri: Uri): Bitmap? {
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            var pfd: android.os.ParcelFileDescriptor? = null
            var renderer: android.graphics.pdf.PdfRenderer? = null

            try {
                // 通过 ContentResolver 获取 PDF 文件的可读文件描述符
                pfd = context.contentResolver.openFileDescriptor(uri, "r")
                    ?: return@withContext null

                // 创建 PdfRenderer 实例，绑定到 PDF 文件描述符
                renderer = android.graphics.pdf.PdfRenderer(pfd)

                // 空 PDF（无页面）直接返回 null
                if (renderer.pageCount == 0) {
                    return@withContext null
                }

                // 打开 PDF 的第一页（index = 0）
                val page = renderer.openPage(0)

                // 按比例计算缩略图尺寸：宽度固定 256px，高度按 PDF 原始宽高比缩放
                val width = THUMBNAIL_SIZE
                val height = (width * page.height / page.width.toFloat()).toInt()
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

                // 渲染页面到 Bitmap（RENDER_MODE_FOR_DISPLAY = 屏幕显示优化模式）
                page.render(bitmap, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                page.close()

                Log.d(TAG, "renderPdfPage: success for $uri")
                bitmap
            } catch (e: Exception) {
                Log.e(TAG, "renderPdfPage: failed for $uri", e)
                null
            } finally {
                // 释放 native 资源（PdfRenderer 和 ParcelFileDescriptor 都需要关闭）
                renderer?.close()
                pfd?.close()
            }
        }
    }

    /**
     * 保存缩略图到缓存系统（主存储 + 快速缓存）
     *
     * 【双缓存策略】
     * 1. 主存储：context.filesDir/thumbnails/<encoded_uri>/<文件名>.webp
     *    - 永久存储，随应用存在而存在
     *    - 组织良好，与库结构对应
     *
     * 2. 快速缓存：context.cacheDir/thumbnails/thumb_<hash>.webp
     *    - 临时存储，系统可能在存储不足时自动清除
     *    - 文件名基于 URI hashCode，查找更快
     *
     * 两者均保存相同的缩略图数据，系统中存在两副本。
     * 读取时优先从快速缓存读取（最快路径）。
     *
     * @param context Android 上下文
     * @param pdfUri  PDF 文件 URI
     * @param bitmap  要保存的缩略图 Bitmap
     * @return true=主存储保存成功, false=主存储保存失败（快速缓存独立于此返回值）
     */
    private suspend fun saveThumbnail(context: Context, pdfUri: Uri, bitmap: Bitmap): Boolean {
        return withContext(Dispatchers.IO) {
            // 获取库目录 URI（用于确定主存储中的子文件夹）
            val libraryUriString = PreferencesManager(context).getLibraryUri()
                ?: return@withContext false

            // 1. 保存到私有目录（主存储）
            val privateFile = getPrivateThumbnailFile(context, libraryUriString, pdfUri)
            val saveToPrivateSuccess = try {
                saveToPrivateDirectory(bitmap, privateFile)
                Log.d(TAG, "saveThumbnail: saved to private dir: ${privateFile.name}")
                true
            } catch (e: Exception) {
                Log.e(TAG, "saveThumbnail: failed to save to private dir: ${privateFile.name}", e)
                false
            }

            // 2. 保存到快速缓存（cacheDir），即使主存储失败也尝试保存快速缓存
            saveToFastCache(context, pdfUri, bitmap)

            // 返回值以主存储是否成功为准
            saveToPrivateSuccess
        }
    }

    /**
     * 将 Bitmap 保存到指定 File（WebP 格式，质量 80）
     *
     * 【格式选择】
     * - API 30+（Android 11+）：使用 WEBP_LOSSY（专为有损压缩优化的 WebP 编码器，更高效）
     * - API 30 以下：使用 WEBP（兼容旧版 API）
     *
     * 【压缩质量】
     * 80/100：在文件大小和图像质量之间取得良好平衡
     * 相比 PNG：文件大小约 1/3，解码速度更快
     *
     * @param bitmap 要保存的 Bitmap
     * @param file   目标 File 对象
     */
    private fun saveToPrivateDirectory(bitmap: Bitmap, file: File) {
        FileOutputStream(file).use { out ->
            // API 30+ 用 WEBP_LOSSY，更高效的压缩算法
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, 80, out)
            } else {
                bitmap.compress(Bitmap.CompressFormat.WEBP, 80, out)
            }
        }
    }

    /**
     * 生成缩略图文件名（基于原始 PDF 文件名）
     *
     * 【文件名生成规则】
     * 1. 通过 DocumentFile API 获取 PDF 文件的实际文件名
     * 2. 如果无法获取（异常或 DocumentFile 返回 null），则使用 getFallbackName()
     * 3. 去掉 .pdf 扩展名（如果文件名以 .pdf 结尾）
     * 4. 清理文件名中的非法字符（Windows 文件系统不允许的字符全部替换为下划线）
     * 5. 替换所有空白字符为下划线
     * 6. 去掉首尾下划线
     * 7. 如果最终结果为空，使用 "unknown" 作为基础名
     * 8. 附加 .webp 扩展名
     *
     * 示例：
     * 输入 PDF: "我的文档  001.pdf"
     * 处理: 去掉扩展名 → "我的文档  001"
     *      替换空白为 "_" → "我的文档__001"
     *      去掉首尾 "_" → "我的文档__001"
     * 输出: "我的文档__001.webp"
     *
     * @param context Android 上下文
     * @param pdfUri  PDF 文件 URI
     * @return 缩略图文件名（如 "document.webp"）
     */
    internal fun getThumbnailFileName(context: Context, pdfUri: Uri): String {
        // 从 DocumentFile 获取 PDF 原始文件名
        val pdfName = try {
            val docFile = DocumentFile.fromSingleUri(context, pdfUri)
            docFile?.name ?: getFallbackName(pdfUri)
        } catch (e: Exception) {
            getFallbackName(pdfUri)
        }

        // 去掉 .pdf 扩展名（大小写不敏感）
        val baseName = if (pdfName.endsWith(".pdf", ignoreCase = true)) {
            pdfName.substring(0, pdfName.length - 4)
        } else {
            // 非 .pdf 后缀的文件，去掉最后一个 '.' 后的部分
            pdfName.substringBeforeLast('.')
        }

        // 清理非法字符，确保文件名在所有文件系统上可用
        val safeName = baseName
            // 替换文件系统非法字符：\ / : * ? " < > | 以及控制字符
            .replace(Regex("[\\\\/:*?\"<>|\\x00-\\x1F\\x7F]+"), "_")
            // 替换所有空白字符为下划线
            .replace(Regex("\\s+"), "_")
            // 去掉首尾可能残留的下划线
            .trim('_')
            // 如果结果为空字符串，使用默认名
            .ifEmpty { "unknown" }

        return "$safeName.webp"
    }

    /**
     * 获取备用文件名（当无法从 URI 获取文件名时使用）
     *
     * 使用 UUID.nameUUIDFromBytes() 基于 URI 的字节数组生成固定 UUID，
     * 确保同一个 URI 始终生成相同的备用名（幂等性保证）
     *
     * @param pdfUri PDF 文件 URI
     * @return 备用文件名（如 "pdf_550e8400-e29b-41d4-a716-446655440000"）
     */
    private fun getFallbackName(pdfUri: Uri): String {
        return "pdf_${java.util.UUID.nameUUIDFromBytes(pdfUri.toString().toByteArray())}"
    }

    /**
     * 保存 Bitmap 到快速磁盘缓存（context.cacheDir/thumbnails/）
     *
     * 【快速缓存的特点】
     * - 位置：Android 系统缓存目录（cacheDir），系统可在低存储时自动清理
     * - 命名：基于 URI 的 hashCode 的十六进制字符串 → 短小且唯一
     * - 格式：WebP，质量 80
     * - 性能：检查存在性 <1ms，读取 <10ms（纯文件 I/O，不走 SAF）
     *
     * 【设计用途】
     * UI 加载阶段（如 RecyclerView 的 Adapter 加载缩略图）直接命中此缓存，
     * 避免走慢速的 SAF I/O 路径，确保列表滑动流畅。
     *
     * @param context Android 上下文
     * @param pdfUri  PDF 文件 URI
     * @param bitmap  要缓存的 Bitmap
     */
    private fun saveToFastCache(context: Context, pdfUri: Uri, bitmap: Bitmap) {
        val cacheDir = File(context.cacheDir, "thumbnails")
        if (!cacheDir.exists()) {
            cacheDir.mkdirs()
        }
        // 用 URI 的 hashCode 的十六进制作为文件名（确保唯一且简短）
        val fileName = "thumb_${pdfUri.toString().hashCode().toString(16)}.webp"
        val cacheFile = File(cacheDir, fileName)

        try {
            cacheFile.outputStream().use { out ->
                // 使用 WebP 格式，质量 80
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, 80, out)
                } else {
                    bitmap.compress(Bitmap.CompressFormat.WEBP, 80, out)
                }
            }
            Log.d(TAG, "saveToFastCache: saved: $pdfUri")
        } catch (e: Exception) {
            Log.e(TAG, "saveToFastCache: failed: $pdfUri", e)
        }
    }

    /**
     * 从快速磁盘缓存读取缩略图 Bitmap
     *
     * 【读取流程】
     * 1. 构造缓存文件路径（与 saveToFastCache 中的路径保持一致）
     * 2. 检查文件是否存在
     * 3. 使用 BitmapFactory.decodeFile() 直接解码文件为 Bitmap
     *
     * 【调用位置】
     * - PdfAdapter / 图片加载组件：在绑定 ViewHolder 时获取缩略图
     * - 由于在 IO 线程执行，不会阻塞 UI
     *
     * @param context Android 上下文
     * @param pdfUri  PDF 文件 URI
     * @return Bitmap 对象（如果缓存存在且有效），否则返回 null
     */
    fun getFastCachedThumbnail(context: Context, pdfUri: Uri): Bitmap? {
        val cacheDir = File(context.cacheDir, "thumbnails")
        val fileName = "thumb_${pdfUri.toString().hashCode().toString(16)}.webp"
        val cacheFile = File(cacheDir, fileName)

        if (!cacheFile.exists()) {
            return null
        }

        return try {
            BitmapFactory.decodeFile(cacheFile.absolutePath)
        } catch (e: Exception) {
            Log.e(TAG, "getFastCachedThumbnail: failed to decode: $pdfUri", e)
            null
        }
    }
}
