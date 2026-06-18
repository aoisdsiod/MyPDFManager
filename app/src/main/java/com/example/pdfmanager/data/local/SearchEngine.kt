package com.example.pdfmanager.data.local

import com.example.pdfmanager.data.model.PdfFile
import java.util.regex.Pattern

/**
 * 搜索引擎 —— 专为纯数字文件名设计的搜索组件
 *
 * 【设计背景】
 * 该 PDF 管理系统常处理按数字编号命名的文档（如 "0001.pdf"、"12345.pdf" 等 3-7 位数字文件名）。
 * 为满足快速精确查找和前缀匹配两种场景，本搜索引擎采用"哈希表 + 前缀树"双索引结构。
 *
 * 【数据结构】
 * ┌─ exactIndex（HashMap）: 精确索引 —— 数字字符串 → PdfFile 列表
 * │   用途：用户输入完整数字时，O(1) 时间直接命中
 * │
 * ├─ trieRoot（TrieNode）: 前缀树 —— 字符树结构，节点存储文件列表
 * │   用途：用户输入部分数字（前缀）时，沿树遍历快速匹配全部候选
 * │
 * └─ fileIdToName（HashMap）: 反向映射 —— PDF 文件 ID → 数字文件名（无扩展名）
 *     用途：删除文件时快速查找其在索引中的位置，O(1) 定位
 *
 * 【搜索流程】
 * 1. 用户输入查询字符串（如 "001"）
 * 2. 先在 exactIndex 中精确查找（O(1)）
 * 3. 如果未命中，降级到前缀搜索（prefixSearch），遍历 Trie 树
 *
 * 【文件名规则】
 * - 只索引"纯数字 3-7 位"的文件名（去掉扩展名后判断）
 * - 例如 "0001.pdf" → 去掉扩展名 → "0001" → 是纯数字(4位) → 加入索引
 * - 非数字命名的文件不会被索引，搜索时直接忽略
 *
 * 【调用位置】
 * - com.example.pdfmanager.ui.MainViewModel (构建/更新/删除索引、搜索调用)
 * - 搜索页面用户输入查询时触发 search() 方法
 */
class SearchEngine {

    /**
     * 精确索引 —— 数字文件名（无扩展名）→ 文件列表
     *
     * 键：纯数字字符串（如 "0001","12345"），取值范围 3-7 位数字
     * 值：匹配此文件名的 PdfFile 列表（理论上每个数字名仅对应一个文件，但用列表容错）
     *
     * 【使用场景】
     * - search() 中优先查找：exactIndex[query] 直接命中
     * - addToIndex() 中填充新条目
     * - removeInternal() 中删除条目，若列表为空则移除键
     */
    private val exactIndex: MutableMap<String, MutableList<PdfFile>> = mutableMapOf()

    /**
     * 前缀树根节点 —— 整个 Trie 树的入口
     *
     * Trie 树的核心思想：
     * - 每个节点包含一个子节点 Map（字符 → TrieNode）和一个文件列表
     * - 沿着单词的每个字符向下遍历，路径上的每个节点都存储该单词对应的文件
     * - 例如插入 "001"：根节点 → '0' → '0' → '1'（isEnd=true），每个节点都存有 "001" 的文件
     *
     * 优势：当用户输入 "0" 作为前缀时，可以直接从根节点的 '0' 子节点获取所有以 "0" 开头的文件
     */
    private val trieRoot = TrieNode()

    /**
     * 文件 ID 到数字文件名的反向映射
     *
     * 键：PdfFile.id（UUID 字符串）
     * 值：对应的纯数字文件名（已去掉扩展名）
     *
     * 【用途】
     * - removeInternal() 中通过 fileId 快速找到索引中的键
     * - update() 中先通过此映射移除旧索引，再重新添加新索引
     * - 避免遍历整个 exactIndex 或 Trie 树来定位要删除的文件
     */
    private val fileIdToName: MutableMap<String, String> = mutableMapOf()

