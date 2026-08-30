package com.newoether.agora.viewmodel

import com.newoether.agora.api.DebugProvider
import com.newoether.agora.data.local.ChatEntity
import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import com.newoether.agora.util.DebugLog
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.verify
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConversationSelectionControllerTest {
    @Test
    fun selectPublishesConversationAndModelBeforeUiReadiness() = runTest {
        val fixture = Fixture(backgroundScope)
        coEvery { fixture.conversations.getConversation("conversation") } returns
            ChatEntity("conversation", "Title", modelId = "provider:model")

        fixture.controller.selectConversation("conversation", hapticOnCompletion = false)
        runCurrent()

        assertEquals("conversation", fixture.controller.currentConversationId.value)
        assertEquals("provider:model", fixture.controller.currentActiveModel.value)
        assertFalse(fixture.controller.isNewChatMode.value)
        val request = fixture.controller.switchingScrollRequest.value
        assertEquals(SwitchingRequestKind.CONVERSATION, request?.kind)
        assertTrue(request?.readyForUi == true)
        assertFalse(request?.hapticOnCompletion ?: true)
        assertTrue(fixture.controller.completeSwitchingScroll(checkNotNull(request).id))

        fixture.controller.selectConversation("conversation")
        assertNull(fixture.controller.switchingScrollRequest.value)
        coVerify(exactly = 1) { fixture.conversations.getConversation("conversation") }
    }

    @Test
    fun activeModelWritesAlwaysUseTheWorkspaceBoundary() = runTest {
        val fixture = Fixture(backgroundScope)

        fixture.controller.setActiveModel("new-chat-model")
        verify(exactly = 1) {
            fixture.workspaces.setModel(NEW_CHAT_WORKSPACE_ID, "new-chat-model")
        }

        fixture.controller.publishAcceptedConversation("conversation", "provider:model")
        fixture.controller.setActiveModel("conversation-model")
        verify(exactly = 1) {
            fixture.workspaces.setModel("conversation", "conversation-model")
        }
        coVerify(exactly = 0) { fixture.conversations.upsertConversation(any()) }
    }

    @Test
    fun debugModelVisibilityRequiresBothDeveloperGates() {
        val ordinary = linkedSetOf("provider:model")
        val staleDebugSelection = ordinary + DebugProvider.MODEL_ID

        assertEquals(ordinary, validChatModels(ordinary, false, false))
        assertEquals(ordinary, validChatModels(ordinary, true, false))
        assertEquals(ordinary, validChatModels(ordinary, false, true))
        assertEquals(ordinary, validChatModels(staleDebugSelection, true, false))
        assertEquals(ordinary, validChatModels(staleDebugSelection, false, true))
        assertEquals(
            ordinary + DebugProvider.MODEL_ID,
            validChatModels(ordinary, true, true),
        )
    }

    @Test
    fun disablingDebugFallsBackAndPersistsTheNewChatModel() = runTest {
        val fixture = Fixture(backgroundScope)
        fixture.validModels.value = setOf("default-model", DebugProvider.MODEL_ID)
        fixture.newChatModelId.value = DebugProvider.MODEL_ID
        runCurrent()
        assertEquals(DebugProvider.MODEL_ID, fixture.controller.currentActiveModel.value)

        fixture.validModels.value = setOf("default-model")
        runCurrent()

        assertEquals("default-model", fixture.controller.currentActiveModel.value)
        verify(exactly = 1) {
            fixture.workspaces.setModel(NEW_CHAT_WORKSPACE_ID, "default-model")
        }
    }

    @Test
    fun removedConversationModelFallsBackAndPersistsThroughTheWorkspace() = runTest {
        val fixture = Fixture(backgroundScope)
        fixture.validModels.value = fixture.validModels.value.orEmpty() + "removed-model"
        fixture.controller.publishAcceptedConversation("conversation", "removed-model")
        runCurrent()
        assertEquals("removed-model", fixture.controller.currentActiveModel.value)

        fixture.validModels.value = fixture.validModels.value.orEmpty() - "removed-model"
        runCurrent()

        assertEquals("default-model", fixture.controller.currentActiveModel.value)
        verify(exactly = 1) {
            fixture.workspaces.setModel("conversation", "default-model")
        }
    }

    @Test
    fun missingValidDefaultClearsTheConversationModelAndBlocksGeneration() = runTest {
        val fixture = Fixture(backgroundScope)
        fixture.validModels.value = setOf("stale-model")
        fixture.controller.publishAcceptedConversation("conversation", "stale-model")
        runCurrent()
        assertEquals("stale-model", fixture.controller.currentActiveModel.value)

        fixture.defaultModel.value = "missing-default"
        fixture.validModels.value = emptySet()
        runCurrent()

        assertEquals("", fixture.controller.currentActiveModel.value)
        verify(exactly = 1) {
            fixture.workspaces.setModel("conversation", null)
        }
    }

    @Test
    fun explicitNewChatSelectionSupersedesAStaleWorkspaceFallback() = runTest {
        val fixture = Fixture(backgroundScope)
        fixture.validModels.value = setOf("default-model", "selected-model")
        fixture.newChatModelId.value = "removed-model"
        fixture.controller.setActiveModel("selected-model")
        runCurrent()

        assertEquals("selected-model", fixture.controller.currentActiveModel.value)
        verify(exactly = 1) {
            fixture.workspaces.setModel(NEW_CHAT_WORKSPACE_ID, "selected-model")
        }
        verify(exactly = 0) {
            fixture.workspaces.setModel(NEW_CHAT_WORKSPACE_ID, "default-model")
        }
    }

    @Test
    fun acceptedConversationKeepsTheCapturedModelAfterNewChatStateClears() = runTest {
        val fixture = Fixture(backgroundScope)
        fixture.newChatModelId.value = "restored-model"
        runCurrent()
        assertEquals("restored-model", fixture.controller.currentActiveModel.value)
        fixture.controller.publishAcceptedConversation("conversation", "restored-model")
        fixture.newChatModelId.value = null
        runCurrent()
        assertFalse(fixture.controller.isNewChatMode.value)
        assertEquals("conversation", fixture.controller.currentConversationId.value)
        assertEquals("restored-model", fixture.controller.currentActiveModel.value)
    }

    @Test
    fun newChatKeepsOldConversationUntilFadeThenClearsProjection() = runTest {
        val fadeGate = CompletableDeferred<Unit>()
        val fixture = Fixture(backgroundScope, fadeDelay = { fadeGate.await() })
        fixture.controller.publishAcceptedConversation("conversation", "provider:model")

        fixture.controller.createNewChat()

        assertTrue(fixture.controller.isNewChatMode.value)
        assertTrue(fixture.controller.isTransitioningToNewChat.value)
        assertEquals("conversation", fixture.controller.currentConversationId.value)
        assertEquals(2L, fixture.controller.newChatEntryId.value)

        fadeGate.complete(Unit)
        runCurrent()

        assertNull(fixture.controller.currentConversationId.value)
        assertFalse(fixture.controller.isTransitioningToNewChat.value)
        assertEquals(1, fixture.clearGraphCount)
        assertEquals(1, fixture.abortRegenerationCount)
    }

    @Test
    fun newerConversationSelectionSupersedesPendingNewChat() = runTest {
        val fadeGate = CompletableDeferred<Unit>()
        val fixture = Fixture(backgroundScope, fadeDelay = { fadeGate.await() })
        fixture.controller.publishAcceptedConversation("old", "old-model")
        coEvery { fixture.conversations.getConversation("new") } returns
            ChatEntity("new", "New", modelId = "new-model")

        fixture.controller.createNewChat()
        fixture.controller.selectConversation("new")
        fadeGate.complete(Unit)
        runCurrent()

        assertEquals("new", fixture.controller.currentConversationId.value)
        assertEquals("new-model", fixture.controller.currentActiveModel.value)
        assertFalse(fixture.controller.isNewChatMode.value)
        assertFalse(fixture.controller.isTransitioningToNewChat.value)
        assertEquals(0, fixture.clearGraphCount)
        assertEquals(SwitchingRequestKind.CONVERSATION, fixture.controller
            .switchingScrollRequest.value?.kind)
    }

    @Test
    fun missingTargetDoesNotReplaceTheCurrentConversation() = runTest {
        mockkObject(DebugLog)
        every { DebugLog.e(any(), any()) } returns Unit
        try {
            val fixture = Fixture(backgroundScope)
            fixture.controller.publishAcceptedConversation("current", "current-model")
            coEvery { fixture.conversations.getConversation("missing") } returns null

            fixture.controller.selectConversation("missing")
            runCurrent()

            assertEquals("current", fixture.controller.currentConversationId.value)
            assertFalse(fixture.controller.isNewChatMode.value)
            assertNull(fixture.controller.switchingScrollRequest.value)
            coVerify(exactly = 1) { fixture.conversations.getConversation("missing") }
        } finally {
            unmockkObject(DebugLog)
        }
    }

    @Test
    fun projectionFailureOnlyReleasesItsMatchingSwitchRequest() = runTest {
        mockkObject(DebugLog)
        every { DebugLog.e(any(), any()) } returns Unit
        try {
            val fixture = Fixture(backgroundScope)
            coEvery { fixture.conversations.getConversation("conversation") } returns
                ChatEntity("conversation", "Title")

            fixture.controller.selectConversation("conversation")
            runCurrent()
            val request = checkNotNull(fixture.controller.switchingScrollRequest.value)

            fixture.controller.failConversationLoad("stale-conversation")
            assertEquals(request, fixture.controller.switchingScrollRequest.value)

            fixture.controller.failConversationLoad("conversation")
            assertNull(fixture.controller.switchingScrollRequest.value)
            assertEquals("conversation", fixture.controller.currentConversationId.value)
            assertFalse(fixture.controller.isNewChatMode.value)
        } finally {
            unmockkObject(DebugLog)
        }
    }

    @Test
    fun branchSelectionCommitsRoomBeforePublishingReadyTarget() = runTest {
        val fixture = Fixture(backgroundScope)
        fixture.controller.publishAcceptedConversation("conversation", "provider:model")
        fixture.renderStore.replaceGraph(
            allMessages = listOf(PARENT, FIRST_BRANCH, SECOND_BRANCH),
            selectedChildren = mapOf("parent" to "first"),
        )
        coEvery {
            fixture.conversations.selectRunBranch(
                conversationId = "conversation",
                parentRunId = "parent-run",
                runId = "second-run",
                messageSelections = mapOf("parent" to "second"),
            )
        } coAnswers {
            assertFalse(fixture.controller.switchingScrollRequest.value?.readyForUi ?: true)
        }

        fixture.controller.switchBranch("parent", "first", direction = 1)
        runCurrent()

        assertEquals("second", fixture.renderStore.selectedChildren["parent"])
        val request = fixture.controller.switchingScrollRequest.value
        assertEquals(SwitchingRequestKind.TREE_MUTATION, request?.kind)
        assertEquals("second", request?.targetMessageId)
        assertTrue(request?.readyForUi == true)
        assertEquals(listOf("conversation"), fixture.contextInvalidations)
        coVerify(exactly = 1) {
            fixture.conversations.selectRunBranch(
                "conversation",
                "parent-run",
                "second-run",
                mapOf("parent" to "second"),
            )
        }
        fixture.registry.remove("conversation")
    }

    @Test
    fun deleteMutationCompletionInvalidatesContextBeforePublishingReady() = runTest {
        val fixture = Fixture(backgroundScope)
        fixture.controller.publishAcceptedConversation("conversation", "provider:model")

        val requestId = fixture.controller.beginTreeMutation(scrollToTarget = false)

        assertTrue(fixture.contextInvalidations.isEmpty())
        fixture.controller.markTreeMutationReady(requestId, targetMessageId = null)

        assertEquals(listOf("conversation"), fixture.contextInvalidations)
        val request = fixture.controller.switchingScrollRequest.value
        assertTrue(request?.readyForUi == true)
        assertFalse(request?.scrollToTarget ?: true)
        assertTrue(fixture.controller.completeSwitchingScroll(checkNotNull(request).id))
    }

    @Test
    fun activeRunRejectsBranchMutationBeforeRoom() = runTest {
        val fixture = Fixture(backgroundScope)
        fixture.controller.publishAcceptedConversation("conversation", "provider:model")
        fixture.renderStore.replaceGraph(
            allMessages = listOf(PARENT, FIRST_BRANCH, SECOND_BRANCH),
            selectedChildren = mapOf("parent" to "first"),
        )
        val state = fixture.registry.getOrCreate("conversation")
        assertTrue(state.acquireForSend() != null)

        fixture.controller.switchBranch("parent", "first", direction = 1)
        runCurrent()

        assertEquals("first", fixture.renderStore.selectedChildren["parent"])
        assertNull(fixture.controller.switchingScrollRequest.value)
        coVerify(exactly = 0) {
            fixture.conversations.selectRunBranch(any(), any(), any(), any())
        }
        fixture.registry.remove("conversation")
    }

    private class Fixture(
        scope: CoroutineScope,
        fadeDelay: suspend () -> Unit = {},
    ) {
        val conversations = mockk<ConversationRepository>()
        val registry = ConversationStateRegistry()
        val renderStore = ConversationRenderStore()
        val defaultModel = MutableStateFlow("default-model")
        val validModels = MutableStateFlow<Set<String>?>(
            setOf(
                "default-model",
                "provider:model",
                "new-chat-model",
                "conversation-model",
                "restored-model",
                "old-model",
                "new-model",
                "current-model",
            ),
        )
        val newChatModelId = MutableStateFlow<String?>(null)
        val workspaces = mockk<ConversationWorkspaceStore>(relaxed = true).also {
            every { it.newChatModelId } returns newChatModelId
        }
        var clearGraphCount = 0
        var abortRegenerationCount = 0
        val contextInvalidations = mutableListOf<String>()
        val controller = ConversationSelectionController(
            scope = scope,
            conversations = conversations,
            registry = registry,
            defaultModel = defaultModel,
            validModels = validModels,
            scrollRequests = ScrollRequestCoordinator(),
            renderStore = { renderStore },
            clearConversationGraph = { clearGraphCount += 1 },
            workspaces = workspaces,
            abortRegeneration = { abortRegenerationCount += 1 },
            onTreeMutationCommitted = contextInvalidations::add,
            fadeDelay = fadeDelay,
        )
    }

    private companion object {
        val PARENT = ChatMessage(
            id = "parent",
            text = "input",
            participant = Participant.USER,
            status = MessageStatus.SUCCESS,
            timestamp = 1L,
            runId = "parent-run",
        )
        val FIRST_BRANCH = ChatMessage(
            id = "first",
            parentId = "parent",
            text = "first",
            participant = Participant.MODEL,
            status = MessageStatus.SUCCESS,
            timestamp = 2L,
            runId = "first-run",
        )
        val SECOND_BRANCH = ChatMessage(
            id = "second",
            parentId = "parent",
            text = "second",
            participant = Participant.MODEL,
            status = MessageStatus.SUCCESS,
            timestamp = 3L,
            runId = "second-run",
        )
    }
}
