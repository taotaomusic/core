package com.taotao.music.data

import com.taotao.music.model.Song
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlaybackMetadataResolverTest {
    private val song = Song("测试", "歌手", "03:00", 0L, remoteId = 42, source = "kuwo")

    @Test
    fun `后台补全与详情页同曲并发只查询一次且保留本地文件`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var calls = 0
        val resolver = PlaybackMetadataResolver(fetch = {
            calls++
            started.complete(Unit)
            release.await()
            it.copy(hasMv = true)
        })
        val first = async { resolver.resolve(song) }
        started.await()
        val second = async { resolver.resolve(song.copy(audioUri = "file:/offline/42/audio.mp3")) }
        release.complete(Unit)
        assertEquals(true, first.await().hasMv)
        assertEquals("file:/offline/42/audio.mp3", second.await().audioUri)
        assertEquals(true, second.await().hasMv)
        assertEquals(1, calls)
    }

    @Test
    fun `离线失败不误报没有且退避到期可以重试`() = runBlocking {
        var clock = 1L
        var calls = 0
        val resolver = PlaybackMetadataResolver(fetch = {
            if (++calls == 1) throw IllegalStateException("离线")
            it.copy(hasMv = false)
        }, now = { clock })
        assertNull(resolver.resolve(song).hasMv)
        assertNull(resolver.resolve(song).hasMv)
        assertEquals(1, calls)
        clock += 30_001L
        assertEquals(false, resolver.resolve(song).hasMv)
        assertEquals(2, calls)
    }

    @Test
    fun `无远端身份的导入文件不会查询任何接口`() = runBlocking {
        var calls = 0
        val resolver = PlaybackMetadataResolver(fetch = { calls++; it.copy(hasMv = true) })
        resolver.resolve(song.copy(remoteId = null))
        resolver.resolve(song.copy(source = "local"))
        resolver.resolve(song.copy(source = "tencent"))
        resolver.resolve(song.copy(source = "netease"))
        assertEquals(0, calls)
    }

    @Test
    fun `播放接口确认无 MV 后旧快照不得重新显示入口`() = runBlocking {
        val resolver = PlaybackMetadataResolver(fetch = { it.copy(hasMv = true) })
        assertEquals(true, resolver.resolve(song).hasMv)
        resolver.rememberMv(song, false)
        assertEquals(false, resolver.resolve(song.copy(hasMv = true)).hasMv)
        assertEquals(false, resolver.resolve(song.copy(hasMv = true, artistId = 1, albumId = 1,
            refrainStartMs = 1, refrainEndMs = 2)).hasMv)
    }
}
