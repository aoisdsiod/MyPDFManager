package com.example.pdfmanager.ui.screen.reader

import android.content.Context
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException

/**
 * PDF 文档仓库 —— 统一管理 PdfRenderer 生命周期
 *
 * ===== 功能 =====
 * 1. 统一持有 PdfRenderer 实例，避免多处创建导致的资源泄露
 * 2. 提供安全的文档访问方法（getPageCount / openPage / renderPage）
 * 3. 管理文档打开/关闭生命周期（openDocument / closeDocument）
 * 4. 线程安全：使用 Mutex（协程互斥锁）保护并发访问
 * 5. 幂等性：重复打开同一 URI 的文档不会重新创建
 *
 * ===== 调用位置 =====
 * - ReaderViewModel.kt 第 45 行：创建唯一实例（val pdfDocumentRepository = PdfDocumentRepository()）
 * - ReaderViewModel.kt 第 126 行：打开文档（openDocument 协程）
 * - ReaderViewModel.kt 第 167 行：打开文档的具体调用
 * - ReaderViewModel.kt 第 307/329 行：渲染页面
 * - ReaderViewModel.kt 第 460 行：关闭文档
 * - SinglePageView.kt 第 150-176 行：通过 init() 接收并延迟初始化
 * - PdfContinuousView.kt 第 143-176 行：通过 init() 接收并延迟初始化
 * - ContinuousCanvasState.kt 第 32 行：构造函数注入
 * - SinglePageState.kt 第 18 行：构造函数注入
 *
 * ===== 使用场景 =====
 * 所有 PDF 阅读器功能（ContinuousCanvasState / SinglePageState / ReaderViewModel
 * / PdfContinuousView / SinglePageView）都通过此仓库访问文档数据。
 * 它是整个 PDF 阅读器模块的文档数据中枢。
 *
 * 使用方式：
 * val repository = PdfDocumentRepository()
 * repository.openDocument(context, uri)  // 打开文档（协程中调用）
 * val pageCount = repository.getPageCount()
 * val page = repository.openPage(pageIndex)
 * repository.renderPage(pageIndex, bitmap)
 * repository.closeDocument()
 */
class PdfDocumentRepository {
    companion object {
        /** 日志标签 */
        private const val TAG = "PdfDocumentRepository"
    }

    /** 当前打开的 PdfRenderer 实例，null 表示文档未打开或已关闭 */
    private var pdfRenderer: PdfRenderer? = null

    /** 当前文档的 URI 字符串形式，用于幂等性检查 */
    private var currentUri: String? = null

    /**
     * 协程互斥锁
     *
     * 用于保护 openDocument / closeDocumentSuspend 等挂起函数的并发安全。
     * 确保同一时间只有一个协程在执行文档打开/关闭操作。
     */
    private val mutex = Mutex()

    /**
     * 是否正在渲染中
     *
     * 注：当前代码中此字段声明后从未被置为 true，也未在别处读取，
     * 属于预留字段，便于后续添加渲染状态管理。
     */
    private var isRendering = false

    /**
     * 打开 PDF 文档
     *
     * 使用 ContentResolver 从 URI 打开文件描述符，
     * 创建 PdfRenderer 实例并记录 URI。
     *
     * 幂等性：如果已打开同一个 URI 的文档，直接返回 true。
     * 线程安全：使用 mutex.withLock 保证互斥。
     *
     * @param context Android 上下文，用于访问 ContentResolver
     * @param uri     文档的 URI（content:// 或 file:// 等）
     * @return true 表示成功打开（或已打开），false 表示失败
     */
    suspend fun openDocument(context: Context, uri: Uri): Boolean = mutex.withLock {
        try {
            // 如果已经打开同一个文档，直接返回
            if (currentUri == uri.toString() && pdfRenderer != null) {
                Log.d(TAG, "Document already open: $uri")
                return true
            }

            // 关闭旧文档（如果之前有打开其他文档）
            closeDocumentInternal()

            // 通过 ContentResolver 打开文件描述符
            val pfd = context.contentResolver.openFileDescriptor(uri, "r")
                ?: throw IOException("Failed to open file descriptor for $uri")

            // 创建 PdfRenderer（这是 Android 官方的 PDF 渲染引擎）
            pdfRenderer = PdfRenderer(pfd)
            currentUri = uri.toString()

            Log.d(TAG, "Document opened: $uri, pageCount=${pdfRenderer!!.pageCount}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open document: $uri", e)
            // 打开失败时确保清理
            closeDocumentInternal()
            false
        }
    }

