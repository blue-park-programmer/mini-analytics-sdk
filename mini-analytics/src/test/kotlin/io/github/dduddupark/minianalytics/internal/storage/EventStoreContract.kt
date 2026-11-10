package io.github.dduddupark.minianalytics.internal.storage

import io.github.dduddupark.minianalytics.internal.model.Identity
import io.github.dduddupark.minianalytics.internal.model.MessageType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 모든 [EventStore] 구현이 지켜야 하는 동작 (설계 §5).
 * [SqliteEventStoreTest]와 [InMemoryEventStoreTest]가 같은 테스트를 실행한다.
 * 그래서 3단계 이후 테스트에서 쓰는 [InMemoryEventStore]가 실제 SQLite 구현과 같은 의미를 갖는다.
 */
internal abstract class EventStoreContract {

    protected abstract fun createStore(): EventStore

    protected lateinit var store: EventStore

    @Before
    fun setUpStore() {
        store = createStore()
    }

    @After
    fun tearDownStore() {
        store.close()
    }

    // ---------- 헬퍼 ----------

    private var sequence = 0

    protected fun event(type: MessageType = MessageType.TRACK, size: Int = 10, messageId: String? = null): NewEvent {
        val n = sequence++
        return NewEvent(messageId ?: "m-$n", type, payload = "{\"n\":$n}", sizeBytes = size)
    }

    protected fun commit(
        vararg events: NewEvent,
        identity: StoredIdentity? = null,
        maxEvents: Int = 1_000,
        maxIdentifies: Int = 100,
    ): CommitResult = store.commit(StoreTransaction(events.toList(), identity, StoreLimits(maxEvents, maxIdentifies)))

    protected fun allEvents(): List<StoredEvent> = store.readBatch(Int.MAX_VALUE, Int.MAX_VALUE)

    protected val identityA = StoredIdentity(Identity("anon-1", "user-a"), version = 1)

    // ---------- 빈 저장소 ----------

    @Test
    fun emptyStoreHasNothing() {
        assertNull(store.loadIdentity())
        assertEquals(StoreCounts(0, 0), store.counts())
        assertTrue(store.readBatch(10, 1_000).isEmpty())
    }

    // ---------- 삽입과 순서 ----------

    @Test
    fun eventsAreReadInInsertionOrderWithIncreasingIds() {
        val inserted = listOf(event(), event(MessageType.SCREEN), event(MessageType.IDENTIFY), event())
        commit(*inserted.take(2).toTypedArray())
        commit(*inserted.drop(2).toTypedArray())

        val stored = allEvents()
        assertEquals(inserted.map { it.messageId }, stored.map { it.messageId })
        assertEquals(inserted.map { it.type }, stored.map { it.type })
        assertEquals(inserted.map { it.payload }, stored.map { it.payload })
        assertEquals(inserted.map { it.sizeBytes }, stored.map { it.sizeBytes })
        assertTrue(stored.zipWithNext().all { (a, b) -> a.id < b.id })
    }

    @Test
    fun idsKeepIncreasingAfterAllRowsAreDeleted() {
        commit(event(), event())
        val before = allEvents()
        store.deleteByIds(before.map { it.id })
        commit(event())
        assertTrue(allEvents().single().id > before.maxOf { it.id })
    }

    @Test
    fun duplicateMessageIdIsIgnoredSoRetriesAreIdempotent() {
        val original = event(messageId = "same")
        commit(original)
        // 이미 commit된 트랜잭션을 다시 시도하는 상황
        commit(original.copy(payload = "{\"retry\":true}"), event())
        val stored = allEvents()
        assertEquals(2, stored.size)
        assertEquals(original.payload, stored.first { it.messageId == "same" }.payload)
    }

    // ---------- Identity ----------

    @Test
    fun identityIsStoredAndReplaced() {
        commit(identity = identityA)
        assertEquals(identityA, store.loadIdentity())

        val anonymous = StoredIdentity(Identity("anon-2", null), version = 2)
        commit(identity = anonymous)
        assertEquals(anonymous, store.loadIdentity())
    }

    @Test
    fun commitWithoutIdentityKeepsStoredIdentity() {
        commit(identity = identityA)
        commit(event())
        assertEquals(identityA, store.loadIdentity())
    }

