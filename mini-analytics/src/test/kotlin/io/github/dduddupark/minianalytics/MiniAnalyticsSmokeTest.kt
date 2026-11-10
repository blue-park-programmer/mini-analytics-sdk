package io.github.dduddupark.minianalytics

import io.github.dduddupark.minianalytics.internal.BuildInfo
import org.junit.Assert.assertEquals
import org.junit.Test

/** 순수 Kotlin 로직용 일반 JUnit 테스트가 동작하는지 확인한다. */
class MiniAnalyticsSmokeTest {
    @Test
    fun libraryInfoIsDefined() {
        assertEquals("mini-analytics-android", BuildInfo.LIBRARY_NAME)
        assertEquals("0.1.0-SNAPSHOT", BuildInfo.LIBRARY_VERSION)
    }
}
