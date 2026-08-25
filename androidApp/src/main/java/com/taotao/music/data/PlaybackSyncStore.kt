package com.taotao.music.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 播放同步待办队列。
 *
 * 队列只保存每个会话最新的累计快照，重复写入会覆盖旧快照；因此长时间离线播放不会让
 * SharedPreferences 无限膨胀。清空操作也作为待办保存，避免网络失败后云端历史复活。
 */
class PlaybackSyncStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("playback_sync", Context.MODE_PRIVATE)

    fun snapshots(): List<PendingPlaybackSnapshot> = runCatching {
        val values = JSONArray(preferences.getString(KEY_SNAPSHOTS, "[]"))
        (0 until values.length()).mapNotNull { index -> values.optJSONObject(index)?.toSnapshot() }
    }.getOrDefault(emptyList())

    fun put(snapshot: PendingPlaybackSnapshot) {
        val updated = snapshots().filterNot { it.sessionId == snapshot.sessionId } + snapshot
        writeSnapshots(updated)
    }

    fun remove(sessionId: String) = writeSnapshots(snapshots().filterNot { it.sessionId == sessionId })

    /** 清空发生前尚未上传的会话不能在稍后把历史重新写回来。 */
    fun discardSnapshots() = writeSnapshots(emptyList())

    fun markClearPending() = preferences.edit().putBoolean(KEY_CLEAR_PENDING, true).apply()

    fun isClearPending(): Boolean = preferences.getBoolean(KEY_CLEAR_PENDING, false)

    fun confirmClear() = preferences.edit().remove(KEY_CLEAR_PENDING).apply()

    private fun writeSnapshots(items: List<PendingPlaybackSnapshot>) {
        val encoded = JSONArray().apply { items.takeLast(MAX_PENDING).forEach { put(it.toJson()) } }
        preferences.edit().putString(KEY_SNAPSHOTS, encoded.toString()).apply()
    }

    private fun PendingPlaybackSnapshot.toJson() = JSONObject()
        .put("sessionId", sessionId).put("songId", songId).put("startedAt", startedAt)
        .put("lastPlayedAt", lastPlayedAt).put("listenedMs", listenedMs)
        .put("durationSeconds", durationSeconds).put("completed", completed)

    private fun JSONObject.toSnapshot(): PendingPlaybackSnapshot? {
        val sessionId = optString("sessionId")
        val songId = optLong("songId", -1)
        val startedAt = optLong("startedAt", 0)
        if (sessionId.isBlank() || songId < 0 || startedAt <= 0) return null
        return PendingPlaybackSnapshot(
            sessionId = sessionId,
            songId = songId,
            startedAt = startedAt,
            lastPlayedAt = optLong("lastPlayedAt", startedAt).coerceAtLeast(startedAt),
            listenedMs = optLong("listenedMs", 0).coerceAtLeast(0),
            durationSeconds = optInt("durationSeconds", 0).takeIf { it > 0 },
            completed = optBoolean("completed"),
        )
    }

    private companion object {
        const val KEY_SNAPSHOTS = "snapshots"
        const val KEY_CLEAR_PENDING = "clear_pending"
        const val MAX_PENDING = 64
    }
}

data class PendingPlaybackSnapshot(
    val sessionId: String,
    val songId: Long,
    val startedAt: Long,
    val lastPlayedAt: Long,
    val listenedMs: Long,
    val durationSeconds: Int?,
    val completed: Boolean,
)
