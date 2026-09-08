package com.newoether.agora.remote

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteMessageHydrationTest {
    private val native = RemoteMessage("answer", "turn", null, "assistant", "body", 1, groupId = "group")
    private val node = RemoteMessageNode("answer", "turn", null, "assistant", 1, "a".repeat(64), 4, groupId = "group")
    private fun snapshot() = RemoteState(deviceId = "device", session = RemoteSession("session", "Task", "", 1),
        hydrationEnabled = true, messageGroups = projectRemoteTopology(listOf(node), RemoteRuntime("idle")))

    @Test fun creatingOrUpdatingTopologyDoesNotReadBodiesAndHydrationKeepsAllStubPositions() = runTest {
        val state = MutableStateFlow(snapshot())
        var reads = 0
        val hydration = RemoteMessageHydration(state, { _, _ -> reads++; listOf(native) }, { throw it })
        val before = state.value.messageGroups.map { it.stub }
        assertEquals(0, reads)
        val loaded = hydration.observeMessage(state.value.owner!!, "group").filterNotNull().first()
        assertEquals("body", loaded.text)
        assertEquals(1, reads)
        assertEquals(before, state.value.messageGroups.map { it.stub })
        assertEquals(loaded, hydration.loadMessages(state.value.owner!!, listOf("group")).single())
        assertEquals(1, reads)
    }

    @Test fun changingSelectionDuringPayloadReadCannotPublishOrCacheTheOldBody() = runTest {
        val state = MutableStateFlow(snapshot())
        val gate = CompletableDeferred<List<RemoteMessage>>()
        val hydration = RemoteMessageHydration(state, { _, _ -> gate.await() }, { throw it })
        val read = async { hydration.loadMessages(state.value.owner!!, listOf("group")) }
        testScheduler.runCurrent()
        state.value = state.value.copy(session = null, messageGroups = emptyList())
        gate.complete(listOf(native))
        try { read.await(); fail("Old selection must be cancelled") }
        catch (_: kotlinx.coroutines.CancellationException) {}
        assertTrue(state.value.messageGroups.isEmpty())
    }

    @Test fun stalePayloadRevisionIsRehydratedWhileUnchangedVisibleRowsUseOriginalCache() = runTest {
        val state = MutableStateFlow(snapshot())
        var reads = 0
        val hydration = RemoteMessageHydration(state, { _, _ -> reads++; listOf(native.copy(text = "body $reads")) }, { throw it })
        val owner = state.value.owner!!
        assertEquals("body 1", hydration.loadMessages(owner, listOf("group")).single().text)
        assertEquals("body 1", hydration.loadMessages(owner, listOf("group")).single().text)
        state.value = state.value.copy(messageGroups = projectRemoteTopology(
            listOf(node.copy(revision = "b".repeat(64))), RemoteRuntime("idle")))
        assertEquals("body 2", hydration.loadMessages(owner, listOf("group")).single().text)
        assertEquals(2, reads)
    }
}
