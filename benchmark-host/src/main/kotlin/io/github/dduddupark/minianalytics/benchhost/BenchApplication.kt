package io.github.dduddupark.minianalytics.benchhost

import android.app.Application

/**
 * SDK 초기화는 [BenchActivity.onCreate](메인 스레드)에서 한다. 시나리오마다 설정(endpoint, 상한, flush)을
 * 바꾸기 위해서다(실행마다 새 프로세스라 설정이 섞이지 않는다). 통합 가이드 G-1(Application.onCreate)과 다른 점은
 * 측정 앱에만 해당한다.
 */
class BenchApplication : Application() {
    companion object {
        var initializeResult: String = "?"
        var initializeNanos: Long = -1
    }
}
