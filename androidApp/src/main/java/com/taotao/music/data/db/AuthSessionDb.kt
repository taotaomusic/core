package com.taotao.music.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert

/**
 * 登录会话行（原 SharedPreferences 的 auth 文件）。
 *
 * 恒定单行（[AUTH_SESSION_ID]）；access/refresh 令牌可空表示未登录，[expiresAt] 为
 * 访问令牌过期时刻，[userId] 是本地账号分桶键（老客户端未重登时可为空）。
 */
@Entity(tableName = "auth_session")
data class AuthSessionRow(
    @PrimaryKey val id: Int,
    val accessToken: String?,
    val refreshToken: String?,
    val expiresAt: Long,
    val userId: Long?,
)

/** auth_session 的恒定单行主键。 */
const val AUTH_SESSION_ID = 1

@Dao
interface AuthSessionDao {

    @Query("SELECT * FROM auth_session WHERE id = 1 LIMIT 1")
    fun get(): AuthSessionRow?

    @Upsert
    fun upsert(row: AuthSessionRow)
}
