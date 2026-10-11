package com.taotao.music.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * 收藏关系 ID（带来源前缀的稳定键，如 "tencent:123456"）。
 * 收藏关系的权威来源始终是服务端，这张表只让状态在页面间与重启后保持一致。
 */
@Entity(tableName = "favorite_ids")
data class FavoriteIdRow(
    @PrimaryKey val stableKey: String,
)

/**
 * 收藏页首屏展示的歌曲快照。一个歌曲可能同时有数字 ID 与 mid 两个别名键，
 * 快照行以其主别名键为主键；[position] 维持旧存储的「新收藏在最前」顺序。
 */
@Entity(tableName = "favorite_songs")
data class FavoriteSongRow(
    @PrimaryKey val stableKey: String,
    val songJson: String,
    val position: Int,
)

@Dao
interface FavoritesDao {

    @Query("SELECT * FROM favorite_ids")
    fun listIds(): List<FavoriteIdRow>

    @Insert
    fun insertIds(rows: List<FavoriteIdRow>)

    @Query("DELETE FROM favorite_ids")
    fun deleteAllIds()

    @Query("SELECT * FROM favorite_songs ORDER BY position ASC")
    fun listSongs(): List<FavoriteSongRow>

    @Insert
    fun insertSongs(rows: List<FavoriteSongRow>)

    @Query("DELETE FROM favorite_songs")
    fun deleteAllSongs()
}
