package io.github.dduddupark.minianalytics.internal.upload

import io.github.dduddupark.minianalytics.Configuration
import io.github.dduddupark.minianalytics.internal.AnalyticsClient
import io.github.dduddupark.minianalytics.internal.pipeline.Counters
import io.github.dduddupark.minianalytics.internal.pipeline.ManualExecutor
import io.github.dduddupark.minianalytics.internal.pipeline.RecordingLogger
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
 * 실제 스레드와 실제 dispatcher (5단계 검증 항목 3, 4).
 * 운영과 같은 구성: storage = `Dispatchers.IO.limitedParallelism(1)`, network = `Dispatchers.IO`.
 */
@RunWith(RobolectricTestRunner::class)
class UploaderConcurrencyTest {

    private fun waitUntil(timeoutSeconds: Long = 10, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
        while (!condition()) {
            if (System.nanoTime() > deadline) fail("condition not met in time")
            Thread.sleep(5)
        }
    }

    // ---------- 3. 여러 flush가 동시에 실행되지 않음 ----------

    @Test
    fun `concurrent flush requests from many threads never run more than one upload at a time`() {
        val store = InMemoryEventStore()
        val storage = Dispatchers.IO.limitedParallelism(1)
        val scope = CoroutineScope(SupervisorJob() + storage)
        val release = CountDownLatch(1)
        val transport = FakeTransport()
        transport.onSend = { release.await(10, TimeUnit.SECONDS); Thread.sleep(2) }
        try {
            kotlinx.coroutines.runBlocking(storage) { store.addEvents("e", 250) }
            val uploader = Uploader(
                store, storage, Dispatchers.IO, scope, transport, EnvelopeBuilder({ "{}" }),
                flushAt = 1, flushIntervalMillis = 60_000, counters = Counters(), logger = RecordingLogger(),
                monotonicMillis = { System.nanoTime() / 1_000_000 },
            )
            waitUntil { transport.requests.size == 1 } // 첫 요청이 진행 중(블로킹)

            val threads = (0 until 8).map {
                Thread { repeat(200) { uploader.requestFlush() } }.apply { start() }
            }
            threads.forEach { it.join() }
            release.countDown()
            waitUntil { kotlinx.coroutines.runBlocking(storage) { store.counts().total } == 0 }

            assertEquals(1, transport.maxInFlight.get())
            // 1,600번의 flush 요청이 하나의 전송 루프로 합쳐졌다: 배치 수(100 + 100 + 50) 그대로
            assertEquals(listOf(100, 100, 50), transport.batchSizes())
        } finally {
            release.countDown()
            scope.cancel()
        }
    }

    // ---------- 4. 네트워크 요청 중에도 track()의 저장이 지연되지 않음 ----------

    @Test
    fun `track keeps being stored while an upload is blocked on the network`() {
        val store = InMemoryEventStore()
        val storage = Dispatchers.IO.limitedParallelism(1)
        val scope = CoroutineScope(SupervisorJob() + storage)
        val release = CountDownLatch(1)
        val transport = FakeTransport()
        transport.onSend = { release.await(20, TimeUnit.SECONDS) }
        val configuration = Configuration.Builder("k", "https://example.com").flushAt(1).build()
        val client = AnalyticsClient(
            configuration, store, scope, ManualExecutor(), RecordingLogger(),
            transport = transport, envelope = EnvelopeBuilder({ "{}" }),
        )
        try {
            client.track("first", null)
            waitUntil { transport.requests.size == 1 } // 전송이 네트워크에서 멈춰 있음

            val start = System.nanoTime()
            repeat(300) { client.track("t$it", null) }
            val callMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)

            // 전송이 멈춰 있는 동안에도 storage coroutine은 계속 commit한다.
            waitUntil(timeoutSeconds = 5) { client.pipeline.isIdle() }
            val stored = kotlinx.coroutines.runBlocking(storage) { store.counts().total }
            assertEquals(301, stored)
            assertEquals(1, transport.requests.size) // 여전히 첫 요청만 진행 중
            assertTrue("300 track() calls took ${callMillis}ms", callMillis < 1_000)
        } finally {
            release.countDown()
            client.close()
        }
    }
}
