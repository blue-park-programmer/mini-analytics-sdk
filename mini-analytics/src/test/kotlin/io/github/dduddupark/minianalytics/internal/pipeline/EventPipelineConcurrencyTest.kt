package io.github.dduddupark.minianalytics.internal.pipeline

import io.github.dduddupark.minianalytics.internal.storage.InMemoryEventStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 실제 스레드 여러 개에서 동시에 호출할 때의 순서와 식별자 일관성 (설계 §11.1, R-1, R-3).
 * 실행 순서는 매번 달라지므로 특정 결과 대신 **항상 성립해야 하는 불변식**을 검사한다.
 */
@RunWith(RobolectricTestRunner::class)
class EventPipelineConcurrencyTest {

    private val threads = 8
    private val iterations = 100

    private fun runConcurrently(action: (EventPipeline, thread: Int, i: Int) -> Unit): List<StoredMessage> {
        val store = InMemoryEventStore()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
        try {
            val pipeline = EventPipeline(store, 10_000, scope, CallbackDispatcher(ManualExecutor(), RecordingLogger()), RecordingLogger())
            val start = CountDownLatch(1)
            val workers = (0 until threads).map { t ->
                Thread {
                    start.await()
                    repeat(iterations) { i -> action(pipeline, t, i) }
                }.apply { start() }
            }
            start.countDown()
            workers.forEach { it.join(TimeUnit.SECONDS.toMillis(10)) }
            awaitIdle(pipeline)
            return store.messages()
        } finally {
            scope.cancel()
        }
    }

    private fun awaitIdle(pipeline: EventPipeline) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!pipeline.isIdle()) {
            if (System.nanoTime() > deadline) fail("pipeline did not become idle")
            Thread.sleep(5)
        }
    }

    @Test
    fun `per-thread call order is preserved and every event carries the latest committed user`() {
        val messages = runConcurrently { pipeline, t, i ->
            if (i % 10 == 0) pipeline.identify("u$t-$i", null)
            pipeline.track("t$t-$i", null)
        }

        assertEquals(threads * iterations + threads * iterations / 10, messages.size)

        // 1) 같은 스레드에서 호출한 순서가 저장 순서와 같다.
        for (t in 0 until threads) {
            val ownTracks = messages.filter { it.type == "track" && it.name!!.startsWith("t$t-") }
            assertEquals((0 until iterations).map { "t$t-$it" }, ownTracks.map { it.name })
        }

        // 2) 각 track의 userId는 저장 순서상 바로 앞의 identify 메시지의 userId다 (식별자가 섞이지 않음).
        var lastUser: String? = null
        for (message in messages) {
            if (message.type == "identify") lastUser = message.userId
            else assertEquals("event ${message.name}", lastUser, message.userId)
        }

        // 3) 스레드 t의 track "t$t-$i"(i%10==0)는 같은 스레드의 identify "u$t-$i" 뒤에 있다.
        for (t in 0 until threads) {
            for (i in 0 until iterations step 10) {
                val identifyIndex = messages.indexOfFirst { it.type == "identify" && it.userId == "u$t-$i" }
                val trackIndex = messages.indexOfFirst { it.name == "t$t-$i" }
                assertTrue(identifyIndex in 0 until trackIndex)
            }
        }
        assertEquals(1, messages.map { it.anonymousId }.distinct().size)
    }

    @Test
    fun `with concurrent resets identity transitions are never mixed`() {
        // identify 행은 상한(100, R-4')을 넘으면 오래된 것부터 지워진다. 그러면 "사용자 변경 앞에는
        // identify 메시지가 있다"는 불변식을 검사할 수 없으므로, identify 수를 상한 아래로 둔다 (8 × 8 = 64).
        val messages = runConcurrently { pipeline, t, i ->
            when {
                i % 14 == 0 -> pipeline.identify("u$t-$i", null)
                i % 7 == 3 -> pipeline.reset(null)
                else -> pipeline.track("t$t-$i", null)
            }
        }

        // 인접한 두 메시지 a, b에 대해:
        // - anonymousId가 바뀌었다면 그 사이에 reset이 있었으므로, b가 track이면 userId는 null이어야 한다.
        // - anonymousId가 같고 b가 track이면 userId는 a의 userId와 같아야 한다 (identify 없이 바뀌지 않음).
        for ((a, b) in messages.zipWithNext()) {
            if (b.type != "track") continue
            if (a.anonymousId != b.anonymousId) {
                assertEquals("after reset: ${b.name}", null, b.userId)
            } else {
                assertEquals("same identity: ${b.name}", a.userId, b.userId)
            }
        }
        for (t in 0 until threads) {
            val own = messages.filter { it.type == "track" && it.name!!.startsWith("t$t-") }.map { it.name }
            val expected = (0 until iterations).filter { it % 14 != 0 && it % 7 != 3 }.map { "t$t-$it" }
            assertEquals(expected, own)
        }
    }
}
