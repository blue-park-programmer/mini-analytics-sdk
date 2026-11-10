@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.dduddupark.minianalytics.internal.pipeline

import io.github.dduddupark.minianalytics.FailureReason
import io.github.dduddupark.minianalytics.IdentityResult
import io.github.dduddupark.minianalytics.internal.storage.FaultyEventStore
import io.github.dduddupark.minianalytics.internal.storage.InMemoryEventStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * 호스트 앱의 로그아웃이 SDK 저장 실패 때문에 막히지 않는지 (설계 §12 G-3a~c, 구현 계획 T-L1~T-L7).
 *
 * T-L3(`initialize()` 전 `reset` → `NOT_INITIALIZED`)는 facade가 생기는 4단계에서 end-to-end로 검증한다.
 */
@RunWith(RobolectricTestRunner::class)
class LogoutFlowTest {

    private val delegate = InMemoryEventStore()
    private val store = FaultyEventStore(delegate)
    private val logger = RecordingLogger()

    /**
     * 설계 §12 G-3를 따르는 호스트 앱의 로그아웃 흐름 예시.
     * - 결과를 기다리되 타임아웃(2초)을 둔다 (G-3b).
     * - 메인 스레드를 블로킹하지 않고 suspend로 기다린다 (G-3c).
     * - 결과가 Success든 Failure든, 타임아웃이든 로그아웃을 진행한다 (G-3a).
     */
    private class HostApp(private val mainScope: CoroutineScope, private val pipeline: EventPipeline) {
        var sessionCleared = false
        var analyticsResult: IdentityResult? = null
        var logoutFinishedAt: Long = -1

        fun logout(now: () -> Long) = mainScope.launch {
            analyticsResult = withTimeoutOrNull(2_000) {
                suspendCancellableCoroutine { continuation ->
                    pipeline.reset { result -> if (continuation.isActive) continuation.resume(result) }
                }
            }
            sessionCleared = true
            logoutFinishedAt = now()
        }
    }

    /** 가상 시간 위에서 메인 Looper를 흉내 낸다: post된 콜백은 테스트 스케줄러에서 실행된다. */
    private fun TestScope.mainExecutor() = Executor { runnable -> launch { runnable.run() } }

    private fun TestScope.pipeline(retryDelays: List<Long> = EventPipeline.DEFAULT_RETRY_DELAYS_MILLIS) =
        EventPipeline(
            store = store,
            maxQueuedEvents = 1_000,
            scope = pipelineScope(),
            callbacks = CallbackDispatcher(mainExecutor(), logger),
            logger = logger,
            retryDelaysMillis = retryDelays,
        ).also { advanceUntilIdle() }

    // ---------- T-L1: reset()은 저장소가 멈춰 있어도 즉시 반환 ----------

