package com.example.pdfmanager.data.local

import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.pdfmanager.data.model.*
import android.util.Log

/**
 * Room 数据库 - 主数据库
 * 
 * 功能说明：
 * 1. 管理所有实体表的创建和版本管理（使用 Room 注解自动生成 DDL）
 * 2. 提供 5 个 DAO（Data Access Object）接口供业务层调用
 * 3. 支持数据库迁移（版本升级时自动执行 MIGRATION 逻辑）
 * 4. 支持多库隔离（不同库文件夹使用不同数据库文件，通过 dbName 区分）
 * 
 * 包含的实体表：
 * - FavoriteFolder：收藏文件夹
 * - OrganizeFolder：整理文件夹
 * - FolderPdfOrder：文件夹内 PDF 排序
 * - PdfFileEntity：PDF 文件信息（含缩略图相关字段）
 * - TagCategoryEntity：标签类别
 * - CategoryTagEntity：类别下的标签值
 * - PdfTagEntity：PDF 与标签的关联关系
 * 
 * 使用示例：
 * ```kotlin
 * // 获取数据库实例（默认名称）
 * val db = PdfManagerDatabase.getDatabase(context)
 * 
 * // 获取 DAO
 * val pdfFileDao = db.pdfFileDao()
 * val pdfTagDao = db.pdfTagDao()
 * 
 * // 切换库文件夹（不同库使用不同数据库文件）
 * val db = PdfManagerDatabase.getDatabase(context, "pdf_manager_12345.db")
 * 
 * // 关闭数据库（切换库文件夹前调用）
 * PdfManagerDatabase.closeDatabase()
 * ```
 * 
 * 数据库版本历史：
 * - v1：初始版本，包含 PdfFileEntity、TagCategoryEntity、CategoryTagEntity、PdfTagEntity
 * - v2：新增 OrganizeFolder 和 FolderPdfOrder 表
 * - v3：新增 FavoriteFolder 表
 * - v4：PdfFileEntity 新增 thumbnail_generated 和 thumbnail_path 列（MIGRATION_3_4）
 * 
 * 线程安全：
 * - 使用 @Volatile 和 synchronized 双重检查锁定保证单例安全
 * - Room 自动处理所有数据库操作的线程安全
 * 
 * 依赖关系：
 * - 依赖：android.content.Context（用于创建数据库文件）
 * - 被依赖：AppContainer、所有 Repository 层类
 * 
 * @author PDF Manager Development Team
 * @version 4
 * @since 2024-01-01
 */
@Database(
    entities = [
        FavoriteFolder::class,
        OrganizeFolder::class,
        FolderPdfOrder::class,
        PdfFileEntity::class,
        TagCategoryEntity::class,
        CategoryTagEntity::class,
        PdfTagEntity::class
    ],
    version = 4,  // 从 v3 升级到 v4，添加 thumbnailGenerated 列
    exportSchema = false  // 生产环境建议设为 true 以跟踪 Schema 变更历史
)
abstract class PdfManagerDatabase : RoomDatabase() {
    
    // ── DAO 抽象方法（Room 自动生成实现）─────────────────────────
    
    /** 收藏文件夹 DAO（CRUD 收藏文件夹及排序） */
    abstract fun favoritesDao(): FavoritesDao
    /** PDF 文件 DAO（增删改查 PDF 文件信息） */
    abstract fun pdfFileDao(): PdfFileDao
    /** 标签类别 DAO */
    abstract fun tagCategoryDao(): TagCategoryDao
    /** 类别标签值 DAO */
    abstract fun categoryTagDao(): CategoryTagDao
    /** PDF 标签关联 DAO（管理 PDF 与标签的多对多关系） */
    abstract fun pdfTagDao(): PdfTagDao

    companion object {
        @Volatile
        private var INSTANCE: PdfManagerDatabase? = null
        
        /** 当前打开的数据库名称（用于判断是否需要切换数据库） */
        private var currentDbName: String = ""

        /**
         * 数据库迁移：从版本 3 升级到版本 4
         * 
         * 变更内容：
         * 1. 添加 thumbnail_generated 列（INTEGER, 默认值=0, 非空）
         *    状态值含义：0=未生成, 1=已生成, 2=生成失败
         * 2. 添加 thumbnail_path 列（TEXT, 可空）
         *    存储缩略图相对路径（相对于应用私有目录）
         * 
         * 容错处理：
         * - thumbnail_path 列使用 try-catch 包裹（兼容已存在该列的情况）
         */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 添加 thumbnail_generated 列（非空，默认值 0）
                db.execSQL("ALTER TABLE pdf_files ADD COLUMN thumbnail_generated INTEGER NOT NULL DEFAULT 0")
                
                // 检查并添加 thumbnail_path 列（如果不存在则跳过）
                // SQLite 不支持 IF NOT EXISTS for ALTER TABLE，所以用 try-catch 容错
                try {
                    db.execSQL("ALTER TABLE pdf_files ADD COLUMN thumbnail_path TEXT")
                } catch (e: Exception) {
                    // 列已存在，忽略错误
                    Log.d("Migration", "thumbnail_path column already exists")
                }
            }
        }

        /**
         * 获取数据库实例（单例模式，支持多库隔离）
         * 
         * 功能说明：
         * 1. 如果数据库名不同，先关闭旧数据库再创建新的
         * 2. 使用双重检查锁定（Double-Checked Locking）保证线程安全
         * 3. 自动执行 MIGRATION_3_4 迁移逻辑
         * 4. 如果迁移失败，回退到破坏性重建（fallbackToDestructiveMigration）
         * 
         * 调用位置：
         * - AppContainer.init() - 初始化时获取默认数据库
         * - AppContainer.switchLibrary() - 切换库文件夹时获取新数据库
         * 
         * 使用场景：
         * - 应用启动，需要访问数据库
         * - 用户切换库文件夹，需要打开新的数据库
         * 
         * @param context Context（用于指定数据库文件位置）
         * @param dbName 数据库文件名（默认 "pdf_manager.db"）
         *               多库隔离方案："pdf_manager_{库URI哈希}.db"
         * @return PdfManagerDatabase 实例
         */
        fun getDatabase(context: android.content.Context, dbName: String = "pdf_manager.db"): PdfManagerDatabase {
            // 如果数据库名不同，关闭旧数据库
            if (INSTANCE != null && currentDbName != dbName) {
                INSTANCE?.close()
                INSTANCE = null
            }
            
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    PdfManagerDatabase::class.java,
                    dbName
                )
                    .addMigrations(MIGRATION_3_4)
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                currentDbName = dbName
                instance
            }
        }

        /**
         * 关闭当前数据库（切换库文件夹时调用）
         * 
         * 功能说明：
         * 1. 关闭当前数据库连接
         * 2. 清除单例引用（INSTANCE = null）
         * 3. 重置当前数据库名称（currentDbName = ""）
         * 
         * 调用位置：
         * - AppContainer.switchLibrary() - 切换库文件夹前调用
         * 
         * 使用场景：
         * - 用户切换库文件夹，需要关闭旧数据库
         */
        fun closeDatabase() {
            INSTANCE?.close()
            INSTANCE = null
            currentDbName = ""
        }
    }
}