package com.taotao.music.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert

/**
 * 通用本地键值行。键必须带存储域前缀（如 "auth.access_token"），保证多存储共用一张表
 * 时不撞键、且能按前缀整域删除。值为字符串：数字与布尔按约定序列化（布尔 "1"/"0"）。
 */
@Entity(tableName = "local_kv")
data class LocalKvRow(
    @PrimaryKey val storeKey: String,
    val storeValue: String,
)

@Dao
interface LocalKvDao {

    @Query("SELECT * FROM local_kv")
    fun all(): List<LocalKvRow>

    @Upsert
    fun upsert(row: LocalKvRow)

    @Insert
    fun insertAll(rows: List<LocalKvRow>)

    @Query("DELETE FROM local_kv WHERE storeKey = :storeKey")
    fun delete(storeKey: String)

    @Query("DELETE FROM local_kv WHERE storeKey LIKE :prefix || '%'")
    fun deletePrefix(prefix: String)
}
