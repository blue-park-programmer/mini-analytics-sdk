@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.dduddupark.minianalytics.internal.pipeline

import io.github.dduddupark.minianalytics.FailureReason
import io.github.dduddupark.minianalytics.IdentityResult
import io.github.dduddupark.minianalytics.internal.model.Identity
import io.github.dduddupark.minianalytics.internal.storage.FaultyEventStore
import io.github.dduddupark.minianalytics.internal.storage.InMemoryEventStore
import io.github.dduddupark.minianalytics.internal.storage.StoreCounts
import io.github.dduddupark.minianalytics.internal.storage.StoredIdentity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 명령 큐와 storage coroutine의 정상 경로 (설계 §3, §4.2, §4.3, R-1).
 * 가상 시간(`kotlinx-coroutines-test`)과 [InMemoryEventStore]를 쓴다.
 * 직렬화가 `org.json`을 쓰므로 Robolectric으로 실행한다.
 */
@RunWith(RobolectricTestRunner::class)
class EventPipelineTest {

    private val store = FaultyEventStore(InMemoryEventStore())
    private val main = ManualExecutor()
    private val logger = RecordingLogger()
    private var now = 1_000L

    private fun TestScope.pipeline(
        scope: CoroutineScope = pipelineScope(),
        maxQueuedEvents: Int = 1_000,
        memoryQueueLimit: Int = EventPipeline.DEFAULT_MEMORY_QUEUE_LIMIT,
        onCommitted: ((StoreCounts) -> Unit)? = null,
    ) = EventPipeline(
        store = store,
        maxQueuedEvents = maxQueuedEvents,
        scope = scope,
        callbacks = CallbackDispatcher(main, logger),
        logger = logger,
        clock = { now },
        newId = IdSequence(),
        memoryQueueLimit = memoryQueueLimit,
        onCommitted = onCommitted,
    )

    // ---------- 생성 시점 스냅샷 (Q8, R-1) ----------

    @Test
    fun `track before reset keeps the old identity and track after reset gets the new one`() = runTest {
        val pipeline = pipeline()
        advanceUntilIdle() // identity 생성
        val initial = store.loadIdentity()!!.identity

        pipeline.track("T1", null)
        pipeline.reset(null)
        pipeline.track("T2", null)
        advanceUntilIdle()

        val (t1, t2) = store.messages()
        assertEquals(initial.anonymousId, t1.anonymousId)
        assertNotEqualsAnonymous(t1, t2)
        assertNull(t2.userId)
        assertEquals(t2.anonymousId, store.loadIdentity()!!.identity.anonymousId)
    }

    @Test
    fun `identify message and following events carry the new user id in call order`() = runTest {
        val pipeline = pipeline()
        pipeline.track("before", null)
        pipeline.identify("user-1", null)
        pipeline.track("after", null)
        advanceUntilIdle()

        val messages = store.messages()
        assertEquals(listOf("track", "identify", "track"), messages.map { it.type })
        assertEquals(listOf(null, "user-1", "user-1"), messages.map { it.userId })
        assertEquals(1, messages.map { it.anonymousId }.distinct().size) // identify는 anonymousId를 바꾸지 않는다
        assertEquals("user-1", store.loadIdentity()!!.identity.userId)
    }

    @Test
    fun `already stored events are not rewritten by a later reset`() = runTest {
        val pipeline = pipeline()
        pipeline.identify("user-1", null)
        pipeline.track("T1", null)
        advanceUntilIdle()
        // 저장된 원본 payload 문자열을 비교한다 (JSONObject는 equals를 구현하지 않음).
        val before = store.readBatch(Int.MAX_VALUE, Int.MAX_VALUE).map { it.payload }

        pipeline.reset(null)
        advanceUntilIdle()

        assertEquals(before, store.readBatch(Int.MAX_VALUE, Int.MAX_VALUE).map { it.payload })
    }

    @Test
    fun `screen goes through the same pipeline`() = runTest {
        val pipeline = pipeline()
        pipeline.identify("u", null)
        pipeline.screen("Home", mapOf("tab" to 1))
        advanceUntilIdle()
        val screen = store.messages().last()
        assertEquals("screen", screen.type)
        assertEquals("Home", screen.name)
        assertEquals("u", screen.userId)
        assertEquals(1, screen.properties!!.getInt("tab"))
    }

    // ---------- 초기 로드 (Q20, R-1) ----------

