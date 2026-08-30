package com.newoether.agora.viewmodel

import com.newoether.agora.model.AttachmentStorage
import com.newoether.agora.model.SelectedAttachment
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ComposerSendAdapterTest {
    @Test
    fun rejectedSendDoesNotClearOrAcknowledgeComposer() = runTest {
        val fixture = Fixture(this, acceptance = null)
        var acknowledged = false

        val result = fixture.adapter.sendMessage("text", onAccepted = { acknowledged = true })
        runCurrent()

        assertNull(result)
        assertFalse(acknowledged)
        coVerify(exactly = 0) { fixture.composers.clearAccepted(any()) }
        coVerify(exactly = 0) { fixture.drafts.reclaimAttachments(any()) }
    }

    @Test
    fun directAcceptanceClearsExactDraftBeforeUiAndReclaimsAfterward() = runTest {
        val attachment = SelectedAttachment(uri = "uri", type = "file")
        val acceptance = SendAcceptance.Direct("message", "accepted-conversation")
        val fixture = Fixture(this, acceptance, listOf(attachment))

        val result = fixture.adapter.sendMessage(
            text = "text",
            images = listOf("image"),
            attachments = listOf(attachment),
            onAccepted = { fixture.events += "ui" },
        )
        runCurrent()

        assertEquals(acceptance, result)
        assertEquals(
            listOf("send:text:image:uri", "clear:accepted-conversation", "ui", "reclaim"),
            fixture.events,
        )
        coVerify(exactly = 1) { fixture.drafts.reclaimAttachments(listOf(attachment)) }
    }

    @Test
    fun queuedAcceptanceClearsDraftButRetainsMemoryOwnedAttachments() = runTest {
        val attachment = SelectedAttachment(uri = "uri", type = "file")
        val acceptance = SendAcceptance.Queued("queued", "conversation")
        val fixture = Fixture(this, acceptance, listOf(attachment))

        val result = fixture.adapter.sendMessage("text", onAccepted = { fixture.events += "ui" })
        runCurrent()

        assertEquals(acceptance, result)
        assertEquals(listOf("send:text::", "clear:conversation", "ui"), fixture.events)
        coVerify(exactly = 0) { fixture.drafts.reclaimAttachments(any()) }
    }

    @Test
    fun newChatAcceptanceClearsWorkspaceOwnerInsteadOfCreatedConversation() = runTest {
        val acceptance = SendAcceptance.Direct("message", "created-conversation")
        val fixture = Fixture(this, acceptance)
        val result = fixture.adapter.sendMessage(
            text = "text",
            draftOwnerId = NEW_CHAT_WORKSPACE_ID,
            onAccepted = { fixture.events += "ui" },
        )
        runCurrent()
        assertEquals(acceptance, result)
        assertEquals(
            listOf("send:text::", "clear:$NEW_CHAT_WORKSPACE_ID", "ui"),
            fixture.events,
        )
        coVerify(exactly = 0) { fixture.composers.clearAccepted("created-conversation") }
    }
    @Test
    fun stalePendingDraftCannotReclaimSubmittedRuntimeAttachment() = runTest {
        val stalePending = SelectedAttachment(
            localId = "stable-id",
            uri = "uri",
            type = "file",
            storage = AttachmentStorage.LOCAL_SANDBOX_PENDING,
        )
        val submitted = stalePending.copy(storage = AttachmentStorage.LOCAL_SANDBOX_RUNTIME)
        val acceptance = SendAcceptance.Direct("message", "conversation")
        val fixture = Fixture(this, acceptance, listOf(stalePending))

        fixture.adapter.sendMessage(
            text = "inspect",
            attachments = listOf(submitted),
            onAccepted = { fixture.events += "ui" },
        )
        runCurrent()

        assertEquals(listOf("send:inspect::uri", "clear:conversation", "ui"), fixture.events)
        coVerify(exactly = 0) { fixture.drafts.reclaimAttachments(any()) }
    }

    private class Fixture(
        testScope: kotlinx.coroutines.test.TestScope,
        private val acceptance: SendAcceptance?,
        attachmentsToReclaim: List<SelectedAttachment> = emptyList(),
    ) {
        val composers = mockk<ConversationComposerController>()
        val drafts = mockk<ComposerDraftController>()
        val events = mutableListOf<String>()
        private val dispatcher = StandardTestDispatcher(testScope.testScheduler)
        val adapter = ComposerSendAdapter(
            send = { text, images, attachments, onAccepted ->
                events += "send:$text:${images.joinToString()}:${attachments.joinToString { it.uri }}"
                acceptance?.let { onAccepted(it) }
                acceptance
            },
            composers = composers,
            drafts = drafts,
            scope = testScope.backgroundScope,
            mainDispatcher = dispatcher,
            ioDispatcher = dispatcher,
        )

        init {
            coEvery { composers.clearAccepted(any()) } answers {
                events += "clear:${firstArg<String>()}"
                DraftClearResult(
                    attachments = attachmentsToReclaim,
                    revision = 1L,
                    succeeded = true,
                )
            }
            coEvery { drafts.reclaimAttachments(any()) } answers {
                events += "reclaim"
            }
        }
    }
}
