package com.taotao.music.data

import com.taotao.music.model.Song
import java.io.File
import java.util.Properties
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OfflineDownloadMetadataTest {
    @get:Rule
    val temp = TemporaryFolder()
    private val tokens = object : TokenProvider {
        override fun currentToken(): String? = null
        override fun validToken(): String? = error("离线资料更新不应读取令牌")
        override fun renewToken(rejectedToken: String?): String? = error("不应请求网络")
    }

    private fun writeDownload(root: File, folder: String, source: String): File {
        val dir = File(root, folder).apply { mkdirs() }
        File(dir, "audio.flac").writeBytes(ByteArray(8_192) { 42 })
        File(dir, "lyric.txt").writeText("旧歌词")
        File(dir, "song.properties").outputStream().use { output ->
            Properties().apply {
                setProperty("remoteId", "42")
                setProperty("title", "旧下载")
                setProperty("source", source)
                setProperty("quality", "8")
                setProperty("color", "0")
            }.store(output, "旧格式")
        }
        return dir
    }

    @Test
    fun `旧下载补全落盘后新实例读取且音频歌词不变`() {
        val root = temp.newFolder()
        val dir = writeDownload(root, "42", "kuwo")
        val manager = OfflineDownloadManager(root, tokens)
        val before = assertNotNull(manager.findDownloaded(42, "kuwo"))
        assertNull(before.hasMv)
        assertTrue(manager.updateMetadata(before.copy(hasMv = true, artistId = 9, refrainStartMs = 100)))
        val after = assertNotNull(OfflineDownloadManager(root, tokens).findDownloaded(42, "kuwo"))
        assertEquals(before.copy(hasMv = true, artistId = 9, refrainStartMs = 100), after)
        assertEquals(8_192L, File(dir, "audio.flac").length())
        assertEquals("旧歌词", File(dir, "lyric.txt").readText())
        assertTrue(manager.updateMetadata(after.copy(hasMv = false)))
        assertEquals(false, manager.findDownloaded(42, "kuwo")?.hasMv)
    }

    @Test
    fun `历史数字目录与新音源目录同号查找更新删除互不干扰`() {
        val root = temp.newFolder()
        val old = writeDownload(root, "42", "tencent")
        val kuwo = writeDownload(root, "kuwo-42", "kuwo")
        val manager = OfflineDownloadManager(root, tokens)
        val local = assertNotNull(manager.findDownloaded(42, "kuwo"))
        assertEquals(File(kuwo, "audio.flac").toURI().toString(), local.audioUri)
        assertTrue(manager.updateMetadata(local.copy(hasMv = true)))
        assertNull(manager.findDownloaded(42, "tencent")?.hasMv)
        assertNull(manager.findDownloaded(42, "netease"))
        assertFalse(manager.delete(Song("", "", "", 0L, remoteId = 42, source = "netease")))
        assertTrue(manager.delete(local))
        assertTrue(old.isDirectory)
        assertFalse(kuwo.exists())
    }
}
