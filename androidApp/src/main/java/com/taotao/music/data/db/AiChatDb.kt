package com.taotao.music.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query

/** AI 工作台会话行（原 SharedPreferences 的 ai_chat/conversations JSON 桶）。 */
@Entity(tableName = "ai_conversations")
data class AiConversationRow(
    @PrimaryKey val conversationId: String,
    val title: String,
    val pinned: Boolean,
    /** 会话在列表中的顺序，读取时按升序还原。 */
    val position: Int,
)

/** AI 会话内消息行；单条损坏只丢一条消息，不再连坐整个会话列表。 */
@Entity(tableName = "ai_messages", primaryKeys = ["conversationId", "position"])
data class AiMessageRow(
    val conversationId: String,
    val position: Int,
    val role: String,
    val text: String,
    val taskId: String?,
    val progress: Int,
    val imageUrl: String?,
    val error: String?,
)

@Dao
interface AiChatDao {

    @Query("SELECT * FROM ai_conversations ORDER BY position ASC")
    fun listConversations(): List<AiConversationRow>

    @Query("SELECT * FROM ai_messages ORDER BY conversationId ASC, position ASC")
    fun listMessages(): List<AiMessageRow>

    @Insert
    fun insertConversations(rows: List<AiConversationRow>)

    @Insert
    fun insertMessages(rows: List<AiMessageRow>)

    @Query("DELETE FROM ai_conversations")
    fun deleteAllConversations()

    @Query("DELETE FROM ai_messages")
    fun deleteAllMessages()
}
