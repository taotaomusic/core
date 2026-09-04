package com.taotao.music.data

import com.taotao.music.model.Song
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 最近播放离线 outbox 的纯逻辑回归测试。 */
class PlaybackSnapshotPolicyTest {
    @Test
    fun `旧快照不能回写完成状态或累计时长`() {
        val completed = snapshot(listenedMs = 35_000, completed = true, outboxVersion = 8)
        val stale = snapshot(listenedMs = 15_000, completed = false, outboxVersion = 2)

        val merged = PlaybackSnapshotPolicy.merge(completed, stale, outboxVersion = 9)

        assertEquals(35_000, merged.listenedMs)
        assertTrue(merged.completed)
        assertEquals(9, merged.outboxVersion)
    }

    @Test
    fun `上传中的旧版本不能删除后来写入的新快照`() {
        val uploaded = snapshot(outboxVersion = 3)
        val newer = uploaded.copy(listenedMs = 30_000, outboxVersion = 4)

        assertFalse(PlaybackSnapshotPolicy.shouldRemoveAfterUpload(newer, uploaded))
        assertTrue(PlaybackSnapshotPolicy.shouldRemoveAfterUpload(uploaded, uploaded))
    }

    @Test
    fun `账号桶只能由当前同一账号上传`() {
        assertTrue(PlaybackSnapshotPolicy.canUploadForAccount(101, 101))
        assertFalse(PlaybackSnapshotPolicy.canUploadForAccount(101, 202))
        assertFalse(PlaybackSnapshotPolicy.canUploadForAccount(101, null))
    }

    @Test
    fun `同一会话不允许跨账号合并`() {
        val accountA = snapshot()
        val accountB = accountA.copy(accountId = 202)

        val error = runCatching {
            PlaybackSnapshotPolicy.merge(accountA, accountB, outboxVersion = 2)
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun `会话代际在正常同步中冻结不被后续清空升级`() {
        val oldGeneration = snapshot(historyRevision = 4)
        val laterSnapshot = oldGeneration.copy(historyRevision = 9, listenedMs = 10_000)

        val merged = PlaybackSnapshotPolicy.merge(oldGeneration, laterSnapshot, outboxVersion = 2)

        assertEquals(4, merged.historyRevision)
        assertEquals(10_000, merged.listenedMs)
    }

    @Test
    fun `清空后旧活跃会话不能在finally重新写回`() {
        val clearEpochWhenSessionStarted = 7
        val clearEpochAfterUserClearedHistory = 8

        assertFalse(
            PlaybackSnapshotPolicy.shouldPersistAfterClear(
                clearEpochWhenSessionStarted,
                clearEpochAfterUserClearedHistory,
            ),
        )
        assertTrue(PlaybackSnapshotPolicy.shouldPersistAfterClear(8, 8))
    }

    @Test
    fun `同曲重播与单曲循环必须新建会话`() {
        val active = snapshot(playbackCycle = 12)

        assertTrue(PlaybackSnapshotPolicy.shouldReuseSession(active, 101, "tencent", active.songId, 12))
        assertFalse(PlaybackSnapshotPolicy.shouldReuseSession(active, 101, "tencent", active.songId, 13))
        assertFalse(PlaybackSnapshotPolicy.shouldReuseSession(active, 202, "tencent", active.songId, 12))
    }

    @Test
    fun `mid-only 会话按来源和字符串身份复用`() {
        val active = snapshot().copy(source = "tencent", songId = "mid-only-abc")

        assertTrue(PlaybackSnapshotPolicy.shouldReuseSession(active, 101, "tencent", "mid-only-abc", 1))
        assertFalse(PlaybackSnapshotPolicy.shouldReuseSession(active, 101, "netease", "mid-only-abc", 1))
        assertFalse(PlaybackSnapshotPolicy.shouldReuseSession(active, 101, "tencent", "other", 1))
    }

    @Test
    fun `超过容量时淘汰最早播放会话而非最新会话`() {
        val kept = PlaybackSnapshotPolicy.retainNewest(
            listOf(
                snapshot(sessionId = "old", lastPlayedAt = 10),
                snapshot(sessionId = "middle", lastPlayedAt = 20),
                snapshot(sessionId = "new", lastPlayedAt = 30),
            ),
            limit = 2,
        )

        assertEquals(listOf("middle", "new"), kept.map { it.sessionId })
    }

    @Test
    fun `本地移动最近播放时保留已同步的云端统计`() {
        val song = Song("测试歌曲", "测试歌手", "03:00", 0L, remoteId = 9527)
        val remote = PlaybackHistoryEntry(
            song = song,
            playedAtMillis = 1_000,
            firstPlayedAtMillis = 500,
            playCount = 4,
            completedCount = 2,
            totalListenedMs = 90_000,
        )

        val refreshed = PlaybackHistoryPolicy.refreshEntry(remote, song, playedAtMillis = 2_000)

        assertEquals(2_000, refreshed.playedAtMillis)
        assertEquals(500, refreshed.firstPlayedAtMillis)
        assertEquals(4, refreshed.playCount)
        assertEquals(2, refreshed.completedCount)
        assertEquals(90_000, refreshed.totalListenedMs)
    }

    private fun snapshot(
        sessionId: String = "session",
        listenedMs: Long = 3_000,
        completed: Boolean = false,
        outboxVersion: Long = 1,
        playbackCycle: Long = 1,
        lastPlayedAt: Long = 20_000,
        historyRevision: Long = 4,
    ) = PendingPlaybackSnapshot(
        sessionId = sessionId,
        accountId = 101,
        songId = "9527",
        startedAt = 10_000,
        lastPlayedAt = lastPlayedAt,
        listenedMs = listenedMs,
        durationSeconds = 180,
        completed = completed,
        historyRevision = historyRevision,
        outboxVersion = outboxVersion,
        playbackCycle = playbackCycle,
    )
}
