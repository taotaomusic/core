package com.taotao.music.data

import android.content.Context
import com.taotao.music.data.db.LocalDb
import com.taotao.music.data.db.LocalDbMetaRow
import com.taotao.music.data.db.PlaybackOutboxRow
import java.util.UUID
import java.util.concurrent.Callable

/**
 * 播放同步待办队列，落在本地 Room 库的 playback_outbox 表，账号级 revision/清空标记
 * 等元数据在 local_db_meta 表（键名沿用旧的 account.<id>.<name>）。
 *
 * 队列按服务端用户 ID 分桶。ID 只来自已验证的认证响应或已签名令牌 payload，绝不把
 * access token 本身当作存储键；因此 A 账号离线产生的会话没有任何路径能被 B 账号上传。
 *
 * 每个会话只保留最新累计快照。写入时将 listenedMs 做单调最大合并、completed 做逻辑或，
 * 上传完成则以 [PendingPlaybackSnapshot.outboxVersion] compare-and-remove，避免网络请求期间
 * 新产生的时长被旧上传任务删掉。
 *
 * 崩溃安全沿用旧约定：关键写入必须同步落盘后才返回（SQLite 事务提交即持久，等价于旧
 * SharedPreferences 的 commit），因此调用方必须在 IO 线程；主线程调用会被 Room 直接拒绝，
 * 这正是想要的显式失败。
 */
class PlaybackSyncStore(private val context: Context) {
    private val lock = Any()
    private val db by lazy { LocalDb.ready(context) }

    /**
     * 带返回值的事务。Room 的 runInTransaction 有 Runnable 与 Callable 两个重载，Kotlin
     * 会把带返回值的 lambda 解析到 Runnable（要求 lambda 返回 Unit）导致编译失败，
     * 必须显式走 Callable；纯 Unit 的写事务不受影响，可直接用 runInTransaction。
     */
    private fun <T> inTransaction(body: () -> T): T = db.runInTransaction(Callable(body))

    fun snapshots(accountId: Long?): List<PendingPlaybackSnapshot> = accountId.validAccountId()?.let { account ->
        synchronized(lock) {
            db.playbackOutboxDao().listByAccount(account).mapNotNull { it.toSnapshot() }
        }
    }.orEmpty()

    /**
     * 合并并持久化一条会话快照，返回带最新 [PendingPlaybackSnapshot.outboxVersion] 的权威副本。
     * 调用方必须保存返回值，后续上传才能安全 compare-and-remove。
     */
    fun put(accountId: Long?, snapshot: PendingPlaybackSnapshot): PendingPlaybackSnapshot {
        val account = accountId.validAccountId() ?: return snapshot
        // 入队账号与会话创建时冻结的归属必须一致；不一致宁可保留内存态，也绝不跨账号写入。
        if (snapshot.accountId != account) return snapshot
        return synchronized(lock) {
            inTransaction {
                val dao = db.playbackOutboxDao()
                val existing = dao.getBySession(snapshot.sessionId)?.toSnapshot()
                val stored = PlaybackSnapshotPolicy.merge(existing, snapshot, nextOutboxVersion(account))
                dao.upsert(stored.toRow())
                dao.trimByAccount(account, MAX_PENDING)
                stored
            }
        }
    }

    /**
     * 只有队列里的仍是当初上传的同一版本才删除。上传中有新快照时返回 false 并保留它重试。
     */
    fun removeIfUnchanged(accountId: Long?, uploaded: PendingPlaybackSnapshot): Boolean {
        val account = accountId.validAccountId() ?: return false
        return synchronized(lock) {
            inTransaction {
                val matched = db.playbackOutboxDao().getBySession(uploaded.sessionId)?.toSnapshot()
                if (!PlaybackSnapshotPolicy.shouldRemoveAfterUpload(matched, uploaded)) {
                    false
                } else {
                    db.playbackOutboxDao().deleteBySession(uploaded.sessionId)
                    true
                }
            }
        }
    }

