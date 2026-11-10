package io.github.dduddupark.minianalytics.internal.pipeline

import io.github.dduddupark.minianalytics.IdentityCallback
import io.github.dduddupark.minianalytics.IdentityResult
import io.github.dduddupark.minianalytics.internal.log.Logger
import io.github.dduddupark.minianalytics.internal.storage.EventStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import org.json.JSONObject
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger

/** 로그를 기록만 하는 Logger. */
internal class RecordingLogger : Logger {
    val warnings = CopyOnWriteArrayList<String>()
    val errors = CopyOnWriteArrayList<String>()

    override fun debug(message: String) = Unit
    override fun warn(message: String, throwable: Throwable?) {
        warnings += message
    }

    override fun error(message: String, throwable: Throwable?) {
        errors += message
    }
}

/**
 * 메인 Looper를 흉내 내는 실행기. post된 작업은 [runAll]을 호출할 때까지 실행되지 않는다.
 * 그래서 "콜백은 호출한 곳에서 직접 실행되지 않고 post된다"를 검증할 수 있다.
 */
internal class ManualExecutor : Executor {
    private val queue = ConcurrentLinkedQueue<Runnable>()
    val size: Int get() = queue.size

    override fun execute(command: Runnable) {
        queue += command
    }

    fun runAll() {
        while (true) (queue.poll() ?: return).run()
    }
}

/** 콜백 결과를 기록한다. 같은 콜백이 두 번 호출되면 [calls]가 2가 된다. */
internal class RecordingCallback : IdentityCallback {
    val results = CopyOnWriteArrayList<IdentityResult>()
    val calls: Int get() = results.size

    override fun onComplete(result: IdentityResult) {
        results += result
    }
}

/** "id-1", "id-2", … 를 차례로 만든다. */
internal class IdSequence(private val prefix: String = "id-") : () -> String {
    private val next = AtomicInteger(1)
    override fun invoke(): String = prefix + next.getAndIncrement()
}

/** 저장된 메시지를 읽기 쉬운 형태로 바꾼다 (org.json → Robolectric 필요). */
internal data class StoredMessage(
    val type: String,
    val name: String?,
    val messageId: String,
    val userId: String?,
    val anonymousId: String,
    val timestamp: String,
    val properties: JSONObject?,
)

internal fun EventStore.messages(): List<StoredMessage> =
    readBatch(Int.MAX_VALUE, Int.MAX_VALUE).map { stored ->
        val json = JSONObject(stored.payload)
        StoredMessage(
            type = json.getString("type"),
            name = json.optString("event", "").ifEmpty { json.optString("name", "") }.ifEmpty { null },
            messageId = json.getString("messageId"),
            userId = if (json.isNull("userId")) null else json.getString("userId"),
            anonymousId = json.getString("anonymousId"),
            timestamp = json.getString("timestamp"),
            properties = json.optJSONObject("properties"),
        )
    }

/**
 * 파이프라인용 scope. 테스트 스케줄러를 공유하는 **foreground** dispatcher를 쓴다.
 * `backgroundScope`의 작업은 `advanceUntilIdle()`이 실행하지 않으므로 쓰지 않는다.
 * 테스트가 끝나면(= backgroundScope가 취소되면) 함께 취소된다.
 */
internal fun TestScope.pipelineScope(): CoroutineScope {
    val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
    backgroundScope.coroutineContext.job.invokeOnCompletion { scope.cancel() }
    return scope
}
