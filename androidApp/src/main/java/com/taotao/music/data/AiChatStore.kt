package com.taotao.music.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** AI 工作台的本地会话缓存；只保存对话和图片 URL，不保存服务端 Key 或访问令牌。 */
class AiChatStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("ai_chat", Context.MODE_PRIVATE)

    fun read(): List<SavedAiChatMessage> = runCatching {
        val items = JSONArray(preferences.getString(KEY_MESSAGES, "[]"))
        buildList {
            for (index in 0 until items.length()) {
                val item = items.optJSONObject(index) ?: continue
                val text = item.optString("text").trim()
                if (text.isNotEmpty()) {
                    add(
                        SavedAiChatMessage(
                            role = item.optString("role", "assistant"),
                            text = text,
                            taskId = item.optString("taskId").takeIf { it.isNotBlank() },
                            progress = item.optInt("progress", 0),
                            imageUrl = item.optString("imageUrl").takeIf { it.isNotBlank() },
                            error = item.optString("error").takeIf { it.isNotBlank() },
                        ),
                    )
                }
            }
        }
    }.getOrDefault(emptyList())

    fun save(messages: List<SavedAiChatMessage>) {
        val array = JSONArray()
        messages.takeLast(MAX_MESSAGES).forEach { message ->
            array.put(
                JSONObject()
                    .put("role", message.role)
                    .put("text", message.text)
                    .put("taskId", message.taskId)
                    .put("progress", message.progress)
                    .put("imageUrl", message.imageUrl)
                    .put("error", message.error),
            )
        }
        preferences.edit().putString(KEY_MESSAGES, array.toString()).apply()
    }

    private companion object {
        const val KEY_MESSAGES = "messages"
        const val MAX_MESSAGES = 80
    }
}

data class SavedAiChatMessage(
    val role: String,
    val text: String,
    val taskId: String?,
    val progress: Int,
    val imageUrl: String?,
    val error: String?,
)
