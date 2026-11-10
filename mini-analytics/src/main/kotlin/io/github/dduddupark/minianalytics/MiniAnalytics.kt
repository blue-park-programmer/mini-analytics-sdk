package io.github.dduddupark.minianalytics

import android.content.Context
import io.github.dduddupark.minianalytics.internal.AnalyticsClient
import io.github.dduddupark.minianalytics.internal.DefaultClientFactory
import io.github.dduddupark.minianalytics.internal.config.ConfigurationValidator
import io.github.dduddupark.minianalytics.internal.log.AndroidLogger
import io.github.dduddupark.minianalytics.internal.log.Logger
import io.github.dduddupark.minianalytics.internal.pipeline.CallbackDispatcher
import io.github.dduddupark.minianalytics.internal.pipeline.MainThreadExecutor

/**
 * mini-analytics SDK의 공개 진입점 (설계 §2).
 *
 * - 프로세스당 하나의 인스턴스로 동작한다. [initialize]를 `Application.onCreate()`에서 한 번 호출한다.
 * - 모든 함수는 메인 스레드에서 호출해도 디스크·네트워크 I/O를 하지 않는다.
 * - 공개 함수는 예외를 던지지 않는다. 내부에서 난 `Exception`은 로그로 남기고 호출을 무시한다.
 *   (`OutOfMemoryError` 같은 `Error`는 잡지 않는다. 설계 §8.4)
 * - Java 호출자가 `null`을 넘겨도 NPE가 나지 않는다 (설계 §2.4).
 */
public object MiniAnalytics {

    private val lock = Any()

    @Volatile
    private var client: AnalyticsClient? = null

    internal var logger: Logger = AndroidLogger

    /** 운영에서는 [DefaultClientFactory]. 테스트에서만 바꾼다. */
    internal var clientFactory: (Context, Configuration) -> AnalyticsClient = DefaultClientFactory

    /** 초기화 전 호출의 콜백 전달용. 필요할 때만 만든다. */
    private val notInitializedCallbacks: CallbackDispatcher by lazy {
        CallbackDispatcher(MainThreadExecutor(), logger)
    }

    /**
     * SDK를 초기화한다. 비동기 초기화(DB 열기, Identity 로드)를 **시작**하고 즉시 반환한다.
     *
     * 검증 순서: `context`/`configuration`의 null 검사 → 설정값 검증 → 이미 초기화되었는지 확인.
     *
     * @return 이 호출을 받아들였는지. 비동기 초기화의 성공 여부는 나타내지 않는다 (설계 §2.1).
     */
    @JvmStatic
    public fun initialize(context: Context?, configuration: Configuration?): InitializeResult =
        try {
            initializeInternal(context, configuration, allowInsecureEndpoint = false, clientFactory)
        } catch (e: Exception) {
            logger.error("initialize() failed unexpectedly; MiniAnalytics was not initialized", e)
            InitializeResult.INVALID_CONFIGURATION
        }

    /** 이벤트를 기록한다. 이름이 유효하지 않으면(null, 공백뿐, 256자 초과) 경고 후 무시한다. */
    @JvmStatic
    @JvmOverloads
    public fun track(event: String?, properties: Map<String, Any?>? = null) {
        guarded("track") { (client ?: return notInitialized("track")).track(event, properties) }
    }

    /** 화면 조회를 기록한다. `track()`과 같은 규칙을 따른다. */
    @JvmStatic
    @JvmOverloads
    public fun screen(name: String?, properties: Map<String, Any?>? = null) {
        guarded("screen") { (client ?: return notInitialized("screen")).screen(name, properties) }
    }

    /**
     * 사용자를 식별한다. 같은 `userId`여도 항상 identify 메시지를 만든다.
     * `userId`가 유효하지 않으면 Identity를 바꾸지 않고 [FailureReason.INVALID_ARGUMENT]를 전달한다.
     * 초기화 전이면 [FailureReason.NOT_INITIALIZED]를 전달한다 (검증보다 우선).
     */
    @JvmStatic
    @JvmOverloads
    public fun identify(userId: String?, callback: IdentityCallback? = null) {
        guarded("identify") {
            val current = client
            if (current == null) {
                notInitialized("identify", callback)
            } else {
                current.identify(userId, callback)
            }
        }
    }

    /**
     * `userId`를 지우고, 이미 익명이어도 새 `anonymousId`를 발급한다.
     * 로그아웃을 이 콜백에 의존시키지 말 것. 기다린다면 타임아웃을 둔다 (설계 §12 G-3).
     */
    @JvmStatic
    @JvmOverloads
    public fun reset(callback: IdentityCallback? = null) {
        guarded("reset") {
            val current = client
            if (current == null) notInitialized("reset", callback) else current.reset(callback)
        }
    }

    /** 전송을 요청한다. 완료를 보장하지 않는다. */
    @JvmStatic
    public fun flush() {
        guarded("flush") { (client ?: return notInitialized("flush")).flush() }
    }

    // ===================== 내부 =====================

    internal fun initializeInternal(
        context: Context?,
        configuration: Configuration?,
        allowInsecureEndpoint: Boolean,
        clientFactory: (Context, Configuration) -> AnalyticsClient,
    ): InitializeResult {
        val nullErrors = buildList {
            if (context == null) add("context is null")
            if (configuration == null) add("configuration is null")
        }
        if (nullErrors.isNotEmpty()) {
            logger.error("initialize() rejected: ${nullErrors.joinToString(", ")}")
            return InitializeResult.INVALID_CONFIGURATION
        }
        context!!
        configuration!!

        val errors = ConfigurationValidator.validate(configuration, allowInsecureEndpoint)
        if (errors.isNotEmpty()) {
            logger.error("initialize() rejected: invalid configuration: ${errors.joinToString("; ")}")
            return InitializeResult.INVALID_CONFIGURATION
        }

        synchronized(lock) {
            val existing = client
            if (existing != null) {
                val differences = existing.configuration.describeDifferences(configuration)
                if (differences.isNotEmpty()) {
                    logger.error(
                        "initialize() ignored: MiniAnalytics is already initialized with a different " +
                            "configuration. Differences: ${differences.joinToString("; ")}",
                    )
                }
                return InitializeResult.ALREADY_INITIALIZED
            }
            client = clientFactory(context.applicationContext ?: context, configuration)
            return InitializeResult.STARTED
        }
    }

    private fun notInitialized(call: String, callback: IdentityCallback? = null) {
        logger.warn("$call() ignored: MiniAnalytics.initialize() has not been called")
        if (callback != null) {
            notInitializedCallbacks.deliver(callback, IdentityResult.Failure(FailureReason.NOT_INITIALIZED, null))
        }
    }

    private inline fun guarded(call: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            logger.error("$call() failed unexpectedly and was ignored", e)
        }
    }

    /** 테스트 전용: 초기화 상태를 지운다. */
    internal fun resetForTesting() {
        synchronized(lock) {
            client?.close()
            client = null
        }
        logger = AndroidLogger
        clientFactory = DefaultClientFactory
    }

    internal fun clientForTesting(): AnalyticsClient? = client
}
