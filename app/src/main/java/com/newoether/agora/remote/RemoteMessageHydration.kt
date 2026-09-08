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
    private val image: (suspend (String, RemotePayloadRequest) -> com.newoether.agora.model.ToolImageAttachment)? = null,
) {
    private val cacheLock = Any()
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

    private suspend fun cached(owner: String, group: RemoteMessageGroup, includeImages: Boolean = false): ChatMessage? {
        val message = synchronized(cacheLock) {
            if (state.value.owner != owner) return null
            if (cacheOwner != owner) {
                cacheOwner = owner
                cache = HydratedMessagePayloadLru()
                revisions.clear()
            }
            cache[group.stub.id]?.takeIf { revisions[group.stub.id] == group.revision }
        } ?: return null
        if (includeImages && group.nodes.any { it.activity?.hasImage == true } && kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            group.nodes.any { node ->
                node.activity?.hasImage == true && message.segments.orEmpty()
                    .firstOrNull { it.toolCallId == node.id }?.toolImages.orEmpty()
                    .none { java.io.File(it.path).isFile }
            }
        }) return null
        if (state.value.owner != owner || !state.value.hydrationEnabled) throw CancellationException()
        return message.copy(status = group.stub.status, parentId = group.stub.parentId)
    }

    private fun remember(owner: String, group: RemoteMessageGroup, message: ChatMessage) = synchronized(cacheLock) {
        if (state.value.owner == owner && cacheOwner == owner) {
            cache.put(message)
            revisions[group.stub.id] = group.revision
        }
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
            val cached = cached(owner, group, includeImages = true)
            val active = group.stub.status in setOf(MessageStatus.SENDING, MessageStatus.THINKING, MessageStatus.TOOL_CALLING)
            if (cached != null && !active) { send(cached); return@collectLatest }
            try {
                val fresh = records(owner, group).map { message ->
                    if (message.activity?.imagePath == null || image == null) message
                    else try {
                        val attachment = image.invoke(owner, group.requests.first { it.id == message.id })
                        message.copy(activity = message.activity.copy(images = listOf(attachment)))
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) { failed(error); message }
                }
                val projected = projector.project {
                    val animated = deltas.apply(previous, fresh, previousRuntime, target.runtime)
                    projectRemoteMessages(animated, target.runtime).firstOrNull { it.id == id }
                        ?.copy(parentId = group.stub.parentId, status = group.stub.status) ?: group.stub
                }
                currentCoroutineContext().ensureActive()
                previous = fresh
                previousRuntime = target.runtime
                remember(owner, group, projected)
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
            currentCoroutineContext().ensureActive()
            if (state.value.owner != owner || !state.value.hydrationEnabled) throw CancellationException()
            remember(owner, group, message)
            add(message)
        }
    }
}
