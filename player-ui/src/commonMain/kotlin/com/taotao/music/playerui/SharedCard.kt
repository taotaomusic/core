package com.taotao.music.playerui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import com.taotao.music.playerui.theme.TaotaoShapes
import com.taotao.music.playerui.theme.TaotaoSpacing

/**
 * 三端共用的圆角卡片。
 *
 * 收敛自 `components.kt` 的 `CardWithTitle` 与各处裸 `Column.background`：
 * 此前卡片的圆角、背景色、标题内边距在每个页面各写一遍。
 *
 * 圆角走 [TaotaoShapes.card]，背景走 `colorScheme.surface`，
 * 标题复用 [SharedSectionHeader] 的 [SharedSectionLevel.CARD] 档。
 *
 * [content] 不额外包内边距 —— 卡片内容的留白由内容自身决定
 * （设置页的 `SettingRow` 自带内边距），避免出现「双份内边距」。
 */
@Composable
fun SharedCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    titleColor: Color = MaterialTheme.colorScheme.primary,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(TaotaoShapes.card)
            .background(MaterialTheme.colorScheme.surface),
    ) {
        if (!title.isNullOrBlank()) {
            SharedSectionHeader(
                title = title,
                level = SharedSectionLevel.CARD,
                titleColor = titleColor,
                modifier = Modifier.padding(
                    horizontal = TaotaoSpacing.sm,
                    vertical = TaotaoSpacing.sm,
                ),
            )
        }
        content()
    }
}
