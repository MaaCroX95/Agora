package com.newoether.agora.ui.chat.message

import com.newoether.agora.model.MessageSegment

/**
 * A historical assistant row is visually ready only after every visible answer parser has
 * published its current document. Reporting the first answer alone can uncover a partially
 * measured message and move the list when a later answer appears.
 */
internal class AssistantRenderReadiness(expectedKeys: Set<Int>) {
    private val pendingKeys = expectedKeys.toMutableSet()
    private var reported = false

    fun claimSynchronousReady(): Boolean {
        if (reported || pendingKeys.isNotEmpty()) return false
        reported = true
        return true
    }

    fun markReady(key: Int): Boolean {
        if (reported || !pendingKeys.remove(key) || pendingKeys.isNotEmpty()) return false
        reported = true
        return true
    }
}

internal fun assistantAsynchronousAnswerKeys(
    useTimelineSegments: Boolean,
    orderedSegments: List<MessageSegment>,
    fallbackAnswer: String?,
): Set<Int> = if (useTimelineSegments) {
    orderedSegments.mapIndexedNotNull { index, segment ->
        index.takeIf { segment.isVisibleAnswerSegment() && segment.content.isNotBlank() }
    }.toSet()
} else if (fallbackAnswer.isNullOrBlank()) {
    emptySet()
} else {
    setOf(-1)
}
