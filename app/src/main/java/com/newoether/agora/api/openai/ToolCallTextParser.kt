package com.newoether.agora.api.openai

import com.newoether.agora.api.GenerationError
import com.newoether.agora.api.OpenAiResponseOutputItem
import com.newoether.agora.api.OpenAiResponseStreamEvent
import com.newoether.agora.api.StreamEvent
import com.newoether.agora.api.toTokenUsage
import com.newoether.agora.api.util.ToolArgumentAccumulator
import com.newoether.agora.api.util.safeWireToolCallId
import com.newoether.agora.api.util.safeWireToolName
import com.newoether.agora.model.CitationAnchor
import com.newoether.agora.model.CitationPolicy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.util.UUID

private val RESPONSES_THOUGHT_TITLE_BOLD = Regex("\\*\\*(.*?)\\*\\*")
private val RESPONSES_THOUGHT_TITLE_HEADING = Regex("(?m)^#+\\s*(.*)$")

private fun extractResponsesThoughtTitle(content: String): String? =
    RESPONSES_THOUGHT_TITLE_BOLD.find(content)?.groupValues?.get(1)
        ?: RESPONSES_THOUGHT_TITLE_HEADING.find(content)?.groupValues?.get(1)

/**
 * Recovers tool calls that an OpenAI-compatible server emitted as **content text** rather than as
 * structured `delta.tool_calls` (issue #33, path B). llama.cpp and other self-hosted servers
 * frequently finish with `finish_reason == "stop"` while placing the tool call inside the message
 * `content` — the model's chat template renders it as a tagged ``{json}`` block. The structured
 * path in [BaseOpenAiProvider] only fires on `finish_reason == "tool_calls"`, so without this
 * fallback such servers never enter the tool-call phase (the JSON just shows up as answer text).
 * This parser brings them to parity with Ollama, which reads the structured field.
 *
 * Recognized forms:
 *  - One or more tagged blocks anywhere in the content (the standard form emitted by
 *    Hermes / Qwen / llama.cpp tool-aware templates). The inner JSON may use
 *    `{"name":...,"arguments":...}` or `{"name":...,"parameters":...}`, or nest them under
 *    `"function"`.
 *  - As a last resort, the *entire* trimmed content being a single JSON object or array of the
 *    same tool-call shape (some templates emit the JSON with no surrounding tags). Only attempted
 *    when the whole content is JSON, so prose answers are never misread as tool calls.
 *
 * The inner `arguments`/`parameters` value is preserved verbatim as a JSON string for the
 * downstream tool executor, matching how structured tool calls carry arguments.
 */
internal object ToolCallTextParser {

    data class ParsedCall(
        val id: String?,
        val name: String,
        val arguments: String,
    )

    // Split so the bare tag literals never appear as a contiguous substring in source tooling.
    private const val OPEN_TAG = "<tool_" + "call>"
    private const val CLOSE_TAG = "</tool_" + "call>"
    private val XML_INVOKE_BLOCK = Regex(
        """<(?:(?:antml):)?invoke\s+name\s*=\s*["']([^"']+)["'][^>]*>([\s\S]*?)</(?:(?:antml):)?invoke\s*>""",
        RegexOption.IGNORE_CASE,
    )
    private val XML_PARAMETER = Regex(
        """<(?:(?:antml):)?parameter\s+name\s*=\s*["']([^"']+)["'][^>]*>([\s\S]*?)</(?:(?:antml):)?parameter\s*>""",
        RegexOption.IGNORE_CASE,
    )
    private val XML_ID_ATTRIBUTE_NAME = Regex(
        """\b(?:id|call_id)\s*=""",
        RegexOption.IGNORE_CASE,
    )
    private val XML_ID_ATTRIBUTE = Regex(
        """\b(?:id|call_id)\s*=\s*(["'])([^"']+)\1""",
        RegexOption.IGNORE_CASE,
    )

