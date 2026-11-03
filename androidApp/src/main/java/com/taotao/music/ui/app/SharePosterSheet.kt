package com.taotao.music.ui.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.taotao.music.playerui.theme.TaotaoShapes
import com.taotao.music.playerui.theme.TaotaoSizes
import com.taotao.music.playerui.theme.TaotaoSpacing
import com.taotao.music.playerui.theme.TaotaoTypeScale
import com.taotao.music.ui.common.SharePoster

/**
 * 分享歌曲的预览面板：先让用户看到生成的海报，再决定发图还是只发链接。
 *
 * 为什么不在点「分享」时直接拉起系统面板：海报要联网取短链和封面，慢的时候
 * 用户会以为没点上；预览面板同时承担加载态与确认作用。文字分享路径保留，
 * 照顾只想贴链接、不想发图的场景。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SharePosterSheet(
    loading: Boolean,
    poster: SharePoster?,
    onShareImage: () -> Unit,
    onCopyLink: () -> Unit,
    onShareLink: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (!loading && poster == null) return
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = TaotaoSpacing.screenHorizontal)
                .padding(bottom = TaotaoSpacing.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(TaotaoSpacing.md),
        ) {
            Text(
                text = if (loading) "正在生成分享图" else "分享这首歌",
                modifier = Modifier.fillMaxWidth(),
                style = TaotaoTypeScale.sectionTitle,
            )
            if (loading) {
                Box(
                    modifier = Modifier.fillMaxWidth().height(260.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(TaotaoSpacing.sm),
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(TaotaoSizes.progressInline),
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            "正在绘制封面与二维码…",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            } else if (poster != null) {
                AsyncImage(
                    model = poster.file,
                    contentDescription = "分享图预览",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth(0.66f)
                        .aspectRatio(2f / 3f)
                        .clip(TaotaoShapes.large),
                )
                Text(
                    text = poster.song.title,
                    style = TaotaoTypeScale.cardTitle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "图片已带二维码，对方扫码即可打开这首歌",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(TaotaoSpacing.sm),
                ) {
                    OutlinedButton(onClick = onCopyLink, modifier = Modifier.weight(1f)) {
                        Text("复制链接")
                    }
                    Button(onClick = onShareImage, modifier = Modifier.weight(1f)) {
                        Icon(
                            Icons.Default.Share,
                            contentDescription = null,
                            modifier = Modifier.size(TaotaoSizes.iconSm),
                        )
                        Text("分享图片", modifier = Modifier.padding(start = TaotaoSpacing.xxs))
                    }
                }
                TextButton(onClick = onShareLink) {
                    Text("只分享文字链接", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