    /**
     * 数字文件名正则表达式 —— 只匹配 3-7 位纯数字
     *
     * ^         - 字符串开始
     * \\d{3,7}  - 数字字符，重复 3 到 7 次
     * $         - 字符串结束
     *
     * 此正则用于 isNumericName() 和 addToIndex() 的过滤条件
     */
    private val numericPattern = Pattern.compile("^\\d{3,7}$")

    /**
     * 去掉文件名的扩展名部分
     *
     * 逻辑：
     * - 查找最后一个 '.' 的位置（支持 "file.name.pdf" 这种多级扩展名，取最后一级）
     * - 如果找到 '.' 且 '.' 不在首位（dotIndex > 0），则截取其之前的部分
     * - 没有 '.' 则返回原字符串
     *
     * 示例：
     * - "0001.pdf"  → "0001"
     * - "0123"      → "0123"
     * - ".hidden"   → ".hidden"（dotIndex=0，不截取）
     *
     * @param name 原始文件名（可含扩展名也可不含）
     * @return 去掉扩展名后的文件名
     */
    private fun stripExtension(name: String): String {
        val dotIndex = name.lastIndexOf('.')
        return if (dotIndex > 0) name.substring(0, dotIndex) else name
    }

    /**
     * 全量构建索引 —— 清空旧索引后重新从文件列表构建
     *
     * 【执行时机】
     * - 应用启动时，从数据库中加载所有 PDF 文件后调用
     * - 数据源发生大规模变化时调用（例如从外部导入批量文件）
     *
     * 【执行流程】
     * 1. 清空 exactIndex（精确索引 Map）
     * 2. 递归清空 Trie 树（重置所有节点）
     * 3. 清空 fileIdToName 反向映射
     * 4. 遍历所有文件，逐个调用 addToIndex()
     *
     * @param files 所有 PdfFile 的列表（通常来自 PdfRepository 或数据库）
     */
    fun buildIndex(files: List<PdfFile>) {
        exactIndex.clear()
        clearTrie(trieRoot)
        fileIdToName.clear()
        for (file in files) {
            addToIndex(file)
        }
    }

    /**
     * 增量更新单个文件的索引
     *
     * 【执行时机】
     * - 用户新增一个 PDF 文件（扫描发现新文件、转换生成新文件等）
     * - 外部操作导致文件列表变化后，同步更新搜索引擎
     *
     * 【执行流程】
     * 1. 先通过 removeInternal() 移除该文件旧的索引记录（如果存在）
     * 2. 再通过 addToIndex() 添加新的索引记录
     *
     * 设计上先删再加是为了防止文件名变更时脏数据残留
     *
     * @param file 需要更新索引的 PdfFile 对象
     */
    fun update(file: PdfFile) {
        removeInternal(file.id)
        addToIndex(file)
    }

    /**
     * 从索引中移除文件
     *
     * 【执行时机】
     * - 用户删除文件时调用
     * - 文件被移动到其他位置时调用
     *
     * @param fileId PdfFile 的 ID（UUID 字符串）
     */
    fun remove(fileId: String) {
        removeInternal(fileId)
    }

    /**
     * 搜索文件 —— 先精确查找，未命中则降级到前缀搜索
     *
     * 【搜索策略】
     * 1. 空查询直接返回空列表（避免无意义搜索）
     * 2. 先在 exactIndex 中精确查找（HashMap 的 O(1) 查询）
     * 3. 精确命中则直接返回结果副本（toList() 防止外部修改内部数据）
     * 4. 未命中则调用 prefixSearch() 进行前缀匹配
     *
     * 【使用场景】
     * - 用户在搜索框输入数字后，SearchViewModel 触发此方法
     * - 配合协程和防抖（Debounce），在用户停止输入后执行搜索
     *
     * @param query 用户输入的搜索关键词（纯数字字符串，不含扩展名）
     * @return 匹配的 PdfFile 列表，未匹配返回空列表
     */
    fun search(query: String): List<PdfFile> {
        if (query.isEmpty()) return emptyList()
        val exactResults = exactIndex[query]
        if (exactResults != null) {
            return exactResults.toList()
        }
        return prefixSearch(query)
    }

