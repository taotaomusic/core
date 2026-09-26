package com.taotao.music.data.crypto

import android.content.ContentResolver
import android.provider.Settings

/**
 * 传输层加密用的硬件设备号来源。
 *
 * 取 [Settings.Secure.ANDROID_ID]：同一设备 + 同一签名密钥下稳定，恢复出厂或换签名会变。
 * 它的作用是让传输加密的会话密钥按设备派生（`per_device_psk = HKDF(psk, salt=deviceId)`），
 * 即使加密协议被逆向，攻击者仍需在每台真机上单独提取此值才能冒充该机。
 *
 * 与 [com.taotao.music.data.DeviceIdStore] 区别开：那是灰度分桶用的随机 UUID，
 * 不含硬件标识、用途不同，两者不可互相替代。
 */
class HardwareDeviceId(private val contentResolver: ContentResolver) {

    /**
     * 返回稳定的硬件设备号。
     *
     * 极少数厂商 ROM 会返回空串或缺失，此时回退到一个固定占位值——占位值让派生仍可进行，
     * 代价是这些设备之间无法互相区分（可接受：它们本就拿不到稳定硬件标识）。
     */
    fun deviceId(): String {
        val androidId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
        return androidId?.takeIf { it.isNotBlank() } ?: FALLBACK
    }

    private companion object {
        /** ANDROID_ID 缺失时的固定占位值，保证 HKDF 派生不因空盐中断。 */
        const val FALLBACK = "unknown-android-device"
    }
}
