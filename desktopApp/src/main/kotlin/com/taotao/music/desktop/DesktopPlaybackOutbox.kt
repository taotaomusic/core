package com.taotao.music.desktop

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/**
 * Windows 端播放上报待办队列。
 *
 * 每个账号独立保存；同一会话只保留累计时长和完成态都不倒退的最新快照。上传成功时按
 * [DesktopPlaybackSnapshot.outboxVersion] 比较后删除，避免网络请求期间的新进度被旧请求覆盖。
 */
class DesktopPlaybackOutbox(root: File) {
    private val file = File(root, "playback-outbox.json")

    @Synchronized
    fun snapshots(accountId: Long?): List<DesktopPlaybackSnapshot> {
        val account = accountId.validAccountId() ?: return emptyList()
        return readAccount(account).snapshots
    }

    @Synchronized
    fun put(snapshot: DesktopPlaybackSnapshot): DesktopPlaybackSnapshot {
        val account = snapshot.accountId.validAccountId() ?: return snapshot
        val document = readDocument()
        val state = document.account(account)
        val existing = state.snapshots.firstOrNull { it.sessionId == snapshot.sessionId }
        val version = (state.outboxVersion + 1L).coerceAtLeast(1L)
        val stored = snapshot.copy(
            startedAt = minOf(existing?.startedAt ?: snapshot.startedAt, snapshot.startedAt),
            lastPlayedAt = maxOf(existing?.lastPlayedAt ?: snapshot.lastPlayedAt, snapshot.lastPlayedAt),
            listenedMs = maxOf(existing?.listenedMs ?: 0L, snapshot.listenedMs),
            durationSeconds = snapshot.durationSeconds ?: existing?.durationSeconds,
            completed = snapshot.completed || existing?.completed == true,
            // 账号水位已经前进时，新建会话直接使用最新版本；已有会话仍保留首次落盘的代际，
            // 由上层在确认它是当前会话后显式 rebase，避免把离线清空前的旧记录复活。
            historyRevision = existing?.historyRevision ?: maxOf(snapshot.historyRevision, state.historyRevision),
            outboxVersion = version,
        )
        document.accounts[account] = state.copy(
            snapshots = retainNewest(state.snapshots.filterNot { it.sessionId == snapshot.sessionId } + stored),
            outboxVersion = version,
        )
        writeDocument(document)
        return stored
    }

    /** 只删除上传时读取到的同一版本；上传期间有新进度时保留下一轮再传。 */
    @Synchronized
    fun removeIfUnchanged(accountId: Long?, uploaded: DesktopPlaybackSnapshot): Boolean {
        val account = accountId.validAccountId() ?: return false
        val document = readDocument()
        val state = document.account(account)
        val current = state.snapshots.firstOrNull { it.sessionId == uploaded.sessionId }
        if (current?.outboxVersion != uploaded.outboxVersion) return false
        document.accounts[account] = state.copy(
            snapshots = state.snapshots.filterNot { it.sessionId == uploaded.sessionId },
        )
        writeDocument(document)
        return true
    }

    @Synchronized
    fun historyRevision(accountId: Long?): Long = accountId.validAccountId()
        ?.let { readAccount(it).historyRevision }
        ?: 0L

    @Synchronized
    fun acceptServerRevision(accountId: Long?, revision: Long): Long {
        val account = accountId.validAccountId() ?: return 0L
        val document = readDocument()
        val state = document.account(account)
        if (state.clearPending) return state.historyRevision
        val merged = maxOf(state.historyRevision, revision.coerceAtLeast(0L))
        if (merged != state.historyRevision) {
            document.accounts[account] = state.copy(historyRevision = merged)
            writeDocument(document)
        }
        return merged
    }

