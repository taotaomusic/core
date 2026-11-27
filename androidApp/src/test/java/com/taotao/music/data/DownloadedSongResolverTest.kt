package com.taotao.music.data

import com.taotao.music.model.Song
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DownloadedSongResolverTest {
    @Test
    fun `绑定下载文件时使用最新 MV 判断且未知值不覆盖已知状态`() {
        val local = song(remoteId = 42, audioUri = "file:/offline/42/audio.mp3").copy(hasMv = true)
        val online = song(remoteId = 42).copy(hasMv = false)

        assertEquals(false, bindDownloadedSong(online, local).hasMv)
        assertEquals(true, bindDownloadedSong(online.copy(hasMv = null), local).hasMv)
    }

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

    @Test
    fun `绑定本地文件时在线封面兜底本地缺失的封面`() {
        // 本地那份当年没下下来封面（coverUri 为 null）；来源队列带着在线封面地址。
        val local = song(remoteId = 42, audioUri = "file:/offline/42/audio.flac", coverUri = null)
        val online = song(remoteId = 42, coverUri = "https://img.example/42.jpg")

        val resolved = bindDownloadedSongs(listOf(online), listOf(local))

        assertEquals("file:/offline/42/audio.flac", resolved[0].audioUri)
        assertEquals("https://img.example/42.jpg", resolved[0].coverUri)
    }

    @Test
    fun `本地已有封面时绑定优先本地文件`() {
        val local = song(remoteId = 42, audioUri = "file:/offline/42/audio.flac", coverUri = "file:/offline/42/cover")
        val online = song(remoteId = 42, coverUri = "https://img.example/42.jpg")

        val resolved = bindDownloadedSongs(listOf(online), listOf(local))

        assertEquals("file:/offline/42/audio.flac", resolved[0].audioUri)
        assertEquals("file:/offline/42/cover", resolved[0].coverUri)
    }

    private fun song(
        remoteId: Long? = null,
        mid: String? = null,
        source: String = "tencent",
        audioUri: String? = "https://music.xydaigua.cn/placeholder",
        coverUri: String? = null,
    ) = Song(
        title = "测试歌曲",
        artist = "测试歌手",
        duration = "03:30",
        color = 0L,
        audioUri = audioUri,
        remoteId = remoteId,
        mid = mid,
        source = source,
        coverUri = coverUri,
    )
}
