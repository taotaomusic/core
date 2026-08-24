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
 * 歌曲快照只用于收藏页首屏展示，不参与判断收藏关系；[set] 只做乐观更新，
 * 请求失败由调用方回滚。
 */
class FavoritesStore(context: Context) {
    private val preferences = context.getSharedPreferences("favorites", Context.MODE_PRIVATE)

    init {
        // 1.0.75 曾把歌曲快照写进收藏缓存，升级后立即清掉，避免它继续与账号收藏接口分叉。
        preferences.edit().remove(LEGACY_KEY_SONGS).apply()
    }

    fun ids(): Set<String> = preferences.getStringSet(KEY_IDS, emptySet()).orEmpty()

    fun contains(remoteId: Long?): Boolean = remoteId != null && ids().contains(remoteId.toString())

    /**
     * 读取收藏页的本地展示缓存。
     *
     * 收藏 ID 才是关系依据，所以即使缓存文件残留了旧歌曲，也只返回当前 ID 集合中的项。
     */
    fun cachedSongs(): List<Song> {
        val favoriteIds = ids()
        return SongCodec.decodeList(preferences.getString(KEY_LIBRARY_CACHE, null))
            .filter { it.remoteId?.toString() in favoriteIds }
            .map { it.copy(favorited = true) }
    }

    /** 用服务端的全量收藏列表覆盖本地。 */
    fun replaceAll(remoteIds: Set<String>) {
        // 存进 SharedPreferences 的 Set 不能原地改，取出来的实例是内部引用，必须传新集合。
        val retainedSongs = cachedSongs().filter { it.remoteId?.toString() in remoteIds }
        preferences.edit()
            .putStringSet(KEY_IDS, remoteIds.toSet())
            .putString(KEY_LIBRARY_CACHE, SongCodec.encodeList(retainedSongs))
            .apply()
    }

    /** 云端全量同步成功后，同时刷新关系 ID 与下一次冷启动要展示的歌曲缓存。 */
    fun replaceLibrary(remoteIds: Set<String>, songs: List<Song>) {
        val cached = songs.filter { it.remoteId?.toString() in remoteIds }.map { it.copy(favorited = true) }
        preferences.edit()
            .putStringSet(KEY_IDS, remoteIds.toSet())
            .putString(KEY_LIBRARY_CACHE, SongCodec.encodeList(cached))
            .apply()
    }

    /**
     * 按搜索结果里的权威值就地校正。
     *
     * 只调整本次出现过的歌:没出现的歌不能因为「这次没提到」就被当成未收藏。
     */
    fun merge(songs: List<Song>) {
        val seenSongs = songs.mapNotNull { song -> song.remoteId?.let { it.toString() to song } }.toMap()
        val favoritedIds = seenSongs.filterValues { it.favorited }.keys
        val merged = ids().toMutableSet()
        merged.removeAll(seenSongs.keys)
        merged.addAll(favoritedIds)
        val cachedById = cachedSongs().associateByTo(linkedMapOf()) { it.remoteId.toString() }
        seenSongs.forEach { (id, song) ->
            if (song.favorited) cachedById[id] = song.copy(favorited = true) else cachedById.remove(id)
        }
        preferences.edit()
            .putStringSet(KEY_IDS, merged)
            .putString(KEY_LIBRARY_CACHE, SongCodec.encodeList(cachedById.values.toList()))
            .apply()
    }

    fun set(remoteId: Long, favorite: Boolean, song: Song? = null) {
        val updated = ids().toMutableSet()
        if (favorite) updated.add(remoteId.toString()) else updated.remove(remoteId.toString())
        val cached = cachedSongs().filterNot { it.remoteId == remoteId }.toMutableList()
        if (favorite && song != null) cached.add(0, song.copy(favorited = true))
        preferences.edit()
            .putStringSet(KEY_IDS, updated)
            .putString(KEY_LIBRARY_CACHE, SongCodec.encodeList(cached))
            .apply()
    }

    /** 退出登录时清空：收藏是账号状态，换账号不能沿用上一个人的。 */
    fun clear() = preferences.edit()
        .remove(KEY_IDS)
        .remove(KEY_LIBRARY_CACHE)
        .remove(LEGACY_KEY_SONGS)
        .apply()

    private companion object {
        const val KEY_IDS = "favorite_ids"
        const val KEY_LIBRARY_CACHE = "favorite_library_cache_v2"
        const val LEGACY_KEY_SONGS = "favorite_songs"
    }
}
