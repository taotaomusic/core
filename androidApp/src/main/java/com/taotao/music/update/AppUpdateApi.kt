package com.taotao.music.update

import com.taotao.music.data.TencentMusicApi
import com.taotao.music.data.TokenProvider
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 热更新与远程配置客户端。
 *
 * 令牌是可选的，且刻意使用 [TokenProvider.currentToken] 而不是 `validToken()`：
 * 后者在令牌过期时会同步续期，续期失败还会触发会话失效回调把用户踢回登录页。
 * 检查更新是后台动作，绝不能因此让用户掉线；令牌过期时服务端会退回按设备号分桶。
 */
class AppUpdateApi(private val tokenProvider: TokenProvider?) {

    fun bootstrap(versionCode: Long, sdk: Int, deviceId: String, channel: String = "release"): BootstrapResult {
        val query = "?channel=${channel}&versionCode=$versionCode&sdk=$sdk&deviceId=$deviceId"
        val connection = (URL("${TencentMusicApi.ENDPOINT}$PATH_BOOTSTRAP$query").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 20_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "TaotaoMusic/1.0")
            tokenProvider?.currentToken()?.takeIf { it.isNotBlank() }?.let { setRequestProperty("Authorization", "Bearer $it") }
        }
        val code = connection.responseCode
        if (code !in 200..299) {
            runCatching { connection.errorStream?.close() }
            throw IllegalStateException("检查更新失败：HTTP $code")
        }
        val body = connection.inputStream.bufferedReader().use { it.readText() }
        val result = JSONObject(body)
        check(result.optInt("code") == 0) { result.optString("message", "检查更新失败") }
        val data = result.getJSONObject("data")
        return BootstrapResult(
            release = releaseOf(data.optJSONObject("update")),
            forced = data.optJSONObject("update")?.optBoolean("forced") == true,
            minSupportedVersionCode = data.optJSONObject("update")?.optLong("minSupportedVersionCode") ?: 0L,
            patch = patchOf(data.optJSONObject("patch")),
            config = configOf(data.optJSONObject("config")),
            configVersion = data.optLong("configVersion"),
        )
    }

    private fun releaseOf(update: JSONObject?): UpdateRelease? {
        if (update == null || !update.optBoolean("available")) return null
        val apkUrl = update.optString("apkUrl")
        val versionCode = update.optLong("versionCode")
        if (apkUrl.isBlank() || versionCode <= 0L) return null
        return UpdateRelease(
            versionCode = versionCode,
            versionName = update.optString("versionName"),
            apkUrl = apkUrl,
            apkSize = update.optLong("apkSize"),
            apkSha256 = update.optString("apkSha256").lowercase(),
            releaseNote = update.optString("releaseNote"),
        )
    }

    /** 服务端还没部署补丁功能时这个字段不存在，按"没有补丁"处理即可。 */
    private fun patchOf(patch: JSONObject?): AvailablePatch? {
        if (patch == null || !patch.optBoolean("available")) return null
        val url = patch.optString("url")
        val patchVersion = patch.optInt("patchVersion")
        val targetVersionCode = patch.optLong("targetVersionCode")
        if (url.isBlank() || patchVersion <= 0 || targetVersionCode <= 0L) return null
        return AvailablePatch(
            patchVersion = patchVersion,
            targetVersionCode = targetVersionCode,
            url = url,
            size = patch.optLong("size"),
            sha256 = patch.optString("sha256").lowercase(),
            note = patch.optString("note"),
        )
    }

    private fun configOf(config: JSONObject?): Map<String, String> {
        if (config == null) return emptyMap()
        return config.keys().asSequence().associateWith { config.optString(it) }
    }

    private companion object {
        const val PATH_BOOTSTRAP = "/api/v1/app/bootstrap"
    }
}
