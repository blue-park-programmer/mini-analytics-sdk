package io.github.dduddupark.minianalytics.internal.storage

import android.content.ContentValues
import android.content.Context
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import io.github.dduddupark.minianalytics.internal.model.Identity
import io.github.dduddupark.minianalytics.internal.model.MessageType
import kotlin.math.ceil

/**
 * `SQLiteOpenHelper`를 직접 쓰는 [EventStore] 구현 (설계 §5, Q12).
 *
 * - 생성자는 디스크 I/O를 하지 않는다. DB는 첫 메서드 호출 때 연다.
 * - 손상된 DB는 `SQLiteOpenHelper`의 기본 `DatabaseErrorHandler`가 삭제하고 다시 만든다 (설계 §8.2).
 * - journal 모드는 rollback journal을 쓴다. Android 9 이상에서는 앱이 따로 지정하지 않으면
 *   "compatibility WAL"이 적용될 수 있으므로, WAL을 명시적으로 끈다 (설계 §5.1, R-4).
 *
 * @param beforeCommit 테스트 전용 훅. 트랜잭션의 모든 쓰기가 끝나고 commit 직전에 호출된다.
 *   여기서 예외를 던지면 트랜잭션 전체가 롤백되는지 검증할 수 있다.
 */
internal class SqliteEventStore(
    context: Context,
    databaseName: String = DEFAULT_DATABASE_NAME,
    private val beforeCommit: (() -> Unit)? = null,
) : EventStore {

    private val helper = Helper(context.applicationContext, databaseName)

    private val db: SQLiteDatabase get() = helper.writableDatabase

    override fun loadIdentity(): StoredIdentity? =
        db.rawQuery(
            "SELECT $COL_ANONYMOUS_ID, $COL_USER_ID, $COL_VERSION FROM $TABLE_IDENTITY WHERE $COL_ID = 1",
            null,
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            StoredIdentity(
                identity = Identity(
                    anonymousId = cursor.getString(0),
                    userId = if (cursor.isNull(1)) null else cursor.getString(1),
                ),
                version = cursor.getLong(2),
            )
        }

    override fun commit(transaction: StoreTransaction): CommitResult {
        val database = db
        database.beginTransaction()
        try {
            for (event in transaction.events) insertEvent(database, event)
            transaction.identity?.let { upsertIdentity(database, it) }
            val evictedEvents = evictOldest(database, EVENT_TYPES, transaction.limits.maxQueuedEvents)
            val evictedIdentifies =
                evictOldest(database, IDENTIFY_TYPES, transaction.limits.maxQueuedIdentifies)
            beforeCommit?.invoke()
            database.setTransactionSuccessful()
            return CommitResult(evictedEvents, evictedIdentifies)
        } finally {
            database.endTransaction()
        }
    }

    override fun readBatch(maxCount: Int, maxBytes: Int): List<StoredEvent> {
        if (maxCount <= 0) return emptyList()
        val result = ArrayList<StoredEvent>()
        var totalBytes = 0L
        db.rawQuery(
            "SELECT $COL_ID, $COL_MESSAGE_ID, $COL_TYPE, $COL_PAYLOAD, $COL_SIZE " +
                "FROM $TABLE_EVENTS ORDER BY $COL_ID LIMIT ?",
            arrayOf(maxCount.toString()),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val size = cursor.getInt(4)
                if (result.isNotEmpty() && totalBytes + size > maxBytes) break
                val typeName = cursor.getString(2)
                val type = MessageType.fromWireName(typeName)
                    ?: throw IllegalStateException("Unknown message type in store: $typeName")
                result += StoredEvent(
                    id = cursor.getLong(0),
                    messageId = cursor.getString(1),
                    type = type,
                    payload = cursor.getString(3),
                    sizeBytes = size,
                )
                totalBytes += size
            }
        }
        return result
    }

    override fun deleteByIds(ids: Collection<Long>): Int {
        if (ids.isEmpty()) return 0
        var deleted = 0
        // SQLite의 바인딩 변수 개수 제한(오래된 버전 999개)을 넘지 않도록 나눈다.
        for (chunk in ids.chunked(DELETE_CHUNK_SIZE)) {
            val placeholders = chunk.joinToString(",") { "?" }
            deleted += db.delete(
                TABLE_EVENTS,
                "$COL_ID IN ($placeholders)",
                chunk.map { it.toString() }.toTypedArray(),
            )
        }
        return deleted
    }

    override fun reclaimSpace(fraction: Double): Int {
        val database = db
        val count = countByTypes(database, EVENT_TYPES)
        if (count == 0L) return 0
        val toDelete = ceil(count * fraction.coerceIn(0.0, 1.0)).toLong().coerceIn(1L, count)
        return deleteOldest(database, EVENT_TYPES, toDelete)
    }

    override fun counts(): StoreCounts {
        val database = db
        return StoreCounts(
            events = countByTypes(database, EVENT_TYPES).toInt(),
            identifies = countByTypes(database, IDENTIFY_TYPES).toInt(),
        )
    }

    override fun close() {
        helper.close()
    }

    private fun insertEvent(database: SQLiteDatabase, event: NewEvent) {
        val values = ContentValues().apply {
            put(COL_MESSAGE_ID, event.messageId)
            put(COL_TYPE, event.type.wireName)
            put(COL_PAYLOAD, event.payload)
            put(COL_SIZE, event.sizeBytes)
        }
        // 같은 messageId는 무시한다: 이미 commit된 트랜잭션을 재시도해도 결과가 같게 한다.
        database.insertWithOnConflict(TABLE_EVENTS, null, values, SQLiteDatabase.CONFLICT_IGNORE)
    }

    private fun upsertIdentity(database: SQLiteDatabase, stored: StoredIdentity) {
        val values = ContentValues().apply {
            put(COL_ID, 1)
            put(COL_ANONYMOUS_ID, stored.identity.anonymousId)
            if (stored.identity.userId == null) putNull(COL_USER_ID) else put(COL_USER_ID, stored.identity.userId)
            put(COL_VERSION, stored.version)
        }
        database.insertWithOnConflict(TABLE_IDENTITY, null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private fun evictOldest(database: SQLiteDatabase, types: Array<String>, max: Int): Int {
        val excess = countByTypes(database, types) - max
        return if (excess > 0) deleteOldest(database, types, excess) else 0
    }

    private fun deleteOldest(database: SQLiteDatabase, types: Array<String>, count: Long): Int =
        database.delete(
            TABLE_EVENTS,
            "$COL_ID IN (SELECT $COL_ID FROM $TABLE_EVENTS WHERE ${typeFilter(types)} " +
                "ORDER BY $COL_ID LIMIT $count)",
            types,
        )

    private fun countByTypes(database: SQLiteDatabase, types: Array<String>): Long =
        DatabaseUtils.longForQuery(
            database,
            "SELECT COUNT(*) FROM $TABLE_EVENTS WHERE ${typeFilter(types)}",
            types,
        )

    private fun typeFilter(types: Array<String>): String =
        "$COL_TYPE IN (${types.joinToString(",") { "?" }})"

    private class Helper(context: Context, name: String) :
        SQLiteOpenHelper(context, name, null, SCHEMA_VERSION) {

        init {
            setWriteAheadLoggingEnabled(false)
        }

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE $TABLE_EVENTS (" +
                    "$COL_ID INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "$COL_MESSAGE_ID TEXT NOT NULL UNIQUE, " +
                    "$COL_TYPE TEXT NOT NULL, " +
                    "$COL_PAYLOAD TEXT NOT NULL, " +
                    "$COL_SIZE INTEGER NOT NULL)",
            )
            db.execSQL("CREATE INDEX events_type_id ON $TABLE_EVENTS ($COL_TYPE, $COL_ID)")
            db.execSQL(
                "CREATE TABLE $TABLE_IDENTITY (" +
                    "$COL_ID INTEGER PRIMARY KEY CHECK ($COL_ID = 1), " +
                    "$COL_ANONYMOUS_ID TEXT NOT NULL, " +
                    "$COL_USER_ID TEXT, " +
                    "$COL_VERSION INTEGER NOT NULL)",
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // v1에는 이전 스키마가 없다. 스키마를 바꿀 때 마이그레이션을 추가한다.
        }
    }

    internal companion object {
        const val DEFAULT_DATABASE_NAME = "mini_analytics.db"
        private const val SCHEMA_VERSION = 1
        private const val DELETE_CHUNK_SIZE = 500

        private const val TABLE_EVENTS = "events"
        private const val TABLE_IDENTITY = "identity"
        private const val COL_ID = "id"
        private const val COL_MESSAGE_ID = "message_id"
        private const val COL_TYPE = "type"
        private const val COL_PAYLOAD = "payload"
        private const val COL_SIZE = "size"
        private const val COL_ANONYMOUS_ID = "anonymous_id"
        private const val COL_USER_ID = "user_id"
        private const val COL_VERSION = "version"

        private val EVENT_TYPES = arrayOf(MessageType.TRACK.wireName, MessageType.SCREEN.wireName)
        private val IDENTIFY_TYPES = arrayOf(MessageType.IDENTIFY.wireName)
    }
}
