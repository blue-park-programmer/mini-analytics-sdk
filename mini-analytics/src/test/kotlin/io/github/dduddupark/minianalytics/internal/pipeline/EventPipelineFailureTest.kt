@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.dduddupark.minianalytics.internal.pipeline

import android.database.sqlite.SQLiteFullException
import io.github.dduddupark.minianalytics.FailureReason
import io.github.dduddupark.minianalytics.IdentityResult
import io.github.dduddupark.minianalytics.internal.model.MessageType
import io.github.dduddupark.minianalytics.internal.storage.FaultyEventStore
import io.github.dduddupark.minianalytics.internal.storage.InMemoryEventStore
import io.github.dduddupark.minianalytics.internal.storage.NewEvent
import io.github.dduddupark.minianalytics.internal.storage.StoreLimits
import io.github.dduddupark.minianalytics.internal.storage.StoreTransaction
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
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

/** 저장소 오류: 멈추고 재시도, 실패하면 비활성화 (설계 §8, R-7''). */
@RunWith(RobolectricTestRunner::class)
class EventPipelineFailureTest {

    private val delegate = InMemoryEventStore()
    private val store = FaultyEventStore(delegate)
    private val main = ManualExecutor()
    private val logger = RecordingLogger()

    private fun TestScope.pipeline() = EventPipeline(
        store = store,
        maxQueuedEvents = 1_000,
        scope = pipelineScope(),
        callbacks = CallbackDispatcher(main, logger),
        logger = logger,
        newId = IdSequence(),
    )

    /** 로드를 끝낸 파이프라인. 이후의 commit만 실패를 주입한다. */
    private fun TestScope.loadedPipeline(): EventPipeline = pipeline().also {
        advanceUntilIdle()
    }

    // ---------- 재시도 후 성공 ----------

    @Test
    fun `failed commit is retried with the same commands and nothing leaks before it succeeds`() = runTest {
        val pipeline = loadedPipeline()
        val oldIdentity = delegate.loadIdentity()!!.identity
        store.failNextCommits = 2
        val callback = RecordingCallback()

        pipeline.track("T1", null)
        pipeline.reset(callback)
        pipeline.track("T2", null)
        runCurrent() // 첫 시도 실패
        assertEquals(0, delegate.counts().total)

        advanceTimeBy(1_001) // 1초 후 두 번째 시도 실패
        assertEquals(0, delegate.counts().total)

        // 재시도 중에 들어온 명령: reset이 commit되기 전에는 기록되면 안 된다.
        pipeline.track("T3", null)
        advanceTimeBy(4_000)
        assertEquals(0, delegate.counts().total)
        main.runAll()
        assertEquals(0, callback.calls) // 재시도 중에는 콜백이 없다

        advanceUntilIdle() // 5초 후 세 번째 시도 성공
        val messages = delegate.messages()
        assertEquals(listOf("T1", "T2", "T3"), messages.map { it.name })
        assertEquals(oldIdentity.anonymousId, messages[0].anonymousId)
        val newAnonymous = delegate.loadIdentity()!!.identity.anonymousId
        assertEquals(newAnonymous, messages[1].anonymousId)
        assertEquals(newAnonymous, messages[2].anonymousId)

        main.runAll()
        assertSame(IdentityResult.Success, callback.results.single())
        assertFalse(pipeline.isDisabled())
    }

    // ---------- 재시도 실패 → 비활성화 (T-L2) ----------

