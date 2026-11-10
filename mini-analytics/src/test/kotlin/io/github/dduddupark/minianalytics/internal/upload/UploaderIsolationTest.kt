@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.dduddupark.minianalytics.internal.upload

import io.github.dduddupark.minianalytics.internal.pipeline.Counters
import io.github.dduddupark.minianalytics.internal.pipeline.RecordingLogger
import io.github.dduddupark.minianalytics.internal.storage.FaultyEventStore
import io.github.dduddupark.minianalytics.internal.storage.InMemoryEventStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 400/422 단건 격리 (설계 §6.4, R-8'', 6단계 검증 조건).
 *
 * 서버 응답은 [FakeTransport.responder]로 요청 내용(messageId 목록)을 보고 정한다.
 * - [batchStrictServer]: 계약을 따르는 서버. 잘못된 이벤트가 섞인 **여러 건** 요청에는 400(코드 없음),
 *   잘못된 이벤트 **한 건** 요청에는 `invalid_event` + messageId를 담은 400을 돌려준다.
 */
@RunWith(RobolectricTestRunner::class)
class UploaderIsolationTest {

    private val delegate = InMemoryEventStore()
    private val store = FaultyEventStore(delegate)
    private val counters = Counters()
    private val logger = RecordingLogger()

    private fun TestScope.transport() = FakeTransport { testScheduler.currentTime }

    private fun TestScope.uploader(transport: FakeTransport, flushAt: Int = 1, trust: Boolean = true): Uploader {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        backgroundScope.coroutineContext.job.invokeOnCompletion { scope.cancel() }
        return Uploader(
            store = store, storageDispatcher = dispatcher, networkDispatcher = dispatcher, scope = scope,
            transport = transport, envelope = EnvelopeBuilder({ "{}" }, { 0L }),
            flushAt = flushAt, flushIntervalMillis = 30_000, counters = counters, logger = logger,
            monotonicMillis = { testScheduler.currentTime }, backoff = Backoff(random = MaxJitterRandom),
            trustInvalidEventResponses = trust,
        )
    }

    private fun invalidEventBody(messageId: String) =
        "{\"error\":{\"code\":\"invalid_event\",\"messageId\":\"$messageId\"}}"

    /** 계약을 따르는 서버: [bad]에 속한 이벤트가 문제다. */
    private fun batchStrictServer(bad: Set<String>, code: Int = 400): (List<String>) -> TransportResponse = { ids ->
        val badIds = ids.filter { it in bad }
        when {
            badIds.isEmpty() -> TransportResponse(200, null, "{}")
            ids.size > 1 -> TransportResponse(code, null, "{\"error\":{\"code\":\"invalid_request\"}}")
            else -> TransportResponse(code, null, invalidEventBody(ids.single()))
        }
    }

    // ---------- 정상 이벤트와 잘못된 이벤트가 섞인 배치 ----------

    @Test
    fun `mixed batch - only the proven invalid event is dropped and all valid events are delivered`() = runTest {
        delegate.addEvents("e", 5)
        val transport = transport()
        transport.responder = batchStrictServer(bad = setOf("e-3"))
        uploader(transport)
        runCurrent()

        assertEquals(listOf(5, 1, 1, 1, 1, 1), transport.batchSizes())
        assertEquals(listOf("e-1", "e-2", "e-3", "e-4", "e-5"), (1..5).map { transport.messageIds(it).single() })
        assertEquals(0, delegate.counts().total)
        assertEquals(1L, counters.rejectedByServer.get())
    }

    @Test
    fun `422 is handled like 400`() = runTest {
        delegate.addEvents("e", 3)
        val transport = transport()
        transport.responder = batchStrictServer(bad = setOf("e-2"), code = 422)
        uploader(transport)
        runCurrent()
        assertEquals(listOf(3, 1, 1, 1), transport.batchSizes())
        assertEquals(1L, counters.rejectedByServer.get())
    }

