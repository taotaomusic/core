package com.taotao.music.desktop

import org.bytedeco.javacv.FFmpegFrameGrabber
import org.bytedeco.javacv.Frame
import org.bytedeco.javacv.FrameGrabber
import java.io.File
import java.net.URI
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine

/**
 * 基于 FFmpeg 的 Windows 桌面播放器适配器。
 *
 * FFmpeg 负责网络流和本地文件解码，Java Sound 负责把 PCM 送到系统音频设备。每次播放都会
 * 创建独立会话，旧会话先失效再释放 native 资源，避免快速切歌时旧线程覆盖新歌曲状态。
 * 状态回调发生在解码线程，调用方需要切回 Compose UI 线程。
 */
class DesktopPlayer {
    val hasMedia: Boolean
        // 已经自然结束的会话不能再走 resume()；上层会重新装载直链，从头开始播放。
        get() = activeSession?.let { !it.cancelled.get() && !it.failed.get() && !it.finished.get() } == true

    /** 当前底层媒体代际；每次播放或停止都会变化，用于丢弃排队的旧结束事件。 */
    val mediaGeneration: Long
        get() = sessionGeneration.get()

    @Volatile var isPlaying: Boolean = false
        private set
    @Volatile var positionMs: Int = 0
        private set
    @Volatile var durationMs: Int = 0
        private set
    @Volatile var error: String? = null
        private set
    @Volatile var volume: Float = 1f
        set(value) {
            field = value.coerceIn(0f, 1f)
            notifyState()
        }

    /** 参数是触发状态变化时的媒体代际，调用方可丢弃排队后已经过期的事件。 */
    var onStateChanged: ((Long) -> Unit)? = null
    /** 参数依次为产生事件的媒体代际、最终位置和时长。 */
    var onEnded: ((Long, Int, Int) -> Unit)? = null

    @Volatile
    private var activeSession: PlaybackSession? = null
    @Volatile
    private var currentUri: String? = null
    private val sessionGeneration = AtomicLong(0L)
    private val lastPositionNotifyNanos = AtomicLong(0L)
    private val stateLock = Any()

    fun play(uri: String, startPositionMs: Int = 0) {
        val normalizedUri = normalizeUri(uri)
        val safeStartPositionMs = startPositionMs.coerceAtLeast(0)
        val session = synchronized(stateLock) {
            val generation = sessionGeneration.incrementAndGet()
            activeSession?.cancel()
            PlaybackSession(generation, normalizedUri).also {
                activeSession = it
                currentUri = normalizedUri
                error = null
                isPlaying = false
                positionMs = safeStartPositionMs
                durationMs = 0
            }
        }
        notifyState()

        val thread = Thread(
            { runPlayback(session, safeStartPositionMs) },
            "taotao-ffmpeg-${session.generation}",
        )
        thread.isDaemon = true
        session.thread = thread
        thread.start()
    }

    fun pause() {
        val changed = synchronized(stateLock) {
            val session = activeSession ?: return
            if (!isActive(session)) return
            session.pause()
            isPlaying = false
            true
        }
        if (!changed) return
        notifyState()
    }

    fun resume() {
        val restart = synchronized(stateLock) {
            val session = activeSession ?: return
            if (!isActive(session)) return
            if (session.finished.get() || session.failed.get()) {
                currentUri to (positionMs.takeIf { it < durationMs } ?: 0)
            } else {
                session.resume()
                isPlaying = true
                null
            }
        }
        if (restart != null) {
            restart.first?.let { play(it, restart.second ?: 0) }
            return
        }
        notifyState()
    }

    fun seek(positionMs: Int) {
        val action = synchronized(stateLock) {
            val session = activeSession ?: return
            if (!isActive(session)) return
            val targetMs = positionMs.coerceIn(0, durationMs.takeIf { it > 0 } ?: Int.MAX_VALUE)
            if (session.finished.get() || session.failed.get()) {
                SeekAction.Restart(currentUri, targetMs)
            } else {
                session.requestSeek(targetMs)
                this.positionMs = targetMs
                SeekAction.Pending
            }
        }
        if (action is SeekAction.Restart) {
            action.uri?.let { play(it, action.positionMs) }
            return
        }
        notifyState()
    }

    fun stop() {
        synchronized(stateLock) {
            sessionGeneration.incrementAndGet()
            activeSession?.cancel()
            activeSession = null
            currentUri = null
            isPlaying = false
            positionMs = 0
            durationMs = 0
            error = null
        }
        notifyState()
    }

    fun release() = stop()

