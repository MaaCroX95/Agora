package com.newoether.agora.api.gemini

import android.content.Context
import android.content.pm.ApplicationInfo
import com.newoether.agora.api.GenerationError
import com.newoether.agora.api.ProviderConfig
import com.newoether.agora.api.StreamEvent
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.Participant
import com.newoether.agora.util.DebugLog
import com.sun.net.httpserver.HttpServer
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress

class GeminiStreamTerminationTest {
    @Before
    fun disableAndroidLoggingForJvmNetworkTests() {
        val context = mockk<Context>()
        every { context.applicationInfo } returns ApplicationInfo().apply { flags = 0 }
        DebugLog.forceEnabled = false
        DebugLog.init(context)
    }

    @Test
    fun finishReasonProvesSemanticCompletion() {
        val termination = geminiStreamTermination(
            sawDone = false,
            finishReason = normalizeGeminiFinishReason("STOP"),
            producedContent = true,
        )

        assertTrue(termination.sawTerminalMarker)
        assertEquals("stop", termination.stopReason)
        assertNull(termination.toError("Gemini"))
    }

    @Test
    fun eofWithoutFinishReasonIsIncompleteAndOnlyEmptyAttemptRetries() {
        val empty = geminiStreamTermination(false, null, false)
        val partial = geminiStreamTermination(false, null, true)

        assertTrue(empty.isRetryable)
        assertFalse(partial.isRetryable)
        assertTrue(partial.toError("Gemini") is GenerationError.IncompleteStream)
    }

    @Test
    fun outputCapIsReportedWithoutPointlessReplay() {
        val termination = geminiStreamTermination(
            sawDone = false,
            finishReason = normalizeGeminiFinishReason("MAX_TOKENS"),
            producedContent = true,
        )

        assertFalse(termination.isRetryable)
        assertTrue(termination.toError("Gemini") is GenerationError.OutputTruncated)
    }

    @Test
    fun streamErrorRetriesOnlyBeforeVisibleOutput() {
        val error = GenerationError.Api(null, "failed_to_generate", "failed to generate")

        assertTrue(geminiStreamTermination(false, null, false, error).isRetryable)
        assertFalse(geminiStreamTermination(false, null, true, error).isRetryable)
    }

    @Test
    fun timeoutUsesSharedTerminationPolicy() {
        val termination = geminiStreamTermination(
            sawDone = false,
            finishReason = null,
            producedContent = false,
            timedOut = true,
        )

        assertTrue(termination.isRetryable)
        assertEquals(GenerationError.Timeout, termination.toError("Gemini"))
    }

    @Test
    fun functionCallPartsEmitStreamingUpdatesBeforeTheirRequests() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            exchange.requestBody.use { it.readBytes() }
            val response = (
                "data: {\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[" +
                    "{\"functionCall\":{\"id\":\"call-1\",\"name\":\"file_read\"," +
                    "\"args\":{\"path\":\"a.txt\"}}}," +
                    "{\"functionCall\":{\"id\":\"call-2\",\"name\":\"file_read\"," +
                    "\"args\":{\"path\":\"b.txt\"}}}]},\"finishReason\":\"STOP\"}]}\n\n"
                ).toByteArray()
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        server.start()
        try {
            val events = runBlocking {
                withTimeout(2_000L) {
                    GeminiProvider().generateResponse(
                        messages = listOf(
                            ChatMessage(text = "inspect", participant = Participant.USER),
                        ),
                        config = ProviderConfig(
                            apiKey = "test-key",
                            modelId = "gemini-2.5-flash",
                            baseUrl = "http://127.0.0.1:${server.address.port}",
                        ),
                    ).toList()
                }
            }

            val toolEvents = events.filter {
                it is StreamEvent.ToolCallUpdate || it is StreamEvent.ToolCallRequest
            }
            assertEquals(4, toolEvents.size)
            val firstUpdate = toolEvents[0] as StreamEvent.ToolCallUpdate
            val firstRequest = toolEvents[1] as StreamEvent.ToolCallRequest
            val secondUpdate = toolEvents[2] as StreamEvent.ToolCallUpdate
            val secondRequest = toolEvents[3] as StreamEvent.ToolCallRequest
            assertEquals(listOf("call-1", "call-2"), listOf(firstUpdate.id, secondUpdate.id))
            assertEquals(firstUpdate.streamKey, firstRequest.streamKey)
            assertEquals(secondUpdate.streamKey, secondRequest.streamKey)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun terminalMarkerCannotCompletePendingCodeExecution() {
        val termination = geminiStreamTermination(
            sawDone = true,
            finishReason = "stop",
            producedContent = true,
            toolCallInFlight = true,
        )

        assertFalse(termination.isRetryable)
        assertTrue(termination.toError("Gemini") is GenerationError.IncompleteStream)
    }

    @Test
    fun terminalMarkerCannotCompleteAnInvalidOpenFunctionCall() {
        val error = GenerationError.SseParse(
            rawLine = "functionCall",
            cause = "Gemini returned a blank or invalid tool name",
        )
        val termination = geminiStreamTermination(
            sawDone = true,
            finishReason = "stop",
            producedContent = false,
            streamError = error,
            toolCallInFlight = true,
        )

        assertTrue(termination.isRetryable)
        assertEquals(error, termination.toError("Gemini"))
    }
}
