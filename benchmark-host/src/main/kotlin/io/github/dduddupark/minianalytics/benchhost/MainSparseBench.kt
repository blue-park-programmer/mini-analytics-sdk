package io.github.dduddupark.minianalytics.benchhost

import android.app.Activity
import android.os.Build
import android.os.Debug
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.FrameMetrics
import android.view.Window
import io.github.dduddupark.minianalytics.MiniAnalytics
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 메인 스레드 간헐 호출 (Step 8). SDK 공개 API만 쓴다.
 *  - main_sparse: 메인 Handler의 postDelayed로 [intervalMs]마다 track() 1건을 메인 스레드에서 호출한다.
 *  - main_sparse_control: 같은 방식으로 SDK를 부르지 않는 호스트 코드(속성 맵 복사 + UUID + 시각)를 실행한다.
 * UI 조건(ui): idle(정지 화면, 메인 스레드는 호출 외에 대부분 쉼) / anim(매 프레임 다시 그림, 메인 스레드가 프레임마다 동작).
 * 기록: 호출 경과 시간·스레드 CPU 시간, 메인 스레드 지연(예약 시각보다 늦게 실행된 시간),
 *       anim에서는 FrameMetrics(프레임 생성 시간, 기준 프레임 시간 초과 수). FrameMetrics는 프레임을 새로 요청하지 않는다.
 * 호출 자체의 지연과 실제 UI 프레임 영향은 다른 지표다. 프레임 영향의 최종 판단은 Macrobenchmark에서 한다.
 */
object MainSparseBench {
    private const val EVENT = "bench_event"
    private const val JIT_WARMUP_CALLS = 500

    private fun pct(values: LongArray): JSONObject {
        if (values.isEmpty()) return JSONObject().put("n", 0)
        val s = values.copyOf().also { it.sort() }
        fun p(q: Double) = s[((s.size - 1) * q).toInt()]
        return JSONObject().put("n", s.size).put("min", s.first()).put("p50", p(0.50)).put("p95", p(0.95))
            .put("p99", p(0.99)).put("max", s.last()).put("mean", s.average().toLong())
    }

    private fun waitDrained() {
        val latch = CountDownLatch(1)
        MiniAnalytics.identify("bench-user") { latch.countDown() }
        latch.await(60, TimeUnit.SECONDS)
    }

    @Suppress("DEPRECATION")
    fun run(activity: Activity, scenario: String, propsKind: String, measured: Int, warmup: Int,
            intervalMs: Long, ui: String): JSONObject {
        require(intervalMs > 0) { "main_sparse needs intervalMs > 0" }
        val sdk = scenario == "main_sparse"
        val properties = BenchHarness.props(propsKind)
        var sink = 0L
        fun control(): Int {
            val copy = HashMap<String, Any?>(properties ?: emptyMap())
            copy["id"] = UUID.randomUUID().toString()
            copy["ts"] = System.currentTimeMillis()
            return copy.size
        }
        val out = JSONObject().put("scenario", scenario).put("props", propsKind).put("measured", measured)
            .put("warmup", warmup).put("intervalMs", intervalMs).put("device", Build.MODEL).put("sdkInt", Build.VERSION.SDK_INT)
            .put("debuggable", (activity.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0)
            .put("initializeNanos", BenchApplication.initializeNanos)

        // JIT 예열 (sparse와 같은 조건). SDK 쪽은 50건 묶음 + 처리 대기.
        var calls = 0L
        if (sdk) {
            var w = 0
            while (w < JIT_WARMUP_CALLS) {
                repeat(50) { MiniAnalytics.track(EVENT, properties) }
                w += 50
                waitDrained()
            }
            calls += JIT_WARMUP_CALLS
        } else {
            repeat(JIT_WARMUP_CALLS) { sink += control() }
        }

        val wall = LongArray(measured)
        val cpu = LongArray(measured)
        val late = LongArray(measured)
        val main = Handler(Looper.getMainLooper())
        val done = CountDownLatch(1)
        val total = warmup + measured

        // 프레임 지표 (anim에서만, API 24+)
        val frameTotals = ArrayList<Long>()
        val metricsThread = HandlerThread("bench-frames").apply { start() }
        var listener: Window.OnFrameMetricsAvailableListener? = null
        val recordFrames = ui == "anim" && Build.VERSION.SDK_INT >= 24
        val measuring = java.util.concurrent.atomic.AtomicBoolean(false)

        val tick = object : Runnable {
            var i = 0
            var expected = 0L
            override fun run() {
                val start = System.nanoTime()
                val c = Debug.threadCpuTimeNanos()
                val a = System.nanoTime()
                if (sdk) MiniAnalytics.track(EVENT, properties) else sink += control()
                val d = System.nanoTime() - a
                val cd = Debug.threadCpuTimeNanos() - c
                if (i >= warmup) {
                    val k = i - warmup
                    wall[k] = d
                    cpu[k] = cd
                    late[k] = maxOf(0L, start - expected)
                    if (k == 0) measuring.set(true)
                }
                i++
                if (i < total) {
                    expected = System.nanoTime() + intervalMs * 1_000_000
                    main.postDelayed(this, intervalMs)
                } else {
                    measuring.set(false)
                    done.countDown()
                }
            }
        }

        main.post {
            if (recordFrames) {
                val l = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
                    if (measuring.get()) synchronized(frameTotals) { frameTotals += metrics.getMetric(FrameMetrics.TOTAL_DURATION) }
                }
                activity.window.addOnFrameMetricsAvailableListener(l, Handler(metricsThread.looper))
                listener = l
            }
            tick.expected = System.nanoTime() + intervalMs * 1_000_000
            main.postDelayed(tick, intervalMs)
        }
        val t0 = System.nanoTime()
        done.await(total * intervalMs / 1000 + 120, TimeUnit.SECONDS)
        val unregistered = CountDownLatch(1)
        main.post {
            listener?.let { activity.window.removeOnFrameMetricsAvailableListener(it) }
            unregistered.countDown()
        }
        unregistered.await(10, TimeUnit.SECONDS)
        metricsThread.quitSafely()
        calls += total

        out.put("measuredWallNanos", System.nanoTime() - t0)
        out.put("latencyNanos", pct(wall)).put("threadCpuNanos", pct(cpu))
            .put("wallMinusCpuNanos", pct(LongArray(measured) { maxOf(0L, wall[it] - cpu[it]) }))
            .put("mainLatenessNanos", pct(late))
        if (recordFrames) {
            @Suppress("DEPRECATION")
            val refresh = activity.windowManager.defaultDisplay.refreshRate
            val budget = (1_000_000_000L / refresh).toLong()
            val frames = synchronized(frameTotals) { frameTotals.toLongArray() }
            out.put("frames", JSONObject().put("refreshHz", refresh.toDouble()).put("budgetNanos", budget)
                .put("totalDuration", pct(frames))
                .put("overBudget", frames.count { it > budget }).put("over2xBudget", frames.count { it > 2 * budget }))
        }
        if (sdk) {
            waitDrained()
            val stored = StoreBench.countTracks(activity.applicationContext)
            out.put("trackCalls", calls).put("storedTracks", stored).put("notStored", if (stored >= 0) calls - stored else -1)
        }
        out.put("sink", sink % 2)
        return out
    }
}
