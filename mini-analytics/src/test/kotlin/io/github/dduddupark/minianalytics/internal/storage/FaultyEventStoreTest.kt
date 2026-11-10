package io.github.dduddupark.minianalytics.internal.storage

import io.github.dduddupark.minianalytics.internal.model.Identity
import io.github.dduddupark.minianalytics.internal.model.MessageType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

/** 3단계 테스트가 의존할 오류 주입 도구 자체가 의도대로 동작하는지 확인한다. */
class FaultyEventStoreTest {

    private val delegate = InMemoryEventStore()
    private val store = FaultyEventStore(delegate)
    private val transaction = StoreTransaction(
        events = listOf(NewEvent("m-1", MessageType.TRACK, "{}", 2)),
        identity = StoredIdentity(Identity("anon", "user"), 1),
        limits = StoreLimits(maxQueuedEvents = 10),
    )

    @Test
    fun failedCommitAppliesNothingAndLaterCommitSucceeds() {
        store.failNextCommits = 2
        repeat(2) { expectFailure { store.commit(transaction) } }
        assertEquals(0, delegate.counts().total)
        assertNull(delegate.loadIdentity())

        store.commit(transaction)
        assertEquals(1, delegate.counts().total)
        assertEquals(3, store.commitAttempts)
    }

    @Test
    fun failAllCommitsKeepsFailing() {
        store.failAllCommits = true
        repeat(5) { expectFailure { store.commit(transaction) } }
        assertEquals(0, delegate.counts().total)
    }

    @Test
    fun loadFailureIsInjected() {
        store.failNextLoads = 1
        expectFailure { store.loadIdentity() }
        assertNull(store.loadIdentity())
    }

    private fun expectFailure(block: () -> Unit) {
        try {
            block()
            fail("expected injected failure")
        } catch (expected: IllegalStateException) {
        }
    }
}
