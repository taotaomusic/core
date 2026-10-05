package com.taotao.music.playerui.skin

/**
 * 详情页封面皮肤标识，同时是皮肤注册表：枚举顺序即选择面板里的展示顺序。
 *
 * **新增一个皮肤只需三步（对本框架的全部接入端同时生效）**：
 * 1. 在本枚举里按展示顺序加一个值，中文标签 [label] 就是选择面板里的名字；
 * 2. 在本包新建一个皮肤控件，参数与 [VinylSkin] 等现有皮肤完全一致（可直接复制一个改）；
 * 3. 在 [CoverSkin] 的 `when` 里加一个分支挂上它，然后跑
 *    `player-ui/src/commonTest/kotlin/com/taotao/music/playerui/skin/CoverSkinIdTest.kt`
 *    确认注册表测试仍通过（中文标签唯一且非空、名称可无损往返）。
 *
 * 选择面板 [CoverSkinSheet] 直接遍历本枚举，因此枚举加完值后无需再改面板。
 */
enum class CoverSkinId(val label: String, val squareArt: Boolean) {
    VINYL("黑胶唱片", squareArt = false),
    CD("CD 光碟", squareArt = false),
    CASSETTE("磁带机", squareArt = true),
    GLOW("光晕", squareArt = false),
    CARD("圆角卡片", squareArt = true),
    WAVE("声波", squareArt = false),
    TIDAL("潮汐", squareArt = false),
    FAN("扇形卡叠", squareArt = true),
    NEON_RING("霓虹环", squareArt = true),
    ;

    companion object {
        /** 没有任何本地记录（新安装 / 旧版本升级）时使用的默认皮肤。 */
        val DEFAULT = VINYL

        /**
         * 容错解析：名称对不上任何已知皮肤（含 null 与历史脏数据）时回退默认值。
         * Android 端历史偏好里存的就是这里的枚举名，口径一致可无损升级。
         */
        fun of(name: String?): CoverSkinId = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}
