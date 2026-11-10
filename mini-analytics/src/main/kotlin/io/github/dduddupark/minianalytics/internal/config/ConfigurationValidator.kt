package io.github.dduddupark.minianalytics.internal.config

import io.github.dduddupark.minianalytics.Configuration
import java.net.URI
import java.net.URISyntaxException

/**
 * `initialize()`가 수행하는 동기 설정 검증 (설계 §2.1).
 *
 * 예외를 던지지 않고 위반 목록을 돌려준다. 목록이 비어 있으면 유효하다.
 */
internal object ConfigurationValidator {
    const val MIN_FLUSH_INTERVAL_MILLIS: Long = 1_000L
    const val MAX_QUEUED_EVENTS_UPPER_BOUND: Int = 10_000

    /**
     * @param allowInsecureEndpoint `http` endpoint를 허용할지. 테스트에서 명시적으로 켠 경우에만 true다 (설계 §7.1).
     */
    fun validate(configuration: Configuration, allowInsecureEndpoint: Boolean = false): List<String> {
        val errors = mutableListOf<String>()

        if (configuration.apiKey.isNullOrBlank()) errors += "apiKey must not be null or blank"

        validateEndpoint(configuration.endpoint, allowInsecureEndpoint)?.let { errors += it }

        if (configuration.maxQueuedEvents !in 1..MAX_QUEUED_EVENTS_UPPER_BOUND) {
            errors += "maxQueuedEvents must be in 1..$MAX_QUEUED_EVENTS_UPPER_BOUND " +
                "(was ${configuration.maxQueuedEvents})"
        }
        if (configuration.flushAt < 1 || configuration.flushAt > configuration.maxQueuedEvents) {
            errors += "flushAt must be in 1..maxQueuedEvents (was ${configuration.flushAt})"
        }
        if (configuration.flushIntervalMillis < MIN_FLUSH_INTERVAL_MILLIS) {
            errors += "flushIntervalMillis must be >= $MIN_FLUSH_INTERVAL_MILLIS " +
                "(was ${configuration.flushIntervalMillis})"
        }
        return errors
    }

    private fun validateEndpoint(endpoint: String?, allowInsecure: Boolean): String? {
        if (endpoint == null) return "endpoint must not be null"
        val uri = try {
            URI(endpoint)
        } catch (e: URISyntaxException) {
            return "endpoint is not a valid URI"
        }
        val scheme = uri.scheme?.lowercase()
        val schemeAllowed = scheme == "https" || (allowInsecure && scheme == "http")
        if (!schemeAllowed) {
            return if (scheme == "http") {
                "endpoint must use https (http is allowed only when explicitly enabled for tests)"
            } else {
                "endpoint must use https"
            }
        }
        if (uri.host.isNullOrEmpty()) return "endpoint must have a host"
        return null
    }
}
