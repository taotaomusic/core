package com.taotao.music.data

import android.content.Context
import com.taotao.music.data.db.DeviceIdentityRow
import com.taotao.music.data.db.MirroredPreference
import com.taotao.music.data.db.PREFERENCE_ROW_ID
import com.taotao.music.data.db.TaotaoLocalDatabase
import java.util.UUID

/**
 * 匿名设备标识，首次启动生成后持久化。
 *
 * 用途是灰度放量分桶：未登录的客户端没有用户 ID，需要一个稳定标识，
 * 否则每次检查更新都会落到不同的分桶，更新提示会时有时无。
 * 只在本机生成，不含任何硬件标识符，不上传除自身以外的设备信息。
 */
class DeviceIdStore(context: Context) {
    private val mirror = object : MirroredPreference<DeviceIdentityRow>(context, "device") {
        override fun default() = DeviceIdentityRow(PREFERENCE_ROW_ID, "")
        override fun load(db: TaotaoLocalDatabase) = db.preferenceDao().getDeviceIdentity()
        override fun persist(db: TaotaoLocalDatabase, value: DeviceIdentityRow) {
            db.preferenceDao().upsertDeviceIdentity(value)
        }
    }

    fun deviceId(): String = synchronized(LOCK) {
        mirror.current().deviceId.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString().also {
            mirror.commit(DeviceIdentityRow(PREFERENCE_ROW_ID, it))
        }
    }

    private companion object {
        /** 首次生成时加锁，避免两个线程同时初始化得到两个不同的标识。 */
        val LOCK = Any()
    }
}
