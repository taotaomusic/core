package com.taotao.music.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 播放会话的可靠落盘与补传边界。
 *
 * Compose 页面只负责把播放器事件交给此协调器；结束快照、账号隔离 outbox、清空代际与网络
 * 补传都在 data 层完成，因此它们属于可 DEX 热修的业务逻辑。
 */
class PlaybackSyncCoordinator(
    private val store: PlaybackSyncStore,
    private val api: TencentMusicApi,
    private val deviceId: String,
    /** 当前经认证的服务端用户 ID。换号后旧 ID 的队列绝不允许走新账号令牌上传。 */
    private val accountIdProvider: () -> Long?,
) {
    private val syncMutex = Mutex()

    /**
     * 播放结束、暂停或切歌时先同步写入 outbox，网络不能阻塞播放器事件。
     * 返回的是合并后的权威快照，调用方用它替换内存态即可避免 completed 被旧协程回写。
     */
    fun persistTerminalSnapshot(
        snapshot: PendingPlaybackSnapshot,
        completed: Boolean,
        nowMillis: Long = System.currentTimeMillis(),
    ): PendingPlaybackSnapshot {
        val normalized = store.normalizeClearTargetRevision(snapshot.accountId, snapshot)
        val terminal = normalized.copy(
            lastPlayedAt = maxOf(normalized.lastPlayedAt, nowMillis),
            completed = normalized.completed || completed,
            // historyRevision 在会话创建时冻结。若跨设备此后清空，旧会话必须保持旧代际，
            // 由服务端隐藏而不是被客户端“升级”成清空后的新播放。
            historyRevision = normalized.historyRevision,
        )
        // 用会话创建时冻结的 accountId，而不是“此刻谁登录着”。A 切换成 B 后旧协程 finally
        // 仍只会写回 A 桶，稍后 A 再登录才会补传。
        return store.put(snapshot.accountId, terminal)
    }

    /**
     * 在创建新远端会话前获取服务端最新 generation。网络不可用时抛给调用方回退本地值，
     * 播放本身不会被同步阻塞。
     */
    suspend fun refreshHistoryRevision(): Long {
        val accountId = accountIdProvider() ?: return 0L
        return withContext(Dispatchers.IO) {
            // 防止登录/退出过程切换令牌后，拿 A 的状态去污染 B 的本地桶。
            if (!PlaybackSnapshotPolicy.canUploadForAccount(accountId, accountIdProvider())) {
                return@withContext store.historyRevision(accountId)
            }
            val remote = api.playbackHistoryState()
            if (!PlaybackSnapshotPolicy.canUploadForAccount(accountId, accountIdProvider())) {
                return@withContext store.historyRevision(accountId)
            }
            store.acceptServerHistoryRevision(accountId, remote.revision)
        }
    }

    /** 取新会话应冻结的 history revision；在线时应先调用 [refreshHistoryRevision]。 */
    fun historyRevision(): Long = store.historyRevision(accountIdProvider())

    /**
     * 先同步清空命令，再上传每个会话的最新快照；服务端确认成功后只 compare-and-remove
     * 对应版本。多处生命周期事件同时要求同步时由 Mutex 串行，不会产生“旧上传删新快照”。
     */
    suspend fun syncPending() = syncMutex.withLock {
        val accountId = accountIdProvider() ?: return@withLock
        withContext(Dispatchers.IO) {
            if (!PlaybackSnapshotPolicy.canUploadForAccount(accountId, accountIdProvider())) return@withContext

            // 每轮上传先校准远端 generation。清空尚待确认时 store 会保留自身 marker/目标
            // revision，不会因为这次 GET 将同一清空错误升级为第二次清空。
            runCatching { api.playbackHistoryState() }
                .onSuccess { state ->
                    if (PlaybackSnapshotPolicy.canUploadForAccount(accountId, accountIdProvider())) {
                        store.acceptServerHistoryRevision(accountId, state.revision)
                    }
                }

            if (!PlaybackSnapshotPolicy.canUploadForAccount(accountId, accountIdProvider())) return@withContext
            if (store.isClearPending(accountId)) {
                // marker 在本地清空第一次发生时生成并持久化，网络响应丢失后的重试仍是同一操作。
                val state = api.clearRecentPlayback(store.clearMarker(accountId))
                if (!PlaybackSnapshotPolicy.canUploadForAccount(accountId, accountIdProvider())) return@withContext
                store.confirmClear(accountId, state.revision)
            }

            store.snapshots(accountId).forEach { snapshot ->
                if (!PlaybackSnapshotPolicy.canUploadForAccount(accountId, accountIdProvider())) return@withContext
                val result = api.reportPlayback(
                    sessionId = snapshot.sessionId,
                    deviceId = deviceId,
                    source = snapshot.source,
                    songId = snapshot.songId,
                    startedAt = snapshot.startedAt,
                    lastPlayedAt = snapshot.lastPlayedAt,
                    listenedMs = snapshot.listenedMs,
                    durationSeconds = snapshot.durationSeconds,
                    completed = snapshot.completed,
                    historyRevision = snapshot.historyRevision,
                )
                if (!PlaybackSnapshotPolicy.canUploadForAccount(accountId, accountIdProvider())) return@withContext
                store.removeIfUnchanged(accountId, snapshot)
                // 上报返回的 current revision 能覆盖“拉状态后另一台设备又清空”的竞态；
                // 新会话下次开始时会读取这里缓存的 generation。
                store.acceptServerHistoryRevision(accountId, result.currentHistoryRevision)
            }
        }
    }

    fun pendingSnapshots(): List<PendingPlaybackSnapshot> = store.snapshots(accountIdProvider())

    /** 清空与所有待上传会话在同一账户桶内原子落盘，离线时下次登录此账号再补发。 */
    fun markClearPending() {
        store.markClearPending(accountIdProvider())
    }
}
