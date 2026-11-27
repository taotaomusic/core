package com.taotao.music.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SongMetadataTest {
    private val song = Song("旧下载", "歌手", "03:00", 0L, remoteId = 42, source = "kuwo")

    @Test
    fun `不支持接口的来源和无远端身份的文件始终隐藏 MV`() {
        for (source in listOf("tencent", "netease", "local", "unknown")) {
            assertFalse(song.copy(source = source, hasMv = true).canPlayMv)
            assertFalse(song.copy(source = source).needsPlaybackMetadata)
        }
        assertFalse(song.copy(remoteId = null, hasMv = true).canPlayMv)
        assertFalse(song.canPlayMv)
        assertTrue(song.copy(source = " KuWo ", hasMv = true).canPlayMv)
        assertNull(song.copy(source = "local").metadataKey)
        assertFalse(song.copy(remoteId = null, mid = null).needsPlaybackMetadata)
    }

    @Test
    fun `旧下载补资料保留所有本地资源且明确 false 可覆盖 true`() {
        val local = song.copy(audioUri = "file:/audio.flac", coverUri = "file:/cover", lyricUri = "file:/lyric",
            lyricWordsUri = "file:/words", localQuality = 8, hasMv = true)
        val refreshed = song.copy(title = "上游标题", hasMv = false, artistId = 9, refrainStartMs = 10)
        assertEquals(local.copy(hasMv = false, artistId = 9, refrainStartMs = 10), local.mergePlaybackMetadata(refreshed))
        assertEquals(local, local.mergePlaybackMetadata(song))
    }

    @Test
    fun `异步结果不能覆盖其他音源或其他歌曲`() {
        for (other in listOf(song.copy(source = "tencent"), song.copy(remoteId = 43))) {
            assertEquals(song, song.mergePlaybackMetadata(other.copy(hasMv = true, artistId = 9)))
        }
    }
}