    /**
     * 将指定的当前播放会话迁移到服务端最新历史代际。
     *
     * 只有调用方已经确认该 session 仍属于当前播放时才应调用；其它待办可能是清空前
     * 的离线会话，必须继续保留旧代际并在服务端累计但不显示。
     */
    @Synchronized
    fun rebaseSessionRevision(accountId: Long?, sessionId: String, revision: Long): DesktopPlaybackSnapshot? {
        val account = accountId.validAccountId() ?: return null
        val document = readDocument()
        val state = document.account(account)
        if (state.clearPending) return null
        val normalizedRevision = revision.coerceAtLeast(0L)
        val current = state.snapshots.firstOrNull { it.sessionId == sessionId } ?: return null
        if (current.historyRevision >= normalizedRevision) return current
        val rebased = current.copy(
            historyRevision = normalizedRevision,
            outboxVersion = (state.outboxVersion + 1L).coerceAtLeast(1L),
        )
        document.accounts[account] = state.copy(
            snapshots = state.snapshots.map { if (it.sessionId == sessionId) rebased else it },
            outboxVersion = rebased.outboxVersion,
        )
        writeDocument(document)
        return rebased
    }

    /**
     * 本地先完成清空并持久化同一个 marker。清空之前的快照一并丢弃，之后新建的会话会使用
     * 预测 revision；服务端确认后再统一改为实际 revision。
     */
    @Synchronized
    fun markClearPending(accountId: Long?): Long? {
        val account = accountId.validAccountId() ?: return null
        val document = readDocument()
        val state = document.account(account)
        if (state.clearPending) return state.historyRevision
        val target = (state.historyRevision + 1L).coerceAtLeast(1L)
        document.accounts[account] = state.copy(
            snapshots = emptyList(),
            historyRevision = target,
            clearPending = true,
            clearMarker = UUID.randomUUID().toString(),
        )
        writeDocument(document)
        return target
    }

    @Synchronized
    fun clearMarker(accountId: Long?): String? = accountId.validAccountId()
        ?.let(::readAccount)
        ?.takeIf(AccountState::clearPending)
        ?.clearMarker

    @Synchronized
    fun confirmClear(accountId: Long?, revision: Long): Long {
        val account = accountId.validAccountId() ?: return 0L
        val document = readDocument()
        val state = document.account(account)
        val confirmed = maxOf(state.historyRevision, revision.coerceAtLeast(0L))
        val versionStart = state.outboxVersion
        val rebased = state.snapshots.mapIndexed { index, snapshot ->
            snapshot.copy(historyRevision = confirmed, outboxVersion = versionStart + index + 1L)
        }
        document.accounts[account] = state.copy(
            snapshots = rebased,
            historyRevision = confirmed,
            clearPending = false,
            clearMarker = null,
            outboxVersion = versionStart + rebased.size,
        )
        writeDocument(document)
        return confirmed
    }

    private fun readAccount(accountId: Long): AccountState = readDocument().account(accountId)

    private fun readDocument(): Document = runCatching {
        if (!file.isFile) return Document()
        val root = JSONObject(file.readText())
        val accountsJson = root.optJSONObject("accounts") ?: return Document()
        val accounts = buildMap {
            accountsJson.keys().forEach { key ->
                val accountId = key.toLongOrNull()?.takeIf { it > 0L } ?: return@forEach
                val value = accountsJson.optJSONObject(key) ?: return@forEach
                val snapshotsJson = value.optJSONArray("snapshots") ?: JSONArray()
                val snapshots = (0 until snapshotsJson.length()).mapNotNull { index ->
                    snapshotsJson.optJSONObject(index)?.toSnapshot(accountId)
                }
                put(
                    accountId,
                    AccountState(
                        snapshots = retainNewest(snapshots),
                        historyRevision = value.optLong("historyRevision", 0L).coerceAtLeast(0L),
                        clearPending = value.optBoolean("clearPending"),
                        clearMarker = value.optString("clearMarker").takeIf { it.isNotBlank() && it != "null" },
                        outboxVersion = value.optLong("outboxVersion", 0L).coerceAtLeast(0L),
                    ),
                )
            }
        }
        Document(accounts.toMutableMap())
    }.getOrDefault(Document())