    /** 清空发生前尚未上传的会话不能在稍后把历史重新写回来。 */
    fun discardSnapshots(accountId: Long?) {
        accountId.validAccountId()?.let { account ->
            synchronized(lock) { db.playbackOutboxDao().deleteByAccount(account) }
        }
    }

    /**
     * 记录一次本地清空。新会话先使用“已知 revision + 1”，清空请求成功后再用服务端实际
     * revision 回填；整个过程不依赖设备时钟。
     */
    fun markClearPending(accountId: Long?): Long? {
        val account = accountId.validAccountId() ?: return null
        return synchronized(lock) {
            inTransaction {
                if (readMetaBool(account, KEY_CLEAR_PENDING)) {
                    return@inTransaction readClearTargetRevision(account)
                }
                val targetRevision = (readMetaLong(account, KEY_HISTORY_REVISION) + 1L).coerceAtLeast(1L)
                writeMetaLong(account, KEY_HISTORY_REVISION, targetRevision)
                writeMetaBool(account, KEY_CLEAR_PENDING, true)
                writeMetaLong(account, KEY_CLEAR_TARGET_REVISION, targetRevision)
                // 同一清空操作无论网络重试多少次都带同一个 marker，让服务端保持幂等。
                writeMetaString(account, KEY_CLEAR_MARKER, UUID.randomUUID().toString())
                // 清空即作废所有尚未上传的会话（旧实现同步把快照桶写成空数组）。
                db.playbackOutboxDao().deleteByAccount(account)
                targetRevision
            }
        }
    }

    fun isClearPending(accountId: Long?): Boolean = accountId.validAccountId()?.let { account ->
        synchronized(lock) { readMetaBool(account, KEY_CLEAR_PENDING) }
    } == true

    /** 当前待确认清空的稳定请求标识；历史版本升级前的损坏数据没有它时自动补一个。 */
    fun clearMarker(accountId: Long?): String? {
        val account = accountId.validAccountId() ?: return null
        return synchronized(lock) {
            if (!readMetaBool(account, KEY_CLEAR_PENDING)) return@synchronized null
            readMetaString(account, KEY_CLEAR_MARKER)?.takeIf { it.isNotBlank() }
                ?: UUID.randomUUID().toString().also { marker ->
                    writeMetaString(account, KEY_CLEAR_MARKER, marker)
                }
        }
    }

    /** 新会话仅在本机清空尚待服务端确认时携带该 marker。 */
    fun pendingClearMarker(accountId: Long?): String? = clearMarker(accountId)

    /** 当前新会话应携带的 history revision；有本地清空待办时使用待确认的目标 revision。 */
    fun historyRevision(accountId: Long?): Long = accountId.validAccountId()?.let { account ->
        synchronized(lock) { readMetaLong(account, KEY_HISTORY_REVISION) }
    } ?: 0L

    /**
     * 仅规范化“本地清空后、服务端确认前”创建的会话 revision。DELETE 可能确认出比本地预估
     * 更高的 revision，此时内存会话还没达到 3 秒、尚未入队，也必须升级到确认代际；普通
     * 旧会话没有同一 clear marker，绝不会被跨设备清空错误地升级。
     */
    fun normalizeClearTargetRevision(accountId: Long?, snapshot: PendingPlaybackSnapshot): PendingPlaybackSnapshot {
        val account = accountId.validAccountId() ?: return snapshot
        if (snapshot.accountId != account) return snapshot
        return synchronized(lock) {
            val marker = snapshot.clearMarker ?: return@synchronized snapshot
            val confirmedMarker = readMetaString(account, KEY_LAST_CONFIRMED_CLEAR_MARKER)
            if (marker != confirmedMarker) return@synchronized snapshot
            val confirmed = readMetaLong(account, KEY_LAST_CONFIRMED_CLEAR_REVISION)
            snapshot.copy(historyRevision = confirmed, clearMarker = null)
        }
    }

