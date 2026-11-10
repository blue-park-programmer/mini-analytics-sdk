package io.github.dduddupark.minianalytics.internal.upload

import kotlin.random.Random

/** 응답 분류 (설계 §6.3). */
internal enum class ResponseOutcome {
    /** 2xx: 배치 전체 삭제. */
    SUCCESS,

    /** 네트워크 오류, 408, 429, 5xx: 유지하고 지수 backoff 후 재시도. */
    TRANSIENT,

    /** 413: 유지하고 배치 크기를 절반으로. 1건이면 그 이벤트 삭제. */
    PAYLOAD_TOO_LARGE,

    /** 401, 403: 유지하고 최대 backoff, `Log.e`. */
    AUTH_ERROR,

    /** 400, 422: 단건 격리 (설계 §6.4, R-8''). */
    INVALID_REQUEST,

    /**
     * 설계 §6.3 표에 없는 응답 (1xx, 3xx, 그 밖의 4xx 등, 5단계 D5-1 승인).
     * 유지하고 최대 backoff, `Log.e`. 아무것도 삭제하지 않는다.
     */
    UNEXPECTED,
}

internal object ResponseClassifier {
    /** @param code HTTP 상태 코드. 네트워크 오류로 응답이 없으면 null. */
    fun classify(code: Int?): ResponseOutcome = when (code) {
        null -> ResponseOutcome.TRANSIENT
        in 200..299 -> ResponseOutcome.SUCCESS
        408, 429, in 500..599 -> ResponseOutcome.TRANSIENT
        413 -> ResponseOutcome.PAYLOAD_TOO_LARGE
        401, 403 -> ResponseOutcome.AUTH_ERROR
        400, 422 -> ResponseOutcome.INVALID_REQUEST
        else -> ResponseOutcome.UNEXPECTED
    }
}

/**
 * 지수 backoff + full jitter (설계 §6.3, Q23).
 * n번째 연속 실패(1부터) 후 대기 시간은 `[0, min(max, base × 2^(n-1))]`에서 균등하게 고른다.
 * `Retry-After`가 있으면 jitter 대신 그 값을 쓰되 [maxMillis]를 넘지 않게 한다.
 */
internal class Backoff(
    private val baseMillis: Long = 1_000L,
    val maxMillis: Long = 300_000L,
    private val random: Random = Random.Default,
) {
    fun delayFor(consecutiveFailures: Int, retryAfterMillis: Long? = null): Long {
        if (retryAfterMillis != null) return retryAfterMillis.coerceIn(0, maxMillis)
        return random.nextLong(0, ceiling(consecutiveFailures) + 1)
    }

    fun ceiling(consecutiveFailures: Int): Long {
        val exponent = (consecutiveFailures - 1).coerceIn(0, 30)
        val exponential = baseMillis shl exponent
        return if (exponential <= 0 || exponential > maxMillis) maxMillis else exponential
    }
}
