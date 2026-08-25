package com.taotao.music.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 播放会话的可靠落盘与补传边界。
 *
 * Compose 页面只负责把播放器事件交给此协调器；结束快照、离线队列、清空优先级与网络补传
 * 都在 data 层完成，因此它们属于可 DEX 热修的业务逻辑。
 */
class PlaybackSyncCoordinator(
    private val store: PlaybackSyncStore,
    private val api: TencentMusicApi,
    private val deviceId: String,
) {
    /** 播放结束、暂停或切歌时先同步写入 outbox，网络不能阻塞播放器事件。 */
    fun persistTerminalSnapshot(
        snapshot: PendingPlaybackSnapshot,
        completed: Boolean,
        nowMillis: Long = System.currentTimeMillis(),
    ): PendingPlaybackSnapshot {
        val terminal = snapshot.copy(
            lastPlayedAt = maxOf(snapshot.lastPlayedAt, nowMillis),
            completed = snapshot.completed || completed,
        )
        store.put(terminal)
        return terminal
    }

    /** 先同步清空命令，再上传每个会话的最新快照；服务端确认成功后才移出 outbox。 */
    suspend fun syncPending() {
        withContext(Dispatchers.IO) {
            if (store.isClearPending()) {
                api.clearRecentPlayback()
                store.confirmClear()
            }
            store.snapshots().forEach { snapshot ->
                api.reportPlayback(
                    sessionId = snapshot.sessionId,
                    deviceId = deviceId,
                    songId = snapshot.songId,
                    startedAt = snapshot.startedAt,
                    lastPlayedAt = snapshot.lastPlayedAt,
                    listenedMs = snapshot.listenedMs,
                    durationSeconds = snapshot.durationSeconds,
                    completed = snapshot.completed,
                )
                store.remove(snapshot.sessionId)
            }
        }
    }

    fun pendingSnapshots(): List<PendingPlaybackSnapshot> = store.snapshots()

    fun markClearPending() {
        store.markClearPending()
        store.discardSnapshots()
    }
}