    /**
     * 接受服务端最新 revision。清空待确认时绝不能用一次 GET 的状态去推高本地清空目标：
     * 那会把同一个清空重试误判成连续多个清空。待 DELETE 返回明确确认后才调用 [confirmClear]。
     */
    fun acceptServerHistoryRevision(accountId: Long?, serverRevision: Long): Long {
        val account = accountId.validAccountId() ?: return 0L
        val safeServerRevision = serverRevision.coerceAtLeast(0L)
        return synchronized(lock) {
            if (!readMetaBool(account, KEY_CLEAR_PENDING)) {
                inTransaction {
                    val currentRevision = readMetaLong(account, KEY_HISTORY_REVISION)
                    val merged = maxOf(currentRevision, safeServerRevision)
                    if (merged != currentRevision) {
                        writeMetaLong(account, KEY_HISTORY_REVISION, merged)
                    }
                    merged
                }
            } else {
                readClearTargetRevision(account)
            }
        }
    }

    /**
     * 服务端确认清空后，清空之后创建的会话统一改为服务端实际 revision。
     * 老服务端无 revision 时退回本地预测值，仍可保持旧协议下的正常同步。
     */
    fun confirmClear(accountId: Long?, serverRevision: Long): Long {
        val account = accountId.validAccountId() ?: return 0L
        return synchronized(lock) {
            inTransaction {
                val targetRevision = readClearTargetRevision(account)
                val confirmedRevision = maxOf(targetRevision, serverRevision.coerceAtLeast(0L))
                val marker = readMetaString(account, KEY_CLEAR_MARKER)
                val dao = db.playbackOutboxDao()
                val transformed = dao.listByAccount(account).mapNotNull { row ->
                    val snapshot = row.toSnapshot() ?: return@mapNotNull null
                    if (marker != null && snapshot.clearMarker == marker) {
                        snapshot.copy(
                            historyRevision = confirmedRevision,
                            clearMarker = null,
                            // 升级过的会话换新版本号，正在途中的旧上传不会把它误删。
                            outboxVersion = nextOutboxVersion(account),
                        )
                    } else {
                        snapshot
                    }
                }
                dao.deleteByAccount(account)
                transformed.forEach { dao.upsert(it.toRow()) }
                writeMetaLong(account, KEY_HISTORY_REVISION, confirmedRevision)
                // 只保留最近一次确认映射；旧会话没有同一个 marker，无法被误升级。
                if (marker == null) {
                    db.localDbMetaDao().delete(key(account, KEY_LAST_CONFIRMED_CLEAR_MARKER))
                } else {
                    writeMetaString(account, KEY_LAST_CONFIRMED_CLEAR_MARKER, marker)
                }
                writeMetaLong(account, KEY_LAST_CONFIRMED_CLEAR_REVISION, confirmedRevision)
                db.localDbMetaDao().delete(key(account, KEY_CLEAR_PENDING))
                db.localDbMetaDao().delete(key(account, KEY_CLEAR_TARGET_REVISION))
                db.localDbMetaDao().delete(key(account, KEY_CLEAR_MARKER))
                confirmedRevision
            }
        }
    }

    // ---- meta 读写（outbox 版本、revision、清空标记都走 local_db_meta 表） ----

    /**
     * outbox 版本号 +1 并落盘。调用方必须在同一事务里紧跟着写快照本体——版本号与快照
     * 一次性原子提交（旧实现是两次独立 commit，这里顺带合并了）。
     */
    private fun nextOutboxVersion(account: Long): Long {
        val next = (readMetaLong(account, KEY_OUTBOX_VERSION) + 1L).coerceAtLeast(1L)
        writeMetaLong(account, KEY_OUTBOX_VERSION, next)
        return next
    }

    private fun readClearTargetRevision(account: Long): Long =
        readMetaLong(account, KEY_CLEAR_TARGET_REVISION).takeIf { it >= 1L }
            ?: (readMetaLong(account, KEY_HISTORY_REVISION) + 1L).coerceAtLeast(1L)

