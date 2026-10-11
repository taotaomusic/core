package com.taotao.music.data.db

import com.taotao.music.data.SongCodec
import com.taotao.music.model.Song
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/**
 * 旧 SharedPreferences JSON 桶的解析，专供一次性迁移使用。
 *
 * 全部为纯函数（只依赖 org.json），可在 JVM 单元测试中直接覆盖。
 * 解析失败的条目跳过、不拖累其余数据；歌曲 JSON 一律原文保留，不做解码再编码，
 * 避免迁移过程本身引入字段漂移。
 */
internal object PrefsPayloadParser {

    /** playback_history prefs 里按账号分桶的历史键：account.<id>.entries */
    val HISTORY_KEY_REGEX = Regex("^account\\.(\\d+)\\.entries$")

    /** playback_sync prefs 里按账号分桶的 outbox 键：account.<id>.snapshots */
    val SNAPSHOTS_KEY_REGEX = Regex("^account\\.(\\d+)\\.snapshots$")

    /** playback_sync prefs 里随快照一起迁移的账号级元数据键名。 */
    val SYNC_META_NAMES = setOf(
        "history_revision",
        "clear_pending",
        "clear_target_revision",
        "clear_marker",
        "last_confirmed_clear_marker",
        "last_confirmed_clear_revision",
        "outbox_version",
    )

    private val SYNC_META_KEY_REGEX = Regex("^account\\.(\\d+)\\.(" + SYNC_META_NAMES.joinToString("|") + ")$")

    /** 迁移后的整库完成标记，落在 local_db_meta 里与数据同事务翻转。 */
    const val MIGRATED_FLAG = "prefs_migrated_v1"

