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
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ComposerSendAdapterTest {
    @Test
    fun rejectedSendDoesNotClearOrAcknowledgeComposer() = runTest {
        val fixture = Fixture(this, acceptance = null)
        var acknowledged = false

        val result = fixture.adapter.sendMessage(
            "text",
            onAccepted = { acknowledged = true },
            draftOwnerId = "conversation",
        )
        runCurrent()

        assertNull(result)
        assertFalse(acknowledged)
        assertEquals(listOf("load:conversation", "send:text::", "release:conversation"), fixture.events)
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
            draftOwnerId = "draft-owner",
        )
        runCurrent()

        assertEquals(acceptance, result)
        assertEquals(
            listOf(
                "load:draft-owner",
                "send:text:image:uri",
                "clear:draft-owner",
                "ui",
                "release:draft-owner",
                "reclaim",
            ),
            fixture.events,
        )
        coVerify(exactly = 0) { fixture.composers.clearAccepted("accepted-conversation") }
        coVerify(exactly = 1) { fixture.drafts.reclaimAttachments(listOf(attachment)) }
    }

    @Test
    fun queuedAcceptanceClearsDraftButRetainsMemoryOwnedAttachments() = runTest {
        val attachment = SelectedAttachment(uri = "uri", type = "file")
        val acceptance = SendAcceptance.Queued("queued", "conversation")
        val fixture = Fixture(this, acceptance, listOf(attachment))

        val result = fixture.adapter.sendMessage(
            "text",
            onAccepted = { fixture.events += "ui" },
            draftOwnerId = "conversation",
        )
        runCurrent()

        assertEquals(acceptance, result)
        assertEquals(
            listOf(
                "load:conversation",
                "send:text::",
                "clear:conversation",
                "ui",
                "release:conversation",
            ),
            fixture.events,
        )
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
            listOf(
                "load:$NEW_CHAT_WORKSPACE_ID",
                "send:text::",
                "clear:$NEW_CHAT_WORKSPACE_ID",
                "ui",
                "release:$NEW_CHAT_WORKSPACE_ID",
            ),
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
            draftOwnerId = "conversation",
        )
        runCurrent()

        assertEquals(
            listOf(
                "load:conversation",
                "send:inspect::uri",
                "clear:conversation",
                "ui",
                "release:conversation",
            ),
            fixture.events,
        )
        coVerify(exactly = 0) { fixture.drafts.reclaimAttachments(any()) }
    }

    @Test
    fun sendFailureStillReleasesExactDraftOwner() = runTest {
        val fixture = Fixture(
            testScope = this,
            acceptance = null,
            sendFailure = IllegalStateException("send failed"),
        )

        val failure = runCatching {
            fixture.adapter.sendMessage(
                text = "text",
                draftOwnerId = "conversation",
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals(
            listOf("load:conversation", "send:text::", "release:conversation"),
            fixture.events,
        )
        coVerify(exactly = 0) { fixture.composers.clearAccepted(any()) }
    }

    private class Fixture(
        testScope: kotlinx.coroutines.test.TestScope,
        private val acceptance: SendAcceptance?,
        attachmentsToReclaim: List<SelectedAttachment> = emptyList(),
        private val sendFailure: Throwable? = null,
    ) {
        val composers = mockk<ConversationComposerController>()
        val drafts = mockk<ComposerDraftController>()
        val events = mutableListOf<String>()
        private val dispatcher = StandardTestDispatcher(testScope.testScheduler)
        val adapter = ComposerSendAdapter(
            send = { text, images, attachments, onAccepted ->
                events += "send:$text:${images.joinToString()}:${attachments.joinToString { it.uri }}"
                sendFailure?.let { throw it }
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
            coEvery { composers.load(any()) } answers {
                val ownerId = firstArg<String>()
                events += "load:$ownerId"
                ConversationComposerSnapshot(loaded = true)
            }
            coEvery { composers.release(any()) } answers {
                events += "release:${firstArg<String>()}"
            }
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
