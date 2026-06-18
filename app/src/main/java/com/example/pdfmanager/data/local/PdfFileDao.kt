package com.example.pdfmanager.data.local

import androidx.room.*
import com.example.pdfmanager.data.model.PdfFileEntity
import kotlinx.coroutines.flow.Flow

/**
 * Room DAO - PDF 文件数据访问对象
 * 
 * 功能说明：
 * 1. 提供 pdf_files 表的所有 CRUD 操作
 * 2. 支持 Flow 响应式查询（可观察数据变化）
 * 3. 提供缩略图相关的批量更新操作
 * 4. 使用 Room 自动生成的 SQL 实现（部分自定义 @Query）
 * 
 * 使用示例：
 * ```kotlin
 * // 获取所有文件
 * val files = pdfFileDao.getAll()
 * 
 * // 观察变化
 * pdfFileDao.getAllFlow().collect { files ->
 *     // 实时响应数据库变化
 * }
 * 
 * // 批量插入
 * pdfFileDao.insertAll(entities)
 * 
 * // 按 ID 删除
 * pdfFileDao.deleteById(fileId)
 * ```
 * 
 * 对应表结构：
 * - id (TEXT, PRIMARY KEY)：文件唯一标识（基于 URI 的 UUID）
 * - name (TEXT)：文件名（不含扩展名）
 * - display_name (TEXT)：完整文件名（含扩展名）
 * - relative_path (TEXT)：相对路径
 * - uri (TEXT)：文件 URI
 * - size (INTEGER)：文件大小（字节）
 * - last_modified (INTEGER)：最后修改时间（毫秒时间戳）
 * - is_favorite (INTEGER)：收藏标志（0/1）
 * - notes (TEXT)：备注
 * - thumbnail_path (TEXT)：缩略图路径（可空）
 * - thumbnail_generated (INTEGER)：缩略图生成状态（0=未, 1=已, 2=失败）
 * 
 * 调用位置：
 * - PdfRepository：所有 PDF 文件相关的数据库操作
 * - ThumbnailGenerationService：缩略图生成状态更新
 */
@Dao
interface PdfFileDao {

    // ── 查询操作 ─────────────────────────────────────

    /**
     * 获取所有文件（按名称升序排列）
     * 
     * 调用位置：
     * - PdfRepository.restoreFromRoom() - 冷启动时恢复文件列表
     * - PdfRepository.saveToRoom() - 批量保存后验证
     * 
     * @return 所有 PdfFileEntity 列表（按 name ASC 排序）
     */
    @Query("SELECT * FROM pdf_files ORDER BY name ASC")
    suspend fun getAll(): List<PdfFileEntity>

    /**
     * 获取所有文件的 Flow（可观察数据变化）
     * 
     * 使用场景：
     * - 需要实时监听数据库变化的场景
     * - Room 会在底层数据变化时自动发送新数据
     * 
     * @return Flow<List<PdfFileEntity>>（可观察）
     */
    @Query("SELECT * FROM pdf_files ORDER BY name ASC")
    fun getAllFlow(): Flow<List<PdfFileEntity>>

    /**
     * 根据相对路径获取单个文件
     * 
     * 使用场景：
     * - 增量扫描时，通过相对路径匹配已存在的文件
     * 
     * @param path 文件的相对路径
     * @return PdfFileEntity（如果未找到则返回 null）
     */
    @Query("SELECT * FROM pdf_files WHERE relative_path = :path")
    suspend fun getByRelativePath(path: String): PdfFileEntity?

    /**
     * 根据 ID 获取单个文件
     * 
     * 调用位置：
     * - PdfRepository.getFileById() - 获取单个文件详情
     * - DetailViewModel.loadFile() - 加载详情页数据
     * 
     * @param id PDF 文件 ID（基于 URI 的 UUID）
     * @return PdfFileEntity（如果未找到则返回 null）
     */
    @Query("SELECT * FROM pdf_files WHERE id = :id")
    suspend fun getById(id: String): PdfFileEntity?

    /**
     * 获取所有收藏的文件
     * 
     * 调用位置：
     * - PdfRepository.getFavoritePdfFiles() - 加载收藏列表
     * 
     * @return 已收藏的 PdfFileEntity 列表（is_favorite = 1）
     */
    @Query("SELECT * FROM pdf_files WHERE is_favorite = 1")
    suspend fun getFavorites(): List<PdfFileEntity>

    /**
     * 获取文件总数
     * 
     * 使用场景：
     * - 判断是否需要从 JSON 迁移
     * - 统计功能
     * 
     * @return 数据库中的文件数量
     */
    @Query("SELECT COUNT(*) FROM pdf_files")
    suspend fun getCount(): Int

    // ── 插入/更新操作 ─────────────────────────────────────

    /**
     * 插入单条记录（冲突时替换）
     * 
     * 使用 OnConflictStrategy.REPLACE：如果 ID 冲突则覆盖现有记录
     * 
     * 调用位置：
     * - PdfRepository.addFile() - 增量添加文件时调用
     * 
     * @param pdf 要插入的 PdfFileEntity
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(pdf: PdfFileEntity)

    /**
     * 批量插入多条记录（冲突时替换）
     * 
     * 使用 OnConflictStrategy.REPLACE：如果 ID 冲突则覆盖现有记录
     * 
     * 调用位置：
     * - PdfRepository.saveToRoom() - 扫描完成后批量保存
     * - PdfRepository.restoreFromRoom() - 恢复时批量插入
     * 
     * @param pdfs 要插入的 PdfFileEntity 列表
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(pdfs: List<PdfFileEntity>)

    /**
     * 更新单条记录（按 ID 匹配）
     * 
     * 调用位置：
     * - PdfRepository.updateFile() - 更新文件信息时调用
     * 
     * @param pdf 更新后的 PdfFileEntity（必须包含有效的 ID）
     */
    @Update
    suspend fun update(pdf: PdfFileEntity)

