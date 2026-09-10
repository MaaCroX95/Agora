package com.newoether.agora.remote

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteSessionStatusTest {
    private val dispatcher = StandardTestDispatcher()
    private val client = mockk<FiloClient>()
    private val store = mockk<RemoteConnectionStore>(relaxed = true)
    private val address = "http://computer/"
    private val session = RemoteSession("session", "Existing", "/workspace", 1)
    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        coEvery { store.load() } returns listOf(RemoteConnection("Computer", address, "token"))
        every { client.address } returns address
        coEvery { client.connect() } returns "Computer"
        coEvery { client.sessions(any()) } returns RemoteSessionPage(listOf(session), null)
        coEvery { client.models() } returns emptyList()
        coEvery { client.sessionStatuses(any()) } returns listOf(RemoteSessionStatus("session", "idle"))
        every { client.events(any()) } returns flow { awaitCancellation() }
    }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test fun onlyVisibleSessionsArePolledAndHiddenPageCancelsReads() = runTest(dispatcher) {
        val vm = RemoteViewModel(store) { _, _ -> client }; runCurrent()
        vm.setVisible(true); vm.selectDevice(address); runCurrent()
        coVerify(exactly = 0) { client.sessionStatuses(any()) }
        vm.observeSessions(address, listOf("session", "session", "unknown")); runCurrent()
        coVerify(exactly = 1) { client.sessionStatuses(listOf("session")) }
        vm.setVisible(false); advanceTimeBy(9000); runCurrent()
        coVerify(exactly = 1) { client.sessionStatuses(any()) }
    }

    @Test fun listStatusSeedsRowsBeforeVisibleStatusPolling() = runTest(dispatcher) {
        coEvery { client.sessions(any()) } returns RemoteSessionPage(listOf(session.copy(status = "active")), null)
        val vm = RemoteViewModel(store) { _, _ -> client }; runCurrent()
        vm.setVisible(true); vm.selectDevice(address); runCurrent()
        assertEquals("active", vm.state.value.sessionStatuses["$address/session"]?.status)
        coVerify(exactly = 0) { client.sessionStatuses(any()) }
        vm.setVisible(false)
    }

    @Test fun listStatusPreservesExactIdentityUntilVisibleRefreshCompletes() = runTest(dispatcher) {
        var page = RemoteSessionPage(listOf(session.copy(status = "active")), null)
        coEvery { client.sessions(any()) } answers { page }
        coEvery { client.sessionStatuses(any()) } returns
            listOf(RemoteSessionStatus("session", "active", activeTurnId = "turn"))
        val vm = RemoteViewModel(store) { _, _ -> client }; runCurrent()
        vm.setVisible(true); vm.selectDevice(address); runCurrent()
        vm.observeSessions(address, listOf("session")); runCurrent()
        assertEquals("turn", vm.state.value.sessionStatuses["$address/session"]?.activeTurnId)

        page = RemoteSessionPage(listOf(session.copy(status = "idle")), null)
        val exact = CompletableDeferred<List<RemoteSessionStatus>>()
        coEvery { client.sessionStatuses(any()) } coAnswers { exact.await() }
        vm.refresh(); runCurrent()
        val listed = vm.state.value.sessionStatuses["$address/session"]
        assertEquals("idle", listed?.status)
        assertEquals("turn", listed?.activeTurnId)
        assertFalse(requireNotNull(listed).hasUnreadTurn)

        exact.complete(listOf(RemoteSessionStatus("session", "idle", completedTurnId = "turn"))); runCurrent()
        assertTrue(vm.state.value.hasUnreadGeneration("session"))
        vm.setVisible(false)
    }

    @Test fun pagedListStatusSeedsNewRows() = runTest(dispatcher) {
        coEvery { client.sessions(null) } returns RemoteSessionPage(listOf(session.copy(status = "idle")), "next")
        coEvery { client.sessions("next") } returns RemoteSessionPage(
            listOf(session.copy(id = "other", status = "active")), null)
        val vm = RemoteViewModel(store) { _, _ -> client }; runCurrent()
        vm.setVisible(true); vm.selectDevice(address); runCurrent()
        vm.loadMore(); runCurrent()
        assertEquals("idle", vm.state.value.sessionStatuses["$address/session"]?.status)
        assertEquals("active", vm.state.value.sessionStatuses["$address/other"]?.status)
        coVerify(exactly = 0) { client.sessionStatuses(any()) }
        vm.setVisible(false)
    }

    @Test fun CompletedUnviewedGenerationRemainsUnreadUntilHistoryIsActuallyRead() = runTest(dispatcher) {
        var status = RemoteSessionStatus("session", "active", activeTurnId = "turn")
        coEvery { client.sessionStatuses(any()) } answers { listOf(status) }
        val vm = RemoteViewModel(store) { _, _ -> client }; runCurrent()
        vm.setVisible(true); vm.selectDevice(address); runCurrent()
        vm.observeSessions(address, listOf("session")); runCurrent()
        assertEquals("active", vm.state.value.sessionStatuses["$address/session"]?.status)
        assertFalse(vm.state.value.hasUnreadGeneration("session"))
        status = RemoteSessionStatus("session", "idle", completedTurnId = "turn")
        advanceTimeBy(3000); runCurrent()
        assertTrue(vm.state.value.hasUnreadGeneration("session"))
        advanceTimeBy(3000); runCurrent()
        assertTrue(vm.state.value.hasUnreadGeneration("session"))
        vm.selectSession(session); runCurrent()
        coVerify(exactly = 0) { store.markViewed(any(), any(), any()) }
        assertTrue(vm.state.value.hasUnreadGeneration("session"))
        every { client.events(any()) } returns flow {
            emit(bodyPage(emptyList(), null, emptyList(), RemoteRuntime("idle", completedTurnId = "turn")))
            awaitCancellation()
        }
        vm.refresh(); runCurrent()
        assertFalse(vm.state.value.hasUnreadGeneration("session"))
        assertEquals("idle", vm.state.value.sessionStatuses["$address/session"]?.status)
        coVerify(exactly = 1) { store.markViewed(address, "session", "turn") }
        vm.refresh(); runCurrent()
        coVerify(exactly = 1) { store.markViewed(address, "session", "turn") }
        vm.selectSession(null)
        assertEquals("idle", vm.state.value.sessionStatuses["$address/session"]?.status)
        vm.setVisible(false)
    }

    @Test fun restoredViewedTurnSuppressesOnlyThatGeneration() = runTest(dispatcher) {
        coEvery { store.load() } returns listOf(RemoteConnection("Computer", address, "token", mapOf("session" to "old")))
        var status = RemoteSessionStatus("session", "idle", completedTurnId = "old", hasUnreadTurn = true)
        coEvery { client.sessionStatuses(any()) } answers { listOf(status) }
        val vm = RemoteViewModel(store) { _, _ -> client }; runCurrent()
        vm.setVisible(true); vm.selectDevice(address); runCurrent()
        vm.observeSessions(address, listOf("session")); runCurrent()
        assertFalse(vm.state.value.hasUnreadGeneration("session"))
        status = status.copy(completedTurnId = "new")
        advanceTimeBy(3000); runCurrent()
        assertTrue(vm.state.value.hasUnreadGeneration("session"))
        vm.setVisible(false)
    }

    @Test fun lateStatusCannotRepopulateAnotherSelection() = runTest(dispatcher) {
        val gate = CompletableDeferred<List<RemoteSessionStatus>>()
        coEvery { client.sessionStatuses(any()) } coAnswers { withContext(NonCancellable) { gate.await() } }
        val vm = RemoteViewModel(store) { _, _ -> client }; runCurrent()
        vm.setVisible(true); vm.selectDevice(address); runCurrent()
        vm.observeSessions(address, listOf("session")); runCurrent()
        vm.selectDevice(null)
        gate.complete(listOf(RemoteSessionStatus("session", "active"))); runCurrent()
        assertNull(vm.state.value.deviceId)
        assertTrue(vm.state.value.sessionStatuses.isEmpty())
        vm.setVisible(false)
    }

    @Test fun paginationRejectsRepeatedCursorAndDoesNotDuplicatePendingRequests() = runTest(dispatcher) {
        coEvery { client.sessions(null) } returns RemoteSessionPage(listOf(session), "next")
        val gate = CompletableDeferred<RemoteSessionPage>()
        coEvery { client.sessions("next") } coAnswers { gate.await() }
        val vm = RemoteViewModel(store) { _, _ -> client }; runCurrent()
        vm.setVisible(true); vm.selectDevice(address); runCurrent()
        vm.loadMore(); vm.loadMore(); runCurrent()
        coVerify(exactly = 1) { client.sessions("next") }
        gate.complete(RemoteSessionPage(listOf(session.copy(id = "other")), "next")); runCurrent()
        assertTrue(vm.state.value.error)
        assertEquals(listOf(session), vm.state.value.sessions)
        assertFalse(vm.state.value.loadingMore)
        vm.setVisible(false)
    }

    @Test fun paginationAppendsDistinctSessionsAndStopsAtLastPage() = runTest(dispatcher) {
        coEvery { client.sessions(null) } returns RemoteSessionPage(listOf(session), "next")
        coEvery { client.sessions("next") } returns RemoteSessionPage(listOf(session, session.copy(id = "other")), null)
        val vm = RemoteViewModel(store) { _, _ -> client }; runCurrent()
        vm.setVisible(true); vm.selectDevice(address); runCurrent()
        vm.loadMore(); runCurrent()
        assertEquals(listOf("session", "other"), vm.state.value.sessions.map { it.id })
        assertNull(vm.state.value.sessionCursor)
        vm.loadMore(); runCurrent()
        coVerify(exactly = 1) { client.sessions("next") }
        vm.setVisible(false)
    }


    @Test fun cachedStatusesSurviveNavigationAndStayIsolatedBetweenDevices() = runTest(dispatcher) {
        val otherAddress = "http://other/"
        val other = mockk<FiloClient>()
        coEvery { store.load() } returns listOf(
            RemoteConnection("Computer", address, "token"), RemoteConnection("Other", otherAddress, "token"))
        every { other.address } returns otherAddress
        coEvery { other.connect() } returns "Other"
        coEvery { other.sessions(any()) } returns RemoteSessionPage(listOf(session), null)
        coEvery { other.models() } returns emptyList()
        coEvery { other.sessionStatuses(any()) } returns listOf(RemoteSessionStatus("session", "idle"))
        coEvery { client.sessionStatuses(any()) } returns listOf(RemoteSessionStatus("session", "active", activeTurnId = "turn"))
        val vm = RemoteViewModel(store) { url, _ -> if (url == otherAddress) other else client }; runCurrent()
        vm.setVisible(true); vm.selectDevice(address); runCurrent()
        vm.observeSessions(address, listOf("session")); runCurrent()
        vm.selectDevice(otherAddress); runCurrent()
        vm.observeSessions(otherAddress, listOf("session")); runCurrent()
        assertEquals("active", vm.state.value.sessionStatuses["$address/session"]?.status)
        assertEquals("idle", vm.state.value.sessionStatuses["$otherAddress/session"]?.status)
        val waiting = CompletableDeferred<List<RemoteSessionStatus>>()
        coEvery { client.sessionStatuses(any()) } coAnswers { waiting.await() }
        vm.selectDevice(address); runCurrent()
        vm.observeSessions(address, listOf("session")); runCurrent()
        assertEquals("active", vm.state.value.sessionStatuses["$address/session"]?.status)
        waiting.complete(listOf(RemoteSessionStatus("session", null))); runCurrent()
        assertEquals("active", vm.state.value.sessionStatuses["$address/session"]?.status)
        vm.removeDevice(address); runCurrent()
        assertNull(vm.state.value.sessionStatuses["$address/session"])
        assertEquals("idle", vm.state.value.sessionStatuses["$otherAddress/session"]?.status)
        vm.setVisible(false)
    }

    @Test fun failedStatusReadRetainsCachedActivityWithoutBreakingHistoryList() = runTest(dispatcher) {
        coEvery { client.sessionStatuses(any()) } returns listOf(RemoteSessionStatus("session", "active"))
        val vm = RemoteViewModel(store) { _, _ -> client }; runCurrent()
        vm.setVisible(true); vm.selectDevice(address); runCurrent()
        vm.observeSessions(address, listOf("session")); runCurrent()
        coEvery { client.sessionStatuses(any()) } throws IOException("offline")
        advanceTimeBy(3000); runCurrent()
        assertEquals("active", vm.state.value.sessionStatuses["$address/session"]?.status)
        assertFalse(vm.state.value.error)
        assertEquals(listOf(session), vm.state.value.sessions)
        vm.setVisible(false)
    }
}
