package com.taotao.music.data

import android.net.Uri
import com.taotao.music.model.Song

/** 分享页唤起 App 的深链接前缀，Manifest 里声明为 `taotaomusic://open`。 */
private const val OPEN_LINK_PREFIX = "taotaomusic://open"

/** 分享链路的品牌兜底色，与 Web 端 `parseShareSong` 的写死值一致。 */
private const val SHARE_FALLBACK_COLOR = 0xFFFA5E5B

/**
 * 解析 `taotaomusic://open?...`，把 Web 分享页正在听的歌还原成 [Song]。
 *
 * 参数由 webApp 的分享页拼接（webApp `Main.kt` 的 `openAppUrl`），两端的键名必须同步改：
 * - `source` / `id` / `mid` / `type`：歌曲身份，与创建分享接口的 body 同名同义；
 * - `title` / `artist` / `album` / `cover` / `duration`（已格式化 mm:ss）/ `vip`：展示快照；
 * - `rs` / `re`：高潮区间（整曲毫秒）。
 *
 * 身份不完整（既无数字 ID 也无 mid）或标题/歌手缺失时返回 null，调用方直接忽略链接 ——
 * 宁可不动，也不要把残缺的歌曲塞进播放队列。
 */
fun parseOpenSongLink(link: String?): Song? {
    if (link.isNullOrEmpty() || !link.startsWith(OPEN_LINK_PREFIX)) return null
    val uri = Uri.parse(link)
    val remoteId = uri.getQueryParameter("id")?.toLongOrNull()?.takeIf { it > 0L }
    val mid = uri.getQueryParameter("mid")?.trim()?.takeIf { it.isNotBlank() }
    if (remoteId == null && mid == null) return null
    val title = uri.getQueryParameter("title")?.trim().orEmpty()
    val artist = uri.getQueryParameter("artist")?.trim().orEmpty()
    if (title.isEmpty() || artist.isEmpty()) return null
    return Song(
        title = title,
        artist = artist,
        duration = uri.getQueryParameter("duration")?.takeIf { it.isNotBlank() } ?: "00:00",
        color = SHARE_FALLBACK_COLOR,
        remoteId = remoteId,
        coverUri = uri.getQueryParameter("cover")?.takeIf { it.isNotBlank() },
        album = uri.getQueryParameter("album").orEmpty(),
        refrainStartMs = uri.getQueryParameter("rs")?.toLongOrNull(),
        refrainEndMs = uri.getQueryParameter("re")?.toLongOrNull(),
        mid = mid,
        type = uri.getQueryParameter("type")?.toIntOrNull(),
        vip = uri.getQueryParameter("vip") == "1",
        source = uri.getQueryParameter("source")?.takeIf { it.isNotBlank() } ?: "tencent",
    )
}