    @Test
    fun `T-L1 reset returns immediately even while the store is stuck`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
        val release = CountDownLatch(1)
        val main = ManualExecutor()
        try {
            val pipeline = EventPipeline(delegateWithHang(release), 1_000, scope, CallbackDispatcher(main, logger), logger)
            pipeline.track("warm-up", null) // storage coroutine이 commit 안에서 멈춘다

            val callback = RecordingCallback()
            val elapsedNanos = measure {
                pipeline.reset(callback)
                pipeline.identify("u", callback)
                pipeline.track("t", mapOf("k" to "v"))
            }

            assertTrue("calls took ${elapsedNanos / 1_000_000}ms", elapsedNanos < TimeUnit.MILLISECONDS.toNanos(200))
            assertEquals(0, callback.calls)
        } finally {
            release.countDown()
            scope.cancel()
        }
    }

    private var hangArmed = false

    private fun delegateWithHang(release: CountDownLatch): FaultyEventStore {
        val hanging = FaultyEventStore(InMemoryEventStore())
        hanging.beforeCommit = {
            // 첫 commit(identity 생성)은 통과시키고, 그다음 commit부터 멈춘다.
            if (hangArmed) release.await(10, TimeUnit.SECONDS) else hangArmed = true
        }
        return hanging
    }

    private inline fun measure(block: () -> Unit): Long {
        val start = System.nanoTime()
        block()
        return System.nanoTime() - start
    }

    // ---------- T-L6: 콜백이 늦거나 오지 않아도 타임아웃 후 로그아웃 진행 ----------

    @Test
    fun `T-L6 slow storage - logout proceeds at the timeout and the late callback is harmless`() = runTest {
        val pipeline = pipeline()
        store.failAllCommits = true // 재시도(1초 + 5초) 후 실패
        val host = HostApp(this, pipeline)
        var uiWorkRanAt = -1L

        host.logout { currentTime }
        launch { // 기다리는 동안 메인 스레드가 다른 작업을 처리할 수 있는지
            kotlinx.coroutines.delay(500)
            uiWorkRanAt = currentTime
        }

        advanceTimeBy(1_999)
        assertFalse(host.sessionCleared)
        assertEquals(500L, uiWorkRanAt) // 메인 스레드는 막히지 않았다

        advanceTimeBy(2)
        assertTrue(host.sessionCleared)
        assertNull(host.analyticsResult)
        assertEquals(2_000L, host.logoutFinishedAt)

        advanceUntilIdle() // 6초에 Failure 콜백이 도착하지만 이미 끝난 흐름에는 영향이 없다
        assertTrue(pipeline.isDisabled())
        assertTrue(logger.errors.none { it.contains("IdentityCallback threw") })
    }

    @Test
    fun `T-L6 callback that never arrives - logout proceeds at the timeout`() = runTest {
        // 재시도 대기를 매우 길게 해서 "콜백이 오지 않는" 상황을 흉내 낸다 (예: 프로세스가 곧 종료됨).
        val pipeline = pipeline(retryDelays = listOf(3_600_000L, 3_600_000L))
        store.failAllCommits = true
        val host = HostApp(this, pipeline)

        host.logout { currentTime }
        advanceTimeBy(2_001)

        assertTrue(host.sessionCleared)
        assertNull(host.analyticsResult)
    }

    // ---------- T-L7: Failure를 받아도 로그아웃 진행 ----------

    @Test
    fun `T-L7 failure result - logout proceeds immediately`() = runTest {
        val pipeline = pipeline()
        store.failAllCommits = true
        pipeline.track("x", null)
        advanceUntilIdle() // 비활성화
        assertTrue(pipeline.isDisabled())
        val host = HostApp(this, pipeline)

        val start = currentTime
        host.logout { currentTime }
        runCurrent()

        assertTrue(host.sessionCleared)
        assertEquals(start, host.logoutFinishedAt) // 기다리지 않았다
        assertEquals(FailureReason.STORAGE_ERROR, (host.analyticsResult as IdentityResult.Failure).reason)
    }

    @Test
    fun `logout with healthy storage receives Success before clearing the session`() = runTest {
        val pipeline = pipeline()
        val host = HostApp(this, pipeline)
        host.logout { currentTime }
        advanceUntilIdle()
        assertTrue(host.sessionCleared)
        assertSame(IdentityResult.Success, host.analyticsResult)
    }

    // ---------- T-L5: 콜백이 예외를 던져도 SDK와 호출부에 영향 없음 ----------

    @Test
    fun `T-L5 a throwing callback does not affect the caller or later processing`() = runTest {
        val pipeline = pipeline()
        pipeline.reset { throw IllegalStateException("host bug") } // 호출 자체는 정상 반환
        pipeline.track("after", null)
        advanceUntilIdle()

        assertTrue(logger.errors.any { it.contains("IdentityCallback threw") })
        assertFalse(pipeline.isDisabled())
        assertEquals("after", delegate.messages().single().name)

        pipeline.track("later", null)
        advanceUntilIdle()
        assertEquals(listOf("after", "later"), delegate.messages().map { it.name })
    }
}
