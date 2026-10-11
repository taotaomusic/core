package com.taotao.music.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * 本地结构化存储库（SQLite/Room）。
 *
 * 收录五个原本整串塞在 SharedPreferences 里的 JSON 大桶：最近播放、播放统计 outbox、
 * 收藏缓存、AI 会话、播放队列。单行损坏只丢单条数据；查询、增量更新与事务都比
 * 「读全量 → 改一条 → 写全量」的键值模式可靠得多。
 * v2 起小键值也统一进 local_kv 表（[LocalKvStore] 门面），Android 端持久化只剩
 * 本库、SharedPreferences 的 hotfix 计数（随补丁系统下线一并删除）与文件系统大件。
 */
@Database(
    entities = [
        PlaybackHistoryRow::class,
        PlaybackOutboxRow::class,
        FavoriteIdRow::class,
        FavoriteSongRow::class,
        AiConversationRow::class,
        AiMessageRow::class,
        SavedQueueSongRow::class,
        SavedPlaybackMetaRow::class,
        LocalDbMetaRow::class,
        LocalKvRow::class,
    ],
    version = 2,
    exportSchema = false,
)
abstract class TaotaoLocalDatabase : RoomDatabase() {
    abstract fun playbackHistoryDao(): PlaybackHistoryDao
    abstract fun playbackOutboxDao(): PlaybackOutboxDao
    abstract fun favoritesDao(): FavoritesDao
    abstract fun aiChatDao(): AiChatDao
    abstract fun playbackQueueDao(): PlaybackQueueDao
    abstract fun localDbMetaDao(): LocalDbMetaDao
    abstract fun localKvDao(): LocalKvDao
}

/** 库单例与共享设施。 */
object LocalDb {

    @Volatile
    private var database: TaotaoLocalDatabase? = null

    fun database(context: Context): TaotaoLocalDatabase = database ?: synchronized(this) {
        database ?: Room.databaseBuilder(context.applicationContext, TaotaoLocalDatabase::class.java, "taotao-local.db")
            .addMigrations(MIGRATION_1_2)
            // 版本回退（装回旧包）时重建本地库：所有桶都是可自愈的缓存或可补传的 outbox，
            // 比因 schema 版本不匹配而崩溃循环安全。
            .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
            .build()
            .also { database = it }
    }

    /**
     * 所有存储入口统一从这里拿库：保证该库第一次被使用前，旧 SharedPreferences 的数据
     * 已经原子迁入。Room 默认禁止主线程访问，主线程调用方（键值镜像、收藏镜像、AI 会话）
     * 必须自行通过 runBlocking(Dispatchers.IO) 进入。
     */
    fun ready(context: Context): TaotaoLocalDatabase {
        val db = database(context)
        PrefsToRoomMigrator.migrateIfNeeded(context.applicationContext, db)
        PrefsToRoomMigrator.migrateKvIfNeeded(context.applicationContext, db)
        return db
    }

    /** 收藏、AI 会话与本地键值的写后落盘队列：单线程串行执行，主线程调用方只改内存镜像。 */
    val writeExecutor: Executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "taotao-local-db-writer").apply { isDaemon = true }
    }

    /** v1→v2：新增通用键值表（v1 从未随发布版出过端，升级路径只有开发装机，建表即可）。 */
    private val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `local_kv` " +
                    "(`storeKey` TEXT NOT NULL, `storeValue` TEXT NOT NULL, PRIMARY KEY(`storeKey`))"
            )
        }
    }
}
