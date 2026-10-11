package com.taotao.music.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert

/** 冷启动恢复用的播放队列歌曲行（原 SharedPreferences 的 playback_state/queue JSON 桶）。 */
@Entity(tableName = "saved_queue_song")
data class SavedQueueSongRow(
    /** 队列下标，0 起。 */
    @PrimaryKey val position: Int,
    val songJson: String,
)

/** 队列的当前下标与播放进度；恒定单行（[com.taotao.music.data.db.PLAYBACK_META_ID]）。 */
@Entity(tableName = "saved_playback_meta")
data class SavedPlaybackMetaRow(
    @PrimaryKey val id: Int,
    val queueIndex: Int,
    val positionMs: Int,
)

@Dao
interface PlaybackQueueDao {

    @Query("SELECT * FROM saved_queue_song ORDER BY position ASC")
    fun listSongs(): List<SavedQueueSongRow>

    @Insert
    fun insertSongs(rows: List<SavedQueueSongRow>)

    @Query("DELETE FROM saved_queue_song")
    fun deleteAllSongs()

    @Query("SELECT * FROM saved_playback_meta WHERE id = :id LIMIT 1")
    fun getMeta(id: Int): SavedPlaybackMetaRow?

    @Upsert
    fun upsertMeta(row: SavedPlaybackMetaRow)

    @Query("DELETE FROM saved_playback_meta WHERE id = :id")
    fun deleteMeta(id: Int)
}

/** saved_playback_meta 的恒定单行主键。 */
const val PLAYBACK_META_ID = 1
