package com.taotao.music.desktop.crypto

import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Windows 桌面端传输层加密用的硬件设备号来源。
 *
 * 读注册表 `HKLM\SOFTWARE\Microsoft\Cryptography\MachineGuid`：随系统安装生成，
 * 重装系统会变，正常使用稳定。作用与 Android 的 [ANDROID_ID] 对应——让传输加密
 * 的会话密钥按设备派生，即使协议被逆向也需逐台真机提取此值才能冒充该机。
 *
 * 读不到时（权限受限、非 Windows 环境）回退到一次性生成并持久化的 UUID，
 * 持久化由调用方负责（本类只负责取硬件值或生成回退值）。
 */
object MachineGuid {

    /**
     * 读取 `MachineGuid`；失败返回 `null`，由调用方决定回退策略。
     *
     * 用 `reg query` 而不是 JNI 访问注册表：桌面端本就是 Windows JVM，避免为一次读取
     * 引入原生依赖。注册表值必须用 64 位视图读取，否则 32 位 JVM 会被重定向到 WoW6432Node。
     */
    fun read(): String? {
        return runCatching {
            val process = ProcessBuilder(
                "reg", "query",
                "HKLM\\SOFTWARE\\Microsoft\\Cryptography",
                "/v", "MachineGuid",
                "/reg:64",
            ).redirectErrorStream(true).start()

            val output = process.inputStream.bufferedReader().use { it.readText() }
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return@runCatching null
            }
            parseMachineGuid(output)
        }.getOrNull()
    }

    /**
     * 从 `reg query` 输出里抽出 GUID。输出形如：
     * `    MachineGuid    REG_SZ    <guid>`
     */
    internal fun parseMachineGuid(output: String): String? {
        val line = output.lineSequence().firstOrNull { it.contains("MachineGuid", ignoreCase = true) }
            ?: return null
        val marker = "REG_SZ"
        val index = line.indexOf(marker)
        if (index < 0) return null
        return line.substring(index + marker.length).trim().takeIf { it.isNotBlank() }
    }

    /** 读不到硬件值时生成的回退设备号，调用方需持久化以保持稳定。 */
    fun fallback(): String = "fallback-${UUID.randomUUID()}"
}
