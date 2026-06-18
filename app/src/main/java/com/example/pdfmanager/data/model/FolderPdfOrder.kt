package com.example.pdfmanager.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * ## 数据实体：虚拟文件夹内的 PDF 排序持久化
 *
 * --- 映射数据库表 ---
 * 表名：`folder_pdf_order`
 *
 * --- 表作用 ---
 * 存储每个"虚拟文件夹（FavoriteFolder）"中 PDF 文件的手动排序结果。
 * 当用户在虚拟文件夹内通过拖拽等方式自定义 PDF 文件的显示顺序时，
 * 将排序结果持久化到此表中，下次打开该虚拟文件夹时按此顺序展示。
 *
 * --- 设计思路 ---
 * 使用"逗号分隔的 PDF ID 列表"（`pdfOrder`）而非另一张关联表来存储排序，
 * 原因：
 * 1. 排序数据通常在读取时一次性获取整个列表，按顺序遍历即可
 * 2. 避免额外 JOIN 操作，提高读取性能
 * 3. 整体序列化/反序列化在一次 I/O 中完成，无需逐行操作
 *
 * --- 数据更新策略 ---
 * 每当用户在虚拟文件夹内完成一次拖拽排序后：
 * 1. 生成新的排序列表
 * 2. 以 `folderId` 为主键，整体替换（UPSERT）该行的 `pdfOrder` 和 `updatedAt`
 *
 * --- 数据删除策略 ---
 * 当对应的虚拟文件夹（FavoriteFolder）被删除时，
 * 需要同时删除此表中对应的行（级联删除，由应用层 DAO 实现）。
 *
 * --- 相关表 ---
 * - `favorite_folders`：虚拟文件夹（通过 `folderId` 关联其主键 `id`）
 * - 本表不直接关联具体的 `PdfInfo` 表，仅存储 PDF 的 ID 字符串列表
 *
 * --- 参考 ---
 * 执行书 §4.4.2：排序结果持久化到 `folder_pdf_order` 表
 */
@Entity(tableName = "folder_pdf_order")
data class FolderPdfOrder(
    /**
     * 虚拟文件夹的唯一 ID（同时也是本表的主键）。
     *
     * - 类型：`String`（主键，非空）
     * - 对应列名：`folderId`
     * - 取值来源：`FavoriteFolder.id`
     * - 无默认值，传入时确定
     * - 格式示例：`"a1b2c3d4-e5f6-7890-abcd-ef1234567890"`
     *
     * 作用：
     * 1. 唯一标识一条排序记录
     * 2. 作为外键逻辑引用 `favorite_folders.id`（外键约束由应用层保证，数据库不强制）
     * 3. 每个虚拟文件夹在此表中至多对应一行（一对一关系）
     *
     * 使用说明：
     * - 插入时：如果虚拟文件夹是新建的且尚无排序记录，不会自动写入此表
     *   （只有用户在该虚拟文件夹内执行过排序操作后，才会写入）
     * - 读取时：若本表中没有对应 `folderId` 的行，说明该虚拟文件夹使用默认排序
     *   （如按创建时间倒序或按文件名排序）
     */
    @PrimaryKey
    val folderId: String,

    /**
     * PDF 文件 ID 的有序列表（逗号分隔的字符串）。
     *
     * - 类型：`String`（非空）
     * - 对应列名：`pdfOrder`
     * - 无默认值，必须有值
     * - 格式说明：由 PDF 文件的唯一 ID 以英文逗号（`,`）连接而成的字符串
     *   - 示例：`"pdf_uuid_1,pdf_uuid_2,pdf_uuid_3"`
     *   - 列表顺序即用户自定义的展示顺序（第一个元素为最顶部）
     *
     * 序列化与反序列化（通常在 Repository 层或 ViewModel 层进行）：
     * ```
     * // 序列化（写入时）：将 List&lt;String&gt; 拼接为逗号分隔字符串
     * val orderStr: String = pdfIdList.joinToString(",")
     *
     * // 反序列化（读取时）：将逗号分隔字符串拆分为 List&lt;String&gt;
     * val pdfIdList: List&lt;String&gt; = orderStr.split(",")
     * ```
     *
     * 注意：
     * - 此列表只包含该虚拟文件夹中**已排序**的 PDF ID
     * - 如果某些 PDF 不在列表中，则它们会按照默认排序规则排在最后
     * - 当虚拟文件夹的筛选结果发生变化（新增或删除了 PDF）时，
     *   需要以此列表为基础，合并新的结果集来更新排序列表
     */
    val pdfOrder: String,

    /**
     * 排序记录的最近更新时间戳（毫秒级）。
     *
     * - 类型：`Long`
     * - 对应列名：`updatedAt`
     * - 默认值：`System.currentTimeMillis()` —— 记录本次更新时刻的 Unix 时间戳（毫秒）
     * - 格式说明：自 1970-01-01 00:00:00 UTC 以来的毫秒数
     *   - 示例：`1718764800000` 对应 2024-06-19 12:00:00 UTC
     *
     * 作用：
     * 1. 记录用户最后一次在虚拟文件夹中进行排序操作的时间
     * 2. 可用于排序冲突时的最后写入者优先（Last-Write-Wins）策略
     * 3. 可用于排序缓存的过期判断：如果上次排序时间远早于文件夹筛选条件变化时间，
     *    可能需要触发排序列表的刷新/合并
     */
    val updatedAt: Long = System.currentTimeMillis()
)
