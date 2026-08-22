package com.taotao.music.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** 主色：珊瑚红，用于强调按钮、选中态和图标。 */
val TaotaoCoral = Color(0xFFFF6B5F)

/** 底色：偏暖的浅色背景，卡片用纯白叠在其上形成层次。 */
val TaotaoBackground = Color(0xFFFFF9F7)

/**
 * 全局主题：所有页面统一从这里取配色。
 *
 * 抽取的原因是登录页此前直接使用 [MaterialTheme] 默认配色，
 * 与应用内的珊瑚红主题不一致，进入首页时会突然换色。
 */
@Composable
fun TaotaoTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(background = TaotaoBackground, primary = TaotaoCoral),
        content = content,
    )
}

/** 骨架屏占位色。比背景略深，比纯灰更贴主题。 */
val TaotaoSkeleton = Color(0xFFF3E7E4)
