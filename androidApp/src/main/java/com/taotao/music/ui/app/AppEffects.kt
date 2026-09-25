package com.taotao.music.ui.app

import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.taotao.music.ui.common.playbackSongId
import com.taotao.music.ui.common.playbackSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 登录门禁之前就该存在的副作用：通知权限与公告、补发统计、IM 前后台、返回键、热更新。
 * 与原主入口的排布一致 —— 强制更新与登录页出现时这些能力仍然生效。
 */
@Composable
internal fun TaotaoAppPreAuthEffects(state: TaotaoAppState) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val syncedImPeers by state.wukongImClient.syncedPeers.collectAsState()

    /**
     * 申请通知权限。
     *
     * Android 13 起 POST_NOTIFICATIONS 要运行时授权，而清单里早就声明了却从没申请过 ——
     * 也就是说系统媒体通知（锁屏控制）在 13+ 上一直没显示。下载进度通知同样依赖它，
     * 顺手一起补上。只申请一次，拒绝了也不再骚扰。
     */
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !state.downloadNotifier.canNotify()) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        state.loadAnnouncements()
    }

    // 离线会话同步完成后，把悟空返回的对端 UUID 写入本机入口；换号时先恢复新账号的本地缓存。
    LaunchedEffect(state.authSession.accountId, syncedImPeers) {
        state.reloadImPeers()
        syncedImPeers.forEach { peerUid -> state.rememberImPeer(peerUid) }
    }

    // 冷启动就先补发上次进程退出前保存的快照，不必等用户再点进「最近播放」。
    LaunchedEffect(state.signedIn) {
        if (state.signedIn) runCatching { state.syncPendingPlayback() }
    }

    // 监听应用前后台状态，用于 IM 消息通知
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> state.wukongImClient.setAppForeground(true)
                Lifecycle.Event.ON_PAUSE -> state.wukongImClient.setAppForeground(false)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    /**
     * 物理返回键：详情页先收起详情，搜索页先退出搜索，都不在时禁用拦截，
     * 交回系统默认行为（退出应用）—— 这样不必自己去拿 onBackPressedDispatcher。
     */
    BackHandler(
        enabled = state.showPlayerDetail || state.showSearchPage || state.showSettingsPage ||
            state.showProfilePage || state.playlist.selected != null || state.mineLibrarySection != null ||
            state.diarySong != null,
    ) {
        when {
            // 与 currentPage 一致：详情页盖在日记之上，返回时先退详情再退日记；
            // 播放记录是日记的下级页，返回时也要先退它。
            state.showPlayerDetail -> state.showPlayerDetail = false
            state.showDiaryRecords -> state.showDiaryRecords = false
            state.diarySong != null -> state.diarySong = null
            state.showProfilePage -> state.showProfilePage = false
            state.showSettingsPage -> state.showSettingsPage = false
            state.showSearchPage -> state.showSearchPage = false
            state.playlist.selected != null -> state.playlist.selected = null
            state.mineLibrarySection != null -> state.mineLibrarySection = null
        }
    }

    // 热更新检查。刻意放在登录门禁之前：最需要强制更新的场景恰恰是上一个版本把登录搞坏了，
    // 若要求先登录才能看到更新页，坏版本的用户就永远走不出来。
    LaunchedEffect(state.updateManager) { state.updateManager.check() }

    /**
     * 确认热修复补丁可用。
     *
     * 刻意等到界面组合起来、再多等几秒才确认：加载补丁前记了尝试计数，只有走到这里
     * 才清零。太早确认等于把自愈机制关掉 —— 一个能让应用起不来的补丁必须能自己退回去。
     *
     * key 是 `activePatchVersion` 而不是 `Unit`：会话中途装上的补丁同样需要被确认。
     * 用 Unit 的话这个效果只在启动时烧一次，中途装的补丁尝试计数永远停在 1，
     * 下次启动被判成"加载后启动失败"而回滚 —— 表现是"点了能生效，一重启就没了"。
     */
    LaunchedEffect(state.updateManager.activePatchVersion) {
        delay(5_000)
        state.updateManager.confirmPatch(state.updateManager.activePatchVersion)
    }

    /**
     * 会话中途发现新版本。
     *
     * 服务端在每个响应上带回当前全量可用的最高版本号，比本机高就走一次正常检查。
     * 回调来自请求线程，切回主线程再动状态；连接由 DisposableEffect 负责断开。
     */
    DisposableEffect(state.musicApi, state.updateManager) {
        state.musicApi.onLatestVersion = { latest ->
            state.scope.launch { state.updateManager.onLatestVersionHint(latest) }
        }
        state.musicApi.onLatestPatch = { latestPatch ->
            state.scope.launch { state.updateManager.onLatestPatchHint(latestPatch) }
        }
        onDispose {
            state.musicApi.onLatestVersion = null
            state.musicApi.onLatestPatch = null
        }
    }

    /** 手动检查更新的结果单独提示，后台检查保持安静。 */
    LaunchedEffect(state.updateManager.manualResult) {
        state.updateManager.consumeManualResult()?.let { state.message = it }
    }
}

