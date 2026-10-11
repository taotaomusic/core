package com.taotao.music.data

import android.content.Context
import com.taotao.music.data.db.ImPeerRow
import com.taotao.music.data.db.MirroredPreference
import com.taotao.music.data.db.TaotaoLocalDatabase

/** IM 对端的镜像值：账号 → 最近对端 UUID 集合。 */
private data class ImPeersSnapshot(
    val buckets: Map<Long, Set<String>>,
)

/**
 * 本机最近使用过的悟空 IM 对端 UUID。
 *
 * 它仅保存公开的聊天标识，不保存 Token、消息正文或悟空本地消息库的数据；同时按桃桃账号
 * 隔离，避免同一设备切换账号后把另一人的常用联系人展示出来。
 */
class ImPeerStore(context: Context) {
    private val mirror = object : MirroredPreference<ImPeersSnapshot>(context, "im_peers") {
        override fun default() = ImPeersSnapshot(emptyMap())
        override fun load(db: TaotaoLocalDatabase) = ImPeersSnapshot(
            buckets = db.imDao().listAllPeers()
                .groupBy({ it.accountId }, { it.uid })
                .mapValues { (_, uids) -> uids.toSet() },
        )
        override fun persist(db: TaotaoLocalDatabase, value: ImPeersSnapshot) {
            val dao = db.imDao()
            dao.deleteAllPeers()
            if (value.buckets.isNotEmpty()) {
                dao.insertPeers(value.buckets.flatMap { (accountId, uids) -> uids.map { ImPeerRow(accountId, it) } })
            }
        }
    }

    fun read(accountId: Long?): List<String> {
        if (accountId == null || accountId <= 0) return emptyList()
        return mirror.current().buckets[accountId].orEmpty().sorted()
    }

    fun remember(accountId: Long?, uid: String): List<String> {
        if (accountId == null || accountId <= 0) return emptyList()
        val normalized = uid.trim().lowercase()
        if (!UUID_PATTERN.matches(normalized)) return read(accountId)
        val peers = read(accountId).toMutableSet().apply { add(normalized) }.take(MAX_PEERS).toSet()
        mirror.commit(ImPeersSnapshot(mirror.current().buckets + (accountId to peers)))
        return peers.sorted()
    }

    private companion object {
        const val MAX_PEERS = 30
        val UUID_PATTERN = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
    }
}
