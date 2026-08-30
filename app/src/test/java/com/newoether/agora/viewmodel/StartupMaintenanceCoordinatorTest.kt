package com.newoether.agora.viewmodel

import com.newoether.agora.data.EmbeddingModelConfig
import com.newoether.agora.data.EmbeddingModelType
import com.newoether.agora.data.CustomProviderIdentityMigration
import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.data.repository.SettingsRepository
import com.newoether.agora.util.SnackbarEvent
import com.newoether.agora.util.UpdateInfo
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.mockk
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class StartupMaintenanceCoordinatorTest {
    @Test
    fun updateCheckAtExactDailyBoundaryIsNotDue() = runTest {
        val fixture = Fixture(this, now = DAY_MS + 1L)
        coEvery { fixture.settings.getAutoUpdateCheck() } returns true
        coEvery { fixture.settings.getLastUpdateCheckTime() } returns 1L

        fixture.coordinator.start()
        runCurrent()

        coVerify(exactly = 0) { fixture.settings.saveLastUpdateCheckTime(any()) }
        assertTrue(fixture.checkedVersions.isEmpty())
    }

    @Test
    fun dueUpdatePersistsTimestampBeforeNetworkAndPublishesResult() = runTest {
        val fixture = Fixture(this, now = DAY_MS + 2L)
        val update = UpdateInfo("3.0", "https://example.invalid", "notes")
        coEvery { fixture.settings.getAutoUpdateCheck() } returns true
        coEvery { fixture.settings.getLastUpdateCheckTime() } returns 1L
        coEvery { fixture.settings.saveLastUpdateCheckTime(DAY_MS + 2L) } answers {
            fixture.events += "timestamp"
        }
        fixture.updateResult = update

        fixture.coordinator.start()
        runCurrent()

        assertEquals(listOf("timestamp", "check"), fixture.events.filter { it != "backup" })
        assertEquals(listOf("current"), fixture.checkedVersions)
        assertEquals(listOf(update), fixture.updates)
    }

    @Test
    fun uncachedReminderKeepsCountsAndExactCacheAction() = runTest {
        val fixture = Fixture(this)
        val active = EmbeddingModelConfig(
            id = "embedding",
            name = "Embedding",
            type = EmbeddingModelType.REMOTE,
        )
        coEvery { fixture.settings.getEmbeddingModels() } returns listOf(active)
        coEvery { fixture.settings.getActiveEmbeddingModelId() } returns active.id
        coEvery { fixture.settings.getAutoCacheEnabled() } returns false
        coEvery { fixture.settings.getShowUncachedNotification() } returns true
        coEvery { fixture.conversations.getIndexableMessageCount() } returns 10
        coEvery { fixture.conversations.getEmbeddingCountByModel(active.id) } returns 3

        fixture.coordinator.start()
        runCurrent()
        fixture.snackbars.single().onAction?.invoke()

        assertEquals("7/10", fixture.snackbars.single().message)
        assertEquals(listOf(active.id to false), fixture.cacheRequests)
    }

    @Test
    fun autoCacheSilentlyBackfillsUncachedMessages() = runTest {
        val fixture = Fixture(this)
        val active = EmbeddingModelConfig(
            id = "embedding",
            name = "Embedding",
            type = EmbeddingModelType.LOCAL,
        )
        coEvery { fixture.settings.getEmbeddingModels() } returns listOf(active)
        coEvery { fixture.settings.getActiveEmbeddingModelId() } returns active.id
        coEvery { fixture.settings.getAutoCacheEnabled() } returns true
        coEvery { fixture.conversations.getIndexableMessageCount() } returns 10
        coEvery { fixture.conversations.getEmbeddingCountByModel(active.id) } returns 3

        fixture.coordinator.start()
        runCurrent()

        assertEquals(listOf(active.id to true), fixture.cacheRequests)
        assertTrue(fixture.snackbars.isEmpty())
    }

    @Test
    fun disabledAutoCacheAndReminderLeaveUncachedMessagesSilent() = runTest {
        val fixture = Fixture(this)
        val active = EmbeddingModelConfig(
            id = "embedding",
            name = "Embedding",
            type = EmbeddingModelType.REMOTE,
        )
        coEvery { fixture.settings.getEmbeddingModels() } returns listOf(active)
        coEvery { fixture.settings.getActiveEmbeddingModelId() } returns active.id
        coEvery { fixture.settings.getAutoCacheEnabled() } returns false
        coEvery { fixture.settings.getShowUncachedNotification() } returns false
        coEvery { fixture.conversations.getIndexableMessageCount() } returns 10
        coEvery { fixture.conversations.getEmbeddingCountByModel(active.id) } returns 3

        fixture.coordinator.start()
        runCurrent()

        assertTrue(fixture.cacheRequests.isEmpty())
        assertTrue(fixture.snackbars.isEmpty())
    }

    @Test
    fun activeCachingSuppressesReminder() = runTest {
        val fixture = Fixture(this, cachingIds = setOf("embedding"))
        val active = EmbeddingModelConfig(
            id = "embedding",
            name = "Embedding",
            type = EmbeddingModelType.LOCAL,
        )
        coEvery { fixture.settings.getEmbeddingModels() } returns listOf(active)
        coEvery { fixture.settings.getActiveEmbeddingModelId() } returns active.id
        coEvery { fixture.conversations.getIndexableMessageCount() } returns 10
        coEvery { fixture.conversations.getEmbeddingCountByModel(active.id) } returns 0

        fixture.coordinator.start()
        runCurrent()

        assertTrue(fixture.snackbars.isEmpty())
    }

    @Test
    fun sweepFailureIsReportedWhileOtherMaintenanceStillRuns() = runTest {
        val error = IllegalStateException("sweep")
        val fixture = Fixture(this, sweepFailure = error)

        fixture.coordinator.start()
        assertEquals(listOf("backup"), fixture.events)
        runCurrent()

        assertSame(error, fixture.sweepFailures.single())
        coVerify(exactly = 1) { fixture.conversations.deleteOrphanedEmbeddings() }
    }

    @Test
    fun legacyProviderMarkerClearsOnlyAfterRoomReferencesMigrate() = runTest {
        val fixture = Fixture(this)
        val migration = CustomProviderIdentityMigration(
            legacyReference = "Relay X",
            providerId = "custom-provider-00000000-0000-4000-8000-000000000001",
        )
        coEvery { fixture.settings.normalizeCustomProviderIdentities() } returns listOf(migration)
        coEvery {
            fixture.conversations.renameConfiguredProviderModelReferences(
                migration.legacyReference,
                migration.providerId,
            )
        } returns Unit

        fixture.coordinator.start()
        runCurrent()

        coVerifyOrder {
            fixture.settings.normalizeCustomProviderIdentities()
            fixture.conversations.renameConfiguredProviderModelReferences(
                migration.legacyReference,
                migration.providerId,
            )
            fixture.settings.clearLegacyCustomProviderNames(listOf(migration))
        }
    }

    private class Fixture(
        testScope: kotlinx.coroutines.test.TestScope,
        now: Long = 0L,
        cachingIds: Set<String> = emptySet(),
        private val sweepFailure: Exception? = null,
    ) {
        val settings = mockk<SettingsRepository>()
        val conversations = mockk<ConversationRepository>()
        val events = mutableListOf<String>()
        val checkedVersions = mutableListOf<String>()
        val updates = mutableListOf<UpdateInfo>()
        val snackbars = mutableListOf<SnackbarEvent>()
        val cacheRequests = mutableListOf<Pair<String, Boolean>>()
        val sweepFailures = mutableListOf<Exception>()
        var updateResult: UpdateInfo? = null
        private val dispatcher = StandardTestDispatcher(testScope.testScheduler)
        val coordinator = StartupMaintenanceCoordinator(
            settings = settings,
            conversations = conversations,
            scope = testScope,
            currentVersion = { "current" },
            checkUpdate = { version ->
                events += "check"
                checkedVersions += version
                updateResult
            },
            onUpdateFound = updates::add,
            isCaching = { it in cachingIds },
            cacheMessages = { modelId, silent -> cacheRequests += modelId to silent },
            cacheReminder = { notCached, total, action ->
                SnackbarEvent("$notCached/$total", "cache", action)
            },
            emitSnackbar = snackbars::add,
            sweepAttachments = { sweepFailure?.let { throw it } },
            onAttachmentSweepFailure = sweepFailures::add,
            startAutoBackup = { events += "backup" },
            now = { now },
            ioDispatcher = dispatcher,
        )

        init {
            coEvery { settings.getAutoUpdateCheck() } returns false
            coEvery { settings.getEmbeddingModels() } returns emptyList()
            coEvery { settings.getActiveEmbeddingModelId() } returns ""
            coEvery { settings.normalizeCustomProviderIdentities() } returns emptyList()
            coEvery { settings.clearLegacyCustomProviderNames(any()) } returns Unit
            coEvery { conversations.deleteOrphanedEmbeddings() } returns Unit
            coEvery { conversations.repairInvalidRunBranchSelections() } returns 0
        }
    }

    private companion object {
        const val DAY_MS = 24 * 60 * 60 * 1000L
    }
}
