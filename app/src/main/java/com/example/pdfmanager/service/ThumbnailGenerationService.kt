package com.example.pdfmanager.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.pdfmanager.R
import com.example.pdfmanager.data.local.PdfManagerDatabase
import com.example.pdfmanager.data.local.ThumbnailGenerator
import com.example.pdfmanager.data.model.PdfFile
import com.example.pdfmanager.data.repository.AppContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.withContext

/**
 * =============================================================================
 * ThumbnailGenerationService —— 缩略图生成后台服务
 * =============================================================================
 *
 * 【用途】
 *   使用 Android Foreground Service（前台服务）在后台批量生成 PDF 文件的缩略图。
 *   确保即使应用被用户切换到后台或锁定屏幕，缩略图仍然可以继续生成。
 *
 * 【核心功能】
 *   1. 前台服务：在通知栏显示常驻通知，防止系统杀死进程
 *   2. 并发生成：使用信号量（Semaphore）限制最多 8 个缩略图同时生成
 *   3. 进度通知：每隔 2 秒更新通知栏的进度信息
 *   4. 批量写入：每生成 10 个缩略图，批量更新一次数据库，减少 I/O 次数
 *   5. 内存同步：批量更新后同步刷新内存缓存，实现 UI 的渐进式刷新
 *   6. 取消支持：通过 isCancelled 标志支持中途取消
 *
 * 【数据流】
 *   UI（ViewModel 触发）→ startGeneration(files) →
 *   启动协程 → 并发生成缩略图（Semaphore 控制并发数）→
 *   每完成一个：更新进度 + 缓存到 batch →
 *   每 10 个或全部完成：flushBatch() 写入数据库 + 刷新内存缓存 →
 *   全部完成：更新状态 → 停止前台服务 → 自动 stopSelf()
 *
 * 【设计优化（相对旧版）】
 *   - 移除全局 Mutex，改用 Semaphore(8) 支持并发生成
 *   - 批量更新数据库（每 50 个 → 调优为每 10 个一批）
 *   - 限流通知更新（每 2 秒更新一次，避免通知栏闪烁）
 *   - 使用 AppContainer 全局状态通知各组件进度
 *
 * 【调用位置】
 *   - PdfListViewModel.generateThumbnails(): 用户首次打开库时触发
 *   - LibraryFragment: 进入库页面时检查并启动缩略图生成
 *   - MainActivity: 绑定服务的入口点
 *
 * 【生命周期】
 *   startGeneration() → onCreate() → startForeground() → 并行生成 →
 *   flushBatch() × N → stopForeground() → stopSelf() → onDestroy()
 */
class ThumbnailGenerationService : Service() {

    // ═══════════════════════════════════════════════════════════════
    //  伴生对象：常量定义
    // ═══════════════════════════════════════════════════════════════

    companion object {
        /** 日志标签 */
        private const val TAG = "ThumbnailGenerationService"

        /** 通知渠道 ID（Android 8.0+ 必须） */
        private const val CHANNEL_ID = "thumbnail_generation_channel"

        /** 通知唯一 ID */
        private const val NOTIFICATION_ID = 10001

        /**
         * 批量提交大小：每生成 BATCH_SIZE 个缩略图，统一写入一次数据库。
         * 调优后为 10，平衡了数据库 I/O 次数和内存占用量。
         */
        private const val BATCH_SIZE = 10

        /**
         * 通知更新限流间隔（毫秒）：每 NOTIFICATION_THROTTLE_MS 毫秒
         * 更新一次通知栏进度，避免频繁通知导致的 UI 卡顿和电量消耗。
         */
        private const val NOTIFICATION_THROTTLE_MS = 2000L
    }

    // ═══════════════════════════════════════════════════════════════
    //  属性定义
    // ═══════════════════════════════════════════════════════════════

    /**
     * Binder 实例，供 UI 组件绑定此服务并调用公开方法。
     * 在 onBind() 中返回给绑定方。
     */
    private val binder = ThumbnailGenerationBinder(this)

