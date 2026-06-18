package com.example.pdfmanager.ui.component

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.util.Log
import coil.decode.DataSource
import coil.fetch.DrawableResult
import coil.fetch.Fetcher
import coil.ImageLoader
import coil.request.Options
import com.example.pdfmanager.data.local.ThumbnailGenerator
import com.example.pdfmanager.data.local.ThumbnailMemoryCache
import java.io.File
import java.io.IOException

/**
 * PDF 缩略图加载器（Coil 2.x 自定义 Fetcher）
 *
 * ====== Fetcher / Fetcher.Factory 模式说明（Coil 框架） ======
 *
 * Coil 是一个 Kotlin 优先的 Android 图片加载库。它使用 Fetcher 模式来支持
 * 加载各种来源/格式的图片。Fetcher 是 Coil 的可扩展点之一：
 *
 * 【Fetcher】（接口）
 *   - 负责从给定数据源（如 Uri）加载并解码图片
 *   - 需要实现 fetch() 方法，返回 DrawableResult
 *   - 本类 PdfFetcher 实现了 Fetcher 接口，专门处理 PDF Uri 的缩略图加载
 *
 * 【Fetcher.Factory】（工厂接口）
 *   - 负责判断某个数据是否应由对应的 Fetcher 处理
 *   - 需要实现 create(data, options, imageLoader) 方法
 *   - 如果数据匹配（如 Uri 是 PDF 文件），则返回 Fetcher 实例；否则返回 null
 *   - 本类的内部类 Factory 实现了 Fetcher.Factory<Uri>，专门匹配 PDF Uri
 *
 * 【注册方式】
 *   - 在 PdfManagerApp.kt 和 MainActivity.kt 中创建 ImageLoader 时，
 *     通过 .components { add(PdfFetcher.Factory(context)) } 注册
 *   - 注册后，Coil 在遇到 Uri 时，会遍历所有注册的 Factory，
 *     由 PdfFetcher.Factory.create() 判断是否为 PDF 文件
 *
 * ====== 缓存策略（三级缓存，从快到慢） ======
 * 1. 内存缓存（ThumbnailMemoryCache）：<1ms
 *    - 键：Uri.toString()
 *    - 值：Bitmap 对象
 *    - 使用 LruCache 实现，防止 OOM
 *
 * 2. 快速磁盘缓存（context.cacheDir/thumbnails/）：<10ms
 *    - 普通文件 I/O，存放在应用缓存目录
 *    - 文件名：thumb_{uri_hashCode_hex}.webp
 *    - WebP Lossy 格式，质量 80
 *    - 第一次从 SAF 缓存读取后自动存入此缓存，后续走此路径
 *
 * 3. SAF 磁盘缓存（filesDir/thumbnails/）：较慢
 *    - 由 ThumbnailGenerationService 在生成阶段保存
 *    - 需要通过 ContentResolver 的 openInputStream 访问
 *
 * ====== 设计原则 ======
 * UI 加载阶段不生成缩略图，避免卡顿。
 * 缩略图只会在以下两个地方生成：
 *   1. 设置页面的"生成缩略图"按钮 → ThumbnailGenerationService
 *   2. ZIP 转 PDF 功能模块中完成转换后
 * 缓存未命中时直接抛出 IOException，由调用方处理（显示占位图）
 *
 * ====== 调用位置 ======
 * 【PdfFetcher 类】
 *   - 由 Coil 框架内部调用（当 ImageLoader 处理 PDF Uri 时）
 *   - PdfFetcher.Factory.create() 判定匹配后，Coil 自动调用 fetch()
 *
 * 【PdfFetcher.Factory 类】
 *   - PdfManagerApp.kt → 在 Application.onCreate() 的 ImageLoader.Builder 中注册
 *   - MainActivity.kt  → 在 Activity.onCreate() 的 ImageLoader.Builder 中注册
 *     （两边都注册以确保全局生效）
 *
 * 【ThumbnailGenerator.getCachedThumbnail() 方法】
 *   - 被本类的 fetch() 方法调用，用于查询 SAF 磁盘缓存
 *   - 也被 PdfThumbnail 组件的 LaunchedEffect 调用（直接读取缓存）
 *
 * @param context Android 上下文（用于访问资源、ContentResolver 和缓存目录）
 * @param uri PDF 文件的 Uri（content:// 或 file:// 协议）
 */
