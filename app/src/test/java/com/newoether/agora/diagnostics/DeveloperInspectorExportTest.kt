package com.newoether.agora.diagnostics

import com.newoether.agora.data.CustomProviderConfig
import com.newoether.agora.model.ChatConversation
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.ConversationRuntimeTraceEntry
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeveloperInspectorExportTest {
    @Test
    fun `conversation inspector retains metadata but no raw ids or message content`() {
        val conversation = ChatConversation(
            id = CONVERSATION_ID,
            title = "private title",
            modelId = "provider:model",
            origin = "user",
        )
        val messages = listOf(
            ChatMessage(
                id = MESSAGE_ID,
                text = PRIVATE_TEXT,
                thoughts = "private thought",
                participant = Participant.USER,
                status = MessageStatus.SUCCESS,
                runId = RUN_ID,
                tokenCount = 12,
            ),
        )

        val inspection = checkNotNull(
            DeveloperConversationInspector.inspect(
                conversation = conversation,
                messages = messages,
                totalTokens = 12,
                isLoading = false,
                runtimeTransitions = listOf(
                    ConversationRuntimeTraceEntry(
                        sequence = 1L,
                        conversationIdHash = "existing-hash",
                        runId = RUN_ID,
                        pass = 2,
                        effectId = EFFECT_ID,
                        oldState = "Idle",
                        commandType = "SendRequested",
                        newState = "Preparing",
                        effectTypes = listOf("PersistAcceptedInput"),
                        timestamp = 10L,
                    ),
                ),
            ),
        )
        val formatted = DeveloperConversationInspector.format(inspection)

        assertEquals(1, inspection.messageCount)
        assertEquals(PRIVATE_TEXT.length, inspection.messages.single().textChars)
        assertEquals(15, inspection.messages.single().thoughtChars)
        assertEquals(24, inspection.conversationIdHash.length)
        assertFalse(formatted.contains(CONVERSATION_ID))
        assertFalse(formatted.contains(MESSAGE_ID))
        assertFalse(formatted.contains(RUN_ID))
        assertFalse(formatted.contains(EFFECT_ID))
        assertFalse(formatted.contains(PRIVATE_TEXT))
        assertFalse(formatted.contains("private thought"))
        assertFalse(formatted.contains("private title"))
    }

    @Test
    fun `three export formats share sanitized raw data and apply exact content policy`() {
        val providerId = "custom-provider-00000000-0000-4000-8000-000000000001"
        val requestBody = DiagnosticRedactor.captureJson(
            """{"messages":[{"role":"user","content":"private prompt"}],"api_key":"request-secret","max_tokens":10}""",
        )
        val events = listOf(
            DiagnosticEvent(
                sequence = 1L,
                timestampMillis = 10L,
                context = DiagnosticRequestContext(
                    requestId = REQUEST_ID,
                    provider = providerId,
                    model = "$providerId:model",
                    requestKind = "send",
                ),
                payload = DiagnosticEventPayload.HttpRequest(
                    method = "POST",
                    url = DiagnosticRedactor.captureUrl(
                        "https://example.invalid/chat?key=query-secret&model=test",
                    ),
                    headers = DiagnosticRedactor.captureHeaders(
                        mapOf("Authorization" to "Bearer header-secret"),
                    ),
                    body = requestBody,
                ),
            ),
            DiagnosticEvent(
                sequence = 2L,
                timestampMillis = 11L,
                context = DiagnosticRequestContext(requestId = REQUEST_ID),
                payload = DiagnosticEventPayload.WireLine(
                    lineNumber = 1L,
                    line = DiagnosticRedactor.captureWireLine(
                        """data: {"text":"private response","result":"private tool result"}""",
                    ),
                ),
            ),
            DiagnosticEvent(
                sequence = 3L,
                timestampMillis = 12L,
                context = DiagnosticRequestContext(requestId = REQUEST_ID),
                payload = DiagnosticEventPayload.ParsedStreamEvent(
                    eventType = "HostedToolCallUpdate",
                    attributes = mapOf("name" to "fixture_tool", "resultChars" to "19"),
                    content = DiagnosticRedactor.captureContent("private parsed tool result"),
                ),
            ),
        )
        val snapshot = DiagnosticSnapshot(
            session = DiagnosticSession(
                id = SESSION_ID,
                mode = DiagnosticCaptureMode.SENSITIVE_CONTENT,
                startedAtMillis = 1L,
            ),
            events = events,
            droppedEventCount = 2L,
        )

        val raw = DiagnosticBundleExporter.export(
            snapshot = snapshot,
            format = DiagnosticExportFormat.RAW_JSON,
            generatedAtMillis = 20L,
        )
        val redacted = DiagnosticBundleExporter.export(
            snapshot = snapshot,
            format = DiagnosticExportFormat.REDACTED_JSON,
            generatedAtMillis = 20L,
        )
        val summary = DiagnosticBundleExporter.export(
            snapshot = snapshot,
            format = DiagnosticExportFormat.SUMMARY_TEXT,
            generatedAtMillis = 20L,
        )

        assertTrue(raw.contains("private prompt"))
        assertTrue(raw.contains("private response"))
        assertTrue(raw.contains("private parsed tool result"))
        assertTrue(raw.contains(providerId))
        assertFalse(raw.contains("request-secret"))
        assertFalse(raw.contains("query-secret"))
        assertFalse(raw.contains("header-secret"))
        assertTrue(raw.contains("[REDACTED_SECRET]"))

        assertFalse(redacted.contains("private prompt"))
        assertFalse(redacted.contains("private response"))
        assertFalse(redacted.contains("private tool result"))
        assertFalse(redacted.contains("private parsed tool result"))
        assertTrue(redacted.contains("[REDACTED_CONTENT]"))
        assertTrue(redacted.contains("max_tokens"))
        assertTrue(redacted.contains("fixture_tool"))

        assertTrue(summary.contains("eventCount=3"))
        assertTrue(summary.contains("droppedEventCount=2"))
        assertTrue(summary.contains("type=HttpRequest"))
        assertTrue(summary.contains("type=ParsedStreamEvent"))
        assertFalse(summary.contains("private prompt"))
        assertFalse(summary.contains("private response"))
        assertFalse(summary.contains("private parsed tool result"))
    }

    @Test
    fun `diagnostic display projection replaces custom provider ids with current alias`() {
        val providerId = "custom-provider-00000000-0000-4000-8000-000000000001"
        val providers = listOf(CustomProviderConfig(name = "Relay Alias", id = providerId))
        val rawBody = """{"model":"$providerId:model"}"""
        val rawSnapshot = DiagnosticSnapshot(
            events = listOf(
                DiagnosticEvent(
                    sequence = 1L,
                    timestampMillis = 1L,
                    context = DiagnosticRequestContext(
                        provider = providerId,
                        model = "$providerId:model",
                    ),
                    payload = DiagnosticEventPayload.HttpRequest(
                        method = "POST",
                        url = CapturedDiagnosticText(
                            value = "https://example.invalid",
                            originalLength = 23,
                            truncated = false,
                            redacted = true,
                        ),
                        headers = mapOf("X-Provider" to providerId),
                        body = CapturedDiagnosticText(
                            value = rawBody,
                            originalLength = rawBody.length,
                            truncated = false,
                            redacted = true,
                        ),
                    ),
                ),
            ),
        )
        val displaySnapshot = rawSnapshot.forDisplay(providers)
        val displayInspection = checkNotNull(
            DeveloperConversationInspector.inspect(
                conversation = ChatConversation(
                    id = CONVERSATION_ID,
                    title = "diagnostic fixture",
                    modelId = "$providerId:model",
                    origin = "user",
                ),
                messages = emptyList(),
                totalTokens = 0,
                isLoading = false,
                runtimeTransitions = emptyList(),
            ),
        ).forDisplay(providers)

        assertEquals("Relay Alias", displaySnapshot.events.single().context.provider)
        assertEquals("Relay Alias:model", displaySnapshot.events.single().context.model)
        assertEquals("Relay Alias:model", displayInspection.model)
        assertFalse(displaySnapshot.toString().contains(providerId))
        assertFalse(displayInspection.toString().contains(providerId))
    }

    @Test
    fun `offline test lab fixtures all pass`() {
        val results = DeveloperTestLab.runAll()

        assertTrue(results.isNotEmpty())
        assertTrue(results.joinToString(), results.all(DeveloperTestResult::passed))
    }

    private companion object {
        const val CONVERSATION_ID = "raw-conversation-id"
        const val MESSAGE_ID = "raw-message-id"
        const val RUN_ID = "raw-run-id"
        const val EFFECT_ID = "raw-effect-id"
        const val SESSION_ID = "raw-session-id"
        const val REQUEST_ID = "raw-request-id"
        const val PRIVATE_TEXT = "private message text"
    }
}
