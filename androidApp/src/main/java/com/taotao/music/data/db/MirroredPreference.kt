package com.taotao.music.data.db

import android.content.Context
import android.os.Looper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.util.concurrent.ConcurrentHashMap

/**
 * 偏好类存储的进程级内存镜像基类。
 *
 * 这些偏好会被主线程（Compose 状态、UI 回调）高频同步读取，而 Room 拒绝主线程访问；
 * 因此读永远走进程级镜像——同一 [domain] 的所有实例共享同一份内存值（与旧
 * SharedPreferences 的多实例共享等价），写先同步改镜像再经串行后台线程整行落库。
 * 崩溃最多丢最后一笔偏好写，均为可自愈配置。
 *
 * 子类只实现三个纯存储动作：[default]（无记录默认值）、[load]（读表，无行返回 null）、
 * [persist]（整行写表，后台线程内可自由使用事务）。
 */
internal abstract class MirroredPreference<R : Any>(context: Context, private val domain: String) {
    private val db by lazy { LocalDb.ready(context) }

    protected abstract fun default(): R

    protected abstract fun load(db: TaotaoLocalDatabase): R?

    protected abstract fun persist(db: TaotaoLocalDatabase, value: R)

    fun current(): R {
        Registry.cache[domain]?.let { @Suppress("UNCHECKED_CAST") return it as R }
        val domainLock = Registry.locks.computeIfAbsent(domain) { Any() }
        return synchronized(domainLock) {
            @Suppress("UNCHECKED_CAST")
            (Registry.cache[domain] as? R) ?: run {
                // load 经 LocalDb.ready，保证 prefs→表迁移先于本次水合完成。
                val loaded = if (Looper.myLooper() == Looper.getMainLooper()) {
                    runBlocking(Dispatchers.IO) { load(db) } ?: default()
                } else {
                    load(db) ?: default()
                }
                Registry.cache[domain] = loaded
                loaded
            }
        }
    }

    fun commit(value: R) {
        Registry.cache[domain] = value
        LocalDb.writeExecutor.execute {
            runCatching {
                val database = db
                database.runInTransaction { persist(database, value) }
            }
        }
    }

    private object Registry {
        val cache = ConcurrentHashMap<String, Any?>()
        val locks = ConcurrentHashMap<String, Any>()
    }
}
