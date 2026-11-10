package io.github.dduddupark.minianalytics.internal

import android.content.Context
import io.github.dduddupark.minianalytics.Configuration
import io.github.dduddupark.minianalytics.IdentityCallback
import io.github.dduddupark.minianalytics.internal.log.Logger
import io.github.dduddupark.minianalytics.internal.pipeline.CallbackDispatcher
import io.github.dduddupark.minianalytics.internal.pipeline.EventPipeline
import io.github.dduddupark.minianalytics.internal.pipeline.MainThreadExecutor
import io.github.dduddupark.minianalytics.internal.storage.EventStore
import io.github.dduddupark.minianalytics.internal.storage.SqliteEventStore
import io.github.dduddupark.minianalytics.internal.upload.Backoff
import io.github.dduddupark.minianalytics.internal.upload.EnvelopeBuilder
import io.github.dduddupark.minianalytics.internal.upload.HttpUrlConnectionTransport
import io.github.dduddupark.minianalytics.internal.upload.Transport
import io.github.dduddupark.minianalytics.internal.upload.Uploader
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.Executor

/**
 * 초기화된 SDK 인스턴스 (설계 §2.1, Q19). `MiniAnalytics` facade가 위임한다.
 * 테스트에서는 의존성을 주입해서 직접 만든다. 운영에서는 [create]를 쓴다.
 *
 * 생성자는 디스크·네트워크 I/O를 하지 않는다.
 *
 * @param scope storage coroutine과 Uploader가 함께 쓰는 scope. dispatcher는 **storage dispatcher**
 *   (한 번에 하나만 실행)여야 하며, Uploader의 DB 작업도 이 dispatcher에서 한다.
 * @param transport null이면 전송하지 않는다 (4단계 테스트용).
 */
internal class AnalyticsClient(
    val configuration: Configuration,
    val store: EventStore,
    private val scope: CoroutineScope,
    callbackExecutor: Executor,
    private val logger: Logger,
    transport: Transport? = null,
    envelope: EnvelopeBuilder? = null,
    networkDispatcher: CoroutineDispatcher = Dispatchers.IO,
    monotonicMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    backoff: Backoff = Backoff(),
) {
    @Volatile
    private var uploader: Uploader? = null

    val pipeline = EventPipeline(
        store = store,
        maxQueuedEvents = configuration.maxQueuedEvents,
        scope = scope,
        callbacks = CallbackDispatcher(callbackExecutor, logger),
        logger = logger,
        onCommitted = { counts -> uploader?.onStoreCounts(counts) },
    )

    init {
        if (transport != null && envelope != null) {
            val storageDispatcher = scope.coroutineContext[CoroutineDispatcher]
                ?: error("scope must have a storage dispatcher")
            uploader = Uploader(
                store = store,
                storageDispatcher = storageDispatcher,
                networkDispatcher = networkDispatcher,
                scope = scope,
                transport = transport,
                envelope = envelope,
                flushAt = configuration.flushAt,
                flushIntervalMillis = configuration.flushIntervalMillis,
                counters = pipeline.counters,
                logger = logger,
                monotonicMillis = monotonicMillis,
                backoff = backoff,
                trustInvalidEventResponses = configuration.trustInvalidEventResponses,
            )
            pipeline.onDisabled = { uploader?.stop() }
            if (pipeline.isDisabled()) uploader?.stop()
        }
    }

    fun track(event: String?, properties: Map<String, Any?>?) = pipeline.track(event, properties)

    fun screen(name: String?, properties: Map<String, Any?>?) = pipeline.screen(name, properties)

    fun identify(userId: String?, callback: IdentityCallback?) = pipeline.identify(userId, callback)

    fun reset(callback: IdentityCallback?) = pipeline.reset(callback)

    /** 비동기 전송 요청. 완료를 보장하지 않는다 (설계 §2.2). */
    fun flush() {
        if (pipeline.isDisabled()) {
            pipeline.counters.droppedStorageError.incrementAndGet()
            return
        }
        uploader?.requestFlush() ?: logger.debug("flush() ignored: uploading is not configured")
    }

    /** 테스트 전용: coroutine을 멈추고 DB를 닫는다. 운영에서는 프로세스가 끝날 때까지 유지한다. */
    fun close() {
        uploader?.stop()
        scope.cancel()
        store.close()
    }

    companion object {
        /** 운영용 구성. 메인 스레드에서 호출해도 I/O를 하지 않는다. */
        fun create(
            context: Context,
            configuration: Configuration,
            logger: Logger,
            allowInsecureEndpoint: Boolean = false,
        ): AnalyticsClient {
            val scope = CoroutineScope(
                SupervisorJob() + Dispatchers.IO.limitedParallelism(1) + CoroutineName("MiniAnalytics"),
            )
            val client = AnalyticsClient(
                configuration = configuration,
                store = SqliteEventStore(context),
                scope = scope,
                callbackExecutor = MainThreadExecutor(),
                logger = logger,
                transport = HttpUrlConnectionTransport(
                    endpoint = configuration.endpoint!!,
                    apiKey = configuration.apiKey!!,
                    userAgent = "${BuildInfo.LIBRARY_NAME}/${BuildInfo.LIBRARY_VERSION}",
                    allowInsecure = allowInsecureEndpoint,
                ),
                envelope = EnvelopeBuilder({ EnvelopeBuilder.androidContextJson(context) }),
            )
            // 메인 프로세스 확인은 Android 9 미만에서 파일을 읽으므로 백그라운드에서 한다.
            scope.launch {
                if (ProcessInfo.isMainProcess(context) == false) {
                    logger.warn(
                        "MiniAnalytics was initialized outside the main process " +
                            "(${ProcessInfo.currentProcessName()}). Multi-process use is not supported.",
                    )
                }
            }
            return client
        }
    }
}
