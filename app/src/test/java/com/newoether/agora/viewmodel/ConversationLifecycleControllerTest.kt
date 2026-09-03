package com.newoether.agora.viewmodel

import com.newoether.agora.data.repository.ConversationRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ConversationLifecycleControllerTest {
    @Test
    fun renamePersistsTheExactTitle() = runTest {
        val fixture = Fixture(this)
        coEvery { fixture.conversations.updateConversationTitle(any(), any()) } returns true

        fixture.controller.rename("conversation", "New title")
        runCurrent()

        coVerify(exactly = 1) {
            fixture.conversations.updateConversationTitle("conversation", "New title")
        }
    }

    @Test
    fun visibleDeletionPreservesStopLockCleanupAndSelectionSettlementOrder() = runTest {
        val fixture = Fixture(this)
        coEvery { fixture.conversations.deleteConversation("conversation") } answers {
            fixture.events += "delete"
        }

        fixture.controller.delete("conversation")
        assertEquals(listOf("stop"), fixture.events)
        runCurrent()

        assertEquals(
            listOf(
                "stop",
                "stop-loop",
                "lock-start",
                "delete",
                "lock-end",
                "remove",
                "settle:conversation",
            ),
            fixture.events,
        )
    }

    @Test
    fun deletionDoesNotStopOrReplaceAnotherVisibleConversation() = runTest {
        val fixture = Fixture(this, currentConversationId = "other")
        coEvery { fixture.conversations.deleteConversation("conversation") } answers {
            fixture.events += "delete"
        }

        fixture.controller.delete("conversation")
        runCurrent()

        assertEquals(listOf("stop-loop", "lock-start", "delete", "lock-end", "remove"), fixture.events)
        assertTrue("stop" !in fixture.events)
        assertTrue(fixture.events.none { it.startsWith("settle:") })
    }

    @Test
    fun lifecycleSettlesTheOriginallySelectedDeletionAfterCleanup() = runTest {
        val fixture = Fixture(this)
        coEvery { fixture.conversations.deleteConversation("conversation") } answers {
            fixture.events += "delete"
        }
        fixture.onRemove = { fixture.currentConversationId.value = "other" }

        fixture.controller.delete("conversation")
        runCurrent()

        assertTrue("stop" in fixture.events)
        assertEquals("settle:conversation", fixture.events.last())
    }

    @Test
    fun frozenSubmissionRejectsDeletionBeforeAnySideEffect() = runTest {
        val fixture = Fixture(this, deleteLocked = true)

        assertFalse(fixture.controller.delete("conversation"))
        runCurrent()

        assertTrue(fixture.events.isEmpty())
        coVerify(exactly = 0) { fixture.conversations.deleteConversation(any()) }
    }

    @Test
    fun failedDurableDeletionDoesNotCleanupOrSettleSelection() = runTest {
        val failures = mutableListOf<Throwable>()
        val controllerScope = CoroutineScope(
            SupervisorJob() +
                StandardTestDispatcher(testScheduler) +
                CoroutineExceptionHandler { _, error -> failures += error },
        )
        val fixture = Fixture(this, controllerScope = controllerScope)
        coEvery { fixture.conversations.deleteConversation("conversation") } throws
            IllegalStateException("delete failed")

        fixture.controller.delete("conversation")
        runCurrent()

        assertEquals(1, failures.size)
        assertEquals("delete failed", failures.single().message)
        assertTrue("remove" !in fixture.events)
        assertTrue(fixture.events.none { it.startsWith("settle:") })
    }

    private class Fixture(
        testScope: kotlinx.coroutines.test.TestScope,
        currentConversationId: String? = "conversation",
        controllerScope: CoroutineScope = testScope,
        deleteLocked: Boolean = false,
    ) {
        val conversations = mockk<ConversationRepository>()
        val currentConversationId = MutableStateFlow(currentConversationId)
        val events = mutableListOf<String>()
        var onRemove: () -> Unit = {}
        private val dispatcher = StandardTestDispatcher(testScope.testScheduler)
        val controller = ConversationLifecycleController(
            currentConversationId = this.currentConversationId,
            conversations = conversations,
            scope = controllerScope,
            stopLoop = { events += "stop-loop" },
            withConversationLock = { _, block ->
                events += "lock-start"
                block()
                events += "lock-end"
            },
            removeRuntime = {
                events += "remove"
                onRemove()
            },
            stopVisibleGeneration = { events += "stop" },
            settleDeletedSelectedConversation = { conversationId ->
                events += "settle:$conversationId"
            },
            isDeleteLocked = { deleteLocked },
            ioDispatcher = dispatcher,
            mainDispatcher = dispatcher,
        )
    }
}
