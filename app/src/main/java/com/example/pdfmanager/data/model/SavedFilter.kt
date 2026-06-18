package com.example.pdfmanager.data.model

import com.google.gson.annotations.SerializedName

/**
 * ## 保存的筛选条件数据类
 *
 * 用于持久化用户在筛选界面配置的过滤条件状态，
 * 支持两个使用场景：
 *
 * 1. **虚拟文件夹（Saved View）**：用户可以将一组筛选条件保存为一个"虚拟文件夹"，
 *    后续在文件列表中以独立入口展示，每次进入自动执行相同筛选。
 * 2. **筛选页状态恢复**：当用户退出筛选页再返回时，通过此模型恢复之前的筛选设置，
 *    避免筛选状态丢失。
 *
 * 该模型使用 Gson 序列化为 JSON 字符串后，存储在 Room 数据库的某个字段
 * （如 `saved_views` 表的 `filter_json` 列）中，支持嵌套的 [FilterLogic] 枚举。
 *
 * @property selectedTagKeys 当前选中的标签键列表，每个元素格式为 `"categoryId:value"`
 *                           （示例：`"year:2024"`、`"author:张三"`），
 *                           用于在筛选时精确匹配具有指定类别 + 标签值的文件。
 *                           Gson 序列化时字段名为 "selectedTagKeys"。
 * @property filterLogic     跨类别标签的筛选逻辑：
 *                           - [FilterLogic.AND]：选中的不同类别条件取"交集"
 *                             （文件必须同时满足所有选中类别）
 *                           - [FilterLogic.OR]：选中的不同类别条件取"并集"
 *                             （文件只需满足任一选中类别即可）
 *                           同一类别内多个标签值之间始终取 OR 逻辑。
 *                           Gson 序列化时字段名为 "filterLogic"。
 * @property includeNoTag    是否仅筛选出没有任何标签的文件：
 *                           - `false`（默认）：正常筛选，按 [selectedTagKeys] 匹配
 *                           - `true`：忽略 [selectedTagKeys]，直接展示所有无标签文件
 *                           用于"无标签文件"这一特殊筛选场景。
 *                           Gson 序列化时字段名为 "includeNoTag"。
 */
data class SavedFilter(
    @SerializedName("selectedTagKeys")
    val selectedTagKeys: List<String> = emptyList(),

    @SerializedName("filterLogic")
    val filterLogic: FilterLogic = FilterLogic.AND,

    @SerializedName("includeNoTag")
    val includeNoTag: Boolean = false
)

/**
 * ## 跨类别筛选逻辑枚举
 *
 * 定义当选中多个不同类别的标签时，如何组合筛选条件。
 * 同一类别内部多个标签值之间始终取 OR（或）逻辑，不受此枚举控制。
 *
 * ### 使用场景
 * 用户选中了"年份:2024"（A 类别）和"作者:张三"（B 类别）：
 * - [AND]：仅显示"2024 年且作者是张三"的文件
 * - [OR] ：显示"2024 年的文件"或"作者是张三的文件"的并集
 *
 * #### FilterLogic.AND
 * 跨类别条件取交集（Intersection），缩小筛选范围，结果更加精确。
 * 适合用户需要精确定位同时满足多个条件文件的场景。
 *
 * #### FilterLogic.OR
 * 跨类别条件取并集（Union），扩大筛选范围，结果更加宽泛。
 * 适合用户希望查看满足任一条件即可的宽泛筛选场景。
 */
enum class FilterLogic {
    AND,
    OR
}
