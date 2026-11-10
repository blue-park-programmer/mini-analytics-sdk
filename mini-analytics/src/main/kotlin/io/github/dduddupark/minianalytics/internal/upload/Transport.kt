package io.github.dduddupark.minianalytics.internal.upload

import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * 배치 하나를 서버로 보낸다 (설계 §7.1). **블로킹**이므로 네트워크 dispatcher에서만 호출한다.
 * 네트워크 오류(연결 실패, 타임아웃 등)는 [IOException]으로 던진다. HTTP 응답은 상태 코드와 관계없이 돌려준다.
 */
internal interface Transport {
    @Throws(IOException::class)
    fun send(body: ByteArray): TransportResponse
}

/**
 * @property retryAfterMillis `Retry-After` 헤더 값(밀리초). 없거나 해석할 수 없으면 null.
 * @property body 응답 본문 (최대 [HttpUrlConnectionTransport.MAX_RESPONSE_CHARS]자). 읽지 못하면 null.
 */
internal data class TransportResponse(val code: Int, val retryAfterMillis: Long?, val body: String?)

/**
 * `HttpURLConnection` 구현 (설계 §7.1). 외부 HTTP 라이브러리를 쓰지 않는다.
 *
 * - HTTPS만 허용한다. `http`는 테스트에서 [allowInsecure]를 명시적으로 켠 경우에만 허용한다.
 * - 리다이렉트를 따라가지 않는다. POST 본문을 다른 곳으로 다시 보내지 않도록 하기 위해서이며,
 *   3xx는 "예상하지 못한 응답"으로 분류된다.
 */
internal class HttpUrlConnectionTransport(
    endpoint: String,
    private val apiKey: String,
    private val userAgent: String,
    allowInsecure: Boolean = false,
    private val connectTimeoutMillis: Int = 15_000,
    private val readTimeoutMillis: Int = 15_000,
    private val wallClock: () -> Long = System::currentTimeMillis,
) : Transport {

    private val url = URL(endpoint)

    init {
        val scheme = url.protocol.lowercase(Locale.US)
        require(scheme == "https" || (allowInsecure && scheme == "http")) { "endpoint must use https" }
    }

    override fun send(body: ByteArray): TransportResponse {
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.useCaches = false
            connection.instanceFollowRedirects = false
            connection.connectTimeout = connectTimeoutMillis
            connection.readTimeout = readTimeoutMillis
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Authorization", "Bearer $apiKey")
            connection.setRequestProperty("User-Agent", userAgent)
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { it.write(body) }

            val code = connection.responseCode
            val retryAfter = parseRetryAfter(connection.getHeaderField("Retry-After"), wallClock())
            val responseBody = readBody(if (code >= 400) connection.errorStream else connection.inputStream)
            return TransportResponse(code, retryAfter, responseBody)
        } finally {
            connection.disconnect()
        }
    }

    private fun readBody(stream: InputStream?): String? = try {
        stream?.bufferedReader(Charsets.UTF_8)?.use { reader ->
            val buffer = CharArray(MAX_RESPONSE_CHARS)
            var total = 0
            while (total < buffer.size) {
                val read = reader.read(buffer, total, buffer.size - total)
                if (read < 0) break
                total += read
            }
            String(buffer, 0, total)
        }
    } catch (e: IOException) {
        null // 본문은 부가 정보다. 읽지 못해도 상태 코드로 처리한다.
    }

    companion object {
        const val MAX_RESPONSE_CHARS: Int = 64 * 1024

        /**
         * `Retry-After`: 초 단위 정수 또는 HTTP-date(RFC 1123). 과거 시각이나 음수는 0으로 본다.
         * 해석할 수 없으면 null.
         */
        fun parseRetryAfter(value: String?, nowMillis: Long): Long? {
            val text = value?.trim()
            if (text.isNullOrEmpty()) return null
            text.toLongOrNull()?.let { seconds -> return (seconds.coerceAtLeast(0) * 1_000) }
            return try {
                val format = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("GMT")
                }
                (format.parse(text)!!.time - nowMillis).coerceAtLeast(0)
            } catch (e: ParseException) {
                null
            }
        }
    }
}
