package com.taotao.music.data

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream
import java.net.URL

/** 将 AI 成图保存到系统图片库；Android 10 及以上使用分区存储。 */
object AiImageSaver {
    fun save(context: Context, imageUrl: String): String {
        val connection = URL(imageUrl).openConnection()
        val mimeType = connection.contentType?.substringBefore(';')?.takeIf { it.startsWith("image/") }
            ?: "image/png"
        val extension = if (mimeType == "image/jpeg") "jpg" else "png"
        val fileName = "Taotao_${System.currentTimeMillis()}.$extension"

        connection.getInputStream().use { input ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                    put(MediaStore.Images.Media.MIME_TYPE, mimeType)
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/桃桃音乐")
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: error("无法创建图片文件")
                try {
                    resolver.openOutputStream(uri)?.use { output -> input.copyTo(output) }
                        ?: error("无法写入图片文件")
                } catch (error: Throwable) {
                    resolver.delete(uri, null, null)
                    throw error
                }
            } else {
                val directory = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                    "桃桃音乐",
                ).apply { mkdirs() }
                val file = File(directory, fileName)
                FileOutputStream(file).use { output -> input.copyTo(output) }
                MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf(mimeType), null)
            }
        }
        return "已保存到系统图片库"
    }
}
