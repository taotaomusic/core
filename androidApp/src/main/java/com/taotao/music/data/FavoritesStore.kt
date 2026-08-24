package com.taotao.music.data

import android.content.Context
import com.taotao.music.model.Song

/**
 * 收藏状态本地缓存。
 *
 * 之所以要缓存：早先判断「这首歌收藏了吗」是**每首歌拉一次完整收藏列表**再线性查找，
 * 搜索页想显示心形就得发几十个请求。现在搜索结果直接带 `favorited`，
 * 这里只负责让状态在页面之间、以及重启之后保持一致。
 *
 * 权威来源始终是服务端：[replaceAll] 用收藏列表或搜索结果覆盖本地，
 * [toggle] 只做乐观更新，请求失败由调用方回滚。
 */
class FavoritesStore(context: Context) {
    private val preferences = context.getSharedPreferences("favorites", Context.MODE_PRIVATE)

    fun ids(): Set<String> = preferences.getStringSet(KEY_IDS, emptySet()).orEmpty()

    /** 收藏夹页面使用的歌曲快照；服务端目前只保存歌曲 ID，所以元信息在本机补齐。 */
    fun songs(): List<Song> = SongCodec.decodeList(preferences.getString(KEY_SONGS, null))

    fun contains(remoteId: Long?): Boolean = remoteId != null && ids().contains(remoteId.toString())

    /** 用服务端的全量收藏列表覆盖本地。 */
    fun replaceAll(remoteIds: Set<String>) {
        // 存进 SharedPreferences 的 Set 不能原地改，取出来的实例是内部引用，必须传新集合。
        val retainedSongs = songs().filter { it.remoteId?.toString() in remoteIds }
        preferences.edit()
            .putStringSet(KEY_IDS, remoteIds.toSet())
            .putString(KEY_SONGS, SongCodec.encodeList(retainedSongs))
            .apply()
    }

    /**
     * 按搜索结果里的权威值就地校正。
     *
     * 只调整本次出现过的歌:没出现的歌不能因为「这次没提到」就被当成未收藏。
     */
    fun merge(favoritedIds: Set<String>, seenIds: Set<String>, seenSongs: List<Song> = emptyList()) {
        val merged = ids().toMutableSet()
        merged.removeAll(seenIds)
        merged.addAll(favoritedIds)
        val refreshedIds = favoritedIds.toSet()
        val refreshedSongs = seenSongs.filter { it.remoteId?.toString() in refreshedIds }
        val cachedSongs = songs().filterNot { it.remoteId?.toString() in seenIds }
        val songSnapshots = dedupeSongs(refreshedSongs + cachedSongs).filter { it.remoteId?.toString() in merged }
        preferences.edit()
            .putStringSet(KEY_IDS, merged)
            .putString(KEY_SONGS, SongCodec.encodeList(songSnapshots))
            .apply()
    }

    fun set(song: Song, favorite: Boolean) {
        val remoteId = song.remoteId ?: return
        val updated = ids().toMutableSet()
        if (favorite) updated.add(remoteId.toString()) else updated.remove(remoteId.toString())
        val songSnapshots = if (favorite) {
            dedupeSongs(listOf(song) + songs())
        } else {
            songs().filterNot { it.remoteId == remoteId }
        }
        preferences.edit()
            .putStringSet(KEY_IDS, updated)
            .putString(KEY_SONGS, SongCodec.encodeList(songSnapshots))
            .apply()
    }

    /** 退出登录时清空：收藏是账号状态，换账号不能沿用上一个人的。 */
    fun clear() = preferences.edit().remove(KEY_IDS).remove(KEY_SONGS).apply()

    private fun dedupeSongs(songs: List<Song>): List<Song> {
        val seen = HashSet<String>(songs.size)
        return songs.filter { song ->
            val key = song.remoteId?.let { "remote:$it" }
                ?: "local:${song.audioUri.orEmpty()}#${song.title}#${song.artist}"
            seen.add(key)
        }
    }

    private companion object {
        const val KEY_IDS = "favorite_ids"
        const val KEY_SONGS = "favorite_songs"
    }
}
