import os
import re

# 目标共享目录
shared_ui_dir = r"F:\music\player-ui\src\commonMain\kotlin\com\taotao\music\playerui\layout"
os.makedirs(shared_ui_dir, exist_ok=True)

# 1. 写入全局共享的 Apple 风格主布局
shared_layout_code = '''package com.taotao.music.playerui.layout

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.taotao.music.playerui.theme.AppleStyleTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SharedMainLayout(
    bottomTab: Int,
    onTabSelected: (Int) -> Unit,
    miniPlayerContent: @Composable () -> Unit,
    content: @Composable (PaddingValues) -> Unit
) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.95f))
            ) {
                // 迷你播放器容器，带果冻般圆角和呼吸感边缘
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    miniPlayerContent()
                }
                
                // 高级感底部导航栏
                NavigationBar(
                    containerColor = Color.Transparent,
                    tonalElevation = 0.dp,
                    windowInsets = WindowInsets.navigationBars
                ) {
                    val navItems = listOf(
                        Triple(0, "音乐", Icons.Default.MusicNote),
                        Triple(1, "AI", Icons.Default.AutoAwesome),
                        Triple(2, "聊天", Icons.Default.ChatBubbleOutline),
                        Triple(3, "我的", Icons.Default.Person)
                    )
                    
                    navItems.forEach { (index, label, icon) ->
                        NavigationBarItem(
                            selected = bottomTab == index,
                            onClick = { onTabSelected(index) },
                            icon = { Icon(icon, contentDescription = label) },
                            label = { 
                                Text(
                                    label, 
                                    fontWeight = if (bottomTab == index) FontWeight.Bold else FontWeight.Normal 
                                ) 
                            },
                            colors = NavigationBarItemDefaults.colors(
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        content(innerPadding)
    }
}
'''
with open(os.path.join(shared_ui_dir, "SharedMainLayout.kt"), "w", encoding="utf-8") as f:
    f.write(shared_layout_code)

print("SharedMainLayout generated.")
