package com.example.pdfmanager.data.model

/**
 * 转换进度数据类
 *
 * 用于跟踪和展示将图片合成 PDF 文件过程中的实时进度。
 * 此对象在 ViewModel 中通过 StateFlow（如 MutableStateFlow<ConversionProgress>）
 * 向 UI 层发射进度更新，UI 层据此更新进度条、文字提示等控件。
 * 典型的使用场景：ZipImportViewModel 中的图片转 PDF 过程。
 *
 * 进度计算方式建议：
 *   - 总体进度百分比 ≈ (fileIndex * totalPages + currentPage) / (totalFiles * totalPages) * 100
 *   - 进度文本描述可组合为："正在处理第 ${fileIndex}/${totalFiles} 个文件（第 ${currentPage}/${totalPages} 页）：${currentFileName}"
 *
 * @param currentFileName 当前正在处理的文件名
 *             默认值：""（空字符串）
 *             示例："001.jpg"，"document_page_5.png"
 *             格式：String 类型，仅文件名（不含路径）
 *             用于在进度提示中显示当前处理到哪个文件
 *
 * @param currentPage 当前文件已处理的页数（从 0 开始，每次处理完一页后递增）
 *             默认值：0
 *             示例：3（表示当前文件已处理完第 3 页）
 *             格式：Int 类型，逐页递增
 *             注意：如果每个图片文件只对应一页，则该值始终为 0 或 1
 *
 * @param totalPages 当前文件的总页数
 *             默认值：0
 *             示例：10（表示当前文件共有 10 页要处理）
 *             格式：Int 类型
 *             注意：如果是单页图片文件，通常该值固定为 1
 *
 * @param fileIndex 当前正在处理第几个文件（从 0 开始计数）
 *             默认值：0
 *             示例：2（表示正在处理第 3 个文件）
 *             格式：Int 类型，0-based 索引
 *             用于计算总体进度中的"文件维度"进度
 *
 * @param totalFiles 需要处理的总文件数
 *             默认值：0
 *             示例：5（表示共有 5 个文件需要处理）
 *             格式：Int 类型
 *             用于计算总体进度中的"文件总数"参考值
 */
data class ConversionProgress(
    val currentFileName: String = "",
    val currentPage: Int = 0,
    val totalPages: Int = 0,
    val fileIndex: Int = 0,
    val totalFiles: Int = 0
)
