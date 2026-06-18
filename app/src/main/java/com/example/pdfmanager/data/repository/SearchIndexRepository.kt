package com.example.pdfmanager.data.repository

import com.example.pdfmanager.data.local.SearchEngine
import com.example.pdfmanager.data.model.PdfFile

/**
 * ## 搜索索引仓库
 *
 * ─── 功能职责 ─────────────────────────────────────────────────
 * 封装 [SearchEngine]，为上层 ViewModel 提供搜索索引的构建、更新、移除和查询能力。
 * SearchEngine 内部维护基于 T9 通讯录式数字键盘映射（2→ABC, 3→DEF ...）的倒排索引，
 * 将 3~7 位纯数字文件名映射到拼音/声母/笔画等维度，实现"拨号盘式"快速检索。
 *
 * ─── 数据流 ───────────────────────────────────────────────────
 * ```
 * [SearchViewModel / AllFilesViewModel]
 *         │
 *         ▼
 *   SearchIndexRepository  ←── 委托调用 ──→  SearchEngine
 *         │                                  （内存索引，非持久化）
 *         ▼
 *   List<PdfFile>  (匹配结果)
 * ```
 *
 * ─── 调用位置 ─────────────────────────────────────────────────
 * 在 [AppContainer]（应用级依赖注入容器）中作为单例初始化。
 * - AllFilesViewModel：文件扫描完成后调用 [buildIndex] 全量构建索引。
 * - SearchViewModel：调用 [search] 执行查询，调用 [isSearchableName] 校验输入合法性。
 * - 文件编辑/删除操作的上层回调中调用 [update] / [remove] 保持索引同步。
 *
 * ─── 生命周期 ─────────────────────────────────────────────────
 * 索引完全存在于内存中，不持久化到数据库。
 * 应用重启或索引重建后，需要重新调用 [buildIndex] 构建。
 *
 * @see SearchEngine 底层搜索引擎，持有实际索引数据结构
 * @see com.example.pdfmanager.ui.viewmodel.SearchViewModel 搜索功能的上层消费者
 * @see com.example.pdfmanager.ui.viewmodel.AllFilesViewModel 文件列表管理，触发索引构建
 */
class SearchIndexRepository {

    /**
     * 底层搜索引擎实例。
     * 持有倒排索引字典、拼音转换表等核心数据结构。
     * 包级私有（private），外部不可直接访问。
     */
    private val searchEngine = SearchEngine()

    /**
     * ## 全量构建搜索索引
     *
     * 清空旧索引，将传入的所有 PDF 文件重新编入索引。
     * 适合在应用启动、文件列表初次扫描完成或"重建索引"按钮触发时调用。
     *
     * ─── 调用场景 ──────────────────────────────────────────────
     * - AllFilesViewModel 在 [androidx.lifecycle.viewModelScope] 中异步扫描完文件后立即调用。
     * - 用户通过设置页面触发"重建索引"功能时。
     *
     * ─── 性能说明 ──────────────────────────────────────────────
     * 全量构建 O(n) 时间复杂度，n = 文件总数。
     * 应在后台协程（[kotlinx.coroutines.Dispatchers.Default]）中调用，
     * 避免阻塞主线程。
     *
     * @param files 所有 PDF 文件列表。应传入当前用户文件树下的完整文件集合，
     *              包含文件 ID、文件名、路径等元信息。
     *              传入空列表等同于清空索引。
     * @see SearchEngine.buildIndex 委托给引擎内部的索引构建逻辑
     */
    fun buildIndex(files: List<PdfFile>) {
        searchEngine.buildIndex(files)
    }

    /**
     * ## 增量更新单个文件的索引
     *
     * 当某个 PDF 文件的标签、备注或文件名发生变更时，
     * 更新索引中该文件对应的条目，无需重建全量索引。
     *
     * ─── 调用场景 ──────────────────────────────────────────────
     * - 用户编辑文件标签（Tag）后保存时，由上层 ViewModel 触发。
     * - 用户修改文件备注（Note）后保存时触发。
     * - 文件重命名后触发（尤其涉及文件名的拼音变化）。
     * - 文件从外部导入、元信息更新后触发。
     *
     * ─── 注意事项 ──────────────────────────────────────────────
     * 如果文件不在现有索引中（例如索引尚未构建），此方法不会报错，
     * 但也不会有实际效果。建议先确保索引已通过 [buildIndex] 构建。
     *
     * @param file 更新后的 [PdfFile] 对象，应包含最新的 ID、文件名和元数据。
     *             传入的文件 ID（file.id）用于定位索引中的旧记录并将之替换。
     * @see SearchEngine.update 委托给引擎内部的单条更新逻辑
     */
    fun update(file: PdfFile) {
        searchEngine.update(file)
    }