    /**
     * 判断文件名是否为纯数字（3-7 位）
     *
     * 自动去掉扩展名后再判断，因此 "0001.pdf" 也会返回 true
     *
     * 【使用场景】
     * - UI 层在搜索输入框旁显示提示时调用
     * - 判断是否展示数字搜索相关功能
     *
     * @param name 原始文件名（可含扩展名，如 "0001.pdf"）
     * @return true=是纯数字命名的文件, false=否
     */
    fun isNumericName(name: String): Boolean {
        return numericPattern.matcher(stripExtension(name)).matches()
    }

    /**
     * 将文件添加到索引（内部方法，仅索引数字命名的文件）
     *
     * 【过滤条件】
     * 去掉扩展名后，通过 numericPattern 正则判断是否为 3-7 位纯数字
     * 不是纯数字命名的文件直接忽略（return），不会被索引
     *
     * 【添加步骤】
     * 1. 添加到 exactIndex（精确索引 Map），如果文件名已存在则追加到列表（去重检查）
     * 2. 插入前缀树：从根节点开始，按字符逐个向下创建/遍历节点
     * 3. 记录 fileId → 数字文件名的映射，供后续删除时反向查找
     *
     * @param file 要添加索引的 PdfFile 对象
     */
    private fun addToIndex(file: PdfFile) {
        val nameWithoutExt = stripExtension(file.name)
        // 只索引纯数字命名的文件（3-7 位）
        if (!numericPattern.matcher(nameWithoutExt).matches()) return

        // ---- 添加到精确索引（HashMap） ----
        // getOrPut：如果键存在则返回已有列表，不存在则创建新列表后返回
        val list = exactIndex.getOrPut(nameWithoutExt) { mutableListOf() }
        // 去重检查：同一个文件可能因为多次调用 addToIndex() 而重复添加
        if (list.none { it.id == file.id }) {
            list.add(file)
        }

        // ---- 添加到前缀树（Trie） ----
        insertIntoTrie(nameWithoutExt, file)

        // ---- 记录反向映射 ----
        fileIdToName[file.id] = nameWithoutExt
    }

    /**
     * 从索引中移除文件（内部方法）
     *
     * 【移除步骤】
     * 1. 通过 fileIdToName 反向映射找到数字文件名（O(1)）
     * 2. 从 exactIndex 精确索引中移除对应的文件条目
     *    - 如果文件名对应的列表变空，则删除整个键（避免空列表占位）
     * 3. 从前缀树中移除该文件的引用
     * 4. 从 fileIdToName 中移除该文件的映射
     *
     * 如果 fileId 未在 fileIdToName 中找到（该文件未被索引），则直接返回
     *
     * @param fileId PdfFile 的 ID（UUID 字符串）
     */
    private fun removeInternal(fileId: String) {
        val name = fileIdToName[fileId] ?: return
        exactIndex[name]?.removeAll { it.id == fileId }
        if (exactIndex[name]?.isEmpty() == true) {
            exactIndex.remove(name)
        }
        removeFromTrie(name, fileId)
        fileIdToName.remove(fileId)
    }

