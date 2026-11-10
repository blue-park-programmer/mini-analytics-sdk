package io.github.dduddupark.minianalytics.internal.upload

import io.github.dduddupark.minianalytics.internal.log.Logger
import io.github.dduddupark.minianalytics.internal.pipeline.Counters
import io.github.dduddupark.minianalytics.internal.pipeline.forwardToUncaughtExceptionHandler
import io.github.dduddupark.minianalytics.internal.storage.EventStore
import io.github.dduddupark.minianalytics.internal.storage.StoreCounts
import io.github.dduddupark.minianalytics.internal.storage.StoredEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException

/**
 * 전송 파이프라인 (설계 §4.4, §6, R-5).
 *
 * - **coroutine 하나**로 동작하므로 요청은 항상 하나씩만 나간다 (single in-flight).
 * - trigger(저장 행 수 ≥ `flushAt`, `flushInterval` 주기, `flush()`, 시작 시 1회)는 conflated 신호로 합쳐진다.
 *   그래서 동시에 여러 번 요청해도 전송 루프는 하나다.
 * - DB 작업(`readBatch`, `deleteByIds`, `counts`)은 **storage dispatcher**에서, 네트워크는 **network dispatcher**에서 한다.
 *   네트워크 요청이 오래 걸려도 storage coroutine(= `track()`의 저장)은 막히지 않는다.
 * - 서버가 처리 완료를 확인한(2xx) 배치의 id만 삭제한다. 삭제 전에 프로세스가 끝나면 다음 실행에서
 *   같은 `messageId`로 다시 보낸다 (at-least-once, 설계 §6.1).
 * - backoff 중에는 어떤 trigger도 다음 시도 시각을 앞당기지 않는다.
 */
