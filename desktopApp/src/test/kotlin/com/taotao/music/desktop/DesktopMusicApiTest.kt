package com.taotao.music.desktop

import com.taotao.music.model.Song
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopMusicApiTest {
    @Test
    fun `网易云母带请求映射到最高档标记`() {
        assertEquals(18, DesktopMusicApi.requestQuality("netease", 14))
        assertEquals(14, DesktopMusicApi.requestQuality("tencent", 14))
        assertEquals(10, DesktopMusicApi.requestQuality("netease", 10))
    }

    @Test
    fun `mid only 歌曲使用稳定身份并生成可恢复的占位地址`() {
        val song = Song(
            title = "测试歌曲",
            artist = "测试歌手",
            duration = "03:20",
            color = 0L,
            mid = "004Z8Ihr0JIu5s",
            type = 42,
            source = "tencent",
        )

        assertEquals("004Z8Ihr0JIu5s", song.remoteIdentity())
        assertEquals("tencent:004Z8Ihr0JIu5s", DesktopStorage.songKey(song))
        val placeholder = requireNotNull(DesktopMusicApi.placeholderUri(song, 10, "https://music.example"))
        assertTrue(placeholder.startsWith("https://music.example/api/v1/songs/0/play?"))
        assertTrue(placeholder.contains("mid=004Z8Ihr0JIu5s"))
        assertTrue(placeholder.contains("type=42"))

        val resolved = song.copy(remoteId = 123L)
        assertEquals("123", resolved.remoteIdentity())
        assertTrue(song.sameRemoteSong(resolved))
        assertFalse(song.sameRemoteSong(song.copy(mid = "another-mid")))
    }
}
