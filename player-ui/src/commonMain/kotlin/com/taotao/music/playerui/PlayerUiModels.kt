package com.taotao.music.playerui

import com.taotao.music.model.Song

/** 播放详情公共 UI 只关心这些状态，不直接依赖 Android Media3 或 Windows 播放器。 */
data class PlayerUiState(
    val song: Song,
    val isPlaying: Boolean,
    val isBuffering: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val repeatMode: PlayerRepeatMode = PlayerRepeatMode.OFF,
    val errorMessage: String? = null,
)

/** 跨平台播放循环模式；各端在适配层转换为自己的播放器枚举。 */
enum class PlayerRepeatMode {
    OFF,
    ALL,
    ONE,
}

/** 公共播放控件的单向事件出口。页面业务不通过 UI 组件反向读取播放器对象。 */
data class PlayerActions(
    val onTogglePlaying: () -> Unit,
    val onSeek: (Long) -> Unit,
    val onSeekFinished: () -> Unit = {},
    val onToggleRepeat: () -> Unit,
    val onPrevious: (() -> Unit)? = null,
    val onNext: (() -> Unit)? = null,
)

/** 平台页面决定哪些可选能力显示，避免 Web 为了复用 UI 被迫实现下载或队列。 */
data class PlayerCapabilities(
    val showPreviousNext: Boolean = true,
    val showRepeat: Boolean = true,
    val showProgress: Boolean = true,
)

/** 把播放器时间换算为 UI 进度，未知时长统一返回 0。 */
internal fun normalizedPlayerProgress(positionMs: Long, durationMs: Long): Float {
    if (durationMs <= 0L) return 0f
    return (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
}

/** 把 UI 进度换回播放器时间，并限制在合法时长内。 */
internal fun playerPositionForProgress(progress: Float, durationMs: Long): Long {
    if (durationMs <= 0L) return 0L
    return (progress.coerceIn(0f, 1f) * durationMs).toLong()
}

private val displayWhitespace = Regex("\\s+")

/** 列表元数据可能带有换行或连续空白，三端统一压缩为单个空格。 */
internal fun normalizedDisplayText(value: String): String = value.replace(displayWhitespace, " ").trim()