    /** Extract tool calls from [content]; empty if none are recognized. */
    fun parse(content: String): List<ParsedCall> {
        val results = mutableListOf<ParsedCall>()
        var idx = 0
        var sawTaggedBlock = false
        var malformedTaggedBlock = false
        while (true) {
            val start = content.indexOf(OPEN_TAG, idx)
            if (start < 0) break
            sawTaggedBlock = true
            val innerStart = start + OPEN_TAG.length
            val end = content.indexOf(CLOSE_TAG, innerStart)
            if (end < 0) {
                malformedTaggedBlock = true
                break
            }
            val inner = content.substring(innerStart, end).trim()
            val parsed = parseCallJson(inner)
            if (parsed == null) malformedTaggedBlock = true else results += parsed
            idx = end + CLOSE_TAG.length
        }
        if (sawTaggedBlock) return if (malformedTaggedBlock) emptyList() else results

        // Some Anthropic relays and prompt-based tool shims serialize a native tool_use block as
        // XML in ordinary assistant text. Recover both the bare and namespaced forms:
        //   <invoke name="tool"><parameter name="arg">value</parameter></invoke>
        //   <antml:invoke ...><antml:parameter ...>...</antml:parameter></antml:invoke>
        // Without this branch the model believes it called a tool while Agora renders the markup
        // as answer text and executes nothing.
        XML_INVOKE_BLOCK.findAll(content).forEach { match ->
            parseXmlInvoke(match)?.let(results::add)
        }
        if (results.isNotEmpty()) return results

        val trimmed = content.trim()
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) return emptyList()
        // Only treat the whole content as a tool call when it is pure JSON — never parse tool
        // calls out of prose that merely happens to contain a JSON fragment.
        parseCallJson(trimmed)?.let { return listOf(it) }
        if (trimmed.startsWith("[")) {
            val array = try { Json.parseToJsonElement(trimmed).jsonArray } catch (_: Exception) { return emptyList() }
            // A multi-call payload is atomic. Never execute only the valid members of a damaged
            // array while silently discarding the rest.
            return array.map { element ->
                val obj = element as? JsonObject ?: return emptyList()
                parseCallJson(obj.toString()) ?: return emptyList()
            }
        }
        return emptyList()
    }

    private fun parseXmlInvoke(match: MatchResult): ParsedCall? {
        val name = decodeXml(match.groupValues[1]).trim()
            .takeIf { it.matches(safeWireToolName) }
            ?: return null
        val openingTag = match.value.substringBefore('>')
        val idAttributePresent = XML_ID_ATTRIBUTE_NAME.containsMatchIn(openingTag)
        val id = XML_ID_ATTRIBUTE.find(openingTag)?.groupValues?.getOrNull(2)
            ?.let(::decodeXml)
            ?.trim()
        if (idAttributePresent && (id == null || !id.matches(safeWireToolCallId))) return null
        val body = match.groupValues[2]
        val entries = linkedMapOf<String, JsonElement>()
        XML_PARAMETER.findAll(body).forEach { parameter ->
            val key = decodeXml(parameter.groupValues[1]).trim()
            if (key.isNotBlank()) {
                val value = decodeXml(parameter.groupValues[2]).trim()
                // Tool parameters are strings in this XML protocol. Preserve their text as a JSON
                // string rather than guessing numbers/booleans and changing the declared schema.
                entries[key] = JsonPrimitive(value)
            }
        }
        return ParsedCall(id, name, JsonObject(entries).toString())
    }

    private fun decodeXml(value: String): String = value
        .replace("&lt;", "<", ignoreCase = true)
        .replace("&gt;", ">", ignoreCase = true)
        .replace("&quot;", "\"", ignoreCase = true)
        .replace("&apos;", "'", ignoreCase = true)
        .replace("&amp;", "&", ignoreCase = true)

    private fun parseCallJson(jsonStr: String): ParsedCall? {
        val obj = try { Json.parseToJsonElement(jsonStr).jsonObject } catch (_: Exception) { return null }
        val function = obj["function"] as? JsonObject
        val name = stringField(obj, "name")
            ?: function?.let { stringField(it, "name") }
            ?: return null
        if (!name.matches(safeWireToolName)) return null
        val idFields = listOf("id", "call_id").filter(obj::containsKey)
        val ids = idFields.map { key -> stringField(obj, key) ?: return null }
        val distinctIds = ids.distinct()
        if (distinctIds.size > 1) return null
        val id = distinctIds.singleOrNull()
        if (id != null && !id.matches(safeWireToolCallId)) return null
        val args = obj["arguments"] ?: obj["parameters"]
            ?: function?.get("arguments") ?: function?.get("parameters")
        val arguments = args?.let { normalizeArguments(it) ?: return null } ?: "{}"
        return ParsedCall(id, name, arguments)
    }

    private fun stringField(obj: JsonObject, key: String): String? =
        (obj[key] as? JsonPrimitive)?.let { if (it.isString) it.content else null }

    /** Tool arguments must form one complete JSON object before an executable event is emitted. */
    private fun normalizeArguments(element: JsonElement): String? {
        val raw = if (element is JsonPrimitive && element.isString) element.content else element.toString()
        return (runCatching { Json.parseToJsonElement(raw) }.getOrNull() as? JsonObject)?.toString()
    }

}

