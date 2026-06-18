package com.example.pdfmanager.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * ## 数据实体：自建文件夹（纯组织用途）
 *
 * --- 映射数据库表 ---
 * 表名：`organize_folders`
 *
 * --- 表作用 ---
 * 存储用户手动创建的"自建文件夹"。
 * 自建文件夹本质上是一个纯分类容器（类似于操作系统的文件夹），
 * 仅用于对"虚拟文件夹（FavoriteFolder）"做分组管理，并不直接包含 PDF 文件。
 *
 * --- 与虚拟文件夹（FavoriteFolder）的关系 ---
 * - 一个自建文件夹（OrganizeFolder）下可包含**多个**虚拟文件夹（FavoriteFolder）
 * - 虚拟文件夹通过 `FavoriteFolder.belongToFolderId` 指向自建文件夹
 * - 是典型的一对多关系
 *
 * --- 层级限制 ---
 * 依据产品需求（执行书 §4.4.1），自建文件夹**仅支持一级子文件夹**：
 * - `parentFolderId == null`：根目录级文件夹（顶层）
 * - `parentFolderId != null`：子文件夹（最多一层嵌套，不允许继续嵌套子文件夹）
 * 即：根 → 一级文件夹（可包含虚拟文件夹），不允许文件夹内再建文件夹。
 *
 * --- 删除行为 ---
 * 删除自建文件夹时，其下所有虚拟文件夹的 `belongToFolderId` 会被设为 `null`，
 * 从而自动移动到根目录层级（不删除虚拟文件夹本身）。
 *
 * --- 相关表 ---
 * - `favorite_folders`：虚拟文件夹（通过 `belongToFolderId` 关联此表的 `id`）
 */
@Entity(tableName = "organize_folders")
data class OrganizeFolder(
    /**
     * 自建文件夹的唯一标识 ID。
     *
     * - 类型：`String`（主键，非空）
     * - 对应列名：`id`
     * - 默认值：`UUID.randomUUID().toString()` —— 创建时自动生成全局唯一 UUID
     *   - 格式示例：`"f47ac10b-58cc-4372-a567-0e02b2c3d479"`
     * - 用 UUID 的原因：避免本地多端同步或数据库迁移时的 ID 冲突
     *
     * 作用：作为主键唯一标识一个自建文件夹，
     * 同时也被 `FavoriteFolder.belongToFolderId` 作为外键引用目标。
     */
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),

    /**
     * 自建文件夹的显示名称。
     *
     * - 类型：`String`（非空）
     * - 对应列名：`name`
     * - 内容示例：`"工作"`、`"学习资料"`、`"项目管理"`
     * - 无默认值，用户创建时必须提供
     *
     * 作用：在文件夹列表 UI 中以可读文本形式展示给用户。
     */
    val name: String,

    /**
     * 父文件夹 ID（实现一级嵌套层级）。
     *
     * - 类型：`String?`（可空）
     * - 对应列名：`parentFolderId`
     * - 默认值：`null`
     *   - `null` = 根目录级文件夹（顶层）
     *   - 非 `null` = 子文件夹，值指向其父级 `OrganizeFolder.id`
     *
     * 作用：实现自建文件夹之间的父子嵌套关系。
     *
     * --- 层级约束（应用层保证） ---
     * 依据产品需求，仅支持一级嵌套：
     * - `parentFolderId == null` ⇒ 顶层文件夹
     * - `parentFolderId != null` ⇒ 子文件夹（不允许子文件夹再拥有子文件夹）
     * 该约束需要在 DAO/Repository 层或 UI 交互层做校验，数据库不强制。
     */
    val parentFolderId: String? = null,

    /**
     * 在父容器中的排序序号。
     *
     * - 类型：`Int`
     * - 对应列名：`sortOrder`
     * - 默认值：`0`
     * - 排序规则：
     *   - 在同一个父容器下（同为根目录，或同为同一个父文件夹内），数值越小越靠前
     *   - 根目录级的文件夹按各自的 `sortOrder` 排序
     *   - 同一个父文件夹下的子文件夹按各自的 `sortOrder` 排序
     *
     * 作用：支持用户通过拖拽等方式自定义文件夹的排列顺序。
     * 在查询时配合 `ORDER BY sortOrder ASC, createdAt ASC` 获取有序列表。
     */
    val sortOrder: Int = 0,

    /**
     * 自建文件夹的创建时间戳（毫秒级）。
     *
     * - 类型：`Long`
     * - 对应列名：`createdAt`
     * - 默认值：`System.currentTimeMillis()` —— 记录对象创建时刻的 Unix 时间戳（毫秒）
     * - 格式说明：自 1970-01-01 00:00:00 UTC 以来的毫秒数
     *   - 示例：`1718764800000` 对应 2024-06-19 12:00:00 UTC
     *
     * 作用：作为创建时间的记录，可用于在排序号相同时的二次排序依据
     * （越早创建的排在前面），也可供 UI 展示创建时间。
     */
    val createdAt: Long = System.currentTimeMillis()
)
