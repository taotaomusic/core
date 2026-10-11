package com.taotao.music.data

import android.content.Context
import com.taotao.music.data.db.MirroredPreference
import com.taotao.music.data.db.SearchHistoryEntryRow
import com.taotao.music.data.db.SearchHistoryStateRow
import com.taotao.music.data.db.SEARCH_HISTORY_STATE_ID
import com.taotao.music.data.db.TaotaoLocalDatabase

/** 搜索历史的镜像值：关键词有序列表 + 是否继续记录。 */
private data class SearchHistorySnapshot(
    val keywords: List<String>,
    val recordingEnabled: Boolean,
)

/** 本地搜索历史，只保存在当前设备，不上传服务器。 */
class SearchHistoryStore(context: Context) {
    private val mirror = object : MirroredPreference<SearchHistorySnapshot>(context, "search_history") {
        override fun default() = SearchHistorySnapshot(emptyList(), true)
        override fun load(db: TaotaoLocalDatabase): SearchHistorySnapshot {
            val state = db.searchHistoryDao().getState()
            return SearchHistorySnapshot(
                keywords = db.searchHistoryDao().listEntries().map { it.keyword },
                recordingEnabled = state?.recordingEnabled ?: true,
            )
        }
        override fun persist(db: TaotaoLocalDatabase, value: SearchHistorySnapshot) {
            val dao = db.searchHistoryDao()
            dao.deleteAllEntries()
            if (value.keywords.isNotEmpty()) {
                dao.insertEntries(value.keywords.mapIndexed { index, keyword -> SearchHistoryEntryRow(keyword, index) })
            }
            dao.upsertState(SearchHistoryStateRow(SEARCH_HISTORY_STATE_ID, value.recordingEnabled))
        }
    }

    fun read(): List<String> = mirror.current().keywords

    /** 是否记录新的搜索关键词；关闭后已有历史保留、可点击，但不再新增。 */
    fun isEnabled(): Boolean = mirror.current().recordingEnabled

    fun setEnabled(value: Boolean) {
        mirror.commit(mirror.current().copy(recordingEnabled = value))
    }

    fun add(keyword: String) {
        if (!isEnabled()) return
        val value = keyword.trim()
        if (value.isBlank()) return
        // 判重忽略大小写：搜 "jay" 之后又搜 "Jay"，不该留下两条等价记录。
        val values = (listOf(value) + read().filterNot { it.equals(value, ignoreCase = true) }).take(MAX_SIZE)
        mirror.commit(mirror.current().copy(keywords = values))
    }

    /** 用调用方给出的完整序列覆盖历史，长按拖动排序松手后整体提交。 */
    fun replaceAll(values: List<String>) {
        val cleaned = values.map(String::trim).filter(String::isNotBlank).distinct().take(MAX_SIZE)
        mirror.commit(mirror.current().copy(keywords = cleaned))
    }

    fun remove(keyword: String) {
        mirror.commit(mirror.current().copy(keywords = read().filterNot { it == keyword }))
    }

    fun clear() {
        // 只清关键词，记录开关保留（与旧实现 remove(KEY) 的语义一致）。
        mirror.commit(mirror.current().copy(keywords = emptyList()))
    }

    private companion object {
        const val MAX_SIZE = 20
    }
}