    @Test
    fun `T-L2 repeated failure disables the SDK and fails pending callbacks exactly once`() = runTest {
        val pipeline = loadedPipeline()
        val oldIdentity = delegate.loadIdentity()
        store.failAllCommits = true
        val reset = RecordingCallback()
        val identify = RecordingCallback()

        pipeline.track("T1", null)
        pipeline.reset(reset)
        pipeline.identify("u", identify)
        pipeline.track("T2", null)

        advanceTimeBy(5_999)
        assertFalse(pipeline.isDisabled())
        advanceUntilIdle() // 0초, 1초, 6초에 세 번 시도
        assertTrue(pipeline.isDisabled())
        assertEquals(3 + 1, store.commitAttempts) // 로드 시 identity 생성 commit 1회 포함

        main.runAll()
        main.runAll()
        for (callback in listOf(reset, identify)) {
            val failure = callback.results.single() as IdentityResult.Failure
            assertEquals(FailureReason.STORAGE_ERROR, failure.reason)
        }
        // 실패한 Identity는 어디에도 반영되지 않았다.
        assertEquals(oldIdentity, delegate.loadIdentity())
        assertEquals(0, delegate.counts().total)
        assertEquals(4, pipeline.counters.snapshot().droppedStorageError)
        assertEquals(1, logger.errors.size)
    }

    @Test
    fun `T-L4 calls after disable are dropped and identity callbacks fail immediately`() = runTest {
        val pipeline = loadedPipeline()
        store.failAllCommits = true
        pipeline.track("T", null)
        advanceUntilIdle()
        assertTrue(pipeline.isDisabled())
        main.runAll()
        val droppedBefore = pipeline.counters.snapshot().droppedStorageError

        val reset = RecordingCallback()
        val identify = RecordingCallback()
        pipeline.reset(reset)
        pipeline.identify("u", identify)
        pipeline.track("T2", null)
        main.runAll() // 가상 시간을 진행하지 않아도 바로 전달된다

        assertEquals(FailureReason.STORAGE_ERROR, (reset.results.single() as IdentityResult.Failure).reason)
        assertEquals(FailureReason.STORAGE_ERROR, (identify.results.single() as IdentityResult.Failure).reason)
        assertEquals(droppedBefore + 3, pipeline.counters.snapshot().droppedStorageError)
        assertEquals(1, logger.errors.size) // 비활성 이후 호출마다 error 로그를 남기지 않는다
    }

    // ---------- 로드 실패 ----------

    @Test
    fun `load failure is retried and then disables with failure for queued callbacks`() = runTest {
        store.failNextLoads = 3
        val pipeline = pipeline()
        val callback = RecordingCallback()
        pipeline.reset(callback)

        advanceUntilIdle()
        main.runAll()

        assertTrue(pipeline.isDisabled())
        assertEquals(FailureReason.STORAGE_ERROR, (callback.results.single() as IdentityResult.Failure).reason)
        assertNull(delegate.loadIdentity())
    }

    @Test
    fun `load succeeds on the third attempt`() = runTest {
        store.failNextLoads = 2
        val pipeline = pipeline()
        pipeline.track("T", null)
        advanceUntilIdle()
        assertFalse(pipeline.isDisabled())
        assertEquals(1, delegate.counts().events)
    }

    // ---------- 디스크 부족 ----------

    @Test
    fun `storage full reclaims space once before retrying`() = runTest {
        delegate.commit(
            StoreTransaction(
                (1..8).map { NewEvent("old-$it", MessageType.TRACK, "{}", 2) }, null, StoreLimits(1_000),
            ),
        )
        val pipeline = loadedPipeline()
        store.errorFactory = { SQLiteFullException("disk full") }
        store.failNextCommits = 1

        pipeline.track("new", null)
        advanceUntilIdle()

        // 8개의 25% = 2개 삭제 후 재시도 성공
        assertEquals(7, delegate.counts().events)
        assertEquals(2, pipeline.counters.snapshot().evictedFromStore)
        assertFalse(pipeline.isDisabled())
    }

    @Test
    fun `non full errors do not reclaim space`() = runTest {
        delegate.commit(
            StoreTransaction(listOf(NewEvent("old", MessageType.TRACK, "{}", 2)), null, StoreLimits(1_000)),
        )
        val pipeline = loadedPipeline()
        store.failNextCommits = 1
        pipeline.track("new", null)
        advanceUntilIdle()
        assertEquals(2, delegate.counts().events)
        assertEquals(0, pipeline.counters.snapshot().evictedFromStore)
    }
}