    @Test
    fun identityAndEventsAreCommittedTogether() {
        commit(event(MessageType.IDENTIFY), identity = identityA)
        assertEquals(identityA, store.loadIdentity())
        assertEquals(StoreCounts(events = 0, identifies = 1), store.counts())
    }

    // ---------- 상한 정리 ----------

    @Test
    fun eventLimitEvictsOldestTrackAndScreenOnly() {
        val identify = event(MessageType.IDENTIFY)
        commit(event(), identify, event(MessageType.SCREEN), event())
        val result = commit(event(), maxEvents = 2)

        assertEquals(2, result.evictedEvents)
        assertEquals(0, result.evictedIdentifies)
        assertEquals(StoreCounts(events = 2, identifies = 1), store.counts())
        assertTrue(allEvents().any { it.messageId == identify.messageId })
    }

    @Test
    fun identifyLimitEvictsOldestIdentifyOnly() {
        val track = event()
        commit(event(MessageType.IDENTIFY), track, event(MessageType.IDENTIFY), event(MessageType.IDENTIFY))
        val result = commit(maxIdentifies = 2)

        assertEquals(0, result.evictedEvents)
        assertEquals(1, result.evictedIdentifies)
        assertEquals(StoreCounts(events = 1, identifies = 2), store.counts())
        assertEquals("m-2", allEvents().first { it.type == MessageType.IDENTIFY }.messageId)
    }

    @Test
    fun noEvictionWithinLimits() {
        val result = commit(event(), event(MessageType.IDENTIFY), maxEvents = 1, maxIdentifies = 1)
        assertEquals(CommitResult(0, 0), result)
    }

    // ---------- 배치 읽기 ----------

    @Test
    fun readBatchRespectsMaxCount() {
        commit(event(), event(), event())
        assertEquals(listOf("m-0", "m-1"), store.readBatch(2, Int.MAX_VALUE).map { it.messageId })
    }

    @Test
    fun readBatchStopsBeforeExceedingMaxBytes() {
        commit(event(size = 40), event(size = 40), event(size = 40))
        assertEquals(2, store.readBatch(10, 80).size)
        assertEquals(2, store.readBatch(10, 119).size)
        assertEquals(3, store.readBatch(10, 120).size)
    }

    @Test
    fun readBatchAlwaysIncludesTheFirstEvent() {
        commit(event(size = 500), event(size = 1))
        assertEquals(1, store.readBatch(10, 100).size)
    }

    @Test
    fun readBatchDoesNotRemoveEvents() {
        commit(event(), event())
        store.readBatch(10, 1_000)
        assertEquals(2, store.counts().total)
    }

    // ---------- 삭제 ----------

    @Test
    fun deleteByIdsRemovesOnlyThoseIdsAndIgnoresUnknown() {
        commit(event(), event(), event())
        val ids = allEvents().map { it.id }
        val deleted = store.deleteByIds(listOf(ids[0], ids[2], 9_999L))
        assertEquals(2, deleted)
        assertEquals(listOf(ids[1]), allEvents().map { it.id })
    }

    @Test
    fun deleteManyIdsAtOnce() {
        val events = Array(1_200) { event() }
        commit(*events, maxEvents = 2_000) // 상한 정리가 먼저 일어나지 않게 한다
        assertEquals(1_200, store.deleteByIds(allEvents().map { it.id }))
        assertEquals(0, store.counts().total)
    }

    // ---------- 공간 확보 ----------

    @Test
    fun reclaimSpaceDeletesOldestFractionOfTrackAndScreenRoundedUp() {
        val identify = event(MessageType.IDENTIFY)
        commit(identify, event(), event(), event(), event(), event())
        val deleted = store.reclaimSpace(0.25) // ceil(5 * 0.25) = 2
        assertEquals(2, deleted)
        assertEquals(StoreCounts(events = 3, identifies = 1), store.counts())
        assertEquals(listOf(identify.messageId, "m-3", "m-4", "m-5"), allEvents().map { it.messageId })
    }

    @Test
    fun reclaimSpaceDeletesAtLeastOne() {
        commit(event())
        assertEquals(1, store.reclaimSpace(0.01))
    }

    @Test
    fun reclaimSpaceWithNothingToDelete() {
        commit(event(MessageType.IDENTIFY))
        assertEquals(0, store.reclaimSpace(0.25))
        assertEquals(1, store.counts().identifies)
    }
}
