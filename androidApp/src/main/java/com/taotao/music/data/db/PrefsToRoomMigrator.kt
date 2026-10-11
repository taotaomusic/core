package com.taotao.music.data.db

import android.content.Context
import android.content.SharedPreferences

/**
 * 旧 SharedPreferences 五个 JSON 大桶 → Room 的一次性迁移。
 *
 * - 幂等：完成标记写在 local_db_meta，与数据插入同一事务原子翻转；任何一步失败回滚，
 *   下次启动重试。
 * - 原文导入：歌曲 JSON 不做解码再编码，杜绝迁移自身引入的字段漂移。
 * - 迁移成功后清空旧 prefs 键，存储单一真相源回到 Room。
 *
 * 全程在调用方线程同步执行（所有存储入口都已保证非主线程），首次约几十毫秒。
 */
internal object PrefsToRoomMigrator {

    private val lock = Any()

    @Volatile
    private var done = false

    private val PREFS_FILE_NAMES = listOf(
        "playback_history",
        "playback_sync",
        "favorites",
        "ai_chat",
        "playback_state",
    )

    fun migrateIfNeeded(context: Context, db: TaotaoLocalDatabase) {
        if (done) return
        synchronized(lock) {
            if (done) return
            if (db.localDbMetaDao().getValue(PrefsPayloadParser.MIGRATED_FLAG) == "1") {
                done = true
                return
            }
            migrate(context, db)
        }
    }

    private fun migrate(context: Context, db: TaotaoLocalDatabase) {
        val appContext = context.applicationContext
        val historyPrefs = appContext.getSharedPreferences("playback_history", Context.MODE_PRIVATE)
        val syncPrefs = appContext.getSharedPreferences("playback_sync", Context.MODE_PRIVATE)
        val favoritesPrefs = appContext.getSharedPreferences("favorites", Context.MODE_PRIVATE)
        val aiPrefs = appContext.getSharedPreferences("ai_chat", Context.MODE_PRIVATE)
        val statePrefs = appContext.getSharedPreferences("playback_state", Context.MODE_PRIVATE)

        val historyRows = historyPrefs.all.flatMap { (key, value) ->
            val match = PrefsPayloadParser.HISTORY_KEY_REGEX.matchEntire(key) ?: return@flatMap emptyList()
            val accountId = match.groupValues[1].toLongOrNull() ?: return@flatMap emptyList()
            PrefsPayloadParser.parseHistoryRows(accountId, value as? String)
        }
        val outboxRows = syncPrefs.all.flatMap { (key, value) ->
            val match = PrefsPayloadParser.SNAPSHOTS_KEY_REGEX.matchEntire(key) ?: return@flatMap emptyList()
            val accountId = match.groupValues[1].toLongOrNull() ?: return@flatMap emptyList()
            PrefsPayloadParser.parseOutboxRows(accountId, value as? String)
        }
        val syncMetaRows = syncPrefs.all.mapNotNull { (key, value) ->
            PrefsPayloadParser.parseSyncMetaRow(key, value)
        }
        val favoriteIdRows = PrefsPayloadParser.parseFavoriteIdRows(
            favoritesPrefs.getStringSet("favorite_ids", null),
        )
        val favoriteSongRows = PrefsPayloadParser.parseFavoriteSongRows(
            favoritesPrefs.getString("favorite_library_cache_v2", null),
            favoriteIdRows.map { it.stableKey }.toSet(),
        )
        val (aiConversationRows, aiMessageRows) = PrefsPayloadParser.parseAiRows(
            aiPrefs.getString("conversations", null),
        )
        val queueRaw = statePrefs.getString("queue", null)
        val legacySongRaw = statePrefs.getString("song", null)
        val queueRows = PrefsPayloadParser.parseQueueRows(queueRaw, legacySongRaw)
        val savedMeta = when {
            queueRows.isEmpty() -> null
            queueRaw != null -> SavedPlaybackMetaRow(
                id = PLAYBACK_META_ID,
                queueIndex = statePrefs.getInt("queue_index", 0),
                positionMs = statePrefs.getInt("position_ms", 0),
            )
            // 旧版单歌曲格式：进度存在歌曲 JSON 里，没有独立下标。
            else -> SavedPlaybackMetaRow(
                id = PLAYBACK_META_ID,
                queueIndex = 0,
                positionMs = PrefsPayloadParser.parseLegacyPositionMs(legacySongRaw),
            )
        }

        db.runInTransaction {
            val meta = db.localDbMetaDao()
            // 双保险：并发入口已在对象锁上串行，这里再防一次跨进程的极端情况。
            if (meta.getValue(PrefsPayloadParser.MIGRATED_FLAG) == "1") return@runInTransaction
            if (historyRows.isNotEmpty()) db.playbackHistoryDao().insertAll(historyRows)
            if (outboxRows.isNotEmpty()) db.playbackOutboxDao().insertAll(outboxRows)
            syncMetaRows.forEach { meta.upsert(it) }
            if (favoriteIdRows.isNotEmpty()) db.favoritesDao().insertIds(favoriteIdRows)
            if (favoriteSongRows.isNotEmpty()) db.favoritesDao().insertSongs(favoriteSongRows)
            if (aiConversationRows.isNotEmpty()) db.aiChatDao().insertConversations(aiConversationRows)
            if (aiMessageRows.isNotEmpty()) db.aiChatDao().insertMessages(aiMessageRows)
            if (queueRows.isNotEmpty()) {
                db.playbackQueueDao().insertSongs(queueRows)
                savedMeta?.let { db.playbackQueueDao().upsertMeta(it) }
            }
            meta.upsert(LocalDbMetaRow(PrefsPayloadParser.MIGRATED_FLAG, "1"))
        }

        // 事务已提交，旧存储整体退役：清掉这五个文件的全部键，避免残留旧数据被误读。
        PREFS_FILE_NAMES.forEach { name ->
            appContext.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().apply()
        }
        done = true
    }
}
