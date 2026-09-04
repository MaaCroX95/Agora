package com.newoether.agora.api.openai

import com.newoether.agora.api.OpenAiMessage
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class OpenAiWebToolNameCompatibilityTest {
    @Test
    fun responsesRawReplayNamespacesLegacyGenericWebFunctionCall() {
        val legacy = JsonObject(
            mapOf(
                "type" to JsonPrimitive("function_call"),
                "call_id" to JsonPrimitive("call_legacy"),
                "name" to JsonPrimitive("web_fetch"),
                "arguments" to JsonPrimitive("{\"url\":\"https://example.test\"}"),
            )
        )
        val message = OpenAiMessage(
            role = "assistant",
            responseOutputItems = listOf(legacy),
            responseOutputItemProvider = "NanoGPT",
        )

        val input = listOf(message).toResponsesInput(providerName = "NanoGPT")

        assertEquals("function_call", (input.single()["type"] as JsonPrimitive).content)
        assertEquals("agora_web_fetch", (input.single()["name"] as JsonPrimitive).content)
    }

    @Test
    fun responsesReplayDoesNotRewriteHostedSearchItems() {
        val hosted = JsonObject(
            mapOf(
                "type" to JsonPrimitive("web_search_call"),
                "id" to JsonPrimitive("ws_1"),
            )
        )
        val message = OpenAiMessage(
            role = "assistant",
            responseOutputItems = listOf(hosted),
            responseOutputItemProvider = "NanoGPT",
        )

        val input = listOf(message).toResponsesInput(providerName = "NanoGPT")

        assertEquals(hosted, input.single())
    }
}
