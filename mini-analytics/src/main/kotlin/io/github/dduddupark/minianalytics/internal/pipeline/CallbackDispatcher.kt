package io.github.dduddupark.minianalytics.internal.pipeline

import android.os.Handler
import android.os.Looper
import io.github.dduddupark.minianalytics.IdentityCallback
import io.github.dduddupark.minianalytics.IdentityResult
import io.github.dduddupark.minianalytics.internal.log.Logger
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/**
 * `IdentityCallback` 전달 (설계 §2.3, Q18, Q21).
 *
 * - 콜백은 항상 [executor]로 **post**한다. 호출한 스레드(SDK의 lock 안이나 storage coroutine)에서
 *   직접 실행하지 않는다. 운영에서는 [MainThreadExecutor]를 쓴다.
 * - 콜백 안에서 난 예외는 잡아서 로그로 남긴다. SDK 상태에는 영향이 없다.
 * - post 자체가 실패해도 예외를 밖으로 던지지 않는다.
 */
internal class CallbackDispatcher(
    private val executor: Executor,
    private val logger: Logger,
) {
    fun deliver(callback: IdentityCallback?, result: IdentityResult) {
        if (callback == null) return
        try {
            executor.execute {
                try {
                    callback.onComplete(result)
                } catch (e: Exception) {
                    logger.error("IdentityCallback threw an exception; it was ignored", e)
                }
            }
        } catch (e: Exception) {
            logger.error("Failed to post IdentityCallback", e)
        }
    }
}

/**
 * 감싼 콜백을 **최대 한 번만** 호출한다 (Q21 exactly-once의 방어 장치).
 * 파이프라인이 명령을 만들 때 감싸므로, 어느 경로(성공, 실패, 비활성화)로 완료되든 한 번만 호출된다.
 */
internal class OnceIdentityCallback(private val delegate: IdentityCallback) : IdentityCallback {
    private val completed = AtomicBoolean(false)

    override fun onComplete(result: IdentityResult) {
        if (completed.compareAndSet(false, true)) delegate.onComplete(result)
    }
}

/** 메인 Looper로 post한다. `Dispatchers.Main`(kotlinx-coroutines-android)을 쓰지 않는다 (설계 §10). */
internal class MainThreadExecutor : Executor {
    private val handler = Handler(Looper.getMainLooper())

    override fun execute(command: Runnable) {
        if (!handler.post(command)) throw IllegalStateException("Main looper is not accepting messages")
    }
}
