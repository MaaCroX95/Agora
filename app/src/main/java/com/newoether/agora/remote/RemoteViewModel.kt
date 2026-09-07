package com.newoether.agora.remote

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.newoether.agora.diagnostics.DeveloperDiagnostics
import com.newoether.agora.diagnostics.DiagnosticRequestContext
import com.newoether.agora.viewmodel.ScrollRequestCoordinator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.UUID

internal enum class RemoteDeviceStatus { IDLE, CONNECTING, CONNECTED, ERROR }
internal data class RemoteDevice(
    val id: String, val name: String, val address: String,
    val status: RemoteDeviceStatus = RemoteDeviceStatus.IDLE, val failure: RemoteFailure? = null,
)
internal enum class RemoteDelivery { SUBMITTING, ACCEPTED, DELIVERED, REJECTED, UNKNOWN }
internal data class RemoteAttempt(val clientId: String, val text: String, val delivery: RemoteDelivery)
internal data class RemoteState(
    val devices: List<RemoteDevice> = emptyList(), val deviceId: String? = null,
    val sessions: List<RemoteSession> = emptyList(), val sessionCursor: String? = null,
    val session: RemoteSession? = null, val messages: List<RemoteMessage> = emptyList(),
    val historyCursor: String? = null, val queued: List<RemoteQueuedMessage> = emptyList(),
    val drafts: Map<String, String> = emptyMap(), val attempts: Map<String, RemoteAttempt> = emptyMap(),
    val saving: Boolean = false, val loading: Boolean = false, val loadingMore: Boolean = false, val failure: RemoteFailure? = null,
    val restoring: Boolean = true, val storageError: Boolean = false, val addingDevice: Boolean = false,
    val editedDeviceId: String? = null,
    val runtime: RemoteRuntime? = null, val models: List<RemoteModel> = emptyList(),
    val controlling: Boolean = false,
) {
    val error: Boolean get() = failure != null
    val owner: String? get() = session?.let { "$deviceId/${it.id}" }
}

