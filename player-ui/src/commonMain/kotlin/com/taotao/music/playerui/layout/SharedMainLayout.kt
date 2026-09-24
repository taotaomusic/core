package com.taotao.music.playerui.layout

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import com.taotao.music.playerui.theme.TaotaoElevation

/** 平台导航项；共享布局不解释页面含义，只负责一致的视觉和选中反馈。 */
@Immutable
data class SharedNavigationItem(
    val id: Int,
    val label: String,
    val icon: ImageVector,
    val contentDescription: String = label,
)

/**
 * 移动端共用的主页面骨架。
 *
 * 页面状态由平台层持有，导航项、迷你播放器和正文全部通过参数传入，避免组件隐藏全局状态。
 */
@Composable
fun SharedMainLayout(
    selectedNavigationId: Int,
    navigationItems: List<SharedNavigationItem>,
    onNavigationSelected: (Int) -> Unit,
    miniPlayerContent: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)),
            ) {
                Box(modifier = Modifier.fillMaxWidth()) {
                    miniPlayerContent()
                }
                NavigationBar(
                    containerColor = Color.Transparent,
                    tonalElevation = TaotaoElevation.flat,
                    windowInsets = WindowInsets.navigationBars,
                ) {
                    navigationItems.forEach { item ->
                        val selected = selectedNavigationId == item.id
                        NavigationBarItem(
                            selected = selected,
                            onClick = { onNavigationSelected(item.id) },
                            icon = {
                                Icon(
                                    imageVector = item.icon,
                                    contentDescription = item.contentDescription,
                                )
                            },
                            label = {
                                Text(
                                    text = item.label,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                )
                            },
                            colors = NavigationBarItemDefaults.colors(
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                        )
                    }
                }
            }
        },
        content = content,
    )
}
