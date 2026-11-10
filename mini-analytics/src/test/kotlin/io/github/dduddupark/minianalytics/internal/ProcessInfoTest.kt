package io.github.dduddupark.minianalytics.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProcessInfoTest {
    @Test
    fun `main process is detected by comparing with the application process name`() {
        assertEquals(true, ProcessInfo.isMainProcess("com.example", "com.example"))
        assertEquals(false, ProcessInfo.isMainProcess("com.example:remote", "com.example"))
    }

    @Test
    fun `unknown names give no answer instead of a false warning`() {
        assertNull(ProcessInfo.isMainProcess(null, "com.example"))
        assertNull(ProcessInfo.isMainProcess("com.example", null))
        assertNull(ProcessInfo.isMainProcess("", "com.example"))
    }
}
