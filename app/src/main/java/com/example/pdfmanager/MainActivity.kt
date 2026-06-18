package com.example.pdfmanager

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import com.example.pdfmanager.ui.theme.MyPDFManagerTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.rememberNavController
import coil.Coil
import coil.ImageLoader
import coil.compose.LocalImageLoader
import com.example.pdfmanager.data.local.PreferencesManager
import com.example.pdfmanager.data.repository.AppContainer
import com.example.pdfmanager.ui.component.PdfFetcher
import com.example.pdfmanager.ui.navigation.AppNavGraph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 主 Activity —— 应用程序的单一入口点 Activity。
 *
 * 职责说明：
 * ---------------------------------------------------------------------------
 * 1. 应用初始化：在首次 [onCreate] 中调用 [AppContainer.init] 完成全局依赖容器
 *    的初始化（包括数据仓库、Room 数据库、SharedPreferences 等）。
 * 2. Coil 图片加载框架集成：构建自定义 [ImageLoader]，注册 [PdfFetcher.Factory]
 *    使 Coil 能够解码 PDF 文件中的页面缩略图，并通过 [CompositionLocal] 注入
 *    Compose 组件树。
 * 3. 主题管理：监听 [PreferencesManager] 中用户偏好的主题模式（跟随系统/亮色/
 *    暗色），动态切换 Material3 主题。
 * 4. 导航架构：使用 Jetpack Navigation Compose 构建 [AppNavGraph] 单 Activity
 *    导航图，通过 [NavController] 管理页面跳转。
 * 5. SAF（Storage Access Framework）权限管理：通过 [openDocumentTreeLauncher]
 *    让用户选择 PDF 库根目录，持久化读取/写入 URI 权限，并在切换后重建
 *    Activity 以完全重置 ViewModel 状态。
 * 6. 库目录结构维护：当用户选择新的库文件夹后，自动在目录下创建
 *    database/、database/thumbnail/、database/zip/、database/share/ 子目录
 *    用于存放元数据、缩略图缓存、压缩包和分享文件。
 *
 * 生命周期概述：
 * - [onCreate]：全局初始化、注册 Coil 组件、设置 Compose 内容。
 * - [saveAndRecreate]：切换库后保存 URI、切换数据库、重建界面。
 * - [createRequiredFolders]：在 IO 线程中自动创建所需的子文件夹结构。
 *
 * @see ComponentActivity 继承自 Jetpack Activity 基类，支持 Compose 集成。
 * @see AppContainer 全局依赖注入容器，持有数据仓库、数据库和偏好管理器。
 * @see AppNavGraph 应用主导航图，定义所有页面的路由与跳转逻辑。
 * @see PdfFetcher 自定义 Coil Fetcher，负责从 PDF 文件提取页面图像。
 */
class MainActivity : ComponentActivity() {

