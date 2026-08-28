package com.newoether.agora.diagnostics

import com.newoether.agora.api.StreamEvent
import com.newoether.agora.model.ConversationRuntimeTrace
import com.newoether.agora.model.ConversationRuntimeTraceEntry
import kotlinx.coroutines.flow.StateFlow

/**
 * Explicit, process-local diagnostics facade.
 *
 * Developer Options enablement is persisted elsewhere. Capture itself deliberately starts off for
 * every process and can only be activated from the Developer Options surface.
 */
object DeveloperDiagnostics {
    private val buffer = DiagnosticEventBuffer()

    val snapshots: StateFlow<DiagnosticSnapshot> = buffer.snapshots
    val isCaptureActive: Boolean get() = buffer.isCaptureActive
    val activeMode: DiagnosticCaptureMode? get() = buffer.activeMode

    fun startMetadataCapture(): DiagnosticSession =
        buffer.start(DiagnosticCaptureMode.METADATA)

    fun startRedactedContentCapture(): DiagnosticSession =
        buffer.start(DiagnosticCaptureMode.REDACTED_CONTENT)

    fun startSensitiveContentCapture(): DiagnosticSession =
        buffer.start(DiagnosticCaptureMode.SENSITIVE_CONTENT)

    fun stopCapture() = buffer.stop()

    fun clear() = buffer.clear()

    fun stopAndClear() = buffer.stopAndClear()

    fun newRequestContext(
        requestId: String,
        conversationId: String,
        runId: String,
        pass: Int,
        provider: String,
        model: String,
        requestKind: String,
    ): DiagnosticRequestContext? {
        if (!buffer.isCaptureActive) return null
        return DiagnosticRequestContext(
            requestId = DiagnosticRedactor.safeIdentifier(requestId).take(MAX_IDENTIFIER_LENGTH),
            conversationIdHash = ConversationRuntimeTrace.hashConversationId(conversationId),
            runId = DiagnosticRedactor.safeIdentifier(runId).take(MAX_IDENTIFIER_LENGTH),
            pass = pass,
            provider = DiagnosticRedactor.safeIdentifier(provider).take(MAX_IDENTIFIER_LENGTH),
            model = DiagnosticRedactor.safeIdentifier(model).take(MAX_IDENTIFIER_LENGTH),
            requestKind = DiagnosticRedactor.safeIdentifier(requestKind).take(MAX_IDENTIFIER_LENGTH),
        )
    }

    fun recordRuntimeTransition(entry: ConversationRuntimeTraceEntry) {
        if (!buffer.isCaptureActive) return
        buffer.record { sequence, timestampMillis ->
            DiagnosticEvent(
                sequence = sequence,
                timestampMillis = timestampMillis,
                context = DiagnosticRequestContext(
                    conversationIdHash = entry.conversationIdHash,
                    runId = entry.runId,
                    pass = entry.pass,
                ),
                payload = DiagnosticEventPayload.RuntimeTransition(
                    oldState = entry.oldState,
                    commandType = entry.commandType,
                    newState = entry.newState,
                    effectId = entry.effectId,
                    effectTypes = entry.effectTypes,
                ),
            )
        }
    }

    fun recordHttpStage(
        context: DiagnosticRequestContext?,
        stage: String,
        elapsedMillis: Long,
        detail: String,
    ) {
        if (context == null || !buffer.isCaptureActive) return
        buffer.record { sequence, timestampMillis ->
            DiagnosticEvent(
                sequence = sequence,
                timestampMillis = timestampMillis,
                context = context,
                payload = DiagnosticEventPayload.HttpStage(
                    stage = stage.take(MAX_STAGE_LENGTH),
                    elapsedMillis = elapsedMillis.coerceAtLeast(0L),
                    attributes = safeHttpAttributes(detail),
                ),
            )
        }
    }

