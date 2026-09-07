package com.newoether.agora.remote

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.verify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import com.newoether.agora.diagnostics.DeveloperDiagnostics
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.SerializationException
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val client = mockk<FiloClient>()
    private val connections = mockk<RemoteConnectionStore>(relaxed = true)
    private val session = RemoteSession("session", "Existing", "/workspace", 1)
    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        coEvery { connections.load() } returns emptyList()
        every { client.address } returns "http://computer/"
        coEvery { client.connect() } returns "Computer"
        coEvery { client.sessions(any()) } returns RemoteSessionPage(listOf(session), null)
        coEvery { client.conversation(any(), any()) } returns RemoteConversationPage(emptyList(), null, emptyList())
        coEvery { client.models() } returns listOf(RemoteModel("model", "Model", true))
        every { client.events(any()) } answers {
            val id = firstArg<String>()
            flow {
                val page = client.conversation(id)
                emit(page.copy(runtime = page.runtime ?: RemoteRuntime("idle", model = "model")))
                awaitCancellation()
            }
        }
    }
    @After fun tearDown() { Dispatchers.resetMain() }

    private fun TestScope.saveAndSelect(vm: RemoteViewModel) {
        vm.saveDevice("http://computer/", "token"); runCurrent()
        vm.selectDevice("http://computer/"); runCurrent()
    }

    @Test fun leavingAnUnsubmittedDeviceEditorDoesNotConnectOrSave() = runTest(dispatcher) {
        val vm = RemoteViewModel(connections) { _, _ -> client }; runCurrent()
        vm.addDevice()
        assertTrue(vm.state.value.addingDevice)
        assertNull(vm.state.value.deviceId)
        vm.selectDevice(null); runCurrent()
        assertFalse(vm.state.value.addingDevice)
        coVerify(exactly = 0) { client.connect() }
        coVerify(exactly = 0) { connections.save(any(), any()) }
        coVerify(exactly = 0) { client.sessions(any()) }
    }

    @Test fun savedOfflineDeviceReturnsToListWithConnectionFailureOnItsRow() = runTest(dispatcher) {
        val saveGate = CompletableDeferred<Unit>()
        coEvery { connections.save(any(), any()) } coAnswers { saveGate.await() }
        coEvery { client.connect() } throws FiloHttpException(401)
        val vm = RemoteViewModel(connections) { _, _ -> client }; runCurrent()
        vm.setVisible(true)
        vm.addDevice(); vm.saveDevice("http://computer/", "token"); runCurrent()
        assertTrue(vm.state.value.addingDevice)
        assertTrue(vm.state.value.devices.isEmpty())
        coVerify(exactly = 0) { client.connect() }
        saveGate.complete(Unit); runCurrent()
        assertFalse(vm.state.value.addingDevice)
        assertNull(vm.state.value.failure)
        assertNull(vm.state.value.deviceId)
        assertEquals(RemoteFailure.AUTHENTICATION, vm.state.value.devices.single().failure)
        assertEquals(RemoteDeviceStatus.ERROR, vm.state.value.devices.single().status)
        coVerify(exactly = 1) { connections.save(any(), null) }
        coVerify(exactly = 0) { client.sessions(any()) }
        coEvery { client.connect() } returns "Computer"
        vm.selectDevice("http://computer/"); runCurrent()
        assertEquals(listOf(session), vm.state.value.sessions)
        vm.setVisible(false)
    }

    @Test fun saveCompletedAfterBackDoesNotTakeNavigationAndDuplicateSaveIsIgnored() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        coEvery { connections.save(any(), any()) } coAnswers { gate.await() }
        val vm = RemoteViewModel(connections) { _, _ -> client }; runCurrent()
        vm.setVisible(true)
        vm.addDevice(); vm.saveDevice("http://computer/", "token"); runCurrent()
        vm.selectDevice(null)
        vm.addDevice(); vm.saveDevice("http://computer/", "token"); runCurrent()
        assertFalse(vm.state.value.addingDevice)
        gate.complete(Unit); runCurrent()
        assertFalse(vm.state.value.saving)
        assertFalse(vm.state.value.addingDevice)
        assertNull(vm.state.value.deviceId)
        assertEquals(1, vm.state.value.devices.size)
        coVerify(exactly = 1) { client.connect() }
        coVerify(exactly = 1) { connections.save(any(), null) }
        coVerify(exactly = 0) { client.sessions(any()) }
        vm.setVisible(false)
    }

    @Test fun backgroundingDuringCheckKeepsSavedDeviceWithoutOpeningHistory() = runTest(dispatcher) {
        val gate = CompletableDeferred<String>()
        coEvery { client.connect() } coAnswers { gate.await() }
        val vm = RemoteViewModel(connections) { _, _ -> client }; runCurrent()
        vm.setVisible(true)
        vm.addDevice(); vm.saveDevice("http://computer/", "token"); runCurrent()
        assertEquals(RemoteDeviceStatus.CONNECTING, vm.state.value.devices.single().status)
        vm.refresh(); runCurrent()
        coVerify(exactly = 1) { client.connect() }
        vm.setVisible(false)
        gate.complete("Computer"); runCurrent()
        assertFalse(vm.state.value.addingDevice)
        assertNull(vm.state.value.deviceId)
        assertEquals(RemoteDeviceStatus.CONNECTED, vm.state.value.devices.single().status)
        coVerify(exactly = 0) { client.sessions(any()) }
        vm.setVisible(true); runCurrent()
        coVerify(exactly = 0) { client.sessions(any()) }
        vm.setVisible(false)
    }

    @Test fun lateConnectionFailureDoesNotReplaceAnEditorOrDiscardTheDevice() = runTest(dispatcher) {
        val gate = CompletableDeferred<String>()
        coEvery { client.connect() } coAnswers { gate.await() }
        val vm = RemoteViewModel(connections) { _, _ -> client }; runCurrent()
        vm.addDevice(); vm.saveDevice("http://computer/", "token"); runCurrent()
        vm.editDevice("http://computer/")
        gate.completeExceptionally(IOException("Offline")); runCurrent()
        assertTrue(vm.state.value.addingDevice)
        assertFalse(vm.state.value.saving)
        assertNull(vm.state.value.failure)
        assertEquals("token", vm.editorConnection()?.token)
        assertEquals(RemoteFailure.NETWORK, vm.state.value.devices.single().failure)
        coVerify(exactly = 1) { connections.save(any(), null) }
    }

    @Test fun editReplacesAddressOnlyAfterCommitAndRejectsLateOldCheck() = runTest(dispatcher) {
        val oldCheck = CompletableDeferred<String>()
        val saveGate = CompletableDeferred<Unit>()
        coEvery { client.connect() } coAnswers { withContext(NonCancellable) { oldCheck.await() } }
        val replacement = mockk<FiloClient>()
        every { replacement.address } returns "http://new/"
        coEvery { replacement.connect() } returns "New computer"
        val vm = RemoteViewModel(connections) { address, _ -> if (address == "http://new/") replacement else client }
        runCurrent()
        vm.saveDevice("http://computer/", "original"); runCurrent()
        vm.editDevice("http://computer/")
        assertEquals("original", vm.editorConnection()?.token)
        vm.selectDevice(null)
        vm.editDevice("http://computer/")
        coVerify(exactly = 1) { connections.save(any(), any()) }
        coEvery { connections.save(any(), "http://computer/") } throws RemoteStorageException()
        vm.saveDevice("http://new/", "changed"); runCurrent()
        assertTrue(vm.state.value.storageError)
        assertEquals("original", vm.editorConnection()?.token)
        coEvery { connections.save(any(), "http://computer/") } coAnswers { saveGate.await() }
        vm.saveDevice("http://new/", "changed"); runCurrent()
        assertEquals("http://computer/", vm.state.value.devices.single().id)
        saveGate.complete(Unit); runCurrent()
        oldCheck.complete("Stale computer"); runCurrent()
        assertEquals("http://new/", vm.state.value.devices.single().id)
        assertEquals("New computer", vm.state.value.devices.single().name)
        vm.editDevice("http://new/")
        assertEquals("changed", vm.editorConnection()?.token)
    }

    @Test fun removedDeviceCannotBeRevivedByLateConnectionCheck() = runTest(dispatcher) {
        val check = CompletableDeferred<String>()
        coEvery { client.connect() } coAnswers { withContext(NonCancellable) { check.await() } }
        val vm = RemoteViewModel(connections) { _, _ -> client }; runCurrent()
        vm.saveDevice("http://computer/", "token"); runCurrent()
        vm.removeDevice("http://computer/"); runCurrent()
        check.complete("Deleted computer"); runCurrent()
        assertTrue(vm.state.value.devices.isEmpty())
        assertNull(vm.state.value.deviceId)
    }

    @Test fun selectingWhileCheckingWaitsForProtocolValidationAndCoalescesRequests() = runTest(dispatcher) {
        val gate = CompletableDeferred<String>()
        coEvery { client.connect() } coAnswers { gate.await() }
        val vm = RemoteViewModel(connections) { _, _ -> client }; runCurrent()
        vm.setVisible(true)
        vm.saveDevice("http://computer/", "token"); runCurrent()
        vm.selectDevice("http://computer/"); vm.refresh(); runCurrent()
        assertTrue(vm.state.value.loading)
        coVerify(exactly = 1) { client.connect() }
        coVerify(exactly = 0) { client.sessions(any()) }
        gate.complete("Computer"); runCurrent()
        assertEquals(listOf(session), vm.state.value.sessions)
        coVerify(exactly = 1) { client.sessions(any()) }
        vm.setVisible(false)
    }

    @Test fun editingTokenAtSameAddressRejectsTheReplacedClientsLateResult() = runTest(dispatcher) {
        val gate = CompletableDeferred<String>()
        coEvery { client.connect() } coAnswers { withContext(NonCancellable) { gate.await() } }
        val replacement = mockk<FiloClient>()
        every { replacement.address } returns "http://computer/"
        coEvery { replacement.connect() } returns "Current computer"
        val vm = RemoteViewModel(connections) { _, token -> if (token == "changed") replacement else client }
        runCurrent()
        vm.saveDevice("http://computer/", "original"); runCurrent()
        vm.editDevice("http://computer/")
        vm.saveDevice("http://computer/", "changed"); runCurrent()
        gate.completeExceptionally(IOException("Old token rejected")); runCurrent()
        assertEquals(RemoteDeviceStatus.CONNECTED, vm.state.value.devices.single().status)
        assertEquals("Current computer", vm.state.value.devices.single().name)
        assertNull(vm.state.value.devices.single().failure)
    }

    @Test fun staleReadCannotReplaceNewSession() = runTest(dispatcher) {
        val gate = CompletableDeferred<RemoteConversationPage>()
        coEvery { client.conversation("session", null) } coAnswers { withContext(NonCancellable) { gate.await() } }
        val vm = RemoteViewModel(connections) { _, _ -> client }; runCurrent()
        saveAndSelect(vm)
        vm.setVisible(true); runCurrent()
        vm.selectSession(session); runCurrent()
        vm.selectSession(session.copy(id = "other")); runCurrent()
        gate.complete(RemoteConversationPage(listOf(RemoteMessage("stale", "t", null, "user", "old", 0)), null, emptyList()))
        runCurrent()
        assertEquals("other", vm.state.value.session?.id)
        assertNull(vm.animatedScrollRequest.value)
        assertTrue(vm.state.value.messages.isEmpty())
        vm.setVisible(false)
    }

    @Test fun sendAcceptanceIsBoundToOriginAndDoesNotClearEditedDraft() = runTest(dispatcher) {
        val gate = CompletableDeferred<String>()
        coEvery { client.send(any(), any(), any()) } coAnswers { gate.await() }
        val vm = RemoteViewModel(connections) { _, _ -> client }; runCurrent()
        saveAndSelect(vm)
        vm.selectSession(session); vm.setVisible(true); runCurrent()
        val owner = vm.state.value.owner!!
        vm.editDraft(owner, "first"); vm.send(); runCurrent()
        vm.editDraft(owner, "second"); vm.selectSession(session.copy(id = "other"))
        gate.complete("queued"); runCurrent()
        assertEquals("second", vm.state.value.drafts[owner])
        assertEquals(RemoteDelivery.ACCEPTED, vm.state.value.attempts[owner]?.delivery)
        assertEquals("other", vm.state.value.session?.id)
        coVerify(exactly = 1) { client.send("session", "first", any()) }
        val acceptedId = vm.state.value.attempts[owner]!!.clientId
        coEvery { client.conversation(any(), any()) } returns RemoteConversationPage(
            emptyList(), null, listOf(RemoteQueuedMessage("queue", acceptedId, "first")))
        vm.selectSession(session); vm.editDraft(owner, "first")
        vm.setVisible(true); runCurrent()
        assertEquals("first", vm.state.value.drafts[owner])
        assertEquals(RemoteDelivery.ACCEPTED, vm.state.value.attempts[owner]?.delivery)
        vm.setVisible(false)
    }

    @Test fun uncertainSendKeepsDraftAndCannotAutomaticallyRetry() = runTest(dispatcher) {
        coEvery { client.send(any(), any(), any()) } throws IOException("Lost response")
        val vm = RemoteViewModel(connections) { _, _ -> client }; runCurrent()
        saveAndSelect(vm)
        vm.selectSession(session); vm.setVisible(true); runCurrent()
        val owner = vm.state.value.owner!!
        vm.editDraft(owner, "hello"); vm.send(); runCurrent()
        vm.send(); runCurrent()
        assertEquals(RemoteDelivery.UNKNOWN, vm.state.value.attempts[owner]?.delivery)
        assertEquals("hello", vm.state.value.drafts[owner])
        assertNull(vm.animatedScrollRequest.value)
        coVerify(exactly = 1) { client.send(any(), any(), any()) }
        vm.acknowledgeUnknown(owner)
        assertNull(vm.state.value.attempts[owner])
        vm.setVisible(false)
    }

    @Test fun acceptedSendRequestsOneOwnedScrollAndNavigationClearsIt() = runTest(dispatcher) {
        val gate = CompletableDeferred<String>()
        coEvery { client.send(any(), any(), any()) } coAnswers { gate.await() }
        coEvery { client.conversation(any(), any()) } returns RemoteConversationPage(
            listOf(
                RemoteMessage("tail", "turn", null, "assistant", "Previous answer", 1),
                RemoteMessage("tool", "turn", null, "assistant", "", 1,
                    RemoteActivity("tool", toolName = "exec", state = "succeeded")),
            ), null, emptyList())
        val vm = RemoteViewModel(connections) { _, _ -> client }; runCurrent()
        saveAndSelect(vm)
        vm.selectSession(session); vm.setVisible(true); runCurrent()
        val owner = vm.state.value.owner!!
        vm.editDraft(owner, "hello"); vm.send(); runCurrent()
        assertEquals(RemoteDelivery.SUBMITTING, vm.state.value.attempts[owner]?.delivery)
        assertNull(vm.animatedScrollRequest.value)
        gate.complete("turn"); runCurrent()
        assertEquals(RemoteDelivery.ACCEPTED, vm.state.value.attempts[owner]?.delivery)
        assertEquals("hello", vm.state.value.drafts[owner])
        assertNull(vm.animatedScrollRequest.value)
        val clientId = vm.state.value.attempts[owner]!!.clientId
        coEvery { client.conversation(any(), any()) } returns RemoteConversationPage(
            listOf(RemoteMessage("sent", "new-turn", clientId, "user", "hello", 2)), null, emptyList())
        vm.refresh(); runCurrent()
        assertEquals(RemoteDelivery.DELIVERED, vm.state.value.attempts[owner]?.delivery)
        assertNull(vm.state.value.drafts[owner])
        val request = vm.animatedScrollRequest.value!!
        assertEquals(owner, request.conversationId)
        assertEquals("sent", request.targetMessageId)
        assertEquals(com.newoether.agora.viewmodel.AnimatedScrollDestination.ABSOLUTE_BOTTOM, request.destination)
        vm.completeAnimatedScroll(request.id + 1)
        assertEquals(request, vm.animatedScrollRequest.value)
        vm.completeAnimatedScroll(request.id)
        assertNull(vm.animatedScrollRequest.value)
        vm.refresh(); runCurrent()
        assertNull(vm.animatedScrollRequest.value)
        coEvery { client.send(any(), any(), any()) } returns "second-turn"
        vm.editDraft(owner, "next"); vm.send(); runCurrent()
        assertNull(vm.animatedScrollRequest.value)
        val nextId = vm.state.value.attempts[owner]!!.clientId
        coEvery { client.conversation(any(), any()) } returns RemoteConversationPage(
            listOf(RemoteMessage("next", "next-turn", nextId, "user", "next", 3)), null, emptyList())
        vm.refresh(); runCurrent()
        assertNotNull(vm.animatedScrollRequest.value)
        vm.selectSession(session.copy(id = "other"))
        assertNull(vm.animatedScrollRequest.value)
        vm.setVisible(false)
    }

    @Test fun malformedAcceptedResponseIsUnknownRatherThanRejected() = runTest(dispatcher) {
        coEvery { client.send(any(), any(), any()) } throws SerializationException("Invalid response")
        val vm = RemoteViewModel(connections) { _, _ -> client }; runCurrent()
        saveAndSelect(vm)
        vm.selectSession(session); vm.setVisible(true); runCurrent()
        val owner = vm.state.value.owner!!
        vm.editDraft(owner, "hello"); vm.send(); runCurrent()
        assertEquals(RemoteDelivery.UNKNOWN, vm.state.value.attempts[owner]?.delivery)
        assertEquals("hello", vm.state.value.drafts[owner])
        vm.setVisible(false)
    }

    @Test fun restoredDevicesSurviveReadFailureWithoutSendingOrOpeningHistory() = runTest(dispatcher) {
        coEvery { connections.load() } returns listOf(RemoteConnection("Computer", "http://computer/", "token"))
        val vm = RemoteViewModel(connections) { _, _ -> client }; runCurrent()
        assertEquals(1, vm.state.value.devices.size)
        assertNull(vm.state.value.deviceId)
        assertFalse(vm.state.value.restoring)
        coVerify(exactly = 0) { client.connect() }
        coVerify(exactly = 0) { client.sessions(any()) }
        coVerify(exactly = 0) { client.conversation(any(), any()) }
        coVerify(exactly = 0) { client.send(any(), any(), any()) }
        coEvery { client.sessions(any()) } throws IOException("Offline")
        vm.setVisible(true); vm.selectDevice("http://computer/"); runCurrent()
        assertTrue(vm.state.value.error)
        assertEquals(1, vm.state.value.devices.size)
        coVerify(exactly = 0) { connections.remove(any()) }
        vm.setVisible(false)
    }

    @Test fun failingPersistenceDoesNotPublishAnUnsavedConnection() = runTest(dispatcher) {
        coEvery { connections.save(any(), any()) } throws RemoteStorageException()
        val vm = RemoteViewModel(connections) { _, _ -> client }; runCurrent()
        vm.addDevice()
        vm.saveDevice("http://computer/", "token"); runCurrent()
        assertTrue(vm.state.value.storageError)
        assertTrue(vm.state.value.addingDevice)
        assertFalse(vm.state.value.saving)
        assertTrue(vm.state.value.devices.isEmpty())
        coVerify(exactly = 0) { client.connect() }
    }

    @Test fun removalPublishesOnlyAfterCommitAndFencesPendingSends() = runTest(dispatcher) {
        val removeGate = CompletableDeferred<Unit>()
        val sendGate = CompletableDeferred<String>()
        coEvery { connections.remove(any()) } coAnswers { removeGate.await() }
        coEvery { client.send(any(), any(), any()) } coAnswers { sendGate.await() }
        val vm = RemoteViewModel(connections) { _, _ -> client }; runCurrent()
        saveAndSelect(vm)
        vm.selectSession(session); vm.setVisible(true); runCurrent()
        vm.editDraft(vm.state.value.owner!!, "Once"); vm.send(); runCurrent()
        vm.removeDevice("http://computer/"); runCurrent()
        assertEquals(1, vm.state.value.devices.size)
        removeGate.complete(Unit); runCurrent()
        sendGate.complete("queue"); runCurrent()
        assertTrue(vm.state.value.devices.isEmpty())
        assertTrue(vm.state.value.attempts.isEmpty())
        assertTrue(vm.state.value.drafts.isEmpty())
        assertNull(vm.state.value.deviceId)
        coVerify(exactly = 1) { client.send(any(), any(), any()) }
        vm.setVisible(false)
    }

    @Test fun nativeEventBeforeLostReceiptConfirmsOnceAndDisconnectInvalidatesRuntime() = runTest(dispatcher) {
        val events = MutableSharedFlow<RemoteConversationPage>()
        every { client.events(any()) } returns events
        val response = CompletableDeferred<String>()
        coEvery { client.send(any(), any(), any()) } coAnswers { response.await() }
        val vm = RemoteViewModel(connections) { _, _ -> client }; runCurrent()
        saveAndSelect(vm); vm.selectSession(session); vm.setVisible(true); runCurrent()
        val running = RemoteRuntime("active", "turn", "model", 1234, 256000)
        events.emit(RemoteConversationPage(emptyList(), null, emptyList(), running)); runCurrent()
        val owner = vm.state.value.owner!!
        vm.editDraft(owner, "hello"); vm.send(); runCurrent()
        val id = vm.state.value.attempts[owner]!!.clientId
        val message = RemoteMessage("sent", "turn", id, "user", "hello", 1)
        events.emit(RemoteConversationPage(listOf(message), null, emptyList(), running)); runCurrent()
        val scroll = vm.animatedScrollRequest.value
        assertEquals(RemoteDelivery.DELIVERED, vm.state.value.attempts[owner]?.delivery)
        response.completeExceptionally(IOException("Lost receipt")); runCurrent()
        assertEquals(RemoteDelivery.DELIVERED, vm.state.value.attempts[owner]?.delivery)
        assertEquals(scroll, vm.animatedScrollRequest.value)
        assertEquals(1234, vm.state.value.runtime?.contextTokens)
        vm.setVisible(false)
        assertNull(vm.state.value.runtime)
        vm.editDraft(owner, "offline"); vm.send(); runCurrent()
        coVerify(exactly = 1) { client.send(any(), any(), any()) }
    }

    @Test fun newSessionUsesHostCreationAndLateResultDoesNotNavigateAfterBack() = runTest(dispatcher) {
        val created = CompletableDeferred<RemoteSession>()
        coEvery { client.create() } coAnswers { created.await() }
        val vm = RemoteViewModel(connections) { _, _ -> client }; runCurrent()
        saveAndSelect(vm); vm.setVisible(true); runCurrent()
        vm.newSession(); vm.newSession(); runCurrent()
        assertTrue(vm.state.value.controlling)
        vm.selectDevice(null)
        created.complete(RemoteSession("new", "New", "/host/default", 1)); runCurrent()
        assertNull(vm.state.value.session)
        assertFalse(vm.state.value.controlling)
        coVerify(exactly = 1) { client.create() }
        vm.setVisible(false)
    }

    @Test fun failedRemovalKeepsTheSavedDeviceAndRuntimeClient() = runTest(dispatcher) {
        coEvery { connections.remove(any()) } throws RemoteStorageException()
        val vm = RemoteViewModel(connections) { _, _ -> client }; runCurrent()
        saveAndSelect(vm)
        vm.removeDevice("http://computer/"); runCurrent()
        assertEquals(1, vm.state.value.devices.size)
        assertTrue(vm.state.value.storageError)
        assertFalse(vm.state.value.saving)
        vm.setVisible(true); vm.selectDevice("http://computer/"); runCurrent()
        assertEquals(listOf(session), vm.state.value.sessions)
        vm.setVisible(false)
    }

    @Test fun diagnosticsCaptureCategoriesWithoutCredentialsOrExceptionContent() = runTest(dispatcher) {
        val events = mutableListOf<String>()
        mockkObject(DeveloperDiagnostics)
        every { DeveloperDiagnostics.recordHttpStage(any(), any(), any(), any()) } answers {
            events += "${firstArg<Any>()} ${secondArg<String>()} ${arg<String>(3)}"
        }
        try {
            coEvery { connections.load() } returns listOf(RemoteConnection("Computer", "http://computer/", "PRIVATE_TOKEN"))
            coEvery { client.sessions(any()) } throws IOException("PRIVATE_PAYLOAD http://private-host/")
            val vm = RemoteViewModel(connections) { _, _ -> client }; runCurrent()
            vm.setVisible(true); vm.selectDevice("http://computer/"); runCurrent()
            assertEquals(RemoteFailure.NETWORK, vm.state.value.failure)
            coEvery { client.sessions(any()) } throws FiloHttpException(401)
            vm.refresh(); runCurrent()
            assertEquals(RemoteFailure.AUTHENTICATION, vm.state.value.failure)
            coEvery { client.sessions(any()) } returns RemoteSessionPage(listOf(session), null)
            vm.refresh(); runCurrent()
            assertNull(vm.state.value.failure)
            vm.setVisible(false)
            assertTrue(events.any { it.contains("restore_completed") })
            assertTrue(events.any { it.contains("read_failed.NETWORK.IOException") })
            assertTrue(events.any { it.contains("AUTHENTICATION") && it.contains("code=401") })
            assertFalse(events.any { it.contains("PRIVATE_") || it.contains("http://") })
        } finally { unmockkObject(DeveloperDiagnostics) }
    }
    @Test fun explicitHistoryResumeKeepsHistoryAndEnablesExistingStreamOnlyAfterAcceptance() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        coEvery { client.resume("history") } coAnswers { gate.await() }
        val old = RemoteMessage("old", "old-turn", null, "user", "Preserved", 1)
        coEvery { client.conversation("history", any()) } returns
            RemoteConversationPage(listOf(old), null, emptyList(), RemoteRuntime("ready"))
        val vm = RemoteViewModel(connections) { _, _ -> client }; runCurrent()
        vm.setVisible(true); saveAndSelect(vm)
        vm.selectSession(session.copy(id = "history", readOnly = true, canResume = true)); runCurrent()
        vm.resumeSession(); vm.resumeSession(); runCurrent()
        assertTrue(vm.state.value.controlling)
        assertTrue(vm.state.value.session!!.readOnly)
        verify(exactly = 0) { client.events("history") }
        gate.complete(Unit); runCurrent()
        assertFalse(vm.state.value.controlling)
        assertFalse(vm.state.value.session!!.readOnly)
        assertEquals(listOf(old), vm.state.value.messages)
        verify(exactly = 1) { client.events("history") }
        coVerify(exactly = 1) { client.resume("history") }
        coVerify(exactly = 0) { client.send(any(), any(), any()) }
        vm.setVisible(false)
    }

    @Test fun busyHistoryStaysReadableAndLateResumeCannotNavigateAfterBack() = runTest(dispatcher) {
        coEvery { client.resume("history") } throws FiloHttpException(409, "session_busy")
        val vm = RemoteViewModel(connections) { _, _ -> client }; runCurrent()
        vm.setVisible(true); saveAndSelect(vm)
        vm.selectSession(session.copy(id = "history", readOnly = true, canResume = true)); runCurrent()
        vm.resumeSession(); runCurrent()
        assertEquals(RemoteFailure.SESSION_BUSY, vm.state.value.failure)
        assertTrue(vm.state.value.session!!.readOnly)
        assertFalse(vm.state.value.controlling)
        verify(exactly = 0) { client.events("history") }
        val gate = CompletableDeferred<Unit>()
        coEvery { client.resume("history") } coAnswers { gate.await() }
        vm.resumeSession(); runCurrent(); vm.selectSession(null); runCurrent()
        gate.complete(Unit); runCurrent()
        assertNull(vm.state.value.session)
        assertFalse(vm.state.value.controlling)
        vm.setVisible(false)
    }

    @Test fun historyPaginationProgressEndsOnFailureAndDoesNotSurviveNavigation() = runTest(dispatcher) {
        val page = CompletableDeferred<RemoteConversationPage>()
        coEvery { client.conversation("history", null) } returns
            RemoteConversationPage(emptyList(), "older", emptyList(), RemoteRuntime("readOnly"))
        coEvery { client.conversation("history", "older") } coAnswers { page.await() }
        val vm = RemoteViewModel(connections) { _, _ -> client }; runCurrent()
        vm.setVisible(true); saveAndSelect(vm)
        vm.selectSession(session.copy(id = "history", readOnly = true)); runCurrent()
        vm.loadMore(); runCurrent()
        assertTrue(vm.state.value.loadingMore)
        page.completeExceptionally(IOException("offline")); runCurrent()
        assertFalse(vm.state.value.loadingMore)
        assertEquals(RemoteFailure.NETWORK, vm.state.value.failure)
        vm.selectSession(null); runCurrent()
        assertFalse(vm.state.value.loadingMore)
        vm.setVisible(false)
    }

    @Test fun ordinaryHistoryLoadsAndPagesWithoutSubscriptionOrControl() = runTest(dispatcher) {
        val historical = session.copy(id = "historical", readOnly = true)
        val recent = RemoteMessage("recent", "turn", null, "assistant", "Recent history", 2)
        val older = RemoteMessage("older", "old-turn", null, "user", "Earlier history", 1)
        coEvery { client.conversation("historical", null) } returns
            RemoteConversationPage(listOf(recent), "older", emptyList(), RemoteRuntime("active", "turn", "model"))
        coEvery { client.conversation("historical", "older") } returns
            RemoteConversationPage(listOf(older), null, emptyList(), RemoteRuntime("readOnly"))
        val vm = RemoteViewModel(connections) { _, _ -> client }; runCurrent()
        vm.setVisible(true); saveAndSelect(vm)
        vm.selectSession(historical); runCurrent()
        assertEquals(listOf(recent), vm.state.value.messages)
        assertFalse(vm.state.value.loading)
        vm.loadMore(); runCurrent()
        assertEquals(listOf(older, recent), vm.state.value.messages)
        assertNull(vm.state.value.historyCursor)
        vm.editDraft(vm.state.value.owner!!, "must not send")
        vm.send(); vm.stop(); vm.setModel("model"); runCurrent()
        verify(exactly = 0) { client.events("historical") }
        coVerify(exactly = 0) { client.send(any(), any(), any()) }
        coVerify(exactly = 0) { client.stop(any(), any()) }
        coVerify(exactly = 0) { client.setModel(any(), any()) }
        vm.setVisible(false)
    }

}