internal class OpenAiResponsesEventRouter(
    private val json: Json,
    private val thinkingEnabled: Boolean,
) {
    private data class FunctionCall(
        val outputIndex: Int,
        val streamKey: String,
        var itemId: String? = null,
        var callId: String? = null,
        var name: String? = null,
        val arguments: ToolArgumentAccumulator = ToolArgumentAccumulator(),
        var completed: Boolean = false,
    )

    private data class OutputTextPartKey(
        val itemId: String?,
        val outputIndex: Int?,
        val contentIndex: Int?,
    )

    private data class OutputTextPart(
        val key: OutputTextPartKey,
        val text: StringBuilder,
        val globalStart: Int,
        var globalEnd: Int,
        var contiguous: Boolean = true,
    )

    private val callsByOutputIndex = linkedMapOf<Int, FunctionCall>()
    private val callsByItemId = mutableMapOf<String, FunctionCall>()
    private val responseItemsByOutputIndex = linkedMapOf<Int, JsonObject>()
    private val openHostedOutputIndexes = mutableSetOf<Int>()
    private val completedCallsByOutputIndex = mutableMapOf<Int, StreamEvent.ToolCallRequest>()
    private val emittedCallIds = mutableSetOf<String>()
    private val emittedCitationKeys = mutableSetOf<String>()
    private val outputTextParts = linkedMapOf<OutputTextPartKey, OutputTextPart>()
    private val answerText = StringBuilder()
    private var lastSummaryOutputIndex: Int? = null
    private var lastSummaryIndex: Int? = null
    private var lastSequenceNumber: Int? = null
    var sawTerminalMarker: Boolean = false
        private set
    var stopReason: String? = null
        private set
    var streamError: GenerationError? = null
        private set
    var reportedError: Boolean = false
        private set

    val toolCallInFlight: Boolean
        get() = callsByOutputIndex.values.any { !it.completed }

    fun route(event: OpenAiResponseStreamEvent): List<StreamEvent> {
        if (streamError != null || reportedError) return emptyList()
        if (sawTerminalMarker) return fail(event.type, "event received after terminal response")
        validateSequence(event)?.let { return fail(event.type, it) }
        return when (event.type) {
            "response.created", "response.in_progress",
            "response.output_text.done", "response.reasoning_text.done",
            "response.reasoning_summary_text.done", "response.content_part.added",
            "response.content_part.done" -> emptyList()
            "response.output_text.delta" -> event.delta?.takeIf(String::isNotEmpty)
                ?.let { delta ->
                    appendOutputText(event, delta)
                    listOf(StreamEvent.TextChunk(delta))
                }.orEmpty()
            "response.output_text.annotation.added" -> routeCitation(event)
            "response.reasoning_text.delta" ->
                event.delta?.takeIf { thinkingEnabled && it.isNotEmpty() }
                    ?.let { listOf(StreamEvent.ThoughtChunk(it)) }.orEmpty()
            "response.reasoning_summary_text.delta" -> routeReasoningSummary(event)
            "response.output_item.added" -> addOutputItem(event)
            "response.function_call_arguments.delta" -> updateArguments(event)
            "response.function_call_arguments.done" -> completeArguments(event)
            "response.output_item.done" -> completeOutputItem(event)
            "response.completed" -> completeResponse(event)
            "response.failed" -> failResponse(event, "failed")
            "response.incomplete" -> failResponse(event, "incomplete")
            "error" -> failApi(event.error, "Provider reported a Responses stream error")
            else -> emptyList()
        }
    }

    private fun routeReasoningSummary(event: OpenAiResponseStreamEvent): List<StreamEvent> {
        val delta = event.delta?.takeIf { thinkingEnabled && it.isNotEmpty() }
            ?: return emptyList()
        val outputIndex = event.outputIndex
        val summaryIndex = event.summaryIndex
        val startsNewPart =
            outputIndex != null &&
                summaryIndex != null &&
                lastSummaryOutputIndex != null &&
                lastSummaryIndex != null &&
                (outputIndex != lastSummaryOutputIndex || summaryIndex != lastSummaryIndex)
        if (outputIndex != null && summaryIndex != null) {
            lastSummaryOutputIndex = outputIndex
            lastSummaryIndex = summaryIndex
        }
        return listOf(
            StreamEvent.ThoughtChunk(
                thought = if (startsNewPart) "\n\n$delta" else delta,
                title = extractResponsesThoughtTitle(delta),
            ),
        )
    }

    private fun routeCitation(event: OpenAiResponseStreamEvent): List<StreamEvent> {
        val annotation = event.annotation ?: return emptyList()
        val anchors = citationAnchors(event, annotation.startIndex, annotation.endIndex)
        val citation = when (annotation.type) {
            "url_citation" -> CitationPolicy.create(
                provider = "openai",
                kind = "url",
                title = annotation.title,
                url = annotation.url,
                anchors = anchors,
                answerText = answerText.toString(),
            )
            "file_citation", "container_file_citation" -> CitationPolicy.create(
                provider = "openai",
                kind = "file",
                title = annotation.title ?: annotation.filename,
                fileName = annotation.filename,
                providerSourceId = annotation.fileId ?: annotation.containerId,
                anchors = anchors,
                answerText = answerText.toString(),
            )
            else -> null
        } ?: return emptyList()
        val eventKey = buildString {
            append(citation.sourceId)
            citation.anchors.forEach { anchor ->
                append(':').append(anchor.startIndex).append(':').append(anchor.endIndex)
            }
        }
        if (!emittedCitationKeys.add(eventKey)) return emptyList()
        return listOf(StreamEvent.CitationUpdate(citation))
    }

    private fun outputTextPartKey(event: OpenAiResponseStreamEvent): OutputTextPartKey? =
        OutputTextPartKey(
            itemId = event.itemId,
            outputIndex = event.outputIndex,
            contentIndex = event.contentIndex,
        ).takeUnless { key ->
            key.itemId == null && key.outputIndex == null && key.contentIndex == null
        }

    private fun findOutputTextPart(event: OpenAiResponseStreamEvent): OutputTextPart? {
        val key = outputTextPartKey(event) ?: return null
        outputTextParts[key]?.let { return it }
        return outputTextParts.values.filter { part ->
            (key.itemId == null || part.key.itemId == null || key.itemId == part.key.itemId) &&
                (
                    key.outputIndex == null ||
                        part.key.outputIndex == null ||
                        key.outputIndex == part.key.outputIndex
                    ) &&
                (
                    key.contentIndex == null ||
                        part.key.contentIndex == null ||
                        key.contentIndex == part.key.contentIndex
                    )
        }.singleOrNull()
    }

    private fun appendOutputText(event: OpenAiResponseStreamEvent, delta: String) {
        val key = outputTextPartKey(event)
        if (key == null) {
            answerText.append(delta)
            return
        }
        val part = findOutputTextPart(event) ?: OutputTextPart(
            key = key,
            text = StringBuilder(),
            globalStart = answerText.length,
            globalEnd = answerText.length,
        ).also { outputTextParts[key] = it }
        if (part.globalEnd != answerText.length) part.contiguous = false
        part.text.append(delta)
        answerText.append(delta)
        part.globalEnd = answerText.length
    }

    private fun citationAnchors(
        event: OpenAiResponseStreamEvent,
        startIndex: Int?,
        endIndex: Int?,
    ): List<CitationAnchor> {
        val start = startIndex ?: return emptyList()
        val end = endIndex ?: return emptyList()
        val key = outputTextPartKey(event)
        val part = when {
            key != null -> findOutputTextPart(event)
            outputTextParts.isEmpty() -> null
            else -> outputTextParts.values.singleOrNull() ?: return emptyList()
        }
        if (key != null && part == null) return emptyList()
        if (part?.contiguous == false) return emptyList()
        val scopedText = part?.text ?: answerText
        if (start < 0 || end <= start || end > scopedText.length) return emptyList()
        val globalStart = (part?.globalStart ?: 0) + start
        val globalEnd = (part?.globalStart ?: 0) + end
        if (globalEnd > answerText.length) return emptyList()
        val citedText = scopedText.substring(start, end)
        if (answerText.substring(globalStart, globalEnd) != citedText) return emptyList()
        return listOf(
            CitationAnchor(
                startIndex = globalStart,
                endIndex = globalEnd,
                citedText = citedText,
            ),
        )
    }

    private fun addOutputItem(event: OpenAiResponseStreamEvent): List<StreamEvent> {
        val rawItem = event.item ?: return fail(event.type, "missing output item")
        val item = try {
            rawItem.toOutputItem()
        } catch (error: Exception) {
            return fail(event.type, error.localizedMessage ?: "invalid output item")
        }
        val index = event.outputIndex ?: return fail(event.type, "missing output_index")
        if (responseItemsByOutputIndex.containsKey(index)) {
            return fail(event.type, "duplicate output_index")
        }
        responseItemsByOutputIndex[index] = rawItem
        if (item.type == "web_search_call") {
            openHostedOutputIndexes += index
            return listOf(
                rawItem.toHostedWebSearchUpdate(
                    streamKey = item.id ?: "response_hosted_$index",
                    completed = false,
                ),
            )
        }
        if (item.type != "function_call") return emptyList()
        val call = FunctionCall(
            outputIndex = index,
            streamKey = item.id ?: "response_tool_$index",
            itemId = item.id,
            callId = item.callId,
            name = item.name,
        )
        call.arguments.append(item.arguments)
        callsByOutputIndex[index] = call
        item.id?.let { id ->
            if (callsByItemId.put(id, call) != null) return fail(event.type, "duplicate item id")
        }
        return listOf(call.updateEvent())
    }

    private fun updateArguments(event: OpenAiResponseStreamEvent): List<StreamEvent> {
        val call = findCall(event) ?: return fail(event.type, "function call item was not added")
        if (call.completed) return fail(event.type, "function call already completed")
        call.arguments.append(event.delta)
        return listOf(call.updateEvent())
    }

    private fun completeArguments(event: OpenAiResponseStreamEvent): List<StreamEvent> {
        val call = findCall(event) ?: return fail(event.type, "function call item was not added")
        if (call.completed) return fail(event.type, "function call already completed")
        event.name?.let { call.name = it }
        event.arguments?.let { finalArguments ->
            val accumulated = call.arguments.toString()
            if (accumulated.isNotEmpty() && finalArguments != accumulated) {
                call.arguments.replace(finalArguments)
            }
            if (accumulated.isEmpty()) call.arguments.append(finalArguments)
        }
        return listOf(call.updateEvent())
    }

    private fun completeOutputItem(event: OpenAiResponseStreamEvent): List<StreamEvent> {
        val rawItem = event.item ?: return fail(event.type, "missing output item")
        val item = try {
            rawItem.toOutputItem()
        } catch (error: Exception) {
            return fail(event.type, error.localizedMessage ?: "invalid output item")
        }
        val index = event.outputIndex ?: return fail(event.type, "missing output_index")
        val addedRawItem = responseItemsByOutputIndex[index]
            ?: return fail(event.type, "output item was not added")
        val addedItem = try {
            addedRawItem.toOutputItem()
        } catch (error: Exception) {
            return fail(event.type, error.localizedMessage ?: "invalid added output item")
        }
        if (item.type == "web_search_call" || addedItem.type == "web_search_call") {
            if (item.type != addedItem.type) {
                return fail(event.type, "hosted output item type changed")
            }
            if (addedItem.id != null && item.id != addedItem.id) {
                return fail(event.type, "hosted output item id changed")
            }
            if (!openHostedOutputIndexes.remove(index)) {
                return fail(event.type, "hosted output item was already completed")
            }
            responseItemsByOutputIndex[index] = rawItem
            return listOf(
                rawItem.toHostedWebSearchUpdate(
                    streamKey = addedItem.id ?: "response_hosted_$index",
                    completed = true,
                ),
            )
        }
        responseItemsByOutputIndex[index] = rawItem
        if (item.type != "function_call") return emptyList()
        val call = findCall(event, item.id)
            ?: return fail(event.type, "function call item was not added")
        item.callId?.let { call.callId = it }
        item.name?.let { call.name = it }
        item.arguments?.let { finalArguments ->
            val accumulated = call.arguments.toString()
            if (accumulated.isNotEmpty() && finalArguments != accumulated) {
                call.arguments.replace(finalArguments)
            }
            if (accumulated.isEmpty()) call.arguments.append(finalArguments)
        }
        return completeCall(call, event.type)
    }

    private fun completeCall(call: FunctionCall, rawType: String): List<StreamEvent> {
        if (call.completed) return fail(rawType, "function call completed twice")
        val callId = call.callId.orEmpty()
        val name = call.name.orEmpty()
        val arguments = call.arguments.toString().ifEmpty { "{}" }
        if (!callId.matches(safeWireToolCallId)) return fail(rawType, "invalid call_id")
        if (!name.matches(safeWireToolName)) return fail(rawType, "invalid function name")
        if (runCatching { json.parseToJsonElement(arguments) is JsonObject }.getOrDefault(false).not()) {
            return fail(rawType, "function arguments are not a complete JSON object")
        }
        if (!emittedCallIds.add(callId)) return fail(rawType, "duplicate call_id")
        call.completed = true
        completedCallsByOutputIndex[call.outputIndex] = StreamEvent.ToolCallRequest(
            callId,
            name,
            arguments,
            streamKey = call.streamKey,
        )
        return emptyList()
    }

    private fun completeResponse(event: OpenAiResponseStreamEvent): List<StreamEvent> {
        if (toolCallInFlight) return fail(event.type, "response completed with an open function call")
        if (openHostedOutputIndexes.isNotEmpty()) {
            return fail(event.type, "response completed with an open hosted tool call")
        }
        val response = event.response ?: return fail(event.type, "missing response envelope")
        if (response.status != "completed") {
            return fail(event.type, "unexpected terminal status ${response.status}")
        }
        sawTerminalMarker = true
        stopReason = "completed"
        val continuationItems = responseItemsByOutputIndex
            .toSortedMap()
            .values
            .toList()
        val calls = completedCallsByOutputIndex
            .toSortedMap()
            .values
            .mapIndexed { index, call ->
                if (index == 0) call.copy(responseOutputItems = continuationItems) else call
            }
        val output = mutableListOf<StreamEvent>()
        if (calls.size == 1) output += calls.single()
        if (calls.size > 1) output += StreamEvent.ToolCallsRequest(calls)
        response.usage?.let { output += StreamEvent.UsageUpdate(it.toTokenUsage()) }
        return output
    }

    private fun failResponse(event: OpenAiResponseStreamEvent, fallback: String): List<StreamEvent> {
        val response = event.response
        response?.usage?.let { return failApi(response.error, response.incompleteDetails?.reason ?: fallback, it) }
        return failApi(response?.error, response?.incompleteDetails?.reason ?: fallback)
    }

    private fun failApi(
        error: com.newoether.agora.api.OpenAiError?,
        fallback: String,
        usage: com.newoether.agora.api.OpenAiResponseUsage? = null,
    ): List<StreamEvent> {
        sawTerminalMarker = true
        stopReason = fallback.lowercase()
        streamError = GenerationError.Api(
            code = error?.code,
            type = error?.type ?: "responses_error",
            message = error?.message?.takeIf(String::isNotBlank) ?: fallback,
        )
        return usage?.let { listOf(StreamEvent.UsageUpdate(it.toTokenUsage())) }.orEmpty()
    }

    private fun findCall(event: OpenAiResponseStreamEvent, itemId: String? = null): FunctionCall? =
        event.itemId?.let(callsByItemId::get)
            ?: itemId?.let(callsByItemId::get)
            ?: event.outputIndex?.let(callsByOutputIndex::get)

    private fun validateSequence(event: OpenAiResponseStreamEvent): String? {
        val sequence = event.sequenceNumber ?: return "missing sequence_number"
        val previous = lastSequenceNumber
        if (previous != null && sequence <= previous) return "non-increasing sequence_number"
        lastSequenceNumber = sequence
        return null
    }

    private fun JsonObject.toHostedWebSearchUpdate(
        streamKey: String,
        completed: Boolean,
    ): StreamEvent.HostedToolCallUpdate {
        val action = this["action"] as? JsonObject ?: JsonObject(emptyMap())
        val status = (this["status"] as? JsonPrimitive)?.content
        return StreamEvent.HostedToolCallUpdate(
            streamKey = streamKey,
            name = "openai_search",
            arguments = action.toString(),
            result = takeIf { completed }?.toString(),
            isError = completed && status in setOf("failed", "incomplete"),
        )
    }

    private fun JsonObject.toOutputItem(): OpenAiResponseOutputItem =
        json.decodeFromJsonElement(OpenAiResponseOutputItem.serializer(), this)

    private fun fail(rawType: String, cause: String): List<StreamEvent> {
        reportedError = true
        return listOf(StreamEvent.Error(GenerationError.SseParse(rawType, cause)))
    }

    private fun FunctionCall.updateEvent() = StreamEvent.ToolCallUpdate(
        streamKey = streamKey,
        id = callId,
        name = name.orEmpty(),
        arguments = arguments.toString(),
    )
}

