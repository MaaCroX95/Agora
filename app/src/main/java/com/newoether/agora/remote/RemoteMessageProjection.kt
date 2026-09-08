package com.newoether.agora.remote

import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.Participant
import com.newoether.agora.model.ToolExecutionStates
import com.newoether.agora.model.MessageStatus

internal fun RemoteSession.displayTitle(untitled: String): String = title.takeUnless { it.isBlank() || it == id } ?: untitled

/** Native records stay in the Remote cache; only presentation groups adjacent assistant records. */
internal fun projectRemoteMessages(messages: List<RemoteMessage>, runtime: RemoteRuntime? = null): List<ChatMessage> = buildList {
    var index = 0
    while (index < messages.size) {
        val first = messages[index++]
        require(first.role == "user" || first.role == "assistant")
        val segments = if (first.role == "assistant") buildList<MessageSegment> {
            var current = first
            while (true) {
                val activity = current.activity
                val segment = when (activity?.type) {
                    null -> MessageSegment(type = "answer", content = current.text.trimEnd('\r', '\n'),
                        streamingTextDeltas = current.streamingTextDeltas)
                    "thought" -> MessageSegment(type = "thought", content = current.text.trimEnd('\r', '\n'))
                    "tool" -> MessageSegment(
                        type = "tool", toolName = activity.toolName, toolArgs = activity.arguments,
                        toolCallId = current.id, toolState = activity.state, durationMs = activity.durationMs,
                        toolResult = activity.result.takeUnless { activity.state == ToolExecutionStates.RUNNING },
                        toolProgress = activity.result.takeIf { activity.state == ToolExecutionStates.RUNNING },
                    )
                    else -> error("Unsupported Remote activity")
                }
                if (segment.type == "tool" || segment.content.isNotBlank()) {
                    // Native text items are separate paragraphs, not adjacent streaming deltas.
                    add(if (segment.type != "tool" && lastOrNull()?.type == segment.type) {
                        segment.copy(content = "\n\n" + segment.content)
                    } else segment)
                }
                val next = messages.getOrNull(index)
                if (next?.role != "assistant" || next.turnId != first.turnId) break
                current = next
                index++
            }
        } else null
        if (segments != null && segments.isEmpty()) continue
        add(ChatMessage(
            id = first.groupId ?: first.id, parentId = lastOrNull()?.id,
            text = segments?.filter { it.type == "answer" }?.joinToString("\n\n") { it.content.trimStart('\n') }
                ?: first.text.trimEnd('\r', '\n'),
            participant = if (first.role == "user") Participant.USER else Participant.MODEL,
            timestamp = first.timestamp, modelName = "Codex", runId = first.turnId,
            segments = segments,
        ))
    }
    val turn = runtime?.activeTurnId?.takeIf { active ->
        runtime.hasVisibleGeneration(messages)
    }
    if (turn != null) {
        val tail = lastOrNull()
        if (tail?.participant == Participant.MODEL && tail.runId == turn) {
            val status = when (tail.segments?.lastOrNull()?.type) {
                "thought" -> MessageStatus.THINKING
                "tool" -> MessageStatus.TOOL_CALLING
                else -> MessageStatus.SENDING
            }
            set(lastIndex, tail.copy(status = status, modelName = runtime.model ?: "Codex"))
        } else {
            // Display-only empty assistant uses the existing initial-generation indicator.
            // Its authority is the real native active turn, not an inferred local request.
            add(ChatMessage(id = "remote-active-$turn", parentId = tail?.id, text = "",
                participant = Participant.MODEL, timestamp = tail?.timestamp ?: 0,
                modelName = runtime.model ?: "Codex", runId = turn, status = MessageStatus.SENDING))
        }
    }
}

/** The newest native page replaces the cached tail, including native edits/removals. */
internal fun mergeRemoteHistory(old: List<RemoteMessage>, fresh: List<RemoteMessage>): List<RemoteMessage> {
    val boundary = fresh.firstOrNull()?.id ?: return emptyList()
    val index = old.indexOfFirst { it.id == boundary }
    return (old.take(index.coerceAtLeast(0)) + fresh).distinctBy { it.id }
}
