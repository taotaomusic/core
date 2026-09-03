package com.taotao.music.desktop

import org.json.JSONObject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Base64
import java.util.prefs.Preferences

/** Windows 端的访问令牌会话。刷新令牌是一次性轮换的，刷新请求串行执行但不占用本地状态锁。 */
class DesktopSession(
    private val endpoint: String = DesktopMusicApi.DEFAULT_ENDPOINT,
) {
    private val preferences = Preferences.userRoot().node("com.taotao.music.desktop.auth")
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build()
    private val lock = Any()
    /** 防止多个请求同时消费同一个一次性刷新令牌；退出不获取此锁，因此不会等待网络。 */
    private val renewLock = Any()

    /** 每次登录、退出或明确过期都会递增，用于丢弃旧账号请求的异步结果。 */
    @Volatile
    private var generation = 0L

    @Volatile
    var onExpired: (() -> Unit)? = null

    val isSignedIn: Boolean
        get() = synchronized(lock) { !preferences.get(KEY_REFRESH, "").isNullOrBlank() }

    val accountId: Long?
        get() = synchronized(lock) {
            preferences.getLong(KEY_ACCOUNT, 0L).takeIf { it > 0L }
                ?: decodeAccountId(preferences.get(KEY_ACCESS, null))
        }

    val sessionGeneration: Long
        get() = generation

    fun currentToken(): String? = synchronized(lock) { preferences.get(KEY_ACCESS, null) }

    fun validToken(expectedGeneration: Long? = null): String? {
        val snapshot = synchronized(lock) {
            if (expectedGeneration != null && generation != expectedGeneration) return@synchronized null
            TokenSnapshot(
                accessToken = preferences.get(KEY_ACCESS, null),
                expiresAt = preferences.getLong(KEY_EXPIRES, 0L),
            )
        }
        if (snapshot == null) return null
        if (!snapshot.accessToken.isNullOrBlank() && snapshot.expiresAt > System.currentTimeMillis() + 30_000L) {
            val token = snapshot.accessToken
            return token
        }
        return renew(null, expectedGeneration)
    }

    fun login(username: String, password: String): TokenPair {
        val result = publicPost("/api/v1/auth/login", JSONObject().put("username", username).put("password", password))
        val tokens = result.toTokenPair()
        save(tokens, preserveAccount = false, advanceGeneration = true)
        return tokens
    }

    fun register(username: String, password: String, email: String, verificationCode: String): TokenPair {
        val result = publicPost(
            "/api/v1/auth/register",
            JSONObject()
                .put("username", username)
                .put("password", password)
                .put("email", email)
                .put("verificationCode", verificationCode),
        )
        val tokens = result.toTokenPair()
        save(tokens, preserveAccount = false, advanceGeneration = true)
        return tokens
    }

    fun signOut() {
        revokeRefreshToken(clearLocalSession())
    }

    /** 立即使本地会话失效，返回待撤销的刷新令牌；网络撤销由调用方放到 IO 线程。 */
    fun clearLocalSession(): String? = synchronized(lock) {
        val value = preferences.get(KEY_REFRESH, null)
        preferences.remove(KEY_ACCESS)
        preferences.remove(KEY_REFRESH)
        preferences.remove(KEY_EXPIRES)
        preferences.remove(KEY_ACCOUNT)
        preferences.flush()
        generation++
        value
    }

    /** 撤销已经从本地删除的刷新令牌；网络失败不影响本地退出结果。 */
    fun revokeRefreshToken(refresh: String?) {
        if (refresh.isNullOrBlank()) return
        runCatching {
            publicRequest(
                "/api/v1/auth/logout",
                "POST",
                JSONObject().put("refreshToken", refresh),
            )
        }
    }

    /** 令牌失效时只重试一次；网络故障不清空本地刷新令牌，下一次联网仍可恢复。 */
    fun renew(rejectedToken: String?, expectedGeneration: Long? = null): String? {
        val outcome = synchronized(renewLock) { renewLocked(rejectedToken, expectedGeneration) }
        if (outcome.notifyExpired && generation == outcome.expiredGeneration) onExpired?.invoke()
        return outcome.accessToken
    }

    /** 网络请求在 renewLock 下串行，但本地状态锁只包围快照和提交，退出不会被网络阻塞。 */
    private fun renewLocked(rejectedToken: String?, expectedGeneration: Long?): RenewOutcome {
        val snapshot = synchronized(lock) {
            if (expectedGeneration != null && generation != expectedGeneration) return@synchronized null
            RenewSnapshot(
                accessToken = preferences.get(KEY_ACCESS, null),
                expiresAt = preferences.getLong(KEY_EXPIRES, 0L),
                refreshToken = preferences.get(KEY_REFRESH, null)?.takeIf { it.isNotBlank() },
                generation = generation,
            )
        }
        if (snapshot == null) return RenewOutcome(null)
        if (!snapshot.accessToken.isNullOrBlank() && snapshot.accessToken != rejectedToken &&
            snapshot.expiresAt > System.currentTimeMillis() + 30_000L
        ) {
            return RenewOutcome(snapshot.accessToken)
        }

        val refresh = snapshot.refreshToken ?: return RenewOutcome(
            accessToken = null,
            notifyExpired = true,
            expiredGeneration = snapshot.generation,
        )
        val refreshResult = runCatching {
            // 关键点：HTTP 不在 lock 内执行，clearLocalSession() 可以立即完成。
            publicRequest("/api/v1/auth/refresh", "POST", JSONObject().put("refreshToken", refresh))
        }

        return synchronized(lock) {
            // 登录或退出已经切换了会话时，旧请求不能拿新账号的令牌重放。
            if (generation != snapshot.generation) return@synchronized RenewOutcome(null)
            // 同账号的另一轮刷新可能已经轮换了刷新令牌，此时复用最新访问令牌即可。
            if (preferences.get(KEY_REFRESH, null) != refresh) {
                return@synchronized RenewOutcome(preferences.get(KEY_ACCESS, null))
            }
            if (refreshResult.isFailure) {
                // 网络抖动或服务端 5xx 时保留刷新令牌，下次请求仍可恢复；只有明确的 401/403
                // 才代表凭据失效并清理会话。
                val error = refreshResult.exceptionOrNull()
                if (error?.message?.contains("HTTP 401") == true || error?.message?.contains("HTTP 403") == true) {
                    preferences.remove(KEY_ACCESS)
                    preferences.remove(KEY_REFRESH)
                    preferences.remove(KEY_EXPIRES)
                    preferences.remove(KEY_ACCOUNT)
                    preferences.flush()
                    generation++
                    return@synchronized RenewOutcome(null, notifyExpired = true, expiredGeneration = generation)
                }
                return@synchronized RenewOutcome(null)
            }
            val response = refreshResult.getOrNull() ?: return@synchronized RenewOutcome(null)
            val tokens = runCatching { response.toTokenPair() }.getOrNull()
                ?: return@synchronized RenewOutcome(null)
            RenewOutcome(saveUnlocked(tokens, preserveAccount = true, advanceGeneration = false).accessToken)
        }
    }

    private fun save(tokens: TokenPair, preserveAccount: Boolean, advanceGeneration: Boolean): TokenPair = synchronized(lock) {
        saveUnlocked(tokens, preserveAccount, advanceGeneration)
    }

    private fun saveUnlocked(tokens: TokenPair, preserveAccount: Boolean, advanceGeneration: Boolean): TokenPair {
        val account = tokens.userId?.takeIf { it > 0L }
            ?: decodeAccountId(tokens.accessToken)
            ?: if (preserveAccount) preferences.getLong(KEY_ACCOUNT, 0L).takeIf { it > 0L } else null
        preferences.put(KEY_ACCESS, tokens.accessToken)
        preferences.put(KEY_REFRESH, tokens.refreshToken)
        preferences.putLong(KEY_EXPIRES, System.currentTimeMillis() + tokens.expiresIn * 1_000L)
        if (account != null) preferences.putLong(KEY_ACCOUNT, account) else preferences.remove(KEY_ACCOUNT)
        preferences.flush()
        if (advanceGeneration) generation++
        return tokens
    }

    private fun publicPost(path: String, body: JSONObject): JSONObject = publicRequest(path, "POST", body)

    private fun publicRequest(path: String, method: String, body: JSONObject? = null): JSONObject {
        val builder = HttpRequest.newBuilder(URI.create(endpoint + path))
            .timeout(Duration.ofSeconds(30))
            .header("Accept", "application/json")
            .header("User-Agent", "TaotaoMusicWindows/1.0")
        if (body != null) {
            builder.header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(body.toString()))
        } else {
            builder.method(method, HttpRequest.BodyPublishers.noBody())
        }
        val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        val raw = response.body()
        if (response.statusCode() !in 200..299) throw DesktopApiException("HTTP ${response.statusCode()}: ${errorMessage(raw)}")
        val envelope = raw.takeIf { it.isNotBlank() }?.let(::JSONObject) ?: JSONObject()
        return envelope.optJSONObject("data") ?: envelope
    }

    private fun decodeAccountId(token: String?): Long? = runCatching {
        val encoded = token?.substringBefore('.')?.takeIf { it.isNotBlank() } ?: return null
        val json = JSONObject(String(Base64.getUrlDecoder().decode(encoded), Charsets.UTF_8))
        json.optLong("sub", 0L).takeIf { it > 0L }
    }.getOrNull()

    private fun JSONObject.toTokenPair(): TokenPair = TokenPair(
        accessToken = getString("accessToken"),
        refreshToken = getString("refreshToken"),
        expiresIn = optInt("expiresIn", 900),
        userId = optJSONObject("user")?.optLong("id", 0L)?.takeIf { it > 0L },
    )

    data class TokenPair(
        val accessToken: String,
        val refreshToken: String,
        val expiresIn: Int,
        val userId: Long? = null,
    )

    private data class TokenSnapshot(
        val accessToken: String?,
        val expiresAt: Long,
    )

    private data class RenewSnapshot(
        val accessToken: String?,
        val expiresAt: Long,
        val refreshToken: String?,
        val generation: Long,
    )

    private data class RenewOutcome(
        val accessToken: String?,
        val notifyExpired: Boolean = false,
        val expiredGeneration: Long = -1L,
    )

    companion object {
        private const val KEY_ACCESS = "access_token"
        private const val KEY_REFRESH = "refresh_token"
        private const val KEY_EXPIRES = "expires_at"
        private const val KEY_ACCOUNT = "account_id"
    }
}

open class DesktopApiException(message: String) : IllegalStateException(message)

class DesktopSessionExpiredException : DesktopApiException("登录状态已过期，请重新登录")

internal fun errorMessage(raw: String): String = runCatching {
    JSONObject(raw).optString("message").ifBlank { JSONObject(raw).optString("error") }
}.getOrNull().orEmpty().ifBlank { "请求失败" }
