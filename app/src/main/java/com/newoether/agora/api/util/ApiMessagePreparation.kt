package com.newoether.agora.api.util

import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.Participant
import com.newoether.agora.model.isContextCompact
import com.newoether.agora.model.isSuccessfulContextCompact

private const val CONTEXT_SUMMARY_ID_PREFIX = "context_summary_"
private const val API_INITIAL_USER_ID_PREFIX = "api_initial_user_"
private const val API_COMPACT_CONTINUATION_ID_PREFIX = "api_compact_continuation_"
private const val API_COMPACT_CONTINUATION_TEXT = "Please continue."
internal const val CONTEXT_SUMMARY_OPEN_TAG = "<context_summary>"
internal const val CONTEXT_SUMMARY_CLOSE_TAG = "</context_summary>"

/**
 * Non-destructive Compact projection. The nearest successful Compact is the logical context start.
 * Generic callers receive the raw summary text; request-only markup is added by [prepareMessages].
 */
fun applyNearestContextCompact(messages: List<ChatMessage>): List<ChatMessage> =
    projectNearestContextCompact(messages, markSummaryForApi = false)

private fun projectNearestContextCompact(
    messages: List<ChatMessage>,
    markSummaryForApi: Boolean,
): List<ChatMessage> {
    // A failed, stopped, or in-flight Compact is durable UI history, but it never summarizes
    // anything and therefore has no Provider-context meaning. Keeping it in the wire history can
    // also leave a generation request ending in an assistant row after automatic fallback.
    val hasNonSuccessfulCompact = messages.any {
        it.isContextCompact() && !it.isSuccessfulContextCompact()
    }
    val providerVisible = if (hasNonSuccessfulCompact) {
        messages.filterNot { it.isContextCompact() && !it.isSuccessfulContextCompact() }
    } else {
        messages
    }
    val index = providerVisible.indexOfLast(ChatMessage::isSuccessfulContextCompact)
    if (index < 0) return providerVisible
    val compact = providerVisible[index]
    val projectedText = if (markSummaryForApi) {
        "$CONTEXT_SUMMARY_OPEN_TAG\n${compact.text.trim()}\n$CONTEXT_SUMMARY_CLOSE_TAG"
    } else {
        compact.text
    }
    return buildList(providerVisible.size - index) {
        add(
            compact.copy(
                id = "$CONTEXT_SUMMARY_ID_PREFIX${compact.id}",
                text = projectedText,
                participant = Participant.USER,
            )
        )
        addAll(providerVisible.drop(index + 1))
    }
}

/**
 * Canonical provider-visible context before applying the configured window. Compact eligibility,
 * the composer usage indicator, and provider rollout share this projection without request markup.
 */
fun canonicalContextMessages(messages: List<ChatMessage>): List<ChatMessage> =
    canonicalContextMessages(messages, markSummaryForApi = false)

private fun canonicalContextMessages(
    messages: List<ChatMessage>,
    markSummaryForApi: Boolean,
): List<ChatMessage> {
    val compacted = projectNearestContextCompact(messages, markSummaryForApi)
    val canonical = validateToolMessages(
        stripEmptyTurns(compacted.distinctBy(ChatMessage::id))
    )
    return stripEmptyTurns(mergeConsecutiveSameRole(canonical))
}

/** Full fail-closed message preparation pipeline shared by every provider. */
fun prepareMessages(messages: List<ChatMessage>, contextTokenBudget: Int): List<ChatMessage> {
    val previous = messages.getOrNull(messages.lastIndex - 1)
    val prompt = messages.lastOrNull()?.takeIf {
        it.id == "$API_INITIAL_USER_ID_PREFIX${previous?.id.orEmpty()}" &&
            it.parentId == previous?.id &&
            it.participant == Participant.USER
    }
    val history = if (prompt == null) messages else messages.dropLast(1)
    val requestHistory = if (prompt == null) {
        appendCompactContinuationIfNeeded(history)
    } else {
        history
    }
    return stripEmptyTurns(
        mergeConsecutiveSameRole(
            limitContext(
                canonicalContextMessages(requestHistory, markSummaryForApi = true),
                contextTokenBudget,
            )
        )
    ) + listOfNotNull(prompt)
}

private fun appendCompactContinuationIfNeeded(
    messages: List<ChatMessage>,
): List<ChatMessage> {
    val compactIndex = messages.indexOfLast(ChatMessage::isSuccessfulContextCompact)
    if (compactIndex < 0) return messages
    val hasLaterOrdinaryUser = messages
        .asSequence()
        .drop(compactIndex + 1)
        .any { message ->
            message.participant == Participant.USER &&
                !message.isToolProtocolMessage() &&
                !message.isContextCompact() &&
                !message.id.startsWith(API_INITIAL_USER_ID_PREFIX)
        }
    if (hasLaterOrdinaryUser) return messages
    val compact = messages[compactIndex]
    val parent = messages.lastOrNull()
    return messages + ChatMessage(
        id = "$API_COMPACT_CONTINUATION_ID_PREFIX${compact.id}",
        parentId = parent?.id,
        text = API_COMPACT_CONTINUATION_TEXT,
        participant = Participant.USER,
        timestamp = parent?.timestamp?.let { if (it == Long.MAX_VALUE) it else it + 1L } ?: 0L,
    )
}
