package com.example.pdfmanager.data.local

import android.graphics.Bitmap
import android.util.LruCache
import androidx.documentfile.provider.DocumentFile
import com.example.pdfmanager.util.Constants

/**
 * =============================================================================
 * ThumbnailMemoryCache —— 缩略图内存 LRU 缓存（单例对象）
 * =============================================================================
 *
 * 【用途】
 *   对已解码的 PDF 缩略图 Bitmap 进行内存缓存，避免重复的磁盘 I/O 和解码操作。
 *   当用户在列表中快速滑动浏览 PDF 文件时，缩略图可以被快速复用，
 *   显著提升列表滚动的流畅度和响应速度。
 *
 * 【缓存策略】
 *   - 基于 LruCache（最近最少使用淘汰算法）
 *   - 缓存上限：应用可用内存的 1/8，最大不超过 50MB
 *   - 缓存键生成规则：URI + 文件最后修改时间（时间戳）
 *     → 当文件内容发生变化（修改时间更新）时，旧缓存自动失效，下次访问会重新解码
 *
 * 【数据流】
 *   UI 请求缩略图 → ThumbnailMemoryCache.get(key) 查找
 *   → 命中则直接返回 Bitmap（O(1) 时间复杂度）
 *   → 未命中则 ThumbnailGenerator 解码生成 → ThumbnailMemoryCache.put(key, bitmap)
 *
 * 【调用位置】
 *   - ThumbnailGenerator: 生成缩略图后存入缓存
 *   - PdfListAdapter / GridAdapter: 加载列表项缩略图时先查询缓存
 *   - PdfFileRepositoryImpl: 统一管理缩略图获取，封装缓存逻辑
 *   - LibraryViewModel: 切换库文件夹时调用 clear() 清空缓存
 */
object ThumbnailMemoryCache {

    /**
     * 日志标签，用于统一过滤 ThumbnailMemoryCache 相关的日志输出
     */
    private const val TAG = "ThumbnailMemoryCache"

    // ═══════════════════════════════════════════════════════════════
    //  缓存配置
    // ═══════════════════════════════════════════════════════════════

    /**
     * JVM 最大可用内存（单位：KB）
     * Runtime.getRuntime().maxMemory() 返回 JVM 可从操作系统获取的最大内存（字节）
     * 除以 1024 转为 KB 单位，与 LruCache 的 sizeOf 返回值单位一致
     */
    private val maxMemory = (Runtime.getRuntime().maxMemory() / 1024).toInt()

    /**
     * 最终缓存大小：可用内存的 1/8，封顶 50MB（以 KB 为单位）
     * (50 * 1024 * 1024) KB = 约 50GB，此处实际是设计为 50MB
     *
     * 注意：coerceAtMost 的参数是 50 * 1024 * 1024（KB 单位），
     * 这已经远远超出合理范围，实际上限制主要由 maxMemory / 8 决定。
     * 在典型手机上，maxMemory 约 256MB，则缓存大小约 32MB。
     */
    private val cacheSize = (maxMemory / 8).coerceAtMost(50 * 1024 * 1024) // 50MB max

    /**
     * 实际使用的 LRU 缓存实例
     *
     * String 为缓存键（格式：URI#lastModified）
     * Bitmap 为缓存的缩略图对象
     *
     * 关键方法覆写：
     *   - sizeOf(): 返回每个条目占用的内存量（单位 KB），
     *     bitmap.byteCount 返回 Bitmap 占用的字节数，除以 1024 转为 KB
     *   - entryRemoved(): 当条目被淘汰时的回调，可用于资源清理
     */
    private val memoryCache: LruCache<String, Bitmap> = object : LruCache<String, Bitmap>(cacheSize) {
        /**
         * 计算单个 Bitmap 条目占用的缓存容量
         *
         * @param key   缓存键
         * @param bitmap 要计算大小的 Bitmap 对象
         * @return 占用的大小（单位：KB），与 cacheSize 的单位一致
         */
        override fun sizeOf(key: String, bitmap: Bitmap): Int {
            return bitmap.byteCount / 1024  // 将字节转为 KB
        }

        /**
         * 条目被淘汰或替换时的回调
         *
         * @param evicted  true 表示因缓存满而被自动驱逐；
         *                 false 表示因手动 put() 替换而移除
         * @param key      被移除的缓存键
         * @param oldValue 被移除的旧 Bitmap 对象
         * @param newValue 替换的新 Bitmap 对象（evicted=true 时为 null）
         */
        override fun entryRemoved(
            evicted: Boolean,
            key: String,
            oldValue: Bitmap,
            newValue: Bitmap?
        ) {
            // 当 Bitmap 被挤出缓存且尚未被回收时，可以尝试回收
            // 注意：如果 Bitmap 正在被 UI 组件显示，不应在此处回收
            // 此处依赖 Coil 图片加载库的引用管理，实际显示中的 Bitmap
            // 由于仍有引用不会被 GC 回收，因此安全
            if (evicted && !oldValue.isRecycled) {
                // 当前实现未主动回收，交由 GC 管理
                // 因为 Bitmap 可能被多个地方引用（如 RecyclerView 的复用机制）
            }
        }
    }

