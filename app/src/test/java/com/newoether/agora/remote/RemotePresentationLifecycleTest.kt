package com.newoether.agora.remote

import com.newoether.agora.model.MessageStatus
import com.newoether.agora.ui.chat.message.GroupedSegmentAutoExpansionAction
import com.newoether.agora.ui.chat.message.GroupedSegmentAutoExpansionController
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RemotePresentationLifecycleTest {
    @Test fun suspendedReadsDoNotCompleteAnAutoExpandedCardAndNativeCompletionStillCollapsesIt() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val client = mockk<FiloClient>()
            val store = mockk<RemoteConnectionStore>(relaxed = true)
            val events = MutableSharedFlow<RemoteConversationPage>()
            val session = RemoteSession("session", "Session", "/workspace", 1)
            coEvery { store.load() } returns emptyList()
            every { client.address } returns "http://computer/"
            coEvery { client.connect() } returns "Computer"
            coEvery { client.sessions(any()) } returns RemoteSessionPage(listOf(session), null)
            coEvery { client.models() } returns emptyList()
            every { client.events(any()) } returns events
            val vm = RemoteViewModel(store, projectionDispatcher = dispatcher) { _, _ -> client }
            runCurrent(); vm.setVisible(true)
            vm.saveDevice("http://computer/", "token"); runCurrent()
            vm.selectDevice("http://computer/"); runCurrent()
            vm.selectSession(session); runCurrent()
            val thought = RemoteMessage("thought", "turn", null, "assistant", "Working", 1,
                activity = RemoteActivity("thought"))
            val page = bodyPage(listOf(thought), null, emptyList(),
                RemoteRuntime("active", "turn", activeTurnHasUserMessage = true))
            events.emit(page); runCurrent()
            val controller = GroupedSegmentAutoExpansionController()
            val key = vm.state.value.messageGroups.single().stub.id
            fun active() = vm.state.value.messageGroups.single().stub.status == MessageStatus.THINKING
            assertEquals(GroupedSegmentAutoExpansionAction.EXPAND, controller.update(key, active(), true))
            vm.setVisible(false); runCurrent()
            assertNull(vm.state.value.runtime)
            assertTrue("Hiding is not native completion", active())
            assertEquals(GroupedSegmentAutoExpansionAction.NONE, controller.update(key, active(), true))
            vm.setVisible(true); runCurrent()
            assertTrue("Waiting for a new snapshot is not native completion", active())
            events.emit(page); runCurrent()
            assertEquals(GroupedSegmentAutoExpansionAction.NONE, controller.update(key, active(), true))
            events.emit(page.copy(runtime = RemoteRuntime("idle"))); runCurrent()
            assertFalse(active())
            assertEquals(GroupedSegmentAutoExpansionAction.COLLAPSE, controller.update(key, active(), true))
            vm.setVisible(false)
        } finally { Dispatchers.resetMain() }
    }
}
