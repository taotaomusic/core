package com.taotao.music.data

import android.content.Context
import android.os.Looper
import com.taotao.music.data.db.FavoriteIdRow
import com.taotao.music.data.db.FavoriteSongRow
import com.taotao.music.data.db.LocalDb
import com.taotao.music.model.Song
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * 收藏状态本地缓存，落在本地 Room 库的 favorite_ids / favorite_songs 两张表。
 *
 * 之所以要缓存：早先判断「这首歌收藏了吗」是**每首歌拉一次完整收藏列表**再线性查找，
 * 搜索页想显示心形就得发几十个请求。现在搜索结果直接带 `favorited`，
 * 这里只负责让状态在页面之间、以及重启之后保持一致。
 *
 * 权威来源始终是服务端：[replaceAll] 用收藏列表或搜索结果覆盖本地，
 * 歌曲快照只用于收藏页首屏展示，不参与判断收藏关系；[set] 只做乐观更新，
 * 请求失败由调用方回滚。
 *
 * 读写模型：内存镜像 + 写后落盘。Compose 会在主线程频繁调 [contains]/[ids]，
 * 所以读永远走内存；写先同步更新内存（界面立即正确），再把整份快照丢给串行后台线程
 * 落库。收藏缓存可从服务端自愈（每次登录都会全量覆盖），崩溃丢掉最后一两笔落盘无害。
 */
class FavoritesStore(context: Context) {

    /** 不可变快照：ids 与 songs 总是一起更新，避免读到半新半旧的状态。 */
    private data class Snapshot(val ids: Set<String>, val songs: List<Song>)

    private val appContext = context.applicationContext
    private val db by lazy { LocalDb.ready(appContext) }

    @Volatile
    private var snapshot: Snapshot = Snapshot(emptySet(), emptyList())

    @Volatile
    private var hydrated = false
    private val hydrateLock = Any()

    /** 返回带来源前缀的稳定键集合；旧版本只存数字 ID，读取时按腾讯来源兼容。 */
    fun ids(): Set<String> = current().ids

    fun contains(remoteId: Long?): Boolean = remoteId != null && current().ids.contains(key("tencent", remoteId.toString()))

    fun contains(song: Song): Boolean {
        val favoriteIds = current().ids
        return song.favoriteKeys().any(favoriteIds::contains)
    }

    /**
     * 读取收藏页的本地展示缓存。
     *
     * 收藏 ID 才是关系依据，所以即使缓存里残留了旧歌曲，也只返回当前 ID 集合中的项。
     */
    fun cachedSongs(): List<Song> {
        val state = current()
        val favoriteIds = state.ids
        return state.songs
            .filter { song -> song.favoriteKeys().any(favoriteIds::contains) }
            .map { it.copy(favorited = true) }
    }

    /** 用服务端的全量收藏列表覆盖本地。 */
    fun replaceAll(remoteIds: Set<String>) {
        val normalizedIds = remoteIds.map(::normalizeKey).toSet()
        val retainedSongs = current().songs.filter { song -> song.favoriteKeys().any(normalizedIds::contains) }
        commit(Snapshot(normalizedIds, retainedSongs))
    }

    /** 云端全量同步成功后，同时刷新关系 ID 与下一次冷启动要展示的歌曲缓存。 */
    fun replaceLibrary(remoteIds: Set<String>, songs: List<Song>) {
        val normalizedIds = remoteIds.map(::normalizeKey).toSet()
        val cached = songs
            .filter { song -> song.favoriteKeys().any(normalizedIds::contains) }
            .map { it.copy(favorited = true) }
        commit(Snapshot(normalizedIds, cached))
    }

    /**
     * 按搜索结果里的权威值就地校正。
     *
     * 只调整本次出现过的歌:没出现的歌不能因为「这次没提到」就被当成未收藏。
     */
    fun merge(songs: List<Song>) {
        val seenSongs = songs.mapNotNull { song -> song.favoriteKey()?.let { it to song } }.toMap()
        val favoritedIds = seenSongs.filterValues { it.favorited }.keys
        val merged = current().ids.toMutableSet()
        merged.removeAll(seenSongs.keys)
        merged.addAll(favoritedIds)
        val cachedById = current().songs.mapNotNull { song -> song.favoriteKey()?.let { it to song } }
            .toMap(linkedMapOf())
        seenSongs.forEach { (id, song) ->
            if (song.favorited) cachedById[id] = song.copy(favorited = true) else cachedById.remove(id)
        }
        commit(Snapshot(merged, cachedById.values.toList()))
    }

