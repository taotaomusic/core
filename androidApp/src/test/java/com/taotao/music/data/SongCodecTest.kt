package com.taotao.music.data

import com.taotao.music.model.Song
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.json.JSONObject

/** [SongCodec] 的编解码回归测试：高潮区间的哨兵语义与既有字段的往返都不能漂移。 */
class SongCodecTest {

    @Test
    fun `高潮区间随编解码往返保留`() {
        // 39482 / 69719 取自服务端实测的一首歌的高潮区间，两端都必须原样落盘。
        val song = song(refrainStartMs = 39_482L, refrainEndMs = 69_719L)

        val decoded = SongCodec.decode(SongCodec.encode(song))

        assertEquals(39_482L, decoded?.refrainStartMs)
        assertEquals(69_719L, decoded?.refrainEndMs)
    }

    @Test
    fun `旧版本缓存缺高潮区间键时解码为 null`() {
        // 手工构造只含四个老字段的 JSON，模拟升级前写进 SharedPreferences 的历史队列；
        // optLong 对缺失键返回 0，takeIf 把它挡成 null，不需要任何迁移代码。
        val legacy = JSONObject().apply {
            put("title", "测试歌曲")
            put("artist", "测试歌手")
            put("duration", "03:30")
            put("color", 0xFFFFB4A2)
        }.toString()

        val decoded = SongCodec.decode(legacy)

        assertNull(decoded?.refrainStartMs)
        assertNull(decoded?.refrainEndMs)
    }

    @Test
    fun `高潮区间为 0 的哨兵值解码为 null`() {
        val sentinel = song(refrainStartMs = 0L, refrainEndMs = 0L)

        val decoded = SongCodec.decode(SongCodec.encode(sentinel))

        assertNull(decoded?.refrainStartMs)
        assertNull(decoded?.refrainEndMs)
    }

    @Test
    fun `既有字段随编解码往返不回退`() {
        val song = Song(
            title = "测试歌曲",
            artist = "测试歌手",
            duration = "03:30",
            color = 0xFFFFB4A2,
            audioUri = "https://music.xydaigua.cn/placeholder",
            remoteId = 9527L,
            coverUri = "https://music.xydaigua.cn/cover.jpg",
            lyricUri = "https://music.xydaigua.cn/lyric",
            lyricWordsUri = "file:/offline/9527/words",
            album = "测试专辑",
            subtitle = "副标题",
            releaseTime = "2024-01-01",
            mid = "song-mid-001",
            type = 1,
            vip = true,
            favorited = true,
            localQuality = 3,
            source = "netease",
        )

        val decoded = SongCodec.decode(SongCodec.encode(song))

        // 整对象相等能同时覆盖所有持久化字段；playable 不落盘，双方都取默认值才能相等。
        assertEquals(song, decoded)
        assertEquals("song-mid-001", decoded?.mid)
        assertEquals(1, decoded?.type)
        assertEquals("netease", decoded?.source)
    }

    private fun song(
        refrainStartMs: Long? = null,
        refrainEndMs: Long? = null,
    ) = Song(
        title = "测试歌曲",
        artist = "测试歌手",
        duration = "03:30",
        color = 0L,
        remoteId = 9527L,
        refrainStartMs = refrainStartMs,
        refrainEndMs = refrainEndMs,
    )
}
