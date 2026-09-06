package com.newoether.agora.remote

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.withContext
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
    }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test fun staleReadCannotReplaceNewSession() = runTest(dispatcher) {
        val gate = CompletableDeferred<RemoteConversationPage>()
        coEvery { client.conversation("session", null) } coAnswers { withContext(NonCancellable) { gate.await() } }
        val vm = RemoteViewModel(connections) { _, _ -> client }; runCurrent()
        vm.connect("http://computer/", "token"); runCurrent()
        vm.setVisible(true); runCurrent()
        vm.selectSession(session); runCurrent()
        vm.selectSession(session.copy(id = "other")); runCurrent()
        gate.complete(RemoteConversationPage(listOf(RemoteMessage("stale", "t", null, "user", "old", 0)), null, emptyList()))
        runCurrent()
        assertEquals("other", vm.state.value.session?.id)
        assertTrue(vm.state.value.messages.isEmpty())
        vm.setVisible(false)
    }

    @Test fun sendAcceptanceIsBoundToOriginAndDoesNotClearEditedDraft() = runTest(dispatcher) {
        val gate = CompletableDeferred<String>()
        coEvery { client.send(any(), any(), any()) } coAnswers { gate.await() }
        val vm = RemoteViewModel(connections) { _, _ -> client }; runCurrent()
        vm.connect("http://computer/", "token"); runCurrent()
        vm.selectSession(session)
        val owner = vm.state.value.owner!!
        vm.editDraft(owner, "first"); vm.send(); runCurrent()
        vm.editDraft(owner, "second"); vm.selectSession(session.copy(id = "other"))
        gate.complete("queued"); runCurrent()
        assertEquals("second", vm.state.value.drafts[owner])
        assertEquals(RemoteDelivery.QUEUED, vm.state.value.attempts[owner]?.delivery)
        assertEquals("other", vm.state.value.session?.id)
        coVerify(exactly = 1) { client.send("session", "first", any()) }
        val acceptedId = vm.state.value.attempts[owner]!!.clientId
        coEvery { client.conversation(any(), any()) } returns RemoteConversationPage(
            emptyList(), null, listOf(RemoteQueuedMessage("queue", acceptedId, "first")))
        vm.selectSession(session); vm.editDraft(owner, "first")
        vm.setVisible(true); runCurrent()
        assertEquals("first", vm.state.value.drafts[owner])
        vm.setVisible(false)
    }

    @Test fun uncertainSendKeepsDraftAndCannotAutomaticallyRetry() = runTest(dispatcher) {
        coEvery { client.send(any(), any(), any()) } throws IOException("Lost response")
        val vm = RemoteViewModel(connections) { _, _ -> client }; runCurrent()
        vm.connect("http://computer/", "token"); runCurrent()
        vm.selectSession(session)
        val owner = vm.state.value.owner!!
        vm.editDraft(owner, "hello"); vm.send(); runCurrent()
        vm.send(); runCurrent()
        assertEquals(RemoteDelivery.UNKNOWN, vm.state.value.attempts[owner]?.delivery)
        assertEquals("hello", vm.state.value.drafts[owner])
        coVerify(exactly = 1) { client.send(any(), any(), any()) }
        vm.acknowledgeUnknown(owner)
        assertNull(vm.state.value.attempts[owner])
    }

    @Test fun malformedAcceptedResponseIsUnknownRatherThanRejected() = runTest(dispatcher) {
        coEvery { client.send(any(), any(), any()) } throws SerializationException("Invalid response")
        val vm = RemoteViewModel(connections) { _, _ -> client }; runCurrent()
        vm.connect("http://computer/", "token"); runCurrent()
        vm.selectSession(session)
        val owner = vm.state.value.owner!!
        vm.editDraft(owner, "hello"); vm.send(); runCurrent()
        assertEquals(RemoteDelivery.UNKNOWN, vm.state.value.attempts[owner]?.delivery)
        assertEquals("hello", vm.state.value.drafts[owner])
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
        coEvery { connections.save(any()) } throws RemoteStorageException()
        val vm = RemoteViewModel(connections) { _, _ -> client }; runCurrent()
        vm.connect("http://computer/", "token"); runCurrent()
        assertTrue(vm.state.value.storageError)
        assertFalse(vm.state.value.connecting)
        assertTrue(vm.state.value.devices.isEmpty())
    }

    @Test fun removalPublishesOnlyAfterCommitAndFencesPendingSends() = runTest(dispatcher) {
        val removeGate = CompletableDeferred<Unit>()
        val sendGate = CompletableDeferred<String>()
        coEvery { connections.remove(any()) } coAnswers { removeGate.await() }
        coEvery { client.send(any(), any(), any()) } coAnswers { sendGate.await() }
        val vm = RemoteViewModel(connections) { _, _ -> client }; runCurrent()
        vm.connect("http://computer/", "token"); runCurrent()
        vm.selectSession(session)
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
    }
}