    /**
     * ## 从索引中移除指定文件
     *
     * 当文件被物理删除或移动到不可搜索的位置时调用，
     * 从索引中清除该文件的条目，避免搜索结果中出现已不存在的文件。
     *
     * ─── 调用场景 ──────────────────────────────────────────────
     * - 用户删除单个 PDF 文件后，由文件操作 ViewModel 触发。
     * - 用户批量删除文件时，逐条调用此方法。
     * - 文件被移出用户库（如取消 SAF 权限）时触发。
     *
     * ─── 容错说明 ──────────────────────────────────────────────
     * 如果指定的 fileId 不在索引中（例如重复调用或未曾插入），
     * 引擎内部应安全处理，不抛出异常。
     *
     * @param fileId 要移除的文件 ID（[PdfFile.id]），必须是非空字符串。
     *               匹配引擎内部以文件 ID 为 key 的索引条目。
     * @see SearchEngine.remove 委托给引擎内部的单条移除逻辑
     */
    fun remove(fileId: String) {
        searchEngine.remove(fileId)
    }

    /**
     * ## 搜索文件
     *
     * 根据用户输入的关键词，在索引中查找匹配的 PDF 文件。
     * 引擎内部支持拼音首字母、全拼、数字映射等多种匹配方式。
     *
     * ─── 调用场景 ──────────────────────────────────────────────
     * - SearchViewModel 在用户每次输入搜索框文本时调用（防抖 debounce 后）。
     * - 配合 [isSearchableName] 先校验输入合法性，再执行搜索。
     * - 结果直接展示在搜索列表 UI 中。
     *
     * ─── 搜索逻辑 ──────────────────────────────────────────────
     * 1. 将 query 字符串转换为 T9 键盘数字序列。
     * 2. 在倒排索引中查找匹配的文件 ID 集合。
     * 3. 返回匹配的 [PdfFile] 列表，按相关性降序排列。
     *
     * @param query 搜索关键词。应用层约束为 3~7 位纯数字字符串，
     *              对应 T9 键盘输入的数字序列。
     *              空字符串或空格应返回空列表。
     * @return 匹配的文件列表 [List<PdfFile>]。若无匹配返回空列表（非 null）。
     * @see SearchEngine.search 委托给引擎内部的搜索逻辑
     */
    fun search(query: String): List<PdfFile> {
        return searchEngine.search(query)
    }

    /**
     * ## 判断文件名的搜索合法性
     *
     * 检查给定的文件名是否为 3~7 位纯数字格式，
     * 这是当前搜索系统支持的有效输入格式。
     *
     * ─── 调用场景 ──────────────────────────────────────────────
     * - SearchViewModel 在用户输入时实时校验，决定是否启用搜索按钮或显示提示。
     * - 搜索输入框的文本变化监听器中，用于控制搜索建议的显示/隐藏。
     * - 用户点击搜索按键前的前置校验。
     *
     * ─── 校验规则 ──────────────────────────────────────────────
     * - 只包含数字字符（0-9）。
     * - 长度在 3 到 7 位之间（含边界）。
     * - 不含空格、字母、特殊符号或中文字符。
     *
     * @param name 待校验的文件名字符串。通常来自用户搜索输入框的当前文本。
     * @return `true` = 文件名合法（纯数字且长度 3-7 位），可执行搜索；
     *         `false` = 文件名不符合搜索条件，应阻止搜索或给出提示。
     * @see SearchEngine.isNumericName 委托给引擎内部的校验逻辑
     */
    fun isSearchableName(name: String): Boolean {
        return searchEngine.isNumericName(name)
    }
}
