package com.taotao.music.model

data class Song(
    val title: String,
    val artist: String,
    val duration: String,
    val color: Long,
    val audioUri: String? = null,
    val remoteId: Long? = null,
    val coverUri: String? = null,
    val lyricUri: String? = null,
    /**
     * 逐字时间轴（yrc）的本地地址，只有离线歌曲会用到。
     * 在线播放时逐字数据随 `?format=json` 一起取，不需要单独的地址。
     */
    val lyricWordsUri: String? = null,
    val album: String = "",
    val subtitle: String = "",
    val releaseTime: String = "",
    /**
     * 上游的 songMID 与歌曲类型，解析播放地址时要原样带回服务端。
     * [remoteId] 为 0 的歌只能靠 [mid] 解析，缺了它这些歌永远拿不到地址。
     */
    val mid: String? = null,
    val type: Int? = null,
    /** 付费 / VIP 歌曲。搜索结果直接告知，界面据此加标记。 */
    val vip: Boolean = false,
    /** 是否已收藏。搜索结果里由服务端下发，是权威值。 */
    val favorited: Boolean = false,
)