    // ── 删除操作 ─────────────────────────────────────

    /**
     * 删除单条记录（按对象匹配）
     * 
     * 调用位置：
     * - PdfRepository.deletePdfFile() - 删除文件时调用
     * 
     * @param pdf 要删除的 PdfFileEntity（必须包含有效的 ID）
     */
    @Delete
    suspend fun delete(pdf: PdfFileEntity)

    /**
     * 根据相对路径删除
     * 
     * 使用场景：
     * - 增量扫描时发现文件已被删除
     * 
     * @param path 文件的相对路径
     */
    @Query("DELETE FROM pdf_files WHERE relative_path = :path")
    suspend fun deleteByRelativePath(path: String)

    /**
     * 根据 ID 删除
     * 
     * 调用位置：
     * - PdfRepository.deletePdfFile() - 删除文件时调用
     * - PdfRepository.quickIncrementalScan() - 增量扫描时删除已不存在的文件
     * 
     * @param id PDF 文件 ID
     */
    @Query("DELETE FROM pdf_files WHERE id = :id")
    suspend fun deleteById(id: String)

    /**
     * 更新文件的 ID 和 URI（用于移动文件场景）
     * 
     * 当文件被移动（URI 改变但内容未变）时，直接更新旧记录的 ID 和 URI，
     * 保留所有元数据（备注、阅读进度、收藏等），避免删除重建导致数据丢失。
     * 
     * 调用位置：
     * - PdfRepository.quickIncrementalScan() - 增量扫描检测到移动文件时调用
     * 
     * @param oldId 旧的 PDF 文件 ID（基于旧 URI 生成）
     * @param newId 新的 PDF 文件 ID（基于新 URI 生成）
     * @param newUri 新的 PDF 文件 URI 字符串
     */
    @Query("UPDATE pdf_files SET id = :newId, uri = :newUri WHERE id = :oldId")
    suspend fun updateIdAndUri(oldId: String, newId: String, newUri: String)

    /**
     * 删除所有记录（清空 pdf_files 表）
     * 
     * 使用场景：
     * - 切换库文件夹时清空旧数据
     * - 用户手动清空所有数据
     * 
     * 注意：此操作不可撤销，谨慎调用
     */
    @Query("DELETE FROM pdf_files")
    suspend fun deleteAll()

    // ── 缩略图相关查询 ───────────────────────────────────

    /**
     * 获取所有未生成缩略图的文件
     * 
     * 使用场景：
     * - 批量生成缩略图时获取需要生成的文件列表
     * 
     * 缩略图状态：
     * - 0：未生成（需要生成）
     * - 1：已生成（无需处理）
     * - 2：生成失败（跳过）
     * 
     * 调用位置：
     * - PdfRepository.getFilesWithoutThumbnail() - 获取需要生成缩略图的文件列表
     * 
     * @return 未生成缩略图的 PdfFileEntity 列表（thumbnail_generated = 0）
     */
    @Query("SELECT * FROM pdf_files WHERE thumbnail_generated = 0")
    suspend fun getFilesWithoutThumbnail(): List<PdfFileEntity>

    /**
     * 更新文件的缩略图生成状态
     * 
     * 调用位置：
     * - PdfRepository.updateThumbnailGeneratedStatus() - 缩略图生成完成后更新状态
     * 
     * @param fileId PDF 文件 ID
     * @param status 状态值：0=未生成, 1=已生成, 2=生成失败
     */
    @Query("UPDATE pdf_files SET thumbnail_generated = :status WHERE id = :fileId")
    suspend fun updateThumbnailGenerated(fileId: String, status: Int)

    /**
     * 更新文件的缩略图路径
     * 
     * 调用位置：
     * - ThumbnailGenerationService.flushBatch() - 批量生成缩略图后更新路径
     * 
     * @param fileId PDF 文件 ID
     * @param path 缩略图相对路径（null 表示清除路径）
     */
    @Query("UPDATE pdf_files SET thumbnail_path = :path WHERE id = :fileId")
    suspend fun updateThumbnailPath(fileId: String, path: String?)

    // ── 文件名查询（轻量操作，无需加载完整对象）───────────────────────────

    /**
     * 获取所有文件名（不含扩展名）
     * 
     * 使用场景：
     * - 重名检测（如 ZIP 转 PDF 时检查是否已存在同名文件）
     * - 统计文件名列表
     * 
     * 性能优化：
     * - 只查询 name 列，不加载整个实体
     * - 返回 List<String>，内存占用小
     * 
     * 调用位置：
     * - PdfRepository.getAllFileNames() - 获取文件名列表
     * 
     * @return 所有 PDF 文件名列表（不含路径和扩展名）
     */
    @Query("SELECT name FROM pdf_files")
    suspend fun getAllFileNames(): List<String>
}
