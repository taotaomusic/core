package com.taotao.music.data

import android.content.ContentResolver
import android.content.res.AssetManager
import android.net.Uri
import com.taotao.music.model.Song
import java.io.File

/** 将已获授权的 QMC 本地文件解码到缓存目录，原始文件不会被修改。 */
class QmcAudioDecoder(private val contentResolver: ContentResolver, private val assetManager: AssetManager, private val cacheDir: File) {
    private val key: IntArray by lazy {
        assetManager.open("qmc_key.txt").bufferedReader().use { reader ->
            reader.readText().split(',').filter { it.isNotBlank() }.map { it.removePrefix("0x").toInt(16) }.toIntArray()
        }
    }

    suspend fun decode(song: Song): Song {
        require(song.isEncrypted && song.audioUri != null) { "不是受保护的 QMC 文件" }
        val source = Uri.parse(song.audioUri)
        val extension = if (song.sourceExtension == "qmcflac") "flac" else "mp3"
        val output = File(cacheDir, "qmc_${song.title.hashCode()}.$extension")
        if (!output.exists()) {
            contentResolver.openInputStream(source).use { input ->
                requireNotNull(input) { "无法读取本地音频" }
                output.outputStream().use { out ->
                    val buffer = ByteArray(8192)
                    var offset = 0
                    var count: Int
                    while (input.read(buffer).also { count = it } > 0) {
                        for (index in 0 until count) buffer[index] = (buffer[index].toInt() xor mapByte(offset + index)).toByte()
                        out.write(buffer, 0, count)
                        offset += count
                    }
                }
            }
        }
        return song.copy(audioUri = Uri.fromFile(output).toString(), isEncrypted = false)
    }

    private fun mapByte(value: Int): Int {
        val normalized = if (value > 0x7FFF) value % 0x7FFF else value.coerceAtLeast(0)
        return key[(normalized * normalized + 80923) % 256]
    }
}
