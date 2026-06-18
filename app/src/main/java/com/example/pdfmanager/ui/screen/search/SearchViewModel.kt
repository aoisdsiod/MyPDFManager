package com.example.pdfmanager.ui.screen.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.pdfmanager.data.model.PdfFile
import com.example.pdfmanager.data.repository.AppContainer
import com.example.pdfmanager.data.repository.SearchIndexRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 搜索页 ViewModel
 *
 * ## 功能说明
 * 1. 持有搜索关键词（[query]）和搜索结果（[results]）的状态
 * 2. 提供搜索关键词变更方法 [onQueryChange]，内置 300ms 防抖机制
 *    - 防抖目的：避免用户每输入一个字符就触发一次搜索，减少计算开销
 *    - 防抖实现：每次输入取消前一次搜索 Job，延迟 300ms 后执行实际搜索
 * 3. 提供清除搜索方法 [clearQuery]，清空关键词和结果
 * 4. 通过 [SearchIndexRepository] 执行实际的搜索逻辑（纯数字文件名索引匹配）
 * 5. 使用 StateFlow 暴露状态，支持 Compose 的 [collectAsStateWithLifecycle] 观察
 *
 * ## 状态说明
 * - [_query]：内部可变搜索关键词（MutableStateFlow<String>）
 * - [query]：对外只读搜索关键词（StateFlow<String>）
 * - [_results]：内部可变搜索结果列表（MutableStateFlow<List<PdfFile>>）
 * - [results]：对外只读搜索结果列表（StateFlow<List<PdfFile>>）
 * - [searchJob]：当前搜索协程 Job，用于取消上一个防抖任务
 *
 * ## 调用位置
 * - [SearchScreen.kt]（第61行）：通过 `viewModel(factory = SearchViewModel.Factory())` 创建
 * - [SearchScreen.kt]（第87行）：搜索输入框 `onValueChange` 时调用 `viewModel.onQueryChange(it)`
 *
 * ## 使用场景
 * - 用户在搜索页面输入文件名关键词时，ViewModel 负责防抖和异步搜索
 * - [SearchScreen] 通过 [query] 和 [results] 两个 StateFlow 驱动 UI 渲染
 *
 * @property searchIndexRepository 搜索索引仓库，负责实际的文件名匹配查询
 *   - 默认值：`AppContainer.searchIndexRepository`（全局单例）
 *   - 依赖关系：SearchIndexRepository → SearchEngine（内存倒排索引）
 */
