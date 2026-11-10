package io.github.dduddupark.minianalytics.internal.upload

import io.github.dduddupark.minianalytics.internal.model.MessageType
import io.github.dduddupark.minianalytics.internal.storage.EventStore
import io.github.dduddupark.minianalytics.internal.storage.NewEvent
import io.github.dduddupark.minianalytics.internal.storage.StoreLimits
import io.github.dduddupark.minianalytics.internal.storage.StoreTransaction
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/**
 * 응답을 미리 정해 두는 테스트용 [Transport].
 * 동시에 몇 개의 요청이 진행 중이었는지([maxInFlight])도 기록한다.
 */
internal class FakeTransport(private val now: () -> Long = { 0L }) : Transport {
    private val script = ConcurrentLinkedQueue<() -> TransportResponse>()
    val requests = CopyOnWriteArrayList<String>()
    val sendTimes = CopyOnWriteArrayList<Long>()
    val maxInFlight = AtomicInteger()
    private val inFlight = AtomicInteger()

    /** 각 send에서 응답을 돌려주기 전에 실행할 동작 (예: 블로킹, 이벤트 추가). */
    @Volatile var onSend: (() -> Unit)? = null

    /** 스크립트가 비었을 때의 응답. */
    @Volatile var fallback: () -> TransportResponse = { TransportResponse(200, null, null) }

    /** 요청 본문의 messageId 목록을 보고 응답을 정한다 (스크립트보다 뒤, fallback보다 앞). */
    @Volatile var responder: ((List<String>) -> TransportResponse)? = null

    fun respond(code: Int, retryAfterMillis: Long? = null, body: String? = null) {
        script += { TransportResponse(code, retryAfterMillis, body) }
    }

    fun failWithNetworkError() {
        script += { throw IOException("simulated network error") }
    }

    fun respondWith(block: () -> TransportResponse) {
        script += block
    }

    override fun send(body: ByteArray): TransportResponse {
        val current = inFlight.incrementAndGet()
        maxInFlight.updateAndGet { maxOf(it, current) }
        try {
            requests += String(body, Charsets.UTF_8)
            sendTimes += now()
            onSend?.invoke()
            script.poll()?.let { return it() }
            responder?.let { return it(messageIdsOf(String(body, Charsets.UTF_8))) }
            return fallback()
        } finally {
            inFlight.decrementAndGet()
        }
    }

    /** n번째 요청의 batch messageId 목록 (org.json → Robolectric 필요). */
    fun messageIds(index: Int): List<String> = messageIdsOf(requests[index])

    private fun messageIdsOf(body: String): List<String> {
        val batch = JSONObject(body).getJSONArray("batch")
        return (0 until batch.length()).map { batch.getJSONObject(it).getString("messageId") }
    }

    fun batchSizes(): List<Int> = requests.indices.map { messageIds(it).size }
}

/** 항상 jitter 범위의 최댓값을 고르는 Random. 그러면 backoff 대기 시간이 결정적으로 정해진다. */
internal object MaxJitterRandom : Random() {
    override fun nextBits(bitCount: Int): Int = 0
    override fun nextLong(from: Long, until: Long): Long = until - 1
}

/** 저장소에 `count`개의 track 이벤트를 넣는다. payload에 messageId를 담아 요청 본문에서 확인할 수 있게 한다. */
internal fun EventStore.addEvents(prefix: String, count: Int, sizeBytes: Int? = null) {
    val events = (1..count).map { i ->
        val id = "$prefix-$i"
        val payload = "{\"messageId\":\"$id\",\"type\":\"track\"}"
        NewEvent(id, MessageType.TRACK, payload, sizeBytes ?: payload.length)
    }
    commit(StoreTransaction(events, null, StoreLimits(maxQueuedEvents = 10_000)))
}

internal fun EventStore.storedMessageIds(): List<String> =
    readBatch(Int.MAX_VALUE, Int.MAX_VALUE).map { it.messageId }
