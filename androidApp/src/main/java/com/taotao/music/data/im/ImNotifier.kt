package com.taotao.music.data.im

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.withTimeoutOrNull

/**
 * IM 消息通知器。
 *
 * 在前台时只震动；切后台后收到消息时显示通知并播放系统提示音。
 */
class ImNotifier(private val context: Context) {
    private val manager = NotificationManagerCompat.from(context)

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "聊天消息", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "显示新的聊天消息"
                    setShowBadge(true)
                    enableVibration(true)
                    setSound(
                        RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                        null,
                    )
                },
            )
        }
    }

    fun canNotify(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    suspend fun showMessage(peerName: String, content: String, avatarUrl: String? = null) {
        if (!canNotify()) return
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle(peerName)
            .setContentText(content)
            .setLargeIcon(loadAvatarBitmap(avatarUrl))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))
            .setVibrate(longArrayOf(0, 250, 250, 250))
            .build()

        runCatching { manager.notify(NOTIFICATION_ID++, notification) }
    }

    /**
     * 通知大图标：限时加载并压到通知尺寸，失败退回 null（默认无图标样式）。
     * 调用方在 IM 事件队列（IO 线程）里，阻塞加载不影响界面。
     */
    private suspend fun loadAvatarBitmap(url: String?): Bitmap? {
        if (url.isNullOrBlank()) return null
        return runCatching {
            withTimeoutOrNull(NOTIFY_ICON_TIMEOUT_MS) {
                val request = ImageRequest.Builder(context)
                    .data(url)
                    .size(NOTIFY_ICON_PIXELS)
                    .build()
                (context.imageLoader.execute(request) as? SuccessResult)?.drawable?.toBitmap()
            }
        }.getOrNull()
    }

    private companion object {
        const val CHANNEL_ID = "im_message"
        var NOTIFICATION_ID = 5001

        /** 通知头像解码上限（像素）与加载时限；超时就放弃大图标，不让通知迟到。 */
        const val NOTIFY_ICON_PIXELS = 256
        const val NOTIFY_ICON_TIMEOUT_MS = 1_500L
    }
}
