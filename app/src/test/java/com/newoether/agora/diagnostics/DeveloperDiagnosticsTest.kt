package com.newoether.agora.diagnostics

import com.newoether.agora.api.StreamEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.nio.file.Files

class DeveloperDiagnosticsTest {
    @After
    fun resetDiagnostics() = runBlocking {
        DeveloperDiagnostics.disableAndClear()
        Unit
    }

    @Test
    fun `request context is not created while capture is disabled`() {
        assertNull(requestContext())
    }

    @Test
    fun `capture correlates request identity without retaining raw conversation id`() {
        runBlocking { DeveloperDiagnostics.startCapture() }

        val context = checkNotNull(requestContext())
        DeveloperDiagnostics.recordHttpStage(
            context = context,
            stage = "response_headers",
            elapsedMillis = 42L,
            detail = "code=200 authorization=secret endpoint=/private",
        )

        val snapshot = runBlocking { DeveloperDiagnostics.flush() }
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
    fun `raw capture correlates request wire and parsed views while removing credentials`() {
        runBlocking { DeveloperDiagnostics.startCapture() }
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

        val events = runBlocking { DeveloperDiagnostics.flush() }.events
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
    fun `parsed semantic content is retained after credential token sanitization`() {
        runBlocking { DeveloperDiagnostics.startCapture() }
        val context = checkNotNull(requestContext())

        DeveloperDiagnostics.recordParsedStreamEvent(
            context,
            StreamEvent.TextChunk("visible text sk-abcdefghijklmnop"),
        )

        val parsed = runBlocking { DeveloperDiagnostics.flush() }.events.single()
            .payload as DiagnosticEventPayload.ParsedStreamEvent
        assertTrue(parsed.content?.value.orEmpty().contains("visible text"))
        assertFalse(parsed.content?.value.orEmpty().contains("sk-abcdefghijklmnop"))
        assertTrue(parsed.content?.value.orEmpty().contains("[REDACTED_SECRET]"))
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

    companion object {
        private const val CONVERSATION_ID = "private-conversation-id"
        private lateinit var root: File
        private lateinit var scope: CoroutineScope

        @JvmStatic
        @BeforeClass
        fun initializeDiagnostics() {
            root = Files.createTempDirectory("agora-developer-diagnostics").toFile()
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            runBlocking {
                DeveloperDiagnostics.initialize(root, scope)
                DeveloperDiagnostics.disableAndClear()
            }
        }

        @JvmStatic
        @AfterClass
        fun shutdownDiagnostics() {
            runBlocking { DeveloperDiagnostics.disableAndClear() }
            scope.cancel()
            root.deleteRecursively()
        }
    }
}