/**
 * Streaming companion to [ToolCallTextParser].
 *
 * Compatible servers may write tool calls in ordinary content. This parser withholds only a
 * possible tag/pure-tool prefix, emits surrounding prose normally, and exposes accumulated
 * name/arguments snapshots as soon as the model has written enough to identify a call.
 */
internal class StreamingTextToolCallParser {
    data class Snapshot(
        val streamKey: String,
        val name: String,
        val arguments: String,
    )

    data class CompletedCall(
        val streamKey: String,
        val id: String?,
        val name: String,
        val arguments: String,
    )

    private enum class Mode { NORMAL, TAGGED_CALL, BARE_JSON_CALL }

    private val buffer = StringBuilder()
    private var mode = Mode.NORMAL
    private var canProbeBareJson = true
    private var streamKey: String? = null
    private var announcedName: String? = null
    private var announcedArguments = ""
    private var lastSnapshot: Snapshot? = null
    private var activeTaggedProtocol = TaggedProtocol.JSON

    suspend fun feed(
        content: String,
        onText: suspend (String) -> Unit,
        onUpdate: suspend (Snapshot) -> Unit,
        onComplete: suspend (CompletedCall) -> Unit,
        onMalformed: suspend (String) -> Unit,
    ) {
        if (content.isEmpty()) return
        buffer.append(content)
        drain(onText, onUpdate, onComplete, onMalformed)
    }

