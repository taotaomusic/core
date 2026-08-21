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

    fun add(keyword: String) {
        val value = keyword.trim(); if (value.isBlank()) return
        val values = (listOf(value) + read().filterNot { it == value }).take(MAX_SIZE)
        preferences.edit().putString(KEY, JSONArray(values).toString()).apply()
    }

    fun remove(keyword: String) { preferences.edit().putString(KEY, JSONArray(read().filterNot { it == keyword }).toString()).apply() }
    fun clear() { preferences.edit().remove(KEY).apply() }

    private companion object { const val KEY = "keywords"; const val MAX_SIZE = 20 }
}
