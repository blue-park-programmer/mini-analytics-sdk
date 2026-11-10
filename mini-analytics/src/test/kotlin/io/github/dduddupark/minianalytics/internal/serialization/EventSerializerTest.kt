package io.github.dduddupark.minianalytics.internal.serialization

import io.github.dduddupark.minianalytics.internal.model.Command
import io.github.dduddupark.minianalytics.internal.model.Identity
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** `org.json`을 쓰므로 Robolectric으로 실행한다 (0단계 결정). */
@RunWith(RobolectricTestRunner::class)
class EventSerializerTest {

    private val serializer = EventSerializer(Iso8601UtcFormatter())
    private val anonymous = Identity(anonymousId = "anon-1", userId = null)
    private val identified = Identity(anonymousId = "anon-1", userId = "user-1")

    private fun success(result: SerializationResult): SerializationResult.Success {
        assertTrue("expected Success but was $result", result is SerializationResult.Success)
        return result as SerializationResult.Success
    }

    private fun parse(result: SerializationResult) = JSONObject(success(result).json)

    @Test
    fun `track message has the documented fields`() {
        val json = parse(
            serializer.serialize(
                Command.Track("m-1", 0L, "Button Clicked", mapOf("screen" to "home")),
                anonymous,
            ),
        )
        assertEquals(
            setOf("messageId", "type", "event", "properties", "userId", "anonymousId", "timestamp"),
            json.keys().asSequence().toSet(),
        )
        assertEquals("m-1", json.getString("messageId"))
        assertEquals("track", json.getString("type"))
        assertEquals("Button Clicked", json.getString("event"))
        assertEquals("home", json.getJSONObject("properties").getString("screen"))
        assertEquals("anon-1", json.getString("anonymousId"))
        assertEquals("1970-01-01T00:00:00.000Z", json.getString("timestamp"))
    }

    @Test
    fun `anonymous user is serialized as explicit null userId`() {
        val json = parse(serializer.serialize(Command.Track("m", 0L, "e", emptyMap()), anonymous))
        assertTrue(json.has("userId"))
        assertTrue(json.isNull("userId"))
    }

    @Test
    fun `identity snapshot is written into the message`() {
        val json = parse(serializer.serialize(Command.Track("m", 0L, "e", emptyMap()), identified))
        assertEquals("user-1", json.getString("userId"))
    }

    @Test
    fun `screen message uses name instead of event`() {
        val json = parse(serializer.serialize(Command.Screen("m", 0L, "Home", emptyMap()), anonymous))
        assertEquals("screen", json.getString("type"))
        assertEquals("Home", json.getString("name"))
        assertFalse(json.has("event"))
        assertTrue(json.has("properties"))
    }

    @Test
    fun `identify message has no event name or properties`() {
        val json = parse(
            serializer.serialize(Command.Identify("m", 0L, "user-1", callback = null), identified),
        )
        assertEquals(
            setOf("messageId", "type", "userId", "anonymousId", "timestamp"),
            json.keys().asSequence().toSet(),
        )
        assertEquals("identify", json.getString("type"))
        assertEquals("user-1", json.getString("userId"))
    }

    @Test
    fun `nested properties keep structure and precision`() {
        val properties = mapOf(
            "long" to Long.MAX_VALUE,
            "double" to 1.25,
            "nullValue" to null,
            "list" to listOf(1, "two", mapOf("three" to true)),
        )
        val json = parse(serializer.serialize(Command.Track("m", 0L, "e", properties), anonymous))
            .getJSONObject("properties")
        assertEquals(Long.MAX_VALUE, json.getLong("long"))
        assertEquals(1.25, json.getDouble("double"), 0.0)
        assertTrue(json.isNull("nullValue"))
        val list = json.getJSONArray("list")
        assertEquals(1, list.getInt(0))
        assertEquals("two", list.getString(1))
        assertTrue(list.getJSONObject(2).getBoolean("three"))
    }

    @Test
    fun `size is measured in utf-8 bytes`() {
        val result = success(
            serializer.serialize(Command.Track("m", 0L, "e", mapOf("k" to "가나다")), anonymous),
        )
        assertEquals(result.json.toByteArray(Charsets.UTF_8).size, result.sizeBytes)
        assertTrue(result.sizeBytes > result.json.length) // 한글은 3바이트
    }

    @Test
    fun `message at exactly 32KB is accepted and one byte more is oversized`() {
        val base = success(
            serializer.serialize(Command.Track("m", 0L, "e", mapOf("p" to "")), anonymous),
        ).sizeBytes
        val fill = EventSerializer.MAX_EVENT_BYTES - base

        // properties 값(8,192자 제한) 여러 개로 정확히 32KB를 채운다.
        fun propertiesWithExtraBytes(extra: Int): Map<String, Any?> {
            val chunk = 8_000
            val values = linkedMapOf<String, Any?>("p" to "x".repeat(extra % chunk))
            var remaining = extra - extra % chunk
            var i = 0
            while (remaining > 0) {
                // 새 key 하나당 `,"qN":"..."` 오버헤드가 붙으므로 그만큼 값에서 뺀다.
                val key = "q$i"
                val overhead = ",\"$key\":\"\"".length
                values[key] = "x".repeat(chunk - overhead)
                remaining -= chunk
                i++
            }
            return values
        }

        val exact = serializer.serialize(Command.Track("m", 0L, "e", propertiesWithExtraBytes(fill)), anonymous)
        assertEquals(EventSerializer.MAX_EVENT_BYTES, success(exact).sizeBytes)

        val over = serializer.serialize(Command.Track("m", 0L, "e", propertiesWithExtraBytes(fill + 1)), anonymous)
        assertTrue("expected Oversized but was $over", over is SerializationResult.Oversized)
        assertEquals(EventSerializer.MAX_EVENT_BYTES + 1, (over as SerializationResult.Oversized).sizeBytes)
    }

    @Test
    fun `unexpected property type becomes Invalid instead of throwing`() {
        // Sanitizer를 거치지 않은 값이 들어오는 버그 상황을 흉내 낸다.
        val result = serializer.serialize(Command.Track("m", 0L, "e", mapOf("bad" to Any())), anonymous)
        assertTrue(result is SerializationResult.Invalid)
    }
}
