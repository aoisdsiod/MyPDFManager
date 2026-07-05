package com.example.pdfmanager.ui.viewmodel

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.pdfmanager.data.model.ConversionProgress
import com.example.pdfmanager.data.model.ImagePreviewInfo
import com.example.pdfmanager.data.model.ZipFileInfo
import com.example.pdfmanager.data.repository.AppContainer
import com.example.pdfmanager.data.repository.ConversionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID

/**
 * 【PDF 转换 ViewModel】
 *
 * ── 功能说明 ──
 * 作为 PdfConversionScreen 和 PageSelectionScreen 两个页面的数据持有者，
 * 管理从 ZIP 扫描、文件选择、解压预览、页码选择到最终 PDF 生成的完整转换流程。
 * 通过 activity 级 ViewModelStoreOwner 共享实例，确保导航前后状态不丢失。
 *
 * ── 使用场景 ──
 * 用户从"库"页面进入 PDF 转换功能后，通过此 ViewModel 的 StateFlow 驱动 UI 状态变化，
 * 包括：文件夹选择、文件列表扫描、文件选中/取消、转换进度跟踪、转换结果展示。
 *
 * ── 被调用文件 ──
 * - PdfConversionScreen.kt: 主转换页面，消费大部分状态和方法
 * - PageSelectionScreen.kt: 页码选择页面，消费 extractedImages/selectedPages 等状态
 *
 * @param application Android Application 实例，用于获取 Context 和 PreferencesManager
 * @param repository  转换核心逻辑仓库层，负责文件扫描和批量转换
 */