    /**
     * 协程 Job 引用，用于在 onDestroy() 时取消正在运行的生成任务。
     * 持有引用确保可以在任何时刻取消任务。
     */
    private var generationJob: Job? = null

    // ── 进度状态 ─────────────────────────────────────────────

    /**
     * 已成功生成的缩略图数量（线程安全计数器）。
     * 多个协程同时增加此值，使用 AtomicInteger 保证原子操作。
     */
    private val generatedCount = AtomicInteger(0)

    /** 需要生成的缩略图总数 */
    private var totalCount = 0

    /**
     * 生成失败的缩略图数量（线程安全计数器）。
     * 用于最终向用户报告失败数量。
     */
    private val failedCount = AtomicInteger(0)

    /** 服务是否正在运行中，防止重复启动 */
    private var isRunning = false

    /**
     * 是否已取消生成。
     * 当用户退出页面或主动取消时设置为 true，
     * 各并发协程检测到后提前退出。
     */
    private var isCancelled = false

    // ── 批量更新缓存 ─────────────────────────────────────────

    /**
     * 批量更新缓存列表，存储 (fileId, thumbnailPath) 对。
     * 每生成一个缩略图先放入此列表，达到 BATCH_SIZE 后统一写入数据库。
     * 使用 synchronized(batchLock) 保证线程安全。
     */
    private val successBatch = mutableListOf<Pair<String, String>>()

    /**
     * 批量缓存的锁对象，用于同步对 successBatch 的并发访问。
     * 多个生成协程可能同时向 successBatch 添加数据。
     */
    private val batchLock = Any()

    // ═══════════════════════════════════════════════════════════════
    //  Service 生命周期方法
    // ═══════════════════════════════════════════════════════════════

