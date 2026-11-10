package io.github.dduddupark.minianalytics.sample

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import io.github.dduddupark.minianalytics.IdentityResult
import io.github.dduddupark.minianalytics.MiniAnalytics

/**
 * 검증 도구(verify.py)가 `adb shell am start --es action ...`로 시나리오를 실행한다.
 * 모든 호출은 메인 스레드에서 한다 (StrictMode가 켜져 있다).
 */
class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handle()
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle()
    }

    private fun handle() {
        val action = intent.getStringExtra("action") ?: return
        val prefix = intent.getStringExtra("prefix") ?: "e"
        val count = intent.getIntExtra("count", 1)
        val invalidIndex = intent.getIntExtra("invalidIndex", -1)
        when (action) {
            "track" -> repeat(count) { i ->
                val properties = if (i == invalidIndex) mapOf("__invalid" to true) else mapOf("i" to i)
                MiniAnalytics.track("$prefix-$i", properties)
            }
            "screen" -> MiniAnalytics.screen("$prefix-screen", mapOf("from" to "sample"))
            "identify" -> MiniAnalytics.identify(intent.getStringExtra("userId")) { log("identify", it) }
            "reset" -> logout()
            "flush" -> MiniAnalytics.flush()
            "java" -> JavaUsage.run(prefix)
        }
        Log.i(SampleApplication.TAG, "action=$action prefix=$prefix count=$count done")
    }

    /** 통합 가이드 G-3: 결과를 기다리되 타임아웃을 두고, 결과와 관계없이 로그아웃을 진행한다. */
    private fun logout() {
        val handler = Handler(Looper.getMainLooper())
        var finished = false
        val proceed = { reason: String ->
            if (!finished) {
                finished = true
                Log.i(SampleApplication.TAG, "logout proceeded ($reason)")
            }
        }
        handler.postDelayed({ proceed("timeout") }, 2_000)
        MiniAnalytics.reset { result ->
            log("reset", result)
            proceed("callback")
        }
    }

    private fun log(call: String, result: IdentityResult) {
        Log.i(SampleApplication.TAG, "$call callback -> $result on main=${Looper.myLooper() == Looper.getMainLooper()}")
    }
}
