package com.example.pdfmanager.data.model

import android.net.Uri

/**
 * Zip 文件信息数据类
 *
 * 用于表示用户通过 SAF（Storage Access Framework）选取的单个 Zip 压缩文件，
 * 在"导入 Zip 文件"功能中作为数据载体，存储文件的元数据信息。
 * 通常在以下场景使用：
 *   - ZipImportViewModel 中通过 SAF 读取用户选择的 Zip 文件列表，并为每个文件创建本对象
 *   - 在 RecyclerView 中展示待导入的 Zip 文件列表
 *   - 导入前检查文件是否已存在于数据库中（通过 isDuplicate 字段）
 *
 * @param name 文件名（含 .zip 后缀）
 *             示例："report_photos.zip", "document_2024.zip"
 *             格式：纯字符串，包含扩展名
 *
 * @param uri 文件 URI（SAF 方式获取的 content:// URI）
 *             示例：content://com.android.externalstorage.documents/document/primary%3ADownload%2Freport.zip
 *             格式：android.net.Uri 类型，用于通过 ContentResolver 打开输入流读取文件内容
 *
 * @param size 文件大小（字节数）
 *             示例：1024（1KB），1048576（1MB）
 *             格式：Long 类型，单位字节（Byte）
 *
 * @param lastModified 最后修改时间（毫秒时间戳）
 *             示例：1704067200000（对应 2024-01-01 00:00:00 UTC）
 *             格式：Long 类型，Unix 时间戳，单位毫秒（ms）
 *             通常用于文件列表排序（按修改时间先后排列）
 *
 * @param isDuplicate 是否与库中已存在的文件重名（判断时去掉扩展名后比较）
 *             默认值：false
 *             示例：true（表示已存在同名文件），false（表示新文件）
 *             用于在导入列表中标记重复文件，方便用户识别
 */
data class ZipFileInfo(
    val name: String,
    val uri: Uri,
    val size: Long,
    val lastModified: Long,
    val isDuplicate: Boolean = false
)