    /**
     * 服务创建时回调
     *
     * 【功能】
     *   初始化通知渠道（Android 8.0+ 必须提前创建通知渠道，
     *   否则通知不会显示）。
     *
     * 【调用者】Android 系统
     */
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        Log.d(TAG, "服务已创建")
    }

    /**
     * 服务绑定时回调
     *
     * 【功能】
     *   返回 Binder 对象，供客户端（如 Activity/Fragment）
     *   绑定后调用 ThumbnailGenerationService 的公开方法。
     *
     * 【调用者】Android 系统（bindService 时）
     *
     * @param intent 绑定请求的 Intent
     * @return [IBinder] 用于与服务通信的 Binder 对象
     */
    override fun onBind(intent: Intent): IBinder {
        Log.d(TAG, "服务已绑定")
        return binder
    }

    /**
     * 服务启动时回调
     *
     * 【功能】
     *   返回 START_STICKY 表示如果服务被系统杀死，会在资源可用时重启，
     *   但不会重新传递 Intent（适用于不需要重做启动操作的后台任务）。
     *
     * 【调用者】Android 系统（startService 时）
     *
     * @param intent 启动 Intent
     * @param flags  启动标志
     * @param startId 启动 ID
     * @return [Int] 粘性重启策略
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    /**
     * =====================================================================
     * 开始生成缩略图（核心入口方法）
     * =====================================================================
     *
     * 【功能】
     *   接收需要生成缩略图的 PDF 文件列表，在后台协程中并发生成缩略图。
     *   主要流程：
     *     1. 状态初始化（重置计数器、清空缓存）
     *     2. 启动前台服务通知
     *     3. 启动生成协程（Semaphore 控制并发数 ≤ 8）
     *     4. 每生成一个缩略图更新进度
     *     5. 批量写入数据库（每 BATCH_SIZE 个写一次）
     *     6. 全部完成后清理并停止服务
     *
     * 【并发控制】
     *   使用 Semaphore(8)，同时最多 8 个缩略图在后台生成。
     *   每个 PDF 的缩略图生成是独立的 I/O 操作，互不依赖。
     *
     * 【通知更新】
     *   单独启动一个通知协程，每 2 秒更新一次通知栏进度，
     *   避免每生成一个就 updateNotification 造成的性能开销。
     *
     * 【调用位置】
     *   - PdfListViewModel.generateAllThumbnails()
     *   - LibraryFragment: 页面初始化时，对尚未生成缩略图的 PDF 调用
     *
     * @param files [List<PdfFile>] 需要生成缩略图的 PDF 文件列表
     *              通常是 thumbnailGenerated = 0 的文件
     */
    fun startGeneration(files: List<PdfFile>) {
        // ── 前置检查 ──
        if (isRunning) {
            Log.w(TAG, "缩略图生成已在运行中")
            return
        }

        if (files.isEmpty()) {
            Log.w(TAG, "没有需要生成缩略图的文件")
            return
        }

        // ── 初始化状态 ──
        isRunning = true
        isCancelled = false
        generatedCount.set(0)
        totalCount = files.size
        failedCount.set(0)
        successBatch.clear()

        // 通知全局状态：缩略图生成已开始
        AppContainer.isThumbnailGenerationRunning.value = true

        // 启动前台服务（显示通知，防止被系统杀死）
        startForeground(NOTIFICATION_ID, createNotification(0, totalCount))

        // ── 启动协程生成缩略图 ──
        generationJob = CoroutineScope(Dispatchers.IO).launch {
            // 信号量：控制同时最多 8 个缩略图生成任务
            val semaphore = Semaphore(8)

            // 使用 AppContainer 中的 database（已切换到用户当前选择的库文件夹）
            val pdfFileDao = AppContainer.database.pdfFileDao()

            // ── 单独启动通知更新协程 ──
            // 该协程每 2 秒更新一次通知栏进度，降低了通知更新频率
            val notificationJob = launch {
                try {
                    while (!isCancelled) {
                        delay(NOTIFICATION_THROTTLE_MS)
                        val current = generatedCount.get()
                        updateNotification(current, totalCount)
                    }
                } catch (e: Exception) {
                    // 协程被取消时（notificationJob.cancel()），
                    // delay() 会抛出 CancellationException，在此处静默处理
                }
            }

            // ── 为每个文件启动一个协程 ──
            // 所有协程并行执行，通过 semaphore.acquire/release 控制并发数
            files.map { pdfFile ->
                launch {
                    semaphore.acquire()  // 获取信号量许可，超过 8 个则等待
                    try {
                        if (isCancelled) return@launch  // 检查取消标志

                        // ── 调用 ThumbnailGenerator 生成缩略图 ──
                        val uri = pdfFile.uri
                        val success = ThumbnailGenerator.generate(applicationContext, uri)

                        if (success) {
                            // 生成成功：获取已保存的缩略图路径
                            val thumbnailPath = ThumbnailGenerator.getThumbnailPath(applicationContext, uri)
                            if (thumbnailPath != null) {
                                // 将结果放入批量缓存（线程安全）
                                synchronized(batchLock) {
                                    successBatch.add(pdfFile.id to thumbnailPath)
                                }
                            } else {
                                Log.w(TAG, "生成后缩略图路径为 null: ${pdfFile.name}")
                            }

                            Log.d(TAG, "缩略图生成成功: ${pdfFile.name}")
                        } else {
                            // 生成失败：增加失败计数
                            failedCount.incrementAndGet()
                            Log.w(TAG, "缩略图生成失败: ${pdfFile.name}")
                        }

                        // 增加已完成计数（无论成功或失败都算一次）
                        val current = generatedCount.incrementAndGet()

                        // ── 批量更新数据库 ──
                        // 每 BATCH_SIZE 个或全部完成时写入数据库
                        if (current % BATCH_SIZE == 0 || current == totalCount) {
                            flushBatch(pdfFileDao)
                        }

                        // 通过 AppContainer 通知 UI 层进度更新
                        AppContainer.thumbnailProgress.value = current to totalCount

                        Log.d(TAG, "进度: $current/$totalCount")
                    } catch (e: Exception) {
                        Log.e(TAG, "生成 ${pdfFile.uri} 缩略图时异常", e)
                        // 异常时仍然增加计数，避免进度永远达不到 totalCount
                        generatedCount.incrementAndGet()
                    } finally {
                        // 释放信号量许可，允许下一个等待的协程执行
                        semaphore.release()
                    }
                }
            }.forEach { it.join() }  // 等待所有协程完成

            // ── 所有文件处理完毕，清理工作 ──

            // 停止通知更新协程
            notificationJob.cancel()

            // 刷新剩余的批量更新（不足 BATCH_SIZE 的剩余记录）
            flushBatch(pdfFileDao)

            // 最终通知更新（显示"已完成"状态）
            updateNotification(generatedCount.get(), totalCount)

            // 通过 AppContainer 通知生成结果
            AppContainer.thumbnailGenerationResult.value = AppContainer.ThumbnailGenerationResult(
                generated = generatedCount.get(),
                total = totalCount,
                failed = failedCount.get()
            )
            AppContainer.isThumbnailGenerationRunning.value = false

            // 重置运行状态
            isRunning = false
            AppContainer.thumbnailProgress.value = null

            // 移除前台通知并停止服务
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()

            Log.d(TAG, "缩略图生成完成: ${generatedCount.get()}/$totalCount (失败: ${failedCount.get()})")
        }
    }

    /**
     * =====================================================================
     * 刷新批量缓存到数据库
     * =====================================================================
     *
     * 【功能】
     *   将缓存在 successBatch 中的缩略图记录批量写入数据库，
     *   并同步更新 PdfRepository 的内存缓存，实现 UI 的渐进式刷新。
     *
     * 【两步操作】
     *   1. 数据库持久化：更新 pdf_files 表的 thumbnailGenerated 和 thumbnailPath 字段
     *   2. 内存缓存同步：通知 PdfRepository 更新内存中的 PdfFile 对象，
     *      使得观察该列表的 LiveData/Flow 能够立即反映变化
     *
     * 【线程安全】
     *   使用 synchronized(batchLock) 确保在复制缓存时不会和其他协程的写入冲突。
     *   复制完成后立即清空原缓存，然后遍历复制结果写入数据库，最小化锁定时间。
     *
     * 【调用位置】
     *   - startGeneration() 内部：每 BATCH_SIZE 个缩略图完成后调用
     *   - startGeneration() 末尾：所有文件处理完毕后最后调用一次
     *
     * @param pdfFileDao [PdfFileDao] PDF 文件数据访问对象
     *                   用于更新数据库中的缩略图状态字段
     */
    private suspend fun flushBatch(pdfFileDao: com.example.pdfmanager.data.local.PdfFileDao) {
        // 线程安全地复制并清空缓存
        val batchCopy: List<Pair<String, String>>
        synchronized(batchLock) {
            if (successBatch.isEmpty()) return  // 没有缓存数据，直接返回
            batchCopy = successBatch.toList()
            successBatch.clear()
        }

        // 第一步：更新数据库中的缩略图状态
        batchCopy.forEach { (fileId, thumbnailPath) ->
            // 标记 thumbnailGenerated = 1（已生成）
            pdfFileDao.updateThumbnailGenerated(fileId, 1)
            // 更新缩略图文件路径
            pdfFileDao.updateThumbnailPath(fileId, thumbnailPath)
        }

        // 第二步：同步更新 PdfRepository 的内存缓存
        // 这将触发观察 PdfFile 列表的 LiveData/Flow 发出新值，
        // 从而实现 UI 的渐进式刷新（用户在列表中可以看到缩略图逐个出现）
        batchCopy.forEach { (fileId, thumbnailPath) ->
            AppContainer.pdfRepository.updateThumbnailPath(fileId, thumbnailPath)
        }

        Log.d(TAG, "已批量刷新 ${batchCopy.size} 条记录到数据库和内存")
    }

    /**
     * =====================================================================
     * 创建通知渠道（Android 8.0+ 必需）
     * =====================================================================
     *
     * 【功能】
     *   创建或获取"缩略图生成"通知渠道。
     *   Android 8.0 (API 26) 开始，所有通知必须属于一个通知渠道。
     *   渠道的重要性设为 IMPORTANCE_LOW，表示不会发出声音，
     *   仅在通知栏显示，避免打扰用户。
     *
     * 【调用位置】
     *   - onCreate(): 服务创建时调用一次
     */
    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "缩略图生成",           // 渠道名称（用户可在设置中查看）
            NotificationManager.IMPORTANCE_LOW  // 低重要性，不发声
        ).apply {
            description = "PDF 缩略图生成进度"  // 渠道描述
        }
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
    }

    /**
     * =====================================================================
     * 创建通知对象
     * =====================================================================
     *
     * 【功能】
     *   构建前台服务通知，显示当前缩略图生成进度。
     *   使用带进度条的 Notification，并在内容中显示 "进度：已完成/总数"。
     *   通知设为 setOngoing(true)，用户无法侧滑移除。
     *
     * 【调用位置】
     *   - startForeground(): 启动前台服务时创建初始通知
     *   - updateNotification(): 更新通知时调用
     *
     * @param generated 已生成的缩略图数量
     * @param total     需要生成的缩略图总数
     * @return [Notification] 构建好的通知对象
     */
    private fun createNotification(generated: Int, total: Int): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("生成缩略图")
            .setContentText("进度：$generated/$total")
            .setSmallIcon(android.R.drawable.ic_popup_sync)  // 使用系统同步图标
            .setProgress(total, generated, false)            // 确定进度条
            .setOngoing(true)                                // 不可滑除
            .build()
    }

    /**
     * =====================================================================
     * 更新通知栏进度
     * =====================================================================
     *
     * 【功能】
     *   使用新的进度值更新通知栏中已存在的通知。
     *   通过 NotificationManager.notify() 更新，而非创建新通知。
     *
     * 【调用位置】
     *   - notificationJob 协程中：每 NOTIFICATION_THROTTLE_MS（2秒）调用一次
     *   - 生成全部完成后：最终更新一次
     *
     * @param generated 已生成的缩略图数量
     * @param total     需要生成的缩略图总数
     */
    private fun updateNotification(generated: Int, total: Int) {
        val notification = createNotification(generated, total)
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    /**
     * 服务销毁时回调
     *
     * 【功能】
     *   取消正在运行的生成任务协程，重置全局运行状态。
     *
     * 【调用位置】
     *   - Android 系统：服务被销毁时自动调用
     *   - stopSelf() 后系统会调用此方法
     */
    override fun onDestroy() {
        super.onDestroy()
        // 取消协程（如果正在运行）
        generationJob?.cancel()
        // 重置全局运行状态
        AppContainer.isThumbnailGenerationRunning.value = false
        Log.d(TAG, "服务已销毁")
    }
}

/**
 * =============================================================================
 * ThumbnailGenerationBinder —— 服务绑定器
 * =============================================================================
 *
 * 【用途】
 *   标准的 Android Binder 模式，允许 UI 组件（Activity/Fragment）
 *   通过 bindService() 绑定到此 Service，获取 Service 实例后调用
 *   公开方法（如 startGeneration）。
 *
 * 【使用方式】
 *   val binder = service as ThumbnailGenerationBinder
 *   binder.getService().startGeneration(files)
 *
 * 【调用位置】
 *   - MainActivity: 在 onServiceConnected 回调中使用
 *   - LibraryFragment: 绑定服务后请求生成缩略图
 *
 * @param service [ThumbnailGenerationService] 关联的服务实例
 */
class ThumbnailGenerationBinder(private val service: ThumbnailGenerationService) : Binder() {
    /**
     * 获取绑定的服务实例
     *
     * @return [ThumbnailGenerationService] 服务实例
     */
    fun getService(): ThumbnailGenerationService {
        return service
    }
}