    // =========================================================================
    // 属性：SAF（Storage Access Framework）文档树启动器
    // =========================================================================
    /**
     * SAF 文档树选择启动器 —— 用于触发系统文件夹选择器并处理选择结果。
     *
     * 使用场景：
     * - 用户在设置页面点击"选择库文件夹"按钮时，通过 [AppNavGraph] 回调
     *   [onRequestLibrary] 触发 [openDocumentTreeLauncher.launch(null)]。
     *
     * 回调流程（[uri] 为用户选中的文件夹 URI）：
     * 1. [contentResolver.takePersistableUriPermission] 向系统请求持久化
     *    的读写权限，使得应用在设备重启后仍可访问该目录。
     * 2. [saveAndRecreate] 保存新 URI 到 Preferences → 切换 Room 数据库
     *    → 创建必要子目录 → 重建 Activity。
     *
     * @see ActivityResultContracts.OpenDocumentTree Android 系统提供的
     *      文件夹选择契约，返回用户选择的文件夹 tree URI。
     * @see Intent.FLAG_GRANT_READ_URI_PERMISSION 读取权限标志
     * @see Intent.FLAG_GRANT_WRITE_URI_PERMISSION 写入权限标志
     */
    private val openDocumentTreeLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            // 向系统申请持久化 URI 权限，使权限跨越设备重启仍有效
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            // 在协程中执行保存和切换操作，避免阻塞主线程
            lifecycleScope.launch {
                saveAndRecreate(uri)
            }
        }
    }

    // =========================================================================
    // 生命周期方法：onCreate
    // =========================================================================
    /**
     * Activity 创建时的入口方法 —— 仅在 Activity 首次创建时调用一次。
     *
     * 执行时序与详细步骤：
     * 1. [super.onCreate]：调用父类实现，恢复已保存的实例状态。
     * 2. [AppContainer.init(this)]：初始化全局依赖容器。
     *    - 创建 [com.example.pdfmanager.data.local.PreferencesManager]。
     *    - 从 SharedPreferences 读取已保存的库 URI。
     *    - 初始化 Room 数据库与 DAO。
     *    - 创建 [com.example.pdfmanager.data.repository.PdfRepository]。
     *    - 初始化 [com.example.pdfmanager.ui.viewmodel.LibraryViewModel] 工厂。
     *    - 设置文件观察器 [com.example.pdfmanager.data.local.FileObserverHelper]。
     *    此方法仅首次执行一次；若因切库导致 Activity 销毁重建，[savedInstanceState]
     *    为空但全局容器已存在，内部会跳过二次初始化。
     * 3. 构建 Coil 自定义 [ImageLoader] 并注册 [PdfFetcher.Factory]。
     *    - [PdfFetcher] 负责从 PDF 文件的第一页提取 Bitmap 作为封面缩略图。
     *    - 通过 [Coil.setImageLoader] 设为全局加载器，供所有 AsyncImage 调用。
     * 4. [setContent]：设置 Compose UI 内容。
     *    - 从 PreferencesManager 收集主题模式 Flow → 包裹 [MyPDFManagerTheme]。
     *    - 通过 [CompositionLocalProvider] 将 Coil 的 [LocalImageLoader] 注入
     *      Compose 树，使子 Composable 能通过 [LocalImageLoader.current] 获取。
     *    - 创建 [NavController] 并传递给 [AppNavGraph]。
     *    - [AppNavGraph] 定义所有页面的路由表，入参 [onRequestLibrary] 回调
     *      供设置页面触发文件夹选择器。
     *
     * @param savedInstanceState 保存的实例状态 Bundle。
     *        若 Activity 被系统回收后重建，此参数包含之前的保存状态；
     *        首次创建时为 null。
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 初始化全局依赖容器（内部保证只执行一次）
        AppContainer.init(this)

        // 构建 Coil ImageLoader 并注册 PdfFetcher，
        // 使 Coil 能够从 PDF 文件解码页面缩略图
        val imageLoader = ImageLoader.Builder(this)
            .components {
                add(PdfFetcher.Factory(applicationContext))
            }
            .build()
        Coil.setImageLoader(imageLoader)

        // 设置 Compose UI 内容
        setContent {
            // 从 PreferencesManager 收集用户偏好的主题模式（跟随系统/亮色/暗色）
            val themeMode by AppContainer.preferencesManager
                .getThemeModeFlow()
                .collectAsState(initial = "follow_system")

            // 应用动态主题
            MyPDFManagerTheme(themeMode = themeMode) {
                // 获取已注册的全局 ImageLoader 实例
                val imageLoader = Coil.imageLoader(applicationContext)
                Surface(
                    modifier = Modifier.fillMaxSize()
                ) {
                    // 将 ImageLoader 通过 CompositionLocal 注入 Compose 组件树
                    androidx.compose.runtime.CompositionLocalProvider(
                        LocalImageLoader provides imageLoader
                    ) {
                        // 创建导航控制器
                        val navController = rememberNavController()

                        // 设置主导航图
                        AppNavGraph(
                            navController = navController,
                            onRequestLibrary = {
                                // 当用户在设置页面点击"选择库文件夹"时触发
                                openDocumentTreeLauncher.launch(null)
                            }
                        )
                    }
                }
            }
        }
    }

    // =========================================================================
    // 私有方法：saveAndRecreate
    // =========================================================================
    /**
     * 保存新的库 URI，切换数据库，并在完成后重建 Activity。
     *
     * 调用时机：
     * - 用户在 SAF 文件夹选择器中选定新库目录后，由 [openDocumentTreeLauncher]
     *   的回调中调用。
     *
     * 执行流程：
     * 1. 将新的 URI 字符串存入 [PreferencesManager]，作为持久化的库路径。
     * 2. 调用 [AppContainer.switchLibrary] 关闭旧 Room 数据库实例，释放连接池，
     *    然后使用新 URI 初始化新的数据库实例。
     * 3. 在 IO 线程中调用 [createRequiredFolders] 创建必要的子目录结构。
     * 4. 通过 [finish] + [startActivity] 重建 Activity：
     *    - 不直接使用 [recreate] 的原因：确保 ViewModel 完全销毁并重新创建，
     *      避免旧 ViewModel 持有旧数据引用导致状态不一致。
     *
     * @param newUri 用户选中的新库文件夹的 tree URI，由 SAF 返回。
     */
    private fun saveAndRecreate(newUri: Uri) {
        lifecycleScope.launch {
            // 将新库 URI 持久化到 SharedPreferences
            AppContainer.preferencesManager.saveLibraryUri(newUri.toString())
            // 关闭旧数据库，使用新 URI 初始化新数据库
            AppContainer.switchLibrary(this@MainActivity, newUri.toString())

            // 切换到 IO 线程创建必要的子文件夹
            withContext(Dispatchers.IO) {
                createRequiredFolders(newUri)
            }

            // 使用 finish + startActivity 替代 recreate()，
            // 确保 ViewModel 被完全销毁并重建，避免数据残留
            val intent = intent
            finish()
            startActivity(intent)
        }
    }

    // =========================================================================
    // 私有方法：createRequiredFolders
    // =========================================================================
    /**
     * 在指定的库根目录下自动创建应用所需的子文件夹结构。
     *
     * 需要创建的目录结构（相对于库根目录）：
     * ```
     * [Library Root]/
     * └── database/
     *     ├── thumbnail/     ← PDF 缩略图缓存目录
     *     ├── zip/           ← 压缩包临时存储目录
     *     └── share/         ← 分享文件临时存储目录
     * ```
     *
     * 设计说明：
     * - 使用 [DocumentFile] API 而非 [java.io.File] 操作，因为库目录是 SAF
     *   tree URI，不能用常规文件路径访问。
     * - [findFile] 检查目录是否存在，不存在则 [createDirectory] 新建。
     *   [?:] Elvis 操作符实现了"存在则复用，不存在则创建"的逻辑。
     * - 所有操作在 [Dispatchers.IO] 上执行，不阻塞主线程。
     *
     * @param libraryUri 库根目录的 tree URI。
     * @return Unit（无返回值）。
     */
    private suspend fun createRequiredFolders(libraryUri: Uri) = withContext(Dispatchers.IO) {
        val libraryDoc = DocumentFile.fromTreeUri(this@MainActivity, libraryUri)

        // 查找或创建 database/ 目录
        val databaseDoc = libraryDoc?.findFile("database") ?: libraryDoc?.createDirectory("database")

        // 在 database/ 下查找或创建 zip/ 子目录
        databaseDoc?.findFile("zip") ?: databaseDoc?.createDirectory("zip")

        // 在 database/ 下查找或创建 share/ 子目录
        databaseDoc?.findFile("share") ?: databaseDoc?.createDirectory("share")
    }
}
