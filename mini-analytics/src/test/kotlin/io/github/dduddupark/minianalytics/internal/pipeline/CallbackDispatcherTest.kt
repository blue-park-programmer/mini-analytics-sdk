package io.github.dduddupark.minianalytics.internal.pipeline

import android.os.Looper
import io.github.dduddupark.minianalytics.FailureReason
import io.github.dduddupark.minianalytics.IdentityResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

@RunWith(RobolectricTestRunner::class)
class CallbackDispatcherTest {

    private val logger = RecordingLogger()

    @Test
    fun `callback is posted, not run inline`() {
        val main = ManualExecutor()
        val callback = RecordingCallback()
        CallbackDispatcher(main, logger).deliver(callback, IdentityResult.Success)
        assertEquals(0, callback.calls)
        main.runAll()
        assertSame(IdentityResult.Success, callback.results.single())
    }

    @Test
    fun `exception thrown by the host callback is contained and logged`() {
        val main = ManualExecutor()
        CallbackDispatcher(main, logger).deliver({ throw IllegalStateException("boom") }, IdentityResult.Success)
        main.runAll() // 예외가 밖으로 나오지 않는다
        assertEquals(1, logger.errors.size)
    }

    @Test
    fun `rejected post does not throw`() {
        val rejecting = Executor { throw RejectedExecutionException("main looper quitting") }
        CallbackDispatcher(rejecting, logger).deliver(RecordingCallback(), IdentityResult.Success)
        assertEquals(1, logger.errors.size)
    }

    @Test
    fun `null callback is a no-op`() {
        val main = ManualExecutor()
        CallbackDispatcher(main, logger).deliver(null, IdentityResult.Success)
        assertEquals(0, main.size)
    }

    @Test
    fun `once wrapper forwards only the first completion`() {
        val recording = RecordingCallback()
        val once = OnceIdentityCallback(recording)
        once.onComplete(IdentityResult.Success)
        once.onComplete(IdentityResult.Failure(FailureReason.STORAGE_ERROR, null))
        assertSame(IdentityResult.Success, recording.results.single())
    }

    @Test
    fun `main thread executor runs the callback on the main looper`() {
        val callback = RecordingCallback()
        var ranOnMainLooper = false
        val dispatcher = CallbackDispatcher(MainThreadExecutor(), logger)

        val background = Thread {
            dispatcher.deliver({ result ->
                ranOnMainLooper = Looper.myLooper() == Looper.getMainLooper()
                callback.onComplete(result)
            }, IdentityResult.Success)
        }
        background.start()
        background.join()

        assertEquals(0, callback.calls) // 메인 Looper가 처리하기 전에는 실행되지 않는다
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, callback.calls)
        assertTrue(ranOnMainLooper)
    }
}