/** Remote owns saved connections and presentation; native Codex owns durable execution. */
internal class RemoteViewModel(
    private val connections: RemoteConnectionStore,
    private val createClient: (String, String) -> FiloClient = { address, token -> FiloClient(address, token) },
) : ViewModel() {
    private val mutableState = MutableStateFlow(RemoteState())
    val state = mutableState.asStateFlow()
    private val scrollRequests = ScrollRequestCoordinator()
    val animatedScrollRequest = scrollRequests.request
    fun completeAnimatedScroll(id: Long) = scrollRequests.complete(id)
    private val clients = mutableMapOf<String, FiloClient>()
    private val configurations = mutableMapOf<String, RemoteConnection>()
    private val checks = mutableMapOf<String, Job>()
    private val checkSlots = Semaphore(2)
    private var epoch = 0L
    private var selectionEpoch = 0L
    private var visible = false
    private var polling: Job? = null
    private var paging: Job? = null
    private var storing: Job? = null
    private val createdAt = System.nanoTime()
    private val diagnosticContext = DiagnosticRequestContext(
        requestId = UUID.randomUUID().toString(), provider = "Filo", model = "Codex", requestKind = "remote",
    )

    init { trace("owner_created"); restoreConnections() }

    private fun trace(stage: String, error: Exception? = null): RemoteFailure? {
        val failure = error?.let(::classifyRemoteFailure)
        val suffix = if (failure == null) "" else ".${failure.name}.${error.javaClass.simpleName}"
        DeveloperDiagnostics.recordHttpStage(diagnosticContext, "remote.$stage$suffix",
            (System.nanoTime() - createdAt) / 1_000_000,
            "addresses=${state.value.devices.size}" + if (error is FiloHttpException) " code=${error.status}" else "")
        return failure
    }

    override fun onCleared() {
        trace("owner_cleared")
        super.onCleared()
    }

    fun restoreConnections() {
        if (storing?.isActive == true) return
        trace("restore_started")
        mutableState.value = state.value.copy(restoring = true, storageError = false)
        selectDevice(null)
        storing = viewModelScope.launch {
            try {
                val restored = connections.load().map { connection ->
                    createClient(connection.address, connection.token) to connection
                }
                checks.values.forEach { it.cancel() }
                checks.clear()
                clients.clear()
                configurations.clear()
                restored.forEach { (client, _) -> clients[client.address] = client }
                restored.forEach { (client, connection) -> configurations[client.address] = connection }
                mutableState.value = state.value.copy(devices = restored.map { (client, connection) ->
                    RemoteDevice(client.address, connection.name, client.address)
                })
                trace("restore_completed")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                trace("restore_failed", error)
                mutableState.value = state.value.copy(storageError = true)
            }
            finally { mutableState.value = state.value.copy(restoring = false) }
            if (!state.value.storageError) refresh()
        }
    }

    fun addDevice() = editDevice(null)

    fun editorConnection(): RemoteConnection? = configurations[state.value.editedDeviceId]

    fun editDevice(id: String?) {
        if (state.value.saving || state.value.restoring || (id != null && id !in clients)) return
        selectionEpoch++
        invalidateReads()
        mutableState.value = state.value.copy(deviceId = null, session = null, addingDevice = true,
            editedDeviceId = id, storageError = false, failure = null)
    }

    fun saveDevice(address: String, token: String) {
        if (state.value.saving || state.value.restoring) return
        val generation = selectionEpoch
        val previous = state.value.editedDeviceId
        mutableState.value = state.value.copy(saving = true, failure = null, storageError = false)
        trace("save_started")
        storing = viewModelScope.launch {
            try {
                val client = createClient(address, token.trim())
                val id = client.address
                if (previous != null && previous != id && id in clients) throw FiloConfigurationException()
                val name = state.value.devices.firstOrNull { it.id == id }?.name ?: id
                val connection = RemoteConnection(name, id, token.trim())
                connections.save(connection, previous)
                val replaced = previous ?: id
                checks.remove(replaced)?.cancel()
                clients.remove(replaced)
                configurations.remove(replaced)
                clients[id] = client
                configurations[id] = connection
                val device = RemoteDevice(id, name, client.address)
                val devices = state.value.devices
                mutableState.value = state.value.copy(
                    devices = if (devices.any { it.id == replaced }) {
                        devices.map { if (it.id == replaced) device else it }
                    } else devices + device,
                    drafts = state.value.drafts.filterKeys { replaced == id || !it.startsWith("$replaced/") },
                    attempts = state.value.attempts.filterKeys { replaced == id || !it.startsWith("$replaced/") },
                )
                // Saving an explicitly submitted connection must not undo a later Back/navigation.
                if (generation == selectionEpoch) selectDevice(null)
                checkDevice(id)
                trace("saved")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: RemoteStorageException) {
                trace("save_failed", error)
                mutableState.value = state.value.copy(storageError = true)
            }
            catch (error: Exception) {
                val failure = trace("save_failed", error)
                mutableState.value = state.value.copy(
                    failure = if (generation == selectionEpoch) failure else state.value.failure)
            }
            finally { mutableState.value = state.value.copy(saving = false) }
        }
    }

    fun removeDevice(id: String) {
        if (state.value.saving || state.value.restoring || id !in clients) return
        mutableState.value = state.value.copy(saving = true, storageError = false)
        storing = viewModelScope.launch {
            try {
                connections.remove(id)
                checks.remove(id)?.cancel()
                clients.remove(id)
                configurations.remove(id)
                mutableState.value = state.value.copy(
                    devices = state.value.devices.filterNot { it.id == id },
                    drafts = state.value.drafts.filterKeys { !it.startsWith("$id/") },
                    attempts = state.value.attempts.filterKeys { !it.startsWith("$id/") },
                )
                if (state.value.deviceId == id) selectDevice(null)
                trace("device_removed")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                trace("remove_failed", error)
                mutableState.value = state.value.copy(storageError = true)
            }
            finally { mutableState.value = state.value.copy(saving = false) }
        }
    }

    private fun checkDevice(id: String) {
        val client = clients[id] ?: return
        if (checks[id]?.isActive == true) return
        updateDevice(id) { it.copy(status = RemoteDeviceStatus.CONNECTING, failure = null) }
        checks[id] = viewModelScope.launch {
            try {
                val name = checkSlots.withPermit { client.connect() }
                if (clients[id] !== client) return@launch
                updateDevice(id) { it.copy(name = name, status = RemoteDeviceStatus.CONNECTED, failure = null) }
                if (state.value.deviceId == id) refresh()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (clients[id] !== client) return@launch
                val failure = trace("check_failed", error)
                updateDevice(id) { it.copy(status = RemoteDeviceStatus.ERROR, failure = failure) }
                if (state.value.deviceId == id) mutableState.value = state.value.copy(loading = false, failure = failure)
            }
        }
    }

    private fun updateDevice(id: String, update: (RemoteDevice) -> RemoteDevice) {
        mutableState.value = state.value.copy(devices = state.value.devices.map { if (it.id == id) update(it) else it })
    }

    fun selectDevice(id: String?) {
        if (id != null && id !in clients) return
        scrollRequests.clear()
        selectionEpoch++
        invalidateReads()
        mutableState.value = state.value.copy(deviceId = id, addingDevice = false, editedDeviceId = null,
            sessions = emptyList(), sessionCursor = null,
            session = null, messages = emptyList(), historyCursor = null, queued = emptyList(), failure = null,
            runtime = null, models = emptyList())
        refresh()
    }

    fun selectSession(session: RemoteSession?) {
        scrollRequests.clear()
        selectionEpoch++
        invalidateReads()
        mutableState.value = state.value.copy(session = session, messages = emptyList(),
            historyCursor = null, queued = emptyList(), failure = null, runtime = null)
        refresh()
    }

    fun setVisible(value: Boolean) {
        if (visible == value) return
        visible = value
        trace(if (value) "visible" else "hidden")
        if (value) refresh() else invalidateReads()
    }

    private fun invalidateReads() {
        epoch++
        polling?.cancel()
        paging?.cancel()
        mutableState.value = state.value.copy(loading = false, loadingMore = false, runtime = null)
    }

    fun refresh() {
        if (!visible || state.value.restoring || state.value.addingDevice) return
        invalidateReads()
        val id = state.value.deviceId
        if (id == null) {
            clients.keys.forEach(::checkDevice)
            return
        }
        val client = clients[id] ?: return
        if (state.value.devices.firstOrNull { it.id == id }?.status != RemoteDeviceStatus.CONNECTED) {
            mutableState.value = state.value.copy(loading = true, failure = null)
            checkDevice(id)
            return
        }
        val generation = epoch
        val session = state.value.session
        polling = viewModelScope.launch {
            mutableState.value = state.value.copy(loading = true)
            do {
                try {
                    val models = if (session?.readOnly == true) emptyList() else client.models()
                    if (generation != epoch) return@launch
                    mutableState.value = state.value.copy(models = models)
                    if (session == null) {
                        val page = client.sessions()
                        if (generation != epoch) return@launch
                        mutableState.value = state.value.copy(sessions = page.sessions, sessionCursor = page.nextCursor,
                            loading = false, failure = null)
                    } else if (session.readOnly) {
                        applyPage(client, session.id, generation, client.conversation(session.id))
                    } else {
                        client.events(session.id).collect { page ->
                            if (generation == epoch) applyPage(client, session.id, generation, page)
                        }
                    }
                    updateDevice(id) { it.copy(status = RemoteDeviceStatus.CONNECTED, failure = null) }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) {
                    if (generation == epoch) {
                        val failure = trace("read_failed", error)
                        updateDevice(id) { it.copy(status = RemoteDeviceStatus.ERROR, failure = failure) }
                        mutableState.value = state.value.copy(loading = false, failure = failure, runtime = null)
                    }
                }
                if (session == null || session.readOnly) break
                delay(3000)
            } while (isActive && visible && generation == epoch)
        }
    }

    private suspend fun applyPage(client: FiloClient, sessionId: String, generation: Long, page: RemoteConversationPage) {
        var fresh = page.messages
        var cursor = page.nextCursor
        val old = state.value.messages
        val cursors = mutableSetOf<String>()
        while (old.isNotEmpty() && fresh.none { item -> old.any { it.id == item.id } } &&
            cursor != null && cursors.add(cursor)) {
            val older = client.conversation(sessionId, cursor)
            fresh = older.messages + fresh
            cursor = older.nextCursor
        }
        if (generation != epoch) return
        val owner = state.value.owner ?: return
        mutableState.value = state.value.copy(
            messages = mergeRemoteHistory(state.value.messages, fresh), queued = page.queued,
            historyCursor = if (old.isEmpty()) page.nextCursor else state.value.historyCursor,
            loading = false, failure = null, runtime = page.runtime,
        )
        state.value.deviceId?.let { id -> updateDevice(id) { it.copy(status = RemoteDeviceStatus.CONNECTED, failure = null) } }
        state.value.attempts[owner]?.let { attempt -> confirmDelivery(owner, attempt) }
    }

    fun newSession() {
        val selected = selectionEpoch
        control { client ->
            val created = client.create()
            if (selected == selectionEpoch) selectSession(created)
        }
    }

    fun resumeSession() {
        val session = state.value.session ?: return
        if (!session.readOnly || !session.canResume) return
        val selected = selectionEpoch
        control { client ->
            client.resume(session.id)
            if (selected == selectionEpoch) {
                val connected = session.copy(readOnly = false, canResume = false)
                mutableState.value = state.value.copy(session = connected,
                    sessions = state.value.sessions.map { if (it.id == session.id) connected else it }, failure = null)
                refresh()
            }
        }
    }

    fun setModel(model: String) {
        val session = state.value.session ?: return
        if (session.readOnly || state.value.models.none { it.id == model }) return
        val selected = selectionEpoch
        control { client ->
            client.setModel(session.id, model)
            if (selected == selectionEpoch) refresh()
        }
    }

    fun stop() {
        val snapshot = state.value
        val session = snapshot.session ?: return
        if (session.readOnly) return
        val turn = snapshot.runtime?.activeTurnId ?: return
        control { client -> client.stop(session.id, turn) }
    }

    private fun control(operation: suspend (FiloClient) -> Unit) {
        if (state.value.controlling) return
        val client = clients[state.value.deviceId] ?: return
        val selected = selectionEpoch
        mutableState.value = state.value.copy(controlling = true)
        viewModelScope.launch {
            try { operation(client) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                val failure = trace("control_failed", error)
                if (selected == selectionEpoch) mutableState.value = state.value.copy(failure = failure)
            } finally { mutableState.value = state.value.copy(controlling = false) }
        }
    }

    fun loadMore() {
        if (paging?.isActive == true || state.value.loading) return
        val snapshot = state.value
        val client = clients[snapshot.deviceId] ?: return
        val cursor = (if (snapshot.session == null) snapshot.sessionCursor else snapshot.historyCursor) ?: return
        val generation = epoch
        mutableState.value = state.value.copy(loadingMore = true)
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
            catch (error: Exception) {
                if (generation == epoch) mutableState.value = state.value.copy(failure = trace("page_failed", error))
            } finally {
                if (generation == epoch) mutableState.value = state.value.copy(loadingMore = false)
            }
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
        if (snapshot.session?.readOnly == true || snapshot.controlling || snapshot.runtime?.status !in setOf("idle", "active", "ready")) return
        val owner = snapshot.owner ?: return
        val client = clients[snapshot.deviceId] ?: return
        val text = snapshot.drafts[owner].orEmpty()
        if (text.isBlank() || snapshot.attempts[owner]?.delivery in
            setOf(RemoteDelivery.SUBMITTING, RemoteDelivery.ACCEPTED, RemoteDelivery.UNKNOWN)) return
        val attempt = RemoteAttempt(UUID.randomUUID().toString(), text, RemoteDelivery.SUBMITTING)
        mutableState.value = state.value.copy(attempts = state.value.attempts + (owner to attempt))
        viewModelScope.launch {
            try {
                client.send(snapshot.session!!.id, text, attempt.clientId)
                if (state.value.attempts[owner]?.clientId == attempt.clientId &&
                    state.value.attempts[owner]?.delivery == RemoteDelivery.SUBMITTING) {
                    mutableState.value = state.value.copy(attempts = state.value.attempts +
                        (owner to attempt.copy(delivery = RemoteDelivery.ACCEPTED)))
                    confirmDelivery(owner, attempt)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                val failure = trace("send_failed", error)
                if (state.value.owner == owner && failure == RemoteFailure.SESSION_BUSY) {
                    mutableState.value = state.value.copy(failure = failure)
                }
                if (state.value.attempts[owner]?.clientId == attempt.clientId &&
                    state.value.attempts[owner]?.delivery == RemoteDelivery.SUBMITTING) {
                    val rejected = error is FiloInputException ||
                        error is FiloHttpException && error.status in setOf(400, 401, 403, 404, 409, 413, 415, 429)
                    mutableState.value = state.value.copy(attempts = state.value.attempts +
                        (owner to attempt.copy(delivery = if (rejected) RemoteDelivery.REJECTED else RemoteDelivery.UNKNOWN)))
                }
            }
        }
    }

    private fun confirmDelivery(owner: String, attempt: RemoteAttempt) {
        if (state.value.attempts[owner]?.clientId != attempt.clientId) return
        if (state.value.attempts[owner]?.delivery == RemoteDelivery.DELIVERED || state.value.owner != owner) return
        val message = state.value.messages.firstOrNull { it.role == "user" && it.clientId == attempt.clientId } ?: return
        mutableState.value = state.value.copy(
            drafts = if (state.value.drafts[owner] == attempt.text) state.value.drafts - owner else state.value.drafts,
            attempts = state.value.attempts + (owner to attempt.copy(delivery = RemoteDelivery.DELIVERED)),
        )
        scrollRequests.requestAbsoluteBottomAfter(owner, message.id)
    }
}
