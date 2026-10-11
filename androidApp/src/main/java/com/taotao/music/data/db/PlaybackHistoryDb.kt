package com.taotao.music.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Upsert

/**
 * 最近播放展示缓存行（原 SharedPreferences 的 account.<id>.entries JSON 桶）。
 *
 * 歌曲本体仍以 [songJson]（SongCodec 编码）原文存储：单行损坏只丢一首，
 * 不再像旧的整串 JSON 那样一处解析失败就丢整份历史。
 */
@Entity(
    tableName = "playback_history",
    primaryKeys = ["accountId", "songKey"],
    // 列表读取与淘汰都按 (账号, 播放时间) 排序，复合索引让 ORDER BY 走索引而非全表排序。
    indices = [Index("accountId", "playedAt")],
)
data class PlaybackHistoryRow(
    /** 已认证的服务端用户 ID，账号分桶与旧存储一致。 */
    val accountId: Long,
    /** 歌曲稳定键（source:identity），同曲原地更新。 */
    val songKey: String,
    val songJson: String,
    val playedAt: Long,
    val firstPlayedAt: Long,
    val playCount: Int,
    val completedCount: Int,
    val totalListenedMs: Long,
)

@Dao
interface PlaybackHistoryDao {

    @Query(
        "SELECT * FROM playback_history WHERE accountId = :accountId " +
            "ORDER BY playedAt DESC, rowid DESC"
    )
    fun listByAccount(accountId: Long): List<PlaybackHistoryRow>

    @Query(
        "SELECT * FROM playback_history WHERE accountId = :accountId AND songKey = :songKey LIMIT 1"
    )
    fun getByKey(accountId: Long, songKey: String): PlaybackHistoryRow?

    @Upsert
    fun upsert(row: PlaybackHistoryRow)

    @Insert
    fun insertAll(rows: List<PlaybackHistoryRow>)

    @Query("DELETE FROM playback_history WHERE accountId = :accountId")
    fun deleteByAccount(accountId: Long)

    /** 只保留最近 [limit] 条；同曲原地更新不会膨胀，超出时按播放时间淘汰最旧。 */
    @Query(
        "DELETE FROM playback_history WHERE accountId = :accountId AND songKey NOT IN (" +
            "SELECT songKey FROM playback_history WHERE accountId = :accountId " +
            "ORDER BY playedAt DESC, rowid DESC LIMIT :limit)"
    )
    fun trimToLimit(accountId: Long, limit: Int)
}