    suspend fun flush(
        onText: suspend (String) -> Unit,
        onUpdate: suspend (Snapshot) -> Unit,
        onComplete: suspend (CompletedCall) -> Unit,
        onMalformed: suspend (String) -> Unit,
    ) {
        when (mode) {
            Mode.NORMAL -> emitBufferedText(onText)
            Mode.TAGGED_CALL -> {
                announcePartial(buffer.toString(), onUpdate)
                onMalformed("Provider ended before the tagged tool call was complete")
            }
            Mode.BARE_JSON_CALL -> completeBareJson(onUpdate, onComplete, onMalformed)
        }
        resetAfterCall()
        buffer.clear()
        canProbeBareJson = false
    }

    private suspend fun drain(
        onText: suspend (String) -> Unit,
        onUpdate: suspend (Snapshot) -> Unit,
        onComplete: suspend (CompletedCall) -> Unit,
        onMalformed: suspend (String) -> Unit,
    ) {
        while (true) {
            when (mode) {
                Mode.NORMAL -> {
                    if (canProbeBareJson) {
                        val candidate = buffer.toString().trimStart()
                        if (candidate.isEmpty()) return
                        if (BARE_JSON_PREFIXES.any(candidate::startsWith)) {
                            beginCall(Mode.BARE_JSON_CALL, onUpdate)
                            announcePartial(buffer.toString(), onUpdate)
                            return
                        }
                        if (BARE_JSON_PREFIXES.any { prefix -> prefix.startsWith(candidate) }) {
                            return
                        }
                        canProbeBareJson = false
                    }

                    val taggedStart = findTaggedStart(buffer)
                    if (taggedStart != null) {
                        val (openAt, protocol) = taggedStart
                        if (openAt > 0) onText(buffer.substring(0, openAt))
                        val openEnd = if (protocol == TaggedProtocol.JSON) {
                            openAt + OPEN_TAG.length
                        } else {
                            // XML invoke carries the tool name as an attribute, so retain the full
                            // opening tag in the parser buffer while still withholding it from UI.
                            buffer.indexOf(">", startIndex = openAt).takeIf { it >= 0 }
                                ?.plus(1) ?: return
                        }
                        val opening = buffer.substring(openAt, openEnd)
                        buffer.delete(0, openEnd)
                        activeTaggedProtocol = protocol
                        beginCall(Mode.TAGGED_CALL, onUpdate)
                        if (protocol == TaggedProtocol.XML) buffer.insert(0, opening)
                        continue
                    }

                    val retained = longestTagPrefixSuffix(buffer)
                    val safeLength = buffer.length - retained
                    if (safeLength > 0) {
                        onText(buffer.substring(0, safeLength))
                        buffer.delete(0, safeLength)
                    }
                    return
                }

                Mode.TAGGED_CALL -> {
                    val closeTag = if (activeTaggedProtocol == TaggedProtocol.JSON) {
                        CLOSE_TAG
                    } else {
                        xmlCloseTag(buffer)
                    }
                    val closeAt = closeTag?.let(buffer::indexOf) ?: -1
                    if (closeAt < 0) {
                        // JSON supports useful partial argument snapshots. XML parameters are only
                        // announced once their closing tags make the complete JSON unambiguous.
                        if (activeTaggedProtocol == TaggedProtocol.JSON) {
                            announcePartial(buffer.toString(), onUpdate)
                        }
                        return
                    }

                    val body = buffer.substring(0, closeAt)
                    val completeTaggedText = if (activeTaggedProtocol == TaggedProtocol.JSON) {
                        OPEN_TAG + body + closeTag
                    } else {
                        body + closeTag
                    }
                    val parsed = ToolCallTextParser.parse(completeTaggedText).singleOrNull()
                    if (parsed != null) {
                        val key = checkNotNull(streamKey)
                        emitSnapshot(key, parsed.name, parsed.arguments, onUpdate)
                        onComplete(CompletedCall(key, parsed.id, parsed.name, parsed.arguments))
                    } else {
                        announcePartial(body, onUpdate)
                        onMalformed("Tagged tool call was not valid complete JSON")
                    }
                    buffer.delete(0, closeAt + checkNotNull(closeTag).length)
                    resetAfterCall()
                    mode = Mode.NORMAL
                    canProbeBareJson = false
                }

                Mode.BARE_JSON_CALL -> {
                    announcePartial(buffer.toString(), onUpdate)
                    return
                }
            }
        }
    }

