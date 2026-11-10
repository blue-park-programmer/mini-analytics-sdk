package io.github.dduddupark.minianalytics

import io.github.dduddupark.minianalytics.internal.config.maskApiKey

/**
 * SDK 설정.
 *
 * `Builder`로 만든다. `build()`는 값을 검증하지 않고 예외도 던지지 않는다.
 * 검증은 `MiniAnalytics.initialize()`가 하며, 유효하지 않으면
 * [InitializeResult.INVALID_CONFIGURATION]을 반환한다.
 *
 * `apiKey`는 앱에 포함되는 공개 식별자이며 비밀 인증 수단이 아니다.
 *
 * Java 호출자가 `null`을 넘겨도 예외가 나지 않도록 문자열 파라미터는 nullable로 받는다.
 * `null`은 검증 단계에서 유효하지 않은 값으로 처리된다 (설계 §2.4).
 */
public class Configuration private constructor(
    internal val apiKey: String?,
    internal val endpoint: String?,
    internal val flushAt: Int,
    internal val flushIntervalMillis: Long,
    internal val maxQueuedEvents: Int,
    internal val trustInvalidEventResponses: Boolean,
) {
    public class Builder(
        private val apiKey: String?,
        private val endpoint: String?,
    ) {
        // 기본값은 리터럴로 둔다. const로 두면 Java에서 보이는 클래스나 필드가 생긴다.
        private var flushAt: Int = 20
        private var flushIntervalMillis: Long = 30_000L
        private var maxQueuedEvents: Int = 1_000
        private var trustInvalidEventResponses: Boolean = false

        /** 저장된 이벤트가 이 개수 이상이면 전송을 시작한다. 기본값 20. */
        public fun flushAt(value: Int): Builder = apply { flushAt = value }

        /** 미전송 이벤트를 확인하는 주기(밀리초). 기본값 30,000. */
        public fun flushIntervalMillis(value: Long): Builder = apply { flushIntervalMillis = value }

        /** 저장할 수 있는 track/screen 이벤트의 최대 개수. 기본값 1,000. */
        public fun maxQueuedEvents(value: Int): Builder = apply { maxQueuedEvents = value }

        /**
         * 서버의 `invalid_event` 응답을 믿고 그 이벤트를 삭제할지. **기본값 false.**
         *
         * - false: 400/422를 받아도 단건 격리와 삭제를 하지 않는다. 이벤트를 보존하고 5분 뒤 다시 시도한다.
         * - true: 수신 서버가 SDK 서버 계약(README "서버 계약" 3번)을 구현했다고 확인한 경우에만 켠다.
         *   이때 400/422를 받은 배치는 한 건씩 다시 보내고, 서버가 `invalid_event`로 지목한 이벤트만 삭제한다.
         */
        public fun trustInvalidEventResponses(value: Boolean): Builder = apply { trustInvalidEventResponses = value }

        public fun build(): Configuration =
            Configuration(apiKey, endpoint, flushAt, flushIntervalMillis, maxQueuedEvents, trustInvalidEventResponses)
    }

    /** 다른 설정과 다른 필드를 사람이 읽을 수 있는 형태로 돌려준다. `apiKey`는 마스킹한다. */
    internal fun describeDifferences(other: Configuration): List<String> {
        val differences = mutableListOf<String>()
        if (apiKey != other.apiKey) {
            differences += "apiKey: ${maskApiKey(apiKey)} -> ${maskApiKey(other.apiKey)}"
        }
        if (endpoint != other.endpoint) differences += "endpoint: $endpoint -> ${other.endpoint}"
        if (flushAt != other.flushAt) differences += "flushAt: $flushAt -> ${other.flushAt}"
        if (flushIntervalMillis != other.flushIntervalMillis) {
            differences += "flushIntervalMillis: $flushIntervalMillis -> ${other.flushIntervalMillis}"
        }
        if (maxQueuedEvents != other.maxQueuedEvents) {
            differences += "maxQueuedEvents: $maxQueuedEvents -> ${other.maxQueuedEvents}"
        }
        if (trustInvalidEventResponses != other.trustInvalidEventResponses) {
            differences += "trustInvalidEventResponses: $trustInvalidEventResponses -> ${other.trustInvalidEventResponses}"
        }
        return differences
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Configuration) return false
        return apiKey == other.apiKey &&
            endpoint == other.endpoint &&
            flushAt == other.flushAt &&
            flushIntervalMillis == other.flushIntervalMillis &&
            maxQueuedEvents == other.maxQueuedEvents &&
            trustInvalidEventResponses == other.trustInvalidEventResponses
    }

    override fun hashCode(): Int {
        var result = apiKey?.hashCode() ?: 0
        result = 31 * result + (endpoint?.hashCode() ?: 0)
        result = 31 * result + flushAt
        result = 31 * result + flushIntervalMillis.hashCode()
        result = 31 * result + maxQueuedEvents
        result = 31 * result + trustInvalidEventResponses.hashCode()
        return result
    }

    override fun toString(): String =
        "Configuration(apiKey=${maskApiKey(apiKey)}, endpoint=$endpoint, flushAt=$flushAt, " +
            "flushIntervalMillis=$flushIntervalMillis, maxQueuedEvents=$maxQueuedEvents, " +
            "trustInvalidEventResponses=$trustInvalidEventResponses)"
}
