package io.github.dduddupark.minianalytics

import android.os.Build
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Robolectric 설정을 확인한다.
 *
 * - `org.json`은 JVM 단위 테스트에서 Android stub이라 동작하지 않으므로,
 *   org.json을 쓰는 테스트는 Robolectric으로 실행한다 (0단계 결정).
 */
@RunWith(RobolectricTestRunner::class)
class RobolectricSetupTest {
    @Test
    fun orgJsonWorksUnderRobolectric() {
        val json = JSONObject().put("event", "Button Clicked").put("count", 1)
        val parsed = JSONObject(json.toString())
        assertEquals("Button Clicked", parsed.getString("event"))
        assertEquals(1, parsed.getInt("count"))
    }

    @Test
    fun runsOnConfiguredSdk() {
        assertTrue("SDK_INT=${Build.VERSION.SDK_INT}", Build.VERSION.SDK_INT >= 23)
    }
}
