package com.taotao.music.desktop

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DesktopPlaybackOutboxTest {
    @Test
    fun `同一会话只向前合并且旧上传不能删除新进度`() {
        val root = Files.createTempDirectory("taotao-playback-outbox").toFile()
        try {
            val store = DesktopPlaybackOutbox(root)
            val first = store.put(snapshot(listenedMs = 15_000L))
            val newer = store.put(snapshot(listenedMs = 32_000L, completed = true))

            assertEquals(32_000L, newer.listenedMs)
            assertTrue(newer.completed)
            assertFalse(store.removeIfUnchanged(101L, first))
            assertTrue(store.removeIfUnchanged(101L, newer))
            assertTrue(store.snapshots(101L).isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `账号分桶且离线清空使用稳定标识并重定新会话`() {
        val root = Files.createTempDirectory("taotao-playback-clear").toFile()
        try {
            val store = DesktopPlaybackOutbox(root)
            store.acceptServerRevision(101L, 7L)
            store.put(snapshot(accountId = 101L, sessionId = "old"))
            store.put(snapshot(accountId = 202L, sessionId = "other"))

            assertEquals(8L, store.markClearPending(101L))
            val marker = store.clearMarker(101L)
            assertEquals(marker, store.clearMarker(101L))
            assertNotEquals(null, marker)
            assertTrue(store.snapshots(101L).isEmpty())
            assertEquals(1, store.snapshots(202L).size)

            store.put(snapshot(accountId = 101L, sessionId = "new", historyRevision = 8L))
            assertEquals(12L, store.confirmClear(101L, 12L))
            assertEquals(12L, store.snapshots(101L).single().historyRevision)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `服务端版本前进时只显式重定当前会话`() {
        val root = Files.createTempDirectory("taotao-playback-rebase").toFile()
        try {
            val store = DesktopPlaybackOutbox(root)
            store.put(snapshot(accountId = 101L, sessionId = "current", historyRevision = 4L))
            store.put(snapshot(accountId = 101L, sessionId = "old", historyRevision = 4L))
            store.acceptServerRevision(101L, 7L)

            assertEquals(4L, store.snapshots(101L).first { it.sessionId == "current" }.historyRevision)
            val rebased = store.rebaseSessionRevision(101L, "current", 7L)
            assertEquals(7L, rebased?.historyRevision)
            assertEquals(7L, store.snapshots(101L).first { it.sessionId == "current" }.historyRevision)
            assertEquals(4L, store.snapshots(101L).first { it.sessionId == "old" }.historyRevision)

            store.put(snapshot(accountId = 101L, sessionId = "new", historyRevision = 0L))
            assertEquals(7L, store.snapshots(101L).first { it.sessionId == "new" }.historyRevision)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun snapshot(
        accountId: Long = 101L,
        sessionId: String = "session",
        listenedMs: Long = 3_000L,
        completed: Boolean = false,
        historyRevision: Long = 4L,
    ) = DesktopPlaybackSnapshot(
        sessionId = sessionId,
        accountId = accountId,
        source = "tencent",
        songId = "9001",
        startedAt = 1_000L,
        lastPlayedAt = 2_000L,
        listenedMs = listenedMs,
        durationSeconds = 240,
        completed = completed,
        historyRevision = historyRevision,
    )
}
