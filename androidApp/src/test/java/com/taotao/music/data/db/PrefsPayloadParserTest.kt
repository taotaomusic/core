package com.taotao.music.data.db

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.json.JSONArray
import org.json.JSONObject

/** SharedPreferences → Room 一次性迁移的解析回归测试（纯 JVM，org.json 走真实实现）。 */
class PrefsPayloadParserTest {

    private fun songJson(remoteId: Long = 0, mid: String? = null, source: String = "tencent"): String {
        val obj = JSONObject().put("title", "歌").put("source", source)
        if (remoteId > 0) obj.put("remoteId", remoteId)
        if (mid != null) obj.put("mid", mid)
        return obj.toString()
    }

    @Test
    fun `历史条目解析出稳定键并按同曲去重`() {
        val songJson = songJson(remoteId = 42)
        val array = JSONArray()
            .put(JSONObject().put("song", songJson).put("playedAtMillis", 100L).put("playCount", 3))
            .put(JSONObject().put("song", songJson(remoteId = 42)).put("playedAtMillis", 200L))

        val rows = PrefsPayloadParser.parseHistoryRows(7, array.toString())

        assertEquals(1, rows.size)
        assertEquals(7L, rows[0].accountId)
        assertEquals("tencent:42", rows[0].songKey)
        assertEquals(100L, rows[0].playedAt)
        assertEquals(3, rows[0].playCount)
    }

    @Test
    fun `outbox 快照兼容数字 songId 并丢弃不完整条目`() {
        val array = JSONArray()
            .put(JSONObject().put("sessionId", "s1").put("accountId", 7).put("songId", 12345L).put("startedAt", 1000L))
            .put(JSONObject().put("sessionId", "s2").put("accountId", 7).put("songId", "abc").put("startedAt", 0L))

        val rows = PrefsPayloadParser.parseOutboxRows(7, array.toString())

        assertEquals(1, rows.size)
        assertEquals("s1", rows[0].sessionId)
        assertEquals("12345", rows[0].songId)
        assertEquals("tencent", rows[0].source)
    }

    @Test
    fun `sync 元数据键做类型归一且只认已知键`() {
        assertEquals("1", PrefsPayloadParser.parseSyncMetaRow("account.7.clear_pending", true)?.metaValue)
        assertEquals("0", PrefsPayloadParser.parseSyncMetaRow("account.7.clear_pending", false)?.metaValue)
        assertEquals("9", PrefsPayloadParser.parseSyncMetaRow("account.7.outbox_version", 9L)?.metaValue)
        assertEquals("abc", PrefsPayloadParser.parseSyncMetaRow("account.7.clear_marker", "abc")?.metaValue)
        assertNull(PrefsPayloadParser.parseSyncMetaRow("account.7.unknown", 1L))
        assertNull(PrefsPayloadParser.parseSyncMetaRow("account.x.clear_pending", 1L))
    }

    @Test
    fun `收藏 ID 归一化兼容裸数字与大写来源`() {
        val rows = PrefsPayloadParser.parseFavoriteIdRows(setOf("123", "TENCENT:abc", "kuwo:9"))
        assertEquals(
            setOf("tencent:123", "tencent:abc", "kuwo:9"),
            rows.map { it.stableKey }.toSet(),
        )
    }

    @Test
    fun `收藏歌曲只保留当前收藏键内的条目`() {
        val ids = PrefsPayloadParser.parseFavoriteIdRows(setOf("tencent:1", "kuwo:2"))
            .map { it.stableKey }
            .toSet()
        val array = JSONArray()
            .put(JSONObject(songJson(remoteId = 1)))
            .put(JSONObject(songJson(source = "kuwo", mid = "not-2")))

        val rows = PrefsPayloadParser.parseFavoriteSongRows(array.toString(), ids)

        assertEquals(1, rows.size)
        assertEquals("tencent:1", rows[0].stableKey)
        assertEquals(0, rows[0].position)
    }

    @Test
    fun `AI 会话丢弃无用户消息的空白对话`() {
        val array = JSONArray()
            .put(
                JSONObject()
                    .put("id", "c1")
                    .put("messages", JSONArray().put(JSONObject().put("role", "user").put("text", "画一只猫")))
            )
            .put(
                JSONObject()
                    .put("id", "c2")
                    .put("messages", JSONArray().put(JSONObject().put("role", "assistant").put("text", "你好")))
            )

        val (conversations, messages) = PrefsPayloadParser.parseAiRows(array.toString())

        assertEquals(listOf("c1"), conversations.map { it.conversationId })
        assertEquals(1, messages.size)
        assertEquals("c1", messages[0].conversationId)
        assertEquals(0, messages[0].position)
    }

    @Test
    fun `prefs 值序列化为 kv 字符串`() {
        assertEquals("1", PrefsPayloadParser.convertPrefValue(true))
        assertEquals("0", PrefsPayloadParser.convertPrefValue(false))
        assertEquals("9", PrefsPayloadParser.convertPrefValue(9L))
        assertEquals("9", PrefsPayloadParser.convertPrefValue(9))
        assertEquals("abc", PrefsPayloadParser.convertPrefValue("abc"))
        assertEquals("""["a","b"]""", PrefsPayloadParser.stringSetToJson(setOf("b", "a")))
        assertNull(PrefsPayloadParser.convertPrefValue(listOf("a")))
    }

    @Test
    fun `队列优先新格式并回退旧版单歌曲`() {
        val queue = JSONArray()
            .put(JSONObject(songJson(remoteId = 5)))
            .put(JSONObject(songJson(remoteId = 6)))

        val rows = PrefsPayloadParser.parseQueueRows(queue.toString(), null)

        assertEquals(2, rows.size)
        assertEquals(0, rows[0].position)
        assertEquals(1, rows[1].position)

        val legacy = JSONObject(songJson(remoteId = 9)).put("positionMs", 3000)
        val legacyRows = PrefsPayloadParser.parseQueueRows(null, legacy.toString())
        assertEquals(1, legacyRows.size)
        assertEquals(3000, PrefsPayloadParser.parseLegacyPositionMs(legacy.toString()))
    }
}
