package com.newoether.agora.remote

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID

internal data class RemoteDevice(val id: String, val name: String, val address: String)
internal enum class RemoteDelivery { SUBMITTING, QUEUED, REJECTED, UNKNOWN }
internal data class RemoteAttempt(val clientId: String, val text: String, val delivery: RemoteDelivery)
internal data class RemoteState(
    val devices: List<RemoteDevice> = emptyList(), val deviceId: String? = null,
    val sessions: List<RemoteSession> = emptyList(), val sessionCursor: String? = null,
    val session: RemoteSession? = null, val messages: List<RemoteMessage> = emptyList(),
    val historyCursor: String? = null, val queued: List<RemoteQueuedMessage> = emptyList(),
    val drafts: Map<String, String> = emptyMap(), val attempts: Map<String, RemoteAttempt> = emptyMap(),
    val connecting: Boolean = false, val loading: Boolean = false, val error: Boolean = false,
) {
    val owner: String? get() = session?.let { "$deviceId/${it.id}" }
}

/** Remote owns transport and in-memory state; native Codex owns durable execution. */
internal class RemoteViewModel(
    private val createClient: (String, String) -> FiloClient = { address, token -> FiloClient(address, token) },
) : ViewModel() {
    private val mutableState = MutableStateFlow(RemoteState())
    val state = mutableState.asStateFlow()
    private val clients = mutableMapOf<String, FiloClient>()
    private var epoch = 0L
    private var visible = false
    private var polling: Job? = null
    private var paging: Job? = null
    private var connecting: Job? = null

    fun connect(address: String, token: String) {
        if (state.value.connecting) return
        mutableState.value = state.value.copy(connecting = true, error = false)
        connecting = viewModelScope.launch {
            try {
                val client = createClient(address, token.trim())
                val name = client.connect()
                val id = client.address
                clients[id] = client
                val device = RemoteDevice(id, name, client.address)
                mutableState.value = state.value.copy(
                    devices = state.value.devices.filterNot { it.id == id } + device,
                    connecting = false,
                )
                selectDevice(id)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableState.value = state.value.copy(connecting = false, error = true) }
        }
    }

    fun selectDevice(id: String?) {
        if (id != null && id !in clients) return
        invalidateReads()
        mutableState.value = state.value.copy(deviceId = id, sessions = emptyList(), sessionCursor = null,
            session = null, messages = emptyList(), historyCursor = null, queued = emptyList(), error = false)
        refresh()
    }

    fun selectSession(session: RemoteSession?) {
        invalidateReads()
        mutableState.value = state.value.copy(session = session, messages = emptyList(),
            historyCursor = null, queued = emptyList(), error = false)
        refresh()
    }

    fun setVisible(value: Boolean) {
        if (visible == value) return
        visible = value
        if (value) refresh() else invalidateReads()
    }

    private fun invalidateReads() {
        epoch++
        polling?.cancel()
        paging?.cancel()
        mutableState.value = state.value.copy(loading = false)
    }

    fun refresh() {
        if (!visible) return
        polling?.cancel()
        val client = clients[state.value.deviceId] ?: return
        val generation = epoch
        val session = state.value.session
        polling = viewModelScope.launch {
            mutableState.value = state.value.copy(loading = true)
            do {
                try {
                    if (session == null) {
                        val page = client.sessions()
                        if (generation != epoch) return@launch
                        mutableState.value = state.value.copy(sessions = page.sessions, sessionCursor = page.nextCursor,
                            loading = false, error = false)
                    } else {
                        val page = client.conversation(session.id)
                        if (generation != epoch) return@launch
                        var fresh = page.messages
                        var cursor = page.nextCursor
                        val old = state.value.messages
                        val cursors = mutableSetOf<String>()
                        while (old.isNotEmpty() && fresh.none { item -> old.any { it.id == item.id } } &&
                            cursor != null && cursors.add(cursor)) {
                            val older = client.conversation(session.id, cursor)
                            fresh = older.messages + fresh
                            cursor = older.nextCursor
                        }
                        if (generation != epoch) return@launch
                        val owner = state.value.owner!!
                        val attempt = state.value.attempts[owner]
                        val accepted = attempt != null && (page.queued.any { it.clientId == attempt.clientId } ||
                            fresh.any { it.clientId == attempt.clientId })
                        mutableState.value = state.value.copy(
                            messages = mergeRemoteHistory(state.value.messages, fresh), queued = page.queued,
                            historyCursor = if (old.isEmpty()) page.nextCursor else state.value.historyCursor,
                            loading = false, error = false,
                        )
                        if (accepted) accept(owner, attempt)
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) {
                    if (generation == epoch) mutableState.value = state.value.copy(loading = false, error = true)
                }
                if (session == null) break
                delay(3000)
            } while (isActive && visible && generation == epoch)
        }
    }

    fun loadMore() {
        if (paging?.isActive == true || state.value.loading) return
        val snapshot = state.value
        val client = clients[snapshot.deviceId] ?: return
        val cursor = (if (snapshot.session == null) snapshot.sessionCursor else snapshot.historyCursor) ?: return
        val generation = epoch
        paging = viewModelScope.launch {
            try {
                if (snapshot.session == null) {
                    val page = client.sessions(cursor)
                    if (generation == epoch) mutableState.value = state.value.copy(
                        sessions = (state.value.sessions + page.sessions).distinctBy { it.id }, sessionCursor = page.nextCursor)
                } else {
                    val page = client.conversation(snapshot.session.id, cursor)
                    if (generation == epoch) mutableState.value = state.value.copy(
                        messages = page.messages.filterNot { item -> state.value.messages.any { it.id == item.id } } +
                            state.value.messages, historyCursor = page.nextCursor)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (generation == epoch) mutableState.value = state.value.copy(error = true) }
        }
    }

    fun editDraft(owner: String, text: String) {
        mutableState.value = state.value.copy(drafts = state.value.drafts + (owner to text))
    }

    fun acknowledgeUnknown(owner: String) {
        if (state.value.attempts[owner]?.delivery == RemoteDelivery.UNKNOWN) {
            mutableState.value = state.value.copy(attempts = state.value.attempts - owner)
        }
    }

    fun send() {
        val snapshot = state.value
        val owner = snapshot.owner ?: return
        val client = clients[snapshot.deviceId] ?: return
        val text = snapshot.drafts[owner].orEmpty()
        if (text.isBlank() || snapshot.attempts[owner]?.delivery in
            setOf(RemoteDelivery.SUBMITTING, RemoteDelivery.UNKNOWN)) return
        val attempt = RemoteAttempt(UUID.randomUUID().toString(), text, RemoteDelivery.SUBMITTING)
        mutableState.value = state.value.copy(attempts = state.value.attempts + (owner to attempt))
        viewModelScope.launch {
            try {
                client.send(snapshot.session!!.id, text, attempt.clientId)
                accept(owner, attempt)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (state.value.attempts[owner]?.clientId == attempt.clientId &&
                    state.value.attempts[owner]?.delivery == RemoteDelivery.SUBMITTING) {
                    val rejected = error is FiloInputException ||
                        error is FiloHttpException && error.status in setOf(400, 401, 403, 404, 413, 415, 429)
                    mutableState.value = state.value.copy(attempts = state.value.attempts +
                        (owner to attempt.copy(delivery = if (rejected) RemoteDelivery.REJECTED else RemoteDelivery.UNKNOWN)))
                }
            }
        }
    }

    private fun accept(owner: String, attempt: RemoteAttempt) {
        if (state.value.attempts[owner]?.clientId != attempt.clientId) return
        if (state.value.attempts[owner]?.delivery == RemoteDelivery.QUEUED) return
        mutableState.value = state.value.copy(
            drafts = if (state.value.drafts[owner] == attempt.text) state.value.drafts - owner else state.value.drafts,
            attempts = state.value.attempts + (owner to attempt.copy(delivery = RemoteDelivery.QUEUED)),
        )
    }
}
