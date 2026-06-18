package com.example.pdfmanager.data.model

import android.net.Uri
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.ColumnInfo
import java.util.UUID

/**
 * ## PdfFileEntity — Room 数据库实体，对应 pdf_files 表
 *
 * ### 数据库表结构
 * 表名：`pdf_files`
 *
 * 该表存储所有被管理的 PDF 文件的元数据信息（不含文件二进制内容），
 * 是应用的核心数据表之一。每条记录对应一个被扫描到的 PDF 文档。
 *
 * ### 表字段一览
 * | 列名                | 类型      | 约束                            | 说明                              |
 * |---------------------|-----------|----------------------------------|-----------------------------------|
 * | id                  | TEXT      | PRIMARY KEY, NOT NULL            | 唯一标识符（UUID）                |
 * | name                | TEXT      | NOT NULL                         | 文件名（不含扩展名）              |
 * | display_name        | TEXT      | NOT NULL                         | 完整文件名（含扩展名）            |
 * | uri                 | TEXT      | NOT NULL                         | SAF 文件 URI（String 形式）       |
 * | size                | INTEGER   | NOT NULL                         | 文件大小（字节）                  |
 * | last_modified       | INTEGER   | NOT NULL                         | 最后修改时间戳（毫秒）            |
 * | thumbnail_path      | TEXT      | NULLABLE                         | 缩略图路径（可为 null）           |
 * | notes               | TEXT      | NOT NULL                         | 用户备注                          |
 * | is_favorite         | INTEGER   | NOT NULL                         | 收藏标志（0/1）                   |
 * | last_read_page      | INTEGER   | NOT NULL                         | 上次阅读页码                      |
 * | relative_path       | TEXT      | NOT NULL                         | 相对路径（用于增量文件匹配）      |
 * | thumbnail_generated | INTEGER   | NOT NULL, DEFAULT 0              | 缩略图生成状态（0=未生成, 1=已生成, 2=失败） |
 *
 * ### 实体关系
 * - [PdfFileEntity] 与 [PdfTagEntity] 存在一对多关系：一个 PDF 文件可以有多个标签。
 *   标签关系存储在独立的 [pdf_tags] 表中，通过 [PdfTagEntity.pdfFileUri] 关联。
 * - 标签类别信息存储在 [tag_categories] 表和 [category_tags] 表中。
 *
 * ### 转换函数
 * - `toPdfFile()`: Entity → Domain Model（给 ViewModel / UI 层使用）
 * - `fromPdfFile()`: Domain Model → Entity（给 Repository 层使用）
 *
 * ### 使用场景
 * - PdfFileDao：所有 CRUD 操作均通过此 DAO 进行
 * - PdfRepository：扫描完成后调用 saveToRoom() 批量写入
 * - PdfRepository：冷启动时调用 restoreFromRoom() 恢复文件列表
 *
 * ### 数据库版本历史
 * - v1：初始表结构
 * - v4：新增 thumbnail_generated 和 thumbnail_path 列（MIGRATION_3_4）
 *
 * @see PdfFile PdfFile 域模型（与此 Entity 互转）
 * @see PdfFileDao 操作此表的 DAO 接口
 * @see PdfRepository 使用此 Entity 的仓库层
 */
