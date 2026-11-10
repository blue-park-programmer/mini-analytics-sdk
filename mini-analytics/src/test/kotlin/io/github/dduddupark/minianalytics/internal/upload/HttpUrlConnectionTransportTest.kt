package io.github.dduddupark.minianalytics.internal.upload

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 실제 HTTP로 [HttpUrlConnectionTransport]를 검증한다 (MockWebServer, HTTP 계약 테스트 전용).
 * MockWebServer는 http를 쓰므로 테스트에서만 `allowInsecure = true`를 명시적으로 켠다 (설계 §7.1).
 */
class HttpUrlConnectionTransportTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun transport(readTimeoutMillis: Int = 5_000) = HttpUrlConnectionTransport(
        endpoint = server.url("/v1/batch").toString(),
        apiKey = "pk_test",
        userAgent = "mini-analytics-android/test",
        allowInsecure = true,
        connectTimeoutMillis = 2_000,
        readTimeoutMillis = readTimeoutMillis,
    )

    @Test
    fun `sends a POST with the documented headers and the exact body`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        val body = "{\"batch\":[]}".toByteArray()

        val response = transport().send(body)

        assertEquals(200, response.code)
        assertEquals("{}", response.body)
        val request = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("POST", request.method)
        assertEquals("/v1/batch", request.path)
        assertEquals("application/json; charset=utf-8", request.getHeader("Content-Type"))
        assertEquals("Bearer pk_test", request.getHeader("Authorization"))
        assertEquals("mini-analytics-android/test", request.getHeader("User-Agent"))
        assertArrayEquals(body, request.body.readByteArray())
    }

    @Test
    fun `error status codes are returned with their body instead of throwing`() {
        for (code in listOf(400, 401, 403, 413, 429, 500, 503)) {
            server.enqueue(MockResponse().setResponseCode(code).setBody("err-$code"))
            val response = transport().send(ByteArray(1))
            assertEquals(code, response.code)
            // JDK HttpURLConnection은 고정 길이 스트리밍 모드에서 401의 error stream을 주지 않는다.
            // 본문은 부가 정보이므로 상태 코드만 보장한다.
            if (code != 401) assertEquals("err-$code", response.body)
        }
    }

    @Test
    fun `retry after header is parsed`() {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "7"))
        assertEquals(7_000L, transport().send(ByteArray(1)).retryAfterMillis)
        server.enqueue(MockResponse().setResponseCode(503))
        assertNull(transport().send(ByteArray(1)).retryAfterMillis)
    }

    @Test
    fun `redirects are not followed for any redirect status`() {
        // 307/308은 따라가면 POST 본문까지 다시 보내는 코드다. 어떤 경우에도 Location으로 요청하지 않아야 한다.
        for (code in listOf(301, 302, 303, 307, 308)) {
            server.enqueue(MockResponse().setResponseCode(code).setHeader("Location", server.url("/redirected")))
            server.enqueue(MockResponse().setResponseCode(200)) // 따라갔다면 이 응답을 받게 된다
            val before = server.requestCount

            assertEquals("code=$code", code, transport().send("{}".toByteArray()).code)
            assertEquals("code=$code", before + 1, server.requestCount)
            assertEquals("/v1/batch", server.takeRequest(1, TimeUnit.SECONDS)!!.path)
            // 사용하지 않은 200 응답을 비운다
            server.takeRequest(10, TimeUnit.MILLISECONDS)
            server.shutdown()
            server = MockWebServer().apply { start() }
        }
    }

    @Test
    fun `connection failure is an IOException`() {
        val transport = transport()
        server.shutdown()
        expectIOException { transport.send(ByteArray(1)) }
    }

    @Test
    fun `read timeout is an IOException`() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        expectIOException { transport(readTimeoutMillis = 200).send(ByteArray(1)) }
    }

    @Test
    fun `http endpoint is rejected unless explicitly allowed`() {
        try {
            HttpUrlConnectionTransport("http://example.com", "k", "ua")
            fail("http must be rejected")
        } catch (expected: IllegalArgumentException) {
        }
        HttpUrlConnectionTransport("https://example.com", "k", "ua") // https는 허용
    }

    private fun expectIOException(block: () -> Unit) {
        try {
            block()
            fail("expected IOException")
        } catch (expected: IOException) {
        }
    }
}
