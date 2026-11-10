package io.github.dduddupark.minianalytics.mockserver

import io.github.dduddupark.minianalytics.Configuration
import io.github.dduddupark.minianalytics.internal.AnalyticsClient
import io.github.dduddupark.minianalytics.internal.pipeline.ManualExecutor
import io.github.dduddupark.minianalytics.internal.pipeline.RecordingLogger
import io.github.dduddupark.minianalytics.internal.storage.InMemoryEventStore
import io.github.dduddupark.minianalytics.internal.upload.Backoff
import io.github.dduddupark.minianalytics.internal.upload.EnvelopeBuilder
import io.github.dduddupark.minianalytics.internal.upload.HttpUrlConnectionTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.TimeUnit

/**
 * SDK 전체 경로(track → SQLite 대신 InMemory 저장 → 배치 → 실제 HTTP) × 계약 Mock Server (6단계).
 *
 * 실제 dispatcher와 실제 소켓을 쓰므로 실제 시간으로 기다린다. backoff는 테스트에서 짧게 줄였다
 * (10ms 시작, 최대 50ms). 그 밖의 동작은 운영과 같다.
 */
@RunWith(RobolectricTestRunner::class)
class ServerContractEndToEndTest {

    private var server: ContractMockServer? = null
    private var client: AnalyticsClient? = null
    private val store = InMemoryEventStore()
    private val storage = Dispatchers.IO.limitedParallelism(1)

    private fun start(
        server: ContractMockServer,
        apiKey: String = "pk_test",
        flushAt: Int = 1,
        trust: Boolean = false,
    ): AnalyticsClient {
        server.start()
        this.server = server
        val configuration = Configuration.Builder(apiKey, server.url())
            .flushAt(flushAt).flushIntervalMillis(if (flushAt == 1) 1_000 else 600_000)
            .trustInvalidEventResponses(trust).build()
        return AnalyticsClient(
            configuration = configuration,
            store = store,
            scope = CoroutineScope(SupervisorJob() + storage),
            callbackExecutor = ManualExecutor(),
            logger = RecordingLogger(),
            transport = HttpUrlConnectionTransport(server.url(), apiKey, "test", allowInsecure = true),
            envelope = EnvelopeBuilder({ "{}" }),
            backoff = Backoff(baseMillis = 10, maxMillis = 50),
        ).also { client = it }
    }

    /**
     * "이전 실행"을 흉내 낸다: 전송하지 않는 클라이언트로 이벤트를 저장해 둔다.
     * 그러면 다음에 시작하는 클라이언트가 시작 시 확인(설계 §4.4)에서 이 이벤트들을 **한 배치로** 보낸다.
     * (클라이언트를 먼저 띄우고 track하면 시작 시 확인과 commit이 경합해서 배치 구성이 매번 달라진다.)
     */
    private fun previousRun(vararg events: Pair<String, Map<String, Any?>?>) {
        val configuration = Configuration.Builder("pk_test", "https://unused.example.com").build()
        val offline = AnalyticsClient(
            configuration, store, CoroutineScope(SupervisorJob() + storage), ManualExecutor(), RecordingLogger(),
        )
        events.forEach { (name, properties) -> offline.track(name, properties) }
        waitUntil { offline.pipeline.isIdle() && storedLocally() == events.size }
        offline.close() // coroutine 정리 (InMemoryEventStore.close()는 데이터를 지우지 않는다)
    }

    @After
    fun tearDown() {
        client?.close()
        server?.shutdown()
    }

    private fun storedLocally(): Int = runBlocking(storage) { store.counts().total }

    private fun waitUntil(timeoutSeconds: Long = 10, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
        while (!condition()) {
            if (System.nanoTime() > deadline) {
                fail(
                    "condition not met in time: local=${storedLocally()} idle=${client?.pipeline?.isIdle()} " +
                        "requests=${server?.requestCount} stored=${server?.stored?.map { it.optString("event") }} " +
                        "received=${server?.received?.size}",
                )
            }
            Thread.sleep(10)
        }
    }

    private fun AnalyticsClient.trackAll(vararg names: String) = names.forEach { track(it, null) }

    // ---------- 정상 경로 ----------

    @Test
    fun `events and identify are delivered once and removed locally`() {
        val server = ContractMockServer()
        val client = start(server)
        client.trackAll("a", "b")
        client.identify("user-1", null)
        client.trackAll("c")
        waitUntil { server.stored.size == 4 && storedLocally() == 0 }

        assertEquals(listOf("track", "track", "identify", "track"), server.stored.map { it.getString("type") })
        assertEquals(1, server.links.size)
    }

    // ---------- 멱등성: 재전송 중복은 서버가 messageId로 제거 ----------

    @Test
    fun `lost response - the batch is resent with the same messageIds and stored once`() {
        previousRun("a" to null, "b" to null, "c" to null)
        val server = ContractMockServer()
        server.injectFault(ContractMockServer.Fault.STORE_THEN_DROP_RESPONSE)
        start(server, flushAt = 100)
        waitUntil { storedLocally() == 0 && server.stored.size == 3 }

        assertEquals(2, server.requestCount) // 응답 유실 → 같은 배치 재전송
        assertEquals(6, server.received.size) // 3건 × 2번 도착
        assertEquals(server.storedIds().toSet().size, server.stored.size) // 저장은 messageId당 한 번
    }

