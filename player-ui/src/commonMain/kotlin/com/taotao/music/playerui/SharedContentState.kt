package com.taotao.music.playerui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import com.taotao.music.playerui.theme.TaotaoSizes
import com.taotao.music.playerui.theme.TaotaoSpacing
import com.taotao.music.playerui.theme.TaotaoStroke

/** 内容区域在加载、空数据和失败时使用的统一状态类型。 */
enum class SharedContentStateType {
    LOADING,
    EMPTY,
    ERROR,
}

/**
 * 三端共用的内容状态视图。
 *
 * 文案和重试、新建等操作由页面传入，公共层只负责稳定的视觉层级和无障碍播报。
 *
 * 间距走 [TaotaoSpacing]：此前这里的 14dp / 6dp 与其他页面的 12dp / 8dp 不成体系，
 * 空态在同一应用里上下留白不一致。
 */
@Composable
fun SharedContentState(
    type: SharedContentStateType,
    title: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    actionContent: (@Composable RowScope.() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = TaotaoSpacing.xxl, vertical = TaotaoSpacing.xxl)
            .semantics { liveRegion = LiveRegionMode.Polite },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        when (type) {
            SharedContentStateType.LOADING -> {
                CircularProgressIndicator(
                    modifier = Modifier.size(TaotaoSizes.stateIcon),
                    color = MaterialTheme.colorScheme.primary,
                    strokeWidth = TaotaoStroke.thick,
                )
            }

            SharedContentStateType.EMPTY -> {
                Icon(
                    imageVector = Icons.Default.MusicNote,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(TaotaoSizes.stateIcon),
                )
            }

            SharedContentStateType.ERROR -> {
                Icon(
                    imageVector = Icons.Default.ErrorOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(TaotaoSizes.stateIcon),
                )
            }
        }

        Spacer(Modifier.height(TaotaoSpacing.md))
        Text(
            text = title,
            color = if (type == SharedContentStateType.ERROR) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        if (!description.isNullOrBlank()) {
            Spacer(Modifier.height(TaotaoSpacing.xs))
            Text(
                text = description,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
        }
        if (actionContent != null) {
            Spacer(Modifier.height(TaotaoSpacing.md))
            Row(
                horizontalArrangement = Arrangement.spacedBy(TaotaoSpacing.xs),
                verticalAlignment = Alignment.CenterVertically,
                content = actionContent,
            )
        }
    }
}
