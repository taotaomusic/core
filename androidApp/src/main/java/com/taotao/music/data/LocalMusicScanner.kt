package com.taotao.music.data

import android.content.ContentResolver
import android.provider.MediaStore
import com.taotao.music.model.Song

/** 从系统 MediaStore 读取本机音频，不直接遍历文件系统。 */
class LocalMusicScanner(private val contentResolver: ContentResolver) {
    fun scan(): List<Song> {
        val songs = mutableListOf<Song>()
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DISPLAY_NAME,
        )
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        val sortOrder = "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"

        contentResolver.query(collection, projection, selection, null, sortOrder)?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val title = cursor.getString(titleColumn).orEmpty().ifBlank { "未知歌曲" }
                val artist = cursor.getString(artistColumn).orEmpty().ifBlank { "未知艺术家" }
                val duration = formatDuration(cursor.getLong(durationColumn))
                val fileName = cursor.getString(nameColumn).orEmpty()
                val encrypted = fileName.substringAfterLast('.', "").lowercase() in setOf("qmcflac", "qmc0", "qmc3")
                val uri = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL, id)
                val extension = fileName.substringAfterLast('.', "").lowercase()
                songs += Song(title, artist, duration, colorFor(id), uri.toString(), encrypted, extension)
            }
        }
        return songs
    }

    private fun formatDuration(milliseconds: Long): String {
        val totalSeconds = milliseconds / 1000
        return "%02d:%02d".format(totalSeconds / 60, totalSeconds % 60)
    }

    private fun colorFor(seed: Long): Long = listOf(0xFFFFB4A2, 0xFFFFD6A5, 0xFFB8C0FF, 0xFFBDE0FE, 0xFFCDEAC0)[(seed % 5).toInt()]
}
