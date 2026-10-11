package com.taotao.music.data

import android.content.Context
import android.os.Looper
import com.taotao.music.data.db.AiConversationRow
import com.taotao.music.data.db.AiMessageRow
import com.taotao.music.data.db.LocalDb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.util.UUID

/** AI 工作台的本地多会话缓存，落在本地 Room 库；不保存服务端 Key 或访问令牌。 */
class AiChatStore(context: Context) {
    private val db by lazy { LocalDb.ready(context) }

    @Volatile
    private var cached: List<SavedAiConversation>? = null
    private val hydrateLock = Any()

    fun readConversations(): List<SavedAiConversation> = ensureLoaded()

    fun saveConversations(conversations: List<SavedAiConversation>) {
        val sanitized = sanitize(conversations)
        cached = sanitized
        LocalDb.writeExecutor.execute {
            runCatching { persist(sanitized) }
        }
    }

    fun newConversation(): SavedAiConversation = SavedAiConversation(
        UUID.randomUUID().toString(), "新对话", false,
        listOf(SavedAiChatMessage("assistant", "你好，我可以把你的音乐灵感变成一张图片。告诉我你想看到的画面吧。", null, 0, null, null)),
    )

    private fun sanitize(conversations: List<SavedAiConversation>): List<SavedAiConversation> =
        conversations
            .filter { conversation -> conversation.messages.any { it.role == "user" } }
            .takeLast(MAX_CONVERSATIONS)
            .map { conversation ->
                conversation.copy(messages = conversation.messages.takeLast(MAX_MESSAGES_PER_CONVERSATION))
            }

    private fun ensureLoaded(): List<SavedAiConversation> {
        cached?.let { return it }
        return synchronized(hydrateLock) {
            cached ?: run {
                val loaded = if (Looper.myLooper() == Looper.getMainLooper()) {
                    // 首次 hydration 撞上主线程时垫一次 runBlocking（一次性开销，与旧 prefs 读盘同量级）。
                    runBlocking(Dispatchers.IO) { loadFromDb() }
                } else {
                    loadFromDb()
                }
                cached = loaded
                loaded
            }
        }
    }

    private fun loadFromDb(): List<SavedAiConversation> {
        val dao = db.aiChatDao()
        val messagesByConversation = dao.listMessages().groupBy { it.conversationId }
        return dao.listConversations().mapNotNull { row ->
            val messages = messagesByConversation[row.conversationId].orEmpty().map { message ->
                SavedAiChatMessage(message.role, message.text, message.taskId, message.progress, message.imageUrl, message.error)
            }
            // 旧版本会把只含欢迎语的空白对话也落盘；读取时一并清掉，避免历史无限堆积。
            if (messages.any { it.role == "user" }) {
                SavedAiConversation(row.conversationId, row.title, row.pinned, messages)
            } else {
                null
            }
        }
    }

    /** 全量重写两张表（有 200×80 的硬上限，事务里几十毫秒以内）；后台线程调用。 */
    private fun persist(conversations: List<SavedAiConversation>) {
        val conversationRows = conversations.mapIndexed { index, conversation ->
            AiConversationRow(conversation.id, conversation.title, conversation.pinned, index)
        }
        val messageRows = conversations.flatMap { conversation ->
            conversation.messages.mapIndexed { messageIndex, message ->
                AiMessageRow(
                    conversationId = conversation.id,
                    position = messageIndex,
                    role = message.role,
                    text = message.text,
                    taskId = message.taskId,
                    progress = message.progress,
                    imageUrl = message.imageUrl,
                    error = message.error,
                )
            }
        }
        db.runInTransaction {
            val dao = db.aiChatDao()
            dao.deleteAllConversations()
            dao.deleteAllMessages()
            if (conversationRows.isNotEmpty()) dao.insertConversations(conversationRows)
            if (messageRows.isNotEmpty()) dao.insertMessages(messageRows)
        }
    }

    private companion object {
        const val MAX_CONVERSATIONS = 200
        const val MAX_MESSAGES_PER_CONVERSATION = 80
    }
}

data class SavedAiConversation(val id: String, val title: String, val pinned: Boolean, val messages: List<SavedAiChatMessage>)
data class SavedAiChatMessage(val role: String, val text: String, val taskId: String?, val progress: Int, val imageUrl: String?, val error: String?)
