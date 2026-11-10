@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.dduddupark.minianalytics.internal.upload

import io.github.dduddupark.minianalytics.internal.pipeline.Counters
import io.github.dduddupark.minianalytics.internal.pipeline.RecordingLogger
import io.github.dduddupark.minianalytics.internal.storage.EventStore
import io.github.dduddupark.minianalytics.internal.storage.FaultyEventStore
import io.github.dduddupark.minianalytics.internal.storage.InMemoryEventStore
import io.github.dduddupark.minianalytics.internal.storage.SqliteEventStore
import io.github.dduddupark.minianalytics.internal.storage.StoreCounts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * 전송 파이프라인 (설계 §4.4, §6, 5단계 검증 항목 1, 2, 5).
 * 가상 시간 + 결정적 jitter(항상 최댓값)로 시도 시각을 정확히 검사한다.
 * `advanceUntilIdle()`은 무한히 반복되는 ticker 때문에 끝나지 않으므로, 시간을 명시적으로 진행한다.
 */
@RunWith(RobolectricTestRunner::class)
class UploaderTest {

    private val delegate = InMemoryEventStore()
    private val store = FaultyEventStore(delegate)
    private val counters = Counters()
    private val logger = RecordingLogger()

    private fun TestScope.uploader(
        store: EventStore = this@UploaderTest.store,
        transport: FakeTransport,
        flushAt: Int = 20,
        flushIntervalMillis: Long = 30_000,
    ): Uploader {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        backgroundScope.coroutineContext.job.invokeOnCompletion { scope.cancel() }
        return Uploader(
            store = store,
            storageDispatcher = dispatcher,
            networkDispatcher = dispatcher,
            scope = scope,
            transport = transport,
            envelope = EnvelopeBuilder({ "{}" }, { 0L }),
            flushAt = flushAt,
            flushIntervalMillis = flushIntervalMillis,
            counters = counters,
            logger = logger,
            monotonicMillis = { testScheduler.currentTime },
            backoff = Backoff(random = MaxJitterRandom),
        )
    }

    private fun TestScope.transport() = FakeTransport { testScheduler.currentTime }

    // ---------- 1. 서버 수신이 확인된 messageId만 삭제 ----------

    @Test
    fun `only the acknowledged batch is deleted - events stored during the request remain`() = runTest {
        delegate.addEvents("old", 3)
        val transport = transport()
        transport.onSend = { delegate.addEvents("new", 2) } // 요청이 진행되는 동안 track()이 저장됨
        uploader(transport = transport, flushAt = 10)
        runCurrent()

        assertEquals(listOf("old-1", "old-2", "old-3"), transport.messageIds(0))
        assertEquals(listOf("new-1", "new-2"), delegate.storedMessageIds())
    }

    @Test
    fun `failed upload deletes nothing and retries the same messageIds`() = runTest {
        delegate.addEvents("e", 3)
        val transport = transport()
        transport.respond(500)
        uploader(transport = transport)
        runCurrent()
        assertEquals(listOf("e-1", "e-2", "e-3"), delegate.storedMessageIds())

        advanceTimeBy(1_001)
        assertEquals(2, transport.requests.size)
        assertEquals(transport.messageIds(0), transport.messageIds(1))
        assertTrue(delegate.storedMessageIds().isEmpty())
    }

    // ---------- 2. 성공 후 삭제 전 종료 → 같은 messageId로 재전송 ----------

    @Test
    fun `local delete failure after 2xx resends the same messageIds after backoff`() = runTest {
        delegate.addEvents("e", 2)
        store.failNextDeletes = 1
        val transport = transport()
        uploader(transport = transport)
        runCurrent()
        assertEquals(1, transport.requests.size)
        assertEquals(2, delegate.counts().total) // 서버는 받았지만 로컬에는 남아 있음

        advanceTimeBy(500)
        assertEquals(1, transport.requests.size) // 곧바로 다시 보내지 않는다 (backoff)
        advanceTimeBy(501)
        assertEquals(transport.messageIds(0), transport.messageIds(1))
        assertEquals(0, delegate.counts().total)
    }

