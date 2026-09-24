package com.taotao.music.data

import android.content.Context
import org.json.JSONArray

/** 本地搜索历史，只保存在当前设备，不上传服务器。 */
class SearchHistoryStore(context: Context) {
    private val preferences = context.getSharedPreferences("search_history", Context.MODE_PRIVATE)

    fun read(): List<String> {
        val array = runCatching { JSONArray(preferences.getString(KEY, "[]")) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotBlank) }
    }

    /** 是否记录新的搜索关键词；关闭后已有历史保留、可点击，但不再新增。 */
    fun isEnabled(): Boolean = preferences.getBoolean(KEY_ENABLED, true)

    fun setEnabled(value: Boolean) {
        preferences.edit().putBoolean(KEY_ENABLED, value).apply()
    }

    fun add(keyword: String) {
        if (!isEnabled()) return
        val value = keyword.trim()
        if (value.isBlank()) return
        // 判重忽略大小写：搜 "jay" 之后又搜 "Jay"，不该留下两条等价记录。
        val values = (listOf(value) + read().filterNot { it.equals(value, ignoreCase = true) }).take(MAX_SIZE)
        preferences.edit().putString(KEY, JSONArray(values).toString()).apply()
    }

    /** 用调用方给出的完整序列覆盖历史，长按拖动排序松手后整体提交。 */
    fun replaceAll(values: List<String>) {
        val cleaned = values.map(String::trim).filter(String::isNotBlank).distinct().take(MAX_SIZE)
        preferences.edit().putString(KEY, JSONArray(cleaned).toString()).apply()
    }

    fun remove(keyword: String) {
        preferences.edit().putString(KEY, JSONArray(read().filterNot { it == keyword }).toString()).apply()
    }

    fun clear() {
        preferences.edit().remove(KEY).apply()
    }

    private companion object {
        const val KEY = "keywords"
        const val KEY_ENABLED = "recording_enabled"
        const val MAX_SIZE = 20
    }
}
