package com.taotao.music.playerui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * 三端共用的返回按钮。
 *
 * 收敛自四处各写一遍、却连图标和 `contentDescription` 都逐字相同的实现：
 * `MineLibraryPages` 的 `LibraryBackButton`、`PlaylistPages` 的 `PlaylistPageHeader`、
 * `SearchPage` 与 `SettingsPage` 里的行内 `IconButton`。
 *
 * 用 `AutoMirrored` 变体而不是 `Icons.Default.ArrowBack`：阿拉伯语等从右向左的
 * 语言环境下箭头需要自动翻转，而 `Default` 那套不会。
 *
 * 作为 [SharedSectionHeader] 的 `leading` 使用时直接传入即可 ——
 * `IconButton` 自带 48dp 触达区，不需要额外包一层。
 */
@Composable
fun SharedBackButton(onBack: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(onClick = onBack, modifier = modifier) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
    }
}
