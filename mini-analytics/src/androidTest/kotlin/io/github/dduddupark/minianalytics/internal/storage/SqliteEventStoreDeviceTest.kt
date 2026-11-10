package io.github.dduddupark.minianalytics.internal.storage

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Build
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.dduddupark.minianalytics.IdentityResult
import io.github.dduddupark.minianalytics.internal.model.Identity
import io.github.dduddupark.minianalytics.internal.model.MessageType
import io.github.dduddupark.minianalytics.internal.pipeline.CallbackDispatcher
import io.github.dduddupark.minianalytics.internal.pipeline.MainThreadExecutor
import io.github.dduddupark.minianalytics.internal.log.AndroidLogger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 실제 기기/에뮬레이터의 SQLite에서 확인한다 (7단계). Robolectric은 기기의 SQLite 설정
 * (특히 Android 9+의 compatibility WAL)과 손상 처리기를 그대로 재현하지 않는다 (A-7).
 */
@RunWith(AndroidJUnit4::class)
class SqliteEventStoreDeviceTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dbName = "device_test_events.db"
    private lateinit var store: SqliteEventStore

    @Before
    fun setUp() {
        context.deleteDatabase(dbName)
        store = SqliteEventStore(context, dbName)
    }

    @After
    fun tearDown() {
        store.close()
        context.deleteDatabase(dbName)
    }

    private fun event(id: String, type: MessageType = MessageType.TRACK) = NewEvent(id, type, "{\"messageId\":\"$id\"}", 20)

    private fun commit(vararg events: NewEvent, identity: StoredIdentity? = null) =
        store.commit(StoreTransaction(events.toList(), identity, StoreLimits(1_000)))

    @Test
    fun journalModeIsNotWalOnThisDevice() {
        store.counts()
        val db = SQLiteDatabase.openDatabase(context.getDatabasePath(dbName).path, null, SQLiteDatabase.OPEN_READONLY)
        val mode = db.rawQuery("PRAGMA journal_mode", null).use { it.moveToFirst(); it.getString(0) }
        db.close()
        assertNotEquals("journal_mode on API ${Build.VERSION.SDK_INT}", "wal", mode.lowercase())
    }

    @Test
    fun dataAndIdentitySurviveReopen() {
        val identity = StoredIdentity(Identity("anon", "user"), 3)
        commit(event("a"), event("b", MessageType.IDENTIFY), identity = identity)
        store.close()
        store = SqliteEventStore(context, dbName)
        assertEquals(identity, store.loadIdentity())
        assertEquals(listOf("a", "b"), store.readBatch(10, Int.MAX_VALUE).map { it.messageId })
    }

    @Test
    fun failureBeforeCommitRollsBackEverything() {
        commit(event("a"), identity = StoredIdentity(Identity("anon", null), 1))
        store.close()
        store = SqliteEventStore(context, dbName, beforeCommit = { throw IllegalStateException("boom") })
        try {
            commit(event("b"), identity = StoredIdentity(Identity("anon-2", "u"), 2))
            fail("expected failure")
        } catch (expected: IllegalStateException) {
        }
        assertEquals(Identity("anon", null), store.loadIdentity()!!.identity)
        assertEquals(listOf("a"), store.readBatch(10, Int.MAX_VALUE).map { it.messageId })
    }

    @Test
    fun idsAreNotReusedAfterReopenWithEmptyTable() {
        commit(event("a"), event("b"))
        val max = store.readBatch(10, Int.MAX_VALUE).maxOf { it.id }
        store.deleteByIds(store.readBatch(10, Int.MAX_VALUE).map { it.id })
        store.close()
        store = SqliteEventStore(context, dbName)
        commit(event("c"))
        assertTrue(store.readBatch(10, Int.MAX_VALUE).single().id > max)
    }

    @Test
    fun corruptedFileIsRecreatedByTheDefaultErrorHandler() {
        commit(event("a"), identity = StoredIdentity(Identity("anon", "u"), 1))
        store.close()
        context.getDatabasePath(dbName).writeBytes(ByteArray(4_096) { 0x5A })
        store = SqliteEventStore(context, dbName)
        assertNull(store.loadIdentity())
        assertEquals(0, store.counts().total)
    }

    @Test
    fun mainThreadExecutorDeliversOnTheRealMainLooper() {
        val latch = CountDownLatch(1)
        var onMain = false
        CallbackDispatcher(MainThreadExecutor(), AndroidLogger).deliver({ _: IdentityResult ->
            onMain = Looper.myLooper() == Looper.getMainLooper()
            latch.countDown()
        }, IdentityResult.Success)
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        assertTrue(onMain)
    }
}