@Entity(tableName = "pdf_files")
data class PdfFileEntity(
    /**
     * 文件唯一标识符
     *
     * - **类型**: String
     * - **格式**: UUID 字符串（例："a1b2c3d4-e5f6-7890-abcd-ef1234567890"）
     * - **生成方式**: 基于文件 URI 的确定性 UUID (UUID.nameUUIDFromBytes)
     *   - 同一个文件始终生成相同的 ID，即使文件路径变化也能识别
     * - **不可空**: 是，主键不可为空
     * - **默认值**: 无（必须在创建时提供）
     *
     * 【用途】
     * - 作为 pdf_files 表的主键，唯一标识一个 PDF 文件
     * - 用于 PdfFileDao 的 getById()、deleteById() 等操作
     * - 在增量和全量扫描中作为文件匹配的依据
     */
    @PrimaryKey
    val id: String,

    /**
     * 文件名（不含扩展名）
     *
     * - **类型**: String
     * - **格式**: 纯文件名文本，不含路径和扩展名
     *   - 例："项目报告"（文件全名为 "项目报告.pdf"）
     * - **不可空**: 是
     * - **默认值**: 无
     *
     * 【用途】
     * - 用于文件列表按名称排序（ORDER BY name ASC）
     * - 用于全文搜索（searchPdfFiles 按 name 模糊匹配）
     * - 用于增量扫描中的文件名匹配（检测文件是否被移动/重命名）
     * - 用于重名检测（getAllFileNames 返回此字段）
     *
     * 【数据来源】
     * - 从 displayName 通过 PdfFile.extractNameFromDisplayName() 提取
     * - 提取逻辑：displayName.substringBeforeLast('.')
     *
     * @see PdfFile.extractNameFromDisplayName 提取方法
     */
    @ColumnInfo(name = "name")
    val name: String,

    /**
     * 完整文件名（含扩展名）
     *
     * - **类型**: String
     * - **格式**: 包含文件名和扩展名的完整文本
     *   - 例："项目报告.pdf"、"会议记录_2024.PDF"
     * - **不可空**: 是
     * - **默认值**: 无
     *
     * 【用途】
     * - 用于文件列表 UI 显示（用户看到的是完整文件名）
     * - 用于全文搜索（按 displayName 模糊匹配）
     *
     * 【与 name 字段的关系】
     * - displayName = name + ".pdf"
     * - name 不含扩展名，用于排序和匹配
     * - displayName 含扩展名，用于 UI 展示
     *
     * @see name 不含扩展名的文件名字段
     */
    @ColumnInfo(name = "display_name")
    val displayName: String,

    /**
     * 文件的 SAF（Storage Access Framework）URI（String 形式）
     *
     * - **类型**: String
     * - **格式**: URI 字符串
     *   - File API 示例："file:///storage/emulated/0/PDFs/文档.pdf"
     *   - SAF (DocumentFile) 示例： "content://com.android.externalstorage.documents/document/primary%3APDFs%2F文档.pdf"
     * - **不可空**: 是
     * - **默认值**: 无
     *
     * 【用途】
     * - 通过 ContentResolver 打开文件流进行读取
     * - 传递到 PdfRenderer 进行 PDF 渲染
     * - 传递到 ThumbnailGenerator 生成缩略图
     * - 作为外键关联到 PdfTagEntity.pdfFileUri
     *
     * 【重要说明】
     * - Room 无法直接存储 android.net.Uri 类型，需转为 String 存储
     * - 在 toPdfFile() 中通过 Uri.parse() 还原为 Uri 对象
     * - 在 fromPdfFile() 中通过 uri.toString() 转为 String
     *
     * @see android.net.Uri.parse 还原 Uri 对象
     */
    @ColumnInfo(name = "uri")
    val uri: String,

    /**
     * 文件大小（字节）
     *
     * - **类型**: Long
     * - **格式**: 文件字节数，无符号
     *   - 例：1048576 = 1MB
     * - **不可空**: 是
     * - **默认值**: 无
     *
     * 【用途】
     * - 文件列表按大小排序
     * - 文件大小信息展示（自动格式化为 KB/MB/GB）
     * - 用于判断是否为大文件（如 > 50MB 提示用户注意）
     */
    @ColumnInfo(name = "size")
    val size: Long,

    /**
     * 文件最后修改时间（毫秒时间戳）
     *
     * - **类型**: Long
     * - **格式**: System.currentTimeMillis() 格式的毫秒级时间戳
     *   - 例：1718611200000 = 2024-06-17 08:00:00 GMT
     * - **不可空**: 是
     * - **默认值**: 无
     *
     * 【用途】
     * - 文件列表按最后修改时间排序
     * - 文件信息展示（格式化为 "2024-06-17 16:00"）
     * - 用于增量扫描时检测文件是否被更新
     */
    @ColumnInfo(name = "last_modified")
    val lastModified: Long,

    /**
     * 缩略图文件路径（相对于应用私有目录）
     *
     * - **类型**: String?（可空）
     * - **格式**: 应用私有目录下的相对路径或绝对路径
     *   - 例："/data/data/com.example.pdfmanager/files/thumbnails/a1b2c3d4.webp"
     *   - 为 null 表示缩略图尚未生成
     * - **可空**: 是（null = 未生成缩略图）
     * - **默认值**: null
     *
     * 【用途】
     * - 文件列表项显示缩略图（若此路径不为 null 则显示）
     * - 传递给 Coil/Glide 等图片加载库进行加载
     *
     * 【关联字段】
     * - 与 thumbnailGenerated 配合使用：路径存在且生成状态为 1 时才被视为有效缩略图
     * - 更新此字段时，调用 PdfFileDao.updateThumbnailPath()
     *
     * @see thumbnailGenerated 缩略图生成状态标志
     */
    @ColumnInfo(name = "thumbnail_path")
    val thumbnailPath: String?,

    /**
     * 用户为文件添加的备注文本
     *
     * - **类型**: String
     * - **格式**: 纯文本字符串
     *   - 例："这是一个重要的合同文档，2024年版"
     * - **不可空**: 是（可以为空字符串 ""）
     * - **默认值**: 无
     *
     * 【用途】
     * - 文件详情页展示和编辑备注
     * - 全文搜索（按 notes 模糊匹配）
     *
     * 【更新方式】
     * - 用户在详情页编辑备注后，调用 PdfRepository.updateFile() 更新
     */
    @ColumnInfo(name = "notes")
    val notes: String,

    /**
     * 收藏标志
     *
     * - **类型**: Boolean
     * - **格式**: true = 已收藏, false = 未收藏
     * - **数据库存储**: Room 自动映射为 INTEGER（true=1, false=0）
     * - **不可空**: 是
     * - **默认值**: 无
     *
     * 【用途】
     * - 收藏夹列表筛选（Dao.getFavorites() 查询 is_favorite = 1 的记录）
     * - 文件列表项的星标显示
     * - 收藏/取消收藏操作：PdfRepository.toggleFavorite()
     *
     * 【使用场景】
     * - 用户点击收藏按钮切换此值
     * - 在"收藏"Tab 中只显示 isFavorite = true 的文件
     */
    @ColumnInfo(name = "is_favorite")
    val isFavorite: Boolean,

    /**
     * 上次阅读页码（断点续读）
     *
     * - **类型**: Int（var 可变字段）
     * - **格式**: 非负整数
     *   - 0 = 从未阅读过
     *   - 1 ~ n = 上次阅读到的实际页码（1-based）
     * - **不可空**: 是
     * - **默认值**: 无
     *
     * 【用途】
     * - 阅读器打开时自动跳转到上次阅读的位置
     * - 文件列表项显示"已读到第 X 页"的进度信息
     *
     * 【更新方式】
     * - 用户在阅读器翻页时自动更新（每翻 3 页保存一次）
     * - 调用 PdfRepository.updateFile() 同步到数据库
     *
     * 【为何是 var】
     * - 此字段在应用运行时会被频繁更新
     * - 每次翻页都会直接修改此变量的值
     * - 通过 PdfRepository.updateFile() 同步到 Room 数据库
     */
    @ColumnInfo(name = "last_read_page")
    var lastReadPage: Int,

    /**
     * 文件相对于库文件夹的路径（用于增量扫描时匹配文件）
     *
     * - **类型**: String
     * - **格式**: 相对于库根目录的路径
     *   - 根目录下文件：""（空字符串）
     *   - 子目录下文件："子文件夹/文档.pdf"（含文件名）
     * - **不可空**: 是
     * - **默认值**: 无
     *
     * 【用途】
     * - 增量扫描时，通过相对路径匹配已存在的文件（Dao.getByRelativePath()）
     * - 检测文件是否被移动（文件名相同但 relativePath 不同 -> 判定为移动）
     * - 文件组织结构展示时判断文件所在的子文件夹
     *
     * 【设计初衷】
     * - 相比 URI 对比，相对路径对比更快、更可靠
     * - 当用户移动整个库文件夹时，相对路径不变，文件匹配更准确
     *
     * @see PdfFileDao.getByRelativePath 按相对路径查询
     */
    @ColumnInfo(name = "relative_path")
    val relativePath: String,

    /**
     * 缩略图生成状态标志
     *
     * - **类型**: Int
     * - **格式**: 整型状态码
     *   - 0 = 未生成（默认值，需要生成缩略图）
     *   - 1 = 已生成成功（缩略图可用）
     *   - 2 = 生成失败（跳过，不再重试）
     * - **不可空**: 是（默认值 0）
     * - **默认值**: 0（未生成）
     * - **数据库约束**: DEFAULT 0
     *
     * 【用途】
     * - 文件列表项决定是否显示缩略图或占位符
     *   - 状态 0：显示默认 PDF 图标，后台触发缩略图生成
     *   - 状态 1：加载 thumbnailPath 显示缩略图
     *   - 状态 2：显示默认 PDF 图标（带失败标志）
     * - 批量生成缩略图时，Dao.getFilesWithoutThumbnail() 查询状态为 0 的文件
     * - 避免重复对生成失败的文件做无效操作
     *
     * 【更新方式】
     * - ThumbnailGenerationService 生成成功后，调用 Dao.updateThumbnailGenerated() 更新为 1
     * - ThumbnailGenerationService 生成失败后，调用 Dao.updateThumbnailGenerated() 更新为 2
     *
     * 【关联字段】
     * - thumbnailPath：当状态为 1 时，此路径指向有效的缩略图文件
     *
     * @see thumbnailPath 缩略图文件路径
     * @see PdfFileDao.getFilesWithoutThumbnail 查询未生成缩略图的文件
     * @see PdfFileDao.updateThumbnailGenerated 更新生成状态
     */
    @ColumnInfo(name = "thumbnail_generated", defaultValue = "0")
    val thumbnailGenerated: Int = 0
) {
    /**
     * ## toPdfFile — Entity 转 Domain Model
     *
     * 将 Room 数据库实体 [PdfFileEntity] 转换为业务层使用的域模型 [PdfFile]。
     * 此方法在 Repository 层中被调用，用于将数据库中的数据提供给 ViewModel 和 UI 层使用。
     *
     * ### 转换说明
     * - uri 字段：从 String 还原为 android.net.Uri（调用 Uri.parse()）
     * - tags 字段：默认传入空列表，调用方需通过 PdfTagDao 单独加载 PDF 的标签
     * - 其余字段：直接一对一映射
     *
     * ### 调用位置
     * - `PdfRepository.restoreFromRoom()`: 冷启动恢复时调用，从 Room 数据库读取所有实体并转为域模型
     *   ```
     *   val entities = pdfFileDao.getAll()
     *   val files = entities.map { it.toPdfFile() }
     *   ```
     * - `PdfRepository.getFilesWithoutThumbnail()`: 获取未生成缩略图的文件列表
     *   ```
     *   val entities = pdfFileDao.getFilesWithoutThumbnail()
     *   return entities.map { it.toPdfFile() }
     *   ```
     *
     * ### 使用场景
     * - 应用冷启动，从 Room 数据库恢复文件列表到内存
     * - 查询未生成缩略图的文件
     * - 获取收藏文件列表
     *
     * @param tags PDF 文件关联的标签列表，默认为空列表
     *             （调用方需要单独从 PdfTagDao 加载）
     * @return PdfFile 域模型对象（供 ViewModel / UI 层使用）
     *
     * @see PdfFile PdfFile 域模型
     * @see PdfRepository.restoreFromRoom 调用此方法的仓库函数
     * @see PdfRepository.getFilesWithoutThumbnail 调用此方法的仓库函数
     */
    fun toPdfFile(tags: List<Tag> = emptyList()): PdfFile {
        return PdfFile(
            id = id,
            name = name,
            displayName = displayName,
            uri = android.net.Uri.parse(uri),  // 将 String 还原为 Uri 对象
            size = size,
            lastModified = lastModified,
            tags = tags,
            notes = notes,
            isFavorite = isFavorite,
            lastReadPage = lastReadPage,
            thumbnailPath = thumbnailPath,
            thumbnailGenerated = thumbnailGenerated
        )
    }

    companion object {
        /**
         * ## fromPdfFile — Domain Model 转 Entity
         *
         * 将业务层使用的域模型 [PdfFile] 转换为 Room 数据库实体 [PdfFileEntity]。
         * 此方法在 Repository 层中被调用，用于将业务数据持久化到 Room 数据库。
         *
         * ### 转换说明
         * - uri 字段：从 android.net.Uri 转为 String（调用 uri.toString()）
         * - relativePath 参数：由调用方根据库文件夹路径计算得出
         * - 其余字段：直接一对一映射
         * - tags 字段：不存储在 PdfFileEntity 中，需通过 PdfTagDao 单独处理
         *
         * ### 调用位置
         * - `PdfRepository.saveToRoom()`: 批量保存文件列表到数据库时调用
         *   ```
         *   val entities = files.map { PdfFileEntity.fromPdfFile(it, "") }
         *   pdfFileDao.insertAll(entities)
         *   ```
         * - `PdfRepository.updateFile()`: 更新单个文件时调用
         *   ```
         *   val entity = PdfFileEntity.fromPdfFile(pdfFile, "")
         *   pdfFileDao.update(entity)
         *   ```
         *
         * ### 使用场景
         * - 全量扫描完成后，批量写入 Room 数据库
         * - 增量扫描发现问题文件后，更新数据库
         * - 用户修改文件属性（收藏、备注、阅读进度）后，同步更新数据库
         *
         * @param pdf PdfFile 域模型对象（包含完整的文件元数据）
         * @param relativePath 文件相对于库文件夹的路径
         *                     （用于增量文件匹配，扫描时由 FileScanner 计算，
         *                      非扫描场景如手动更新时传空字符串 ""）
         * @return PdfFileEntity Room 数据库实体
         *
         * @see PdfFile PdfFile 域模型
         * @see PdfRepository.saveToRoom 调用此方法的仓库函数
         * @see PdfRepository.updateFile 调用此方法的仓库函数
         */
        fun fromPdfFile(pdf: PdfFile, relativePath: String): PdfFileEntity {
            return PdfFileEntity(
                id = pdf.id,
                name = pdf.name,
                displayName = pdf.displayName,
                uri = pdf.uri.toString(),  // 将 Uri 转为 String 以适配 Room 存储
                size = pdf.size,
                lastModified = pdf.lastModified,
                thumbnailPath = pdf.thumbnailPath,
                notes = pdf.notes,
                isFavorite = pdf.isFavorite,
                lastReadPage = pdf.lastReadPage,
                relativePath = relativePath,
                thumbnailGenerated = pdf.thumbnailGenerated
            )
        }
    }
}
