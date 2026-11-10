package io.github.dduddupark.minianalytics

import io.github.dduddupark.minianalytics.internal.config.maskApiKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigurationTest {

    private val apiKey = "pk_live_1234567890"

    @Test
    fun `builder applies documented defaults`() {
        val configuration = Configuration.Builder(apiKey, "https://example.com").build()
        assertEquals(20, configuration.flushAt)
        assertEquals(30_000L, configuration.flushIntervalMillis)
        assertEquals(1_000, configuration.maxQueuedEvents)
    }

    @Test
    fun `invalid_event responses are not trusted by default`() {
        assertEquals(false, Configuration.Builder(apiKey, "https://example.com").build().trustInvalidEventResponses)
        assertEquals(
            true,
            Configuration.Builder(apiKey, "https://example.com").trustInvalidEventResponses(true).build()
                .trustInvalidEventResponses,
        )
    }

    @Test
    fun `trust setting takes part in equality and differences`() {
        val off = Configuration.Builder(apiKey, "https://example.com").build()
        val on = Configuration.Builder(apiKey, "https://example.com").trustInvalidEventResponses(true).build()
        assertNotEquals(off, on)
        assertEquals(listOf("trustInvalidEventResponses: false -> true"), off.describeDifferences(on))
        assertTrue(on.toString().contains("trustInvalidEventResponses=true"))
    }

    @Test
    fun `build never throws even for invalid values`() {
        Configuration.Builder("", "not a url").flushAt(-1).flushIntervalMillis(-1).maxQueuedEvents(-1).build()
    }

    @Test
    fun `equal configurations are equal`() {
        val a = Configuration.Builder(apiKey, "https://example.com").flushAt(5).build()
        val b = Configuration.Builder(apiKey, "https://example.com").flushAt(5).build()
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(a, Configuration.Builder(apiKey, "https://example.com").flushAt(6).build())
    }

    @Test
    fun `toString masks the api key`() {
        val text = Configuration.Builder(apiKey, "https://example.com").build().toString()
        assertFalse(text.contains(apiKey))
        assertTrue(text.contains("pk****90"))
    }

    @Test
    fun `short api keys are fully masked`() {
        assertEquals("****", maskApiKey("short"))
        assertEquals("****", maskApiKey("12345678901"))
        assertEquals("12****12", maskApiKey("123456789012"))
    }

    @Test
    fun `describeDifferences lists changed fields with masked api key`() {
        val a = Configuration.Builder(apiKey, "https://a.example.com").build()
        val b = Configuration.Builder("pk_test_0987654321", "https://b.example.com").flushAt(10).build()
        val differences = a.describeDifferences(b)
        assertEquals(3, differences.size)
        assertTrue(differences.none { it.contains(apiKey) || it.contains("pk_test_0987654321") })
        assertTrue(differences.any { it.startsWith("endpoint:") })
        assertTrue(differences.any { it.startsWith("flushAt: 20 -> 10") })
    }

    @Test
    fun `null api key and endpoint are handled in differences and masking`() {
        val nulls = Configuration.Builder(null, null).build()
        val filled = Configuration.Builder(apiKey, "https://example.com").build()
        val differences = nulls.describeDifferences(filled)
        assertEquals(listOf("apiKey: null -> pk****90", "endpoint: null -> https://example.com"), differences)
        assertTrue(nulls.toString().contains("apiKey=null"))
    }

    @Test
    fun `no differences for equal configurations`() {
        val a = Configuration.Builder(apiKey, "https://example.com").build()
        assertTrue(a.describeDifferences(Configuration.Builder(apiKey, "https://example.com").build()).isEmpty())
    }
}
