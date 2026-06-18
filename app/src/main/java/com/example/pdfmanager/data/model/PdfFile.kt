package com.example.pdfmanager.data.model

import android.net.Uri
import java.util.UUID

/**
 * ## PDF 文件数据类
 *
 * 应用的核心数据模型，代表一个被管理的 PDF 文档。
 * 该类的每个实例对应数据库中 [pdf_files] 表中的一条记录，
 * 包含文件元信息（名称、路径、大小）、用户交互状态（收藏、阅读进度）
 * 以及资源状态（缩略图路径、缩略图生成状态）。
 *
 * 整个应用围绕 [PdfFile] 进行：文件列表展示、标签关联、笔记编辑、阅读器跳转等。
 *
 * @property id           唯一标识符，默认使用 UUID 随机生成（例："a1b2c3d4-e5f6-7890-abcd-ef1234567890"）
 * @property name         文件名（不含扩展名），用于列表显示和搜索匹配
 * @property displayName  完整文件名（含扩展名，如 "项目报告.pdf"），用于用户界面的文件显示
 * @property uri          文件的 SAF（Storage Access Framework）URI，用于通过 [ContentResolver] 打开文件流
 * @property size         文件大小（字节），用于文件列表中的大小显示和排序
 * @property lastModified 最后修改时间（毫秒时间戳，从 [System.currentTimeMillis] 获取），用于文件排序
 * @property tags         当前文件关联的标签列表，用于标签筛选、标签界面展示
 * @property notes        用户为文件添加的备注文本，纯文本格式，用于笔记显示和全文搜索
 * @property isFavorite   是否标记为收藏，用于收藏夹列表的筛选和展示
 * @property lastReadPage 上次阅读到的页码（0 表示从未阅读，1~n 表示实际页码），用于阅读器续读跳转
 * @property thumbnailPath 缩略图文件在应用私有目录中的绝对路径
 *                         （如：/data/data/com.example.pdfmanager/files/thumbnails/xxx/document.webp），
 *                         为 null 表示缩略图尚未生成，用于文件列表的缩略图加载
 * @property thumbnailGenerated 缩略图生成状态标志：
 *                             - 0 = 未生成（默认值）
 *                             - 1 = 已生成成功
 *                             - 2 = 生成失败，
 *                             用于列表界面决定是否展示缩略图或占位符
 */
data class PdfFile(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val displayName: String = "",
    val uri: Uri = Uri.EMPTY,
    val size: Long = 0L,
    val lastModified: Long = 0L,
    val tags: List<Tag> = emptyList(),
    val notes: String = "",
    val isFavorite: Boolean = false,
    val lastReadPage: Int = 0,
    val thumbnailPath: String? = null,
    val thumbnailGenerated: Int = 0
) {

    companion object {
        /**
         * ## 从完整文件名提取名称（不含扩展名）
         *
         * 将 "项目报告.pdf" 转换为 "项目报告"，用于初始化 [PdfFile.name] 字段。
         * 在新建文件记录时被 [repository] 调用。
         *
         * ### 调用位置
         * - `PdfRepository.addPdfFile()` — 添加新文件时从 Uri 文件名提取名称
         * - `PdfViewModel` 文件导入流程
         *
         * @param displayName 完整文件名（含扩展名，如 "合同_2024.pdf"）
         * @return 去除最后一个 '.' 及之后内容的纯文件名（如 "合同_2024"）
         */
        fun extractNameFromDisplayName(displayName: String): String {
            return displayName.substringBeforeLast('.')
        }
    }
}
