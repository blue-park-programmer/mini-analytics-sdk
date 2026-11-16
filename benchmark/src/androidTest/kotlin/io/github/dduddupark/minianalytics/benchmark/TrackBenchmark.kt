package io.github.dduddupark.minianalytics.benchmark

import androidx.benchmark.junit4.BenchmarkRule
import androidx.benchmark.junit4.measureRepeated
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.dduddupark.minianalytics.Configuration
import io.github.dduddupark.minianalytics.MiniAnalytics
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * `MiniAnalytics.track()` 호출 비용 (androidx.benchmark). SDK 공개 API만 쓴다.
 *
 * - `saturated_*`: 기다리지 않고 연속 호출한다. 저장 속도보다 빠르므로 메모리 큐가 상한(1,000)에 머물고,
 *   DB도 maxQueuedEvents(10,000)에 도달해 정리 비용이 저장 쪽에 생긴다. "포화 상태의 호출 비용"이다.
 * - `normal_*`: 50건마다 타이머를 멈추고 identify 콜백으로 앞선 명령이 처리되기를 기다린다.
 *   메모리 큐가 비어 있는 상태의 호출 비용이다. 타이머 정지·재개 자체의 오차가 있어 하네스(benchmark-host)와
 *   교차 확인용으로 쓴다.
 * 할당 수(allocationCount)는 androidx.benchmark가 프로세스 전체 기준으로 세므로 SDK 백그라운드 스레드의
 * 할당도 들어간다(호출 스레드만의 할당은 benchmark-host 하네스에서 따로 잰다).
 */
@RunWith(AndroidJUnit4::class)
class TrackBenchmark {

    @get:Rule
    val benchmarkRule = BenchmarkRule()

    private fun saturated(properties: Map<String, Any?>?) {
        benchmarkRule.measureRepeated {
            MiniAnalytics.track(EVENT, properties)
        }
    }

    private fun normal(properties: Map<String, Any?>?) {
        var n = 0
        benchmarkRule.measureRepeated {
            MiniAnalytics.track(EVENT, properties)
            if (++n % BURST == 0) runWithMeasurementDisabled { waitDrained() }
        }
    }

    @Test fun saturated_noProps() = saturated(null)
    @Test fun saturated_props5() = saturated(P5)
    @Test fun saturated_props20() = saturated(P20)
    @Test fun saturated_props4k() = saturated(P4K)

    @Test fun normal_noProps() = normal(null)
    @Test fun normal_props5() = normal(P5)
    @Test fun normal_props20() = normal(P20)
    @Test fun normal_props4k() = normal(P4K)

    companion object {
        private const val EVENT = "bench_event"
        private const val BURST = 50

        private val P5: Map<String, Any?> =
            mapOf("screen" to "home", "position" to 3, "price" to 12.5, "premium" to true, "tag" to "a")
        private val P20: Map<String, Any?> = (0 until 20).associate { i ->
            "key_$i" to when (i % 4) {
                0 -> "value_$i"
                1 -> i
                2 -> i * 1.5
                else -> i % 2 == 0
            }
        }
        private val P4K: Map<String, Any?> = mapOf("a" to 1, "b" to "x", "c" to 2.0, "d" to false, "blob" to "y".repeat(4_000))

        @JvmStatic
        @BeforeClass
        fun initializeSdk() {
            val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
            val configuration = Configuration.Builder("pk_bench", "https://127.0.0.1:9/v1/batch")
                .maxQueuedEvents(10_000)
                .build()
            MiniAnalytics.initialize(context, configuration)
            waitDrained()
        }

        fun waitDrained() {
            val latch = CountDownLatch(1)
            MiniAnalytics.identify("bench-user") { latch.countDown() }
            latch.await(60, TimeUnit.SECONDS)
        }
    }
}
