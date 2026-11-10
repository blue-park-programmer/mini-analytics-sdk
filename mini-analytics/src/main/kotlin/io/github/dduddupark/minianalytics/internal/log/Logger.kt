package io.github.dduddupark.minianalytics.internal.log

import android.util.Log

/** SDK 내부 로그. 테스트에서는 기록용 구현으로 바꾼다. 구현은 예외를 던지지 않아야 한다. */
internal interface Logger {
    fun debug(message: String)
    fun warn(message: String, throwable: Throwable? = null)
    fun error(message: String, throwable: Throwable? = null)
}

internal object AndroidLogger : Logger {
    private const val TAG = "MiniAnalytics"

    override fun debug(message: String) {
        Log.d(TAG, message)
    }

    override fun warn(message: String, throwable: Throwable?) {
        Log.w(TAG, message, throwable)
    }

    override fun error(message: String, throwable: Throwable?) {
        Log.e(TAG, message, throwable)
    }
}
