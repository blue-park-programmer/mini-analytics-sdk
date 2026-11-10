package io.github.dduddupark.minianalytics.internal.serialization

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale
import java.util.TimeZone

class Iso8601UtcFormatterTest {

    @Test
    fun `epoch zero`() {
        assertEquals("1970-01-01T00:00:00.000Z", Iso8601UtcFormatter().format(0L))
    }

    @Test
    fun `keeps milliseconds`() {
        // 2026-11-04T09:59:58.001Z
        assertEquals("2026-11-04T09:59:58.001Z", Iso8601UtcFormatter().format(1_793_786_398_001L))
    }

    @Test
    fun `output does not depend on the default time zone or locale`() {
        val originalZone = TimeZone.getDefault()
        val originalLocale = Locale.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"))
            // 아라비아 숫자가 아닌 숫자 체계를 쓰는 로케일에서도 ASCII 숫자가 나와야 한다.
            Locale.setDefault(Locale.forLanguageTag("ar-EG"))
            assertEquals("1970-01-01T00:00:00.000Z", Iso8601UtcFormatter().format(0L))
        } finally {
            TimeZone.setDefault(originalZone)
            Locale.setDefault(originalLocale)
        }
    }
}
