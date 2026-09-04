package com.taotao.music.web

import com.taotao.music.model.Song
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class ShareSong(
    val song: Song,
    val previewUrl: String,
    val appDownloadUrl: String,
    val previewDurationSeconds: Int,
)

/** 兼容服务端统一信封与直接对象，便于分享页由 CDN 或 NestJS 托管。 */
fun parseShareSong(payload: String): ShareSong {
    val root = Json.parseToJsonElement(payload).jsonObject
    val data = root["data"]?.jsonObject ?: root
    val title = data.requiredString("title")
    val artist = data.requiredString("artist")
    return ShareSong(
        song = Song(
            title = title,
            artist = artist,
            album = data.string("album"),
            duration = data.string("duration").ifBlank { "01:00" },
            color = 0xFFFA5E5B,
            coverUri = data.string("coverUrl").ifBlank { null },
            remoteId = data.string("songId").toLongOrNull(),
            mid = data.string("mid").ifBlank { null },
            type = data.string("type").toIntOrNull(),
            source = data.string("source").ifBlank { "tencent" },
            vip = data.boolean("vip"),
        ),
        previewUrl = data.requiredString("previewUrl"),
        appDownloadUrl = data.string("appDownloadUrl").ifBlank { "/download" },
        previewDurationSeconds = data.string("previewDurationSeconds").toIntOrNull()?.coerceIn(1, 60) ?: 60,
    )
}

private fun JsonObject.string(name: String): String = this[name]?.jsonPrimitive?.contentOrNull.orEmpty()

private fun JsonObject.requiredString(name: String): String =
    string(name).takeIf { it.isNotBlank() } ?: error("分享歌曲缺少 $name")

private fun JsonObject.boolean(name: String): Boolean = string(name).equals("true", ignoreCase = true)
