package com.taotao.music.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 播放同步待办队列。
 *
 * 队列按服务端用户 ID 分桶。ID 只来自已验证的认证响应或已签名令牌 payload，绝不把
 * access token 本身当作存储键；因此 A 账号离线产生的会话没有任何路径能被 B 账号上传。
 *
 * 每个会话只保留最新累计快照。写入时将 listenedMs 做单调最大合并、completed 做逻辑或，
 * 上传完成则以 [PendingPlaybackSnapshot.outboxVersion] compare-and-remove，避免网络请求期间
 * 新产生的时长被旧上传任务删掉。所有关键写入使用 commit，确保播放器刚落盘就被强杀时仍能补传。
 */
class PlaybackSyncStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("playback_sync", Context.MODE_PRIVATE)

    fun snapshots(accountId: Long?): List<PendingPlaybackSnapshot> = accountId.validAccountId()?.let { account ->
        synchronized(LOCK) { readSnapshots(account) }
    }.orEmpty()

    /**
     * 合并并持久化一条会话快照，返回带最新 [PendingPlaybackSnapshot.outboxVersion] 的权威副本。
     * 调用方必须保存返回值，后续上传才能安全 compare-and-remove。
     */
    fun put(accountId: Long?, snapshot: PendingPlaybackSnapshot): PendingPlaybackSnapshot {
        val account = accountId.validAccountId() ?: return snapshot
        // 入队账号与会话创建时冻结的归属必须一致；不一致宁可保留内存态，也绝不跨账号写入。
        if (snapshot.accountId != account) return snapshot
        return synchronized(LOCK) {
            val current = readSnapshots(account)
            val existing = current.firstOrNull { it.sessionId == snapshot.sessionId }
            val stored = PlaybackSnapshotPolicy.merge(existing, snapshot, nextOutboxVersion(account))
            writeSnapshots(account, current.filterNot { it.sessionId == snapshot.sessionId } + stored)
            stored
        }
    }

    /**
     * 只有队列里的仍是当初上传的同一版本才删除。上传中有新快照时返回 false 并保留它重试。
     */
    fun removeIfUnchanged(accountId: Long?, uploaded: PendingPlaybackSnapshot): Boolean {
        val account = accountId.validAccountId() ?: return false
        return synchronized(LOCK) {
            val current = readSnapshots(account)
            val matched = current.firstOrNull { it.sessionId == uploaded.sessionId }
            if (!PlaybackSnapshotPolicy.shouldRemoveAfterUpload(matched, uploaded)) return@synchronized false
            writeSnapshots(account, current.filterNot { it.sessionId == uploaded.sessionId })
            true
        }
    }

    /** 清空发生前尚未上传的会话不能在稍后把历史重新写回来。 */
    fun discardSnapshots(accountId: Long?) {
        accountId.validAccountId()?.let { account -> synchronized(LOCK) { writeSnapshots(account, emptyList()) } }
    }

    /**
     * 记录一次本地清空。新会话先使用“已知 revision + 1”，清空请求成功后再用服务端实际
     * revision 回填；整个过程不依赖设备时钟。
     */
    fun markClearPending(accountId: Long?): Long? {
        val account = accountId.validAccountId() ?: return null
        return synchronized(LOCK) {
            val alreadyPending = preferences.getBoolean(key(account, KEY_CLEAR_PENDING), false)
            if (alreadyPending) return@synchronized readClearTargetRevision(account)
            val targetRevision = (readHistoryRevision(account) + 1L).coerceAtLeast(1L)
            preferences.edit()
                .putLong(key(account, KEY_HISTORY_REVISION), targetRevision)
                .putBoolean(key(account, KEY_CLEAR_PENDING), true)
                .putLong(key(account, KEY_CLEAR_TARGET_REVISION), targetRevision)
                // 同一清空操作无论网络重试多少次都带同一个 marker，让服务端保持幂等。
                .putString(key(account, KEY_CLEAR_MARKER), UUID.randomUUID().toString())
                .putString(key(account, KEY_SNAPSHOTS), JSONArray().toString())
                .commit()
            targetRevision
        }
    }

    fun isClearPending(accountId: Long?): Boolean = accountId.validAccountId()?.let { account ->
        synchronized(LOCK) { preferences.getBoolean(key(account, KEY_CLEAR_PENDING), false) }
    } == true

    /** 当前待确认清空的稳定请求标识；历史版本升级前的损坏数据没有它时自动补一个。 */
    fun clearMarker(accountId: Long?): String? {
        val account = accountId.validAccountId() ?: return null
        return synchronized(LOCK) {
            if (!preferences.getBoolean(key(account, KEY_CLEAR_PENDING), false)) return@synchronized null
            preferences.getString(key(account, KEY_CLEAR_MARKER), null)?.takeIf { it.isNotBlank() }
                ?: UUID.randomUUID().toString().also { marker ->
                    preferences.edit().putString(key(account, KEY_CLEAR_MARKER), marker).commit()
                }
        }
    }

    /** 新会话仅在本机清空尚待服务端确认时携带该 marker。 */
    fun pendingClearMarker(accountId: Long?): String? = clearMarker(accountId)

    /** 当前新会话应携带的 history revision；有本地清空待办时使用待确认的目标 revision。 */
    fun historyRevision(accountId: Long?): Long = accountId.validAccountId()?.let { account ->
        synchronized(LOCK) { readHistoryRevision(account) }
    } ?: 0L

    /**
     * 仅规范化“本地清空后、服务端确认前”创建的会话 revision。DELETE 可能确认出比本地预估
     * 更高的 revision，此时内存会话还没达到 3 秒、尚未入队，也必须升级到确认代际；普通
     * 旧会话没有同一 clear marker，绝不会被跨设备清空错误地升级。
     */
    fun normalizeClearTargetRevision(accountId: Long?, snapshot: PendingPlaybackSnapshot): PendingPlaybackSnapshot {
        val account = accountId.validAccountId() ?: return snapshot
        if (snapshot.accountId != account) return snapshot
        return synchronized(LOCK) {
            val marker = snapshot.clearMarker ?: return@synchronized snapshot
            val confirmedMarker = preferences.getString(key(account, KEY_LAST_CONFIRMED_CLEAR_MARKER), null)
            if (marker != confirmedMarker) return@synchronized snapshot
            val confirmed = preferences.getLong(key(account, KEY_LAST_CONFIRMED_CLEAR_REVISION), snapshot.historyRevision)
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
        return synchronized(LOCK) {
            if (!preferences.getBoolean(key(account, KEY_CLEAR_PENDING), false)) {
                val currentRevision = readHistoryRevision(account)
                val merged = maxOf(currentRevision, safeServerRevision)
                if (merged != currentRevision) {
                    preferences.edit().putLong(key(account, KEY_HISTORY_REVISION), merged).commit()
                }
                return@synchronized merged
            }

            readClearTargetRevision(account)
        }
    }

    /**
     * 服务端确认清空后，清空之后创建的会话统一改为服务端实际 revision。
     * 老服务端无 revision 时退回本地预测值，仍可保持旧协议下的正常同步。
     */
    fun confirmClear(accountId: Long?, serverRevision: Long): Long {
        val account = accountId.validAccountId() ?: return 0L
        return synchronized(LOCK) {
            val targetRevision = readClearTargetRevision(account)
            val confirmedRevision = maxOf(targetRevision, serverRevision.coerceAtLeast(0L))
            val marker = preferences.getString(key(account, KEY_CLEAR_MARKER), null)
            val transformed = readSnapshots(account).map { snapshot ->
                if (snapshot.clearMarker == marker && marker != null) snapshot.copy(
                    historyRevision = confirmedRevision,
                    clearMarker = null,
                    outboxVersion = nextOutboxVersion(account),
                ) else snapshot
            }
            preferences.edit()
                .putLong(key(account, KEY_HISTORY_REVISION), confirmedRevision)
                // 只保留最近一次确认映射；旧会话没有同一个 marker，无法被误升级。
                .putString(key(account, KEY_LAST_CONFIRMED_CLEAR_MARKER), marker)
                .putLong(key(account, KEY_LAST_CONFIRMED_CLEAR_REVISION), confirmedRevision)
                .remove(key(account, KEY_CLEAR_PENDING))
                .remove(key(account, KEY_CLEAR_TARGET_REVISION))
                .remove(key(account, KEY_CLEAR_MARKER))
                .putString(key(account, KEY_SNAPSHOTS), encodeSnapshots(transformed))
                .commit()
            confirmedRevision
        }
    }

    private fun readSnapshots(account: Long): List<PendingPlaybackSnapshot> = runCatching {
        val values = JSONArray(preferences.getString(key(account, KEY_SNAPSHOTS), "[]"))
        (0 until values.length()).mapNotNull { index -> values.optJSONObject(index)?.toSnapshot() }
    }.getOrDefault(emptyList())

    private fun writeSnapshots(account: Long, items: List<PendingPlaybackSnapshot>) {
        preferences.edit().putString(key(account, KEY_SNAPSHOTS), encodeSnapshots(items)).commit()
    }

    private fun encodeSnapshots(items: List<PendingPlaybackSnapshot>): String = JSONArray().apply {
        PlaybackSnapshotPolicy.retainNewest(items, MAX_PENDING).forEach { put(it.toJson()) }
    }.toString()

    private fun nextOutboxVersion(account: Long): Long {
        val versionKey = key(account, KEY_OUTBOX_VERSION)
        val next = (preferences.getLong(versionKey, 0L) + 1L).coerceAtLeast(1L)
        // 与快照本身同一把进程锁保护；真正的快照 write 会紧随其后 commit。
        preferences.edit().putLong(versionKey, next).commit()
        return next
    }

    private fun readHistoryRevision(account: Long): Long = preferences
        .getLong(key(account, KEY_HISTORY_REVISION), 0L)
        .coerceAtLeast(0L)

    private fun readClearTargetRevision(account: Long): Long = preferences
        .getLong(key(account, KEY_CLEAR_TARGET_REVISION), readHistoryRevision(account) + 1L)
        .coerceAtLeast(1L)

    private fun key(account: Long, name: String): String = "account.$account.$name"

    private fun Long?.validAccountId(): Long? = this?.takeIf { it > 0L }

    private fun PendingPlaybackSnapshot.toJson() = JSONObject()
        .put("sessionId", sessionId)
        .put("accountId", accountId)
        .put("source", source)
        .put("songId", songId)
        .put("startedAt", startedAt)
        .put("lastPlayedAt", lastPlayedAt)
        .put("listenedMs", listenedMs)
        .put("durationSeconds", durationSeconds)
        .put("completed", completed)
        .put("historyRevision", historyRevision)
        .put("clearMarker", clearMarker)
        .put("outboxVersion", outboxVersion)
        .put("playbackCycle", playbackCycle)

    private fun JSONObject.toSnapshot(): PendingPlaybackSnapshot? {
        val sessionId = optString("sessionId")
        val accountId = optLong("accountId", 0L)
        // 旧版本写入的是数字，JSONObject.optString 会自然兼容；新版本允许 mid。
        val songId = optString("songId").trim()
        val source = optString("source").trim().ifBlank { "tencent" }
        val startedAt = optLong("startedAt", 0)
        // v1.0.102 及以前的全局队列没有 accountId，不能安全迁移到任意账号，直接隔离丢弃。
        if (sessionId.isBlank() || accountId <= 0L || songId.isBlank() || startedAt <= 0) return null
        return PendingPlaybackSnapshot(
            sessionId = sessionId,
            accountId = accountId,
            source = source,
            songId = songId,
            startedAt = startedAt,
            lastPlayedAt = optLong("lastPlayedAt", startedAt).coerceAtLeast(startedAt),
            listenedMs = optLong("listenedMs", 0).coerceAtLeast(0),
            durationSeconds = optInt("durationSeconds", 0).takeIf { it > 0 },
            completed = optBoolean("completed"),
            historyRevision = optLong("historyRevision", 0L).coerceAtLeast(0L),
            clearMarker = optString("clearMarker").takeIf { it.isNotBlank() && it != "null" },
            outboxVersion = optLong("outboxVersion", 0L).coerceAtLeast(0L),
            playbackCycle = optLong("playbackCycle", 0L).coerceAtLeast(0L),
        )
    }

    private companion object {
        const val KEY_SNAPSHOTS = "snapshots"
        const val KEY_HISTORY_REVISION = "history_revision"
        const val KEY_CLEAR_PENDING = "clear_pending"
        const val KEY_CLEAR_TARGET_REVISION = "clear_target_revision"
        const val KEY_CLEAR_MARKER = "clear_marker"
        const val KEY_LAST_CONFIRMED_CLEAR_MARKER = "last_confirmed_clear_marker"
        const val KEY_LAST_CONFIRMED_CLEAR_REVISION = "last_confirmed_clear_revision"
        const val KEY_OUTBOX_VERSION = "outbox_version"
        const val MAX_PENDING = 500
        val LOCK = Any()
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