    fun parseHistoryRows(accountId: Long, raw: String?): List<PlaybackHistoryRow> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val songJson = item.optString("song").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val song = SongCodec.decode(songJson) ?: return@mapNotNull null
                PlaybackHistoryRow(
                    accountId = accountId,
                    songKey = songKeyOf(song),
                    songJson = songJson,
                    playedAt = item.optLong("playedAtMillis").coerceAtLeast(0L),
                    firstPlayedAt = item.optLong("firstPlayedAtMillis").coerceAtLeast(0L),
                    playCount = item.optInt("playCount", 0).coerceAtLeast(0),
                    completedCount = item.optInt("completedCount", 0).coerceAtLeast(0),
                    totalListenedMs = item.optLong("totalListenedMs", 0).coerceAtLeast(0L),
                )
            }
        }.getOrDefault(emptyList())
            // 同曲理论上不会重复，但旧数据无法保证；保留首个（最新的）出现。
            .distinctBy { it.songKey }
    }

    fun parseOutboxRows(accountId: Long, raw: String?): List<PlaybackOutboxRow> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val sessionId = item.optString("sessionId")
                val source = item.optString("source").trim().ifBlank { "tencent" }
                // songId 在旧版本里是数字，optString 会自然兼容成字符串。
                val songId = item.optString("songId").trim()
                val startedAt = item.optLong("startedAt", 0)
                // 旧版本的无账号全局快照无法安全归属到任意账号，直接隔离丢弃。
                if (sessionId.isBlank() || accountId <= 0L || songId.isBlank() || startedAt <= 0) {
                    return@mapNotNull null
                }
                PlaybackOutboxRow(
                    sessionId = sessionId,
                    accountId = accountId,
                    source = source,
                    songId = songId,
                    startedAt = startedAt,
                    lastPlayedAt = item.optLong("lastPlayedAt", startedAt).coerceAtLeast(startedAt),
                    listenedMs = item.optLong("listenedMs", 0).coerceAtLeast(0L),
                    durationSeconds = item.optInt("durationSeconds", 0).takeIf { it > 0 },
                    completed = item.optBoolean("completed"),
                    historyRevision = item.optLong("historyRevision", 0L).coerceAtLeast(0L),
                    clearMarker = item.optString("clearMarker").takeIf { it.isNotBlank() && it != "null" },
                    outboxVersion = item.optLong("outboxVersion", 0L).coerceAtLeast(0L),
                    playbackCycle = item.optLong("playbackCycle", 0L).coerceAtLeast(0L),
                )
            }
        }.getOrDefault(emptyList())
    }

    /** playback_sync 的账号级元数据键原样搬进 local_db_meta（键名已是 account.<id>.<name>）。 */
    fun parseSyncMetaRow(key: String, value: Any?): LocalDbMetaRow? {
        if (SYNC_META_KEY_REGEX.matchEntire(key) == null) return null
        val converted = when (value) {
            is Boolean -> if (value) "1" else "0"
            is Number -> value.toString()
            is String -> value
            else -> return null
        }
        return LocalDbMetaRow(key, converted)
    }

    fun parseFavoriteIdRows(ids: Set<String>?): List<FavoriteIdRow> = ids.orEmpty()
        .map(::normalizeKey)
        .toSet()
        .map(::FavoriteIdRow)

    fun parseFavoriteSongRows(raw: String?, favoriteKeys: Set<String>): List<FavoriteSongRow> {
        if (raw.isNullOrBlank()) return emptyList()
        val decoded = runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val songJson = array.optJSONObject(index)?.toString() ?: return@mapNotNull null
                val song = SongCodec.decode(songJson) ?: return@mapNotNull null
                val aliases = song.favoriteAliasKeys()
                if (aliases.none(favoriteKeys::contains)) return@mapNotNull null
                val stableKey = aliases.firstOrNull() ?: return@mapNotNull null
                stableKey to songJson
            }
        }.getOrDefault(emptyList())
        return decoded
            .distinctBy { it.first }
            .mapIndexed { index, (songJson, stableKey) -> FavoriteSongRow(stableKey, songJson, index) }
    }

    /** 返回（会话行，消息行）；只含欢迎语的空白对话在迁移时一并清掉，与旧读取规则一致。 */
    fun parseAiRows(raw: String?): Pair<List<AiConversationRow>, List<AiMessageRow>> {
        if (raw.isNullOrBlank()) return emptyList<AiConversationRow>() to emptyList()
        return runCatching {
            val array = JSONArray(raw)
            val conversations = mutableListOf<AiConversationRow>()
            val messages = mutableListOf<AiMessageRow>()
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val id = item.optString("id").takeIf { it.isNotBlank() } ?: continue
                val decoded = decodeMessages(item.optJSONArray("messages") ?: JSONArray())
                if (decoded.none { it.role == "user" }) continue
                conversations += AiConversationRow(
                    conversationId = id,
                    title = item.optString("title").ifBlank { "新对话" },
                    pinned = item.optBoolean("pinned"),
                    position = conversations.size,
                )
                decoded.forEachIndexed { messageIndex, message ->
                    messages += message.copy(conversationId = id, position = messageIndex)
                }
            }
            conversations to messages
        }.getOrDefault(emptyList<AiConversationRow>() to emptyList())
    }

    private fun decodeMessages(items: JSONArray): List<AiMessageRow> = buildList {
        for (index in 0 until items.length()) {
            val item = items.optJSONObject(index) ?: continue
            val text = item.optString("text").trim()
            if (text.isNotEmpty()) {
                add(
                    AiMessageRow(
                        conversationId = "",
                        position = index,
                        role = item.optString("role", "assistant"),
                        text = text,
                        taskId = item.optString("taskId").takeIf { it.isNotBlank() },
                        progress = item.optInt("progress", 0),
                        imageUrl = item.optString("imageUrl").takeIf { it.isNotBlank() },
                        error = item.optString("error").takeIf { it.isNotBlank() },
                    )
                )
            }
        }
    }

    /** 优先新格式整条队列；否则退回旧版单歌曲（兼容格式）。 */
    fun parseQueueRows(queueRaw: String?, legacySongRaw: String?): List<SavedQueueSongRow> {
        SongCodec.decodeList(queueRaw).takeIf { it.isNotEmpty() }?.let { songs ->
            return songs.mapIndexed { index, song -> SavedQueueSongRow(index, SongCodec.encode(song)) }
        }
        val legacy = SongCodec.decode(legacySongRaw) ?: return emptyList()
        return listOf(SavedQueueSongRow(0, SongCodec.encode(legacy)))
    }

    /** 旧版单歌曲格式把进度存在歌曲 JSON 的 positionMs 字段里。 */
    fun parseLegacyPositionMs(raw: String?): Int = runCatching {
        JSONObject(raw ?: return 0).optInt("positionMs", 0)
    }.getOrDefault(0).coerceAtLeast(0)

    /** 与 PlaybackHistoryStore.keyOf 完全一致的稳定键，保证迁移后同曲仍能原地更新。 */
    private fun songKeyOf(song: Song): String {
        val source = song.source.ifBlank { "tencent" }
        val identity = song.remoteId?.takeIf { it > 0L }?.toString()
            ?: song.mid?.trim()?.takeIf { it.isNotBlank() }
            ?: "local:${song.audioUri.orEmpty()}#${song.title}#${song.artist}"
        return "$source:$identity"
    }

    /** 与 FavoritesStore.favoriteKeys 完全一致的别名键集合。 */
    private fun Song.favoriteAliasKeys(): Set<String> = buildSet {
        val normalizedSource = source.trim().lowercase(Locale.ROOT).ifBlank { "tencent" }
        remoteId?.takeIf { it > 0L }?.let { add("$normalizedSource:$it") }
        mid?.trim()?.takeIf { it.isNotBlank() }?.let { add("$normalizedSource:$it") }
    }

    private fun normalizeKey(value: String): String {
        val trimmed = value.trim()
        if (trimmed.isBlank()) return "tencent:"
        return if (trimmed.contains(':')) {
            val separator = trimmed.indexOf(':')
            "${trimmed.substring(0, separator).lowercase(Locale.ROOT)}:${trimmed.substring(separator + 1)}"
        } else {
            "tencent:$trimmed"
        }
    }
}