    private fun runPlayback(session: PlaybackSession, startPositionMs: Int) {
        var grabber: FFmpegFrameGrabber? = null
        var output: AudioOutput? = null
        try {
            grabber = FFmpegFrameGrabber(session.uri).apply {
                // 防止失效的网络会话永久阻塞 native 读取，单位为微秒。
                setOption("rw_timeout", NETWORK_TIMEOUT_US)
                setOption("timeout", NETWORK_TIMEOUT_US)
                start()
            }
            session.grabber = grabber
            ensureActive(session)
            check(grabber.hasAudio()) { "音频中没有可播放的声音轨道" }

            session.durationMs = microsecondsToMilliseconds(grabber.lengthInTime)
            updateActiveState(session) { durationMs = session.durationMs }
            val outputChannels = grabber.audioChannels.coerceIn(1, 2)
            output = openAudioOutput(grabber.sampleRate, outputChannels)
            session.outputLine = output.line
            ensureActive(session)
            grabber.audioChannels = outputChannels
            grabber.sampleRate = output.sampleRate
            grabber.sampleMode = if (output.sampleBits == 24) {
                FrameGrabber.SampleMode.FLOAT
            } else {
                FrameGrabber.SampleMode.SHORT
            }

            var mediaOriginMs = startPositionMs
            if (startPositionMs > 0) {
                grabber.timestamp = startPositionMs.toLong() * MICROSECONDS_PER_MILLISECOND
            }
            if (!session.paused) output.line.start()
            var lineOriginUs = output.line.microsecondPosition
            if (updateActiveState(session) { isPlaying = !session.paused }) notifyState()

            while (isActive(session)) {
                session.awaitIfPaused()
                ensureActive(session)
                session.takeSeek()?.let { targetMs ->
                    output.line.flush()
                    grabber.timestamp = targetMs.toLong() * MICROSECONDS_PER_MILLISECOND
                    mediaOriginMs = targetMs
                    lineOriginUs = output.line.microsecondPosition
                    if (updateActiveState(session) { positionMs = targetMs }) notifyState()
                }
                if (session.paused) continue

                val frame = grabber.grabSamples() ?: break
                val pcm = frame.toPcm(output.sampleBits, volume)
                var offset = 0
                while (offset < pcm.size && isActive(session)) {
                    val written = output.line.write(pcm, offset, pcm.size - offset)
                    if (written <= 0) break
                    offset += written
                }
                val resolvedPositionMs = (mediaOriginMs +
                    (output.line.microsecondPosition - lineOriginUs) / MICROSECONDS_PER_MILLISECOND)
                    .toInt()
                    .coerceIn(0, session.durationMs.takeIf { it > 0 } ?: Int.MAX_VALUE)
                if (updateActiveState(session) { positionMs = resolvedPositionMs }) notifyPosition()
            }

            if (isActive(session)) {
                output.line.drain()
                session.finished.set(true)
                var endedPositionMs = 0
                val ended = updateActiveState(session) {
                    isPlaying = false
                    if (session.durationMs > 0) positionMs = session.durationMs
                    endedPositionMs = positionMs
                }
                if (ended) {
                    notifyState()
                    runCatching { onEnded?.invoke(session.generation, endedPositionMs, session.durationMs) }
                }
            }
        } catch (cancelled: PlaybackCancelledException) {
            // 正常的切歌或停止路径，不向界面显示错误。
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (throwable: Throwable) {
            if (updateActiveState(session) {
                session.failed.set(true)
                error = readableError(throwable)
                isPlaying = false
            }) {
                notifyState()
            }
        } finally {
            runCatching { output?.line?.stop() }
            runCatching { output?.line?.flush() }
            runCatching { output?.line?.close() }
            runCatching { grabber?.stop() }
            session.outputLine = null
            session.grabber = null
        }
    }

    private fun openAudioOutput(preferredSampleRate: Int, channels: Int): AudioOutput {
        val sampleRates = listOf(preferredSampleRate, 96_000, 48_000, 44_100)
            .filter { it in 8_000..192_000 }
            .distinct()
        val attempts = buildList {
            sampleRates.forEach { sampleRate ->
                add(sampleRate to 24)
                add(sampleRate to 16)
            }
        }
        var lastError: Throwable? = null
        for ((sampleRate, sampleBits) in attempts) {
            val format = AudioFormat(sampleRate.toFloat(), sampleBits, channels, true, false)
            try {
                val info = DataLine.Info(SourceDataLine::class.java, format)
                val line = AudioSystem.getLine(info) as SourceDataLine
                line.open(format)
                return AudioOutput(line, sampleRate, sampleBits)
            } catch (throwable: Throwable) {
                lastError = throwable
            }
        }
        throw IllegalStateException("找不到可用的 Windows 音频输出设备", lastError)
    }

    private fun normalizeUri(uri: String): String = when {
        uri.startsWith("file:", ignoreCase = true) -> runCatching {
            Paths.get(URI(uri)).toAbsolutePath().toString()
        }.getOrDefault(uri)
        File(uri).isFile -> File(uri).absolutePath
        else -> uri.trim()
    }

    private fun notifyState() = runCatching { onStateChanged?.invoke(sessionGeneration.get()) }

    /** 播放进度更新很频繁，限制回调频率避免 Compose 协程队列被填满。 */
    private fun notifyPosition() {
        val now = System.nanoTime()
        val previous = lastPositionNotifyNanos.get()
        if (now - previous < 100_000_000L || !lastPositionNotifyNanos.compareAndSet(previous, now)) return
        notifyState()
    }

    private fun isActive(session: PlaybackSession): Boolean =
        !session.cancelled.get() && session.generation == sessionGeneration.get() && activeSession === session

    private fun ensureActive(session: PlaybackSession) {
        if (!isActive(session)) throw PlaybackCancelledException
    }

    private inline fun updateActiveState(session: PlaybackSession, update: () -> Unit): Boolean =
        synchronized(stateLock) {
            if (!isActive(session)) return@synchronized false
            update()
            true
        }

    private fun Frame.toPcm(sampleBits: Int, volume: Float): ByteArray {
        val buffers = samples?.map { it.duplicateForReading() }
            ?: throw IllegalStateException("FFmpeg 未返回可用的音频采样")
        val channels = audioChannels.coerceAtLeast(1)
        return when (sampleBits) {
            24 -> floatPcm(buffers, channels, volume)
            16 -> shortPcm(buffers, channels, volume)
            else -> error("不支持的 PCM 位深：$sampleBits")
        }
    }

    private fun java.nio.Buffer.duplicateForReading(): java.nio.Buffer = when (this) {
        is ShortBuffer -> duplicate()
        is FloatBuffer -> duplicate()
        else -> throw IllegalStateException("FFmpeg 返回了不支持的采样类型：${javaClass.simpleName}")
    }

    private fun shortPcm(buffers: List<java.nio.Buffer>, channels: Int, volume: Float): ByteArray {
        val shortBuffers = buffers.map {
            it as? ShortBuffer ?: throw IllegalStateException("FFmpeg 未输出 16-bit PCM")
        }
        if (shortBuffers.size == 1) {
            val input = shortBuffers.single()
            return ByteArray(input.remaining() * 2).also { bytes ->
                var offset = 0
                while (input.hasRemaining()) {
                    val sample = (input.get() * volume).toInt()
                        .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    bytes[offset++] = sample.toByte()
                    bytes[offset++] = (sample shr 8).toByte()
                }
            }
        }
        val frames = shortBuffers.take(channels).minOfOrNull { it.remaining() } ?: 0
        return ByteArray(frames * channels * 2).also { bytes ->
            var offset = 0
            repeat(frames) {
                repeat(channels) { channel ->
                    val sample = (shortBuffers[channel].get() * volume).toInt()
                        .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    bytes[offset++] = sample.toByte()
                    bytes[offset++] = (sample shr 8).toByte()
                }
            }
        }
    }

    private fun floatPcm(buffers: List<java.nio.Buffer>, channels: Int, volume: Float): ByteArray {
        val floatBuffers = buffers.map {
            it as? FloatBuffer ?: throw IllegalStateException("FFmpeg 未输出浮点 PCM")
        }
        fun encode(samples: Int, nextSample: () -> Float): ByteArray = ByteArray(samples * 3).also { bytes ->
            var offset = 0
            repeat(samples) {
                val normalized = (nextSample() * volume).coerceIn(-1f, 1f)
                val sample = (normalized * PCM_24_MAX).toInt().coerceIn(PCM_24_MIN, PCM_24_MAX)
                bytes[offset++] = sample.toByte()
                bytes[offset++] = (sample shr 8).toByte()
                bytes[offset++] = (sample shr 16).toByte()
            }
        }
        if (floatBuffers.size == 1) {
            val input = floatBuffers.single()
            return encode(input.remaining()) { input.get() }
        }
        val planes = floatBuffers.take(channels)
        val frames = planes.minOfOrNull { it.remaining() } ?: 0
        var channel = 0
        return encode(frames * channels) {
            planes[channel].get().also { channel = (channel + 1) % channels }
        }
    }

    private fun readableError(throwable: Throwable): String {
        val detail = generateSequence(throwable) { it.cause }
            .mapNotNull { it.message?.takeIf(String::isNotBlank) }
            .firstOrNull()
        return detail?.let { "播放失败：$it" } ?: "播放失败，请检查音频地址或输出设备"
    }

    private fun microsecondsToMilliseconds(value: Long): Int =
        (value / MICROSECONDS_PER_MILLISECOND).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()

    private data class AudioOutput(
        val line: SourceDataLine,
        val sampleRate: Int,
        val sampleBits: Int,
    )

    private sealed interface SeekAction {
        data object Pending : SeekAction
        data class Restart(val uri: String?, val positionMs: Int) : SeekAction
    }

    private class PlaybackSession(
        val generation: Long,
        val uri: String,
    ) {
        val cancelled = AtomicBoolean(false)
        val finished = AtomicBoolean(false)
        val failed = AtomicBoolean(false)
        @Volatile var durationMs = 0
        @Volatile var paused = false
        @Volatile var thread: Thread? = null
        @Volatile var grabber: FFmpegFrameGrabber? = null
        @Volatile var outputLine: SourceDataLine? = null
        private val pendingSeekMs = AtomicLong(NO_SEEK)
        private val pauseMonitor = Object()

        fun pause() {
            paused = true
            runCatching { outputLine?.stop() }
        }

        fun resume() {
            synchronized(pauseMonitor) {
                paused = false
                runCatching { outputLine?.start() }
                pauseMonitor.notifyAll()
            }
        }

        fun requestSeek(positionMs: Int) {
            pendingSeekMs.set(positionMs.toLong())
            // pause 后 write 可能在等待缓冲区腾出空间，flush 可让解码线程先处理 seek。
            runCatching { outputLine?.flush() }
            synchronized(pauseMonitor) { pauseMonitor.notifyAll() }
        }

        fun takeSeek(): Int? = pendingSeekMs.getAndSet(NO_SEEK)
            .takeIf { it != NO_SEEK }
            ?.toInt()

        @Throws(InterruptedException::class)
        fun awaitIfPaused() {
            synchronized(pauseMonitor) {
                while (paused && !cancelled.get() && pendingSeekMs.get() == NO_SEEK) {
                    pauseMonitor.wait()
                }
            }
        }

        fun cancel() {
            if (!cancelled.compareAndSet(false, true)) return
            synchronized(pauseMonitor) {
                paused = false
                pauseMonitor.notifyAll()
            }
            runCatching { outputLine?.stop() }
            runCatching { outputLine?.flush() }
            runCatching { outputLine?.close() }
            thread?.interrupt()
        }
    }

    private object PlaybackCancelledException : RuntimeException(null, null, false, false)

    companion object {
        private const val MICROSECONDS_PER_MILLISECOND = 1_000L
        private const val NETWORK_TIMEOUT_US = "10000000"
        private const val PCM_24_MAX = 8_388_607
        private const val PCM_24_MIN = -8_388_608
        private const val NO_SEEK = -1L
        private val supportedExtensions = setOf("mp3", "m4a", "aac", "wav", "flac", "ogg", "opus")
        private const val UNSUPPORTED_PRIVATE_EXTENSION = "nac"

        /** FFmpeg 可解码桌面端下载链路使用的常见有损与无损容器。 */
        fun supports(uri: String?): Boolean {
            if (uri.isNullOrBlank()) return false
            return extensionOf(uri) in supportedExtensions
        }

        /** 18 档对腾讯表示 NAC 私有容器，网易云则把它作为最高档的统一标记。 */
        fun supportsQuality(source: String, quality: Int): Boolean =
            quality != 18 || source.equals("netease", ignoreCase = true)

        /** 返回桌面端明确不支持的已知私有容器。 */
        fun unsupportedExtension(uri: String?): String? =
            uri?.takeIf(String::isNotBlank)?.let {
                if (extensionOf(it) == UNSUPPORTED_PRIVATE_EXTENSION || hasPrivateFormatQuery(it)) {
                    UNSUPPORTED_PRIVATE_EXTENSION
                } else {
                    null
                }
            }

        private fun hasPrivateFormatQuery(uri: String): Boolean {
            val parsed = runCatching { URI(uri) }.getOrNull() ?: return false
            return parsed.rawQuery.orEmpty().split('&').any { pair ->
                val key = pair.substringBefore('=').lowercase()
                val value = pair.substringAfter('=', "").lowercase()
                key in setOf("format", "ext", "type", "suffix", "filename") &&
                    (value == UNSUPPORTED_PRIVATE_EXTENSION || value.endsWith(".$UNSUPPORTED_PRIVATE_EXTENSION"))
            }
        }

        private fun extensionOf(uri: String): String {
            val path = runCatching { URI(uri).path }.getOrNull() ?: uri.substringBefore('?')
            return path.substringAfterLast('.', "").lowercase()
        }
    }
}
