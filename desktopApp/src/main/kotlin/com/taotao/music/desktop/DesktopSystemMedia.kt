package com.taotao.music.desktop

import com.taotao.music.model.Song
import io.github.selemba1000.JMTC
import io.github.selemba1000.JMTCButtonCallback
import io.github.selemba1000.JMTCCallbacks
import io.github.selemba1000.JMTCEnabledButtons
import io.github.selemba1000.JMTCMediaType
import io.github.selemba1000.JMTCMusicProperties
import io.github.selemba1000.JMTCParameters
import io.github.selemba1000.JMTCPlayingState
import io.github.selemba1000.JMTCSeekCallback
import io.github.selemba1000.JMTCSettings
import io.github.selemba1000.JMTCTimelineProperties
import java.awt.Color
import java.awt.Dimension
import java.awt.EventQueue
import java.awt.Font
import java.awt.GraphicsEnvironment
import java.awt.Insets
import java.awt.RenderingHints
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.imageio.ImageIO
import javax.swing.BorderFactory
import javax.swing.JMenuItem
import javax.swing.JPopupMenu
import javax.swing.JWindow
import javax.swing.SwingConstants
import javax.swing.event.PopupMenuEvent
import javax.swing.event.PopupMenuListener

data class DesktopSystemCallbacks(
    val onShow: () -> Unit = {},
    val onPlay: () -> Unit = {},
    val onPause: () -> Unit = {},
    val onStop: () -> Unit = {},
    val onNext: () -> Unit = {},
    val onPrevious: () -> Unit = {},
    val onSeek: (Int) -> Unit = {},
    val onExit: () -> Unit = {},
)

/**
 * Windows 系统媒体和托盘适配层。
 *
 * SMTC（系统媒体传输控件）初始化及调用固定在单线程执行器，避免 WinRT 的线程模型污染
 * Compose。原生组件或系统托盘不可用时会静默降级，应用内播放器仍可正常工作。
 */
class DesktopSystemMedia(private val storageRoot: File) : AutoCloseable {
    @Volatile
    var callbacks: DesktopSystemCallbacks = DesktopSystemCallbacks()

