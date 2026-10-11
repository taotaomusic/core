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
 * 本地结构化存储库（SQLite/Room），按域分表、每域一个文件：
 *
 * - 播放域：`playback_history`（最近播放）、`playback_outbox`（统计待传）、
 *   `saved_queue_song`/`saved_playback_meta`（队列恢复）、`local_db_meta`（库内元数据）
 * - 收藏域：`favorite_ids` + `favorite_songs`
 * - AI 域：`ai_conversations` + `ai_messages`
 * - 会话与偏好域：`auth_session`、`quality_preference`、`appearance_preference`、
 *   `cover_skin_preference`、`sleep_timer_preference`、`update_dismissal`、
 *   `device_identity`、`search_history_entry`/`search_history_state`、
 *   `remote_config_entry`/`remote_config_state`、
 *   `im_session_credential` + `im_peer`
 *
 * 主线程读方（Compose 状态、UI 回调）一律经各域的内存镜像（[MirroredPreference] 与
 * 各大桶存储类）访问；写统一走 `LocalDb.writeExecutor` 串行落库。
 * Android 端持久化至此只剩：本库、SharedPreferences 的 hotfix 计数（随补丁系统
 * 下线一并删除）、文件系统大件（下载/日志/图片）。
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
        AuthSessionRow::class,
        QualityRow::class,
        AppearanceRow::class,
        CoverSkinRow::class,
        SleepTimerRow::class,
        UpdateDismissalRow::class,
        DeviceIdentityRow::class,
        SearchHistoryEntryRow::class,
        SearchHistoryStateRow::class,
        RemoteConfigEntryRow::class,
        RemoteConfigStateRow::class,
        ImSessionCredentialRow::class,
        ImPeerRow::class,
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
    abstract fun authSessionDao(): AuthSessionDao
    abstract fun preferenceDao(): PreferenceDao
    abstract fun searchHistoryDao(): SearchHistoryDao
    abstract fun remoteConfigDao(): RemoteConfigDao
    abstract fun imDao(): ImDao
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
     * 已经分两阶段原子迁入。Room 默认禁止主线程访问，主线程调用方必须自行通过
     * runBlocking(Dispatchers.IO) 进入（镜像类已内置）。
     */
    fun ready(context: Context): TaotaoLocalDatabase {
        val db = database(context)
        PrefsToRoomMigrator.migrateIfNeeded(context.applicationContext, db)
        PrefsToRoomMigrator.migratePreferenceTablesIfNeeded(context.applicationContext, db)
        return db
    }

    /** 各域镜像的写后落盘队列：单线程串行执行，主线程调用方只改内存。 */
    val writeExecutor: Executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "taotao-local-db-writer").apply { isDaemon = true }
    }

    /**
     * v1→v2：新增会话与偏好域的全部表，并给 v1 的三张大表补查询索引。
     * v1 从未随发布版出过端（升级路径只有开发装机），但索引仍按正式迁移写全：
     * Room 在迁移后会校验实际 schema 与实体声明完全一致，多列/少列索引都不行。
     */
    private val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // ---- 会话与偏好域（新增表） ----
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `auth_session` " +
                    "(`id` INTEGER NOT NULL, `accessToken` TEXT, `refreshToken` TEXT, " +
                    "`expiresAt` INTEGER NOT NULL, `userId` INTEGER, PRIMARY KEY(`id`))"
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `quality_preference` " +
                    "(`id` INTEGER NOT NULL, `playback` INTEGER NOT NULL, `download` INTEGER NOT NULL, PRIMARY KEY(`id`))"
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `appearance_preference` " +
                    "(`id` INTEGER NOT NULL, `mode` TEXT, PRIMARY KEY(`id`))"
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `cover_skin_preference` " +
                    "(`id` INTEGER NOT NULL, `skin` TEXT, PRIMARY KEY(`id`))"
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `sleep_timer_preference` " +
                    "(`id` INTEGER NOT NULL, `lastMinutes` INTEGER NOT NULL, `waitForSongEnd` INTEGER NOT NULL, PRIMARY KEY(`id`))"
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `update_dismissal` " +
                    "(`id` INTEGER NOT NULL, `dismissedVersionCode` INTEGER NOT NULL, PRIMARY KEY(`id`))"
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `device_identity` " +
                    "(`id` INTEGER NOT NULL, `deviceId` TEXT NOT NULL, PRIMARY KEY(`id`))"
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `search_history_entry` " +
                    "(`keyword` TEXT NOT NULL, `position` INTEGER NOT NULL, PRIMARY KEY(`keyword`))"
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `search_history_state` " +
                    "(`id` INTEGER NOT NULL, `recordingEnabled` INTEGER NOT NULL, PRIMARY KEY(`id`))"
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `remote_config_entry` " +
                    "(`configKey` TEXT NOT NULL, `configValue` TEXT NOT NULL, PRIMARY KEY(`configKey`))"
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `remote_config_state` " +
                    "(`id` INTEGER NOT NULL, `version` INTEGER NOT NULL, PRIMARY KEY(`id`))"
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `im_session_credential` " +
                    "(`id` INTEGER NOT NULL, `accountId` INTEGER NOT NULL, `uid` TEXT NOT NULL, `token` TEXT NOT NULL, " +
                    "`expiresAt` INTEGER NOT NULL, `deviceFlag` INTEGER NOT NULL, `deviceLevel` INTEGER NOT NULL, " +
                    "`gatewayUrl` TEXT NOT NULL, PRIMARY KEY(`id`))"
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `im_peer` " +
                    "(`accountId` INTEGER NOT NULL, `uid` TEXT NOT NULL, PRIMARY KEY(`accountId`, `uid`))"
            )

            // ---- v1 大表补索引（实体声明变了，迁移必须同步实际索引） ----
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_playback_history_accountId_playedAt` ON `playback_history` (`accountId`, `playedAt`)")
            db.execSQL("DROP INDEX IF EXISTS `index_playback_outbox_accountId`")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_playback_outbox_accountId_lastPlayedAt` ON `playback_outbox` (`accountId`, `lastPlayedAt`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_favorite_songs_position` ON `favorite_songs` (`position`)")
        }
    }
}
