package com.taotao.music.data

import android.content.Context

/**
 * 收藏状态本地缓存。
 *
 * 之所以要缓存：早先判断「这首歌收藏了吗」是**每首歌拉一次完整收藏列表**再线性查找，
 * 搜索页想显示心形就得发几十个请求。现在搜索结果直接带 `favorited`，
 * 这里只负责让状态在页面之间、以及重启之后保持一致。
 *
 * 权威来源始终是服务端：[replaceAll] 用收藏列表或搜索结果覆盖本地，
 * [set] 只做乐观更新，请求失败由调用方回滚。
 */
class FavoritesStore(context: Context) {
    private val preferences = context.getSharedPreferences("favorites", Context.MODE_PRIVATE)

    init {
        // 1.0.75 曾把歌曲快照写进收藏缓存，升级后立即清掉，避免它继续与账号收藏接口分叉。
        preferences.edit().remove(LEGACY_KEY_SONGS).apply()
    }

    fun ids(): Set<String> = preferences.getStringSet(KEY_IDS, emptySet()).orEmpty()

    fun contains(remoteId: Long?): Boolean = remoteId != null && ids().contains(remoteId.toString())

    /** 用服务端的全量收藏列表覆盖本地。 */
    fun replaceAll(remoteIds: Set<String>) {
        // 存进 SharedPreferences 的 Set 不能原地改，取出来的实例是内部引用，必须传新集合。
        preferences.edit().putStringSet(KEY_IDS, remoteIds.toSet()).apply()
    }

    /**
     * 按搜索结果里的权威值就地校正。
     *
     * 只调整本次出现过的歌:没出现的歌不能因为「这次没提到」就被当成未收藏。
     */
    fun merge(favoritedIds: Set<String>, seenIds: Set<String>) {
        val merged = ids().toMutableSet()
        merged.removeAll(seenIds)
        merged.addAll(favoritedIds)
        preferences.edit().putStringSet(KEY_IDS, merged).apply()
    }

    fun set(remoteId: Long, favorite: Boolean) {
        val updated = ids().toMutableSet()
        if (favorite) updated.add(remoteId.toString()) else updated.remove(remoteId.toString())
        preferences.edit().putStringSet(KEY_IDS, updated).apply()
    }

    /** 退出登录时清空：收藏是账号状态，换账号不能沿用上一个人的。 */
    fun clear() = preferences.edit().remove(KEY_IDS).remove(LEGACY_KEY_SONGS).apply()

    private companion object {
        const val KEY_IDS = "favorite_ids"
        const val LEGACY_KEY_SONGS = "favorite_songs"
    }
}
