package com.taotao.music.data

import android.content.Context
import java.util.UUID

/**
 * 匿名设备标识，首次启动生成后持久化。
 *
 * 用途是灰度放量分桶：未登录的客户端没有用户 ID，需要一个稳定标识，
 * 否则每次检查更新都会落到不同的分桶，更新提示会时有时无。
 * 只在本机生成，不含任何硬件标识符，不上传除自身以外的设备信息。
 */
class DeviceIdStore(context: Context) {
    private val preferences = context.getSharedPreferences("device", Context.MODE_PRIVATE)

    fun deviceId(): String = synchronized(LOCK) {
        preferences.getString(KEY, null)?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString().also {
            preferences.edit().putString(KEY, it).apply()
        }
    }

    private companion object {
        const val KEY = "device_id"

        /** 首次生成时加锁，避免两个线程同时初始化得到两个不同的标识。 */
        val LOCK = Any()
    }
}
