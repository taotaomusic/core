package com.taotao.music.data

import android.content.Context
import com.taotao.music.data.db.LocalKvStore
import org.json.JSONObject

/**
 * 远程配置本地缓存。
 *
 * 读取永远有代码内默认值兜底：首帧渲染时网络请求还没回来，离线时也拿不到配置，
 * 界面不能因此空白或崩溃。服务端下发的值只覆盖已知的键。
 */
class RemoteConfigStore(context: Context) {
    private val kv = LocalKvStore.get(context)

    /** 落盘一份服务端下发的配置；版本号相同则跳过写入。 */
    fun save(values: Map<String, String>, version: Long) {
        if (version != 0L && version == kv.getLong(KEY_VERSION, 0L)) return
        val data = JSONObject().apply { values.forEach { (key, value) -> put(key, value) } }
        kv.putAll(
            mapOf(
                KEY_VALUES to data.toString(),
                KEY_VERSION to version.toString(),
            )
        )
    }

    fun text(key: String, default: String = ""): String = values()[key]?.takeIf { it.isNotBlank() } ?: default

    fun boolean(key: String, default: Boolean = false): Boolean = when (values()[key]?.lowercase()) {
        "true", "1", "yes" -> true
        "false", "0", "no" -> false
        else -> default
    }

    fun int(key: String, default: Int = 0): Int = values()[key]?.toIntOrNull() ?: default

    fun clear() = kv.removePrefix(PREFIX)

    private fun values(): Map<String, String> = runCatching {
        val data = JSONObject(kv.getString(KEY_VALUES) ?: return emptyMap())
        data.keys().asSequence().associateWith { data.optString(it) }
    }.getOrDefault(emptyMap())

    private companion object {
        const val PREFIX = "remote_config."
        const val KEY_VALUES = "${PREFIX}values"
        const val KEY_VERSION = "${PREFIX}version"
    }
}
