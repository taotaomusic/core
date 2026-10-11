package com.taotao.music.data.db

import android.content.Context
import android.os.Looper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * 进程级单例的本地键值镜像（local_kv 表的门面）。
 *
 * 旧的小键值存储各自一份 SharedPreferences 文件，同一进程里的多个实例天然共享同一份
 * 磁盘状态；迁进 SQLite 后改为：全进程共用一个内存镜像（本类单例），读永远走内存
 * （主线程安全），写先同步改内存再经串行后台线程落库。因此必须用 [get] 取实例，
 * 不要自己 new。
 *
 * 值序列化约定：布尔 "1"/"0"，数字十进制字符串；[putAll] 传 null 值即删除该键。
 */
class LocalKvStore private constructor(private val appContext: Context) {

    private val db by lazy { LocalDb.ready(appContext) }
    private val lock = Any()

    @Volatile
    private var values: Map<String, String> = emptyMap()

    @Volatile
    private var hydrated = false

    fun getString(key: String): String? = current()[key]

    fun getInt(key: String, default: Int): Int = current()[key]?.toIntOrNull() ?: default

    fun getLong(key: String, default: Long): Long = current()[key]?.toLongOrNull() ?: default

    fun getBoolean(key: String, default: Boolean): Boolean = current()[key]?.let { it == "1" } ?: default

    fun putString(key: String, value: String?) = putAll(mapOf(key to value))

    fun putInt(key: String, value: Int) = putString(key, value.toString())

    fun putLong(key: String, value: Long) = putString(key, value.toString())

    fun putBoolean(key: String, value: Boolean) = putString(key, if (value) "1" else "0")

    /**
     * 批量更新：内存一次性生效，落库在单个事务完成。null 值即删除该键——用于保持
     * 「一条 Editor 批量写」的近似原子性（如登录令牌四键同写）。
     */
    fun putAll(entries: Map<String, String?>) {
        if (entries.isEmpty()) return
        synchronized(lock) {
            val next = values.toMutableMap()
            entries.forEach { (key, value) ->
                if (value == null) next.remove(key) else next[key] = value
            }
            values = next
        }
        LocalDb.writeExecutor.execute {
            runCatching {
                db.runInTransaction {
                    val dao = db.localKvDao()
                    entries.forEach { (key, value) ->
                        if (value == null) dao.delete(key) else dao.upsert(LocalKvRow(key, value))
                    }
                }
            }
        }
    }

    /** 整域删除：按存储域前缀清掉该域全部键（对应旧实现里清空整个 prefs 文件）。 */
    fun removePrefix(prefix: String) {
        synchronized(lock) { values = values.filterKeys { !it.startsWith(prefix) } }
        LocalDb.writeExecutor.execute {
            runCatching { db.localKvDao().deletePrefix(prefix) }
        }
    }

    private fun current(): Map<String, String> {
        if (hydrated) return values
        return synchronized(lock) {
            if (hydrated) return@synchronized values
            // loadFromDb 经 LocalDb.ready，保证两阶段 prefs 迁移先于本次水合完成。
            val loaded = if (Looper.myLooper() == Looper.getMainLooper()) {
                // 首次水合撞上主线程时垫一次 runBlocking（一次性开销，与旧 prefs 首次读盘同量级）。
                runBlocking(Dispatchers.IO) { loadFromDb() }
            } else {
                loadFromDb()
            }
            values = loaded
            hydrated = true
            loaded
        }
    }

    private fun loadFromDb(): Map<String, String> =
        db.localKvDao().all().associate { it.storeKey to it.storeValue }

    companion object {
        @Volatile
        private var instance: LocalKvStore? = null

        fun get(context: Context): LocalKvStore = instance ?: synchronized(this) {
            instance ?: LocalKvStore(context.applicationContext).also { instance = it }
        }
    }
}
