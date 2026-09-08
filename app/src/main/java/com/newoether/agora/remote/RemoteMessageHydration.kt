package com.newoether.agora.remote

import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.ui.chat.HydratedMessagePayloadLru
import com.newoether.agora.viewmodel.MessagePayloadProjector
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** The original MessageList requests bodies only for composed items; its topology stays untouched. */
internal class RemoteMessageHydration(
    private val state: StateFlow<RemoteState>,
    private val read: suspend (String, List<RemotePayloadRequest>) -> List<RemoteMessage>,
    private val failed: (Exception) -> Unit,
) {
    private var cacheOwner: String? = null
    private var cache = HydratedMessagePayloadLru()
    private val revisions = mutableMapOf<String, List<String>>()
    private val slots = Semaphore(4)
    private val projector = MessagePayloadProjector()
    private data class Target(val group: RemoteMessageGroup, val runtime: RemoteRuntime?, val retry: Long)

    private fun target(owner: String, id: String): Target? {
        val snapshot = state.value
        if (!snapshot.hydrationEnabled || snapshot.owner != owner) return null
        val group = snapshot.messageGroups.firstOrNull { it.stub.id == id } ?: return null
        return Target(group, snapshot.runtime.takeIf { snapshot.messageGroups.lastOrNull()?.stub?.id == id },
            snapshot.hydrationRevision)
    }

    private fun cached(owner: String, group: RemoteMessageGroup): ChatMessage? {
        if (cacheOwner != owner) {
            cacheOwner = owner
            cache = HydratedMessagePayloadLru()
            revisions.clear()
        }
        return cache[group.stub.id]?.takeIf { revisions[group.stub.id] == group.revision }
            ?.copy(status = group.stub.status, parentId = group.stub.parentId)
    }

    private fun remember(group: RemoteMessageGroup, message: ChatMessage) {
        cache.put(message)
        revisions[group.stub.id] = group.revision
    }

    private suspend fun records(owner: String, group: RemoteMessageGroup): List<RemoteMessage> = slots.withPermit {
        val messages = mutableListOf<RemoteMessage>()
        for (requests in group.requests.chunked(3)) {
            currentCoroutineContext().ensureActive()
            if (state.value.owner != owner || !state.value.hydrationEnabled) throw CancellationException()
            messages += read(owner, requests)
        }
        currentCoroutineContext().ensureActive()
        if (state.value.owner != owner || !state.value.hydrationEnabled) throw CancellationException()
        messages
    }

    fun observeMessage(owner: String, id: String): Flow<ChatMessage?> = channelFlow {
        var previous = emptyList<RemoteMessage>()
        var previousRuntime: RemoteRuntime? = null
        val deltas = RemoteStreamDeltas()
        state.map { target(owner, id) }.distinctUntilChanged().collectLatest { target ->
            if (target == null) { send(null); return@collectLatest }
            val group = target.group
            if (group.nodes.isEmpty()) { send(group.stub); return@collectLatest }
            val cached = cached(owner, group)
            val active = group.stub.status in setOf(MessageStatus.SENDING, MessageStatus.THINKING, MessageStatus.TOOL_CALLING)
            if (cached != null && !active) { send(cached); return@collectLatest }
            try {
                val fresh = records(owner, group)
                val projected = projector.project {
                    val animated = deltas.apply(previous, fresh, previousRuntime, target.runtime)
                    projectRemoteMessages(animated, target.runtime).firstOrNull { it.id == id }
                        ?.copy(parentId = group.stub.parentId, status = group.stub.status) ?: group.stub
                }
                currentCoroutineContext().ensureActive()
                previous = fresh
                previousRuntime = target.runtime
                remember(group, projected)
                send(projected)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { failed(error); send(cached) }
        }
    }

    /** Original conversation Search supplies bounded batches of structural message IDs. */
    suspend fun loadMessages(owner: String, ids: List<String>): List<ChatMessage> = buildList {
        for (id in ids.distinct()) {
            val target = target(owner, id) ?: continue
            val group = target.group
            val existing = cached(owner, group)
            if (existing != null) { add(existing); continue }
            val fresh = records(owner, group)
            val message = projector.project {
                projectRemoteMessages(fresh).firstOrNull { it.id == id }
                    ?.copy(parentId = group.stub.parentId) ?: group.stub
            }
            remember(group, message)
            add(message)
        }
    }
}
