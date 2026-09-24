package com.taotao.music.playerui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 滑块轨道厚度与滑块直径。
 *
 * 刻意不进 TaotaoSizes：两者必须成比例才好看（滑块要明显大于轨道），
 * 单独挪动任何一个都会让进度条失衡，所以成对定义、只在本文件使用。
 */
private val SliderTrackHeight = 4.dp
private val SliderThumbSize = 12.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppleStyleSlider(
    progress: Float,
    onProgressChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onProgressChangeFinished: () -> Unit = {},
) {
    Slider(
        value = progress,
        onValueChange = onProgressChange,
        onValueChangeFinished = onProgressChangeFinished,
        enabled = enabled,
        modifier = modifier.fillMaxWidth(),
        colors = SliderDefaults.colors(
            thumbColor = MaterialTheme.colorScheme.onSurface,
            activeTrackColor = MaterialTheme.colorScheme.onSurface,
            inactiveTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)
        ),
        thumb = {
            Box(
                modifier = Modifier
                    .size(SliderThumbSize)
                    .background(MaterialTheme.colorScheme.onSurface, CircleShape)
            )
        },
        track = { sliderState ->
            SliderDefaults.Track(
                sliderState = sliderState,
                colors = SliderDefaults.colors(
                    activeTrackColor = MaterialTheme.colorScheme.onSurface,
                    inactiveTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)
                ),
                modifier = Modifier.height(SliderTrackHeight).clip(CircleShape)
            )
        }
    )
}

@Composable
fun ApplePlayButton(
    isPlaying: Boolean,
    onClick: () -> Unit,
    playIcon: ImageVector,
    pauseIcon: ImageVector,
    modifier: Modifier = Modifier,
    size: Dp = TaotaoSizes.playButton,
    iconSize: Dp = TaotaoSizes.playButtonIcon,
    enabled: Boolean = true,
) {
    Box(
        modifier = modifier
            .size(size)
            .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = if (isPlaying) pauseIcon else playIcon,
            contentDescription = if (isPlaying) "暂停" else "播放",
            modifier = Modifier.size(iconSize),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 1f else 0.38f),
        )
    }
}