class SearchViewModel(
    private val searchIndexRepository: SearchIndexRepository = AppContainer.searchIndexRepository
) : ViewModel() {

    // ── 搜索关键词状态 ──
    // _query：内部可变状态，用于接收用户输入并触发搜索
    private val _query = MutableStateFlow("")
    /**
     * 当前搜索关键词
     *
     * 对外暴露为只读 [StateFlow]，由 [SearchScreen] 通过 [collectAsStateWithLifecycle] 收集
     * 值通过 [onQueryChange] 方法更新
     *
     * @return StateFlow<String> 当前搜索框的文本内容
     */
    val query: StateFlow<String> = _query.asStateFlow()

    // ── 搜索结果状态 ──
    // _results：内部可变状态，存储搜索匹配到的文件列表
    private val _results = MutableStateFlow<List<PdfFile>>(emptyList())
    /**
     * 当前搜索结果列表
     *
     * 对外暴露为只读 [StateFlow]，由 [SearchScreen] 通过 [collectAsStateWithLifecycle] 收集
     * 当搜索关键词为空时返回空列表；搜素完成后返回匹配的 [PdfFile] 列表
     *
     * @return StateFlow<List<PdfFile>> 当前搜索结果（可能为空列表）
     */
    val results: StateFlow<List<PdfFile>> = _results.asStateFlow()

    // ── 防抖任务 Job ──
    // 保存当前正在等待的搜索协程，用于取消上一个防抖任务
    // 生命周期与 ViewModel 绑定（viewModelScope），ViewModel 销毁时自动取消
    private var searchJob: Job? = null

    /**
     * 更新搜索关键词并触发防抖搜索
     *
     * ## 功能说明
     * 1. 实时更新 [_query] 状态，驱动搜索输入框 UI 更新
     * 2. 执行 300ms 防抖逻辑：
     *    - 用户连续输入时，每次调用都会取消前一次搜索 Job
     *    - 只有当用户停止输入 300ms 后，才真正执行搜索
     * 3. 搜索关键词为空时直接清空结果列表，不触发搜索
     * 4. 实际搜索委托给 [SearchIndexRepository.search] 方法
     *
     * ## 防抖流程
     * ```
     * 用户输入 "1"
     *   └─ onQueryChange("1")
     *       ├─ _query.value = "1"
     *       ├─ 取消前一个 searchJob（null）
     *       ├─ 关键词非空 → launch 新协程
     *       └─ delay(300ms) ← 等待中
     *
     * 用户输入 "12"（300ms 内）
     *   └─ onQueryChange("12")
     *       ├─ _query.value = "12"
     *       ├─ 取消前一个 searchJob（"1" 的协程被取消）
     *       ├─ 关键词非空 → launch 新协程
     *       ├─ delay(300ms) ← 等待中
     *       └─ 300ms 后 → searchIndexRepository.search("12") → 更新 _results
     * ```
     *
     * ## 调用位置
     * - [SearchScreen.kt]（第87行）：搜索输入框 [OutlinedTextField] 的 onValueChange 回调
     *
     * ## 使用场景
     * - 用户在搜索输入框中每输入一个字符，该方法都会触发一次
     * - 最终只在用户停止输入 300ms 后执行一次实际搜索
     *
     * @param newQuery 用户输入的搜索关键词（纯字符串，搜索逻辑在 SearchEngine 中过滤数字）
     */
    fun onQueryChange(newQuery: String) {
        // 1. 实时更新搜索关键词状态（驱动输入框 UI 更新）
        _query.value = newQuery

        // 2. 取消上一次防抖搜索任务（防止并发搜索）
        searchJob?.cancel()

        // 3. 关键词为空 → 直接清空结果，不触发搜索
        if (newQuery.isEmpty()) {
            _results.value = emptyList()
            return
        }

        // 4. 防抖 300ms 后执行实际搜索
        searchJob = viewModelScope.launch {
            delay(300) // 300ms 防抖延迟
            val result = searchIndexRepository.search(newQuery) // 委托 SearchEngine 执行搜索
            _results.value = result // 更新搜索结果状态（驱动 UI 列表更新）
        }
    }

    /**
     * 清除搜索状态
     *
     * ## 功能说明
     * 1. 清空搜索关键词（[_query] 置为空字符串）
     * 2. 清空搜索结果（[_results] 置为空列表）
     * 3. 用于搜索重置或页面退出时的状态清理
     *
     * ## 调用位置
     * - 当前代码库中暂无显式调用（作为公开 API 备用于未来扩展）
     *
     * ## 使用场景
     * - 用户点击搜索框的清除按钮时调用（当前 UI 暂无此按钮）
     * - 搜索页面退出时清理状态
     * - ViewModel 重置时使用
     */
    fun clearQuery() {
        _query.value = ""
        _results.value = emptyList()
    }

    /**
     * [SearchViewModel] 的工厂类
     *
     * ## 功能说明
     * 实现 [ViewModelProvider.Factory] 接口，用于创建 [SearchViewModel] 实例
     * 通过 [AppContainer.searchIndexRepository] 注入依赖
     *
     * ## 调用位置
     * - [SearchScreen.kt]（第61行）：`viewModel(factory = SearchViewModel.Factory())`
     *
     * ## 使用场景
     * - Compose 中通过 `viewModel()` 委托创建 ViewModel 时，提供自定义工厂以注入依赖
     *
     * @suppress UNCHECKED_CAST ViewModelProvider.Factory 的泛型擦除警告抑制
     */
    class Factory : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            // 从 AppContainer 获取 searchIndexRepository 单例并传入构造函数
            return SearchViewModel(AppContainer.searchIndexRepository) as T
        }
    }
}