    private val started = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val mediaExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "taotao-windows-media").apply { isDaemon = true }
    }
    private val coverExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "taotao-cover-cache").apply { isDaemon = true }
    }
    private val coverClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
    private val coverRoot = File(storageRoot, "system-media").apply { mkdirs() }
    private val coverGeneration = AtomicLong(0L)
    private val stateLock = Any()
    private var lastSubmitted = SystemMediaState()
    private var lastPositionSubmittedAt = 0L
    private var jmtc: JMTC? = null
    private var tray: TrayController? = null

    fun start() {
        if (!started.compareAndSet(false, true)) return
        tray = TrayController.create { callbacks }
        mediaExecutor.execute {
            runCatching {
                JMTC.getInstance(JMTCSettings("桃桃音乐", "TaotaoMusic")).also { control ->
                    control.setCallbacks(JMTCCallbacks().apply {
                        onPlay = JMTCButtonCallback { callbacks.onPlay() }
                        onPause = JMTCButtonCallback { callbacks.onPause() }
                        onStop = JMTCButtonCallback { callbacks.onStop() }
                        onNext = JMTCButtonCallback { callbacks.onNext() }
                        onPrevious = JMTCButtonCallback { callbacks.onPrevious() }
                        onSeek = JMTCSeekCallback { position -> callbacks.onSeek(position.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()) }
                    })
                    control.setEnabledButtons(JMTCEnabledButtons(true, true, true, true, true))
                    control.setMediaType(JMTCMediaType.Music)
                    control.setEnabled(true)
                    control.setPlayingState(JMTCPlayingState.CLOSED)
                    control.updateDisplay()
                }
            }.onSuccess {
                jmtc = it
                synchronized(stateLock) {
                    applyState(lastSubmitted, metadataChanged = true)
                }
            }
        }
    }

    fun update(
        song: Song?,
        playing: Boolean,
        positionMs: Int,
        durationMs: Int,
        repeatMode: Int,
        volume: Float,
    ) {
        if (!started.get() || closed.get()) return
        val state = SystemMediaState(
            song = song,
            playing = playing,
            positionMs = positionMs.coerceAtLeast(0),
            durationMs = durationMs.coerceAtLeast(0),
            repeatMode = repeatMode,
            volume = volume.coerceIn(0f, 1f),
        )
        tray?.update(song?.title, playing, song != null)

        val now = System.nanoTime()
        val metadataChanged: Boolean
        val shouldSubmit: Boolean
        synchronized(stateLock) {
            metadataChanged = songKey(lastSubmitted.song) != songKey(state.song)
            val transportChanged = lastSubmitted.playing != state.playing ||
                lastSubmitted.durationMs != state.durationMs ||
                lastSubmitted.repeatMode != state.repeatMode ||
                lastSubmitted.volume != state.volume
            val positionDue = now - lastPositionSubmittedAt >= POSITION_UPDATE_NANOS
            shouldSubmit = metadataChanged || transportChanged || positionDue || state.song == null && lastSubmitted.song != null
            if (!shouldSubmit) return
            lastSubmitted = state
            if (positionDue) lastPositionSubmittedAt = now
        }
        mediaExecutor.execute { applyState(state, metadataChanged) }
    }

    fun notifyHidden() {
        tray?.notifyHidden()
    }

    private fun applyState(state: SystemMediaState, metadataChanged: Boolean) {
        val control = jmtc ?: return
        runCatching {
            val song = state.song
            if (song == null) {
                control.setPlayingState(JMTCPlayingState.CLOSED)
                control.resetDisplay()
                return@runCatching
            }
            if (metadataChanged) {
                val generation = coverGeneration.incrementAndGet()
                val localCover = localCover(song.coverUri)
                setMediaProperties(control, song, localCover)
                if (localCover == null) cacheRemoteCover(song, generation)
            }
            control.setPlayingState(if (state.playing) JMTCPlayingState.PLAYING else JMTCPlayingState.PAUSED)
            control.setParameters(
                JMTCParameters(
                    when (state.repeatMode) {
                        1 -> JMTCParameters.LoopStatus.Playlist
                        2 -> JMTCParameters.LoopStatus.Track
                        else -> JMTCParameters.LoopStatus.None
                    },
                    state.volume.toDouble(),
                    1.0,
                    false,
                ),
            )
            if (state.durationMs > 0) {
                val end = state.durationMs.toLong()
                control.setTimelineProperties(JMTCTimelineProperties(0L, end, 0L, end))
                control.setPosition(state.positionMs.coerceAtMost(state.durationMs).toLong())
            }
            control.updateDisplay()
        }
    }

    private fun setMediaProperties(control: JMTC, song: Song, cover: File?) {
        control.setMediaProperties(
            JMTCMusicProperties(
                song.title,
                song.artist,
                song.album,
                song.artist,
                emptyArray(),
                -1,
                -1,
                cover,
            ),
        )
    }

    private fun cacheRemoteCover(song: Song, generation: Long) {
        val uri = song.coverUri?.takeIf { it.startsWith("http://") || it.startsWith("https://") } ?: return
        val key = songKey(song) ?: return
        val target = File(coverRoot, "${key.hashCode().toUInt().toString(16)}.img")
        if (target.isFile && target.length() > 0L) {
            submitCachedCover(song, target, generation)
            return
        }
        coverExecutor.execute {
            val downloaded = runCatching {
                val temporary = File(coverRoot, ".${target.name}.${System.nanoTime()}.part")
                val request = HttpRequest.newBuilder(URI.create(uri))
                    .timeout(Duration.ofSeconds(20))
                    .header("User-Agent", "TaotaoMusicWindows/1.0")
                    .GET()
                    .build()
                val response = coverClient.send(request, HttpResponse.BodyHandlers.ofFile(temporary.toPath()))
                if (response.statusCode() !in 200..299 || temporary.length() <= 0L) {
                    temporary.delete()
                    error("封面下载失败")
                }
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                target
            }.getOrNull() ?: return@execute
            submitCachedCover(song, downloaded, generation)
        }
    }

    private fun submitCachedCover(song: Song, cover: File, generation: Long) {
        if (coverGeneration.get() != generation) return
        mediaExecutor.execute {
            if (coverGeneration.get() != generation || songKey(lastSubmitted.song) != songKey(song)) return@execute
            jmtc?.let { control -> runCatching { setMediaProperties(control, song, cover); control.updateDisplay() } }
        }
    }

    private fun localCover(uri: String?): File? = runCatching {
        when {
            uri.isNullOrBlank() -> null
            uri.startsWith("file:", ignoreCase = true) -> File(URI(uri)).takeIf(File::isFile)
            !uri.startsWith("http", ignoreCase = true) -> File(uri).takeIf(File::isFile)
            else -> null
        }
    }.getOrNull()

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        tray?.close()
        tray = null
        coverGeneration.incrementAndGet()
        mediaExecutor.execute {
            jmtc?.let { control ->
                runCatching { control.setPlayingState(JMTCPlayingState.CLOSED) }
                runCatching { control.resetDisplay() }
                runCatching { control.setEnabled(false) }
            }
            jmtc = null
        }
        coverExecutor.shutdownNow()
        mediaExecutor.shutdown()
        runCatching { mediaExecutor.awaitTermination(800, TimeUnit.MILLISECONDS) }
        mediaExecutor.shutdownNow()
    }

    private fun songKey(song: Song?): String? = song?.let(DesktopStorage::songKey)

    private data class SystemMediaState(
        val song: Song? = null,
        val playing: Boolean = false,
        val positionMs: Int = 0,
        val durationMs: Int = 0,
        val repeatMode: Int = 0,
        val volume: Float = 1f,
    )

    private companion object {
        const val POSITION_UPDATE_NANOS = 1_000_000_000L
    }
}