    /**
     * 获取 PDF 文档的页面总数
     *
     * 带状态检查：如果文档未打开、已关闭或发生异常，返回 0。
     *
     * 此方法不获取锁，因为 pdfRenderer 只在 openDocument（带锁）中写入，
     * 而 getPageCount 是只读操作，在 Kotlin/JVM 上对引用的读取是原子操作。
     *
     * @return 页面数量，如果文档不可用则返回 0
     */
    fun getPageCount(): Int {
        return try {
            pdfRenderer?.pageCount ?: 0
        } catch (e: IllegalStateException) {
            // PdfRenderer 已关闭后访问 pageCount 会抛出 IllegalStateException
            Log.e(TAG, "Document already closed", e)
            0
        }
    }

    /**
     * 打开指定页面
     *
     * 调用者负责在完成操作后调用 Page.close() 释放页面资源。
     *
     * 带状态检查：
     * - PdfRenderer 为 null → 返回 null
     * - pageIndex 超出范围 → 返回 null
     * - PdfRenderer 已关闭 → 捕获 IllegalStateException，返回 null
     *
     * @param pageIndex 要打开的页面索引（0-based）
     * @return PdfRenderer.Page 对象，失败时返回 null
     */
    fun openPage(pageIndex: Int): PdfRenderer.Page? {
        return try {
            val renderer = pdfRenderer
            if (renderer == null) {
                Log.w(TAG, "PdfRenderer is null")
                null
            } else if (pageIndex < 0 || pageIndex >= renderer.pageCount) {
                Log.w(TAG, "Invalid page index: $pageIndex, pageCount=${renderer.pageCount}")
                null
            } else {
                renderer.openPage(pageIndex)
            }
        } catch (e: IllegalStateException) {
            // PdfRenderer 已关闭后调用 openPage 会抛出 IllegalStateException
            Log.e(TAG, "Document already closed when opening page $pageIndex", e)
            null
        }
    }

    /**
     * 获取指定页面的原始尺寸
     *
     * 快速获取页面宽度和高度的便捷方法。
     * 内部会打开页面 → 获取尺寸 → 关闭页面。
     *
     * @param pageIndex 页面索引
     * @return Pair<宽度, 高度>，如果失败则返回 null
     */
    fun getPageSize(pageIndex: Int): Pair<Int, Int>? {
        val page = openPage(pageIndex) ?: return null
        val size = Pair(page.width, page.height)
        page.close()
        return size
    }

    /**
     * 渲染指定页面到 Bitmap
     *
     * 使用 PdfRenderer.Page.render() 将 PDF 页面渲染到指定的 Bitmap 上。
     * 渲染模式为 RENDER_MODE_FOR_DISPLAY（专为屏幕显示优化）。
     *
     * @param pageIndex 页面索引
     * @param bitmap    目标 Bitmap（必须预先分配好足够的像素空间）
     * @return true 表示渲染成功，false 表示失败
     */
    fun renderPage(pageIndex: Int, bitmap: android.graphics.Bitmap): Boolean {
        return try {
            val page = openPage(pageIndex) ?: return false
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            page.close()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to render page $pageIndex", e)
            false
        }
    }

    /**
     * 检查文档是否可用（已打开且至少有一页）
     *
     * @return true 表示文档可用，false 表示未打开或已关闭或为空文档
     */
    fun isDocumentAvailable(): Boolean {
        return try {
            pdfRenderer != null && getPageCount() > 0
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 关闭文档（公开方法，非 suspend）
     *
     * 不需协程上下文即可调用，适合在 ViewModel.onCleared()、
     * Activity.onDestroy() 等生命周期方法中调用。
     */
    fun closeDocument() {
        closeDocumentInternal()
    }

    /**
     * 关闭文档（suspend 版本）
     *
     * 使用 mutex 互斥锁确保线程安全，
     * 适合在协程上下文中调用。
     */
    suspend fun closeDocumentSuspend() = mutex.withLock {
        closeDocumentInternal()
    }

    /**
     * 关闭文档（内部方法，不获取锁）
     *
     * 实际的关闭逻辑：
     * 1. 调用 PdfRenderer.close() 释放原生资源
     * 2. 将 pdfRenderer 置为 null
     * 3. 清除 URI 记录
     *
     * 注意：此方法不获取 Mutex 锁，调用者需确保线程安全。
     */
    private fun closeDocumentInternal() {
        try {
            pdfRenderer?.close()
            Log.d(TAG, "Document closed: $currentUri")
        } catch (e: Exception) {
            Log.e(TAG, "Error closing document", e)
        } finally {
            pdfRenderer = null
            currentUri = null
        }
    }

    /**
     * 获取当前文档的 URI
     *
     * @return 当前文档 URI 的字符串形式，如果未打开则返回 null
     */
    fun getCurrentUri(): String? = currentUri
}