internal class Uploader(
    private val store: EventStore,
    private val storageDispatcher: CoroutineDispatcher,
    private val networkDispatcher: CoroutineDispatcher,
    scope: CoroutineScope,
    private val transport: Transport,
    private val envelope: EnvelopeBuilder,
    private val flushAt: Int,
    private val flushIntervalMillis: Long,
    private val counters: Counters,
    private val logger: Logger,
    private val monotonicMillis: () -> Long,
    private val backoff: Backoff = Backoff(),
    private val maxBatchCount: Int = DEFAULT_MAX_BATCH_COUNT,
    private val maxBatchBytes: Int = DEFAULT_MAX_BATCH_BYTES,
    private val fatalErrorHandler: (Throwable) -> Unit = ::forwardToUncaughtExceptionHandler,
    /** P7-1: false(기본)면 400/422에 단건 격리와 삭제를 하지 않는다. */
    private val trustInvalidEventResponses: Boolean = false,
    /** P7-2b: 격리 한 번에 보낼 수 있는 단건 요청 수의 상한 (재시도 포함). */
    private val maxIsolationRequests: Int = DEFAULT_MAX_ISOLATION_REQUESTS,
) {
    private val signal = Channel<Unit>(Channel.CONFLATED)

    // ---------- 전송 coroutine만 접근하는 상태 (메모리에만 둔다, 설계 §6.3) ----------
    private var nextAttemptAt = Long.MIN_VALUE
    private var consecutiveFailures = 0
    private var batchLimit = maxBatchCount

    /** 단건 격리 경계 (R-8''). null이면 격리 중이 아니다. id는 AUTOINCREMENT라 재사용되지 않는다. */
    private var isolateThroughId: Long? = null

    /** 이번 프로세스에서 400 경로로 삭제한 이벤트 수 (상한 [MAX_REJECTION_DELETES_PER_PROCESS]). */
    private var rejectionDeletes = 0

    /** 이번 격리에서 보낸 단건 요청 수 (P7-2b). 격리를 시작할 때 0으로 돌아간다. */
    private var isolationRequests = 0

    /** 실패 종류별 마지막 로그 시각과 그 뒤 생략한 횟수 (로그 억제용). 성공하면 비운다. */
    private class FailureLogState(var loggedAt: Long, var suppressed: Int)

    private val failureLogs = HashMap<String, FailureLogState>()

    private val job: Job

    init {
        val lastResort = CoroutineExceptionHandler { _, error ->
            logger.error("Uploader stopped unexpectedly; queued events stay on disk", error)
            if (error is Error) fatalErrorHandler(error)
        }
        job = scope.launch(lastResort) {
            launch {
                while (isActive) {
                    delay(flushIntervalMillis)
                    signal.trySend(Unit)
                }
            }
            signal.trySend(Unit) // 시작 시 1회: 이전 실행에서 남은 이벤트
            for (ignored in signal) drain()
        }
    }

    /** `flush()`: 비동기 전송 요청. 완료를 보장하지 않고, backoff 중이면 시도를 앞당기지 않는다. */
    fun requestFlush() {
        signal.trySend(Unit)
    }

    /** storage coroutine이 commit 뒤에 알려 준다. */
    fun onStoreCounts(counts: StoreCounts) {
        if (counts.total >= flushAt) requestFlush()
    }

    /** 비활성화 시 (설계 §8.3). 이미 보낸 요청의 결과는 버린다. 데이터는 디스크에 남는다. */
    fun stop() {
        job.cancel()
        signal.close()
    }

    /** 테스트용 */
    internal val currentBatchLimit: Int get() = batchLimit

    private suspend fun drain() {
        while (true) {
            waitForBackoff()

            val isolating = isolateThroughId != null
            val batch = try {
                withContext(storageDispatcher) { store.readBatch(if (isolating) 1 else batchLimit, maxBatchBytes) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logFailure("read:${e.javaClass.name}", isError = false, "Failed to read queued events; will retry", e)
                scheduleRetry(retryAfterMillis = null)
                continue
            }
            if (batch.isEmpty()) {
                isolateThroughId = null
                return
            }
            val boundary = isolateThroughId
            if (boundary != null && batch[0].id > boundary) {
                // 격리 대상(경계 이하의 id)을 모두 처리했다. 이후 이벤트는 다시 배치로 보낸다.
                endIsolation()
                continue
            }
            if (boundary != null) {
                if (isolationRequests >= maxIsolationRequests) {
                    // P7-2b: 이번 격리의 요청 상한에 도달했다. 남은 이벤트는 보존하고 다음 시도(최대 backoff)까지 멈춘다.
                    endIsolation()
                    waitMaximum(
                        "isolate-limit",
                        "Stopped sending events one by one after $maxIsolationRequests requests; " +
                            "remaining events are kept and will be retried",
                    )
                    continue
                }
                isolationRequests++
            }

            val response = try {
                val body = envelope.build(batch)
                withContext(networkDispatcher) { transport.send(body) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                // 예외 종류별로 따로 센다: 타임아웃이 이어져도 TLS 오류 같은 다른 원인은 묻히지 않는다 (D6-1).
                logFailure("network:${e.javaClass.name}", isError = false, "Upload failed (network): $e", null)
                null
            } catch (e: Exception) {
                // 예: INTERNET 권한 없음(SecurityException). 데이터를 지우지 않고 일시적 실패처럼 다룬다.
                logFailure("unexpected-exception:${e.javaClass.name}", isError = false, "Upload failed unexpectedly; will retry", e)
                null
            }

            when (ResponseClassifier.classify(response?.code)) {
                ResponseOutcome.SUCCESS -> {
                    recordRejected(response!!.body, batch.size)
                    if (!delete(batch, "after successful upload")) {
                        // 서버는 받았지만 로컬 삭제 실패: 다음 시도에서 같은 messageId로 다시 보내게 된다.
                        // 곧바로 다시 보내지 않도록 backoff한다.
                        scheduleRetry(retryAfterMillis = null)
                        continue
                    }
                    onRecovered()
                    batchLimit = maxBatchCount
                    if (isolateThroughId == null && remaining() < flushAt) return
                }
                ResponseOutcome.TRANSIENT -> {
                    // 격리 중이라면 격리 상태를 유지한다 (R-8'' / Q24 보완 3).
                    // 네트워크 오류(response == null)는 위 catch에서 예외 종류별로 이미 기록했다.
                    if (response != null) {
                        logFailure(
                            "transient:${response.code}", isError = false,
                            "Upload failed (${response.code}); will retry", null,
                        )
                    }
                    scheduleRetry(response?.retryAfterMillis)
                }
                ResponseOutcome.PAYLOAD_TOO_LARGE -> {
                    if (batch.size == 1) {
                        logger.error("Event ${batch[0].messageId} rejected as too large (413); dropped", null)
                        if (delete(batch, "after 413")) counters.rejectedByServer.incrementAndGet()
                    } else {
                        batchLimit = (batch.size / 2).coerceAtLeast(1)
                        logger.warn("Payload too large (413); retrying with batches of $batchLimit", null)
                    }
                }
                ResponseOutcome.AUTH_ERROR ->
                    waitMaximum("auth:${response!!.code}", "Upload rejected with ${response.code}: check the apiKey. Events are kept")
                ResponseOutcome.INVALID_REQUEST -> onInvalidRequest(batch, response!!)
                ResponseOutcome.UNEXPECTED ->
                    waitMaximum("unexpected:${response!!.code}", "Unexpected upload response ${response.code}. Events are kept")
            }
        }
    }

    /**
     * 400/422 처리: 단건 격리 (설계 §6.4, R-8'').
     *
     * - 여러 건의 배치: 그 배치의 마지막 id까지를 격리 대상으로 정하고, 한 건씩 다시 보낸다. 삭제하지 않는다.
     * - 한 건: 서버가 계약대로 `{"error":{"code":"invalid_event","messageId":"<보낸 id>"}}`를 돌려주고,
     *   이번 프로세스의 400 경로 삭제가 [MAX_REJECTION_DELETES_PER_PROCESS]건 미만일 때만 그 이벤트를 삭제한다.
     *   그 밖의 경우(코드 없음, 다른 코드, messageId 불일치, HTML 등, 또는 상한 도달)는 **서버 문제로 보고** 아무것도
     *   삭제하지 않고 격리를 끝낸 뒤 최대 backoff로 기다린다.
     * - 요청 수 상한: 격리 한 번에 보내는 단건 요청은 원래 배치 크기(최대 [maxBatchCount])를 넘지 않는다.
     *   재귀 없이 같은 루프에서 한 건씩 처리한다.
     */
    private suspend fun onInvalidRequest(batch: List<StoredEvent>, response: TransportResponse) {
        if (!trustInvalidEventResponses) {
            // P7-1: 서버 계약을 신뢰하도록 설정하지 않았다. 격리도 삭제도 하지 않고 보존한다.
            endIsolation()
            waitMaximum(
                "invalid:${response.code}:untrusted",
                "Upload rejected with ${response.code}. Events are kept " +
                    "(invalid_event responses are not trusted; see Configuration.trustInvalidEventResponses)",
            )
            return
        }
        if (batch.size > 1) {
            isolateThroughId = batch.last().id
            isolationRequests = 0
            logFailure(
                "isolate:${response.code}", isError = false,
                "Upload rejected with ${response.code}; sending ${batch.size} event(s) one by one", null,
            )
            return
        }
        val event = batch.single()
        val provenInvalid = isInvalidEventResponse(response.body, event.messageId)
        if (provenInvalid && rejectionDeletes < MAX_REJECTION_DELETES_PER_PROCESS) {
            if (delete(batch, "after invalid_event")) {
                rejectionDeletes++
                counters.rejectedByServer.incrementAndGet()
                logger.warn("Event ${event.messageId} was rejected by the server as invalid_event; dropped", null)
            }
            return
        }
        endIsolation()
        val reason = when {
            provenInvalid -> "the per-process limit of $MAX_REJECTION_DELETES_PER_PROCESS deletions was reached"
            else -> "the response does not identify an invalid event"
        }
        waitMaximum(
            "invalid:${response.code}:${if (provenInvalid) "limit" else "unproven"}",
            "Upload rejected with ${response.code} and $reason; treating it as a server problem. Events are kept",
        )
    }

    private fun endIsolation() {
        isolateThroughId = null
    }

    private suspend fun waitForBackoff() {
        val wait = nextAttemptAt - monotonicMillis()
        if (nextAttemptAt != Long.MIN_VALUE && wait > 0) delay(wait)
    }

    private fun scheduleRetry(retryAfterMillis: Long?) {
        consecutiveFailures++
        nextAttemptAt = monotonicMillis() + backoff.delayFor(consecutiveFailures, retryAfterMillis)
    }

    private fun waitMaximum(failureKey: String, message: String) {
        logFailure(failureKey, isError = true, message, null)
        consecutiveFailures++
        nextAttemptAt = monotonicMillis() + backoff.maxMillis
    }

    /**
     * 반복되는 실패 로그를 줄인다 (5단계 승인 조건 1). 같은 종류의 실패가 이어지면 처음 한 번과,
     * 그 뒤로는 [FAILURE_LOG_INTERVAL_MILLIS]마다 한 번만 남긴다. 그사이 생략한 횟수를 함께 적는다.
     */
    private fun logFailure(key: String, isError: Boolean, message: String, error: Throwable?) {
        val now = monotonicMillis()
        val state = failureLogs[key]
        if (state != null && now - state.loggedAt < FAILURE_LOG_INTERVAL_MILLIS) {
            state.suppressed++
            return
        }
        val suffix = if (state != null && state.suppressed > 0) {
            " (${state.suppressed} similar failure(s) since the last log)"
        } else {
            ""
        }
        if (isError) logger.error(message + suffix, error) else logger.warn(message + suffix, error)
        failureLogs[key] = FailureLogState(loggedAt = now, suppressed = 0)
    }

    private fun onRecovered() {
        if (consecutiveFailures > 0) logger.warn("Upload succeeded after $consecutiveFailures failed attempt(s)", null)
        consecutiveFailures = 0
        nextAttemptAt = Long.MIN_VALUE
        failureLogs.clear()
    }

    /** 단건 거부 응답이 계약(R-8''a)대로 이 이벤트를 지목하는지. */
    private fun isInvalidEventResponse(body: String?, messageId: String): Boolean {
        if (body.isNullOrBlank()) return false
        return try {
            val error = JSONObject(body).optJSONObject("error") ?: return false
            error.optString("code") == "invalid_event" && error.optString("messageId") == messageId
        } catch (e: Exception) {
            false
        }
    }

    private suspend fun delete(batch: List<StoredEvent>, reason: String): Boolean = try {
        withContext(storageDispatcher) { store.deleteByIds(batch.map { it.id }) }
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.error("Failed to delete ${batch.size} event(s) $reason; they will be sent again", e)
        false
    }

    private suspend fun remaining(): Int = try {
        withContext(storageDispatcher) { store.counts().total }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        0
    }

    /** 2xx 본문의 선택적 `rejected` 목록 (설계 §6.5). 없거나 해석할 수 없으면 무시한다. 삭제 동작은 같다. */
    private fun recordRejected(body: String?, batchSize: Int) {
        if (body.isNullOrBlank()) return
        val rejected = try {
            JSONObject(body).optJSONArray("rejected")?.length() ?: 0
        } catch (e: Exception) {
            0
        }
        if (rejected == 0) return
        counters.rejectedByServer.addAndGet(rejected.toLong())
        if (rejected >= batchSize) {
            logger.error("Server rejected every event in a batch of $batchSize; check the server", null)
        } else {
            logger.warn("Server rejected $rejected of $batchSize event(s)", null)
        }
    }

    /** 테스트용 */
    internal val isIsolating: Boolean get() = isolateThroughId != null

    internal companion object {
        const val DEFAULT_MAX_BATCH_COUNT: Int = 100
        const val DEFAULT_MAX_BATCH_BYTES: Int = 512 * 1024

        /** 프로세스당 400 경로로 삭제할 수 있는 최대 이벤트 수 (R-8''b). */
        const val MAX_REJECTION_DELETES_PER_PROCESS: Int = 20

        /** 격리 한 번에 보낼 수 있는 단건 요청 수 (P7-2b, v1 초기 정책). */
        const val DEFAULT_MAX_ISOLATION_REQUESTS: Int = 20

        /** 같은 종류의 실패 로그를 다시 남기기까지의 간격. */
        const val FAILURE_LOG_INTERVAL_MILLIS: Long = 60 * 60 * 1_000L
    }
}