/** 会话彻底失效（刷新令牌也被拒绝）时停止播放并回到登录页。 */
@Composable
internal fun TaotaoAppSessionGuard(state: TaotaoAppState) {
    DisposableEffect(state.authSession) {
        state.authSession.onSessionExpired = { state.handleSessionExpired() }
        onDispose { state.authSession.onSessionExpired = null }
    }
}

/**
 * 登录后才组合的副作用：IM 连接、磁盘恢复、统计会话与播放进度持久化。
 * 原先这些 effect 排布在 `if (!signedIn) return` 之后，行为保持一致 ——
 * 未登录时不组合，登录瞬间随重组启动。
 */
@Composable
internal fun TaotaoAppSignedInEffects(state: TaotaoAppState) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val latestPlaybackSongs = rememberUpdatedState(state.playbackSongs)
    val latestSelectedIndex = rememberUpdatedState(state.selectedIndex)

    /**
     * 登录后自动申请短期 IM 凭据并连接悟空 Gateway。IM 尚未在服务端启用、网络暂时不可用
     * 等情况只记日志，不能让音乐首页或账号流程无法使用。
     */
    LaunchedEffect(state.signedIn, state.authSession.accountId) {
        val accountId = state.authSession.accountId
        if (!state.signedIn || accountId == null) {
            state.wukongImClient.signOut()
            return@LaunchedEffect
        }
        runCatching { state.wukongImClient.connect(accountId) }
            .onFailure { error -> android.util.Log.w("TaotaoMusicApp", "悟空 IM 暂不可用", error) }
    }

    DisposableEffect(state.wukongImClient) {
        onDispose { state.wukongImClient.signOut() }
    }

    LaunchedEffect(state.signedIn, state.authSession.accountId) {
        val historyAccountId = state.authSession.accountId
        if (!state.signedIn || historyAccountId == null) {
            state.playbackHistory = emptyList()
            state.restoredPlayback = null
            return@LaunchedEffect
        }
        // 队列与播放记录 JSON 都可能不小，别在主线程读盘拖慢启动。
        val restored = withContext(Dispatchers.IO) {
            state.playbackStateStore.read() to state.playbackHistoryStore.read(historyAccountId)
        }
        if (state.authSession.accountId != historyAccountId) return@LaunchedEffect
        state.restoredPlayback = restored.first
        state.playbackHistory = restored.second
    }

    // 歌单是账号级云端数据；登录/换号后主动刷新，首页“我的歌单”数量不会滞后到手动打开页面。
    LaunchedEffect(state.signedIn, state.authSession.accountId) {
        if (state.signedIn && state.authSession.accountId != null) {
            state.playlist.refresh()
        } else {
            state.playlist.clearState()
        }
    }

    /**
     * 播种收藏缓存。
     *
     * 拉一次完整收藏列表就够了 —— 之后靠搜索结果里的 `favorited` 校正、靠本地乐观更新维持。
     * 失败不提示：收藏状态不是主流程，缓存里还有上次的值可用。
     */
    LaunchedEffect(state.signedIn, state.authSession.accountId) {
        val account = state.authSession.accountId
        if (!state.signedIn || account == null) return@LaunchedEffect
        state.profileLoading = true
        runCatching { withContext(Dispatchers.IO) { state.musicApi.profile() } }
            .onSuccess { if (state.authSession.accountId == account && state.signedIn) state.userProfile = it }
            .onFailure { if (state.authSession.accountId == account && state.signedIn) state.message = it.message ?: "账号资料读取失败" }
        if (state.authSession.accountId == account && state.signedIn) state.profileLoading = false
        runCatching { withContext(Dispatchers.IO) { state.musicApi.favoriteIds() } }
            .onSuccess { remoteIds ->
                if (state.authSession.accountId == account && state.signedIn) {
                    state.favoritesStore.replaceAll(remoteIds)
                    state.favoriteLibrarySongs = state.favoritesStore.cachedSongs()
                    state.favoriteRevision++
                }
            }
    }

    LaunchedEffect(state.restoredPlayback) {
        state.restoredPlayback?.let { savedState ->
            // 播放器已经有队列时以播放器为准，这里只负责冷启动后的恢复。
            if (state.playbackSongs.isEmpty() && state.audioPlayer.queue.isEmpty()) {
                state.playbackSongs = savedState.queue
                state.selectedIndex = savedState.index.coerceIn(savedState.queue.indices)
                state.pendingResumePositionMs = savedState.positionMs
                // 直接把队列装载进播放器：不出声，但时长、进度就位，点播放即刻续播。
                state.audioPlayer.prepareQueueIfIdle(savedState.queue, savedState.index, savedState.positionMs)
            }
        }
    }

    LaunchedEffect(Unit) {
        state.downloadedSongs = withContext(Dispatchers.IO) { state.downloadManager.listDownloaded() }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP || event == Lifecycle.Event.ON_DESTROY) {
                val queue = latestPlaybackSongs.value
                if (state.audioPlayer.hasMedia && queue.isNotEmpty()) {
                    state.playbackStateStore.save(queue, latestSelectedIndex.value, state.audioPlayer.currentPositionMs())
                }
                state.playbackSession?.takeIf { it.listenedMs > 0 }?.let { snapshot ->
                    val persisted = state.playbackSync.persistTerminalSnapshot(snapshot, completed = false)
                    if (state.playbackSession?.sessionId == persisted.sessionId) state.playbackSession = persisted
                    state.scope.launch { runCatching { state.syncPendingPlayback() } }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    /** 连接播放服务；界面销毁时只断开连接，后台播放不受影响。 */
    DisposableEffect(state.audioPlayer) {
        state.audioPlayer.connect()
        onDispose { state.audioPlayer.release() }
    }

    // 由播放器状态机发出结束事件；不再依赖页面协程刚好执行到 finally。
    DisposableEffect(state.audioPlayer, state.playbackSync) {
        val completionListener: (com.taotao.music.model.Song) -> Unit = { completedSong ->
            val activeSession = state.playbackSession
            if (
                activeSession != null &&
                activeSession.source == playbackSource(completedSong) &&
                activeSession.songId == playbackSongId(completedSong) &&
                activeSession.listenedMs > 0
            ) {
                val persisted = state.playbackSync.persistTerminalSnapshot(activeSession, completed = true)
                if (state.playbackSession?.sessionId == persisted.sessionId) state.playbackSession = persisted
                state.scope.launch { runCatching { state.syncPendingPlayback() } }
            }
        }
        state.audioPlayer.addCompletionListener(completionListener)
        onDispose { state.audioPlayer.removeCompletionListener(completionListener) }
    }

    /**
     * 播放服务仍在后台播放时，队列以播放器为准 —— 界面被销毁重建后不必等用户点播放，
     * 也不会出现「还在播但列表只剩一首」。播放器为空才用磁盘上恢复的队列兜底。
     */
    LaunchedEffect(state.audioPlayer.queue) {
        val livingQueue = state.audioPlayer.queue
        if (livingQueue.isNotEmpty()) {
            state.playbackSongs = livingQueue
            state.selectedIndex = state.audioPlayer.currentIndex.coerceIn(livingQueue.indices)
        }
    }

    /** 播放器切到下一首时让界面跟随，下标越界说明队列还没同步，忽略即可。 */
    LaunchedEffect(state.audioPlayer.currentIndex, state.playbackSongs) {
        val playerIndex = state.audioPlayer.currentIndex
        if (playerIndex in state.playbackSongs.indices) state.selectedIndex = playerIndex
    }

    /**
     * 统计会话：播放曲目、播放态、轮次或清空代际任一变化时重启一次会话累计。
     * 主体逻辑在 [TaotaoAppState.trackPlaybackSession]，内部读取的都是实时值。
     */
    val activePlayerSong = state.audioPlayer.queue.getOrNull(state.audioPlayer.currentIndex)
    LaunchedEffect(
        activePlayerSong?.source,
        activePlayerSong?.remoteId,
        activePlayerSong?.mid,
        activePlayerSong?.audioUri,
        state.audioPlayer.isPlaying,
        state.audioPlayer.playbackCycle,
        state.playbackHistoryClearEpoch,
    ) {
        state.trackPlaybackSession()
    }

    /** 播放进度按曲目变化保存整条队列，替代原先每两秒一次的主线程写盘。 */
    LaunchedEffect(state.selectedIndex, state.isPlaying, state.playbackSongs) {
        if (!state.audioPlayer.hasMedia || state.playbackSongs.isEmpty()) return@LaunchedEffect
        // 进度必须在主线程读（MediaController 有线程亲和），只把结果交给 IO 线程写盘。
        val positionMs = state.audioPlayer.currentPositionMs()
        // 冷启动装载队列的瞬间进度可能还没生效，此时别用 0 覆盖掉刚从磁盘读出的进度。
        if (positionMs <= 0 && !state.isPlaying) return@LaunchedEffect
        val queueSnapshot = state.playbackSongs
        val indexSnapshot = state.selectedIndex
        withContext(Dispatchers.IO) { state.playbackStateStore.save(queueSnapshot, indexSnapshot, positionMs) }
    }

    /** 播放服务在取流线程记录失败原因，这里取出后清空，避免同一条错误反复提示。 */
    LaunchedEffect(state.audioPlayer.playError) {
        state.audioPlayer.consumePlayError()?.let { state.message = it }
    }

    // 搜索页打开时刷新热搜。失败时保持空列表，不能让推荐接口阻断正常搜索。
    LaunchedEffect(state.showSearchPage) {
        if (!state.showSearchPage) return@LaunchedEffect
        state.search.refreshHotSearches()
    }

    // 输入停止 250ms 后请求联想；LaunchedEffect 会自动取消上一关键词的在途协程。
    LaunchedEffect(state.showSearchPage, state.search.keyword) {
        state.search.refreshSuggestions(state.showSearchPage)
    }
}
