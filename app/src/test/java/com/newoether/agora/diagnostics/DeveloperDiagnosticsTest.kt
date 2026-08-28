package com.newoether.agora.diagnostics

import com.newoether.agora.api.StreamEvent
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeveloperDiagnosticsTest {
    @After
    fun resetDiagnostics() {
        DeveloperDiagnostics.stopAndClear()
    }

    @Test
    fun `request context is not created while capture is disabled`() {
        assertNull(requestContext())
    }

    @Test
    fun `metadata session correlates request identity without retaining raw conversation id`() {
        DeveloperDiagnostics.startMetadataCapture()

        val context = checkNotNull(requestContext())
        DeveloperDiagnostics.recordHttpStage(
            context = context,
            stage = "response_headers",
            elapsedMillis = 42L,
            detail = "code=200 authorization=secret endpoint=/private",
        )

        val snapshot = DeveloperDiagnostics.snapshots.value
        val event = snapshot.events.single()
        val stage = event.payload as DiagnosticEventPayload.HttpStage
        assertTrue(snapshot.isCaptureActive)
        assertEquals("request-id", event.context.requestId)
        assertEquals("run-id", event.context.runId)
        assertEquals(3, event.context.pass)
        assertEquals("provider", event.context.provider)
        assertEquals("provider:model", event.context.model)
        assertEquals("send", event.context.requestKind)
        assertEquals(24, event.context.conversationIdHash?.length)
        assertFalse(event.context.conversationIdHash.orEmpty().contains(CONVERSATION_ID))
        assertEquals("response_headers", stage.stage)
        assertEquals(42L, stage.elapsedMillis)
        assertEquals(mapOf("code" to "200"), stage.attributes)
    }

    @Test
    fun `metadata mode records parsed event shape but never request or event content`() {
        DeveloperDiagnostics.startMetadataCapture()
        val context = checkNotNull(requestContext())

        DeveloperDiagnostics.recordHttpRequest(
            context = context,
            method = "POST",
            url = "https://example.com?key=secret",
            headers = mapOf("Authorization" to "Bearer secret-value"),
            body = """{"content":"private"}""",
        )
        DeveloperDiagnostics.recordParsedStreamEvent(
            context,
            StreamEvent.TextChunk("private"),
        )

        val events = DeveloperDiagnostics.snapshots.value.events
        assertEquals(1, events.size)
        val parsed = events.single().payload as DiagnosticEventPayload.ParsedStreamEvent
        assertEquals("TextChunk", parsed.eventType)
        assertEquals(mapOf("chars" to "7"), parsed.attributes)
        assertNull(parsed.content)
    }

    @Test
    fun `raw content capture correlates request wire and parsed views while removing credentials`() {
        DeveloperDiagnostics.startSensitiveContentCapture()
        val context = checkNotNull(requestContext())
        DeveloperDiagnostics.recordHttpRequest(
            context = context,
            method = "POST",
            url = "https://example.com/chat?key=query-secret",
            headers = mapOf("Authorization" to "Bearer header-secret"),
            body = """{"content":"private request","api_key":"body-secret"}""",
        )
        DeveloperDiagnostics.recordWireLine(
            context = context,
            lineNumber = 1L,
            line = """data: {"text":"private response"}""",
        )
        DeveloperDiagnostics.recordParsedStreamEvent(
            context,
            StreamEvent.TextChunk("private response"),
        )

        val events = DeveloperDiagnostics.snapshots.value.events
        assertEquals(3, events.size)
        val retained = events.joinToString()
        assertFalse(retained.contains("query-secret"))
        assertFalse(retained.contains("header-secret"))
        assertFalse(retained.contains("body-secret"))
        assertTrue(retained.contains("private request"))
        assertTrue(retained.contains("private response"))
        assertTrue(retained.contains("[REDACTED_SECRET]"))
        assertFalse(retained.contains("[REDACTED_CONTENT]"))
    }

    @Test
    fun `sensitive content mode preserves semantic content but still redacts token patterns`() {
        DeveloperDiagnostics.startSensitiveContentCapture()
        val context = checkNotNull(requestContext())

        DeveloperDiagnostics.recordParsedStreamEvent(
            context,
            StreamEvent.TextChunk("visible text sk-abcdefghijklmnop"),
        )

        val parsed = DeveloperDiagnostics.snapshots.value.events.single()
            .payload as DiagnosticEventPayload.ParsedStreamEvent
        assertTrue(parsed.content?.value.orEmpty().contains("visible text"))
        assertFalse(parsed.content?.value.orEmpty().contains("sk-abcdefghijklmnop"))
    }

    private fun requestContext() = DeveloperDiagnostics.newRequestContext(
        requestId = "request-id",
        conversationId = CONVERSATION_ID,
        runId = "run-id",
        pass = 3,
        provider = "provider",
        model = "provider:model",
        requestKind = "send",
    )

    private companion object {
        const val CONVERSATION_ID = "private-conversation-id"
    }
}
