package com.taotao.music.playerui

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.taotao.music.playerui.theme.TaotaoSpacing

/** 桌面端宽屏布局，左右分栏。 */
@Composable
fun PlayerWideLayout(
    state: PlayerUiState,
    actions: PlayerActions,
    positionLabel: String,
    durationLabel: String,
    modifier: Modifier = Modifier,
    capabilities: PlayerCapabilities = PlayerCapabilities(),
    titleTrailingContent: (@Composable RowScope.() -> Unit)? = null,
    metadataTrailingContent: (@Composable RowScope.() -> Unit)? = null,
    headerActions: (@Composable RowScope.() -> Unit)? = null,
    controlLeadingContent: (@Composable () -> Unit)? = null,
    controlTrailingContent: (@Composable () -> Unit)? = null,
    artworkContent: (@Composable () -> Unit)? = null,
) {
    if (artworkContent == null) {
        PlayerPlaybackDetails(
            state = state,
            actions = actions,
            positionLabel = positionLabel,
            durationLabel = durationLabel,
            modifier = modifier,
            capabilities = capabilities,
            titleTrailingContent = titleTrailingContent,
            metadataTrailingContent = metadataTrailingContent,
            headerActions = headerActions,
            controlLeadingContent = controlLeadingContent,
            controlTrailingContent = controlTrailingContent,
        )
    } else {
        Row(
            modifier = modifier.fillMaxWidth().padding(TaotaoSpacing.xxl),
            horizontalArrangement = Arrangement.spacedBy(TaotaoSpacing.xxxl),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                artworkContent()
            }
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                PlayerPlaybackDetails(
                    state = state,
                    actions = actions,
                    positionLabel = positionLabel,
                    durationLabel = durationLabel,
                    capabilities = capabilities,
                    titleTrailingContent = titleTrailingContent,
                    metadataTrailingContent = metadataTrailingContent,
                    headerActions = headerActions,
                    controlLeadingContent = controlLeadingContent,
                    controlTrailingContent = controlTrailingContent,
                )
            }
        }
    }
}