    @Test
    fun `process restart between server ack and local delete resends identical messageIds`() = runTest {
        val context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("restart.db")
        val firstRun = FaultyEventStore(SqliteEventStore(context, "restart.db"))
        firstRun.addEvents("e", 3)
        firstRun.failNextDeletes = 1 // "서버 수신 → 로컬 삭제" 사이에 프로세스가 죽은 상황
        val firstTransport = transport()
        uploader(store = firstRun, transport = firstTransport).also { runCurrent() }.stop()
        firstRun.close()

        // 다음 실행: 같은 DB 파일을 다시 연다
        val secondRun = SqliteEventStore(context, "restart.db")
        val secondTransport = transport()
        uploader(store = secondRun, transport = secondTransport)
        runCurrent()

        assertEquals(listOf("e-1", "e-2", "e-3"), firstTransport.messageIds(0))
        assertEquals(firstTransport.messageIds(0), secondTransport.messageIds(0))
        assertEquals(0, secondRun.counts().total)
        secondRun.close()
    }

    // ---------- 5. 재시도 정책 ----------

    @Test
    fun `transient failures back off exponentially - 1s 2s 4s`() = runTest {
        delegate.addEvents("e", 1)
        val transport = transport()
        repeat(3) { transport.respond(503) }
        uploader(transport = transport)
        advanceTimeBy(10_000)
        assertEquals(listOf(0L, 1_000L, 3_000L, 7_000L), transport.sendTimes)
    }

    @Test
    fun `network errors are retried like transient failures`() = runTest {
        delegate.addEvents("e", 1)
        val transport = transport()
        transport.failWithNetworkError()
        transport.respond(408)
        uploader(transport = transport)
        advanceTimeBy(5_000)
        assertEquals(listOf(0L, 1_000L, 3_000L), transport.sendTimes)
        assertEquals(0, delegate.counts().total)
    }

    @Test
    fun `429 retry after is honoured and capped at 5 minutes`() = runTest {
        delegate.addEvents("e", 1)
        val transport = transport()
        transport.respond(429, retryAfterMillis = 10_000)
        transport.respond(429, retryAfterMillis = 3_600_000)
        uploader(transport = transport)
        advanceTimeBy(400_000)
        assertEquals(listOf(0L, 10_000L, 310_000L), transport.sendTimes)
    }

    @Test
    fun `triggers during backoff do not bring the next attempt forward`() = runTest {
        delegate.addEvents("e", 1)
        val transport = transport()
        transport.respond(503)
        val uploader = uploader(transport = transport, flushAt = 1, flushIntervalMillis = 100)
        runCurrent()
        repeat(50) { uploader.requestFlush() }
        uploader.onStoreCounts(StoreCounts(events = 1_000, identifies = 0))
        advanceTimeBy(999) // ticker도 9번 지나감
        assertEquals(1, transport.requests.size)
        advanceTimeBy(2)
        assertEquals(2, transport.requests.size)
    }

    @Test
    fun `auth errors keep events and wait the maximum backoff`() = runTest {
        delegate.addEvents("e", 2)
        val transport = transport()
        transport.respond(401)
        transport.respond(403)
        uploader(transport = transport)
        advanceTimeBy(299_999)
        assertEquals(1, transport.requests.size)
        assertEquals(2, delegate.counts().total)
        advanceTimeBy(2)
        assertEquals(listOf(0L, 300_000L), transport.sendTimes)
        assertTrue(logger.errors.any { it.contains("apiKey") })
    }

    @Test
    fun `unexpected codes keep events and wait the maximum backoff`() = runTest {
        delegate.addEvents("e", 2)
        val transport = transport()
        transport.respond(404)
        transport.respond(302)
        uploader(transport = transport)
        advanceTimeBy(600_001)
        assertEquals(listOf(0L, 300_000L, 600_000L), transport.sendTimes)
        assertEquals(0, delegate.counts().total) // 세 번째 요청(기본 200)에서야 삭제됨
        assertEquals(0L, counters.rejectedByServer.get())
    }

    @Test
    fun `413 halves the batch until one event and drops only that event`() = runTest {
        delegate.addEvents("e", 8)
        val transport = transport()
        repeat(4) { transport.respond(413) } // 8, 4, 2, 1
        uploader(transport = transport, flushAt = 1)
        runCurrent()

        assertEquals(listOf(8, 4, 2, 1, 1, 6), transport.batchSizes())
        assertEquals(listOf("e-1"), transport.messageIds(3))
        assertEquals(1L, counters.rejectedByServer.get())
        assertEquals(0, delegate.counts().total)
    }

