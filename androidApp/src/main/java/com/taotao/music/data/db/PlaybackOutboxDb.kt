package com.taotao.music.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert

/**
 * 播放统计 outbox 快照行（原 SharedPreferences 的 account.<id>.snapshots JSON 桶）。
 *
 * 每个会话一行、只保留最新累计快照；会话级 compare-and-remove、单调合并等规则
 * 仍在 [com.taotao.music.data.PlaybackSnapshotPolicy]，这里只负责落盘形态。
 */
@Entity(
    tableName = "playback_outbox",
    indices = [Index("accountId")],
)
data class PlaybackOutboxRow(
    @PrimaryKey val sessionId: String,
    val accountId: Long,
    val source: String,
    val songId: String,
    val startedAt: Long,
    val lastPlayedAt: Long,
    val listenedMs: Long,
    val durationSeconds: Int?,
    val completed: Boolean,
    val historyRevision: Long,
    val clearMarker: String?,
    val outboxVersion: Long,
    val playbackCycle: Long,
)

@Dao
interface PlaybackOutboxDao {

    @Query("SELECT * FROM playback_outbox WHERE accountId = :accountId ORDER BY rowid ASC")
    fun listByAccount(accountId: Long): List<PlaybackOutboxRow>

    @Query("SELECT * FROM playback_outbox WHERE sessionId = :sessionId LIMIT 1")
    fun getBySession(sessionId: String): PlaybackOutboxRow?

    @Upsert
    fun upsert(row: PlaybackOutboxRow)

    @Insert
    fun insertAll(rows: List<PlaybackOutboxRow>)

    @Query("DELETE FROM playback_outbox WHERE sessionId = :sessionId")
    fun deleteBySession(sessionId: String)

    @Query("DELETE FROM playback_outbox WHERE accountId = :accountId")
    fun deleteByAccount(accountId: Long)

    /** 按「最后播放时间 → outbox 版本」保留最新 [keep] 条，淘汰更旧的会话。 */
    @Query(
        "DELETE FROM playback_outbox WHERE accountId = :accountId AND sessionId NOT IN (" +
            "SELECT sessionId FROM playback_outbox WHERE accountId = :accountId " +
            "ORDER BY lastPlayedAt ASC, outboxVersion ASC LIMIT :keep)"
    )
    fun trimByAccount(accountId: Long, keep: Int)
}
