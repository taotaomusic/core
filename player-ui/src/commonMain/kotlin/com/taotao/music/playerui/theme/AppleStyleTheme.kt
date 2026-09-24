package com.taotao.music.playerui.theme

/**
 * 播放器公共组件使用的圆角规格。
 *
 * **已被 [TaotaoShapes] 取代**，此处保留为兼容别名，调用点可以逐步迁移、不必一次性改完。
 * 数值有轻微收敛（`ButtonShape` 12→14dp、`CardShape` 32→28dp），原因见 [TaotaoShapes] 的说明。
 *
 * 迁移对照：
 * - `AppleStyleTheme.ButtonShape` → [TaotaoShapes.button]
 * - `AppleStyleTheme.ArtworkShape` → [TaotaoShapes.artwork]
 * - `AppleStyleTheme.CardShape` → [TaotaoShapes.card]
 * - `AppleStyleTheme.FullRadius` → [TaotaoShapes.pill]
 */
@Deprecated(
    message = "改用 TaotaoShapes 的统一圆角刻度，本对象只作为迁移期的兼容别名。",
    replaceWith = ReplaceWith("TaotaoShapes.button"),
)
object AppleStyleTheme {
    val ArtworkShape = TaotaoShapes.artwork
    val CardShape = TaotaoShapes.card
    val ButtonShape = TaotaoShapes.button
    val FullRadius = TaotaoShapes.pill
}

