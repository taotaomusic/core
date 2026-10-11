package com.taotao.music.data

import android.content.Context
import com.taotao.music.data.db.LocalDb
import com.taotao.music.data.db.PLAYBACK_META_ID
import com.taotao.music.data.db.SavedQueueSongRow
import com.taotao.music.data.db.SavedPlaybackMetaRow
import com.taotao.music.model.Song

/** 保存最近的播放队列及进度，应用重新打开时恢复整条队列而不只是当前一首。 */
class PlaybackStateStore(context: Context) {
    private val db by lazy { LocalDb.ready(context) }

    fun save(queue: List<Song>, index: Int, positionMs: Int) {
        if (queue.isEmpty()) return
        val rows = queue.mapIndexed { position, song -> SavedQueueSongRow(position, SongCodec.encode(song)) }
        db.runInTransaction {
            val dao = db.playbackQueueDao()
            dao.upsertMeta(
                SavedPlaybackMetaRow(
                    id = PLAYBACK_META_ID,
                    queueIndex = index.coerceIn(queue.indices),
                    positionMs = positionMs.coerceAtLeast(0),
                )
            )
            dao.deleteAllSongs()
            dao.insertSongs(rows)
        }
    }

    fun read(): SavedPlaybackState? {
        val meta = db.playbackQueueDao().getMeta(PLAYBACK_META_ID) ?: return null
        // 单行解码失败只丢那一首；队列整体仍可恢复。
        val queue = db.playbackQueueDao().listSongs().mapNotNull { SongCodec.decode(it.songJson) }
        if (queue.isEmpty()) return null
        return SavedPlaybackState(
            queue = queue,
            index = meta.queueIndex.coerceIn(queue.indices),
            positionMs = meta.positionMs.coerceAtLeast(0),
        )
    }

    fun clear() {
        db.runInTransaction {
            db.playbackQueueDao().deleteMeta(PLAYBACK_META_ID)
            db.playbackQueueDao().deleteAllSongs()
        }
    }
}

data class SavedPlaybackState(
    val queue: List<Song>,
    val index: Int,
    val positionMs: Int,
) {
    /** 当前曲目，便于界面直接取用。 */
    val song: Song get() = queue[index.coerceIn(queue.indices)]
}
