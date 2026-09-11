package com.taotao.music.playerui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlayerActionsTest {
    @Test
    fun capabilitiesDefaultProperties() {
        val caps = PlayerCapabilities()
        assertTrue(caps.showPreviousNext)
        assertTrue(caps.showRepeat)
        assertTrue(caps.showProgress)
    }

    @Test
    fun playerActionsExecuteProperly() {
        var played = false
        val actions = PlayerActions(
            onTogglePlaying = { played = true },
            onSeek = {},
            onToggleRepeat = {},
        )
        actions.onTogglePlaying()
        assertTrue(played)
    }

    @Test
    fun playerProgressIsClampedToValidRange() {
        assertEquals(0f, normalizedPlayerProgress(positionMs = 500L, durationMs = 0L))
        assertEquals(0f, normalizedPlayerProgress(positionMs = -500L, durationMs = 1_000L))
        assertEquals(0.5f, normalizedPlayerProgress(positionMs = 500L, durationMs = 1_000L))
        assertEquals(1f, normalizedPlayerProgress(positionMs = 1_500L, durationMs = 1_000L))
    }

    @Test
    fun playerPositionUsesClampedProgress() {
        assertEquals(0L, playerPositionForProgress(progress = 0.5f, durationMs = 0L))
        assertEquals(0L, playerPositionForProgress(progress = -0.5f, durationMs = 1_000L))
        assertEquals(500L, playerPositionForProgress(progress = 0.5f, durationMs = 1_000L))
        assertEquals(1_000L, playerPositionForProgress(progress = 1.5f, durationMs = 1_000L))
    }

    @Test
    fun displayTextCollapsesWhitespace() {
        assertEquals("桃桃 音乐", normalizedDisplayText("  桃桃\n\t音乐  "))
        assertEquals("", normalizedDisplayText(" \n\t "))
    }
}

