package com.taotao.music.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert

/**
 * 本地库的杂项键值（outbox 版本号、history revision、清空标记等，键沿用旧的
 * "account.<id>.<name>" 命名；以及 SharedPreferences→Room 一次性迁移的完成标记）。
 *
 * 放在库自身内部而不是 prefs 里，是为了让「数据写入 + 标记翻转」能在同一个事务里原子完成。
 */
@Entity(tableName = "local_db_meta")
data class LocalDbMetaRow(
    @PrimaryKey val metaKey: String,
    val metaValue: String,
)

@Dao
interface LocalDbMetaDao {

    @Query("SELECT metaValue FROM local_db_meta WHERE metaKey = :metaKey LIMIT 1")
    fun getValue(metaKey: String): String?

    @Upsert
    fun upsert(row: LocalDbMetaRow)

    @Query("DELETE FROM local_db_meta WHERE metaKey = :metaKey")
    fun delete(metaKey: String)
}