    fun recordHttpRequest(
        context: DiagnosticRequestContext?,
        method: String,
        url: String,
        headers: Map<String, String>,
        body: String,
    ) {
        val mode = contentCaptureMode() ?: return
        if (context == null) return
        buffer.record { sequence, timestampMillis ->
            DiagnosticEvent(
                sequence = sequence,
                timestampMillis = timestampMillis,
                context = context,
                payload = DiagnosticEventPayload.HttpRequest(
                    method = method.take(16),
                    url = DiagnosticRedactor.captureUrl(url),
                    headers = DiagnosticRedactor.captureHeaders(headers),
                    body = DiagnosticRedactor.captureJson(body),
                ),
            )
        }
    }

    fun recordHttpResponseBody(
        context: DiagnosticRequestContext?,
        code: Int,
        body: String,
    ) {
        val mode = contentCaptureMode() ?: return
        if (context == null) return
        buffer.record { sequence, timestampMillis ->
            DiagnosticEvent(
                sequence = sequence,
                timestampMillis = timestampMillis,
                context = context,
                payload = DiagnosticEventPayload.HttpResponseBody(
                    code = code,
                    body = DiagnosticRedactor.captureJson(body),
                ),
            )
        }
    }

    fun recordWireLine(
        context: DiagnosticRequestContext?,
        lineNumber: Long,
        line: String,
    ) {
        val mode = contentCaptureMode() ?: return
        if (context == null) return
        buffer.record { sequence, timestampMillis ->
            DiagnosticEvent(
                sequence = sequence,
                timestampMillis = timestampMillis,
                context = context,
                payload = DiagnosticEventPayload.WireLine(
                    lineNumber = lineNumber,
                    line = DiagnosticRedactor.captureWireLine(line),
                ),
            )
        }
    }

    fun recordParsedStreamEvent(
        context: DiagnosticRequestContext?,
        event: StreamEvent,
    ) {
        val mode = buffer.activeMode ?: return
        if (context == null) return
        buffer.record { sequence, timestampMillis ->
            val details = event.diagnosticDetails(mode)
            DiagnosticEvent(
                sequence = sequence,
                timestampMillis = timestampMillis,
                context = context,
                payload = DiagnosticEventPayload.ParsedStreamEvent(
                    eventType = details.eventType,
                    attributes = details.attributes,
                    content = details.content,
                ),
            )
        }
    }

    /**
     * Only explicitly approved transport metadata enters the timeline. Unknown future detail keys
     * are discarded, which prevents an accidental RequestTrace call from becoming a content leak.
     */
    internal fun safeHttpAttributes(detail: String): Map<String, String> {
        if (detail.isBlank()) return emptyMap()
        return DETAIL_PAIR.findAll(detail)
            .mapNotNull { match ->
                val key = match.groupValues[1]
                val value = match.groupValues[2]
                if (key !in SAFE_HTTP_ATTRIBUTE_KEYS) {
                    null
                } else {
                    key to DiagnosticRedactor.safeIdentifier(value).take(MAX_ATTRIBUTE_LENGTH)
                }
            }
            .toMap()
    }

    private fun contentCaptureMode(): DiagnosticCaptureMode? =
        buffer.activeMode?.takeUnless { it == DiagnosticCaptureMode.METADATA }

