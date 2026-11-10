package io.github.dduddupark.minianalytics.internal.storage

/** 테스트용 저장소가 실제 SQLite 구현과 같은 계약을 지키는지 확인한다. 순수 Kotlin이라 일반 JUnit으로 실행한다. */
internal class InMemoryEventStoreTest : EventStoreContract() {
    override fun createStore(): EventStore = InMemoryEventStore()
}