    /**
     * 插入字符串到前缀树（Trie）
     *
     * 【Trie 插入算法】
     * 假设插入字符串 "012"，Trie 树变化如下：
     *
     * 初始:     根(空)
     *           │
     * 插入 '0': 根 → '0'(文件列表=[file012])
     *                │
     * 插入 '1': 根 → '0' → '1'(文件列表=[file012])
     *                     │
     * 插入 '2': 根 → '0' → '1' → '2'(文件列表=[file012], isEnd=true)
     *
     * 关键点：路径上的每个节点都存储文件引用，这样前缀搜索时，
     * 只要走到前缀最后一个字符对应的节点，该节点的 files 列表
     * 就是所有匹配的文件。
     *
     * @param word 数字文件名（如 "00123"）
     * @param file 要关联此单词的 PdfFile 对象
     */
    private fun insertIntoTrie(word: String, file: PdfFile) {
        var node = trieRoot
        for (ch in word) {
            // getOrPut：如果当前字符对应的子节点不存在，则创建新节点
            node = node.children.getOrPut(ch) { TrieNode() }
            // 去重检查：相同文件不允许重复添加
            if (node.files.none { it.id == file.id }) {
                node.files.add(file)
            }
        }
        // 标记单词结束（当前用途不大，保留用于可能的扩展）
        node.isEnd = true
    }

    /**
     * 从前缀树中移除某个文件的引用
     *
     * 【移除算法】
     * 沿单词路径遍历，在每个节点的 files 列表中移除指定文件 ID 的条目
     *
     * 注意：此方法会保持 Trie 树结构不变（不删除空节点），
     * 因为在 Android 环境下，Trie 树规模有限且结点清理的收益不大
     *
     * @param word   要移除的文件名（与插入时的 word 保持一致）
     * @param fileId 要移除的 PdfFile ID
     */
    private fun removeFromTrie(word: String, fileId: String) {
        var node = trieRoot
        for (ch in word) {
            node = node.children[ch] ?: return  // 节点不存在，说明该单词未被索引过
            node.files.removeAll { it.id == fileId }
        }
    }

    /**
     * 前缀搜索 —— 在前缀树中查找所有以指定前缀开头的文件
     *
     * 【搜索算法】
     * 1. 从前缀字符串的第一个字符开始，沿 Trie 树逐层向下遍历
     * 2. 如果某一步找不到对应的子节点，说明没有匹配项，返回空列表
     * 3. 遍历完前缀的所有字符后，当前节点存储的就是所有匹配的文件
     * 4. 使用 distinctBy { it.id } 去重（理论上不需要，但作为安全措施）
     *
     * 时间复杂度：O(m)，其中 m 为前缀字符串长度（不取决于索引总量）
     * 对比：HashMap 前缀扫描（O(n)）效率更高
     *
     * @param prefix 用户输入的前缀字符串（如 "00"）
     * @return 匹配的 PdfFile 列表，无匹配返回空列表
     */
    private fun prefixSearch(prefix: String): List<PdfFile> {
        var node = trieRoot
        for (ch in prefix) {
            node = node.children[ch] ?: return emptyList()
        }
        return node.files.distinctBy { it.id }
    }

    /**
     * 递归清空前缀树（重置根节点下的所有数据）
     *
     * 与直接新建 TrieNode() 不同，此方法复用根节点对象，
     * 确保 trieRoot 引用始终指向同一个对象（对外部引用透明）
     *
     * @param node 要清空的节点（入口传入 trieRoot）
     */
    private fun clearTrie(node: TrieNode) {
        node.children.clear()
        node.files.clear()
        node.isEnd = false
    }

    /**
     * 前缀树节点（内部数据类）
     *
     * 【字段说明】
     * - children: 子节点映射表，键为字符（Char），值为子 TrieNode
     *   例如节点对应字符 '0'，则 children['1'] 表示走 "01" 路径的下一节点
     *
     * - files: 当前节点关联的文件列表
     *   每条路径上的所有节点都存储以该路径为前缀的所有文件
     *   例如："012" 和 "013" 都会在根 → '0' → '1' 节点的 files 中
     *
     * - isEnd: 标记当前节点是否为某个单词的结束位置
     *   目前仅用于标记，前缀搜索中未使用此字段（用于可能的"精确单词匹配"扩展）
     */
    private class TrieNode {
        val children: MutableMap<Char, TrieNode> = mutableMapOf()
        val files: MutableList<PdfFile> = mutableListOf()
        var isEnd: Boolean = false
    }
}
