package com.taotao.music.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert

/**
 * 服务端下发的远程配置项（原 remote_config 文件的 values JSON 对象）。
 * 一键一行：增量读单项、按需查询都不必反序列化整份 JSON。
 */
@Entity(tableName = "remote_config_entry")
data class RemoteConfigEntryRow(
    @PrimaryKey val configKey: String,
    val configValue: String,
)

/** 远程配置的版本水位（原 version 键），用于跳过同版本重复落盘。恒定单行。 */
@Entity(tableName = "remote_config_state")
data class RemoteConfigStateRow(
    @PrimaryKey val id: Int,
    val version: Long,
)

/** remote_config_state 的恒定单行主键。 */
const val REMOTE_CONFIG_STATE_ID = 1

@Dao
interface RemoteConfigDao {

    @Query("SELECT * FROM remote_config_entry")
    fun listEntries(): List<RemoteConfigEntryRow>

    @Query("DELETE FROM remote_config_entry")
    fun deleteAllEntries()

    @Insert
    fun insertEntries(rows: List<RemoteConfigEntryRow>)

    @Query("SELECT * FROM remote_config_state WHERE id = 1 LIMIT 1")
    fun getState(): RemoteConfigStateRow?

    @Upsert
    fun upsertState(row: RemoteConfigStateRow)
}
