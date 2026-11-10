package io.github.dduddupark.minianalytics.internal.serialization

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 밀리초 시각을 `yyyy-MM-dd'T'HH:mm:ss.SSS'Z'`(UTC) 문자열로 바꾼다.
 *
 * **스레드 안전하지 않다** (`SimpleDateFormat`). 인스턴스를 공유하지 말고,
 * 트랜잭션 하나 또는 요청 하나 단위로 새로 만들어 그 안에서만 쓴다 (설계 §4.3, §4.4, R-11).
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
