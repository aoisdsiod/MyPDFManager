package com.example.pdfmanager.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * ## 数据实体：虚拟文件夹（收藏夹）
 *
 * --- 映射数据库表 ---
 * 表名：`favorite_folders`
 *
 * --- 表作用 ---
 * 存储用户创建的"虚拟文件夹"（收藏夹/智能文件夹）。
 * 虚拟文件夹不实际移动 PDF 文件，而是通过一组筛选条件（`savedFilterJson`）
 * 来"动态或半动态"地聚合展示 PDF 文件。
 *
 * --- 与自建文件夹（OrganizeFolder）的关系 ---
 * 虚拟文件夹（FavoriteFolder）可以归类在自建文件夹（OrganizeFolder）之下，
 * 通过 `belongToFolderId` 字段关联。一个自建文件夹下可包含多个虚拟文件夹。
 * 详见：执行书 §4.4.1
 *
 * --- 删除行为 ---
 * 当所属的自建文件夹被删除时，该虚拟文件夹的 `belongToFolderId` 会被设为 `null`，
 * 从而自动移到"根目录"层级下，避免数据丢失。
 *
 * --- 相关表 ---
 * - `organize_folders`：自建文件夹（纯组织用途，用于对虚拟文件夹做分组）
 * - `folder_pdf_order`：虚拟文件夹内部的 PDF 手动排序持久化
 */
@Entity(tableName = "favorite_folders")
data class FavoriteFolder(
    /**
     * 虚拟文件夹的唯一标识 ID。
     *
     * - 类型：`String`（主键，非空）
     * - 对应列名：`id`
     * - 默认值：`UUID.randomUUID().toString()` —— 创建时自动生成全局唯一 UUID
     *   - 格式示例：`"a1b2c3d4-e5f6-7890-abcd-ef1234567890"`
     * - 用 UUID 而非自增 ID 的原因：避免分布式/本地多端同步时的 ID 冲突
     *
     * 作用：作为主键唯一标识一个虚拟文件夹，也是 `FolderPdfOrder.folderId` 的外键引用目标。
     */
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),

    /**
     * 虚拟文件夹的显示名称。
     *
     * - 类型：`String`（非空）
     * - 对应列名：`name`
     * - 内容示例：`"重要文档"`、`"待阅读"`、`"工作资料 2024"`
     * - 无默认值，用户创建时必须提供
     *
     * 作用：在 UI 列表中以可读文本形式展示给用户。
     */
    val name: String,

    /**
     * 筛选条件的 JSON 序列化字符串。
     *
     * - 类型：`String`（非空）
     * - 对应列名：`savedFilterJson`
     * - 格式说明：将 `SavedFilter` 数据类的实例序列化为 JSON 字符串后存储
     *   - `SavedFilter` 包含筛选条件，如：按标签筛选、按分类筛选、按日期范围筛选等
     * - 反序列化时机：当需要根据该虚拟文件夹的条件筛选 PDF 时，
     *   从数据库读取此 JSON 字符串并反序列化为 `SavedFilter` 对象
     *
     * 作用：保存虚拟文件夹的核心——筛选条件定义。
     * 打开该虚拟文件夹时，应用解析此 JSON 并查询符合条件的 PDF 文件。
     */
    val savedFilterJson: String,

    /**
     * 所属自建文件夹的 ID（外键逻辑引用）。
     *
     * - 类型：`String?`（可空）
     * - 对应列名：`belongToFolderId`
     * - 默认值：`null`
     *   - `null` = 此虚拟文件夹位于根目录下，不属于任何自建文件夹
     *   - 非 `null` = 此虚拟文件夹位于对应 `OrganizeFolder.id` 的自建文件夹之下
     *
     * 作用：实现虚拟文件夹→自建文件夹的多对一归属关系。
     *
     * --- 级联删除策略（应用层实现） ---
     * 当用户删除一个自建文件夹时：
     * 1. 将该自建文件夹下所有虚拟文件夹的 `belongToFolderId` 置为 `null`
     * 2. 被"释放"的虚拟文件夹自动出现在根目录列表中
     * 不实际删除虚拟文件夹，避免用户丢失数据。
     */
    val belongToFolderId: String? = null,

    /**
     * 在父容器中的排序序号。
     *
     * - 类型：`Int`
     * - 对应列名：`sortOrder`
     * - 默认值：`0`
     * - 排序范围：
     *   - 当 `belongToFolderId == null` 时：在根目录的同级虚拟文件夹中排序
     *   - 当 `belongToFolderId != null` 时：在所属自建文件夹内的同级虚拟文件夹中排序
     * - 排序规则：数值越小越靠前
     *
     * 作用：支持用户通过拖拽等方式自定义虚拟文件夹的显示顺序。
     * 在查询时配合 `ORDER BY sortOrder ASC` 获取有序列表。
     */
    val sortOrder: Int = 0,

    /**
     * 虚拟文件夹的创建时间戳（毫秒级）。
     *
     * - 类型：`Long`
     * - 对应列名：`createdAt`
     * - 默认值：`System.currentTimeMillis()` —— 记录对象创建时刻的 Unix 时间戳（毫秒）
     * - 格式说明：自 1970-01-01 00:00:00 UTC 以来的毫秒数
     *   - 示例：`1718764800000` 对应 2024-06-19
     *
     * 作用：用于按创建时间排序展示，也可供"最近创建"筛选逻辑使用。
     */
    val createdAt: Long = System.currentTimeMillis()
)
