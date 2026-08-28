package com.newoether.agora.diagnostics

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** Produces local-only diagnostic exports from the credential-sanitized capture snapshot. */
object DiagnosticBundleExporter {
    private val json = Json {
        classDiscriminator = "payloadType"
        encodeDefaults = true
        prettyPrint = true
    }

    fun export(
        snapshot: DiagnosticSnapshot,
        format: DiagnosticExportFormat,
        generatedAtMillis: Long = System.currentTimeMillis(),
    ): String = when (format) {
        DiagnosticExportFormat.RAW_JSON -> exportJson(
            snapshot = snapshot,
            format = format,
            generatedAtMillis = generatedAtMillis,
            redactContent = false,
        )
        DiagnosticExportFormat.REDACTED_JSON -> exportJson(
            snapshot = snapshot,
            format = format,
            generatedAtMillis = generatedAtMillis,
            redactContent = true,
        )
        DiagnosticExportFormat.SUMMARY_TEXT -> exportSummary(
            snapshot = snapshot,
            generatedAtMillis = generatedAtMillis,
        )
    }

    private fun exportJson(
        snapshot: DiagnosticSnapshot,
        format: DiagnosticExportFormat,
        generatedAtMillis: Long,
        redactContent: Boolean,
    ): String {
        val root = buildJsonObject {
            put("schemaVersion", 1)
            put("generatedAtMillis", generatedAtMillis)
            put("format", format.name)
            put("captureActive", snapshot.isCaptureActive)
            put("droppedEventCount", snapshot.droppedEventCount)
            put("eventCount", snapshot.events.size)
            snapshot.session?.let { session ->
                putJsonObject("session") {
                    put("id", session.id)
                    put("mode", session.mode.name)
                    put("startedAtMillis", session.startedAtMillis)
                    session.stoppedAtMillis?.let { put("stoppedAtMillis", it) }
                }
            }
            putJsonArray("events") {
                snapshot.events.forEach { event ->
                    val exported = if (redactContent) event.redactContent() else event
                    add(json.encodeToJsonElement(DiagnosticEvent.serializer(), exported))
                }
            }
        }
        return json.encodeToString(JsonObject.serializer(), root)
    }

    private fun exportSummary(
        snapshot: DiagnosticSnapshot,
        generatedAtMillis: Long,
    ): String = buildString {
        appendLine("Agora Diagnostic Capture Summary")
        appendLine("schemaVersion=1")
        appendLine("generatedAtMillis=$generatedAtMillis")
        appendLine("captureActive=${snapshot.isCaptureActive}")
        appendLine("captureMode=${snapshot.session?.mode?.name.orEmpty()}")
        appendLine("eventCount=${snapshot.events.size}")
        appendLine("droppedEventCount=${snapshot.droppedEventCount}")
        snapshot.session?.let { session ->
            appendLine("sessionId=${session.id}")
            appendLine("startedAtMillis=${session.startedAtMillis}")
            session.stoppedAtMillis?.let { appendLine("stoppedAtMillis=$it") }
        }
        snapshot.events.forEach { event ->
            append('#')
            append(event.sequence)
            append(" timestampMillis=")
            append(event.timestampMillis)
            event.context.requestKind?.let { append(" requestKind=$it") }
            event.context.provider?.let { append(" provider=$it") }
            event.context.model?.let { append(" model=$it") }
            event.context.pass?.let { append(" pass=$it") }
            append(" type=")
            append(event.payload.typeName())
            append(event.payload.summaryAttributes())
            appendLine()
        }
    }

    private fun DiagnosticEvent.redactContent(): DiagnosticEvent = copy(
        payload = when (val current = payload) {
            is DiagnosticEventPayload.RuntimeTransition,
            is DiagnosticEventPayload.HttpStage -> current
            is DiagnosticEventPayload.HttpRequest -> current.copy(
                body = DiagnosticRedactor.redactJsonContent(current.body),
            )
            is DiagnosticEventPayload.HttpResponseBody -> current.copy(
                body = DiagnosticRedactor.redactJsonContent(current.body),
            )
            is DiagnosticEventPayload.WireLine -> current.copy(
                line = DiagnosticRedactor.redactWireContent(current.line),
            )
            is DiagnosticEventPayload.ParsedStreamEvent -> current.copy(
                content = current.content?.let(DiagnosticRedactor::redactContent),
            )
        },
    )

    private fun DiagnosticEventPayload.typeName(): String = when (this) {
        is DiagnosticEventPayload.RuntimeTransition -> "RuntimeTransition"
        is DiagnosticEventPayload.HttpStage -> "HttpStage"
        is DiagnosticEventPayload.HttpRequest -> "HttpRequest"
        is DiagnosticEventPayload.HttpResponseBody -> "HttpResponseBody"
        is DiagnosticEventPayload.WireLine -> "WireLine"
        is DiagnosticEventPayload.ParsedStreamEvent -> "ParsedStreamEvent"
    }

    private fun DiagnosticEventPayload.summaryAttributes(): String = when (this) {
        is DiagnosticEventPayload.RuntimeTransition ->
            " commandType=$commandType oldState=$oldState newState=$newState"
        is DiagnosticEventPayload.HttpStage ->
            " stage=$stage elapsedMillis=$elapsedMillis" + attributes.summaryPairs()
        is DiagnosticEventPayload.HttpRequest -> " method=$method bodyChars=${body.originalLength}"
        is DiagnosticEventPayload.HttpResponseBody -> " code=$code bodyChars=${body.originalLength}"
        is DiagnosticEventPayload.WireLine ->
            " lineNumber=$lineNumber lineChars=${line.originalLength}"
        is DiagnosticEventPayload.ParsedStreamEvent ->
            " eventType=$eventType" + attributes.summaryPairs()
    }

    private fun Map<String, String>.summaryPairs(): String =
        entries.joinToString(separator = "", prefix = "") { (key, value) -> " $key=$value" }
}