    @Test
    fun `partial store with 503 - the whole batch is resent and deduplicated`() {
        previousRun("a" to null, "b" to null, "c" to null, "d" to null)
        val server = ContractMockServer()
        server.injectFault(ContractMockServer.Fault.STORE_HALF_THEN_503)
        start(server, flushAt = 100) // 시작 시 4건을 한 배치로 보낸다
        waitUntil { storedLocally() == 0 && server.stored.size == 4 }

        assertEquals(2, server.requestCount) // 503(앞 2건만 저장) → 배치 전체 재전송
        assertEquals(8, server.received.size) // 4건 × 2번 도착
        assertEquals(4, server.storedIds().toSet().size) // 저장은 messageId당 한 번
    }

    @Test
    fun `a server without deduplication stores duplicates - the documented risk`() {
        val server = ContractMockServer(deduplicate = false)
        server.injectFault(ContractMockServer.Fault.STORE_THEN_DROP_RESPONSE)
        val client = start(server)
        client.trackAll("a")
        waitUntil { storedLocally() == 0 }
        waitUntil { server.stored.size >= 2 }

        // 계약 4를 지키지 않으면 같은 이벤트가 두 번 저장된다 (설계 §6.5).
        assertEquals(1, server.storedIds().toSet().size)
        assertTrue(server.stored.size >= 2)
    }

    @Test
    fun `repeated identify sends distinct messages but links the pair once`() {
        val server = ContractMockServer()
        val client = start(server)
        client.identify("user-1", null)
        client.identify("user-1", null)
        waitUntil { server.identifyMessages.get() == 2 && storedLocally() == 0 }
        assertEquals(1, server.links.size)
    }

    // ---------- 잘못된 이벤트: 계약 서버 (2xx + rejected) ----------

    @Test
    fun `contract server rejects the invalid event via the rejected list and stores the rest`() {
        previousRun("ok-1" to null, "bad" to mapOf("__invalid" to true), "ok-2" to null)
        val server = ContractMockServer(ContractMockServer.Mode.CONTRACT)
        val client = start(server, flushAt = 100)
        waitUntil { storedLocally() == 0 && server.stored.size == 2 }
        assertEquals(1, server.requestCount) // 한 번의 2xx로 처리 완료

        assertEquals(listOf("ok-1", "ok-2"), server.stored.map { it.getString("event") })
        waitUntil { client.pipeline.counters.rejectedByServer.get() == 1L }
    }

    // ---------- 잘못된 이벤트: 400 서버 (단건 격리) ----------

    @Test
    fun `batch 400 server - isolation delivers every valid event and drops only the invalid one`() {
        previousRun("ok-1" to null, "ok-2" to null, "bad" to mapOf("__invalid" to true), "ok-3" to null, "ok-4" to null)
        val server = ContractMockServer(ContractMockServer.Mode.BATCH_400)
        val client = start(server, flushAt = 100, trust = true) // 5건을 한 배치로 보낸다 → 400 → 단건 격리
        waitUntil { storedLocally() == 0 }

        assertEquals(1 + 5, server.requestCount) // 배치 1번 + 단건 5번
        assertEquals(listOf("ok-1", "ok-2", "ok-3", "ok-4"), server.stored.map { it.getString("event") })
        assertEquals(1L, client.pipeline.counters.rejectedByServer.get())
    }

    @Test
    fun `batch 400 server with the default (untrusted) setting - nothing is isolated or deleted`() {
        previousRun("ok-1" to null, "bad" to mapOf("__invalid" to true), "ok-2" to null)
        val server = ContractMockServer(ContractMockServer.Mode.BATCH_400)
        val client = start(server, flushAt = 100) // trustInvalidEventResponses 기본값 false
        waitUntil { server.requestCount >= 1 }
        Thread.sleep(300) // 짧은 backoff(최대 50ms) 동안 몇 번 더 시도할 시간
        assertTrue("only batch requests", server.received.size % 3 == 0) // 단건 요청 없음
        assertEquals(3, storedLocally())
        assertEquals(0L, client.pipeline.counters.rejectedByServer.get())
    }

    // ---------- 계약이 없는 400, 인증 오류: 보존 ----------

    @Test
    fun `broken server answering 400 without the contract - nothing is deleted`() {
        val server = ContractMockServer(ContractMockServer.Mode.BROKEN_400)
        val client = start(server)
        client.trackAll("a", "b", "c")
        waitUntil { server.requestCount >= 6 } // 여러 번 시도할 시간을 준다

        assertEquals(3, storedLocally())
        assertEquals(0L, client.pipeline.counters.rejectedByServer.get())
    }

    @Test
    fun `wrong api key - 401 keeps every event`() {
        val server = ContractMockServer(apiKey = "pk_test")
        val client = start(server, apiKey = "pk_wrong")
        client.trackAll("a", "b")
        waitUntil { server.requestCount >= 3 }

        assertEquals(2, storedLocally())
        assertTrue(server.stored.isEmpty())
    }
}
