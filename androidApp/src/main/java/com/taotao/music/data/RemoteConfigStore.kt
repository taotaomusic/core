package com.taotao.music.data

import android.content.Context
import com.taotao.music.data.db.MirroredPreference
import com.taotao.music.data.db.RemoteConfigEntryRow
import com.taotao.music.data.db.RemoteConfigStateRow
import com.taotao.music.data.db.REMOTE_CONFIG_STATE_ID
import com.taotao.music.data.db.TaotaoLocalDatabase
import com.taotao.music.update.UpdateConfigStore

/** 远程配置的镜像值：版本水位 + 配置键值对。 */
private data class RemoteConfigSnapshot(
    val version: Long,
    val values: Map<String, String>,
)

/**
 * 远程配置本地缓存。
 *
 * 读取永远有代码内默认值兜底：首帧渲染时网络请求还没回来，离线时也拿不到配置，
 * 界面不能因此空白或崩溃。服务端下发的值只覆盖已知的键。
 *
 * 实现 `:hotupdate` 模块的 [UpdateConfigStore] 契约（默认参数值声明在接口上，
 * Kotlin 禁止 override 重复声明默认值）。
 */
class RemoteConfigStore(context: Context) : UpdateConfigStore {
    private val mirror = object : MirroredPreference<RemoteConfigSnapshot>(context, "remote_config") {
        override fun default() = RemoteConfigSnapshot(0L, emptyMap())
        override fun load(db: TaotaoLocalDatabase): RemoteConfigSnapshot = RemoteConfigSnapshot(
            version = db.remoteConfigDao().getState()?.version ?: 0L,
            values = db.remoteConfigDao().listEntries().associate { it.configKey to it.configValue },
        )
        override fun persist(db: TaotaoLocalDatabase, value: RemoteConfigSnapshot) {
            val dao = db.remoteConfigDao()
            dao.deleteAllEntries()
            if (value.values.isNotEmpty()) {
                dao.insertEntries(value.values.map { (key, entry) -> RemoteConfigEntryRow(key, entry) })
            }
            dao.upsertState(RemoteConfigStateRow(REMOTE_CONFIG_STATE_ID, value.version))
        }
    }

    /** 落盘一份服务端下发的配置；版本号相同则跳过写入。 */
    override fun save(values: Map<String, String>, version: Long) {
        if (version != 0L && version == mirror.current().version) return
        mirror.commit(RemoteConfigSnapshot(version, values))
    }

    override fun text(key: String, default: String): String =
        mirror.current().values[key]?.takeIf { it.isNotBlank() } ?: default

    override fun boolean(key: String, default: Boolean): Boolean =
        when (mirror.current().values[key]?.lowercase()) {
            "true", "1", "yes" -> true
            "false", "0", "no" -> false
            else -> default
        }

    override fun int(key: String, default: Int): Int = mirror.current().values[key]?.toIntOrNull() ?: default

    override fun clear() = mirror.commit(RemoteConfigSnapshot(0L, emptyMap()))
}