    private fun readMetaString(account: Long, name: String): String? =
        db.localDbMetaDao().getValue(key(account, name))

    private fun readMetaLong(account: Long, name: String): Long =
        readMetaString(account, name)?.toLongOrNull() ?: 0L

    private fun readMetaBool(account: Long, name: String): Boolean =
        readMetaString(account, name) == "1"

    private fun writeMetaString(account: Long, name: String, value: String) {
        db.localDbMetaDao().upsert(LocalDbMetaRow(key(account, name), value))
    }

    private fun writeMetaLong(account: Long, name: String, value: Long) =
        writeMetaString(account, name, value.toString())

    private fun writeMetaBool(account: Long, name: String, value: Boolean) =
        writeMetaString(account, name, if (value) "1" else "0")

    private fun key(account: Long, name: String): String = "account.$account.$name"

    // ---- 行与领域对象的互转 ----

    private fun PendingPlaybackSnapshot.toRow() = PlaybackOutboxRow(
        sessionId = sessionId,
        accountId = accountId,
        source = source,
        songId = songId,
        startedAt = startedAt,
        lastPlayedAt = lastPlayedAt,
        listenedMs = listenedMs,
        durationSeconds = durationSeconds,
        completed = completed,
        historyRevision = historyRevision,
        clearMarker = clearMarker,
        outboxVersion = outboxVersion,
        playbackCycle = playbackCycle,
    )

    private fun PlaybackOutboxRow.toSnapshot(): PendingPlaybackSnapshot? {
        // 表里都是本类写入的完整行；防御性校验与旧 JSON 解析规则保持一致。
        if (sessionId.isBlank() || accountId <= 0L || songId.isBlank() || startedAt <= 0) return null
        return PendingPlaybackSnapshot(
            sessionId = sessionId,
            accountId = accountId,
            source = source,
            songId = songId,
            startedAt = startedAt,
            lastPlayedAt = lastPlayedAt.coerceAtLeast(startedAt),
            listenedMs = listenedMs.coerceAtLeast(0L),
            durationSeconds = durationSeconds,
            completed = completed,
            historyRevision = historyRevision.coerceAtLeast(0L),
            clearMarker = clearMarker,
            outboxVersion = outboxVersion.coerceAtLeast(0L),
            playbackCycle = playbackCycle.coerceAtLeast(0L),
        )
    }

    private fun Long?.validAccountId(): Long? = this?.takeIf { it > 0L }

    private companion object {
        const val KEY_HISTORY_REVISION = "history_revision"
        const val KEY_CLEAR_PENDING = "clear_pending"
        const val KEY_CLEAR_TARGET_REVISION = "clear_target_revision"
        const val KEY_CLEAR_MARKER = "clear_marker"
        const val KEY_LAST_CONFIRMED_CLEAR_MARKER = "last_confirmed_clear_marker"
        const val KEY_LAST_CONFIRMED_CLEAR_REVISION = "last_confirmed_clear_revision"
        const val KEY_OUTBOX_VERSION = "outbox_version"

        /** 待上传会话上限；超出时按最后播放时间淘汰最旧的会话。 */
        const val MAX_PENDING = 500
    }
}

data class PendingPlaybackSnapshot(
    val sessionId: String,
    /** 创建该远端会话时的服务端用户 ID；后续登录切换不能改变它。 */
    val accountId: Long,
    /** 音乐来源；与 [songId] 一起构成稳定歌曲身份。 */
    val source: String = "tencent",
    /** 可为数字 ID，也可为上游 mid，不能再用 Long 限制跨端契约。 */
    val songId: String,
    val startedAt: Long,
    val lastPlayedAt: Long,
    val listenedMs: Long,
    val durationSeconds: Int?,
    val completed: Boolean,
    /** 会话创建时冻结的服务端最近播放 generation。 */
    val historyRevision: Long = 0L,
    /** 仅清空后到确认前创建的会话携带，解决确认 revision 高于本地预测的因果窗口。 */
    val clearMarker: String? = null,
    /** outbox 条目版本，仅用于客户端 compare-and-remove，不上传。 */
    val outboxVersion: Long = 0L,
    /** 播放器本次重新开始的循环编号，仅用于界面判断重播边界，不上传。 */
    val playbackCycle: Long = 0L,
)

