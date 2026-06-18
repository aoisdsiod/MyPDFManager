package com.example.pdfmanager.data.model

/**
 * ZIP 解压后单张图片的预览信息数据类
 *
 * 用于在图片预览界面中表示解压后的单张图片信息。
 * 当用户选择一个 Zip 文件进行解压预览时，此对象被用于 RecyclerView 适配器
 * 加载和展示每张图片的缩略图或全尺寸图。
 * 通常调用流程：
 *   1. ZipImportViewModel 解压 Zip 文件到临时目录
 *   2. 遍历解压结果中的图片文件，为每个文件创建本对象
 *   3. 将列表传递给 UI 层进行预览展示
 *
 * @param index 页码索引（从 1 开始，按文件名自然排序后的顺序）
 *             示例：1（第一页），2（第二页）
 *             格式：Int 类型，1-based 索引
 *             用于在页码指示器或排序中使用，起始值至少为 1
 *             注意：排序依据是文件名，而非解压顺序
 *
 * @param fileName 原始文件名（包括扩展名）
 *             示例："001.jpg"，"photo_2024.png"，"scan_003.jpeg"
 *             格式：String 类型，含文件扩展名
 *             通常用于显示文件名标签或在保存时还原原始文件名
 *
 * @param filePath 解压后临时文件在本地存储中的绝对路径
 *             示例："/data/data/com.example.pdfmanager/cache/unzip/report/001.jpg"
 *             格式：String 类型，文件系统绝对路径
 *             用于 ImageLoader（如 Coil 或 Glide）加载图片显示，
 *             或者在进行 PDF 合成时读取图片数据
 *             注意：该文件位于应用的缓存目录，应用清理缓存后将被删除
 */
data class ImagePreviewInfo(
    val index: Int,
    val fileName: String,
    val filePath: String
)
