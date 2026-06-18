package com.example.pdfmanager.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.example.pdfmanager.data.model.SavedFilter
import com.example.pdfmanager.data.repository.AppContainer
import com.example.pdfmanager.ui.screen.MainScreen
import com.example.pdfmanager.ui.screen.detail.DetailScreen
import com.example.pdfmanager.ui.screen.favorites.FavoritesScreen
import com.example.pdfmanager.ui.screen.favorites.FavoriteFolderContentScreen
import com.example.pdfmanager.ui.screen.filter.FilterScreen
import com.example.pdfmanager.ui.screen.search.SearchScreen
import com.example.pdfmanager.ui.screen.conversion.PdfConversionScreen
import com.example.pdfmanager.ui.screen.conversion.PageSelectionScreen
import com.example.pdfmanager.ui.screen.reader.ReaderScreenV2
import com.example.pdfmanager.ui.screen.reader.ReaderSettingsScreen

/**
 * 应用导航图（Jetpack Navigation Compose）
 * 
 * 功能说明：
 * 1. 定义所有页面的路由和参数
 * 2. 管理页面间导航和返回栈
 * 3. 传递页面参数（如 fileId、folderId）
 * 
 * 路由结构：
 * ```
 * main                       ← 主页面（底部三 Tab，起始页）
 * ├── detail/{fileId}        ← 详情页（全屏，参数：文件 ID）
 * ├── search                 ← 搜索页（全屏二级）
 * ├── filter                 ← 筛选页（全屏二级，通过 AppContainer 传递结果）
 * ├── favorites              ← 收藏页（底部 Tab）
 * │   └── favorites/content/{folderId}  ← 收藏文件夹内容页（全屏二级）
 * ├── pdf_conversion         ← PDF 转换页（全屏二级）
 * ├── page_selection         ← 页码选择页（全屏二级）
 * ├── reader/{fileId}        ← 阅读器页（参数：文件 ID + 强制重新开始标志）
 * └── reader_settings        ← 阅读设置页（全屏二级）
 * ```
 * 
 * 导航流程：
 * - AppNavGraph 作为顶层导航容器
 * - MainScreen 内部使用底部 Tab 切换子页面
 * - 其他页面从 MainScreen 通过 NavController 跳转
 * 
 * 调用位置：
 * - MainActivity.onCreate() 中调用 setContent { AppNavGraph(...) }
 * 
 * @param navController 导航控制器（用于页面跳转和返回栈管理）
 * @param onRequestLibrary SAF 库文件夹选择回调
 */
@Composable
fun AppNavGraph(
    navController: NavHostController,
    onRequestLibrary: () -> Unit = {}
) {
    NavHost(
        navController = navController,
        startDestination = "main"  // 起始页：主页面（底部三 Tab）
    ) {
        // ── 主页面（起始页，底部导航显示） ──
        composable("main") {
            MainScreen(
                navController = navController,
                onRequestLibrary = onRequestLibrary
            )
        }

        // ── PDF 详情页（从文件列表点击进入） ──
        composable(
            route = "detail/{fileId}",
            arguments = listOf(navArgument("fileId") { type = NavType.StringType })
        ) { backStackEntry ->
            val fileId = backStackEntry.arguments?.getString("fileId") ?: return@composable
            DetailScreen(
                fileId = fileId,
                onBack = { navController.popBackStack() },
                navController = navController
            )
        }

        // ── 搜索页（全屏二级页面，底部导航隐藏） ──
        composable("search") {
            SearchScreen(
                onBack = { navController.popBackStack() },
                navController = navController
            )
        }

        // ── 筛选页（全屏二级页面，通过 AppContainer 传递筛选结果） ──
        composable("filter") {
            FilterScreen(
                onBack = { navController.popBackStack() },
                onConfirm = { savedFilter ->
                    // 将筛选结果暂存到 AppContainer，由 AllFilesViewModel 消费
                    AppContainer.setPendingFilterResult(savedFilter)
                    navController.popBackStack()
                }
            )
        }

        // ── 收藏页（底部导航 Tab） ──
        composable("favorites") {
            FavoritesScreen(
                navController = navController
            )
        }

        // ── 收藏文件夹内容页（全屏二级页面） ──
        composable(
            route = "favorites/content/{folderId}",
            arguments = listOf(navArgument("folderId") { type = NavType.StringType })
        ) { backStackEntry ->
            val folderId = backStackEntry.arguments?.getString("folderId") ?: return@composable
            FavoriteFolderContentScreen(
                folderId = folderId,
                navController = navController
            )
        }

        // ── PDF 转换页（全屏二级页面，底部导航隐藏） ──
        composable("pdf_conversion") {
            PdfConversionScreen(
                navController = navController
            )
        }

        // ── 页码选择页（全屏二级页面） ──
        composable("page_selection") {
            PageSelectionScreen(
                navController = navController
            )
        }

        // ── 数据库管理页（全屏二级页面） ──
        composable("database_manage") {
            com.example.pdfmanager.ui.screen.database.DatabaseManageScreen(
                navController = navController
            )
        }

        // ── 阅读器页（新架构：连续画布模式） ──
        // 参数：fileId（文件 ID，必填），forceStart（强制从第1页开始，可选，默认false）
        composable(
            route = "reader/{fileId}?forceStart={forceStart}",
            arguments = listOf(
                navArgument("fileId") { type = NavType.StringType },
                navArgument("forceStart") {
                    type = NavType.BoolType
                    defaultValue = false
                }
            )
        ) { backStackEntry ->
            val fileId = backStackEntry.arguments?.getString("fileId") ?: return@composable
            val forceStart = backStackEntry.arguments?.getBoolean("forceStart") ?: false
            ReaderScreenV2(
                fileId = fileId,
                forceStart = forceStart,
                onBack = { navController.popBackStack() },
                navController = navController
            )
        }

        // ── 阅读设置页（全屏二级页面） ──
        composable("reader_settings") {
            ReaderSettingsScreen(
                navController = navController
            )
        }
    }
}