    @Test
    fun `first run creates and persists an anonymous identity`() = runTest {
        pipeline()
        advanceUntilIdle()
        val stored = store.loadIdentity()!!
        assertNull(stored.identity.userId)
        assertTrue(stored.identity.anonymousId.isNotEmpty())
    }

    @Test
    fun `existing identity is reused on restart`() = runTest {
        val existing = StoredIdentity(Identity("anon-existing", "user-x"), version = 9)
        store.commit(
            io.github.dduddupark.minianalytics.internal.storage.StoreTransaction(
                emptyList(), existing, io.github.dduddupark.minianalytics.internal.storage.StoreLimits(1_000),
            ),
        )
        val pipeline = pipeline()
        pipeline.track("T", null)
        advanceUntilIdle()
        val message = store.messages().single()
        assertEquals("anon-existing", message.anonymousId)
        assertEquals("user-x", message.userId)
    }

    @Test
    fun `calls made before the store is loaded are kept in order with call-time values`() = runTest {
        val pipeline = pipeline()
        // storage coroutine은 아직 실행되지 않았다 (가상 시간을 진행하지 않음)
        now = 5_000L
        pipeline.track("first", null)
        now = 6_000L
        pipeline.identify("user-1", null)
        now = 7_000L
        pipeline.track("second", null)
        assertNull(store.loadIdentity()) // 아직 로드 전

        now = 99_000L // 로드 시점의 시각은 쓰이지 않아야 한다
        advanceUntilIdle()

        val messages = store.messages()
        assertEquals(listOf("first", null, "second"), messages.map { it.name })
        assertEquals(
            listOf("1970-01-01T00:00:05.000Z", "1970-01-01T00:00:06.000Z", "1970-01-01T00:00:07.000Z"),
            messages.map { it.timestamp },
        )
        assertEquals(listOf(null, "user-1", "user-1"), messages.map { it.userId })
        // messageId도 호출 시점에 정해졌다: id-1(track), id-2(identify), id-3(track). identity 생성 id는 그 뒤.
        assertEquals(listOf("id-1", "id-2", "id-3"), messages.map { it.messageId })
    }

    // ---------- 콜백 (Q17, Q18, Q21) ----------

    @Test
    fun `identity callbacks are posted only after the commit and exactly once`() = runTest {
        val pipeline = pipeline()
        val identify = RecordingCallback()
        val reset = RecordingCallback()
        var identityAtCallback: Identity? = null

        pipeline.identify("user-1", identify)
        pipeline.reset { result ->
            identityAtCallback = store.loadIdentity()?.identity
            reset.onComplete(result)
        }
        advanceUntilIdle()

        // 아직 메인 스레드에서 실행되지 않았다: post만 되었다.
        assertEquals(0, identify.calls)
        assertEquals(2, main.size)

        main.runAll()
        assertSame(IdentityResult.Success, identify.results.single())
        assertSame(IdentityResult.Success, reset.results.single())
        // 콜백 시점에 reset의 효과가 이미 디스크에 있다.
        assertNull(identityAtCallback!!.userId)

        main.runAll()
        assertEquals(1, identify.calls)
        assertEquals(1, reset.calls)
    }

    // ---------- 유효하지 않은 userId (D-5, T-I1, T-I2) ----------

    @Test
    fun `T-I1 invalid userId fails with INVALID_ARGUMENT and keeps the stored identity`() = runTest {
        val pipeline = pipeline()
        pipeline.identify("A", null)
        advanceUntilIdle()
        val before = store.loadIdentity()!!
        val identifyRowsBefore = store.counts().identifies

        val callbacks = listOf("", "   ", "　", "x".repeat(257), null).map { userId ->
            RecordingCallback().also { pipeline.identify(userId, it) }
        }
        advanceUntilIdle()
        main.runAll()

        for (callback in callbacks) {
            val failure = callback.results.single() as IdentityResult.Failure
            assertEquals(FailureReason.INVALID_ARGUMENT, failure.reason)
        }
        assertEquals(before, store.loadIdentity())
        assertEquals(identifyRowsBefore, store.counts().identifies)
    }

    @Test
    fun `T-I2 track after an invalid identify keeps the previous user`() = runTest {
        val pipeline = pipeline()
        pipeline.identify("A", null)
        pipeline.identify("", null)
        pipeline.track("T", null)
        advanceUntilIdle()
        assertEquals("A", store.messages().last().userId)
    }