    @Test
    fun `batch limit is restored after a success`() = runTest {
        delegate.addEvents("e", 4)
        val transport = transport()
        transport.respond(413)
        val uploader = uploader(transport = transport, flushAt = 1)
        runCurrent()
        assertEquals(listOf(4, 2, 2), transport.batchSizes())
        assertEquals(Uploader.DEFAULT_MAX_BATCH_COUNT, uploader.currentBatchLimit)
    }

    @Test
    fun `2xx with a rejected list deletes the whole batch and counts rejections`() = runTest {
        delegate.addEvents("e", 3)
        val transport = transport()
        transport.respond(200, body = "{\"rejected\":[{\"messageId\":\"e-2\",\"reason\":\"bad\"}]}")
        uploader(transport = transport)
        runCurrent()
        assertEquals(0, delegate.counts().total)
        assertEquals(1L, counters.rejectedByServer.get())
        assertTrue(logger.errors.isEmpty())
    }

    @Test
    fun `2xx rejecting every event logs an error but still deletes`() = runTest {
        delegate.addEvents("e", 2)
        val transport = transport()
        transport.respond(200, body = "{\"rejected\":[{\"messageId\":\"e-1\"},{\"messageId\":\"e-2\"}]}")
        uploader(transport = transport)
        runCurrent()
        assertEquals(0, delegate.counts().total)
        assertTrue(logger.errors.single().contains("rejected every event"))
    }

    @Test
    fun `malformed 2xx body does not change the outcome`() = runTest {
        delegate.addEvents("e", 2)
        val transport = transport()
        transport.respond(200, body = "<html>ok</html>")
        uploader(transport = transport)
        runCurrent()
        assertEquals(0, delegate.counts().total)
    }

    @Test
    fun `read failure is retried with backoff`() = runTest {
        delegate.addEvents("e", 1)
        store.failNextReads = 1
        val transport = transport()
        uploader(transport = transport)
        runCurrent()
        assertTrue(transport.requests.isEmpty())
        advanceTimeBy(1_001)
        assertEquals(listOf(1_000L), transport.sendTimes)
    }

    // ---------- 장기간 실패 시 로그 억제 (5단계 승인 조건 1) ----------

    @Test
    fun `a persistent unexpected response is logged once per hour with a count of suppressed failures`() = runTest {
        delegate.addEvents("e", 1)
        val transport = transport()
        transport.fallback = { TransportResponse(404, null, null) }
        uploader(transport = transport)
        advanceTimeBy(3 * 60 * 60 * 1_000L - 1) // 3시간: 5분마다 시도 → 36번

        assertEquals(36, transport.requests.size)
        assertEquals(3, logger.errors.size) // 0시간, 1시간, 2시간
        assertTrue(logger.errors[1].contains("11 similar failure(s)"))
        assertEquals(1, delegate.counts().total) // 데이터는 유지
    }

    @Test
    fun `persistent transient failures do not flood the log and recovery is reported once`() = runTest {
        delegate.addEvents("e", 1)
        val transport = transport()
        repeat(40) { transport.respond(503) }
        uploader(transport = transport)
        // backoff 합계: 1+2+…+256초(511초) + 31 × 300초 ≈ 2.7시간
        advanceTimeBy(3 * 60 * 60 * 1_000L)

        assertEquals(41, transport.requests.size) // 40번 실패 후 성공
        assertTrue("warnings=${logger.warnings}", logger.warnings.count { it.contains("503") } <= 3)
        assertEquals(1, logger.warnings.count { it.contains("succeeded after 40 failed attempt(s)") })
    }

    @Test
    fun `different failure kinds are logged independently`() = runTest {
        delegate.addEvents("e", 1)
        val transport = transport()
        transport.respond(503)
        transport.respond(401)
        transport.respond(404)
        uploader(transport = transport)
        advanceTimeBy(700_000)
        assertEquals(1, logger.warnings.count { it.contains("503") })
        assertEquals(2, logger.errors.size) // 401, 404 각각 한 번
    }

    @Test
    fun `D6-1 distinct important errors are never hidden behind another error kind`() = runTest {
        delegate.addEvents("e", 1)
        val transport = transport()
        // 한 시간 안에 서로 다른 원인이 번갈아 발생한다
        transport.failWithNetworkError() // SocketTimeout 계열 대신 IOException
        transport.respond(401)
        transport.respond(403)
        transport.respond(404)
        transport.respond(500)
        transport.respond(503)
        transport.failWithNetworkError()
        transport.respond(401) // 같은 종류의 반복은 억제된다
        uploader(transport = transport)
        advanceTimeBy(59 * 60 * 1_000L)

        assertTrue(logger.errors.any { it.contains("401") })
        assertTrue(logger.errors.any { it.contains("403") })
        assertTrue(logger.errors.any { it.contains("404") })
        assertTrue(logger.warnings.any { it.contains("(500)") })
        assertTrue(logger.warnings.any { it.contains("(503)") })
        assertEquals(1, logger.errors.count { it.contains("401") })
        assertEquals(1, logger.warnings.count { it.contains("network") })
    }

