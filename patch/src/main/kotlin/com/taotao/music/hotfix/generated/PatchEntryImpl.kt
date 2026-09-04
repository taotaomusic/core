package com.taotao.music.hotfix.generated

import com.taotao.music.hotfix.PatchDispatcher
import com.taotao.music.hotfix.PatchEntry
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64

/**
 * 1.0.128 IM 已读热修。悟空把 type=99 已读回执作为持久内部消息随会话同步返回；旧包
 * 没有在冷启动时消费它，故进程死亡后已读状态回退。补丁先扫描回执，再建立消息投影。
 *
 * 补丁不直接引用宿主或悟空 SDK 类型，避免补丁类加载器制造同名类冲突。
 */
class PatchEntryImpl : PatchEntry {
    override fun targets(): List<String> = listOf(WUKONG_CLIENT)

    override fun dispatcher(): PatchDispatcher = object : PatchDispatcher {
        override fun isSupport(methodKey: String): Boolean = methodKey == MERGE_KEY

        override fun dispatch(methodKey: String, receiver: Any?, args: Array<Any?>): Any? {
            check(methodKey == MERGE_KEY) { "补丁没有实现方法：$methodKey" }
            val client = requireNotNull(receiver) { "缺少 IM 客户端实例" }
            val rows = args.firstOrNull() as? JSONArray ?: return 0
            return restore(client, rows)
        }
    }

    private fun restore(client: Any, rows: JSONArray): Int {
        val uid = connectionUid(client) ?: return 0
        val readIds = collectReadIds(rows)
        val restored = ArrayList<Any>()
        val peers = linkedSetOf<String>()

        for (rowIndex in 0 until rows.length()) {
            val row = rows.optJSONObject(rowIndex) ?: continue
            val channelId = row.optString("channel_id").trim().lowercase()
            if (!UUID_REGEX.matches(channelId) || row.optInt("channel_type") != 1) continue
            val recents = row.optJSONArray("recents") ?: continue
            for (messageIndex in 0 until recents.length()) {
                val message = recents.optJSONObject(messageIndex) ?: continue
                val payload = payloadOf(message)
                if (payload.optString("cmd").isNotBlank()) continue
                val fromUid = message.optString("from_uid").trim().lowercase()
                val peerUid = if (fromUid == uid) channelId else fromUid
                if (!UUID_REGEX.matches(peerUid)) continue
                val content = payload.optString("content").trim()
                if (content.isBlank()) continue

                val id = message.optString("message_idstr", message.optString("message_id"))
                    .ifBlank { message.optString("client_msg_no") }
                val clientMsgNo = message.optString("client_msg_no").ifBlank { id }
                val extra = message.optJSONObject("message_extra")
                val revoked = extra?.optInt("revoke") == 1 || message.optInt("revoke") == 1
                restored += newMessage(
                    client,
                    id,
                    clientMsgNo,
                    peerUid,
                    if (revoked) "消息已撤回" else content,
                    message.optLong("timestamp") * 1_000L,
                    fromUid == uid,
                    fromUid == uid && (extra?.optInt("readed") == 1 || message.optInt("readed") == 1 || id in readIds || clientMsgNo in readIds),
                    revoked,
                )
                peers += peerUid
            }
        }
        if (restored.isEmpty()) return 0
        invokePrivate(client, "mergeMessages", restored)
        mergePeers(client, peers)
        return restored.size
    }

    private fun collectReadIds(rows: JSONArray): Set<String> = buildSet {
        for (rowIndex in 0 until rows.length()) {
            val recents = rows.optJSONObject(rowIndex)?.optJSONArray("recents") ?: continue
            for (messageIndex in 0 until recents.length()) {
                val payload = recents.optJSONObject(messageIndex)?.let(::payloadOf) ?: continue
                if (payload.optString("cmd") != READ_COMMAND) continue
                val ids = payload.optJSONObject("param")?.optJSONArray("message_ids") ?: continue
                for (idIndex in 0 until ids.length()) {
                    ids.optString(idIndex).takeIf { it.isNotBlank() }?.let(::add)
                }
            }
        }
    }

    private fun payloadOf(message: JSONObject): JSONObject {
        val raw = message.optString("payload")
        val decoded = runCatching { String(Base64.getDecoder().decode(raw), Charsets.UTF_8) }.getOrDefault(raw)
        return runCatching { JSONObject(decoded) }.getOrDefault(JSONObject())
    }

    private fun connectionUid(client: Any): String? {
        val connection = privateField(client, "_connection") ?: return null
        val value = invoke(connection, "getValue") ?: return null
        return invoke(value, "getUid") as? String
    }

    private fun newMessage(
        client: Any,
        id: String,
        clientMsgNo: String,
        peerUid: String,
        content: String,
        sentAtMillis: Long,
        isMine: Boolean,
        isRead: Boolean,
        isRevoked: Boolean,
    ): Any {
        val type = Class.forName(IM_CHAT_MESSAGE, true, client.javaClass.classLoader)
        val constructor = type.declaredConstructors.single { it.parameterTypes.size == 8 }
        return constructor.newInstance(id, clientMsgNo, peerUid, content, sentAtMillis, isMine, isRead, isRevoked)
    }

    private fun mergePeers(client: Any, peers: Set<String>) {
        val flow = privateField(client, "_syncedPeers") ?: return
        val current = (invoke(flow, "getValue") as? List<*>)?.filterIsInstance<String>().orEmpty()
        invoke(flow, "setValue", (current + peers).distinct().sorted())
    }

    private fun privateField(instance: Any, name: String): Any? = runCatching {
        instance.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(instance)
    }.getOrNull()

    private fun invokePrivate(instance: Any, name: String, argument: Any) {
        instance.javaClass.getDeclaredMethod(name, List::class.java).apply { isAccessible = true }.invoke(instance, argument)
    }

    private fun invoke(instance: Any, name: String, vararg arguments: Any?): Any? = runCatching {
        val method = instance.javaClass.methods.firstOrNull {
            it.name == name && it.parameterTypes.size == arguments.size
        } ?: return null
        method.invoke(instance, *arguments)
    }.getOrNull()

    private companion object {
        const val WUKONG_CLIENT = "com.taotao.music.data.im.WukongImClient"
        const val IM_CHAT_MESSAGE = "com.taotao.music.data.im.ImChatMessage"
        const val MERGE_KEY = "com/taotao/music/data/im/WukongImClient#mergeSyncedConversations(Lorg/json/JSONArray;)I"
        const val READ_COMMAND = "taotao.messageRead"
        val UUID_REGEX = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
    }
}