private class TrayController private constructor(
    private val trayIcon: TrayIcon,
    private val popupHost: JWindow,
    private val popup: JPopupMenu,
    private val playPauseItem: JMenuItem,
    private val previousItem: JMenuItem,
    private val nextItem: JMenuItem,
) : AutoCloseable {
    private val hiddenNoticeShown = AtomicBoolean(false)

    fun update(songTitle: String?, playing: Boolean, hasSong: Boolean) {
        EventQueue.invokeLater {
            trayIcon.toolTip = songTitle?.let { "桃桃音乐 · ${it.take(48)}" } ?: "桃桃音乐"
            playPauseItem.text = if (playing) "暂停" else "播放"
            playPauseItem.isEnabled = hasSong
            previousItem.isEnabled = hasSong
            nextItem.isEnabled = hasSong
        }
    }

    fun notifyHidden() {
        if (!hiddenNoticeShown.compareAndSet(false, true)) return
        trayIcon.displayMessage("桃桃音乐", "窗口已隐藏，音乐会继续播放", TrayIcon.MessageType.INFO)
    }

    override fun close() {
        EventQueue.invokeLater {
            runCatching {
                popup.isVisible = false
                popupHost.isVisible = false
                popupHost.dispose()
                SystemTray.getSystemTray().remove(trayIcon)
            }
        }
    }

    companion object {
        fun create(callbacks: () -> DesktopSystemCallbacks): TrayController? {
            if (!SystemTray.isSupported()) return null
            // TrayIcon/PopupMenu 是 AWT 组件，必须在 Event Dispatch Thread 创建。
            // Compose 线程直接创建时，Windows 右键菜单可能不弹出或首次弹出位置异常。
            var controller: TrayController? = null
            val createAction = Runnable {
                controller = runCatching {
                    val menuFont = trayMenuFont()
                    val popup = JPopupMenu().apply {
                        font = menuFont
                        isOpaque = true
                        background = Color.WHITE
                        border = BorderFactory.createCompoundBorder(
                            BorderFactory.createLineBorder(Color(205, 205, 205)),
                            BorderFactory.createEmptyBorder(4, 0, 4, 0),
                        )
                    }
                    val popupHost = JWindow().apply {
                        isAlwaysOnTop = true
                        background = Color(0, 0, 0, 0)
                        setSize(1, 1)
                    }
                    fun menuItem(label: String, action: () -> Unit) = JMenuItem(label).apply {
                        font = menuFont
                        margin = Insets(7, 18, 7, 18)
                        preferredSize = Dimension(190, 34)
                        horizontalAlignment = SwingConstants.LEFT
                        addActionListener { action() }
                    }
                    val show = menuItem("打开桃桃音乐") { callbacks().onShow() }
                    val previous = menuItem("上一首") { callbacks().onPrevious() }
                    var playPauseRef: JMenuItem? = null
                    val playPause = menuItem("播放") {
                        val current = callbacks()
                        // 托盘文案由播放态维护，事件触发时按当前文字选择动作。
                        if (playPauseRef?.text == "暂停") current.onPause() else current.onPlay()
                    }.apply { playPauseRef = this; isEnabled = false }
                    val next = menuItem("下一首") { callbacks().onNext() }
                    val exit = menuItem("退出应用") { callbacks().onExit() }
                    popup.add(show)
                    popup.addSeparator()
                    popup.add(previous)
                    popup.add(playPause)
                    popup.add(next)
                    popup.addSeparator()
                    popup.add(exit)
                    val image = TrayController::class.java.getResourceAsStream("/taotao-music.png")
                        ?.use(ImageIO::read)
                        ?: createTrayImage()
                    popup.addPopupMenuListener(object : PopupMenuListener {
                        override fun popupMenuWillBecomeVisible(event: PopupMenuEvent?) = Unit

                        override fun popupMenuCanceled(event: PopupMenuEvent?) {
                            popupHost.isVisible = false
                        }

                        override fun popupMenuWillBecomeInvisible(event: PopupMenuEvent?) {
                            popupHost.isVisible = false
                        }
                    })
                    val icon = TrayIcon(image, "桃桃音乐").apply {
                        isImageAutoSize = true
                        addActionListener { callbacks().onShow() }
                        addMouseListener(object : MouseAdapter() {
                            override fun mousePressed(event: MouseEvent) = showPopup(event)
                            override fun mouseReleased(event: MouseEvent) = showPopup(event)

                            private fun showPopup(event: MouseEvent) {
                                if (!event.isPopupTrigger) return
                                val height = popup.preferredSize.height
                                popupHost.setLocation(event.x, (event.y - height).coerceAtLeast(0))
                                popupHost.isVisible = true
                                popup.show(popupHost.contentPane, 0, 0)
                            }
                        })
                    }
                    SystemTray.getSystemTray().add(icon)
                    TrayController(icon, popupHost, popup, playPause, previous, next)
                }.getOrNull()
            }
            if (EventQueue.isDispatchThread()) {
                createAction.run()
            } else {
                runCatching { EventQueue.invokeAndWait(createAction) }
            }
            return controller
        }

        private fun trayMenuFont(): Font {
            val installed = GraphicsEnvironment.getLocalGraphicsEnvironment()
                .availableFontFamilyNames
                .toSet()
            val family = listOf("Noto Sans SC", "Microsoft YaHei UI", "Microsoft YaHei", "宋体", "SimSun-ExtB", "Dialog")
                .firstOrNull(installed::contains)
                ?: "Dialog"
            return Font(family, Font.PLAIN, 12)
        }

        private fun createTrayImage(): BufferedImage = BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB).also { image ->
            val graphics = image.createGraphics()
            try {
                graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                graphics.color = Color(250, 94, 91)
                graphics.fillRoundRect(1, 1, 30, 30, 8, 8)
                graphics.color = Color.WHITE
                graphics.fillOval(8, 19, 8, 7)
                graphics.fillOval(19, 16, 8, 7)
                graphics.fillRect(14, 8, 3, 14)
                graphics.fillRect(25, 6, 3, 13)
                graphics.fillRect(14, 6, 14, 4)
            } finally {
                graphics.dispose()
            }
        }
    }
}
