package io.github.dduddupark.minianalytics.internal.upload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** 설계 §6.3의 응답 분류와 backoff. 순수 Kotlin이라 일반 JUnit으로 실행한다. */
class ResponsePolicyTest {

    @Test
    fun `responses are classified as in the design table`() {
        val expected = mapOf(
            null to ResponseOutcome.TRANSIENT, // 네트워크 오류
            200 to ResponseOutcome.SUCCESS, 202 to ResponseOutcome.SUCCESS, 299 to ResponseOutcome.SUCCESS,
            408 to ResponseOutcome.TRANSIENT, 429 to ResponseOutcome.TRANSIENT,
            500 to ResponseOutcome.TRANSIENT, 503 to ResponseOutcome.TRANSIENT, 599 to ResponseOutcome.TRANSIENT,
            413 to ResponseOutcome.PAYLOAD_TOO_LARGE,
            401 to ResponseOutcome.AUTH_ERROR, 403 to ResponseOutcome.AUTH_ERROR,
            400 to ResponseOutcome.INVALID_REQUEST, 422 to ResponseOutcome.INVALID_REQUEST,
            // 설계 표에 없는 응답: 임시로 보수적 처리 (승인 대기)
            100 to ResponseOutcome.UNEXPECTED, 302 to ResponseOutcome.UNEXPECTED,
            404 to ResponseOutcome.UNEXPECTED, 405 to ResponseOutcome.UNEXPECTED, 409 to ResponseOutcome.UNEXPECTED,
        )
        for ((code, outcome) in expected) assertEquals("code=$code", outcome, ResponseClassifier.classify(code))
    }

    @Test
    fun `backoff ceiling doubles from 1s and is capped at 5 minutes`() {
        val backoff = Backoff()
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L), (1..4).map { backoff.ceiling(it) })
        assertEquals(256_000L, backoff.ceiling(9))
        assertEquals(300_000L, backoff.ceiling(10))
        assertEquals(300_000L, backoff.ceiling(1_000)) // 오버플로 없음
    }

    @Test
    fun `full jitter stays within zero and the ceiling`() {
        val backoff = Backoff(random = Random(42))
        repeat(1_000) {
            val failures = it % 12 + 1
            val delay = backoff.delayFor(failures)
            assertTrue("failures=$failures delay=$delay", delay in 0..backoff.ceiling(failures))
        }
    }

    @Test
    fun `retry after takes precedence over jitter but is capped`() {
        val backoff = Backoff(random = MaxJitterRandom)
        assertEquals(10_000L, backoff.delayFor(1, retryAfterMillis = 10_000))
        assertEquals(300_000L, backoff.delayFor(1, retryAfterMillis = 3_600_000))
        assertEquals(0L, backoff.delayFor(5, retryAfterMillis = 0))
    }

    @Test
    fun `retry after header accepts seconds and http dates`() {
        val now = 1_000_000_000_000L // 2001-09-09T01:46:40Z
        assertEquals(120_000L, HttpUrlConnectionTransport.parseRetryAfter("120", now))
        assertEquals(0L, HttpUrlConnectionTransport.parseRetryAfter("-5", now))
        assertEquals(30_000L, HttpUrlConnectionTransport.parseRetryAfter("Sun, 09 Sep 2001 01:47:10 GMT", now))
        assertEquals(0L, HttpUrlConnectionTransport.parseRetryAfter("Sun, 09 Sep 2001 01:00:00 GMT", now))
        assertNull(HttpUrlConnectionTransport.parseRetryAfter("soon", now))
        assertNull(HttpUrlConnectionTransport.parseRetryAfter(null, now))
        assertNull(HttpUrlConnectionTransport.parseRetryAfter(" ", now))
    }
}
