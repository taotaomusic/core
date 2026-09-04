package com.taotao.music.desktop

import org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_FLAC
import org.bytedeco.ffmpeg.global.avcodec.avcodec_find_decoder
import org.bytedeco.javacv.FFmpegFrameGrabber
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopPlayerTest {
    @Test
    fun `Windows FFmpeg native 包含 FLAC 解码器`() {
        FFmpegFrameGrabber.tryLoad()

        assertFalse(avcodec_find_decoder(AV_CODEC_ID_FLAC).isNull)
    }

    @Test
    fun `下载格式能力覆盖有损与无损音频`() {
        listOf("mp3", "m4a", "aac", "wav", "flac", "ogg", "opus").forEach { extension ->
            assertTrue(DesktopPlayer.supports("C:/Music/test.$extension"), extension)
        }
        assertFalse(DesktopPlayer.supports("C:/Music/test.nac"))
        assertFalse(DesktopPlayer.supportsQuality("tencent", 18))
        assertTrue(DesktopPlayer.supportsQuality("netease", 18))
    }
}
