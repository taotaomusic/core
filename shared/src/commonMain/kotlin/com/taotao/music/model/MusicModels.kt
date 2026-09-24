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
    /**
     * 这首歌**能不能拿到播放地址**。`false` 时列表要置灰并标注，点击不发起播放。
     *
     * 与 [vip] 是两件事：`vip` 是「上游标它付费」，这里是「实测取不到地址」。
     * 酷我上大量正版热门曲（周杰伦全系列等）属于后者 —— 上游逐首回「歌曲已下线」。
     * 搜索**照常列出**它们（波点 App 也列），只是不可播。
     *
     * 默认 `true`：只有服务端明确下发 `false` 才置灰，缺字段时按可播处理。
     */
    val playable: Boolean = true,
    /** 是否已收藏。搜索结果里由服务端下发，是权威值。 */
    val favorited: Boolean = false,
    /**
     * 已下载到本地的那份是哪个音质档位。
     * 只有离线歌曲才有值；为 null 表示这首歌不是本地文件，或是旧版本下载的没有记录。
     */
    val localQuality: Int? = null,
    /** 音乐来源。腾讯与网易的歌曲 ID 可能相同，桌面端所有云端操作都按来源区分。 */
    val source: String = "tencent",
)
