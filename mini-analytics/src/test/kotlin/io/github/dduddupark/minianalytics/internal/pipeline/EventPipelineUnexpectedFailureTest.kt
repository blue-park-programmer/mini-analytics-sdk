@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.dduddupark.minianalytics.internal.pipeline

import io.github.dduddupark.minianalytics.FailureReason
import io.github.dduddupark.minianalytics.IdentityResult
import io.github.dduddupark.minianalytics.internal.log.Logger
import io.github.dduddupark.minianalytics.internal.storage.FaultyEventStore
import io.github.dduddupark.minianalytics.internal.storage.InMemoryEventStore
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CopyOnWriteArrayList

/**
 * storage coroutine이 재시도 경계 밖의 실패로 끝났을 때 (설계 §8.4, Q-4b, 3단계 추가 확인 3).
 * - 파이프라인이 비활성 상태로 바뀌어, 이후 명령이 큐에 무한히 쌓이지 않는다.
 * - `Error`는 삼키지 않고 fatal handler(운영: 스레드의 uncaught exception handler)로 넘긴다.
 * - 일반 `Exception`은 호스트로 넘기지 않는다 (공개 API는 호스트를 크래시시키지 않는다).
 */
@RunWith(RobolectricTestRunner::class)
class EventPipelineUnexpectedFailureTest {

    private class FatalTestError : Error("simulated fatal error")

    private val delegate = InMemoryEventStore()
    private val store = FaultyEventStore(delegate)
    private val main = ManualExecutor()
    private val fatalErrors = CopyOnWriteArrayList<Throwable>()

    private fun TestScope.pipeline(logger: Logger = RecordingLogger()) = EventPipeline(
        store = store,
        maxQueuedEvents = 1_000,
        scope = pipelineScope(),
        callbacks = CallbackDispatcher(main, logger),
        logger = logger,
        fatalErrorHandler = { fatalErrors += it },
    ).also { advanceUntilIdle() }

    @Test
    fun `an Error escaping the storage coroutine disables the pipeline and is forwarded, not swallowed`() = runTest {
        val pipeline = pipeline()
        store.errorFactory = { throw FatalTestError() } // Exception이 아니라 Error를 던진다
        store.failNextCommits = 1
        val reset = RecordingCallback()

        pipeline.track("T1", null)
        pipeline.reset(reset)
        advanceUntilIdle()

        assertTrue(pipeline.isDisabled())
        assertTrue(fatalErrors.single() is FatalTestError)
        main.runAll()
        assertEquals(FailureReason.STORAGE_ERROR, (reset.results.single() as IdentityResult.Failure).reason)
        assertEquals(0, delegate.counts().total)
    }

    @Test
    fun `after abnormal termination later commands do not pile up`() = runTest {
        val pipeline = pipeline()
        store.errorFactory = { throw FatalTestError() }
        store.failNextCommits = 1
        pipeline.track("trigger", null)
        advanceUntilIdle()
        val droppedBefore = pipeline.counters.snapshot().droppedStorageError

        // 메모리 상한(1,000)보다 훨씬 많이 호출해도 큐에 남는 것이 없다.
        repeat(5_000) { pipeline.track("t$it", null) }
        val identify = RecordingCallback()
        pipeline.identify("u", identify)

        assertTrue(pipeline.isIdle())
        assertEquals(droppedBefore + 5_001, pipeline.counters.snapshot().droppedStorageError)
        main.runAll()
        assertEquals(FailureReason.STORAGE_ERROR, (identify.results.single() as IdentityResult.Failure).reason)
    }

