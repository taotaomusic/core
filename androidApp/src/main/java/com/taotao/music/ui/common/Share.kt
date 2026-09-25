package com.taotao.music.ui.common

import android.content.Context
import com.taotao.music.model.Song
import java.io.File

/**
 * 把导出的日志文件交给系统分享面板。
 *
 * 必须走 FileProvider 换成 content:// —— Android 7 起直接传 file:// 给别的应用会抛
 * FileUriExposedException。`crash/export/` 已在 file_paths.xml 里声明。
 */
internal fun shareLogFile(context: Context, file: File) {
    val uri = androidx.core.content.FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        file,
    )
    val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(android.content.Intent.EXTRA_STREAM, uri)
        putExtra(android.content.Intent.EXTRA_SUBJECT, file.name)
        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(android.content.Intent.createChooser(intent, "导出崩溃日志").apply {
        // 从非 Activity 上下文启动分享面板需要这个标记。
        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    })
}

/** 把歌曲短链交给 Android 系统，不附带服务端试听文件或上游音频地址。 */
internal fun shareSongLink(context: Context, song: Song, url: String) {
    val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(android.content.Intent.EXTRA_SUBJECT, "${song.title} - ${song.artist}")
        putExtra(android.content.Intent.EXTRA_TEXT, "我在桃桃音乐分享了《${song.title}》\n$url")
    }
    context.startActivity(android.content.Intent.createChooser(intent, "分享歌曲"))
}
