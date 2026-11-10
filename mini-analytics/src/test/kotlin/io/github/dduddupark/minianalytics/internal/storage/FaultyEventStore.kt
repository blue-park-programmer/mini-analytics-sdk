package io.github.dduddupark.minianalytics.internal.storage

/**
 * 오류와 지연을 주입하는 [EventStore] 데코레이터 (3단계의 R-7'' 재시도/비활성화 테스트용).
 *
 * 실패를 주입하면 대상 메서드는 **위임하지 않고** 예외를 던진다. 즉 실패한 commit은 아무것도
 * 반영하지 않는다(SQLite 트랜잭션 롤백과 같은 의미).
 */
internal class FaultyEventStore(private val delegate: EventStore) : EventStore {

    /** 다음 N번의 `loadIdentity` 호출을 실패시킨다. */
    @Volatile var failNextLoads: Int = 0

    /** 다음 N번의 `commit` 호출을 실패시킨다. */
    @Volatile var failNextCommits: Int = 0

    /** 모든 `commit`을 실패시킨다 (`failNextCommits`보다 우선). */
    @Volatile var failAllCommits: Boolean = false

    /** `commit` 앞에서 실행할 동작 (예: 지연을 흉내 내기 위한 대기). */
    @Volatile var beforeCommit: (() -> Unit)? = null

    /**
     * 실패할 때 던질 예외. 기본값은 JVM 예외다(일반 JUnit에서는 Android 클래스를 만들 수 없음).
     * Robolectric 테스트에서는 `SQLiteFullException` 등으로 바꿔서 쓴다.
     */
    @Volatile var errorFactory: () -> RuntimeException = { IllegalStateException("injected storage failure") }

    @Volatile var commitAttempts: Int = 0
        private set

    @Volatile var loadAttempts: Int = 0
        private set

    override fun loadIdentity(): StoredIdentity? {
        loadAttempts++
        if (failNextLoads > 0) {
            failNextLoads--
            throw errorFactory()
        }
        return delegate.loadIdentity()
    }

    override fun commit(transaction: StoreTransaction): CommitResult {
        commitAttempts++
        beforeCommit?.invoke()
        if (failAllCommits) throw errorFactory()
        if (failNextCommits > 0) {
            failNextCommits--
            throw errorFactory()
        }
        return delegate.commit(transaction)
    }

    /** 다음 N번의 `readBatch` 호출을 실패시킨다. */
    @Volatile var failNextReads: Int = 0

    /** 다음 N번의 `deleteByIds` 호출을 실패시킨다 (전송 성공 뒤 삭제 전 종료를 흉내 낼 때). */
    @Volatile var failNextDeletes: Int = 0

    override fun readBatch(maxCount: Int, maxBytes: Int): List<StoredEvent> {
        if (failNextReads > 0) {
            failNextReads--
            throw errorFactory()
        }
        return delegate.readBatch(maxCount, maxBytes)
    }

    override fun deleteByIds(ids: Collection<Long>): Int {
        if (failNextDeletes > 0) {
            failNextDeletes--
            throw errorFactory()
        }
        return delegate.deleteByIds(ids)
    }

    override fun reclaimSpace(fraction: Double): Int = delegate.reclaimSpace(fraction)

    override fun counts(): StoreCounts = delegate.counts()

    override fun close() = delegate.close()
}