    @Test
    fun `a batch 400 alone never deletes anything`() = runTest {
        delegate.addEvents("e", 4)
        val transport = transport()
        transport.respond(400, body = invalidEventBody("e-1")) // 여러 건 요청에 대한 응답은 근거가 되지 않는다
        transport.fallback = { TransportResponse(503, null, null) } // 이후 단건은 일시적 실패
        val uploader = uploader(transport)
        runCurrent()

        assertEquals(4, delegate.counts().total)
        assertEquals(0L, counters.rejectedByServer.get())
        assertTrue(uploader.isIsolating)
    }

    @Test
    fun `events added during isolation are sent as a normal batch afterwards`() = runTest {
        delegate.addEvents("e", 3)
        val transport = transport()
        transport.responder = batchStrictServer(bad = setOf("e-2"))
        transport.onSend = {
            if (transport.requests.size == 2) delegate.addEvents("late", 4) // 격리 중에 저장된 이벤트
        }
        val uploader = uploader(transport)
        runCurrent()

        assertEquals(listOf(3, 1, 1, 1, 4), transport.batchSizes())
        assertFalse(uploader.isIsolating)
        assertEquals(0, delegate.counts().total)
    }

    @Test
    fun `a single-event batch with a proven invalid event is dropped directly`() = runTest {
        delegate.addEvents("e", 1)
        val transport = transport()
        transport.responder = batchStrictServer(bad = setOf("e-1"))
        uploader(transport)
        runCurrent()
        assertEquals(listOf(1), transport.batchSizes())
        assertEquals(0, delegate.counts().total)
    }

    // ---------- 근거가 없는 400: 서버/요청/계약 문제로 보고 보존 ----------

    @Test
    fun `single 400 without the invalid_event contract keeps every event and backs off`() = runTest {
        for ((name, body) in listOf(
            "no body" to null,
            "html" to "<html>Bad Request</html>",
            "other code" to "{\"error\":{\"code\":\"invalid_request\",\"messageId\":\"e-1\"}}",
            "wrong messageId" to invalidEventBody("someone-else"),
            "no messageId" to "{\"error\":{\"code\":\"invalid_event\"}}",
        )) {
            val delegate = InMemoryEventStore()
            delegate.addEvents("e", 3)
            val localCounters = Counters()
            val transport = transport()
            transport.respond(400) // 배치
            transport.respond(400, body = body) // 첫 단건
            val dispatcher = StandardTestDispatcher(testScheduler)
            val scope = CoroutineScope(SupervisorJob() + dispatcher)
            // assertion이 실패해도 무한 ticker가 남지 않도록 테스트 종료 시 반드시 취소한다.
            backgroundScope.coroutineContext.job.invokeOnCompletion { scope.cancel() }
            try {
                val uploader = Uploader(
                    delegate, dispatcher, dispatcher, scope, transport, EnvelopeBuilder({ "{}" }, { 0L }),
                    1, 30_000, localCounters, RecordingLogger(), { testScheduler.currentTime },
                    Backoff(random = MaxJitterRandom), trustInvalidEventResponses = true,
                )
                runCurrent()

                assertEquals(name, 3, delegate.counts().total)
                assertEquals(name, 0L, localCounters.rejectedByServer.get())
                assertEquals(name, 2, transport.requests.size) // 배치 1 + 단건 1에서 멈춤
                assertFalse(name, uploader.isIsolating) // 격리를 끝내고 최대 backoff
            } finally {
                scope.cancel()
            }
        }
    }

    @Test
    fun `a server that rejects everything without the contract loses nothing and sends few requests`() = runTest {
        delegate.addEvents("e", 100)
        val transport = transport()
        transport.fallback = { TransportResponse(400, null, "<html>Bad Request</html>") }
        uploader(transport)
        advanceTimeBy(60 * 60 * 1_000L) // 1시간

        assertEquals(100, delegate.counts().total)
        // 5분마다 (배치 1 + 단건 1) = 시간당 약 24번. 단건을 100번 보내지 않는다.
        assertTrue("requests=${transport.requests.size}", transport.requests.size <= 26)
        assertTrue("log count=${logger.errors.size}", logger.errors.size <= 2) // 반복 로그 억제
    }