    private suspend fun completeBareJson(
        onUpdate: suspend (Snapshot) -> Unit,
        onComplete: suspend (CompletedCall) -> Unit,
        onMalformed: suspend (String) -> Unit,
    ) {
        val parsed = ToolCallTextParser.parse(buffer.toString())
        if (parsed.isNotEmpty()) {
            parsed.forEachIndexed { index, call ->
                val key = if (index == 0) {
                    streamKey ?: newStreamKey()
                } else {
                    newStreamKey()
                }
                emitSnapshot(key, call.name, call.arguments, onUpdate)
                onComplete(CompletedCall(key, call.id, call.name, call.arguments))
            }
            return
        }

        announcePartial(buffer.toString(), onUpdate)
        onMalformed("Provider ended before the JSON tool call was complete")
    }

    private suspend fun announcePartial(
        body: String,
        onUpdate: suspend (Snapshot) -> Unit,
    ) {
        val name = extractName(body) ?: announcedName ?: return
        val argumentsStart = ARGUMENTS_KEY.find(body)?.range?.last?.plus(1) ?: return
        val arguments = partialArguments(body.substring(argumentsStart))
        announcedName = name
        announcedArguments = arguments
        emitSnapshot(checkNotNull(streamKey), name, arguments, onUpdate)
    }