    private fun writeDocument(document: Document) {
        val accounts = JSONObject()
        document.accounts.forEach { (account, state) ->
            accounts.put(
                account.toString(),
                JSONObject()
                    .put("historyRevision", state.historyRevision)
                    .put("clearPending", state.clearPending)
                    .put("clearMarker", state.clearMarker)
                    .put("outboxVersion", state.outboxVersion)
                    .put("snapshots", JSONArray().also { array -> state.snapshots.forEach { array.put(it.toJson()) } }),
            )
        }
        atomicWrite(JSONObject().put("version", 1).put("accounts", accounts).toString())
    }

    private fun atomicWrite(text: String) {
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, ".${file.name}.${System.nanoTime()}.part")
        temporary.writeText(text)
        try {
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: Exception) {
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun JSONObject.toSnapshot(accountId: Long): DesktopPlaybackSnapshot? {
        val sessionId = optString("sessionId")
        val songId = optString("songId").trim()
        val startedAt = optLong("startedAt", 0L)
        if (sessionId.isBlank() || songId.isBlank() || startedAt <= 0L) return null
        return DesktopPlaybackSnapshot(
            sessionId = sessionId,
            accountId = accountId,
            source = optString("source").ifBlank { "tencent" },
            songId = songId,
            startedAt = startedAt,
            lastPlayedAt = optLong("lastPlayedAt", startedAt).coerceAtLeast(startedAt),
            listenedMs = optLong("listenedMs", 0L).coerceAtLeast(0L),
            durationSeconds = optInt("durationSeconds", 0).takeIf { it > 0 },
            completed = optBoolean("completed"),
            historyRevision = optLong("historyRevision", 0L).coerceAtLeast(0L),
            outboxVersion = optLong("outboxVersion", 0L).coerceAtLeast(0L),
        )
    }

    private fun DesktopPlaybackSnapshot.toJson(): JSONObject = JSONObject()
        .put("sessionId", sessionId)
        .put("source", source)
        .put("songId", songId)
        .put("startedAt", startedAt)
        .put("lastPlayedAt", lastPlayedAt)
        .put("listenedMs", listenedMs)
        .put("durationSeconds", durationSeconds)
        .put("completed", completed)
        .put("historyRevision", historyRevision)
        .put("outboxVersion", outboxVersion)

    private fun Document.account(accountId: Long): AccountState = accounts[accountId] ?: AccountState()

    private fun Long?.validAccountId(): Long? = this?.takeIf { it > 0L }

    private fun retainNewest(values: List<DesktopPlaybackSnapshot>): List<DesktopPlaybackSnapshot> = values
        .sortedWith(compareBy<DesktopPlaybackSnapshot> { it.lastPlayedAt }.thenBy { it.outboxVersion })
        .takeLast(MAX_PENDING)

    private data class Document(
        val accounts: MutableMap<Long, AccountState> = mutableMapOf(),
    )

    private data class AccountState(
        val snapshots: List<DesktopPlaybackSnapshot> = emptyList(),
        val historyRevision: Long = 0L,
        val clearPending: Boolean = false,
        val clearMarker: String? = null,
        val outboxVersion: Long = 0L,
    )

    private companion object {
        const val MAX_PENDING = 500
    }
}

data class DesktopPlaybackSnapshot(
    val sessionId: String,
    val accountId: Long,
    val source: String,
    /** 上游数字 ID 或 mid；mid-only 歌曲没有可用数字 ID。 */
    val songId: String,
    val startedAt: Long,
    val lastPlayedAt: Long,
    val listenedMs: Long,
    val durationSeconds: Int?,
    val completed: Boolean,
    val historyRevision: Long,
    val outboxVersion: Long = 0L,
)