    @Test
    fun `auth errors during isolation keep the events and the isolation state`() = runTest {
        delegate.addEvents("e", 3)
        val transport = transport()
        transport.respond(400) // 배치 → 격리
        transport.respond(401) // 첫 단건에서 인증 오류
        transport.responder = batchStrictServer(bad = emptySet())
        val uploader = uploader(transport)
        runCurrent()
        assertEquals(3, delegate.counts().total)
        assertTrue(uploader.isIsolating)

        advanceTimeBy(300_001) // 최대 backoff 후 격리를 이어서 진행
        assertEquals(listOf(3, 1, 1, 1, 1), transport.batchSizes())
        assertEquals(0, delegate.counts().total)
    }

    @Test
    fun `transient failures during isolation keep the isolation state and back off`() = runTest {
        delegate.addEvents("e", 3)
        val transport = transport()
        transport.respond(400) // 배치 → 격리
        transport.respond(503) // 단건 일시적 실패
        transport.failWithNetworkError()
        transport.respond(429, retryAfterMillis = 10_000)
        transport.responder = batchStrictServer(bad = setOf("e-2"))
        val uploader = uploader(transport)
        runCurrent()
        assertTrue(uploader.isIsolating)

        advanceTimeBy(20_000)
        // 0초: 배치, 0초: e-1(503), 1초: e-1(네트워크), 3초: e-1(429), 13초: e-1, e-2, e-3
        assertEquals(listOf(0L, 0L, 1_000L, 3_000L, 13_000L, 13_000L, 13_000L), transport.sendTimes)
        assertEquals(listOf(3, 1, 1, 1, 1, 1, 1), transport.batchSizes())
        assertFalse(uploader.isIsolating)
        assertEquals(1L, counters.rejectedByServer.get())
    }

    // ---------- 요청 수와 삭제 수의 상한 ----------

    // ---------- P7-2b: 격리 한 번의 단건 요청 상한 (20) ----------

    @Test
    fun `a 100-event isolation is split into episodes of at most 20 single requests`() = runTest {
        delegate.addEvents("e", 100)
        val transport = transport()
        transport.responder = batchStrictServer(bad = setOf("e-50"))
        val uploader = uploader(transport)
        runCurrent()

        // 1회차: 배치 1번 + 단건 20번에서 멈추고, 남은 80건은 보존한다.
        assertEquals(21, transport.requests.size)
        assertEquals(80, delegate.counts().total)
        assertFalse(uploader.isIsolating)
        assertTrue(logger.errors.any { it.contains("after 20 requests") })

        // 다음 시도는 최대 backoff(5분) 뒤. 그 전에는 요청이 없다.
        advanceTimeBy(299_999)
        assertEquals(21, transport.requests.size)

        advanceTimeBy(5 * 300_000L)
        assertEquals(0, delegate.counts().total) // 결국 정상 99건 전달, 잘못된 1건 삭제
        assertEquals(1L, counters.rejectedByServer.get())
        // 회차마다 (배치 1번 + 단건 최대 20번)을 넘지 않는다
        val starts = transport.batchSizes().withIndex().filter { it.value > 1 }.map { it.index } + transport.requests.size
        assertTrue(starts.zipWithNext().all { (a, b) -> b - a <= 21 })
    }

    @Test
    fun `transient retries during isolation count toward the cap and nothing is lost`() = runTest {
        delegate.addEvents("e", 25)
        val transport = transport()
        transport.respond(400) // 배치 → 격리
        transport.responder = { TransportResponse(503, null, null) } // 이후 단건은 계속 503
        val uploader = uploader(transport)
        // 단건 20번의 backoff 합계: 1+2+…+256초(511초) + 11 × 300초 ≈ 64분. 그 뒤 상한 확인.
        advanceTimeBy(2 * 60 * 60 * 1_000L)

        // 격리 요청 20번(재시도 포함) 뒤 멈추고, 이후 5분마다 배치로만 다시 시도한다.
        val singles = transport.batchSizes().count { it == 1 }
        assertEquals(20, singles)
        assertEquals(25, delegate.counts().total)
        assertFalse(uploader.isIsolating)
        // 503 backoff는 그대로 지켜졌다 (1초, 2초, 4초 …)
        assertEquals(listOf(0L, 0L, 1_000L, 3_000L), transport.sendTimes.take(4))
    }

