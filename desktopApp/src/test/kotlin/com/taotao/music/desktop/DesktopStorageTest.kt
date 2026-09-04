package com.taotao.music.desktop

import com.taotao.music.model.Song
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DesktopStorageTest {
    @Test
    fun `队列与来源可以完整恢复`() = withStorage { storage ->
        val tencent = song(id = 42L, source = "tencent", title = "腾讯歌曲")
        val netease = song(id = 42L, source = "netease", title = "网易歌曲")

        assertNotEquals(DesktopStorage.songKey(tencent), DesktopStorage.songKey(netease))
        storage.saveQueue(listOf(tencent, netease), index = 1, positionMs = 12_345)

        val restored = storage.loadQueue()
        assertEquals(1, restored?.index)
        assertEquals(12_345, restored?.positionMs)
        assertEquals(listOf("tencent", "netease"), restored?.queue?.map(Song::source))
    }

    @Test
    fun `收藏历史搜索与设置可以落盘`() = withStorage { storage ->
        val song = song(id = 7L, source = "netease", title = "测试歌曲")
        storage.saveFavorites(listOf(song))
        storage.saveHistory(listOf(DesktopStorage.HistoryEntry(song, playedAt = 20L, playCount = 2, historyRevision = 4L)))
        storage.saveSearchHistory(listOf(" 周杰伦 ", "周杰伦", "林俊杰"))
        storage.setSetting("theme", "dark")

        assertTrue(storage.loadFavorites().containsKey("netease:7"))
        assertEquals(2, storage.loadHistory().single().playCount)
        assertEquals(4L, storage.loadHistory().single().historyRevision)
        assertEquals(listOf("周杰伦", "林俊杰"), storage.loadSearchHistory())
        assertEquals("dark", storage.getSetting("theme", "light"))
    }

    @Test
    fun `账号切换不会复用收藏与最近播放缓存`() = withStorage { storage ->
        val song = song(id = 9L, source = "tencent", title = "账号 A 的歌曲")
        storage.prepareAccount(101L)
        storage.saveFavorites(listOf(song))
        storage.saveHistory(listOf(DesktopStorage.HistoryEntry(song, playedAt = 30L)))

        storage.prepareAccount(202L)
        assertTrue(storage.loadFavorites().isEmpty())
        assertTrue(storage.loadHistory().isEmpty())

        storage.saveFavorites(listOf(song))
        storage.saveHistory(listOf(DesktopStorage.HistoryEntry(song, playedAt = 40L)))
        storage.prepareAccount(null)
        assertTrue(storage.loadFavorites().isEmpty())
        assertTrue(storage.loadHistory().isEmpty())
    }

    @Test
    fun `下载元数据路径失配时使用目录内的实际音频`() = withStorage { storage ->
        val song = song(id = 42L, source = "tencent", title = "离线歌曲")
        val directory = File(storage.root, "downloads/tencent-42").apply { mkdirs() }
        val actualAudio = File(directory, "audio.flac").apply { writeBytes(ByteArray(5_000) { 1 }) }
        val missingAudio = File(directory, "audio.mp3")
        File(directory, "song.json").writeText(
            JSONObject()
                .put("title", song.title)
                .put("artist", song.artist)
                .put("duration", song.duration)
                .put("color", song.color)
                .put("audioUri", missingAudio.toURI().toString())
                .put("remoteId", song.remoteId)
                .put("source", song.source)
                .toString(),
        )

        val restored = storage.downloads().find(song)
        assertEquals(actualAudio.toURI().toString(), restored?.audioUri)
    }

    private fun song(id: Long, source: String, title: String) = Song(
        title = title,
        artist = "测试歌手",
        duration = "03:30",
        color = 0xFFFFB4A2,
        remoteId = id,
        source = source,
    )

    private fun withStorage(block: (DesktopStorage) -> Unit) {
        val root = Files.createTempDirectory("taotao-desktop-storage-test").toFile()
        try {
            block(DesktopStorage(root))
        } finally {
            root.deleteRecursively()
        }
    }
}
