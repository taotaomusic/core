package com.taotao.music.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert

/** 悟空 IM 短期连接凭据（原 im_session 文件）。恒定单行。 */
@Entity(tableName = "im_session_credential")
data class ImSessionCredentialRow(
    @PrimaryKey val id: Int,
    val accountId: Long,
    val uid: String,
    val token: String,
    val expiresAt: Long,
    val deviceFlag: Int,
    val deviceLevel: Int,
    val gatewayUrl: String,
)

/** im_session_credential 的恒定单行主键。 */
const val IM_SESSION_CREDENTIAL_ID = 1

/**
 * 本机最近用过的悟空 IM 对端（原 im_peers 文件的按账号字符串集合）。
 * 一对端一行、按账号分桶，超出保留上限由存储类负责淘汰。
 */
@Entity(tableName = "im_peer", primaryKeys = ["accountId", "uid"])
data class ImPeerRow(
    val accountId: Long,
    val uid: String,
)

@Dao
interface ImDao {

    @Query("SELECT * FROM im_session_credential WHERE id = 1 LIMIT 1")
    fun getSession(): ImSessionCredentialRow?

    @Upsert
    fun upsertSession(row: ImSessionCredentialRow)

    @Query("DELETE FROM im_session_credential")
    fun deleteSession()

    @Query("SELECT * FROM im_peer")
    fun listAllPeers(): List<ImPeerRow>

    @Query("DELETE FROM im_peer")
    fun deleteAllPeers()

    @Insert
    fun insertPeers(rows: List<ImPeerRow>)
}