/**
 * 纯 Kotlin 的 outbox 合并规则，单独抽出便于 JVM 单元测试覆盖并发快照与完成态倒写。
 */
object PlaybackSnapshotPolicy {
    /** 同一会话只能前进：时间、累计时长、完成标记都不会被旧协程回写。 */
    fun merge(
        existing: PendingPlaybackSnapshot?,
        incoming: PendingPlaybackSnapshot,
        outboxVersion: Long,
    ): PendingPlaybackSnapshot {
        if (existing == null) return incoming.copy(outboxVersion = outboxVersion.coerceAtLeast(1L))
        require(existing.accountId == incoming.accountId) { "同一 sessionId 不能跨账号合并" }
        return incoming.copy(
            accountId = existing.accountId,
            startedAt = minOf(existing.startedAt, incoming.startedAt),
            lastPlayedAt = maxOf(existing.lastPlayedAt, incoming.lastPlayedAt),
            listenedMs = maxOf(existing.listenedMs, incoming.listenedMs),
            durationSeconds = incoming.durationSeconds ?: existing.durationSeconds,
            completed = existing.completed || incoming.completed,
            // 会话 generation 在创建时冻结，之后跨设备清空也不能把旧会话升级成新历史。
            historyRevision = existing.historyRevision,
            clearMarker = existing.clearMarker ?: incoming.clearMarker,
            outboxVersion = outboxVersion.coerceAtLeast(existing.outboxVersion + 1L),
            playbackCycle = maxOf(existing.playbackCycle, incoming.playbackCycle),
        )
    }

    /** 超过容量时按最后播放时间淘汰最旧的会话，而不是按 JSON 插入顺序静默丢数据。 */
    fun retainNewest(items: List<PendingPlaybackSnapshot>, limit: Int): List<PendingPlaybackSnapshot> = items
        .sortedWith(compareBy<PendingPlaybackSnapshot> { it.lastPlayedAt }.thenBy { it.outboxVersion })
        .takeLast(limit.coerceAtLeast(1))

    /** 上传成功只可删除当初读取到的同一 outbox 版本。 */
    fun shouldRemoveAfterUpload(current: PendingPlaybackSnapshot?, uploaded: PendingPlaybackSnapshot): Boolean =
        current?.sessionId == uploaded.sessionId && current.outboxVersion == uploaded.outboxVersion

    /** 账号分桶的最小安全规则，供协调器和单元测试共用。 */
    fun canUploadForAccount(bucketAccountId: Long?, activeAccountId: Long?): Boolean =
        bucketAccountId != null && bucketAccountId > 0L && bucketAccountId == activeAccountId

    /** 同曲仅在同一播放器循环内恢复会话；重播、单曲循环都必须另起会话。 */
    fun shouldReuseSession(
        session: PendingPlaybackSnapshot?,
        accountId: Long?,
        source: String,
        songId: String,
        playbackCycle: Long,
    ): Boolean = session?.let {
        it.accountId == accountId &&
            it.source == source &&
            it.songId == songId &&
            it.playbackCycle == playbackCycle
    } == true

    /** 旧调用方兼容重载；新代码应显式传来源和字符串 ID。 */
    fun shouldReuseSession(
        session: PendingPlaybackSnapshot?,
        accountId: Long?,
        songId: Long,
        playbackCycle: Long,
    ): Boolean = shouldReuseSession(session, accountId, "tencent", songId.toString(), playbackCycle)

    /** 清空后旧 effect 的 finally 不能再写回会话或本地历史。 */
    fun shouldPersistAfterClear(sessionClearEpoch: Int, currentClearEpoch: Int): Boolean =
        sessionClearEpoch == currentClearEpoch
}
