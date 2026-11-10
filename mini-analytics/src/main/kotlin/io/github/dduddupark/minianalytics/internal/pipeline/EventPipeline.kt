package io.github.dduddupark.minianalytics.internal.pipeline

import io.github.dduddupark.minianalytics.FailureReason
import io.github.dduddupark.minianalytics.IdentityCallback
import io.github.dduddupark.minianalytics.IdentityResult
import io.github.dduddupark.minianalytics.internal.log.Logger
import io.github.dduddupark.minianalytics.internal.model.Command
import io.github.dduddupark.minianalytics.internal.model.Identity
import io.github.dduddupark.minianalytics.internal.model.NameValidator
import io.github.dduddupark.minianalytics.internal.properties.PropertiesSanitizer
import io.github.dduddupark.minianalytics.internal.serialization.EventSerializer
import io.github.dduddupark.minianalytics.internal.serialization.Iso8601UtcFormatter
import io.github.dduddupark.minianalytics.internal.serialization.SerializationResult
import io.github.dduddupark.minianalytics.internal.storage.CommitResult
import io.github.dduddupark.minianalytics.internal.storage.EventStore
import io.github.dduddupark.minianalytics.internal.storage.NewEvent
import io.github.dduddupark.minianalytics.internal.storage.StoreCounts
import io.github.dduddupark.minianalytics.internal.storage.StoreLimits
import io.github.dduddupark.minianalytics.internal.storage.StoreTransaction
import io.github.dduddupark.minianalytics.internal.storage.StoredIdentity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 명령 큐와 storage coroutine (설계 §4.2, §4.3, §8, R-1, R-7'').
 *
 * **호출 스레드 (lock 밖)**: 이름 검증, properties 복사, `messageId`·`timestamp`·새 `anonymousId` 생성.
 * **호출 스레드 (lock 안)**: 큐에 넣기와 메모리 상한 적용만 한다. I/O는 하지 않는다.
 * **storage coroutine ([scope]의 단일 coroutine)**: Identity를 독점 소유한다. 명령을 순서대로 적용해서
 * 트랜잭션 하나로 commit하고, commit이 성공했을 때만 `current`를 바꾼다.
 *
 * [scope]는 한 번에 하나만 실행되는 dispatcher를 써야 한다 (운영: `Dispatchers.IO.limitedParallelism(1)`).
 */
