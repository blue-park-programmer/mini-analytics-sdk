package io.github.dduddupark.minianalytics.internal.config

import io.github.dduddupark.minianalytics.Configuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigurationValidatorTest {

    private fun builder(apiKey: String = "key", endpoint: String = "https://example.com/v1/batch") =
        Configuration.Builder(apiKey, endpoint)

    private fun errors(configuration: Configuration, allowInsecure: Boolean = false) =
        ConfigurationValidator.validate(configuration, allowInsecure)

    @Test
    fun `defaults with https endpoint are valid`() {
        assertEquals(emptyList<String>(), errors(builder().build()))
    }

    @Test
    fun `blank api key is rejected`() {
        assertEquals(1, errors(builder(apiKey = "  ").build()).size)
    }

    @Test
    fun `http endpoint is rejected unless explicitly allowed for tests`() {
        val http = builder(endpoint = "http://10.0.2.2:8080/batch").build()
        assertTrue(errors(http).single().contains("https"))
        assertEquals(emptyList<String>(), errors(http, allowInsecure = true))
    }

    @Test
    fun `other schemes and malformed endpoints are rejected`() {
        listOf("ftp://example.com", "example.com/batch", "https://", "https://exa mple.com", "")
            .forEach { endpoint ->
                assertEquals("endpoint=$endpoint", 1, errors(builder(endpoint = endpoint).build()).size)
            }
    }

    @Test
    fun `scheme check is case insensitive`() {
        assertEquals(emptyList<String>(), errors(builder(endpoint = "HTTPS://example.com").build()))
    }

    @Test
    fun `flushAt must be between 1 and maxQueuedEvents`() {
        assertEquals(1, errors(builder().flushAt(0).build()).size)
        assertEquals(1, errors(builder().maxQueuedEvents(10).flushAt(11).build()).size)
        assertEquals(emptyList<String>(), errors(builder().maxQueuedEvents(10).flushAt(10).build()))
    }

    @Test
    fun `flush interval has a lower bound`() {
        assertEquals(1, errors(builder().flushIntervalMillis(999).build()).size)
        assertEquals(emptyList<String>(), errors(builder().flushIntervalMillis(1_000).build()))
    }

    @Test
    fun `maxQueuedEvents must be between 1 and 10000`() {
        assertTrue(errors(builder().maxQueuedEvents(0).flushAt(1).build()).isNotEmpty())
        assertEquals(1, errors(builder().maxQueuedEvents(10_001).build()).size)
        assertEquals(emptyList<String>(), errors(builder().maxQueuedEvents(10_000).build()))
    }

    @Test
    fun `all violations are reported together`() {
        val configuration = builder(apiKey = "", endpoint = "http://x").flushAt(0).flushIntervalMillis(1).build()
        assertEquals(4, errors(configuration).size)
    }
}
