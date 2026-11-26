package com.taotao.music.crypto

import com.taotao.music.data.SessionExpiredException
import com.taotao.music.data.TokenProvider
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CryptoTransportTest {
    @Test
    fun `启动时密钥请求短暂失败后自动恢复并保持加密`() {
        val fixture = Fixture(Reply.psk(503), Reply.psk(), Reply.handshake())

        val sealed = fixture.transport.seal("POST", PATH, PAYLOAD)

        assertEquals("v2:1", sealed.header)
        assertContentEquals("sealed:1".toByteArray(), sealed.frame)
        assertContentEquals(PAYLOAD, fixture.client.sealedBodies.single())
        assertEquals(listOf(300L), fixture.pauses)
        assertEquals(listOf(PSK_PATH, PSK_PATH, HANDSHAKE_PATH), fixture.paths)
        assertTrue(fixture.connections.all { it.disconnected })
    }

    @Test
    fun `握手被拒绝后释放半成品句柄并重新获取轮换后的密钥`() {
        val fixture = Fixture(
            Reply.psk(id = "old"), Reply.handshake(400),
            Reply.psk(id = "new"), Reply.handshake(),
        )

        assertEquals("v2:2", fixture.transport.seal("POST", PATH, PAYLOAD).header)
        assertEquals(listOf("old", "new"), fixture.client.createdPskIds)
        assertEquals(listOf(1L), fixture.client.freed)
        assertEquals(setOf(2L), fixture.client.live)
        assertEquals(1, fixture.client.sealedBodies.size)
        assertTrue(fixture.connections.all { it.disconnected })
    }

    @Test
    fun `会话失效后丢弃旧密钥而不是只重新握手`() {
        val fixture = Fixture(
            Reply.psk(id = "before-restart"), Reply.handshake(),
            Reply.psk(id = "after-restart"), Reply.handshake(),
        )
        fixture.transport.seal("GET", PATH, byteArrayOf())

        fixture.transport.invalidateSession()
        val sealed = fixture.transport.seal("GET", PATH, byteArrayOf())

        assertEquals("v2:2", sealed.header)
        assertEquals(listOf("before-restart", "after-restart"), fixture.client.createdPskIds)
        assertEquals(listOf(1L), fixture.client.freed)
        assertTrue(fixture.pauses.isEmpty())
    }

    @Test
    fun `连续失败停止请求并使用连接重试提示且下次调用仍可恢复`() {
        val fixture = Fixture(
            Reply.psk(), Reply.handshake(503), Reply.psk(), Reply.handshake(503),
            Reply.psk(), Reply.handshake(),
        )

        val error = assertFailsWith<CryptoConnectionException> {
            fixture.transport.seal("POST", PATH, PAYLOAD)
        }

        assertEquals("暂时无法连接服务，请稍后重试", error.message)
        assertTrue(error.cause is CryptoException)
        assertEquals(listOf(1L, 2L), fixture.client.freed)
        assertTrue(fixture.client.live.isEmpty())
        assertTrue(fixture.client.sealedBodies.isEmpty())
        assertEquals(4, fixture.connections.size)
        assertTrue(fixture.paths.all { it == PSK_PATH || it == HANDSHAKE_PATH })
        assertEquals("v2:3", fixture.transport.seal("POST", PATH, PAYLOAD).header)
    }

    @Test
    fun `未通过握手认证的句柄不能用于封帧`() {
        val fixture = Fixture(Reply.psk(), Reply.handshake(), Reply.psk(), Reply.handshake())
        fixture.client.finishAccepted = false

        assertFailsWith<CryptoConnectionException> { fixture.transport.seal("POST", PATH, PAYLOAD) }

        assertEquals(listOf(1L, 2L), fixture.client.freed)
        assertTrue(fixture.client.live.isEmpty())
        assertTrue(fixture.client.sealedBodies.isEmpty())
    }

    @Test
    fun `密钥请求的令牌被拒绝后续期一次并携带新令牌`() {
        val fixture = Fixture(Reply.psk(401), Reply.psk(), Reply.handshake())

        fixture.transport.seal("POST", PATH, PAYLOAD)

        assertEquals(listOf("access-old"), fixture.tokens.rejected)
        assertEquals("Bearer access-old", fixture.connections[0].getRequestProperty("Authorization"))
        assertEquals("Bearer access-new", fixture.connections[1].getRequestProperty("Authorization"))
        assertTrue(fixture.pauses.isEmpty())
    }

    @Test
    fun `登录确实失效时保留登录提示而不是掩盖为连接错误`() {
        val fixture = Fixture(Reply.psk(401))
        fixture.tokens.renewed = null

        assertFailsWith<SessionExpiredException> { fixture.transport.seal("POST", PATH, PAYLOAD) }

        assertEquals(1, fixture.connections.size)
        assertTrue(fixture.pauses.isEmpty())
        assertTrue(fixture.client.createdPskIds.isEmpty())
        assertTrue(fixture.connections.single().disconnected)
    }

    @Test
    fun `原生组件不可用时不发送明文请求`() {
        val fixture = Fixture()
        fixture.client.available = false

        assertFailsWith<CryptoConnectionException> { fixture.transport.seal("POST", PATH, PAYLOAD) }

        assertTrue(fixture.connections.isEmpty())
        assertTrue(fixture.client.sealedBodies.isEmpty())
    }

    @Test
    fun `成功后复用会话不重复下载密钥或握手`() {
        val fixture = Fixture(Reply.psk(), Reply.handshake())

        repeat(3) { fixture.transport.seal("POST", PATH, PAYLOAD) }

        assertEquals(listOf(PSK_PATH, HANDSHAKE_PATH), fixture.paths)
        assertEquals(3, fixture.client.sealedBodies.size)
        assertTrue(fixture.client.freed.isEmpty())
    }

    @Test
    fun `并发启动的多个请求共用一次握手`() {
        val fixture = Fixture(Reply.psk(), Reply.handshake())
        val executor = Executors.newFixedThreadPool(4)
        try {
            val tasks = (1..8).map { Callable { fixture.transport.seal("POST", PATH, PAYLOAD).header } }
            val headers = executor.invokeAll(tasks, 5, TimeUnit.SECONDS).map { it.get() }

            assertEquals(List(8) { "v2:1" }, headers)
            assertEquals(listOf(PSK_PATH, HANDSHAKE_PATH), fixture.paths)
            assertEquals(8, fixture.client.sealedBodies.size)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `等待重试被中断时停止并恢复线程中断标记`() {
        val fixture = Fixture(Reply.psk(503))
        fixture.interruptPause = true
        try {
            assertFailsWith<CryptoConnectionException> { fixture.transport.seal("POST", PATH, PAYLOAD) }
            assertTrue(Thread.currentThread().isInterrupted)
            assertEquals(1, fixture.connections.size)
            assertTrue(fixture.client.live.isEmpty())
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `握手时间戳在密钥下载后生成并在响应后更新`() {
        val fixture = Fixture(Reply.psk(), Reply.handshake())

        fixture.transport.seal("POST", PATH, PAYLOAD)

        assertEquals(listOf(10_000L), fixture.client.helloTimes)
        assertEquals(listOf(20_000L), fixture.client.finishTimes)
    }

    private class Fixture(vararg replies: Reply) {
        val client = FakeClient()
        val tokens = FakeTokens()
        val connections = mutableListOf<FakeConnection>()
        val paths get() = connections.map { it.url.path }
        val pauses = mutableListOf<Long>()
        var interruptPause = false
        private var time = 0L
        private val pending = ArrayDeque(replies.toList())
        val transport = CryptoTransport(
            endpoint = "https://example.test",
            deviceIdProvider = { "test-device" },
            tokenProvider = tokens,
            client = client,
            deviceInfo = "test-device-info",
            connectionFactory = { url ->
                val reply = pending.removeFirst()
                assertEquals(reply.path, url.path)
                FakeConnection(url, reply) { time += 10_000L }.also { connections += it }
            },
            now = { time },
            pause = { delay ->
                pauses += delay
                if (interruptPause) throw InterruptedException("测试中断")
            },
            reportFailure = { _, _ -> },
        )
    }

    private data class Reply(val path: String, val status: Int, val body: String) {
        companion object {
            fun psk(status: Int = 200, id: String = "test-psk") = Reply(
                PSK_PATH, status,
                """{"data":{"pskId":"$id","pskHex":"${"ab".repeat(32)}"}}""",
            )
            fun handshake(status: Int = 200) = Reply(
                HANDSHAKE_PATH, status, """{"data":{"serverHello":"AQID"}}""",
            )
        }
    }

    private class FakeConnection(url: URL, private val reply: Reply, private val onResponse: () -> Unit) :
        HttpURLConnection(url) {
        var disconnected = false
        private val output = ByteArrayOutputStream()
        override fun getResponseCode(): Int = reply.status.also { onResponse() }
        override fun getInputStream() = reply.body.byteInputStream()
        override fun getErrorStream() = "拒绝请求".byteInputStream()
        override fun getOutputStream() = output
        override fun disconnect() { disconnected = true }
        override fun usingProxy() = false
        override fun connect() = Unit
    }

    private class FakeTokens : TokenProvider {
        var renewed: String? = "access-new"
        val rejected = mutableListOf<String?>()
        override fun currentToken() = "access-old"
        override fun validToken() = currentToken()
        override fun renewToken(rejectedToken: String?): String? {
            rejected += rejectedToken
            return renewed
        }
    }

    /** 模拟有状态 JNI；未 finish、已释放、重复释放都直接使测试失败。 */
    private class FakeClient : CryptoClient {
        override var available = true
        var finishAccepted = true
        val live = mutableSetOf<Long>()
        private val ready = mutableSetOf<Long>()
        val freed = mutableListOf<Long>()
        val createdPskIds = mutableListOf<String>()
        val sealedBodies = mutableListOf<ByteArray>()
        val helloTimes = mutableListOf<Long>()
        val finishTimes = mutableListOf<Long>()
        override fun nativeVersion() = 2
        override fun nativeAad(method: String, path: String) = "$method $path".toByteArray()
        override fun clientNew(pskId: String, pskHex: String, deviceId: String): Long {
            assertEquals("test-device", deviceId)
            createdPskIds += pskId
            return createdPskIds.size.toLong().also { live += it }
        }
        override fun clientHandshake(handle: Long, nowMs: Long): ByteArray {
            assertTrue(handle in live)
            helloTimes += nowMs
            return "hello:$handle".toByteArray()
        }
        override fun clientFinish(handle: Long, serverHello: ByteArray, nowMs: Long): Boolean {
            assertTrue(handle in live)
            assertContentEquals(byteArrayOf(1, 2, 3), serverHello)
            finishTimes += nowMs
            if (finishAccepted) ready += handle
            return finishAccepted
        }
        override fun clientNeedsRekey(handle: Long, nowMs: Long) = handle !in ready
        override fun clientSeal(handle: Long, aad: ByteArray, body: ByteArray, nowMs: Long): ByteArray {
            assertTrue(handle in live && handle in ready)
            sealedBodies += body.copyOf()
            return "sealed:$handle".toByteArray()
        }
        override fun clientHeaderForFrame(handle: Long, frame: ByteArray) = "v2:$handle"
        override fun clientOpen(handle: Long, aad: ByteArray, frame: ByteArray, nowMs: Long): ByteArray =
            throw IOException("测试响应解密失败")
        override fun clientFree(handle: Long): Boolean {
            assertTrue(live.remove(handle))
            ready.remove(handle)
            assertFalse(handle in freed)
            freed += handle
            return true
        }
    }

    private companion object {
        const val PATH = "/api/v1/favorites"
        const val PSK_PATH = "/api/v1/crypto/psk"
        const val HANDSHAKE_PATH = "/api/v1/crypto/handshake"
        val PAYLOAD = "{\"songId\":42}".toByteArray()
    }
}
