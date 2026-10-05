package com.taotao.music.ui.player.skin

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.taotao.music.playerui.theme.TaotaoSpacing
import com.taotao.music.ui.theme.TaotaoCoral

/**
 * 封面皮肤选择面板：底部弹层里逐个预览 [CoverSkinId.entries]，
 * 预览缩略图直接用真实皮肤控件绘制（同一个模板入口 [CoverSkin]），保证「所见即所选」。
 * 点选即写入 [CoverSkinStore] 并收起面板；详情页的封面区读 Compose 状态即时换装。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CoverSkinSheet(
    current: CoverSkinId,
    onDismiss: () -> Unit,
    onSelect: (CoverSkinId) -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = TaotaoSpacing.screenHorizontal)
                .padding(bottom = TaotaoSpacing.lg),
        ) {
            Text(
                "封面皮肤",
                modifier = Modifier.padding(bottom = TaotaoSpacing.md),
                style = MaterialTheme.typography.titleMedium,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                for (id in CoverSkinId.entries) {
                    CoverSkinOption(
                        id = id,
                        selected = id == current,
                        onClick = { onSelect(id) },
                    )
                }
            }
        }
    }
}

/** 单个皮肤选项：静态缩略预览（暂停态、不转）+ 名称 + 选中描边。 */
@Composable
private fun CoverSkinOption(
    id: CoverSkinId,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(MaterialTheme.shapes.small)
            .clickable(onClick = onClick, role = Role.RadioButton),
    ) {
        Box(
            Modifier
                .size(SKIN_THUMB_SIZE)
                .clip(MaterialTheme.shapes.small)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .border(
                    width = if (selected) 2.dp else 1.dp,
                    color = if (selected) TaotaoCoral else MaterialTheme.colorScheme.outlineVariant,
                    shape = MaterialTheme.shapes.small,
                ),
            contentAlignment = Alignment.Center,
        ) {
            // 预览不传封面：无图态正好展示各皮肤自带的主题色兜底，不依赖网络图。
            CoverSkin(
                skin = id,
                coverUri = null,
                fallbackColor = TaotaoCoral,
                isPlaying = false,
                rotationDegrees = 0f,
                discSize = SKIN_THUMB_SIZE - THUMB_INNER_PADDING,
            )
        }
        Spacer(Modifier.height(TaotaoSpacing.xxs))
        Text(
            id.label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) TaotaoCoral else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 预览缩略图边长。 */
private val SKIN_THUMB_SIZE = 64.dp

/** 皮肤控件在缩略图里留出的内边距，避免贴边。 */
private val THUMB_INNER_PADDING = 12.dp
