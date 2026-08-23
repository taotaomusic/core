package com.taotao.music.data

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * 下载进度与完成通知。
 *
 * 刻意**不用前台服务**：这个项目已经被前台服务坑过一次（最初那个闪退就是
 * startForeground 超时），而一首歌几秒就下完，不值得为它再引入一个前台服务
 * 和 Android 14+ 的 dataSync 类型声明。代价是切后台且系统吃紧时下载可能被杀 ——
 * 与改动前的行为一致，只是现在至少能看见进度。
 *
 * 通知权限没给时所有方法静默返回：下载本身不该因为看不到通知就失败。
 */
class DownloadNotifier(private val context: Context) {
    private val manager = NotificationManagerCompat.from(context)

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "歌曲下载", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "显示歌曲下载进度与结果"
                    setShowBadge(false)
                },
            )
        }
    }

    /** 通知权限是否可用。Android 13 起需要运行时授权。 */
    fun canNotify(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    /** 更新进度。[total] 为 0 时显示不确定进度条（上游没给 Content-Length）。 */
    fun progress(title: String, downloaded: Long, total: Long) {
        if (!canNotify()) return
        val builder = baseBuilder(title)
            .setContentText(if (total > 0) "${percentOf(downloaded, total)}%  ${sizeText(downloaded, total)}" else "正在下载…")
            .setOngoing(true)
        if (total > 0) builder.setProgress(100, percentOf(downloaded, total), false)
        else builder.setProgress(0, 0, true)
        notify(builder.build())
    }

    fun completed(title: String) {
        if (!canNotify()) return
        notify(
            baseBuilder(title)
                .setContentText("下载完成，可离线播放")
                .setOngoing(false)
                .setAutoCancel(true)
                .build(),
        )
    }

    fun failed(title: String, reason: String) {
        if (!canNotify()) return
        notify(
            baseBuilder(title)
                .setContentText("下载失败：$reason")
                .setOngoing(false)
                .setAutoCancel(true)
                .build(),
        )
    }

    fun clear() = manager.cancel(NOTIFICATION_ID)

    private fun baseBuilder(title: String) = NotificationCompat.Builder(context, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.stat_sys_download)
        .setContentTitle(title)
        .setOnlyAlertOnce(true)
        .setPriority(NotificationCompat.PRIORITY_LOW)

    /** 权限可能在两次检查之间被撤销，SecurityException 不能让下载跟着失败。 */
    private fun notify(notification: android.app.Notification) {
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
    }

    private fun percentOf(downloaded: Long, total: Long) =
        ((downloaded * 100) / total).coerceIn(0L, 100L).toInt()

    private fun sizeText(downloaded: Long, total: Long) =
        "${megabytes(downloaded)} / ${megabytes(total)} MB"

    private fun megabytes(bytes: Long) = ((bytes * 10) / (1024 * 1024)) / 10.0

    private companion object {
        const val CHANNEL_ID = "song_download"

        /** 固定 ID：同一时刻只下载一首，复用同一条通知即可。 */
        const val NOTIFICATION_ID = 4201
    }
}
