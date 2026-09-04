package com.taotao.music.data

import android.content.Context

/**
 * 本机最近使用过的悟空 IM 对端 UUID。
 *
 * 它仅保存公开的聊天标识，不保存 Token、消息正文或悟空本地消息库的数据；同时按桃桃账号
 * 隔离，避免同一设备切换账号后把另一人的常用联系人展示出来。
 */
class ImPeerStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("im_peers", Context.MODE_PRIVATE)

    fun read(accountId: Long?): List<String> {
        if (accountId == null || accountId <= 0) return emptyList()
        return preferences.getStringSet(key(accountId), emptySet()).orEmpty().sorted()
    }

    fun remember(accountId: Long?, uid: String): List<String> {
        if (accountId == null || accountId <= 0) return emptyList()
        val normalized = uid.trim().lowercase()
        if (!UUID_PATTERN.matches(normalized)) return read(accountId)
        val peers = read(accountId).toMutableSet().apply { add(normalized) }.take(MAX_PEERS).toSet()
        preferences.edit().putStringSet(key(accountId), peers).apply()
        return peers.sorted()
    }

    private fun key(accountId: Long) = "peers_$accountId"

    private companion object {
        const val MAX_PEERS = 30
        val UUID_PATTERN = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
    }
}
