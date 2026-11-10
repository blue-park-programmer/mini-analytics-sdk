@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.dduddupark.minianalytics

import android.content.Context
import android.os.Looper
import io.github.dduddupark.minianalytics.internal.AnalyticsClient
import io.github.dduddupark.minianalytics.internal.pipeline.MainThreadExecutor
import io.github.dduddupark.minianalytics.internal.pipeline.RecordingCallback
import io.github.dduddupark.minianalytics.internal.pipeline.RecordingLogger
import io.github.dduddupark.minianalytics.internal.pipeline.messages
import io.github.dduddupark.minianalytics.internal.storage.FaultyEventStore
import io.github.dduddupark.minianalytics.internal.storage.SqliteEventStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.TimeUnit

/** facade와 최소 SDK (설계 §2, 구현 계획 4단계). */
@RunWith(RobolectricTestRunner::class)
class MiniAnalyticsTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val logger = RecordingLogger()
    private val valid = Configuration.Builder("pk_test_1234567890", "https://example.com/v1/batch").build()

    @Before
    fun setUp() {
        MiniAnalytics.resetForTesting()
        MiniAnalytics.logger = logger
    }

    @After
    fun tearDown() {
        MiniAnalytics.resetForTesting()
    }

    private fun idleMainLooper() = shadowOf(Looper.getMainLooper()).idle()

    private fun awaitIdle() {
        val pipeline = MiniAnalytics.clientForTesting()!!.pipeline
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!pipeline.isIdle()) {
            if (System.nanoTime() > deadline) fail("pipeline did not become idle")
            Thread.sleep(5)
        }
        idleMainLooper()
    }

    // ---------- 초기화 전 (T-L3) ----------

    @Test
    fun `T-L3 reset and identify before initialize fail with NOT_INITIALIZED without delay`() {
        val reset = RecordingCallback()
        val identify = RecordingCallback()
        val invalidUser = RecordingCallback()

        MiniAnalytics.reset(reset)
        MiniAnalytics.identify("user", identify)
        MiniAnalytics.identify("", invalidUser) // 검증보다 NOT_INITIALIZED가 우선

        assertEquals(0, reset.calls) // 호출한 곳에서 직접 실행하지 않고 메인 스레드로 post
        idleMainLooper() // 시간을 진행하지 않고 대기 중인 메시지만 처리
        for (callback in listOf(reset, identify, invalidUser)) {
            assertEquals(FailureReason.NOT_INITIALIZED, (callback.results.single() as IdentityResult.Failure).reason)
        }
    }

    @Test
    fun `T-L3 logout flow is not blocked when the SDK was never initialized`() {
        var sessionCleared = false
        MiniAnalytics.reset { sessionCleared = true } // G-3: 결과와 관계없이 로그아웃 진행
        idleMainLooper()
        assertTrue(sessionCleared)
    }

    @Test
    fun `calls before initialize are dropped, not buffered`() {
        MiniAnalytics.track("before", null)
        MiniAnalytics.screen("before", null)
        MiniAnalytics.flush()
        assertEquals(3, logger.warnings.count { it.contains("has not been called") })

        assertEquals(InitializeResult.STARTED, MiniAnalytics.initialize(context, valid))
        MiniAnalytics.track("after", null)
        awaitIdle()

        assertEquals(listOf("after"), MiniAnalytics.clientForTesting()!!.store.messages().map { it.name })
    }

    // ---------- initialize 결과 (R-6', Q-4a) ----------

    @Test
    fun `null context or configuration returns INVALID_CONFIGURATION with distinct reasons`() {
        assertEquals(InitializeResult.INVALID_CONFIGURATION, MiniAnalytics.initialize(null, valid))
        assertTrue(logger.errors.last().contains("context is null"))
        assertFalse(logger.errors.last().contains("configuration is null"))

        assertEquals(InitializeResult.INVALID_CONFIGURATION, MiniAnalytics.initialize(context, null))
        assertTrue(logger.errors.last().contains("configuration is null"))
        assertFalse(logger.errors.last().contains("context is null"))

        assertEquals(InitializeResult.INVALID_CONFIGURATION, MiniAnalytics.initialize(null, null))
        assertTrue(logger.errors.last().contains("context is null, configuration is null"))

        assertNull(MiniAnalytics.clientForTesting())
    }

    @Test
    fun `invalid configuration is rejected and initialize can be retried`() {
        val http = Configuration.Builder("key", "http://example.com").build()
        assertEquals(InitializeResult.INVALID_CONFIGURATION, MiniAnalytics.initialize(context, http))
        assertTrue(logger.errors.last().contains("https"))
        assertNull(MiniAnalytics.clientForTesting())

        assertEquals(InitializeResult.STARTED, MiniAnalytics.initialize(context, valid))
    }

    @Test
    fun `second initialize with the same configuration is ignored silently`() {
        assertEquals(InitializeResult.STARTED, MiniAnalytics.initialize(context, valid))
        val sameValues = Configuration.Builder("pk_test_1234567890", "https://example.com/v1/batch").build()
        assertEquals(InitializeResult.ALREADY_INITIALIZED, MiniAnalytics.initialize(context, sameValues))
        assertTrue(logger.errors.isEmpty())
    }

    @Test
    fun `second initialize with a different configuration logs the differences with a masked key`() {
        MiniAnalytics.initialize(context, valid)
        val first = MiniAnalytics.clientForTesting()
        val other = Configuration.Builder("pk_live_0987654321", "https://other.example.com").flushAt(5).build()

        assertEquals(InitializeResult.ALREADY_INITIALIZED, MiniAnalytics.initialize(context, other))

        val message = logger.errors.single()
        assertTrue(message.contains("endpoint:"))
        assertTrue(message.contains("flushAt: 20 -> 5"))
        assertFalse(message.contains("pk_live_0987654321"))
        assertFalse(message.contains("pk_test_1234567890"))
        assertSame(first, MiniAnalytics.clientForTesting()) // 기존 인스턴스 유지
    }

    @Test
    fun `initialize never throws even if creating the client fails`() {
        MiniAnalytics.clientFactory = { _, _ -> throw IllegalStateException("factory bug") }
        assertEquals(InitializeResult.INVALID_CONFIGURATION, MiniAnalytics.initialize(context, valid))
        assertTrue(logger.errors.last().contains("failed unexpectedly"))
        assertNull(MiniAnalytics.clientForTesting())
        // 이후 호출도 예외 없이 "초기화 전"으로 처리된다.
        MiniAnalytics.track("e", null)
    }

    // ---------- 최소 SDK 동작 (수집 → 영구 저장) ----------

    @Test
    fun `initialized SDK persists events and identity to SQLite`() {
        MiniAnalytics.initialize(context, valid)
        MiniAnalytics.track("app_open", mapOf("cold" to true))
        MiniAnalytics.identify("user-1")
        MiniAnalytics.screen("Home")
        awaitIdle()

        val store = MiniAnalytics.clientForTesting()!!.store
        val messages = store.messages()
        assertEquals(listOf("track", "identify", "screen"), messages.map { it.type })
        assertEquals(listOf(null, "user-1", "user-1"), messages.map { it.userId })
        assertEquals("user-1", store.loadIdentity()!!.identity.userId)
    }

    @Test
    fun `identity callbacks from the facade arrive on the main looper after the commit`() {
        MiniAnalytics.initialize(context, valid)
        var onMainLooper = false
        val callback = RecordingCallback()
        MiniAnalytics.identify("user-1") { result ->
            onMainLooper = Looper.myLooper() == Looper.getMainLooper()
            callback.onComplete(result)
        }
        awaitIdle()
        assertSame(IdentityResult.Success, callback.results.single())
        assertTrue(onMainLooper)
    }

    @Test
    fun `T-I3 invalid userId through the facade fails with INVALID_ARGUMENT`() {
        MiniAnalytics.initialize(context, valid)
        val callback = RecordingCallback()
        MiniAnalytics.identify(null, callback)
        idleMainLooper()
        assertEquals(FailureReason.INVALID_ARGUMENT, (callback.results.single() as IdentityResult.Failure).reason)
    }

    @Test
    fun `flush after initialize does not throw`() {
        MiniAnalytics.initialize(context, valid)
        MiniAnalytics.flush()
    }

    // ---------- 호출 스레드에서 저장소를 건드리지 않음 (설계 원칙 1) ----------

    @Test
    fun `initialize and API calls on the main thread never touch the store or the disk`() {
        val dbName = "facade_main_thread.db"
        context.deleteDatabase(dbName)
        val store = FaultyEventStore(SqliteEventStore(context, dbName))
        val scheduler = TestCoroutineScheduler() // 진행시키기 전에는 storage coroutine이 실행되지 않는다
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scheduler))

        val result = MiniAnalytics.initializeInternal(context, valid, allowInsecureEndpoint = false) { _, config ->
            AnalyticsClient(config, store, scope, MainThreadExecutor(), logger)
        }
        MiniAnalytics.track("e", mapOf("k" to "v"))
        MiniAnalytics.screen("s")
        MiniAnalytics.identify("u", RecordingCallback())
        MiniAnalytics.reset(RecordingCallback())

        assertEquals(InitializeResult.STARTED, result)
        assertEquals(0, store.loadAttempts)
        assertEquals(0, store.commitAttempts)
        assertFalse(context.getDatabasePath(dbName).exists())

        // storage coroutine을 진행시키면 그때 DB가 열리고 기록된다.
        scheduler.advanceUntilIdle()
        assertTrue(context.getDatabasePath(dbName).exists())
        assertEquals(3, store.counts().total)
    }

    // ---------- 비활성 상태 (설계 §8.3) ----------

    @Test
    fun `initialize does not re-enable a disabled SDK`() {
        val store = FaultyEventStore(SqliteEventStore(context, "disabled.db")).apply { failAllCommits = true }
        val scheduler = TestCoroutineScheduler()
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scheduler))
        MiniAnalytics.initializeInternal(context, valid, allowInsecureEndpoint = false) { _, config ->
            AnalyticsClient(config, store, scope, MainThreadExecutor(), logger)
        }
        scheduler.advanceUntilIdle() // identity 생성 commit이 계속 실패 → 비활성화
        assertTrue(MiniAnalytics.clientForTesting()!!.pipeline.isDisabled())

        assertEquals(InitializeResult.ALREADY_INITIALIZED, MiniAnalytics.initialize(context, valid))
        assertTrue(MiniAnalytics.clientForTesting()!!.pipeline.isDisabled())

        val callback = RecordingCallback()
        MiniAnalytics.reset(callback)
        idleMainLooper()
        assertEquals(FailureReason.STORAGE_ERROR, (callback.results.single() as IdentityResult.Failure).reason)
    }
}
