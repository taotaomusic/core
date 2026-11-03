package com.taotao.music.ui.common

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.taotao.music.model.Song
import java.io.File

/**
 * 把导出的日志文件交给系统分享面板。
 *
 * 必须走 FileProvider 换成 content:// —— Android 7 起直接传 file:// 给别的应用会抛
 * FileUriExposedException。`crash/export/` 已在 file_paths.xml 里声明。
 */
internal fun shareLogFile(context: Context, file: File) {
    val uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        file,
    )
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, file.name)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "导出崩溃日志").apply {
        // 从非 Activity 上下文启动分享面板需要这个标记。
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    })
}

/**
 * 把歌曲短链交给 Android 系统，不附带服务端试听文件或上游音频地址。
 *
 * 状态层持有的是 application context（见 TaotaoApplication.appState），从非 Activity
 * 上下文启动分享面板必须带 FLAG_ACTIVITY_NEW_TASK：targetSdk >= P 时缺少它会抛
 * AndroidRuntimeException，表现为点「分享」后整个应用直接退回桌面。
 */
internal fun shareSongLink(context: Context, song: Song, url: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "${song.title} - ${song.artist}")
        putExtra(Intent.EXTRA_TEXT, "我在桃桃音乐分享了《${song.title}》\n$url")
    }
    context.startActivity(
        Intent.createChooser(intent, "分享歌曲").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        },
    )
}

/**
 * 把分享海报交给系统分享面板。
 *
 * `cache/share/` 已在 file_paths.xml 声明，改成 content:// 后授权读权限；同时把短链
 * 塞进 [Intent.EXTRA_TEXT]，只收文字的目标应用（部分旧版微信/QQ）仍能拿到链接。
 */
internal fun shareSongPoster(context: Context, poster: SharePoster) {
    val uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        poster.file,
    )
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "image/png"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, "${poster.song.title} - ${poster.song.artist}")
        putExtra(Intent.EXTRA_TEXT, "我在桃桃音乐分享了《${poster.song.title}》\n${poster.url}")
        clipData = ClipData.newRawUri("分享海报", uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(
        Intent.createChooser(intent, "分享歌曲").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        },
    )
}