    @Test
    fun `at most 20 events per process are deleted through the 400 path, the rest are kept`() = runTest {
        delegate.addEvents("e", 30)
        val transport = transport()
        // 버그가 있는 서버: 모든 단건에 invalid_event를 붙인다
        transport.responder = { ids ->
            if (ids.size > 1) TransportResponse(400, null, null) else TransportResponse(400, null, invalidEventBody(ids.single()))
        }
        val uploader = uploader(transport)
        runCurrent()

        assertEquals(Uploader.MAX_REJECTION_DELETES_PER_PROCESS.toLong(), counters.rejectedByServer.get())
        assertEquals(10, delegate.counts().total) // 30 - 20: 보류됨
        assertEquals(1 + 20, transport.requests.size) // 격리 요청 상한(20)에서 멈춤
        assertFalse(uploader.isIsolating)

        advanceTimeBy(300_001) // 다음 회차: 배치 → 단건 1번에서 삭제 상한 때문에 서버 문제로 판단
        assertEquals(1 + 20 + 2, transport.requests.size)
        assertTrue(logger.errors.any { it.contains("per-process limit") })
        assertEquals(10, delegate.counts().total) // 더 지우지 않는다
    }

    @Test
    fun `isolation state is memory only - a restart isolates again without resending delivered events`() = runTest {
        delegate.addEvents("e", 4)
        val first = transport()
        first.respond(400) // 배치
        first.respond(200) // e-1
        first.respond(200) // e-2
        first.fallback = { TransportResponse(503, null, null) } // e-3에서 일시적 실패 → 종료
        uploader(first).also { runCurrent() }.stop()
        assertEquals(2, delegate.counts().total)

        val second = transport()
        second.responder = batchStrictServer(bad = setOf("e-4"))
        uploader(second)
        runCurrent()
        assertEquals(listOf(listOf("e-3", "e-4"), listOf("e-3"), listOf("e-4")), second.requests.indices.map { second.messageIds(it) })
        assertEquals(0, delegate.counts().total)
    }

    @Test
    fun `isolation ends when the isolated events are evicted meanwhile`() = runTest {
        delegate.addEvents("e", 3)
        val transport = transport()
        transport.respond(400) // 배치 → 격리
        transport.onSend = {
            if (transport.requests.size == 1) {
                // 격리 대상이 상한 정리로 지워지고 새 이벤트가 들어온 상황
                delegate.deleteByIds(delegate.readBatch(10, Int.MAX_VALUE).map { it.id })
                delegate.addEvents("new", 2)
            }
        }
        val uploader = uploader(transport)
        runCurrent()
        assertEquals(listOf(3, 2), transport.batchSizes())
        assertFalse(uploader.isIsolating)
    }

    // ---------- P7-1: trustInvalidEventResponses = false (기본값) ----------

    @Test
    fun `untrusted - a batch 400 is neither isolated nor deleted and is retried after 5 minutes`() = runTest {
        delegate.addEvents("e", 5)
        val transport = transport()
        transport.responder = batchStrictServer(bad = setOf("e-3"))
        val uploader = uploader(transport, trust = false)
        runCurrent()

        assertEquals(listOf(5), transport.batchSizes()) // 단건 요청 없음
        assertFalse(uploader.isIsolating)
        assertEquals(5, delegate.counts().total)
        assertTrue(logger.errors.single().contains("not trusted"))

        advanceTimeBy(300_001)
        assertEquals(listOf(5, 5), transport.batchSizes())
        assertEquals(0L, counters.rejectedByServer.get())
    }

    @Test
    fun `untrusted - even a proven invalid_event for a single event is not deleted`() = runTest {
        delegate.addEvents("e", 1)
        val transport = transport()
        transport.responder = batchStrictServer(bad = setOf("e-1"))
        uploader(transport, trust = false)
        runCurrent()
        assertEquals(1, delegate.counts().total)
        assertEquals(0L, counters.rejectedByServer.get())
    }
}
