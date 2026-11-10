package io.github.dduddupark.minianalytics.internal.storage

import io.github.dduddupark.minianalytics.internal.model.Identity
import io.github.dduddupark.minianalytics.internal.model.MessageType

/**
 * 미전송 메시지와 Identity를 영구 저장하는 저장소 (설계 §5, R-1, R-4', R-5).
 *
 * **사용 규칙**
 * - storage coroutine 하나에서만 호출한다 (설계 §4.3). 구현체는 동시 호출을 가정하지 않는다.
 * - 메인 스레드에서 호출하지 않는다. 생성자는 디스크 I/O를 하지 않고, 첫 호출 때 DB를 연다.
 * - 저장소 오류는 **예외로 던진다.** 예외를 잡아서 재시도하거나 비활성화하는 것은 호출자(storage
 *   coroutine)의 책임이다 (설계 §8.2, R-7'').
 */
internal interface EventStore {

    /** 저장된 Identity를 읽는다. 처음 실행이면 null이다. */
    fun loadIdentity(): StoredIdentity?

    /**
     * [transaction]을 **하나의 트랜잭션**으로 적용한다. 전부 반영되거나 전부 반영되지 않는다.
     *
     * 순서: 메시지 INSERT → Identity UPSERT(있으면) → 상한 정리.
     * 같은 `messageId`가 이미 있으면 그 메시지는 무시한다. 이미 commit된 트랜잭션을 다시 시도해도
     * 결과가 같도록(멱등) 하기 위해서다.
     */
    fun commit(transaction: StoreTransaction): CommitResult

    /**
     * 가장 오래된 메시지부터 최대 [maxCount]개를 id 순서로 읽는다.
     * 크기 합계가 [maxBytes]를 넘기 직전에 멈추지만, 첫 메시지는 크기와 관계없이 항상 포함한다.
     */
    fun readBatch(maxCount: Int, maxBytes: Int): List<StoredEvent>

    /** 주어진 id의 행을 삭제하고 삭제한 행 수를 돌려준다. 없는 id는 무시한다. */
    fun deleteByIds(ids: Collection<Long>): Int

    /**
     * 디스크 부족 시 공간 확보 (설계 §5.4, R-4' (3)).
     * 가장 오래된 track/screen 행을 현재 개수의 [fraction] 비율만큼(올림, 최소 1개) 삭제한다.
     * identify 행은 건드리지 않는다.
     */
    fun reclaimSpace(fraction: Double): Int

    fun counts(): StoreCounts

    fun close()
}

/** @property version 명령마다 1씩 증가하는 디버깅용 값 (R-1b). 로직에는 쓰지 않는다. */
internal data class StoredIdentity(val identity: Identity, val version: Long)

internal data class NewEvent(
    val messageId: String,
    val type: MessageType,
    val payload: String,
    val sizeBytes: Int,
)

internal data class StoredEvent(
    val id: Long,
    val messageId: String,
    val type: MessageType,
    val payload: String,
    val sizeBytes: Int,
)

/**
 * @property maxQueuedEvents track/screen 행의 상한 (설정값, 기본 1,000).
 * @property maxQueuedIdentifies identify 행의 상한 (내부 상수 100, R-4').
 */
internal data class StoreLimits(
    val maxQueuedEvents: Int,
    val maxQueuedIdentifies: Int = DEFAULT_MAX_QUEUED_IDENTIFIES,
) {
    companion object {
        const val DEFAULT_MAX_QUEUED_IDENTIFIES: Int = 100
    }
}

/** @property identity null이면 identity 행을 바꾸지 않는다. */
internal data class StoreTransaction(
    val events: List<NewEvent>,
    val identity: StoredIdentity?,
    val limits: StoreLimits,
)

/**
 * @property evictedEvents 상한 정리로 지운 track/screen 행 (`evictedFromStore`).
 * @property evictedIdentifies 상한 정리로 지운 identify 행 (`evictedIdentify`).
 */
internal data class CommitResult(val evictedEvents: Int, val evictedIdentifies: Int)

/** @property events track/screen 행 수. @property identifies identify 행 수. */
internal data class StoreCounts(val events: Int, val identifies: Int) {
    val total: Int get() = events + identifies
}