internal class EventPipeline(
    private val store: EventStore,
    maxQueuedEvents: Int,
    scope: CoroutineScope,
    private val callbacks: CallbackDispatcher,
    private val logger: Logger,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val retryDelaysMillis: List<Long> = DEFAULT_RETRY_DELAYS_MILLIS,
    private val maxCommandsPerTransaction: Int = DEFAULT_MAX_COMMANDS_PER_TRANSACTION,
    private val memoryQueueLimit: Int = DEFAULT_MEMORY_QUEUE_LIMIT,
    private val reclaimFraction: Double = DEFAULT_RECLAIM_FRACTION,
    private val isStorageFull: (Throwable) -> Boolean = ::isSqliteFull,
    private val onCommitted: ((StoreCounts) -> Unit)? = null,
    private val fatalErrorHandler: (Throwable) -> Unit = ::forwardToUncaughtExceptionHandler,
) {
    /** 비활성화될 때 한 번 호출된다 (Uploader 정지용, 설계 §8.3). */
    @Volatile
    var onDisabled: (() -> Unit)? = null

    val counters = Counters()

    private val limits = StoreLimits(maxQueuedEvents)

    // ---------- lock으로 보호하는 상태 ----------
    private val lock = Any()
    private val pending = ArrayDeque<Command>()
    private var pendingMessageCount = 0 // pending 안의 Track/Screen 수
    private var inFlight: List<Command> = emptyList() // storage coroutine이 commit 중인 명령
    private var disabled = false
    private val unexpectedFailureHandled = AtomicBoolean(false)

    private val storageSignal = Channel<Unit>(Channel.CONFLATED)

    // ---------- storage coroutine만 접근하는 상태 ----------
    private var current: StoredIdentity? = null

    init {
        // 마지막 방어선 (설계 §8.4, Q-4b): 아래 try/catch를 벗어난 실패도 여기서 비활성화로 이어진다.
        val lastResort = CoroutineExceptionHandler { _, error -> onUnexpectedFailure(error) }
        scope.launch(lastResort) {
            try {
                runStorageLoop()
            } catch (e: CancellationException) {
                throw e
            } catch (error: Throwable) {
                // 재시도 경계(withStorageRetry) 밖에서 난 실패: 같은 스레드에서 바로 비활성화한다.
                failUnexpectedly(error)
                throw error
            }
        }
    }

    // ===================== 호출 스레드 =====================

    fun track(event: String?, properties: Map<*, *>?) = enqueueMessage(isScreen = false, event, properties)

    fun screen(name: String?, properties: Map<*, *>?) = enqueueMessage(isScreen = true, name, properties)

    fun identify(userId: String?, callback: IdentityCallback?) {
        val once = callback?.let(::OnceIdentityCallback)
        if (NameValidator.validate(userId) != NameValidator.Result.VALID) {
            logger.warn("identify() ignored: userId must be 1..${NameValidator.MAX_NAME_LENGTH} non-blank chars")
            callbacks.deliver(once, IdentityResult.Failure(FailureReason.INVALID_ARGUMENT, null))
            return
        }
        enqueue(Command.Identify(newId(), clock(), userId!!, once))
    }

    fun reset(callback: IdentityCallback?) {
        enqueue(Command.Reset(newId(), callback?.let(::OnceIdentityCallback)))
    }

    /** 테스트용: 큐와 commit 중인 명령이 모두 없는지. */
    fun isIdle(): Boolean = synchronized(lock) { pending.isEmpty() && inFlight.isEmpty() }

    fun isDisabled(): Boolean = synchronized(lock) { disabled }

    private fun enqueueMessage(isScreen: Boolean, name: String?, properties: Map<*, *>?) {
        val kind = if (isScreen) "screen" else "track"
        if (NameValidator.validate(name) != NameValidator.Result.VALID) {
            logger.warn("$kind() ignored: name must be 1..${NameValidator.MAX_NAME_LENGTH} non-blank chars")
            return
        }
        val sanitized = PropertiesSanitizer.sanitize(properties)
        if (sanitized.isDropped) {
            counters.propertiesDropped.incrementAndGet()
            logger.warn("$kind('$name'): all properties dropped (${sanitized.dropReason})")
        } else if (sanitized.isSanitized) {
            counters.propertiesSanitized.incrementAndGet()
            logger.warn(
                "$kind('$name'): ${sanitized.excludedCount} property value(s) excluded, " +
                    "e.g. ${sanitized.excludedSamples.joinToString()}",
            )
        }
        val command = if (isScreen) {
            Command.Screen(newId(), clock(), name!!, sanitized.values)
        } else {
            Command.Track(newId(), clock(), name!!, sanitized.values)
        }
        enqueue(command)
    }

    private fun enqueue(command: Command) {
        var accepted = false
        var dropped: Command? = null
        synchronized(lock) {
            if (!disabled) {
                accepted = true
                pending.addLast(command)
                if (command.isDroppable()) {
                    pendingMessageCount++
                    if (pendingMessageCount > memoryQueueLimit) dropped = removeOldestDroppableLocked()
                }
            }
        }
        if (!accepted) {
            // 비활성 상태 (설계 §8.3): 메시지는 버리고, Identity 콜백에는 즉시 실패를 알린다.
            counters.droppedStorageError.incrementAndGet()
            callbacks.deliver(command.callback(), IdentityResult.Failure(FailureReason.STORAGE_ERROR, null))
            return
        }
        if (dropped != null) counters.droppedInMemory.incrementAndGet()
        storageSignal.trySend(Unit)
    }

    private fun removeOldestDroppableLocked(): Command? {
        val index = pending.indexOfFirst { it.isDroppable() }
        if (index < 0) return null
        pendingMessageCount--
        return pending.removeAt(index)
    }

    // ===================== storage coroutine =====================

    private suspend fun runStorageLoop() {
        current = withStorageRetry("load identity") { loadOrCreateIdentity() } ?: return
        for (signal in storageSignal) {
            while (true) {
                val batch = takeBatch()
                if (batch.isEmpty()) break
                var prepared: PreparedBatch? = null
                val result = withStorageRetry("commit") {
                    // 준비(직렬화)는 순수 계산이다. 재시도해도 같은 결과가 나온다.
                    val p = prepare(batch)
                    prepared = p
                    store.commit(p.transaction)
                } ?: return
                onCommitted(batch, prepared!!, result)
            }
        }
    }

    private fun loadOrCreateIdentity(): StoredIdentity =
        store.loadIdentity() ?: StoredIdentity(Identity(newId(), null), version = 1).also {
            store.commit(StoreTransaction(emptyList(), it, limits))
        }

    /** 큐 맨 앞에서 최대 K개를 꺼내 commit 중 목록으로 옮긴다. 이 명령들은 메모리 상한으로 버려지지 않는다. */
    private fun takeBatch(): List<Command> = synchronized(lock) {
        if (disabled) return emptyList()
        val count = minOf(maxCommandsPerTransaction, pending.size)
        val batch = ArrayList<Command>(count)
        repeat(count) {
            val command = pending.removeFirst()
            if (command.isDroppable()) pendingMessageCount--
            batch += command
        }
        inFlight = batch
        batch
    }

    private class PreparedBatch(
        val transaction: StoreTransaction,
        val next: StoredIdentity,
        val oversized: Int,
        val invalid: Int,
    )

    private fun prepare(batch: List<Command>): PreparedBatch {
        val base = current!!
        val serializer = EventSerializer(Iso8601UtcFormatter()) // 트랜잭션 단위로 생성 (R-11)
        var identity = base.identity
        var version = base.version
        var oversized = 0
        var invalid = 0
        val events = ArrayList<NewEvent>()

        fun add(message: Command.Message) {
            when (val result = serializer.serialize(message, identity)) {
                is SerializationResult.Success ->
                    events += NewEvent(message.messageId, message.type, result.json, result.sizeBytes)
                is SerializationResult.Oversized -> oversized++
                is SerializationResult.Invalid -> invalid++
            }
        }

        for (command in batch) {
            when (command) {
                is Command.Track, is Command.Screen -> add(command as Command.Message)
                is Command.Identify -> {
                    identity = identity.copy(userId = command.userId)
                    version++
                    add(command)
                }
                is Command.Reset -> {
                    identity = Identity(anonymousId = command.newAnonymousId, userId = null)
                    version++
                }
            }
        }
        val next = StoredIdentity(identity, version)
        val identityToStore = if (version != base.version) next else null
        return PreparedBatch(StoreTransaction(events, identityToStore, limits), next, oversized, invalid)
    }

    private fun onCommitted(batch: List<Command>, prepared: PreparedBatch, result: CommitResult) {
        synchronized(lock) { inFlight = emptyList() }
        current = prepared.next
        counters.evictedFromStore.addAndGet(result.evictedEvents.toLong())
        counters.evictedIdentify.addAndGet(result.evictedIdentifies.toLong())
        counters.droppedOversized.addAndGet(prepared.oversized.toLong())
        counters.droppedInvalid.addAndGet(prepared.invalid.toLong())
        for (command in batch) callbacks.deliver(command.callback(), IdentityResult.Success)

        val hook = onCommitted ?: return
        try {
            hook(store.counts())
        } catch (e: Exception) {
            logger.warn("Failed to report store counts after commit", e)
        }
    }

    /**
     * 저장소 작업을 재시도하고(즉시, 그리고 [retryDelaysMillis] 대기 후), 모두 실패하면 비활성화한다 (R-7'').
     * 실패한 동안 `current`와 commit 중인 명령은 그대로 유지된다.
     * @return 성공한 결과, 또는 비활성화된 경우 null.
     */
    private suspend fun <T> withStorageRetry(operation: String, block: () -> T): T? {
        var lastError: Throwable? = null
        val attempts = retryDelaysMillis.size + 1
        for (attempt in 0 until attempts) {
            if (attempt > 0) delay(retryDelaysMillis[attempt - 1])
            try {
                return block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
                logger.warn("Storage $operation failed (attempt ${attempt + 1}/$attempts)", e)
                if (attempt == 0 && isStorageFull(e)) reclaimSpace()
            }
        }
        disable(operation, lastError)
        return null
    }

    private fun reclaimSpace() {
        try {
            val deleted = store.reclaimSpace(reclaimFraction)
            counters.evictedFromStore.addAndGet(deleted.toLong())
            logger.warn("Storage full: deleted $deleted oldest track/screen event(s) to reclaim space")
        } catch (e: Exception) {
            logger.warn("Failed to reclaim storage space", e)
        }
    }

    /**
     * storage coroutine이 처리하지 못한 실패로 끝날 때 (설계 §8.4, Q-4b).
     * 새 coroutine을 띄우지 않고 **실패가 난 스레드에서** 비활성 상태로 바꾼다. 여러 번 불려도 한 번만 동작한다.
     * 가장 먼저 lock 안에서 `disabled` 표시를 하므로, 이후 명령은 큐에 쌓이지 않는다.
     * 그 뒤의 콜백 전달과 로그는 최선을 다할 뿐 보장하지 않는다 (예: OutOfMemoryError 상황).
     */
    private fun failUnexpectedly(error: Throwable) {
        if (!unexpectedFailureHandled.compareAndSet(false, true)) return
        try {
            disable("processing (unexpected ${error.javaClass.simpleName})", error)
        } catch (secondary: Throwable) {
            // 콜백 전달이나 로그가 실패해도, 적어도 disabled 표시는 남긴다.
            try {
                synchronized(lock) {
                    disabled = true
                    pending.clear()
                    inFlight = emptyList()
                    pendingMessageCount = 0
                }
            } catch (ignored: Throwable) {
            }
        }
    }

    /** [CoroutineExceptionHandler]에서 호출된다. `Error`는 삼키지 않고 [fatalErrorHandler]로 넘긴다. */
    private fun onUnexpectedFailure(error: Throwable) {
        failUnexpectedly(error)
        if (error is Error) fatalErrorHandler(error)
    }

    /** 설계 §8.3: 기다리던 모든 명령을 버리고, Identity 콜백에 실패를 한 번씩 알린다. */
    private fun disable(operation: String, cause: Throwable?) {
        val dropped: List<Command>
        synchronized(lock) {
            if (disabled) return
            disabled = true
            dropped = inFlight + pending
            inFlight = emptyList()
            pending.clear()
            pendingMessageCount = 0
        }
        logger.error("Storage $operation failed repeatedly; MiniAnalytics is disabled for this process", cause)
        try {
            onDisabled?.invoke()
        } catch (e: Exception) {
            logger.warn("Failed to stop uploading after disable", e)
        }
        counters.droppedStorageError.addAndGet(dropped.size.toLong())
        for (command in dropped) {
            callbacks.deliver(command.callback(), IdentityResult.Failure(FailureReason.STORAGE_ERROR, cause))
        }
        storageSignal.close()
    }

    internal companion object {
        /** 첫 시도 후 재시도 대기 시간: 1초, 5초 (총 3회 시도, R-7''). */
        val DEFAULT_RETRY_DELAYS_MILLIS: List<Long> = listOf(1_000L, 5_000L)
        const val DEFAULT_MAX_COMMANDS_PER_TRANSACTION: Int = 50
        const val DEFAULT_MEMORY_QUEUE_LIMIT: Int = 1_000
        const val DEFAULT_RECLAIM_FRACTION: Double = 0.25
    }
}

private fun Command.isDroppable(): Boolean = this is Command.Track || this is Command.Screen

private fun Command.callback(): IdentityCallback? = when (this) {
    is Command.Identify -> callback
    is Command.Reset -> callback
    else -> null
}

private fun isSqliteFull(error: Throwable): Boolean = error is android.database.sqlite.SQLiteFullException

/**
 * `Error`(예: OutOfMemoryError)를 삼키지 않고, 코루틴이 없었을 때와 같이 스레드의 uncaught exception
 * handler로 넘긴다 (설계 §8.4). Android에서는 보통 앱 프로세스가 종료된다.
 */
internal fun forwardToUncaughtExceptionHandler(error: Throwable) {
    val thread = Thread.currentThread()
    val handler = thread.uncaughtExceptionHandler ?: Thread.getDefaultUncaughtExceptionHandler()
    handler?.uncaughtException(thread, error) ?: throw error
}
