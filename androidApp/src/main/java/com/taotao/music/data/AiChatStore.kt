package com.taotao.music.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** AI 工作台的本地多会话缓存；不保存服务端 Key 或访问令牌。 */
class AiChatStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("ai_chat", Context.MODE_PRIVATE)

    fun readConversations(): List<SavedAiConversation> = runCatching {
        val items = JSONArray(preferences.getString(KEY_CONVERSATIONS, "[]"))
        buildList {
            for (index in 0 until items.length()) {
                val item = items.optJSONObject(index) ?: continue
                val id = item.optString("id").takeIf { it.isNotBlank() } ?: continue
                val messages = decodeMessages(item.optJSONArray("messages") ?: JSONArray())
                // 旧版本会把只含欢迎语的空白对话也落盘；读取时一并清掉，避免历史无限堆积。
                if (messages.any { it.role == "user" }) {
                    add(SavedAiConversation(id, item.optString("title").ifBlank { "新对话" }, item.optBoolean("pinned"), messages))
                }
            }
        }
    }.getOrDefault(emptyList()).takeLast(MAX_CONVERSATIONS)

    fun saveConversations(conversations: List<SavedAiConversation>) {
        val array = JSONArray()
        conversations.filter { conversation -> conversation.messages.any { it.role == "user" } }
            .takeLast(MAX_CONVERSATIONS)
            .forEach { conversation ->
            val messages = JSONArray()
            conversation.messages.takeLast(MAX_MESSAGES_PER_CONVERSATION).forEach { message ->
                messages.put(JSONObject().put("role", message.role).put("text", message.text).put("taskId", message.taskId).put("progress", message.progress).put("imageUrl", message.imageUrl).put("error", message.error))
            }
            array.put(JSONObject().put("id", conversation.id).put("title", conversation.title).put("pinned", conversation.pinned).put("messages", messages))
        }
        preferences.edit().putString(KEY_CONVERSATIONS, array.toString()).apply()
    }

    fun newConversation(): SavedAiConversation = SavedAiConversation(
        UUID.randomUUID().toString(), "新对话", false,
        listOf(SavedAiChatMessage("assistant", "你好，我可以把你的音乐灵感变成一张图片。告诉我你想看到的画面吧。", null, 0, null, null)),
    )

    private fun decodeMessages(items: JSONArray): List<SavedAiChatMessage> = buildList {
        for (index in 0 until items.length()) {
            val item = items.optJSONObject(index) ?: continue
            val text = item.optString("text").trim()
            if (text.isNotEmpty()) add(SavedAiChatMessage(item.optString("role", "assistant"), text, item.optString("taskId").takeIf { it.isNotBlank() }, item.optInt("progress", 0), item.optString("imageUrl").takeIf { it.isNotBlank() }, item.optString("error").takeIf { it.isNotBlank() }))
        }
    }

    private companion object {
        const val KEY_CONVERSATIONS = "conversations"
        const val MAX_CONVERSATIONS = 200
        const val MAX_MESSAGES_PER_CONVERSATION = 80
    }
}

data class SavedAiConversation(val id: String, val title: String, val pinned: Boolean, val messages: List<SavedAiChatMessage>)
data class SavedAiChatMessage(val role: String, val text: String, val taskId: String?, val progress: Int, val imageUrl: String?, val error: String?)