    fun set(remoteId: Long, favorite: Boolean, song: Song? = null) {
        val updated = current().ids.toMutableSet()
        val stableKey = key("tencent", remoteId.toString())
        if (favorite) updated.add(stableKey) else updated.remove(stableKey)
        val cached = current().songs.filterNot { it.remoteId == remoteId }.toMutableList()
        if (favorite && song != null) cached.add(0, song.copy(favorited = true))
        commit(Snapshot(updated, cached))
    }

    /** 按歌曲稳定键乐观更新收藏；支持腾讯 mid-only 和未来其他来源。 */
    fun set(song: Song, favorite: Boolean) {
        val stableKey = song.favoriteKey() ?: return
        val aliases = song.favoriteKeys()
        val updated = current().ids.toMutableSet()
        updated.removeAll(aliases)
        if (favorite) updated.add(stableKey)
        val cached = current().songs.filterNot { cachedSong -> cachedSong.favoriteKeys().any(aliases::contains) }.toMutableList()
        if (favorite) cached.add(0, song.copy(favorited = true))
        commit(Snapshot(updated, cached))
    }

    /** 退出登录时清空：收藏是账号状态，换账号不能沿用上一个人的。 */
    fun clear() = commit(Snapshot(emptySet(), emptyList()))

    /** 内存先落地，再整份快照串行落库（空快照即清表）。 */
    private fun commit(next: Snapshot) {
        snapshot = next
        LocalDb.writeExecutor.execute {
            runCatching {
                val database = db
                database.runInTransaction {
                    val dao = database.favoritesDao()
                    dao.deleteAllIds()
                    dao.insertIds(next.ids.map(::FavoriteIdRow))
                    dao.deleteAllSongs()
                    dao.insertSongs(
                        next.songs.mapIndexedNotNull { index, song ->
                            song.favoriteKey()?.let { FavoriteSongRow(it, SongCodec.encode(song), index) }
                        }
                    )
                }
            }
        }
    }

    private fun current(): Snapshot {
        if (hydrated) return snapshot
        return synchronized(hydrateLock) {
            if (hydrated) return@synchronized snapshot
            val loaded = if (Looper.myLooper() == Looper.getMainLooper()) {
                // 首次 hydration 若撞上主线程，用 runBlocking 垫底（与旧 SharedPreferences
                // 首次读盘同量级的一次性开销）；通常进程启动时已由后台预热完成。
                runBlocking(Dispatchers.IO) { loadFromDb() }
            } else {
                loadFromDb()
            }
            snapshot = loaded
            hydrated = true
            loaded
        }
    }

    private fun loadFromDb(): Snapshot {
        val dao = db.favoritesDao()
        val ids = dao.listIds().map { it.stableKey }.toSet()
        val songs = dao.listSongs()
            .mapNotNull { SongCodec.decode(it.songJson) }
            .filter { song -> song.favoriteKeys().any(ids::contains) }
            .map { it.copy(favorited = true) }
        return Snapshot(ids, songs)
    }

    private fun Song.favoriteKey(): String? {
        return favoriteKeys().firstOrNull()
    }

    /** 数字 ID 与 mid 都是同一歌曲的合法别名，云端返回任一形式都应命中缓存。 */
    private fun Song.favoriteKeys(): Set<String> = buildSet {
        val normalizedSource = source.trim().lowercase(Locale.ROOT).ifBlank { "tencent" }
        remoteId?.takeIf { it > 0L }?.let { add(key(normalizedSource, it.toString())) }
        mid?.trim()?.takeIf { it.isNotBlank() }?.let { add(key(normalizedSource, it)) }
    }

    private fun normalizeKey(value: String): String {
        val trimmed = value.trim()
        if (trimmed.isBlank()) return "tencent:"
        return if (trimmed.contains(':')) {
            val separator = trimmed.indexOf(':')
            "${trimmed.substring(0, separator).lowercase(Locale.ROOT)}:${trimmed.substring(separator + 1)}"
        } else {
            key("tencent", trimmed)
        }
    }

    private fun key(source: String, identity: String): String =
        "${source.trim().lowercase(Locale.ROOT).ifBlank { "tencent" }}:$identity"
}
