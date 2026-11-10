package io.github.dduddupark.minianalytics.internal.pipeline

import java.util.concurrent.atomic.AtomicLong

/**
 * 관측 카운터 (설계 §9). 어느 스레드에서든 올릴 수 있다. 로직에는 영향을 주지 않는다.
 * v1에서는 공개 API가 없고, 디버그 로그와 테스트에서만 쓴다.
 */
internal class Counters {
    val droppedInMemory = AtomicLong()
    val evictedFromStore = AtomicLong()
    val evictedIdentify = AtomicLong()
    val droppedOversized = AtomicLong()
    val droppedInvalid = AtomicLong()
    val droppedStorageError = AtomicLong()
    val rejectedByServer = AtomicLong()
    val propertiesSanitized = AtomicLong()
    val propertiesDropped = AtomicLong()

    fun snapshot(): Snapshot = Snapshot(
        droppedInMemory = droppedInMemory.get(),
        evictedFromStore = evictedFromStore.get(),
        evictedIdentify = evictedIdentify.get(),
        droppedOversized = droppedOversized.get(),
        droppedInvalid = droppedInvalid.get(),
        droppedStorageError = droppedStorageError.get(),
        rejectedByServer = rejectedByServer.get(),
        propertiesSanitized = propertiesSanitized.get(),
        propertiesDropped = propertiesDropped.get(),
    )

    data class Snapshot(
        val droppedInMemory: Long = 0,
        val evictedFromStore: Long = 0,
        val evictedIdentify: Long = 0,
        val droppedOversized: Long = 0,
        val droppedInvalid: Long = 0,
        val droppedStorageError: Long = 0,
        val rejectedByServer: Long = 0,
        val propertiesSanitized: Long = 0,
        val propertiesDropped: Long = 0,
    )
}