    private suspend fun emitSnapshot(
        key: String,
        name: String,
        arguments: String,
        onUpdate: suspend (Snapshot) -> Unit,
    ) {
        val snapshot = Snapshot(key, name, arguments)
        if (snapshot != lastSnapshot) {
            lastSnapshot = snapshot
            onUpdate(snapshot)
        }
    }

    private suspend fun beginCall(
        nextMode: Mode,
        onUpdate: suspend (Snapshot) -> Unit,
    ) {
        mode = nextMode
        streamKey = newStreamKey()
        announcedName = null
        announcedArguments = ""
        lastSnapshot = null
        emitSnapshot(checkNotNull(streamKey), "", "", onUpdate)
    }

    private fun resetAfterCall() {
        activeTaggedProtocol = TaggedProtocol.JSON
        streamKey = null
        announcedName = null
        announcedArguments = ""
        lastSnapshot = null
    }

    private suspend fun emitBufferedText(onText: suspend (String) -> Unit) {
        if (buffer.isNotEmpty()) onText(buffer.toString())
    }

    private fun extractName(body: String): String? {
        val encoded = NAME_FIELD.find(body)?.groupValues?.getOrNull(1) ?: return null
        return runCatching {
            Json.decodeFromString<String>("\"$encoded\"")
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    private fun partialArguments(source: String): String {
        val trimmed = source.trimStart()
        if (!trimmed.startsWith('"')) return trimmed
        return trimmed
            .removePrefix("\"")
            .removeSuffix("\"")
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
    }

    private fun findTaggedStart(source: StringBuilder): Pair<Int, TaggedProtocol>? {
        val lower = source.toString().lowercase()
        val candidates = listOf(
            lower.indexOf(OPEN_TAG.lowercase()) to TaggedProtocol.JSON,
            lower.indexOf("<invoke") to TaggedProtocol.XML,
            lower.indexOf("<antml:invoke") to TaggedProtocol.XML,
        ).filter { it.first >= 0 }
        return candidates.minByOrNull { it.first }
    }

    private fun xmlCloseTag(source: StringBuilder): String? {
        val lower = source.toString().lowercase()
        return when {
            lower.contains("</antml:invoke") ->
                Regex("""</antml:invoke\s*>""", RegexOption.IGNORE_CASE)
                    .find(source)?.value
            lower.contains("</invoke") ->
                Regex("""</invoke\s*>""", RegexOption.IGNORE_CASE)
                    .find(source)?.value
            else -> null
        }
    }

    private fun longestTagPrefixSuffix(source: StringBuilder): Int {
        val prefixes = listOf(OPEN_TAG, "<invoke", "<antml:invoke")
        val maxLength = minOf(source.length, prefixes.maxOf { it.length } - 1)
        for (length in maxLength downTo 1) {
            val suffix = source.substring(source.length - length).lowercase()
            if (prefixes.any { it.lowercase().startsWith(suffix) }) return length
        }
        return 0
    }

    private fun newStreamKey(): String = "text_tool_${UUID.randomUUID()}"

    private enum class TaggedProtocol { JSON, XML }

    private companion object {
        const val OPEN_TAG = "<tool_" + "call>"
        const val CLOSE_TAG = "</tool_" + "call>"
        val BARE_JSON_PREFIXES = listOf(
            "{\"name\"",
            "{\"function\"",
            "[{\"name\"",
            "[{\"function\"",
        )
        val NAME_FIELD = Regex(""""name"\s*:\s*"((?:\\.|[^"\\])*)"""")
        val ARGUMENTS_KEY = Regex(""""(?:arguments|parameters)"\s*:\s*""")
    }
}
