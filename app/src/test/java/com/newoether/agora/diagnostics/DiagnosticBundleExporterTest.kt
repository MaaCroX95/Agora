package com.newoether.agora.diagnostics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticBundleExporterTest {
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
                    requestKind = "chat",
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
            state = DiagnosticCaptureState.RUNNING,
            session = DiagnosticSession(
                id = SESSION_ID,
                startedAtMillis = 1L,
            ),
            events = events,
            nextSequence = 4L,
            retainedPayloadBytes = 321L,
            droppedEventCount = 2L,
            evictedEventCount = 3L,
            truncatedPayloadCount = 4L,
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
        assertTrue(raw.contains("captureState"))
        assertTrue(raw.contains("RUNNING"))
        assertTrue(raw.contains("nextSequence"))
        assertTrue(raw.contains("retainedPayloadBytes"))
        assertTrue(raw.contains("evictedEventCount"))
        assertTrue(raw.contains("truncatedPayloadCount"))
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

    private companion object {
        const val SESSION_ID = "raw-session-id"
        const val REQUEST_ID = "raw-request-id"
    }
}
