package com.taotao.music.playerui.skin

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import com.taotao.music.playerui.theme.TaotaoStroke

/**
 * 封面皮肤选择面板（三端共用）：底部弹层里逐个预览 [CoverSkinId.entries]，
 * 预览缩略图直接用真实皮肤控件绘制（同一个模板入口 [CoverSkin]），保证「所见即所选」。
 * 点选回调 [onSelect] 由接入端决定后续行为（Android 关闭面板并落盘；Web 立即生效并写 localStorage）。
 * 皮肤数量已超过一行上限：FlowRow 自动换行，间距走主题 spacing token。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
public fun CoverSkinSheet(
    current: CoverSkinId,
    imageLoader: CoverImageLoader,
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
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(TaotaoSpacing.sm),
                verticalArrangement = Arrangement.spacedBy(TaotaoSpacing.sm),
            ) {
                for (id in CoverSkinId.entries) {
                    CoverSkinOption(
                        id = id,
                        selected = id == current,
                        imageLoader = imageLoader,
                        onClick = { onSelect(id) },
                    )
                }
            }
        }
    }
}

/** 单个皮肤选项：静态缩略预览（暂停态、不转、无图走主题色兜底）+ 名称 + 选中描边。 */
@Composable
private fun CoverSkinOption(
    id: CoverSkinId,
    selected: Boolean,
    imageLoader: CoverImageLoader,
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
                    width = if (selected) TaotaoStroke.medium else TaotaoStroke.thin,
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                    shape = MaterialTheme.shapes.small,
                ),
            contentAlignment = Alignment.Center,
        ) {
            // 预览不传封面：无图态正好展示各皮肤自带的主题色兜底，不依赖网络图。
            CoverSkin(
                skin = id,
                coverUri = null,
                fallbackColor = MaterialTheme.colorScheme.primary,
                isPlaying = false,
                rotationDegrees = 0f,
                discSize = SKIN_THUMB_SIZE - THUMB_INNER_PADDING,
                imageLoader = imageLoader,
            )
        }
        Spacer(Modifier.height(TaotaoSpacing.xxs))
        Text(
            id.label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 预览缩略图边长。
 *
 * 数值与 `TaotaoSizes.avatar`（64dp）相同，但语义属于皮肤缩略图域：
 * 头像是「个人页列表元素」，这里是「皮肤选择面板的预览框」，二者将来可能独立调整，
 * 因此不强行换用同一 token，避免改头像尺寸时缩略图被静默牵连。
 */
private val SKIN_THUMB_SIZE = 64.dp

/**
 * 皮肤控件在缩略图里留出的内边距，避免贴边。
 *
 * 同样属于皮肤缩略图域的局部画布余量，不对应任何现有 spacing 档位，
 * 不参与全应用间距节奏，故保持局部常量、不强行归档。
 */
private val THUMB_INNER_PADDING = 12.dp
