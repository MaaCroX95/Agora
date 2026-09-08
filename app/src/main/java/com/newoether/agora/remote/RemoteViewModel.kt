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
    private var historyAdmissionSelection: Long? = null
    private var visible = false
    private var polling: Job? = null
    private var paging: Job? = null
    private var statusPolling: Job? = null
    private var visibleSessions: List<String> = emptyList()
    private var modelLoading: Job? = null
    private var modelEpoch = 0L
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
                mutableState.value = state.value.copy(viewedTurns = restored.flatMap { (client, connection) ->
                    connection.viewedTurns.map { (session, turn) -> "${client.address}/$session" to turn }
                }.toMap(), devices = restored.map { (client, connection) ->
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
        mutableState.value = state.value.copy(controlling = false, stoppingOwner = null, stoppingTurnId = null)
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
                    sessionOwners = state.value.sessionOwners.filterKeys { replaced == id || !it.startsWith("$replaced/") },
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
                    sessionOwners = state.value.sessionOwners.filterKeys { !it.startsWith("$id/") },
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
        mutableState.value = state.value.copy(controlling = false, stoppingOwner = null, stoppingTurnId = null)
        invalidateReads()
        modelEpoch++
        modelLoading?.cancel()
        mutableState.value = state.value.copy(deviceId = id, addingDevice = false, editedDeviceId = null,
            sessions = emptyList(), sessionCursor = null, sessionStatuses = emptyMap(),
            session = null, messages = emptyList(), historyCursor = null, queued = emptyList(), failure = null,
            runtime = null, models = emptyList(), modelsLoading = false, composerFocusOwner = null,
            draftSessionId = null, draftSettings = RemoteSettings(), draftNativeSession = null)
        refresh()
    }

    fun selectSession(session: RemoteSession?) {
        scrollRequests.clear()
        selectionEpoch++
        mutableState.value = state.value.copy(controlling = false, stoppingOwner = null, stoppingTurnId = null)
        invalidateReads()
        mutableState.value = state.value.copy(session = session, messages = emptyList(),
            historyCursor = null, queued = emptyList(), failure = null, runtime = null, composerFocusOwner = null,
            draftSessionId = null, draftSettings = RemoteSettings(), draftNativeSession = null)
        historyAdmissionSelection = selectionEpoch.takeIf { session?.readOnly == true && session.canResume }
        refresh()
    }

    fun setVisible(value: Boolean) {
        if (visible == value) return
        visible = value
        trace(if (value) "visible" else "hidden")
        if (value) refresh() else {
            invalidateReads()
            modelEpoch++
            modelLoading?.cancel()
            mutableState.value = state.value.copy(modelsLoading = false)
        }
    }

    private fun invalidateReads() {
        epoch++
        polling?.cancel()
        paging?.cancel()
        statusPolling?.cancel()
        mutableState.value = state.value.copy(loading = false, loadingMore = false, runtime = null)
    }

    fun refresh() {
        if (!visible || state.value.restoring || state.value.addingDevice || state.value.isDraft) return
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
        if (session?.readOnly != true && modelLoading?.isActive != true) {
            val modelGeneration = ++modelEpoch
            mutableState.value = state.value.copy(modelsLoading = true)
            modelLoading = viewModelScope.launch {
                try {
                    val models = client.models()
                    if (modelGeneration == modelEpoch && clients[id] === client && state.value.deviceId == id) {
                        mutableState.value = state.value.copy(models = models)
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { trace("models_failed", error) }
                finally {
                    if (modelGeneration == modelEpoch) mutableState.value = state.value.copy(modelsLoading = false)
                }
            }
        }
        polling = viewModelScope.launch {
            mutableState.value = state.value.copy(loading = true)
            do {
                try {
                    if (session == null) {
                        val page = client.sessions()
                        if (generation != epoch) return@launch
                        mutableState.value = state.value.copy(sessions = page.sessions, sessionCursor = page.nextCursor,
                            loading = false, failure = null)
                        startSessionStatusReads()
                    } else if (session.readOnly) {
                        applyPage(client, session.id, generation, client.conversation(session.id))
                        if (generation != epoch) return@launch
                        if (historyAdmissionSelection == selectionEpoch) {
                            historyAdmissionSelection = null // A selection admits once; refresh/reconnect never resends.
                            client.resume(session.id)
                            if (generation != epoch) return@launch
                            markConnected(session)
                            refresh()
                            return@launch
                        }
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
                        mutableState.value = state.value.copy(loading = false, failure = failure, runtime = null,
                            stoppingOwner = null, stoppingTurnId = null)
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
        settleStop()
        page.runtime?.completedTurnId?.let { turn ->
            val address = state.value.deviceId ?: return@let
            val key = "$address/$sessionId"
            if (visible && state.value.viewedTurns[key] != turn) {
                mutableState.value = state.value.copy(viewedTurns = state.value.viewedTurns + (key to turn))
                viewModelScope.launch {
                    try { connections.markViewed(address, sessionId, turn) }
                    catch (error: Exception) {
                        if (error is CancellationException) throw error
                        trace("mark_viewed_failed", error)
                        mutableState.value = state.value.copy(storageError = true)
                    }
                }
            }
        }
    }

    fun observeSessions(deviceId: String, ids: List<String>) {
        if (state.value.deviceId != deviceId || state.value.session != null) return
        val allowed = state.value.sessions.map { it.id }.toSet()
        val next = ids.distinct().filter { it in allowed }.take(12)
        if (next == visibleSessions && statusPolling?.isActive == true) return
        visibleSessions = next
        startSessionStatusReads()
    }

    private fun startSessionStatusReads() {
        statusPolling?.cancel()
        val snapshot = state.value
        if (!visible || snapshot.session != null || snapshot.addingDevice) return
        val address = snapshot.deviceId ?: return
        val client = clients[address] ?: return
        val ids = visibleSessions.filter { id -> snapshot.sessions.any { it.id == id } }
        if (ids.isEmpty()) return
        val selected = selectionEpoch
        statusPolling = viewModelScope.launch {
            while (isActive && visible && selected == selectionEpoch) {
                try {
                    val statuses = client.sessionStatuses(ids)
                    if (selected != selectionEpoch || clients[address] !== client || !visible) return@launch
                    val previous = state.value.sessionStatuses
                    val merged = statuses.associate { item ->
                        val old = previous[item.id]
                        val resolved = if (item.status == null && old != null) old.copy(status = null) else item
                        item.id to resolved.copy(hasUnreadTurn = resolved.hasUnreadTurn ||
                            item.completedTurnId != null && (old?.status == "active" &&
                                old.activeTurnId == item.completedTurnId ||
                                old?.hasUnreadTurn == true && old.completedTurnId == item.completedTurnId))
                    }
                    mutableState.value = state.value.copy(sessionStatuses = previous + merged)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) {
                    if (selected != selectionEpoch) return@launch
                    trace("session_status_failed", error)
                    mutableState.value = state.value.copy(sessionStatuses = state.value.sessionStatuses.mapValues { (id, value) ->
                        if (id in ids) value.copy(status = null) else value
                    })
                    if (error is FiloHttpException && error.status in setOf(401, 403, 404, 501)) return@launch
                }
                delay(3000)
            }
        }
    }

    private fun settleStop() {
        val snapshot = state.value
        val runtime = snapshot.runtime ?: return
        if (!snapshot.isStopping || snapshot.controlling) return
        if (!runtime.isRunning || runtime.activeTurnId != null && runtime.activeTurnId != snapshot.stoppingTurnId) {
            mutableState.value = snapshot.copy(stoppingOwner = null, stoppingTurnId = null)
        }
    }

    fun newSession() {
        val snapshot = state.value
        if (snapshot.deviceId !in clients || snapshot.isDraft || snapshot.controlling) return
        scrollRequests.clear()
        selectionEpoch++
        mutableState.value = state.value.copy(controlling = false, stoppingOwner = null, stoppingTurnId = null)
        invalidateReads()
        val id = UUID.randomUUID().toString()
        // A local composer identity survives promotion to the first native session.
        mutableState.value = state.value.copy(
            session = RemoteSession(id, "", "", 0), draftSessionId = id, draftSettings = RemoteSettings(), draftNativeSession = null,
            messages = emptyList(), historyCursor = null, queued = emptyList(), failure = null,
            composerFocusOwner = "${snapshot.deviceId}/$id",
        )
    }

    fun completeComposerFocus(owner: String) {
        if (state.value.composerFocusOwner == owner) mutableState.value = state.value.copy(composerFocusOwner = null)
    }

    fun resumeSession() {
        val session = state.value.session ?: return
        if (!session.readOnly || !session.canResume) return
        val selected = selectionEpoch
        control { client ->
            client.resume(session.id)
            if (selected == selectionEpoch) {
                markConnected(session)
                refresh()
            }
        }
    }

    private fun markConnected(session: RemoteSession) {
        val connected = session.copy(readOnly = false, canResume = false)
        mutableState.value = state.value.copy(session = connected,
            sessions = state.value.sessions.map { if (it.id == session.id) connected else it }, failure = null)
    }

    fun setModel(model: String) {
        val snapshot = state.value
        val session = snapshot.session ?: return
        val available = snapshot.models.firstOrNull { it.id == model } ?: return
        if (!snapshot.canEditSettings || model == snapshot.selectedModel) return
        val settings = if (available.reasoningEfforts != null) snapshot.settingsForModel(available) else RemoteSettings(model = model)
        if (snapshot.isDraft) {
            mutableState.value = snapshot.copy(draftSettings = snapshot.draftSettings.merge(settings))
        } else if (available.reasoningEfforts == null) {
            val selected = selectionEpoch
            control { client -> client.setModel(session.id, model); if (selected == selectionEpoch) refresh() }
        } else changeSettings(settings)
    }

    fun setThinkingEnabled(enabled: Boolean) {
        val snapshot = state.value
        if (enabled == (snapshot.selectedEffort != null && snapshot.selectedEffort != "none")) return
        val model = snapshot.settingsModel ?: return
        val effort = if (enabled) model.defaultReasoningEffort?.takeUnless { it == "none" }
            ?: model.reasoningEfforts?.firstOrNull { it != "none" } else "none"
        effort?.let(::setThinkingLevel)
    }

    fun setThinkingLevel(effort: String) {
        val snapshot = state.value
        if (effort == snapshot.selectedEffort || effort !in snapshot.settingsModel?.reasoningEfforts.orEmpty()) return
        changeSettings(RemoteSettings(effort = effort))
    }

    fun setServiceTierEnabled(enabled: Boolean) {
        val snapshot = state.value
        if (enabled == (snapshot.selectedServiceTier != null)) return
        val model = snapshot.settingsModel ?: return
        val tier = if (enabled) model.defaultServiceTier?.takeIf { value -> model.serviceTiers.orEmpty().any { it.id == value } }
            ?: model.serviceTiers?.firstOrNull()?.id ?: return else null
        setServiceTier(tier)
    }

    fun setServiceTier(tier: String?) {
        val snapshot = state.value
        if (snapshot.settingsModel?.serviceTiers == null || tier == snapshot.selectedServiceTier ||
            tier != null && snapshot.settingsModel?.serviceTiers.orEmpty().none { it.id == tier }) return
        changeSettings(RemoteSettings(serviceTier = tier, updateServiceTier = true))
    }

    private fun changeSettings(settings: RemoteSettings) {
        val snapshot = state.value
        if (!snapshot.canEditSettings) return
        if (snapshot.isDraft) {
            mutableState.value = snapshot.copy(draftSettings = snapshot.draftSettings.merge(settings))
            return
        }
        val selected = selectionEpoch
        control { client ->
            val id = snapshot.session!!.id
            client.updateSettings(id, settings)
            if (selected == selectionEpoch && clients[snapshot.deviceId] === client && visible) {
                val generation = epoch
                applyPage(client, id, generation, client.conversation(id))
            }
        }
    }

    fun stop() {
        val snapshot = state.value
        val session = snapshot.session ?: return
        if (session.readOnly || snapshot.runtime?.isRunning != true) return
        val turn = snapshot.runtime.activeTurnId ?: return
        control(stoppingTurnId = turn) { client -> client.stop(session.id, turn) }
    }

    private fun control(stoppingTurnId: String? = null, operation: suspend (FiloClient) -> Unit) {
        if (state.value.controlling || state.value.isStopping) return
        val client = clients[state.value.deviceId] ?: return
        val selected = selectionEpoch
        val owner = state.value.owner
        mutableState.value = state.value.copy(controlling = true,
            stoppingOwner = if (stoppingTurnId != null) owner else state.value.stoppingOwner,
            stoppingTurnId = stoppingTurnId ?: state.value.stoppingTurnId)
        viewModelScope.launch {
            var succeeded = false
            try { operation(client); succeeded = true }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                val failure = trace("control_failed", error)
                if (selected == selectionEpoch) mutableState.value = state.value.copy(failure = failure)
            } finally {
                if (selected == selectionEpoch) mutableState.value = state.value.copy(controlling = false)
                if (stoppingTurnId != null && state.value.stoppingOwner == owner &&
                    state.value.stoppingTurnId == stoppingTurnId && (!succeeded || selected != selectionEpoch)) {
                    mutableState.value = state.value.copy(stoppingOwner = null, stoppingTurnId = null)
                }
                // A Stop receipt and the native end-of-turn snapshot may arrive in either order.
                settleStop()
            }
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
                    require(page.nextCursor != cursor) { "Filo session cursor did not advance" }
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
        if (snapshot.session?.readOnly == true || snapshot.controlling || snapshot.isStopping ||
            !snapshot.isDraft && snapshot.runtime?.status !in setOf("idle", "active", "ready")) return
        val owner = snapshot.owner ?: return
        val client = clients[snapshot.deviceId] ?: return
        val text = snapshot.drafts[owner].orEmpty()
        if (text.isBlank() || snapshot.attempts[owner]?.delivery in
            setOf(RemoteDelivery.SUBMITTING, RemoteDelivery.ACCEPTED, RemoteDelivery.UNKNOWN)) return
        val selected = selectionEpoch
        val attempt = RemoteAttempt(UUID.randomUUID().toString(), text, RemoteDelivery.SUBMITTING)
        mutableState.value = state.value.copy(attempts = state.value.attempts + (owner to attempt))
        viewModelScope.launch {
            var knownSession = !snapshot.isDraft
            var inputStarted = false
            try {
                var sessionId = snapshot.session!!.id
                if (snapshot.isDraft) {
                    val created = snapshot.draftNativeSession ?: client.create()
                    knownSession = true
                    if (selected != selectionEpoch || clients[snapshot.deviceId] !== client) {
                        if (state.value.attempts[owner]?.clientId == attempt.clientId) {
                            mutableState.value = state.value.copy(attempts = state.value.attempts +
                                (owner to attempt.copy(delivery = RemoteDelivery.REJECTED)))
                        }
                        return@launch
                    }
                    sessionId = created.id
                    mutableState.value = state.value.copy(draftNativeSession = created)
                    val model = snapshot.settingsModel
                    if (model?.reasoningEfforts != null) {
                        client.updateSettings(sessionId, RemoteSettings(model.id, snapshot.selectedEffort,
                            snapshot.selectedServiceTier, updateServiceTier = true))
                    } else snapshot.draftSettings.model?.let { client.setModel(sessionId, it) }
                    if (selected != selectionEpoch || clients[snapshot.deviceId] !== client) throw FiloInputException()
                    mutableState.value = state.value.copy(session = created,
                        sessionOwners = state.value.sessionOwners + ("${snapshot.deviceId}/${created.id}" to owner))
                    refresh()
                }
                inputStarted = true
                client.send(sessionId, text, attempt.clientId)
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
                    val rejected = knownSession && !inputStarted || error is FiloInputException ||
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
