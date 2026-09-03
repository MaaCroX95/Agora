package com.newoether.agora.api

import com.newoether.agora.api.anthropic.AnthropicProvider
import com.newoether.agora.api.gemini.GeminiProvider
import com.newoether.agora.api.ollama.OllamaProvider
import com.newoether.agora.api.openai.BaseOpenAiProvider
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.Participant
import com.newoether.agora.util.DebugLog
import com.sun.net.httpserver.HttpServer
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockkObject
import io.mockk.unmockkObject
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ProviderRetryRequestResolutionTest {
    @Before
    fun disableAndroidLoggingForJvmNetworkTests() {
        mockkObject(DebugLog)
        every { DebugLog.d(any(), any()) } just Runs
        every { DebugLog.e(any(), any()) } just Runs
        every { DebugLog.w(any(), any()) } just Runs
    }

    @After
    fun restoreAndroidLogging() {
        unmockkObject(DebugLog)
    }

    @Test
    fun openAiRetryResolvesRequestAgain() = withServer(
        contentType = "text/event-stream",
        successBody = listOf(
            "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}",
            "data: [DONE]",
        ).joinToString("\n\n", postfix = "\n\n"),
    ) { server ->
        val provider = object : BaseOpenAiProvider() {
            override val name = "test-openai"
            override val defaultBaseUrl = server.baseUrl
            override val terminalSseGraceMillis = 100L
            override fun retryDelayMillis(attempt: Int) = 1L
        }
        assertRetryResolvesAgain(
            provider = provider,
            config = config(server, modelId = "gpt-test"),
            server = server,
            timeoutMillis = 3_000L,
        )
    }

    @Test
    fun anthropicRetryResolvesRequestAgain() = withServer(
        contentType = "text/event-stream",
        successBody = listOf(
            "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}",
            "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"ok\"}}",
            "{\"type\":\"content_block_stop\",\"index\":0}",
            "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"}}",
            "{\"type\":\"message_stop\"}",
        ).joinToString("\n\n", postfix = "\n\n") { "data: $it" },
    ) { server ->
        assertRetryResolvesAgain(
            provider = AnthropicProvider(defaultBaseUrl = server.baseUrl),
            config = config(
                server,
                modelId = "claude-3-5-sonnet-20240620",
            ),
            server = server,
        )
    }

    @Test
    fun geminiRetryResolvesRequestAgain() = withServer(
        contentType = "text/event-stream",
        successBody = """
            data: {"candidates":[{"content":{"parts":[{"text":"ok"}]},"finishReason":"STOP"}]}

        """.trimIndent(),
    ) { server ->
        assertRetryResolvesAgain(
            provider = GeminiProvider(defaultBaseUrl = server.baseUrl),
            config = config(server, modelId = "gemini-test"),
            server = server,
        )
    }

    @Test
    fun ollamaRetryResolvesRequestAgain() = withServer(
        contentType = "application/x-ndjson",
        successBody = listOf(
            "{\"model\":\"test\",\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},\"done\":false}",
            "{\"model\":\"test\",\"done\":true,\"done_reason\":\"stop\",\"prompt_eval_count\":1,\"eval_count\":1}",
        ).joinToString("\n", postfix = "\n"),
    ) { server ->
        assertRetryResolvesAgain(
            provider = OllamaProvider(),
            config = config(server, modelId = "llama3"),
            server = server,
        )
    }

    private fun assertRetryResolvesAgain(
        provider: LlmProvider,
        config: ProviderConfig,
        server: RetryServer,
        timeoutMillis: Long = 10_000L,
    ) {
        val resolverCalls = AtomicInteger()
        val messages = listOf(
            ChatMessage(id = "user", text = "hello", participant = Participant.USER),
        )
        val events = runBlocking {
            withTimeout(timeoutMillis) {
                provider.generateResponse(
                    messages,
                    config.copy(
                        requestResolver = ProviderRequestResolver { rawMessages, _ ->
                            ProviderRequestInput(
                                messages = rawMessages,
                                systemPrompt = "system-${resolverCalls.incrementAndGet()}",
                            )
                        },
                    ),
                ).toList()
            }
        }

        assertTrue(events.none { it is StreamEvent.Error })
        assertEquals(1, events.filterIsInstance<StreamEvent.Retrying>().size)
        assertEquals(2, resolverCalls.get())
        assertEquals(2, server.bodies.size)
        assertTrue(server.bodies[0].contains("system-1"))
        assertTrue(server.bodies[0].contains("system-2").not())
        assertTrue(server.bodies[1].contains("system-2"))
        assertTrue(server.bodies[1].contains("system-1").not())
    }

    private fun config(server: RetryServer, modelId: String) = ProviderConfig(
        apiKey = "",
        modelId = modelId,
        baseUrl = server.baseUrl,
        thinkingEnabled = false,
    )

    private fun withServer(
        contentType: String,
        successBody: String,
        test: (RetryServer) -> Unit,
    ) = RetryServer(contentType, successBody).use(test)

    private class RetryServer(
        private val contentType: String,
        private val successBody: String,
    ) : AutoCloseable {
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        private val attempts = AtomicInteger()
        val bodies = CopyOnWriteArrayList<String>()
        val baseUrl = "http://127.0.0.1:${server.address.port}"

        init {
            server.createContext("/") { exchange ->
                bodies += exchange.requestBody.bufferedReader().use { it.readText() }
                val attempt = attempts.incrementAndGet()
                val status = if (attempt == 1) 503 else 200
                val response = if (status == 503) {
                    "{\"error\":\"retry\"}"
                } else {
                    successBody
                }.toByteArray()
                exchange.responseHeaders.add(
                    "Content-Type",
                    if (status == 503) "application/json" else contentType,
                )
                exchange.sendResponseHeaders(status, response.size.toLong())
                exchange.responseBody.use { it.write(response) }
            }
            server.start()
        }

        override fun close() = server.stop(0)
    }
}
