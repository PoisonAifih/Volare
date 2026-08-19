package dev.aifih.volare.ui

import dev.aifih.volare.data.CursorApiException
import java.net.UnknownHostException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkRetryTest {

    @Test
    fun dnsFailureIsRetryable() {
        val error = UnknownHostException(
            "Unable to resolve host \"api.cursor.com\": No address associated with hostname",
        )

        assertTrue(error.isRetryableNetworkFailure())
    }

    @Test
    fun resourceExhaustedMessageIsRetryable() {
        assertTrue("[resource_exhausted] Error".isRetryableNetworkFailure())
    }

    @Test
    fun agentBusyIsNotRetryable() {
        val error = CursorApiException(409, "agent_busy", "Agent is busy")

        assertFalse(error.isRetryableNetworkFailure())
    }

    @Test
    fun validationErrorIsNotRetryable() {
        assertFalse("Prompt is empty".isRetryableNetworkFailure())
    }
}
