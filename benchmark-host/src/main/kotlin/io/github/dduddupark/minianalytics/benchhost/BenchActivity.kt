package io.github.dduddupark.minianalytics.benchhost

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.View
import io.github.dduddupark.minianalytics.Configuration
import io.github.dduddupark.minianalytics.MiniAnalytics
import org.json.JSONObject

/**
 * `am start -n <pkg>/.BenchActivity --es scenario <이름> ...` 로 한 번 실행한다. 결과는 logcat `MiniAnalyticsBench`
 * 태그에 JSON 한 줄로 남긴다. 측정은 전용 스레드(`bench-caller`)에서 지휘하고, main_sparse 계열만 메인 스레드에서 호출한다.
 *
 * SDK 설정 extra: `endpoint`(dead|lan), `cap`(maxQueuedEvents), `flushAt`, `flushIntervalMs`.
 * UI extra: `ui`(none|idle|anim). anim은 매 프레임 다시 그리는 View(실제로 UI가 동작하는 조건)다.
 */
class BenchActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scenario = intent.getStringExtra("scenario") ?: "normal"
        val props = intent.getStringExtra("props") ?: "none"
        val measured = intent.getIntExtra("measured", 1_000)
        val warmup = intent.getIntExtra("warmup", 500)
        val intervalMs = intent.getIntExtra("interval", 0).toLong()
        val ui = intent.getStringExtra("ui") ?: "none"

        when (ui) {
            "anim" -> setContentView(AnimView(this))
            "idle" -> setContentView(View(this).apply { setBackgroundColor(Color.DKGRAY) })
        }
        initializeSdk()

        Thread({
            val result = try {
                when {
                    scenario.startsWith("main_sparse") ->
                        MainSparseBench.run(this, scenario, props, measured, warmup, intervalMs, ui)
                    scenario.startsWith("store_") ->
                        StoreBench.run(applicationContext, scenario, intent.getIntExtra("cap", 10_000))
                    else -> BenchHarness.run(applicationContext, scenario, props, measured, warmup, intervalMs)
                }
            } catch (e: Throwable) {
                JSONObject().put("scenario", scenario).put("props", props).put("error", e.toString())
            }
            result.put("ui", ui).put("endpointMode", intent.getStringExtra("endpoint") ?: "dead")
            Log.i(TAG, "RESULT " + result.toString())
            Log.i(TAG, "DONE")
            runOnUiThread { finish() }
        }, "bench-caller").start()
    }

    private fun initializeSdk() {
        val lan = intent.getStringExtra("endpoint") == "lan"
        // dead: 연결이 즉시 거부되는 주소(업로드가 측정에 끼지 않음). lan: 로컬 검증 서버(측정 뒤 업로드 검증용).
        val endpoint = if (lan) BuildConfig.BENCH_ENDPOINT else DEAD_ENDPOINT
        val apiKey = if (lan) "pk_test" else "pk_bench" // 검증 서버(verify.py)의 API 키
        val builder = Configuration.Builder(apiKey, endpoint)
            .maxQueuedEvents(intent.getIntExtra("cap", 10_000))
        if (intent.hasExtra("flushAt")) builder.flushAt(intent.getIntExtra("flushAt", 20))
        if (intent.hasExtra("flushIntervalMs")) builder.flushIntervalMillis(intent.getLongExtra("flushIntervalMs", 30_000L))
        val t0 = SystemClock.elapsedRealtimeNanos()
        BenchApplication.initializeResult = MiniAnalytics.initialize(applicationContext, builder.build()).toString()
        BenchApplication.initializeNanos = SystemClock.elapsedRealtimeNanos() - t0
    }

    /** 매 프레임 다시 그리는 View. 메인 스레드가 프레임마다 깨어 있는 "UI 동작 중" 조건을 만든다. */
    private class AnimView(context: Context) : View(context) {
        private val paint = Paint().apply { color = Color.CYAN }
        private var frame = 0

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            frame++
            canvas.drawColor(Color.BLACK)
            val x = (frame * 7 % width.coerceAtLeast(1)).toFloat()
            canvas.drawRect(x, height / 3f, x + 120f, height / 3f + 120f, paint)
            postInvalidateOnAnimation()
        }
    }

    companion object {
        const val TAG = "MiniAnalyticsBench"
        const val DEAD_ENDPOINT = "https://127.0.0.1:9/v1/batch"
    }
}
