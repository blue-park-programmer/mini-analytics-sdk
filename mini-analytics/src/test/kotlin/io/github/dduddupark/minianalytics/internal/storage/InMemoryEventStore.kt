package io.github.dduddupark.minianalytics.internal.storage

import io.github.dduddupark.minianalytics.internal.model.MessageType
import java.util.TreeMap
import kotlin.math.ceil

/**
 * 테스트용 [EventStore]. [SqliteEventStore]와 같은 의미를 갖도록 [EventStoreContract]로 검증한다.
 * 3단계 이후의 파이프라인 테스트에서 SQLite 없이 빠르게 쓰기 위한 것이다.
 */
internal class InMemoryEventStore : EventStore {
    private var events = TreeMap<Long, StoredEvent>()
    private var identity: StoredIdentity? = null
    private var nextId = 1L

    override fun loadIdentity(): StoredIdentity? = identity

    override fun commit(transaction: StoreTransaction): CommitResult {
        // 복사본에 적용한 뒤 한 번에 교체해서 원자성을 흉내 낸다.
        val workingEvents = TreeMap(events)
        var workingNextId = nextId
        val knownMessageIds = workingEvents.values.mapTo(HashSet()) { it.messageId }
        for (event in transaction.events) {
            if (!knownMessageIds.add(event.messageId)) continue
            val id = workingNextId++
            workingEvents[id] = StoredEvent(id, event.messageId, event.type, event.payload, event.sizeBytes)
        }
        val evictedEvents = evict(workingEvents, EVENT_TYPES, transaction.limits.maxQueuedEvents)
        val evictedIdentifies = evict(workingEvents, IDENTIFY_TYPES, transaction.limits.maxQueuedIdentifies)

        events = workingEvents
        nextId = workingNextId
        transaction.identity?.let { identity = it }
        return CommitResult(evictedEvents, evictedIdentifies)
    }

    override fun readBatch(maxCount: Int, maxBytes: Int): List<StoredEvent> {
        val result = ArrayList<StoredEvent>()
        var total = 0L
        for (event in events.values) {
            if (result.size >= maxCount) break
            if (result.isNotEmpty() && total + event.sizeBytes > maxBytes) break
            result += event
            total += event.sizeBytes
        }
        return result
    }

    override fun deleteByIds(ids: Collection<Long>): Int = ids.toSet().count { events.remove(it) != null }

    override fun reclaimSpace(fraction: Double): Int {
        val count = events.values.count { it.type in EVENT_TYPES }
        if (count == 0) return 0
        val toDelete = ceil(count * fraction.coerceIn(0.0, 1.0)).toInt().coerceIn(1, count)
        return deleteOldest(events, EVENT_TYPES, toDelete)
    }

    override fun counts(): StoreCounts = StoreCounts(
        events = events.values.count { it.type in EVENT_TYPES },
        identifies = events.values.count { it.type in IDENTIFY_TYPES },
    )

    override fun close() = Unit

    private fun evict(target: TreeMap<Long, StoredEvent>, types: Set<MessageType>, max: Int): Int {
        val excess = target.values.count { it.type in types } - max
        return if (excess > 0) deleteOldest(target, types, excess) else 0
    }

    private fun deleteOldest(target: TreeMap<Long, StoredEvent>, types: Set<MessageType>, count: Int): Int {
        val ids = target.values.asSequence().filter { it.type in types }.take(count).map { it.id }.toList()
        ids.forEach { target.remove(it) }
        return ids.size
    }

    private companion object {
        val EVENT_TYPES = setOf(MessageType.TRACK, MessageType.SCREEN)
        val IDENTIFY_TYPES = setOf(MessageType.IDENTIFY)
    }
}
