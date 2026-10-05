package com.taotao.music.ui.player.skin

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp

/**
 * 详情页封面皮肤标识，同时也是皮肤注册表：枚举顺序即选择面板里的展示顺序。
 *
 * **新增一个皮肤只需三步**：
 * 1. 在本枚举里按展示顺序加一个值，中文标签 [label] 就是选择面板里的名字；
 * 2. 在本包新建一个皮肤控件，参数与 [VinylSkin] 等现有皮肤完全一致（可直接复制一个改）；
 * 3. 在 [CoverSkin] 的 `when` 里加一个分支挂上它，然后跑
 *    `androidApp/src/test/java/com/taotao/music/ui/player/skin/CoverSkinIdTest.kt`
 *    确认注册表测试仍通过（中文标签唯一且非空、名称可无损往返）。
 *
 * 选择面板 [CoverSkinSheet] 直接遍历本枚举，因此枚举加完值后无需再改面板。
 */
enum class CoverSkinId(val label: String) {
    VINYL("黑胶唱片"),
    CD("CD 光碟"),
    CASSETTE("磁带机"),
    GLOW("光晕"),
    CARD("圆角卡片"),
    ;

    companion object {
        /** 没有任何本地记录（新安装 / 旧版本升级）时使用的默认皮肤。 */
        val DEFAULT = VINYL

        /**
         * 容错解析：名称对不上任何已知皮肤（含 null 与历史脏数据）时回退默认值，
         * 惯例与 [com.taotao.music.data.AppearanceMode.of] 一致。
         */
        fun of(name: String?): CoverSkinId = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

/**
 * 封面皮肤模板：详情页封面区唯一的绘制入口，按 [skin] 分派给具体皮肤控件。
 *
 * 所有皮肤共享同一份输入，实现新皮肤时不得收窄这些语义：
 * - [coverUri] / [fallbackColor]：封面图地址，以及无图时的兜底色（当前歌曲主题色）；
 * - [isPlaying]：播放态。皮肤用它表达「正在播放」（唱臂落下、卷轴转动、暂停压暗等），
 *   允许某个皮肤不用它，但必须在自己的注释里写明播放态由什么表达；
 * - [rotationDegrees]：调用方持有的封面旋转角，自带三个停止条件
 *   （暂停停、页面退到后台停、滑到歌词页停）。旋转类皮肤直接用它转盘面；
 *   不旋转的皮肤可以忽略；
 * - [discSize]：等边正方形边长，皮肤的全部几何都以它为基准等比缩放，
 *   且不得画出这个正方形之外（光晕这类向外发散的元素要在边界内淡出），
 *   这样详情页才能用同一个尺寸预算喂给所有皮肤。
 */
@Composable
internal fun CoverSkin(
    skin: CoverSkinId,
    coverUri: String?,
    fallbackColor: Color,
    isPlaying: Boolean,
    rotationDegrees: Float,
    discSize: Dp,
    modifier: Modifier = Modifier,
) {
    Box(modifier.size(discSize)) {
        when (skin) {
            CoverSkinId.VINYL -> VinylSkin(
                coverUri = coverUri,
                fallbackColor = fallbackColor,
                isPlaying = isPlaying,
                rotationDegrees = rotationDegrees,
                discSize = discSize,
            )
            CoverSkinId.CD -> CdSkin(
                coverUri = coverUri,
                fallbackColor = fallbackColor,
                isPlaying = isPlaying,
                rotationDegrees = rotationDegrees,
                discSize = discSize,
            )
            CoverSkinId.CASSETTE -> CassetteSkin(
                coverUri = coverUri,
                fallbackColor = fallbackColor,
                isPlaying = isPlaying,
                rotationDegrees = rotationDegrees,
                discSize = discSize,
            )
            CoverSkinId.GLOW -> GlowSkin(
                coverUri = coverUri,
                fallbackColor = fallbackColor,
                isPlaying = isPlaying,
                rotationDegrees = rotationDegrees,
                discSize = discSize,
            )
            CoverSkinId.CARD -> CardSkin(
                coverUri = coverUri,
                fallbackColor = fallbackColor,
                isPlaying = isPlaying,
                rotationDegrees = rotationDegrees,
                discSize = discSize,
            )
        }
    }
}
