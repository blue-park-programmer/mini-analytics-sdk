package io.github.dduddupark.minianalytics.internal.storage

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import io.github.dduddupark.minianalytics.internal.model.Identity
import io.github.dduddupark.minianalytics.internal.model.MessageType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * [SqliteEventStore]: 계약 테스트 + SQLite 고유의 동작 (영속성, 원자성, journal 모드, 손상 처리).
 *
 * 최소 지원 SDK(23)와 기본 테스트 SDK(34)에서 실행한다. SDK 35 이상은 Robolectric에서 JDK 21이 필요해서 제외했다 (0단계 S0-2).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 34])
internal class SqliteEventStoreTest : EventStoreContract() {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    override fun createStore(): EventStore = SqliteEventStore(context, DB_NAME)

    private fun reopen(): EventStore {
        store.close()
        store = SqliteEventStore(context, DB_NAME)
        return store
    }

    @Test
    fun constructorDoesNotTouchTheDisk() {
        val name = "untouched.db"
        context.deleteDatabase(name)
        val untouched = SqliteEventStore(context, name)
        assertFalse(context.getDatabasePath(name).exists())
        untouched.counts() // 첫 호출에서 연다
        assertTrue(context.getDatabasePath(name).exists())
        untouched.close()
    }

    @Test
    fun dataSurvivesReopen() {
        commit(event(), event(MessageType.IDENTIFY), identity = identityA)
        val before = allEvents()

        reopen()

        assertEquals(identityA, store.loadIdentity())
        assertEquals(before, allEvents())
    }

    @Test
    fun idsAreNotReusedAcrossReopenEvenWhenTableBecameEmpty() {
        commit(event(), event())
        val maxBefore = allEvents().maxOf { it.id }
        store.deleteByIds(allEvents().map { it.id })

        reopen()
        commit(event())

        assertTrue(allEvents().single().id > maxBefore)
    }

    @Test
    fun failureBeforeCommitRollsBackEventsAndIdentityTogether() {
        commit(event(), identity = identityA)
        val eventsBefore = allEvents()
        store.close()

        var fail = true
        store = SqliteEventStore(context, DB_NAME, beforeCommit = { if (fail) throw IllegalStateException("boom") })
        val newIdentity = StoredIdentity(Identity("anon-2", null), version = 2)
        try {
            commit(event(), event(MessageType.IDENTIFY), identity = newIdentity, maxEvents = 1)
            fail("expected failure")
        } catch (expected: IllegalStateException) {
        }

        // 삽입, Identity 변경, 상한 정리 모두 반영되지 않았다.
        assertEquals(identityA, store.loadIdentity())
        assertEquals(eventsBefore, allEvents())

        // 같은 트랜잭션을 다시 시도하면 정상적으로 반영된다.
        fail = false
        commit(event(), event(MessageType.IDENTIFY), identity = newIdentity, maxEvents = 1)
        assertEquals(newIdentity, store.loadIdentity())
        assertEquals(StoreCounts(events = 1, identifies = 1), store.counts())
    }

    @Test
    fun journalModeIsNotWal() {
        store.counts() // DB 열기
        val db = SQLiteDatabase.openDatabase(
            context.getDatabasePath(DB_NAME).path, null, SQLiteDatabase.OPEN_READONLY,
        )
        val mode = db.rawQuery("PRAGMA journal_mode", null).use { it.moveToFirst(); it.getString(0) }
        db.close()
        assertNotEquals("wal", mode.lowercase())
    }

    @Test
    fun corruptedDatabaseIsRecreated() {
        commit(event(), identity = identityA)
        store.close()

        // DB 파일을 쓰레기 값으로 덮어써서 손상을 흉내 낸다.
        val file = context.getDatabasePath(DB_NAME)
        file.writeBytes(ByteArray(4_096) { 0x5A })

        store = SqliteEventStore(context, DB_NAME)
        // 기본 DatabaseErrorHandler가 파일을 지우고 새로 만든다: 이전 데이터는 사라진다 (설계 §8.2, L-8).
        assertNull(store.loadIdentity())
        assertEquals(StoreCounts(0, 0), store.counts())
        commit(event())
        assertEquals(1, store.counts().events)
    }

    @Test
    fun nullUserIdRoundTrips() {
        val anonymous = StoredIdentity(Identity("anon", null), version = 7)
        commit(identity = anonymous)
        reopen()
        assertEquals(anonymous, store.loadIdentity())
    }

    private companion object {
        const val DB_NAME = "test_events.db"
    }
}