    @Test
    fun `D6-1 different network exception types are logged separately`() = runTest {
        delegate.addEvents("e", 1)
        val transport = transport()
        transport.failWithNetworkError() // IOException
        transport.respondWith { throw java.net.SocketTimeoutException("timeout") }
        transport.respondWith { throw javax.net.ssl.SSLHandshakeException("bad certificate") }
        transport.respondWith { throw java.net.SocketTimeoutException("timeout again") }
        uploader(transport = transport)
        advanceTimeBy(60_000)

        val network = logger.warnings.filter { it.contains("network") }
        assertEquals(3, network.size) // IOException, SocketTimeout, SSLHandshake 각각 한 번
        assertTrue(network.any { it.contains("SSLHandshakeException") })
    }

    // ---------- trigger ----------

    @Test
    fun `events left from a previous run are sent on start`() = runTest {
        delegate.addEvents("left", 2)
        val transport = transport()
        uploader(transport = transport, flushAt = 50)
        runCurrent()
        assertEquals(listOf("left-1", "left-2"), transport.messageIds(0))
    }

    @Test
    fun `count trigger fires only at flushAt`() = runTest {
        val transport = transport()
        val uploader = uploader(transport = transport, flushAt = 10)
        runCurrent() // 시작 시 확인: 비어 있음
        delegate.addEvents("e", 9)
        uploader.onStoreCounts(delegate.counts())
        runCurrent()
        assertTrue(transport.requests.isEmpty())

        delegate.addEvents("f", 1)
        uploader.onStoreCounts(delegate.counts())
        runCurrent()
        assertEquals(10, transport.messageIds(0).size)
    }

    @Test
    fun `interval trigger sends events below flushAt`() = runTest {
        val transport = transport()
        uploader(transport = transport, flushAt = 50, flushIntervalMillis = 30_000)
        runCurrent()
        delegate.addEvents("e", 3)
        advanceTimeBy(29_999)
        assertTrue(transport.requests.isEmpty())
        advanceTimeBy(2)
        assertEquals(listOf(30_000L), transport.sendTimes)
    }

    @Test
    fun `flush sends events below flushAt`() = runTest {
        val transport = transport()
        val uploader = uploader(transport = transport, flushAt = 50)
        runCurrent()
        delegate.addEvents("e", 3)
        uploader.requestFlush()
        runCurrent()
        assertEquals(3, transport.messageIds(0).size)
    }

    @Test
    fun `keeps sending while at least flushAt events remain`() = runTest {
        delegate.addEvents("e", 150)
        val transport = transport()
        uploader(transport = transport, flushAt = 20)
        runCurrent()
        assertEquals(listOf(100, 50), transport.batchSizes())
    }

    @Test
    fun `stops after a batch when fewer than flushAt remain`() = runTest {
        delegate.addEvents("e", 110)
        val transport = transport()
        uploader(transport = transport, flushAt = 20, flushIntervalMillis = 30_000)
        runCurrent()
        assertEquals(listOf(100), transport.batchSizes())
        advanceTimeBy(30_001)
        assertEquals(listOf(100, 10), transport.batchSizes())
    }

    @Test
    fun `batch is limited to 512KB`() = runTest {
        delegate.addEvents("e", 5, sizeBytes = 200 * 1024)
        val transport = transport()
        uploader(transport = transport, flushAt = 1)
        runCurrent()
        assertEquals(listOf(2, 2, 1), transport.batchSizes())
    }

    @Test
    fun `stopped uploader sends nothing more`() = runTest {
        val transport = transport()
        val uploader = uploader(transport = transport, flushAt = 1, flushIntervalMillis = 1_000)
        runCurrent()
        uploader.stop()
        delegate.addEvents("e", 5)
        uploader.requestFlush()
        uploader.onStoreCounts(delegate.counts())
        advanceTimeBy(10_000)
        assertTrue(transport.requests.isEmpty())
    }
}
