package com.newoether.agora.data.repository

import com.newoether.agora.data.ConversationSettings
import com.newoether.agora.data.local.ConversationSettingsTransferEntity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationSettingsTransferCoordinatorTest {
    @Test
    fun completePersistsDataStoreBeforeDeletingTheOutbox() = runTest {
        val fixture = Fixture()
        val settings = ConversationSettings(temperature = 0.25f, maxTokens = 512)
        fixture.pending["conversation"] = transfer("conversation", settings)

        assertTrue(fixture.coordinator.complete("conversation"))

        assertEquals(
            listOf("await-load", "read:conversation", "write:conversation", "delete:conversation"),
            fixture.events,
        )
        assertEquals(settings, fixture.writes.single().second)
    }

    @Test
    fun dataStoreFailurePreservesTheOutbox() = runTest {
        val fixture = Fixture()
        fixture.pending["conversation"] = transfer("conversation", ConversationSettings())
        coEvery {
            fixture.settings.setConversationSettingsAndAwait("conversation", any())
        } throws IllegalStateException("DataStore unavailable")

        val failure = runCatching { fixture.coordinator.complete("conversation") }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertTrue(fixture.pending.containsKey("conversation"))
        coVerify(exactly = 0) {
            fixture.conversations.deleteConversationSettingsTransfer(any())
        }
    }

    @Test
    fun replayCompletesEveryPendingTransferInOrder() = runTest {
        val fixture = Fixture()
        val first = ConversationSettings(temperature = 0.1f)
        val second = ConversationSettings(temperature = 0.8f)
        fixture.pending["a"] = transfer("a", first)
        fixture.pending["b"] = transfer("b", second)

        assertEquals(2, fixture.coordinator.replayPending())

        assertEquals(listOf("a" to first, "b" to second), fixture.writes)
        assertTrue(fixture.pending.isEmpty())
    }

    @Test
    fun replayIsolatesFailedRowsAndContinuesWithLaterTransfers() = runTest {
        val fixture = Fixture()
        val failed = ConversationSettings(temperature = 0.1f)
        val completed = ConversationSettings(temperature = 0.8f)
        fixture.pending["a"] = transfer("a", failed)
        fixture.pending["b"] = transfer("b", completed)
        coEvery {
            fixture.settings.setConversationSettingsAndAwait("a", any())
        } throws IllegalStateException("DataStore unavailable")

        assertEquals(1, fixture.coordinator.replayPending())

        assertTrue(fixture.pending.containsKey("a"))
        assertFalse(fixture.pending.containsKey("b"))
        assertEquals(listOf("b" to completed), fixture.writes)
        coVerify(exactly = 0) {
            fixture.conversations.deleteConversationSettingsTransfer("a")
        }
    }

    @Test
    fun replayLoadFailureDoesNotBlockProcessStartup() = runTest {
        val fixture = Fixture()
        fixture.pending["conversation"] = transfer("conversation", ConversationSettings())
        coEvery { fixture.settings.awaitInitialLoad() } throws
            IllegalStateException("DataStore unavailable")

        assertEquals(0, fixture.coordinator.replayPending())

        assertTrue(fixture.pending.containsKey("conversation"))
        coVerify(exactly = 0) {
            fixture.conversations.getPendingConversationSettingsTransfers()
        }
    }

    @Test
    fun nullablePayloadTransfersInheritedDefaults() = runTest {
        val fixture = Fixture()
        fixture.pending["conversation"] = ConversationSettingsTransferEntity("conversation", null)

        assertTrue(fixture.coordinator.complete("conversation"))

        assertEquals(listOf("conversation" to null), fixture.writes)
        assertFalse(fixture.pending.containsKey("conversation"))
    }

    @Test
    fun repeatedCompletionIsIdempotentAfterOutboxDeletion() = runTest {
        val fixture = Fixture()
        fixture.pending["conversation"] = transfer("conversation", ConversationSettings())

        assertTrue(fixture.coordinator.complete("conversation"))
        assertFalse(fixture.coordinator.complete("conversation"))

        assertEquals(1, fixture.writes.size)
        coVerify(exactly = 1) {
            fixture.conversations.deleteConversationSettingsTransfer("conversation")
        }
    }

    private class Fixture {
        val conversations = mockk<ConversationRepository>()
        val settings = mockk<SettingsRepository>()
        val pending = linkedMapOf<String, ConversationSettingsTransferEntity>()
        val writes = mutableListOf<Pair<String, ConversationSettings?>>()
        val events = mutableListOf<String>()
        val coordinator = ConversationSettingsTransferCoordinator(conversations, settings)

        init {
            coEvery { settings.awaitInitialLoad() } coAnswers {
                events += "await-load"
            }
            coEvery { conversations.getConversationSettingsTransfer(any()) } coAnswers {
                firstArg<String>().let { conversationId ->
                    events += "read:$conversationId"
                    pending[conversationId]
                }
            }
            coEvery { conversations.getPendingConversationSettingsTransfers() } coAnswers {
                pending.values.toList()
            }
            coEvery { settings.setConversationSettingsAndAwait(any(), any()) } coAnswers {
                val conversationId = firstArg<String>()
                events += "write:$conversationId"
                writes += conversationId to secondArg<ConversationSettings?>()
            }
            coEvery { conversations.deleteConversationSettingsTransfer(any()) } coAnswers {
                val conversationId = firstArg<String>()
                events += "delete:$conversationId"
                pending.remove(conversationId) != null
            }
        }
    }

    private companion object {
        fun transfer(
            conversationId: String,
            settings: ConversationSettings,
        ) = ConversationSettingsTransferEntity(
            conversationId = conversationId,
            settingsJson = Json.encodeToString(settings),
        )
    }
}