    @Test
    fun `invalid identify is reported without touching the queue`() = runTest {
        val pipeline = pipeline()
        advanceUntilIdle()
        val attempts = store.commitAttempts
        pipeline.identify(" ", RecordingCallback())
        assertTrue(pipeline.isIdle())
        advanceUntilIdle()
        assertEquals(attempts, store.commitAttempts)
    }

    // ---------- 이름과 properties (R-9') ----------

    @Test
    fun `invalid event names are ignored with a warning`() = runTest {
        val pipeline = pipeline()
        pipeline.track("", null)
        pipeline.track(null, null)
        pipeline.screen("x".repeat(257), null)
        advanceUntilIdle()
        assertEquals(0, store.counts().events)
        assertEquals(3, logger.warnings.count { it.contains("ignored") })
    }

    @Test
    fun `properties are sanitized on the calling thread and counted`() = runTest {
        val pipeline = pipeline()
        pipeline.track("partial", mapOf("ok" to 1, "bad" to Any()))
        pipeline.track("dropped", (1..600).associate { "k$it" to it })
        advanceUntilIdle()

        val (partial, dropped) = store.messages()
        assertEquals(1, partial.properties!!.getInt("ok"))
        assertFalse(partial.properties!!.has("bad"))
        assertEquals(0, dropped.properties!!.length())
        assertEquals(1, pipeline.counters.snapshot().propertiesSanitized)
        assertEquals(1, pipeline.counters.snapshot().propertiesDropped)
    }

    @Test
    fun `mutating properties after track does not change the stored event`() = runTest {
        val pipeline = pipeline()
        val properties = mutableMapOf<String, Any?>("v" to "before")
        pipeline.track("T", properties)
        properties["v"] = "after"
        advanceUntilIdle()
        assertEquals("before", store.messages().single().properties!!.getString("v"))
    }

    // ---------- 메모리 상한 (R-4') ----------

    @Test
    fun `memory queue limit drops oldest track and screen but never identity commands`() = runTest {
        val pipeline = pipeline(memoryQueueLimit = 3)
        // 로드 전이라 모든 명령이 메모리 큐에 있다.
        pipeline.reset(null)
        pipeline.track("t1", null)
        pipeline.identify("u", null)
        pipeline.track("t2", null)
        pipeline.screen("s3", null)
        pipeline.track("t4", null)
        pipeline.track("t5", null)
        advanceUntilIdle()

        val messages = store.messages()
        assertEquals(listOf(null, "s3", "t4", "t5"), messages.map { it.name })
        assertEquals("identify", messages.first().type)
        assertEquals(2, pipeline.counters.snapshot().droppedInMemory)
        assertEquals("u", store.loadIdentity()!!.identity.userId)
    }

    // ---------- 트랜잭션 단위 ----------

    @Test
    fun `commands are committed in transactions of at most 50`() = runTest {
        val pipeline = pipeline()
        advanceUntilIdle()
        val attemptsAfterLoad = store.commitAttempts
        repeat(120) { pipeline.track("t$it", null) }
        advanceUntilIdle()
        assertEquals(3, store.commitAttempts - attemptsAfterLoad)
        assertEquals((0 until 120).map { "t$it" }, store.messages().map { it.name })
    }

    @Test
    fun `store limit evictions are counted`() = runTest {
        val pipeline = pipeline(maxQueuedEvents = 2)
        repeat(5) { pipeline.track("t$it", null) }
        advanceUntilIdle()
        assertEquals(listOf("t3", "t4"), store.messages().map { it.name })
        assertEquals(3, pipeline.counters.snapshot().evictedFromStore)
    }

    @Test
    fun `committed counts are reported for the flush trigger`() = runTest {
        val reported = mutableListOf<StoreCounts>()
        val pipeline = pipeline(onCommitted = { reported += it })
        pipeline.track("t", null)
        pipeline.identify("u", null)
        advanceUntilIdle()
        assertEquals(StoreCounts(events = 1, identifies = 1), reported.last())
    }

    @Test
    fun `pipeline becomes idle after processing`() = runTest {
        val pipeline = pipeline()
        pipeline.track("t", null)
        assertFalse(pipeline.isIdle())
        advanceUntilIdle()
        runCurrent()
        assertTrue(pipeline.isIdle())
    }

    private fun assertNotEqualsAnonymous(a: StoredMessage, b: StoredMessage) {
        assertTrue("anonymousId should change after reset", a.anonymousId != b.anonymousId)
    }
}