class ConversionViewModel(
    application: Application,
    private val repository: ConversionRepository
) : AndroidViewModel(application) {

    // ═══════════════════════════════════════════════════════════════════
    // 一、基础状态定义（监控文件夹、输出路径、文件列表）
    // ═══════════════════════════════════════════════════════════════════

    // 【监控文件夹 URI】ZIP 文件的来源目录 URI（持久化存储）
    private val _monitorFolderUri = MutableStateFlow<String?>(null)
    val monitorFolderUri: StateFlow<String?> = _monitorFolderUri.asStateFlow()

    // 【输出位置 URI】生成 PDF 的目标目录 URI（null = 使用库根目录）
    private val _outputPathUri = MutableStateFlow<String?>(null)
    val outputPathUri: StateFlow<String?> = _outputPathUri.asStateFlow()

    // 【扫描到的 ZIP 文件列表】每个元素包含文件名、URI、大小和重名标记
    private val _zipFiles = MutableStateFlow<List<ZipFileInfo>>(emptyList())
    val zipFiles: StateFlow<List<ZipFileInfo>> = _zipFiles.asStateFlow()

    // 【用户选中的文件名集合】多选模式为多元素 Set，单选模式为单元素 Set
    private val _selectedFiles = MutableStateFlow<Set<String>>(emptySet())
    val selectedFiles: StateFlow<Set<String>> = _selectedFiles.asStateFlow()

    // 【是否正在扫描】用于显示扫描中的加载指示器
    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    // 【转换进度】不为 null 时表示正在转换中，UI 显示进度弹窗
    private val _conversionProgress = MutableStateFlow<ConversionProgress?>(null)
    val conversionProgress: StateFlow<ConversionProgress?> = _conversionProgress.asStateFlow()

    // 【转换结果】(成功文件数, 失败文件名列表)，不为 null 时显示结果弹窗
    private val _conversionResult = MutableStateFlow<Pair<Int, List<String>>?>(null)
    val conversionResult: StateFlow<Pair<Int, List<String>>?> = _conversionResult.asStateFlow()

    // ═══════════════════════════════════════════════════════════════════
    // 二、页码选择相关状态（用于单选模式下的解压预览和页面选择）
    // ═══════════════════════════════════════════════════════════════════

    // 【"默认转换所有图片"开关】true = 多选模式（跳过页面选择，直接批量转换）
    //                           false = 单选模式（先解压，进入页面选择页选页）
    private val _convertAllImages = MutableStateFlow(true)
    val convertAllImages: StateFlow<Boolean> = _convertAllImages.asStateFlow()

    // 【当前预览的 ZIP 文件名】用于转换完成后删除临时目录
    private val _currentPreviewZipName = MutableStateFlow<String?>(null)

    // 【当前预览的 ZIP 文件 URI】用于转换完成后删除原始 ZIP 文件
    // 支持库目录和监控文件夹中的文件
    private val _currentPreviewZipUri = MutableStateFlow<Uri?>(null)

    // 【解压后的图片预览信息列表】null = 未解压或已清理，非 null = 可导航到页面选择页
    private val _extractedImages = MutableStateFlow<List<ImagePreviewInfo>?>(null)
    val extractedImages: StateFlow<List<ImagePreviewInfo>?> = _extractedImages.asStateFlow()

    // 【用户选中的页码集合（1-based）】null = 全选，emptySet = 全不选
    private val _selectedPages = MutableStateFlow<Set<Int>?>(null)
    val selectedPages: StateFlow<Set<Int>?> = _selectedPages.asStateFlow()

    // 【是否正在解压】用于显示加载中提示
    private val _isExtracting = MutableStateFlow(false)
    val isExtracting: StateFlow<Boolean> = _isExtracting.asStateFlow()

    // ═══════════════════════════════════════════════════════════════════
    // 三、初始化
    // ═══════════════════════════════════════════════════════════════════

    init {
        // 从 PreferencesManager 加载持久化的监控文件夹 URI（实时观察变化）
        viewModelScope.launch {
            val prefs = com.example.pdfmanager.data.local.PreferencesManager(getApplication())
            prefs.getConversionMonitorFolderFlow().collect { uri ->
                _monitorFolderUri.value = uri
            }
        }
        // 从 PreferencesManager 加载持久化的输出路径 URI（实时观察变化）
        viewModelScope.launch {
            val prefs = com.example.pdfmanager.data.local.PreferencesManager(getApplication())
            prefs.getConversionOutputPathFlow().collect { uri ->
                _outputPathUri.value = uri
            }
        }
        // 注意：_convertAllImages 默认值为 true（兼容旧版行为），不读取持久化设置
    }

    // ═══════════════════════════════════════════════════════════════════
    // 四、文件扫描与选择方法
    // ═══════════════════════════════════════════════════════════════════

    /**
     * 【扫描 ZIP 文件】
     *
     * 调用 Repository 扫描指定文件夹中的 ZIP 文件，检查重名后更新 UI 状态。
     * 由 PdfConversionScreen.kt 在页面加载时和用户点击"重新扫描"按钮时调用。
     *
     * 扫描逻辑：
     * 1. 调用 repository.scanZipFiles() 获取 ZIP 文件列表
     * 2. 遍历扫描结果，调用 checkDuplicates() 检测哪些 ZIP 与库中 PDF 同名
     * 3. 更新 _zipFiles 并给重名文件标记 isDuplicate = true
     * 4. 根据 convertAllImages 状态自动预设选中项：
     *    - 多选模式（true）：默认选中所有非重名的文件
     *    - 单选模式（false）：不选中任何文件
     */
    fun scanZipFiles() {
        viewModelScope.launch {
            _isScanning.value = true
            val files = repository.scanZipFiles()

            // 检测重名：检查 ZIP 文件名（去后缀）是否与库中已存在的 PDF 文件名重复
            val duplicateNames = checkDuplicates(files)

            // 更新列表，为每个文件标记是否重名
            val filesWithFlag = files.map { zipInfo ->
                zipInfo.copy(
                    isDuplicate = duplicateNames.contains(
                        zipInfo.name.substringBeforeLast(".", zipInfo.name)
                    )
                )
            }

            _zipFiles.value = filesWithFlag

            // 根据 convertAllImages 状态决定初始选中哪些文件
            if (_convertAllImages.value) {
                // 多选模式：默认只勾选非重名文件（避免转换后覆盖）
                _selectedFiles.value = filesWithFlag
                    .filterNot { it.isDuplicate }
                    .map { it.name }
                    .toSet()
            } else {
                // 单选模式：不选中任何文件，让用户手动选择
                _selectedFiles.value = emptySet()
            }

            _isScanning.value = false
        }
    }

    /**
     * 【检测 ZIP 文件是否与库中 PDF 重名】
     *
     * 将库中所有 PDF 文件的文件名（去掉 .pdf 后缀）与 ZIP 文件名（去掉 .zip 后缀）比对。
     * 如果存在同名文件，说明转换后的 PDF 会覆盖已有文件，需要向用户发出警告。
     *
     * @param zipFiles 扫描到的 ZIP 文件列表
     * @return 存在重名的文件名集合（不含后缀的纯文件名）
     */
    private suspend fun checkDuplicates(zipFiles: List<ZipFileInfo>): Set<String> = withContext(Dispatchers.IO) {
        val duplicateNames = mutableSetOf<String>()

        // 获取库中所有 PDF 文件名，去掉 .pdf 后缀后存入集合
        val existingPdfNames = mutableSetOf<String>()
        val pdfFileNames = AppContainer.pdfRepository.getAllFileNames()
        pdfFileNames.forEach { name ->
            val nameWithoutExt = name.substringBeforeLast(".", name)
            existingPdfNames.add(nameWithoutExt)
        }

        // 遍历 ZIP 文件，检测是否与已有 PDF 同名
        zipFiles.forEach { zipInfo ->
            val nameWithoutExt = zipInfo.name.substringBeforeLast(".", zipInfo.name)
            if (existingPdfNames.contains(nameWithoutExt)) {
                duplicateNames.add(nameWithoutExt)
            }
        }

        duplicateNames
    }

    /**
     * 【全选】将所有 ZIP 文件加入选中列表
     *
     * 调用位置：PdfConversionScreen 中的全选操作（当前 UI 没有直接调用此方法的按钮，
     * 但可以在功能扩展时使用，目前 scanZipFiles 中已根据模式自动设置初始选中状态）
     */
    fun selectAll() {
        _selectedFiles.value = _zipFiles.value.map { it.name }.toSet()
    }

    /**
     * 【取消全选】清空所有选中文件
     *
     * 调用位置：PdfConversionScreen 中的取消全选操作
     */
    fun deselectAll() {
        _selectedFiles.value = emptySet()
    }

    // ═══════════════════════════════════════════════════════════════════
    // 五、文件夹设置方法
    // ═══════════════════════════════════════════════════════════════════

    /**
     * 【保存监控文件夹 URI】
     *
     * 将用户通过 SAF 选择的监控文件夹 URI 持久化到 Preferences，
     * 并立即重新扫描 ZIP 文件列表。
     *
     * 调用位置：PdfConversionScreen.kt → monitorFolderLauncher 回调
     *
     * @param uri 用户从系统文件选择器选中的文件夹 URI
     */
    fun saveMonitorFolder(uri: Uri) {
        viewModelScope.launch {
            val prefs = com.example.pdfmanager.data.local.PreferencesManager(getApplication())
            prefs.saveConversionMonitorFolder(uri.toString())
            _monitorFolderUri.value = uri.toString()
            // 文件夹变更后立即重新扫描
            scanZipFiles()
        }
    }

    /**
     * 【清除监控文件夹】
     *
     * 将监控文件夹设置清空（回到默认），并重新扫描。
     *
     * 调用位置：PdfConversionScreen.kt → "清除"按钮
     */
    fun clearMonitorFolder() {
        viewModelScope.launch {
            val prefs = com.example.pdfmanager.data.local.PreferencesManager(getApplication())
            prefs.saveConversionMonitorFolder("")
            _monitorFolderUri.value = null
            scanZipFiles()
        }
    }

    /**
     * 【保存输出位置 URI】
     *
     * 将用户选择的 PDF 输出目录 URI 持久化到 Preferences。
     *
     * 调用位置：PdfConversionScreen.kt → LibraryFolderPickerDialog 的回调 onFolderSelected
     *
     * @param uri 用户选中的输出文件夹 URI
     */
    fun saveOutputPath(uri: Uri) {
        viewModelScope.launch {
            val prefs = com.example.pdfmanager.data.local.PreferencesManager(getApplication())
            prefs.saveConversionOutputPath(uri.toString())
            _outputPathUri.value = uri.toString()
        }
    }

    /**
     * 【清除输出位置（恢复默认库根目录）】
     *
     * 将输出路径清空，表示后续生成的 PDF 将保存到库根目录。
     *
     * 调用位置：PdfConversionScreen.kt → "恢复默认"按钮
     */
    fun clearOutputPath() {
        viewModelScope.launch {
            val prefs = com.example.pdfmanager.data.local.PreferencesManager(getApplication())
            prefs.saveConversionOutputPath("")
            _outputPathUri.value = null
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // 六、批量转换方法（多选模式）
    // ═══════════════════════════════════════════════════════════════════

    /**
     * 【开始批量转换（多选模式）】
     *
     * 将用户选中的多个 ZIP 文件批量转换为 PDF。
     * 此方法在 convertAllImages = true（"默认转换所有图片"开关打开）时由"开始转换"按钮调用。
     *
     * 流程：
     * 1. 设置初始进度状态（第一个文件）
     * 2. 调用 repository.convertZipFiles() 执行批量转换
     * 3. 转换完成后：
     *    a. 清除进度状态，设置转换结果
     *    b. 从列表中移除已成功转换的 ZIP 文件
     *    c. 同步更新选中状态
     * 4. 失败时记录异常并返回失败结果
     *
     * 调用位置：PdfConversionScreen.kt → 底部"开始转换"按钮的 onClick
     */
    fun startConversion() {
        val filesToConvert = _zipFiles.value.filter { _selectedFiles.value.contains(it.name) }
        if (filesToConvert.isEmpty()) return

        viewModelScope.launch {
            // 设置初始进度（第一个文件的名称，索引从 1 开始）
            _conversionProgress.value = ConversionProgress(
                currentFileName = filesToConvert.first().name,
                fileIndex = 1,
                totalFiles = filesToConvert.size
            )

            try {
                val outputUri = _outputPathUri.value?.let { Uri.parse(it) }
                Log.i("ConversionViewModel",
                    "开始调用 repository.convertZipFiles(), filesToConvert=${filesToConvert.size}, outputUri=$outputUri")
                // 调用 Repository 执行实际的 ZIP→PDF 批量转换
                val result = repository.convertZipFiles(
                    zipFiles = filesToConvert,
                    outputDirUri = outputUri,
                    progress = _conversionProgress
                )
                Log.i("ConversionViewModel",
                    "convertZipFiles() 返回: success=${result.first}, failed=${result.second.size}")

                // 在主线程更新 UI 状态：先清除进度，再设置结果（确保进度弹窗先消失）
                withContext(Dispatchers.Main) {
                    _conversionProgress.value = null
                    _conversionResult.value = result
                }
                val (successCount, failedFiles) = result

                // 转换完成后，从文件列表中移除已成功转换的 ZIP
                if (successCount > 0) {
                    val failedSet = failedFiles.toSet()
                    val remainingFiles = _zipFiles.value.filterNot { zipFile ->
                        // 成功转换的条件：在待转换列表中，且不在失败列表中
                        filesToConvert.any { it.name == zipFile.name } &&
                            !failedSet.contains(zipFile.name)
                    }
                    _zipFiles.value = remainingFiles
                    // 同步更新选中状态：移除已转换的文件
                    _selectedFiles.value = _selectedFiles.value
                        .filter { name -> remainingFiles.any { it.name == name } }
                        .toSet()
                }
            } catch (e: Exception) {
                Log.e("ConversionViewModel", "转换失败", e)
                withContext(Dispatchers.Main) {
                    _conversionProgress.value = null
                    _conversionResult.value = Pair(0, filesToConvert.map { it.name })
                }
            } finally {
                // 注意：不在 finally 中清除 _conversionProgress，
                // 避免进度弹窗来不及显示就被清除。
                // _conversionProgress 会在 clearConversionResult() 中被清除（用户关闭结果弹窗时）
            }
        }
    }

    /**
     * 【清除转换结果】
     *
     * 当用户关闭转换结果弹窗时调用，同时清理进度状态和预览临时目录。
     *
     * 调用位置：PdfConversionScreen.kt → 转换结果 AlertDialog 的 onDismissRequest 和确认按钮
     */
    fun clearConversionResult() {
        Log.i("ConversionViewModel", "clearConversionResult() 被调用")
        _conversionResult.value = null
        _conversionProgress.value = null  // 同时清除进度状态，避免影响下次转换
        clearPreview()  // 清理预览临时目录和状态
    }

    // ═══════════════════════════════════════════════════════════════════
    // 七、页码选择相关方法（单选模式）
    // ═══════════════════════════════════════════════════════════════════

    /**
     * 【切换"默认转换所有图片"开关】
     *
     * 控制 PdfConversionScreen 中文件选择的模式：
     * - true（开）：使用 Checkbox 多选，点击"开始转换"直接批量转换所有选中的 ZIP
     * - false（关）：使用 RadioButton 单选，点击"开始转换"先解压单个 ZIP 并进入页面选择页
     *
     * 切换时自动重置选中状态：
     * - 开 → 默认选中非重名文件
     * - 关 → 清空选中（让用户手动单选）
     *
     * 调用位置：PdfConversionScreen.kt → Switch 组件的 onCheckedChange
     */
    fun toggleConvertAllImages() {
        val newValue = !_convertAllImages.value
        _convertAllImages.value = newValue
        viewModelScope.launch {
            val prefs = com.example.pdfmanager.data.local.PreferencesManager(getApplication())
            prefs.setConvertAllImages(newValue)
        }
        // 切换开关时，重置选中状态
        if (newValue) {
            // 开：恢复默认（非重名全选）
            selectAllNonDuplicate()
        } else {
            // 关：清空选中，改为单选模式
            _selectedFiles.value = emptySet()
        }
    }

    /**
     * 【切换文件选中状态（支持单选/多选模式）】
     *
     * 根据当前是"多选"还是"单选"模式执行不同的选中逻辑。
     *
     * 调用位置：PdfConversionScreen.kt → 文件列表中每个文件的 Checkbox/RadioButton 的 onClick
     *
     * @param fileName 要切换选中状态的 ZIP 文件名
     */
    fun toggleFileSelection(fileName: String) {
        if (_convertAllImages.value) {
            // 多选模式：切换该文件的选中状态（如果已选中则移除，未选中则添加）
            val current = _selectedFiles.value.toMutableSet()
            if (current.contains(fileName)) {
                current.remove(fileName)
            } else {
                current.add(fileName)
            }
            _selectedFiles.value = current
        } else {
            // 单选模式：无论之前选了什么，现在只选中当前这一个
            _selectedFiles.value = setOf(fileName)
        }
    }

    /**
     * 【只选中非重名文件】（内部辅助方法）
     *
     * 从 ZIP 文件列表中筛选出所有不重名的文件并选中。
     * 在 scanZipFiles() 和 toggleConvertAllImages() 中调用。
     */
    private fun selectAllNonDuplicate() {
        _selectedFiles.value = _zipFiles.value
            .filterNot { it.isDuplicate }
            .map { it.name }
            .toSet()
    }

    /**
     * 【开始单个 ZIP 的转换流程（单选模式）】
     *
     * 当"默认转换所有图片"开关关闭时，用户选择一个 ZIP 文件后点击"开始转换"触发此方法。
     * 流程：
     *   1. 在 IO 线程解压 ZIP 到临时目录
     *   2. 提取所有图片的信息（路径、文件名、索引）
     *   3. 更新 _extractedImages 状态，触发 UI 导航到 PageSelectionScreen
     *
     * 调用位置：PdfConversionScreen.kt → 底部"开始转换"按钮（convertAllImages=false 时）
     *
     * @param zipFileName 用户选中的 ZIP 文件名
     */
    fun startSingleConversion(zipFileName: String) {
        val zipInfo = _zipFiles.value.find { it.name == zipFileName } ?: return
        val context: Context = getApplication()

        viewModelScope.launch(Dispatchers.IO) {
            _isExtracting.value = true
            try {
                // 1. 使用 zipInfo.uri 直接打开 ZIP 文件（无需重新查找文件位置）
                val inputStream = context.contentResolver.openInputStream(zipInfo.uri)
                    ?: run {
                        Log.e("ConversionViewModel", "无法打开 ZIP 文件: ${zipInfo.name}")
                        return@launch
                    }

                // 2. 创建临时目录（路径：filesDir/zip_preview/文件名的哈希值）
                val tempDir = File(context.filesDir, "zip_preview/${zipInfo.name.hashCode()}")
                if (tempDir.exists()) tempDir.deleteRecursively()
                tempDir.mkdirs()

                // 3. 使用 ZipProcessor 解压 ZIP 到临时目录，获取图片信息列表
                val imageInfos = com.example.pdfmanager.data.local.ZipProcessor.extractZipToTempDir(inputStream, tempDir)
                inputStream.close()

                if (imageInfos.isEmpty()) {
                    Log.w("ConversionViewModel", "ZIP 中没有图片: ${zipInfo.name}")
                    return@launch
                }

                // 4. 更新状态，触发 UI 导航到 PageSelectionScreen
                _currentPreviewZipName.value = zipInfo.name
                _currentPreviewZipUri.value = zipInfo.uri   // 存储 URI 用于后续删除原始 ZIP
                _extractedImages.value = imageInfos
                // 默认全选所有图片
                _selectedPages.value = null  // null = 全选

                Log.i("ConversionViewModel",
                    "解压完成: ${zipInfo.name}, 共 ${imageInfos.size} 张图片")
            } catch (e: Exception) {
                Log.e("ConversionViewModel", "解压 ZIP 失败: ${zipInfo.name}", e)
            } finally {
                _isExtracting.value = false
            }
        }
    }

    /**
     * 【切换某页码的选中状态】
     *
     * 在 PageSelectionScreen 中，用户点击某张图片卡片时调用此方法。
     * 选中状态处理：
     * - 如果当前是"全选"状态（_selectedPages == null），先构建完整页码集合再从中移除
     * - 如果当前已有选中集合，则按需添加或移除该页码
     *
     * 调用位置：PageSelectionScreen.kt → PageSelectionCard 的 onClick
     *
     * @param page 要切换的页码（1-based 索引）
     */
    fun togglePageSelection(page: Int) {
        val current = _selectedPages.value?.toMutableSet() ?: run {
            // 如果当前是全选（null），先构建完整集合再移除
            val allPages = _extractedImages.value?.map { it.index }?.toSet() ?: emptySet()
            allPages.toMutableSet()
        }
        if (current.contains(page)) {
            current.remove(page)
        } else {
            current.add(page)
        }
        _selectedPages.value = current
    }

    /**
     * 【全选所有页】
     *
     * 将页码选择状态置为 null（null 语义 = 全选）。
     *
     * 调用位置：PageSelectionScreen.kt → "全选"按钮
     */
    fun selectAllPages() {
        _selectedPages.value = null  // null = 全选
    }

    /**
     * 【取消所有页的选中】
     *
     * 将页码选择状态置为空集合。
     *
     * 调用位置：PageSelectionScreen.kt → "取消全选"按钮（当前为全选状态时）
     */
    fun deselectAllPages() {
        _selectedPages.value = emptySet()
    }

    /**
     * 【确认页码选择，开始生成 PDF】
     *
     * 用户在 PageSelectionScreen 点击"开始转换"按钮时调用。
     * 此方法负责：
     *   1. 从临时目录中读取用户选中的图片
     *   2. 按选中页码顺序将图片缩放并写入 PDF 文档
     *   3. 将生成的 PDF 写入目标目录（库文件夹或自定义输出路径）
     *   4. 删除原始 ZIP 文件
    *    5. 将新 PDF 写入数据库并生成缩略图
     *   6. 更新转换结果状态触发 UI 反馈
     *
     * 注意：转换进度（_conversionProgress）会在此方法开始时设置，
     * 触发 PageSelectionScreen 中的 LaunchedEffect 执行 popBackStack() 返回。
     *
     * 调用位置：PageSelectionScreen.kt → 底部"开始转换"按钮
     */
    fun onPageSelectionConfirm() {
        Log.i("ConversionViewModel",
            "onPageSelectionConfirm() 被调用, _extractedImages=${_extractedImages.value?.size}, _currentPreviewZipName=${_currentPreviewZipName.value}")
        val imageInfos = _extractedImages.value ?: run {
            Log.e("ConversionViewModel", "onPageSelectionConfirm: _extractedImages is null, returning")
            return
        }
        val zipName = _currentPreviewZipName.value ?: return
        val zipUri = _currentPreviewZipUri.value   // 用于删除原始 ZIP 文件
        val context: Context = getApplication()

        // 先在主线程设置进度，确保 PageSelectionScreen 导航回去时进度已非空
        _conversionProgress.value = ConversionProgress(
            currentFileName = zipName,
            fileIndex = 1,
            totalFiles = 1
        )

        // 计算要转换的页码集合：null = 全选，否则使用选中的集合
        val pagesToConvert = _selectedPages.value ?: imageInfos.map { it.index }.toSet()

        // 在 IO 调度器上执行耗时操作
        viewModelScope.launch(Dispatchers.IO) {
            var pdfFileSize = 0L  // 存储生成的 PDF 文件大小
            try {
                // ── 确定输出目录 ──────────────────────────────────────
                val prefs = com.example.pdfmanager.data.local.PreferencesManager(context)
                val outputUri = prefs.getConversionOutputPath()?.let { Uri.parse(it) }
                val outputDir = if (outputUri != null) {
                    // 使用自定义输出路径
                    DocumentFile.fromTreeUri(context, outputUri)
                } else {
                    // 使用库根目录
                    val libraryUriStr = prefs.getLibraryUri() ?: return@launch
                    DocumentFile.fromTreeUri(context, Uri.parse(libraryUriStr))
                }

                // ── 创建输出 PDF 文件 ──────────────────────────────────
                val baseName = zipName.substringBeforeLast(".")
                val pdfName = "${baseName}.pdf"
                // 检查目标目录是否已存在同名文件
                val existingFile = outputDir?.findFile(pdfName)
                val finalPdfName = if (existingFile != null) {
                    "${baseName}_converted.pdf"  // 重名时添加后缀
                } else {
                    pdfName
                }
                val pdfDoc = outputDir?.createFile("application/pdf", finalPdfName) ?: return@launch
                // 在缓存目录创建临时 PDF 文件
                val pdfFile = File(context.cacheDir, finalPdfName)

                // ── 准备图片数据 ──────────────────────────────────────
                // 按索引排序，确保页码顺序正确
                val sortedInfos = imageInfos.sortedBy { it.index }
                val selectedInfos = if (_selectedPages.value == null) {
                    sortedInfos  // 全选
                } else {
                    sortedInfos.filter { _selectedPages.value?.contains(it.index) == true }
                }

                if (selectedInfos.isEmpty()) {
                    Log.w("ConversionViewModel", "没有有效图片可转换")
                    return@launch
                }

                // 第一遍：只读取尺寸，找出最大宽度并计算降采样率
                val decodeOpts = BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
                var maxWidth = 0
                for (info in selectedInfos) {
                    BitmapFactory.decodeFile(info.filePath, decodeOpts)
                    if (decodeOpts.outWidth > maxWidth) maxWidth = decodeOpts.outWidth
                }

                // 计算降采样率：限制 PDF 内图片最大宽度 1200px
                val maxTargetWidth = 1200
                // inSampleSize 必须是 2 的幂，用 highestOneBit 保证
                val rawSample = if (maxWidth > maxTargetWidth) maxWidth / maxTargetWidth else 1
                val sampleSize = Integer.highestOneBit(rawSample).coerceAtLeast(1)
                // 解码选项：降采样 + RGB_565（每像素 2 字节，文件减半）
                val renderOpts = BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                    inPreferredConfig = Bitmap.Config.RGB_565
                }

                // 第二遍：逐页解码、缩放为目标宽度、写入 PDF，立即回收
                val pdfDocument = PdfDocument()
                selectedInfos.forEachIndexed { idx, info ->
                    val bmp = BitmapFactory.decodeFile(info.filePath, renderOpts)
                    if (bmp == null) {
                        Log.w("ConversionViewModel", "解码失败: ${info.filePath}")
                        return@forEachIndexed
                    }

                    // 统一缩放到目标宽度
                    val scale = maxTargetWidth.toFloat() / bmp.width
                    val scaledW = maxTargetWidth
                    val scaledH = (bmp.height * scale).toInt()
                    val scaledBmp = if (scale != 1.0f) {
                        Bitmap.createScaledBitmap(bmp, scaledW, scaledH, true)
                    } else bmp

                    // 创建 PDF 页面并绘制图片
                    val pageInfo = PdfDocument.PageInfo.Builder(scaledW, scaledH, idx + 1).create()
                    val page = pdfDocument.startPage(pageInfo)
                    page.canvas.drawBitmap(scaledBmp, 0f, 0f, null)
                    pdfDocument.finishPage(page)

                    // 立即回收，释放内存
                    if (scaledBmp != bmp) scaledBmp.recycle()
                    bmp.recycle()

                    // 在主线程更新进度（当前页数/总页数）
                    val current = idx + 1
                    val total = selectedInfos.size
                    withContext(Dispatchers.Main) {
                        _conversionProgress.value = ConversionProgress(
                            currentFileName = zipName,
                            currentPage = current,
                            totalPages = total,
                            fileIndex = 1,
                            totalFiles = 1
                        )
                    }
                }

                // ── 写入 PDF 文件 ──────────────────────────────────────
                FileOutputStream(pdfFile).use { fos ->
                    pdfDocument.writeTo(fos)
                }
                pdfDocument.close()

                // 将生成的 PDF 从缓存复制到输出 URI
                val outputStream = context.contentResolver.openOutputStream(pdfDoc.uri) ?: return@launch
                val inputStream = FileInputStream(pdfFile)
                inputStream.use { ins ->
                    outputStream.use { outs -> ins.copyTo(outs) }
                }
                pdfFileSize = pdfFile.length()  // 获取文件大小（在删除前）
                pdfFile.delete()                // 删除缓存文件

                // ── 删除原始 ZIP 文件（使用存储的 URI） ────────────────
                if (zipUri != null) {
                    try {
                        val zipDoc = DocumentFile.fromSingleUri(context, zipUri)
                        val deleted = zipDoc?.delete() ?: false
                        if (deleted) {
                            Log.i("ConversionViewModel", "已删除原始 ZIP: ${zipName}")
                        } else {
                            Log.w("ConversionViewModel", "无法删除原始 ZIP: ${zipName}")
                        }
                    } catch (e: Exception) {
                        Log.w("ConversionViewModel", "删除原始 ZIP 失败: ${zipName}", e)
                    }
                }

                // ── 立即写入数据库 + 生成缩略图 ──────────────────────
                val pdfOutputUri = pdfDoc.uri
                // 使用 URI 的 UUID 作为 PDF 的唯一 ID
                val id = UUID.nameUUIDFromBytes(pdfOutputUri.toString().toByteArray()).toString()
                val displayName = finalPdfName
                val name = com.example.pdfmanager.data.model.PdfFile.extractNameFromDisplayName(displayName)

                // 生成缩略图
                Log.i("ConversionViewModel", "开始为 $displayName 生成缩略图...")
                val tp = com.example.pdfmanager.data.local.ThumbnailGenerator.getThumbnailPath(context, pdfOutputUri)
                if (tp == null) {
                    val generated = com.example.pdfmanager.data.local.ThumbnailGenerator.generate(context, pdfOutputUri)
                    if (generated) {
                        Log.i("ConversionViewModel", "已生成缩略图: $displayName")
                    } else {
                        Log.e("ConversionViewModel", "缩略图生成失败: $displayName")
                    }
                } else {
                    Log.d("ConversionViewModel", "缩略图已存在: $displayName")
                }

                val finalTp = com.example.pdfmanager.data.local.ThumbnailGenerator.getThumbnailPath(context, pdfOutputUri)

                // 构造 PdfFile 对象并写入数据库
                val newPdf = com.example.pdfmanager.data.model.PdfFile(
                    id = id,
                    name = name,
                    displayName = displayName,
                    uri = pdfOutputUri,
                    size = pdfFileSize,
                    lastModified = System.currentTimeMillis(),
                    thumbnailPath = finalTp,
                    tags = emptyList(),
                    notes = "",
                    isFavorite = false,
                    thumbnailGenerated = 1  // 标记缩略图已生成，避免后台扫描时重新生成
                )
                Log.i("ConversionViewModel", "准备调用 addFile(): $displayName")
                AppContainer.pdfRepository.addFile(newPdf)
                Log.i("ConversionViewModel", "已写入数据库: $displayName")

                // 在主线程清除进度并设置结果
                withContext(Dispatchers.Main) {
                    _conversionProgress.value = null  // 先清除进度
                    _conversionResult.value = Pair(1, emptyList())  // 成功 1 个
                    // 从文件列表删除已转换的 ZIP
                    val remainingFiles = _zipFiles.value.filterNot { it.name == zipName }
                    _zipFiles.value = remainingFiles
                    _selectedFiles.value = _selectedFiles.value - zipName

                }
            } catch (e: Exception) {
                Log.e("ConversionViewModel", "页码选择后转换失败", e)
                withContext(Dispatchers.Main) {
                    _conversionProgress.value = null
                    _conversionResult.value = Pair(0, listOf(zipName))
                }
            } finally {
                // 注意：不在 finally 清 _conversionProgress，弹窗状态由 clearConversionResult() 管理
                // 也不在这里调用 clearPreview()，以避免 _extractedImages 被过早置 null
                // 导致 PdfConversionScreen 额外重组。clearPreview() 在 clearConversionResult() 中执行。
            }
        }
    }

    /**
     * 【清理预览临时目录和状态】
     *
     * 删除解压 ZIP 产生的临时目录，并将所有预览相关状态重置。
     *
     * 调用位置：
     * - clearConversionResult() 中调用（转换完成后清理）
     * - PageSelectionScreen 点击返回/取消时调用
     */
    fun clearPreview() {
        val app: Application = getApplication()
        // 删除 zip_preview 临时目录及其所有子目录
        val tempDir = File(app.filesDir, "zip_preview")
        if (tempDir.exists()) tempDir.deleteRecursively()
        // 重置所有预览状态
        _extractedImages.value = null
        _selectedPages.value = null
        _currentPreviewZipName.value = null
        _currentPreviewZipUri.value = null
    }

    // ═══════════════════════════════════════════════════════════════════
    // 八、其他辅助方法
    // ═══════════════════════════════════════════════════════════════════

    /**
     * 【获取监控文件夹显示名称】
     *
     * @return 监控文件夹的人类可读显示名称
     */
    suspend fun getMonitorFolderDisplayName(): String {
        return repository.getMonitorFolderDisplayName()
    }

    /**
     * 【获取输出位置显示名称】
     *
     * @return 输出路径的人类可读显示名称
     */
    suspend fun getOutputPathDisplayName(): String {
        return repository.getOutputPathDisplayName()
    }

    /**
     * 【获取库文件夹 URI】
     *
     * 从 PreferencesManager 读取库文件夹的 URI 字符串。
     *
     * @return 库文件夹的 URI 字符串，如果未设置则返回 null
     */
    suspend fun getLibraryUri(): String? {
        return com.example.pdfmanager.data.local.PreferencesManager(getApplication()).getLibraryUri()
    }

    companion object {
        /**
         * 【ViewModel 工厂】
         *
         * 用于创建 ConversionViewModel 实例，自动注入 Application 和 ConversionRepository 依赖。
         * 通过 AppContainer 获取全局依赖。
         */
        val Factory = object : ViewModelProvider.Factory {
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                val app = AppContainer.appContext as Application
                val repository = AppContainer.conversionRepository
                @Suppress("UNCHECKED_CAST")
                return ConversionViewModel(app, repository) as T
            }
        }
    }
}