class PdfFetcher(
    private val context: Context,
    private val uri: Uri
) : Fetcher {

    companion object {
        private const val TAG = "PdfFetcher"
    }

    /**
     * 执行缩略图加载（Coil Fetcher 接口的核心方法）
     *
     * 按照 内存缓存 → 快速磁盘缓存 → SAF 磁盘缓存 的顺序查找，
     * 命中任一缓存即返回 DrawableResult，三级都未命中则抛出异常。
     *
     * 返回的 DrawableResult 包含：
     * - drawable:   图片 Drawable 对象
     * - isSampled:  是否已采样（压缩），false 表示原图尺寸
     * - dataSource: 数据来源（MEMORY / DISK），影响 Coil 的缓存策略
     *
     * @return DrawableResult 包含已加载的图片 Drawable
     * @throws IOException 当三级缓存都未命中时抛出，由 Coil 回调 Error 状态
     */
    override suspend fun fetch(): DrawableResult {
        val cacheKey = uri.toString()
        
        // === 第 1 级：内存缓存（最快，<1ms） ===
        // ThumbnailMemoryCache 基于 LruCache 实现，存储已解码的 Bitmap
        // 同一 Session 内重复加载时直接命中，避免磁盘 I/O
        val cachedBitmap = ThumbnailMemoryCache.get(cacheKey)
        if (cachedBitmap != null) {
            Log.d(TAG, "fetch: memory cache hit: $uri")
            val drawable = BitmapDrawable(context.resources, cachedBitmap)
            return DrawableResult(
                drawable = drawable,
                isSampled = false,
                dataSource = DataSource.MEMORY
            )
        }

        // === 第 2 级：快速磁盘缓存（context.cacheDir/thumbnails/，普通文件 I/O） ===
        // 使用 File 直接访问，不需要 ContentResolver，读写速度快（<10ms）
        // 格式：WebP Lossy，质量 80，兼顾速度与画质
        val fastCacheFile = getFastDiskCacheFile(context, uri)
        if (fastCacheFile.exists()) {
            try {
                val bitmap = BitmapFactory.decodeFile(fastCacheFile.absolutePath)
                if (bitmap != null) {
                    // 存入内存缓存（下次直接走第 1 级）
                    ThumbnailMemoryCache.put(cacheKey, bitmap)
                    Log.d(TAG, "fetch: fast disk cache hit: $uri")
                    val drawable = BitmapDrawable(context.resources, bitmap)
                    return DrawableResult(
                        drawable = drawable,
                        isSampled = false,
                        dataSource = DataSource.DISK
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "fetch: failed to load from fast disk cache: $uri", e)
            }
        }

        // === 第 3 级：SAF 磁盘缓存（生成阶段已保存，但访问慢） ===
        // 通过 ThumbnailGenerator.getCachedThumbnail() 获取 SAF URI，
        // 再通过 ContentResolver.openInputStream() 读取，需要跨进程访问
        // 读取成功后，同时存入第 1 级（内存）和第 2 级（快速磁盘）缓存
        val cachedThumbnail = ThumbnailGenerator.getCachedThumbnail(context, uri)
        if (cachedThumbnail != null) {
            val bitmap = context.contentResolver.openInputStream(cachedThumbnail)?.use { inputStream ->
                BitmapFactory.decodeStream(inputStream)
            }
            
            if (bitmap != null) {
                // 存入快速磁盘缓存（下次直接命中快速缓存，不再走 SAF）
                saveToFastDiskCache(context, uri, bitmap)
                // 存入内存缓存
                ThumbnailMemoryCache.put(cacheKey, bitmap)
                Log.d(TAG, "fetch: SAF cache hit, copied to fast cache: $uri")
                val drawable = BitmapDrawable(context.resources, bitmap)
                return DrawableResult(
                    drawable = drawable,
                    isSampled = false,
                    dataSource = DataSource.DISK
                )
            }
        }

        // === 三级缓存全部未命中 ===
        // 设计说明：
        // 缩略图只会在以下两个地方生成：
        // 1. 设置页面的"生成缩略图"按钮（ThumbnailGenerationService）
        // 2. ZIP转PDF功能模块中完成转换后
        // UI 加载阶段不生成缩略图，避免卡顿
        Log.d(TAG, "fetch: cache miss, thumbnail not generated yet: $uri")
        throw IOException("Thumbnail not cached for $uri, please generate in settings")
    }

    /**
     * 获取快速磁盘缓存文件对象
     *
     * 缓存文件存放在应用缓存目录下的 thumbnails/ 子目录中。
     * 使用 URI 的 hashCode 的 16 进制字符串作为文件名，确保同一 PDF
     * 始终映射到同一缓存文件，避免重复缓存。
     *
     * 格式说明：
     * - 目录：context.cacheDir/thumbnails/
     * - 文件名：thumb_{hashCode_hex}.webp
     * - 优点：普通文件 I/O，飞快（<1ms 检查存在性，<10ms 读取）
     * - 注意：Android 系统可能在存储空间不足时自动清理 cacheDir
     *
     * @param context Android 上下文
     * @param uri PDF 文件的 Uri
     * @return File 缓存文件对象（可能不存在，调用者需检查 exists()）
     */
    private fun getFastDiskCacheFile(context: Context, uri: Uri): File {
        val cacheDir = File(context.cacheDir, "thumbnails")
        if (!cacheDir.exists()) {
            cacheDir.mkdirs()
        }
        // 用 URI 的 hashCode 作为文件名（确保唯一）
        val fileName = "thumb_${uri.toString().hashCode().toString(16)}.webp" // 使用.webp扩展名
        return File(cacheDir, fileName)
    }

    /**
     * 保存缩略图到快速磁盘缓存（WebP 格式，质量 80）
     *
     * 当从第 3 级（SAF 磁盘缓存）命中后，自动将 Bitmap 转存到第 2 级
     * （快速磁盘缓存），下次加载直接从第 2 级读取，不再走慢速的 SAF 路径。
     *
     * 使用 WebP Lossy 格式的考虑：
     * - WebP 比 JPEG 压缩率更高，节省磁盘空间
     * - 质量 80 在画质和文件大小之间取得良好平衡
     * - API 30+ 使用 WEBP_LOSSY，以下使用 WEBP（自动选有损）
     *
     * @param context Android 上下文
     * @param uri PDF 文件的 Uri（用于构建缓存文件名）
     * @param bitmap 要写入缓存的 Bitmap 对象
     */
    private fun saveToFastDiskCache(context: Context, uri: Uri, bitmap: Bitmap) {
        val cacheFile = getFastDiskCacheFile(context, uri)
        try {
            cacheFile.outputStream().use { out ->
                // 使用WebP格式，质量80
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, 80, out)
                } else {
                    bitmap.compress(Bitmap.CompressFormat.WEBP, 80, out)
                }
            }
            Log.d(TAG, "saveToFastDiskCache: saved (WebP): $uri")
        } catch (e: Exception) {
            Log.e(TAG, "saveToFastDiskCache: failed: $uri", e)
        }
    }

    /**
     * PdfFetcher 的工厂类（实现 Coil 2.x 的 Fetcher.Factory 接口）
     *
     * ====== 角色说明 ======
     * Factory 是 Coil 框架的扩展点，职责是"判断某个数据源是否应由对应的 Fetcher 处理"。
     * 当 Coil 的 ImageLoader 遇到需要加载的数据（data 参数）时，会依次调用
     * 所有已注册的 Factory 的 create() 方法，直到某个 Factory 返回非 null 的 Fetcher。
     *
     * ====== 匹配逻辑 ======
     * 本 Factory 通过以下三种方式判断 Uri 是否指向 PDF 文件：
     * 1. Uri 的 lastPathSegment 以 .pdf 结尾（最常用，覆盖 content:// URI）
     * 2. Uri 的 path 以 .pdf 结尾（覆盖 file:// URI）
     * 3. Uri.toString() 包含 .pdf（兜底，覆盖特殊编码的 URI）
     *
     * ====== 注册方式 ======
     * 在 PdfManagerApp.kt 和 MainActivity.kt 中：
     * val imageLoader = ImageLoader.Builder(context)
     *     .components {
     *         add(PdfFetcher.Factory(context))  // ← 注册本 Factory
     *     }
     *     .build()
     * Coil.setImageLoader(imageLoader)
     *
     * @param context Android 上下文，传递给 PdfFetcher 构造函数
     */
    class Factory(private val context: Context) : Fetcher.Factory<Uri> {
        /**
         * 判断并创建 PdfFetcher 实例
         *
         * Coil 框架调用此方法，传入需要加载的数据。如果判断为 PDF 文件，
         * 则返回 PdfFetcher 实例；否则返回 null，让后续 Factory 处理。
         *
         * @param data 需要加载的数据（Uri 类型），可能指向 PDF 或普通图片
         * @param options Coil 加载选项（包含目标尺寸、缩放模式等）
         * @param imageLoader 调用此 Factory 的 ImageLoader 实例
         * @return PdfFetcher? 如果数据是 PDF 文件则返回 Fetcher，否则返回 null
         */
        override fun create(
            data: Uri,
            options: Options,
            imageLoader: ImageLoader
        ): Fetcher? {
            val lastSegment = data.lastPathSegment ?: ""
            val isPdf = lastSegment.endsWith(".pdf", ignoreCase = true)
                    || data.path?.endsWith(".pdf", ignoreCase = true) == true
                    || data.toString().contains(".pdf", ignoreCase = true)

            Log.d(TAG, "Factory.create: uri=$data, isPdf=$isPdf")
            return if (isPdf) {
                PdfFetcher(context, data)
            } else {
                null
            }
        }
    }
}