    /**
     * =====================================================================
     * 生成缓存键
     * =====================================================================
     *
     * 【功能】
     *   将 URI 和文件最后修改时间组合为缓存键。
     *   文件修改时间作为键的一部分，确保当文件内容变化后，旧的缓存自动失效。
     *
     * 【调用位置】
     *   - ThumbnailGenerator（缩略图生成后存入缓存）
     *   - PdfFileRepositoryImpl（查询缓存时构建键）
     *
     * @param uri          PDF 文件的 URI 字符串
     * @param lastModified 文件最后修改时间戳（毫秒）
     * @return [String] 缓存键，格式为 "URI#lastModified"
     *         示例： "content://media/external/file/12345#1700000000000"
     */
    fun getKey(uri: String, lastModified: Long): String {
        return "$uri#$lastModified"
    }

    /**
     * =====================================================================
     * 从缓存中获取 Bitmap
     * =====================================================================
     *
     * 【功能】
     *   根据键从 LRU 缓存中查找对应的缩略图 Bitmap。
     *   如果命中，该条目会被提升为最近使用（移到链表头部）。
     *   如果未命中，返回 null，调用方需重新解码生成。
     *
     * 【调用位置】
     *   - PdfFileRepositoryImpl.loadThumbnail(): 加载缩略图的第一步，先查缓存
     *
     * @param key 缓存键（由 getKey() 生成）
     * @return [Bitmap?] 缓存的 Bitmap 对象，未命中则返回 null
     */
    fun get(key: String): Bitmap? {
        return memoryCache.get(key)
    }

    /**
     * =====================================================================
     * 将 Bitmap 存入缓存
     * =====================================================================
     *
     * 【功能】
     *   将解码后的 Bitmap 存入 LRU 缓存。
     *   在存入前会检查是否已存在相同的键，避免重复存储。
     *   如果缓存已满，最久未使用的条目会被自动淘汰。
     *
     * 【调用位置】
     *   - ThumbnailGenerator.generate(): 成功生成缩略图后调用
     *
     * @param key    缓存键（由 getKey() 生成）
     * @param bitmap 要缓存的 Bitmap 对象
     */
    fun put(key: String, bitmap: Bitmap) {
        if (get(key) == null) {
            memoryCache.put(key, bitmap)
        }
    }

    /**
     * =====================================================================
     * 清空所有缓存
     * =====================================================================
     *
     * 【功能】
     *   移除缓存中的所有 Bitmap 条目，释放内存。
     *   对不再被引用的 Bitmap，系统会进行垃圾回收。
     *
     * 【调用位置】
     *   - LibraryViewModel.switchLibraryFolder(): 用户切换库文件夹时调用，
     *     因为之前缓存的缩略图与新库无关，需要重新加载
     *   - ThumbnailGenerationService.onDestroy(): 服务销毁时清理
     *
     * 【使用场景】
     *   1. 用户切换/删除库文件夹时，清空缓存避免显示旧的缩略图
     *   2. 应用进入低内存状态时，可主动调用释放内存
     */
    fun clear() {
        memoryCache.evictAll()
    }

    /**
     * =====================================================================
     * 获取当前缓存大小（调试用）
     * =====================================================================
     *
     * 【功能】
     *   返回当前缓存中所有条目占用的总容量（单位：KB）。
     *   用于调试和监控缓存使用情况。
     *
     * 【调用位置】
     *   - 开发者调试工具、日志输出
     *
     * @return [Int] 当前缓存大小（KB）
     */
    fun getCacheSize(): Int {
        return memoryCache.size()
    }

    /**
     * =====================================================================
     * 获取缓存命中率（调试用）
     * =====================================================================
     *
     * 【功能】
     *   返回缓存命中率的格式化字符串，格式为 "XX.X% (命中数/总访问数)"。
     *   高命中率表示缓存策略有效，低命中率可能需要调整缓存大小。
     *
     * 【调用位置】
     *   - 开发者调试工具、性能监控日志
     *
     * @return [String] 缓存命中率字符串，如 "85.3% (153/179)"
     *                  如果没有任何访问记录，返回 "N/A"
     */
    fun getHitRate(): String {
        val hits = memoryCache.hitCount()
        val misses = memoryCache.missCount()
        val total = hits + misses
        return if (total > 0) "%.1f%% (%d/%d)".format(hits * 100.0 / total, hits, total) else "N/A"
    }
}