    @Test
    fun `an unexpected Exception outside the retry boundary disables without forwarding it`() = runTest {
        // 재시도 경계의 catch 안에서 로그를 남기다가 예외가 나는 상황 (경계 밖으로 새어 나감)
        val throwingLogger = object : Logger {
            override fun debug(message: String) = Unit
            override fun warn(message: String, throwable: Throwable?) {
                if (message.startsWith("Storage commit failed")) throw IllegalStateException("logger bug")
            }

            override fun error(message: String, throwable: Throwable?) = Unit
        }
        val pipeline = pipeline(throwingLogger)
        store.failNextCommits = 1
        val reset = RecordingCallback()
        pipeline.reset(reset)
        advanceUntilIdle()

        assertTrue(pipeline.isDisabled())
        assertTrue("Exception must not be forwarded as fatal", fatalErrors.isEmpty())
        main.runAll()
        assertEquals(FailureReason.STORAGE_ERROR, (reset.results.single() as IdentityResult.Failure).reason)
    }

    @Test
    fun `callbacks are completed at most once even if failure handling runs twice`() = runTest {
        val pipeline = pipeline()
        store.errorFactory = { throw FatalTestError() }
        store.failNextCommits = 1
        val identify = RecordingCallback()
        pipeline.identify("u", identify)
        advanceUntilIdle() // catch 블록과 CoroutineExceptionHandler가 모두 실행됨
        main.runAll()
        main.runAll()
        assertEquals(1, identify.calls)
    }

    // ---------- C-1: 운영 기본값(스레드의 uncaught exception handler)으로의 실제 전달 경로 ----------

    /** fatalErrorHandler를 바꾸지 않은(운영 기본값) 파이프라인. */
    private fun TestScope.pipelineWithDefaultFatalHandler() = EventPipeline(
        store = store,
        maxQueuedEvents = 1_000,
        scope = pipelineScope(),
        callbacks = CallbackDispatcher(main, RecordingLogger()),
        logger = RecordingLogger(),
    ).also { advanceUntilIdle() }

    /** 테스트 스케줄러는 advanceUntilIdle()을 부른 스레드에서 작업을 실행하므로, 그 스레드의 handler를 바꿔서 관찰한다. */
    private fun <T> withRecordingUncaughtHandler(block: (MutableList<Throwable>) -> T): T {
        val thread = Thread.currentThread()
        val original = thread.uncaughtExceptionHandler
        val received = CopyOnWriteArrayList<Throwable>()
        thread.uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, e -> received += e }
        try {
            return block(received)
        } finally {
            thread.uncaughtExceptionHandler = original
        }
    }

    @Test
    fun `C-1 an Error reaches the thread uncaught handler exactly once`() = runTest {
        withRecordingUncaughtHandler { received ->
            val pipeline = pipelineWithDefaultFatalHandler()
            store.errorFactory = { throw FatalTestError() }
            store.failNextCommits = 1
            pipeline.track("T", null)
            advanceUntilIdle()

            assertTrue(pipeline.isDisabled())
            assertEquals(1, received.size) // catch 블록과 CoroutineExceptionHandler를 모두 거쳐도 한 번
            assertTrue(received.single() is FatalTestError)
        }
    }

    @Test
    fun `C-1 an escaping Exception never reaches the thread uncaught handler`() = runTest {
        withRecordingUncaughtHandler { received ->
            val throwingLogger = object : Logger {
                override fun debug(message: String) = Unit
                override fun warn(message: String, throwable: Throwable?) {
                    if (message.startsWith("Storage commit failed")) throw IllegalStateException("logger bug")
                }

                override fun error(message: String, throwable: Throwable?) = Unit
            }
            val pipeline = EventPipeline(
                store, 1_000, pipelineScope(), CallbackDispatcher(main, throwingLogger), throwingLogger,
            ).also { advanceUntilIdle() }
            store.failNextCommits = 1
            pipeline.track("T", null)
            advanceUntilIdle()

            assertTrue(pipeline.isDisabled())
            assertTrue("Exception must not terminate the host: $received", received.isEmpty())
        }
    }

    @Test
    fun `ordinary storage exceptions still use the retry path`() = runTest {
        val pipeline = pipeline()
        store.failNextCommits = 2 // 기본 errorFactory: IllegalStateException
        val reset = RecordingCallback()
        pipeline.reset(reset)
        advanceUntilIdle()
        main.runAll()
        assertSame(IdentityResult.Success, reset.results.single())
        assertTrue(fatalErrors.isEmpty())
    }
}
