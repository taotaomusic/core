package com.taotao.music.model

/** 音源和数字 ID/mid 必须一起比较；本地导入文件不能按标题猜测远端身份。 */
val Song.metadataKey: String?
    get() {
        val normalizedSource = source.trim().lowercase().ifBlank { "tencent" }
        if (normalizedSource !in setOf("tencent", "netease", "kuwo")) return null
        val identity = remoteId?.takeIf { it > 0L }?.let { "id:$it" }
            ?: mid?.trim()?.takeIf { it.isNotEmpty() }?.let { "mid:$it" }
            ?: return null
        return "$normalizedSource:$identity"
    }

/** 目前只有酷我提供 MV 接口；其他来源即使旧快照误带 true 也不能展示入口。 */
val Song.supportsMv: Boolean
    get() = source.trim().equals("kuwo", ignoreCase = true) && (remoteId ?: 0L) > 0L

val Song.canPlayMv: Boolean
    get() = supportsMv && hasMv == true

/** 当前这组补充资料由酷我提供；与是否已下载无关，后台执行以免阻塞本地起播。 */
val Song.needsPlaybackMetadata: Boolean
    get() = supportsMv && (
        refrainStartMs == null || refrainEndMs == null ||
            hasMv == null || artistId == null || albumId == null
        )

/** 只合并同一首歌的补充元数据，保留本地音频、封面、歌词、下载音质与展示资料。 */
fun Song.mergePlaybackMetadata(resolved: Song): Song {
    val key = metadataKey ?: return this
    if (key != resolved.metadataKey) return this
    return copy(
        hasMv = if (supportsMv) resolved.hasMv ?: hasMv else false,
        artistId = resolved.artistId ?: artistId,
        albumId = resolved.albumId ?: albumId,
        refrainStartMs = resolved.refrainStartMs ?: refrainStartMs,
        refrainEndMs = resolved.refrainEndMs ?: refrainEndMs,
    )
}
