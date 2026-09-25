package com.taotao.music.playerui

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** 移动端紧凑纵向布局，所有公共组件都在一个容器中。 */
@Composable
fun PlayerCompactLayout(
    state: PlayerUiState,
    actions: PlayerActions,
    positionLabel: String,
    durationLabel: String,
    modifier: Modifier = Modifier,
    capabilities: PlayerCapabilities = PlayerCapabilities(),
    titleTrailingContent: (@Composable RowScope.() -> Unit)? = null,
    metadataTrailingContent: (@Composable RowScope.() -> Unit)? = null,
    headerActions: (@Composable RowScope.() -> Unit)? = null,
    quickActions: (@Composable RowScope.() -> Unit)? = null,
    controlLeadingContent: (@Composable () -> Unit)? = null,
    controlTrailingContent: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
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
            quickActions = quickActions,
            controlLeadingContent = controlLeadingContent,
            controlTrailingContent = controlTrailingContent,
        )
    }
}
