package com.taotao.music.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert

/**
 * 本地搜索历史条目（原 search_history 文件的 keywords JSON 数组）。
 * [keyword] 为主键——同一关键词只占一行，位置即展示顺序。
 */
@Entity(tableName = "search_history_entry")
data class SearchHistoryEntryRow(
    @PrimaryKey val keyword: String,
    val position: Int,
)

/** 搜索历史是否继续记录新关键词（原 recording_enabled）。恒定单行。 */
@Entity(tableName = "search_history_state")
data class SearchHistoryStateRow(
    @PrimaryKey val id: Int,
    val recordingEnabled: Boolean,
)

/** search_history_state 的恒定单行主键。 */
const val SEARCH_HISTORY_STATE_ID = 1

@Dao
interface SearchHistoryDao {

    @Query("SELECT * FROM search_history_entry ORDER BY position ASC")
    fun listEntries(): List<SearchHistoryEntryRow>

    @Query("DELETE FROM search_history_entry")
    fun deleteAllEntries()

    @Insert
    fun insertEntries(rows: List<SearchHistoryEntryRow>)

    @Query("SELECT * FROM search_history_state WHERE id = 1 LIMIT 1")
    fun getState(): SearchHistoryStateRow?

    @Upsert
    fun upsertState(row: SearchHistoryStateRow)
}