    private fun StreamEvent.diagnosticDetails(mode: DiagnosticCaptureMode): ParsedEventDetails =
        when (this) {
            is StreamEvent.TextChunk -> ParsedEventDetails(
                eventType = "TextChunk",
                attributes = mapOf("chars" to text.length.toString()),
                content = captureParsedContent(text, mode),
            )
            is StreamEvent.CitationUpdate -> ParsedEventDetails(
                eventType = "CitationUpdate",
                attributes = mapOf(
                    "provider" to DiagnosticRedactor.safeIdentifier(citation.provider),
                    "kind" to DiagnosticRedactor.safeIdentifier(citation.kind),
                    "anchors" to citation.anchors.size.toString(),
                ),
            )
            is StreamEvent.ThoughtChunk -> ParsedEventDetails(
                eventType = "ThoughtChunk",
                attributes = mapOf(
                    "chars" to thought.length.toString(),
                    "titleChars" to (title?.length ?: 0).toString(),
                    "hasSignature" to (signature != null).toString(),
                ),
                content = captureParsedContent(thought, mode),
            )
            is StreamEvent.UsageUpdate -> ParsedEventDetails(
                eventType = "UsageUpdate",
                attributes = buildMap {
                    put("totalTokens", usage.totalTokenCount.toString())
                    usage.inputTokenCount?.let { put("inputTokens", it.toString()) }
                    usage.outputTokenCount?.let { put("outputTokens", it.toString()) }
                    usage.reasoningTokenCount?.let { put("reasoningTokens", it.toString()) }
                },
            )
            is StreamEvent.Error -> ParsedEventDetails(
                eventType = "Error",
                attributes = mapOf("errorType" to error.javaClass.simpleName),
                content = captureParsedContent(message, mode),
            )
            is StreamEvent.HostedToolCallUpdate -> ParsedEventDetails(
                eventType = "HostedToolCallUpdate",
                attributes = mapOf(
                    "streamKey" to DiagnosticRedactor.safeIdentifier(streamKey),
                    "name" to DiagnosticRedactor.safeIdentifier(name),
                    "argumentChars" to arguments.length.toString(),
                    "resultChars" to (result?.length ?: 0).toString(),
                    "isError" to isError.toString(),
                ),
                content = captureParsedContent(result ?: arguments, mode),
            )
            is StreamEvent.ToolCallUpdate -> ParsedEventDetails(
                eventType = "ToolCallUpdate",
                attributes = mapOf(
                    "streamKey" to DiagnosticRedactor.safeIdentifier(streamKey),
                    "id" to DiagnosticRedactor.safeIdentifier(id.orEmpty()),
                    "name" to DiagnosticRedactor.safeIdentifier(name),
                    "argumentChars" to arguments.length.toString(),
                ),
                content = captureParsedContent(arguments, mode),
            )
            is StreamEvent.ToolCallRequest -> ParsedEventDetails(
                eventType = "ToolCallRequest",
                attributes = mapOf(
                    "streamKey" to DiagnosticRedactor.safeIdentifier(streamKey),
                    "id" to DiagnosticRedactor.safeIdentifier(id),
                    "name" to DiagnosticRedactor.safeIdentifier(name),
                    "argumentChars" to arguments.length.toString(),
                ),
                content = captureParsedContent(arguments, mode),
            )
            is StreamEvent.ToolCallsRequest -> ParsedEventDetails(
                eventType = "ToolCallsRequest",
                attributes = mapOf("calls" to calls.size.toString()),
            )
            is StreamEvent.Retrying -> ParsedEventDetails(
                eventType = "Retrying",
                attributes = mapOf(
                    "attempt" to attempt.toString(),
                    "maxAttempts" to maxAttempts.toString(),
                ),
            )
        }

    private fun captureParsedContent(
        content: String,
        mode: DiagnosticCaptureMode,
    ): CapturedDiagnosticText? = mode
        .takeUnless { it == DiagnosticCaptureMode.METADATA }
        ?.let { DiagnosticRedactor.captureContent(content) }

    private data class ParsedEventDetails(
        val eventType: String,
        val attributes: Map<String, String>,
        val content: CapturedDiagnosticText? = null,
    )

    private val SAFE_HTTP_ATTRIBUTE_KEYS = setOf(
        "acceptedDelayMs",
        "addresses",
        "bodyBytes",
        "bytes",
        "chars",
        "code",
        "messages",
        "protocol",
        "proxy",
        "tools",
        "version",
    )
    private val DETAIL_PAIR = Regex("""([A-Za-z][A-Za-z0-9_]*)=([^\s]+)""")
    private const val MAX_IDENTIFIER_LENGTH = 160
    private const val MAX_STAGE_LENGTH = 80
    private const val MAX_ATTRIBUTE_LENGTH = 80
}
