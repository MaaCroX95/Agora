package com.newoether.agora.remote

import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import kotlinx.serialization.Serializable

@Serializable
internal data class RemoteMessageNode(
    val id: String, val turnId: String, val clientId: String?, val role: String, val timestamp: Long,
    val revision: String, val textLength: Int,
    val groupId: String? = null, val nativeId: String? = null,
    val textOffset: Int = 0, val textContinues: Boolean = false,
    val activity: RemoteNodeActivity? = null,
)
@Serializable
internal data class RemoteNodeActivity(val type: String, val state: String? = null, val durationMs: Long? = null)
@Serializable
internal data class RemoteTopologyPage(
    val nodes: List<RemoteMessageNode>, val nextCursor: String?, val queued: List<RemoteQueuedMessage>,
    val runtime: RemoteRuntime? = null,
)
@Serializable
internal data class RemotePayloadRequest(val id: String, val revision: String)
@Serializable
internal data class RemotePayloadResponse(val messages: List<RemoteMessage>)

internal data class RemoteMessageGroup(val stub: ChatMessage, val nodes: List<RemoteMessageNode>) {
    val revision: List<String> get() = nodes.map { it.revision }
    val requests: List<RemotePayloadRequest> get() = nodes.map { RemotePayloadRequest(it.id, it.revision) }
}

/** Full lightweight structure stays resident. Payload loading never changes its IDs or positions. */
internal fun projectRemoteTopology(nodes: List<RemoteMessageNode>, runtime: RemoteRuntime?): List<RemoteMessageGroup> = buildList {
    var index = 0
    while (index < nodes.size) {
        val first = nodes[index++]
        val group = mutableListOf(first)
        while (index < nodes.size) {
            val next = nodes[index]
            val same = if (first.role == "assistant") next.role == "assistant" && next.turnId == first.turnId
            else next.role == "user" && (next.nativeId ?: next.id) == (first.nativeId ?: first.id)
            if (!same) break
            group += next
            index++
        }
        if (first.role == "assistant" && group.none { it.textLength > 0 || it.activity?.type == "tool" }) continue
        val id = first.groupId ?: first.nativeId ?: first.id
        add(RemoteMessageGroup(ChatMessage(id = id, parentId = lastOrNull()?.stub?.id, text = "",
            participant = if (first.role == "user") Participant.USER else Participant.MODEL,
            timestamp = first.timestamp, modelName = "Codex", runId = first.turnId), group))
    }
    if (runtime?.isRunning == true && runtime.activeTurnId != null &&
        (runtime.activeTurnHasUserMessage || nodes.any { it.role == "user" && it.turnId == runtime.activeTurnId })) {
        val tail = lastOrNull()
        if (tail?.stub?.participant == Participant.MODEL && tail.stub.runId == runtime.activeTurnId) {
            val status = when (tail.nodes.lastOrNull()?.activity?.type) {
                "thought" -> MessageStatus.THINKING
                "tool" -> MessageStatus.TOOL_CALLING
                else -> MessageStatus.SENDING
            }
            set(lastIndex, tail.copy(stub = tail.stub.copy(status = status, modelName = runtime.model ?: "Codex")))
        } else add(RemoteMessageGroup(ChatMessage(id = "remote-active-${runtime.activeTurnId}",
            parentId = tail?.stub?.id, text = "", participant = Participant.MODEL,
            timestamp = tail?.stub?.timestamp ?: 0, modelName = runtime.model ?: "Codex",
            runId = runtime.activeTurnId, status = MessageStatus.SENDING), emptyList()))
    }
}

/** Only native topology changes replace structure; payload hydration cannot evict its prefix. */
internal fun mergeRemoteTopology(old: List<RemoteMessageNode>, fresh: List<RemoteMessageNode>): List<RemoteMessageNode> {
    val boundary = fresh.firstOrNull()?.id ?: return emptyList()
    val index = old.indexOfFirst { it.id == boundary }
    return (old.take(index.coerceAtLeast(0)) + fresh).distinctBy { it.id }
}
