package com.taotao.music.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AudioQualityTest {
    @Test
    fun `取值与上游档位对应`() {
        assertEquals(4, AudioQuality.STANDARD.value)
        assertEquals(8, AudioQuality.HIGH.value)
        assertEquals(10, AudioQuality.LOSSLESS.value)
        assertEquals(11, AudioQuality.HIRES.value)
        assertEquals(14, AudioQuality.MASTER.value)
    }

    @Test
    fun `按取值反查档位`() {
        assertEquals(AudioQuality.LOSSLESS, AudioQuality.of(10))
        assertEquals(AudioQuality.MASTER, AudioQuality.of(14))
    }

    /** 偏好里可能残留旧版本或手改过的值，不能让它把播放彻底搞坏。 */
    @Test
    fun `未知取值退回默认档`() {
        assertEquals(AudioQuality.Default, AudioQuality.of(99))
        assertEquals(AudioQuality.Default, AudioQuality.of(-1))
        assertEquals(AudioQuality.Default, AudioQuality.of(12))
    }

    @Test
    fun `无损标记正确`() {
        assertFalse(AudioQuality.STANDARD.lossless)
        assertFalse(AudioQuality.HIGH.lossless)
        assertTrue(AudioQuality.LOSSLESS.lossless)
        assertTrue(AudioQuality.HIRES.lossless)
        assertTrue(AudioQuality.MASTER.lossless)
    }

    /** 服务端降级后可能落在枚举之外的档位，界面仍要说得出名字。 */
    @Test
    fun `展示名覆盖上游全部档位`() {
        assertEquals("试听", labelOfQuality(0))
        assertEquals("有损", labelOfQuality(1))
        assertEquals("标准", labelOfQuality(3))
        assertEquals("标准", labelOfQuality(7))
        assertEquals("HQ 高音质", labelOfQuality(9))
        assertEquals("SQ 无损", labelOfQuality(10))
        assertEquals("杜比全景声", labelOfQuality(12))
        assertEquals("NAC", labelOfQuality(18))
    }

    @Test
    fun `未知档位也有兜底文案`() {
        assertEquals("音质 42", labelOfQuality(42))
    }

    /** 每个可选档位都必须能被 labelOfQuality 命名，否则界面会出现「音质 N」。 */
    @Test
    fun `可选档位都有专属文案`() {
        AudioQuality.entries.forEach { quality ->
            assertFalse(labelOfQuality(quality.value).startsWith("音质 "), "${quality.name} 缺少文案")
        }
    }
}
