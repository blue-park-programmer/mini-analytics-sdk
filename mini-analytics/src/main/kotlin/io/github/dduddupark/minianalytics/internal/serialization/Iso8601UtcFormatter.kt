package io.github.dduddupark.minianalytics.internal.serialization

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 밀리초 시각을 `yyyy-MM-dd'T'HH:mm:ss.SSS'Z'`(UTC) 문자열로 바꾼다.
 *
 * **스레드 안전하지 않다** (`SimpleDateFormat`). 한 인스턴스를 동시에 쓰지 않는다 (R-11).
 * - 저장: storage coroutine 전용 인스턴스 하나를 만들어 계속 쓴다 (설계 §4.3, R-11 개정, Step 8).
 * - 업로드: `sentAt`용으로 요청마다 새로 만든다 (설계 §4.4).
 *
 * `java.time`은 API 26 이상이고 library desugaring을 쓰지 않기로 했으므로 사용하지 않는다 (설계 §10).
 */
internal class Iso8601UtcFormatter {
    private val format = SimpleDateFormat(PATTERN, Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
        isLenient = false
    }

    fun format(epochMillis: Long): String = format.format(Date(epochMillis))
}

private const val PATTERN = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"
