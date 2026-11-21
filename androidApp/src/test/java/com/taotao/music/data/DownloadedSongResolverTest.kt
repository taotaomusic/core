package com.taotao.music.data

import com.taotao.music.model.Song
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DownloadedSongResolverTest {
    @Test
    fun `歌单歌曲按数字 ID 绑定本地音频`() {
        val local = song(remoteId = 42, audioUri = "file:/offline/42/audio.mp3")

        assertEquals(local, findDownloadedSong(song(remoteId = 42), listOf(local)))
    }

    @Test
    fun `旧歌单缺数字 ID 时按 mid 绑定本地音频`() {
        val local = song(remoteId = 42, mid = "song-mid", audioUri = "file:/offline/42/audio.flac")

        assertEquals(local, findDownloadedSong(song(mid = "song-mid"), listOf(local)))
    }

    @Test
    fun `不同音源的相同 ID 不会误绑`() {
        val local = song(remoteId = 42, source = "netease", audioUri = "file:/offline/42/audio.mp3")

        assertNull(findDownloadedSong(song(remoteId = 42, source = "tencent"), listOf(local)))
    }

    @Test
    fun `歌单整条队列都会绑定本地音频`() {
        val queue = listOf(song(remoteId = 41), song(remoteId = 42), song(remoteId = 43))
        val downloads = listOf(
            song(remoteId = 41, audioUri = "file:/offline/41/audio.mp3"),
            song(remoteId = 42, audioUri = "file:/offline/42/audio.flac"),
        )

        val resolved = bindDownloadedSongs(queue, downloads)

        assertEquals("file:/offline/41/audio.mp3", resolved[0].audioUri)
        assertEquals("file:/offline/42/audio.flac", resolved[1].audioUri)
        assertEquals(queue[2].audioUri, resolved[2].audioUri)
    }

    private fun song(
        remoteId: Long? = null,
        mid: String? = null,
        source: String = "tencent",
        audioUri: String? = "https://music.xydaigua.cn/placeholder",
    ) = Song(
        title = "测试歌曲",
        artist = "测试歌手",
        duration = "03:30",
        color = 0L,
        audioUri = audioUri,
        remoteId = remoteId,
        mid = mid,
        source = source,
    )
}
