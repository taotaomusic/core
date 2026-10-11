package com.taotao.music.data

import android.content.Context
import com.taotao.music.data.db.MirroredPreference
import com.taotao.music.data.db.RemoteConfigEntryRow
import com.taotao.music.data.db.RemoteConfigStateRow
import com.taotao.music.data.db.REMOTE_CONFIG_STATE_ID
import com.taotao.music.data.db.TaotaoLocalDatabase

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
 */
class RemoteConfigStore(context: Context) {
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
    fun save(values: Map<String, String>, version: Long) {
        if (version != 0L && version == mirror.current().version) return
        mirror.commit(RemoteConfigSnapshot(version, values))
    }

    fun text(key: String, default: String = ""): String = mirror.current().values[key]?.takeIf { it.isNotBlank() } ?: default

    fun boolean(key: String, default: Boolean = false): Boolean = when (mirror.current().values[key]?.lowercase()) {
        "true", "1", "yes" -> true
        "false", "0", "no" -> false
        else -> default
    }

    fun int(key: String, default: Int = 0): Int = mirror.current().values[key]?.toIntOrNull() ?: default

    fun clear() = mirror.commit(RemoteConfigSnapshot(0L, emptyMap()))
}
