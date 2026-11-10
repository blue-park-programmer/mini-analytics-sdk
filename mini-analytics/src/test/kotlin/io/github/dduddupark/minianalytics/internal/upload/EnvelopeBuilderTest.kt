package io.github.dduddupark.minianalytics.internal.upload

import io.github.dduddupark.minianalytics.internal.model.MessageType
import io.github.dduddupark.minianalytics.internal.storage.StoredEvent
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class EnvelopeBuilderTest {

    private fun event(id: Long, payload: String) = StoredEvent(id, "m-$id", MessageType.TRACK, payload, payload.length)

    @Test
    fun `stored payloads are embedded as is and in order`() {
        var now = 0L
        val builder = EnvelopeBuilder({ "{\"library\":{\"name\":\"x\"}}" }, { now })
        now = 1_793_786_398_001L
        val json = JSONObject(
            String(builder.build(listOf(event(1, "{\"messageId\":\"a\",\"n\":1}"), event(2, "{\"messageId\":\"b\"}")))),
        )
        assertEquals("2026-11-04T09:59:58.001Z", json.getString("sentAt"))
        assertEquals("x", json.getJSONObject("context").getJSONObject("library").getString("name"))
        val batch = json.getJSONArray("batch")
        assertEquals(listOf("a", "b"), (0 until batch.length()).map { batch.getJSONObject(it).getString("messageId") })
        assertEquals(1, batch.getJSONObject(0).getInt("n"))
    }

    @Test
    fun `sentAt is taken per request`() {
        var now = 0L
        val builder = EnvelopeBuilder({ "{}" }, { now })
        val first = JSONObject(String(builder.build(listOf(event(1, "{}"))))).getString("sentAt")
        now = 5_000L
        val second = JSONObject(String(builder.build(listOf(event(1, "{}"))))).getString("sentAt")
        assertEquals("1970-01-01T00:00:00.000Z", first)
        assertEquals("1970-01-01T00:00:05.000Z", second)
    }

    @Test
    fun `android context contains only library os and app versions`() {
        val context = JSONObject(EnvelopeBuilder.androidContextJson(RuntimeEnvironment.getApplication()))
        assertEquals(setOf("library", "os", "app"), context.keys().asSequence().toSet())
        assertEquals("mini-analytics-android", context.getJSONObject("library").getString("name"))
        assertEquals("Android", context.getJSONObject("os").getString("name"))
        assertTrue(context.getJSONObject("app").has("version"))
        assertFalse(context.toString().contains("model"))
    }
}
