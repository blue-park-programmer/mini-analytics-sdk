package io.github.dduddupark.minianalytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** Kotlin 쪽 사용성. Java 쪽은 src/test/java의 IdentityCallbackJavaUsage가 컴파일되는지로 확인한다. */
class IdentityResultTest {

    @Test
    fun `callback can be written as a kotlin lambda and handled with when`() {
        var observed: String? = null
        val callback = IdentityCallback { result ->
            observed = when (result) {
                is IdentityResult.Success -> "success"
                is IdentityResult.Failure -> "failure:${result.reason}"
            }
        }
        callback.onComplete(IdentityResult.Success)
        assertEquals("success", observed)
        callback.onComplete(IdentityResult.Failure(FailureReason.STORAGE_ERROR, null))
        assertEquals("failure:STORAGE_ERROR", observed)
    }

    @Test
    fun `failure exposes reason and cause`() {
        val cause = IllegalStateException("disk")
        val failure = IdentityResult.Failure(FailureReason.STORAGE_ERROR, cause)
        assertEquals(FailureReason.STORAGE_ERROR, failure.reason)
        assertSame(cause, failure.cause)
        assertNull(IdentityResult.Failure(FailureReason.NOT_INITIALIZED, null).cause)
    }

    @Test
    fun `java interop sample compiles and behaves the same`() {
        assertEquals("success", IdentityCallbackJavaUsage.describe(IdentityResult.Success))
        assertEquals(
            "failure:NOT_INITIALIZED",
            IdentityCallbackJavaUsage.describe(IdentityResult.Failure(FailureReason.NOT_INITIALIZED, null)),
        )
    }
}
