package com.newoether.agora.data

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PortableSettingsArchiveTest {
    @Test
    fun compactThresholdImportAcceptsOnlyThePortableRange() {
        assertEquals(50, importedContextCompactThresholdPercent(50))
        assertEquals(90, importedContextCompactThresholdPercent(90))
        assertEquals(100, importedContextCompactThresholdPercent(100))
        assertEquals(null, importedContextCompactThresholdPercent(null))
        assertEquals(null, importedContextCompactThresholdPercent(49))
        assertEquals(null, importedContextCompactThresholdPercent(101))
    }

    @Test
    fun legacyArchiveProviderReusesExistingIdentityAndMarksRoomReferences() {
        val id = "custom-provider-00000000-0000-4000-8000-000000000001"
        val result = PortableSettingsArchive.prepareImportedCustomProviders(
            raw = listOf(CustomProviderConfig(name = "Relay X")),
            existing = listOf(CustomProviderConfig(name = "Relay X", id = id)),
            replace = false,
        )

        assertEquals(id, result.providers.single().id)
        assertEquals(setOf("Relay X"), result.providers.single().legacyNames)
        assertEquals(false, result.providers.single().responsesApiEnabled)
        assertEquals(mapOf("Relay X" to id), result.modelReferenceRemap)
        assertEquals(mapOf("Relay X" to "Relay X"), result.providerNameRemap)
    }

    @Test
    fun importedProviderResponsesSettingReplacesExistingStableRecord() {
        val id = "custom-provider-00000000-0000-4000-8000-000000000001"
        val result = PortableSettingsArchive.prepareImportedCustomProviders(
            raw = listOf(CustomProviderConfig(name = "Relay X", responsesApiEnabled = true)),
            existing = listOf(CustomProviderConfig(name = "Relay X", id = id)),
            replace = false,
        )
        assertEquals(id, result.providers.single().id)
        assertEquals(true, result.providers.single().responsesApiEnabled)
    }

    @Test
    fun replacingFromLegacyArchiveAllocatesStableIdentity() {
        val result = PortableSettingsArchive.prepareImportedCustomProviders(
            raw = listOf(CustomProviderConfig(name = "Relay X")),
            existing = emptyList(),
            replace = true,
        )

        val provider = result.providers.single()
        assertTrue(CustomProviderIdentityPolicy.isStableId(provider.id))
        assertEquals(provider.id, result.modelReferenceRemap["Relay X"])
        assertEquals(setOf("Relay X"), provider.legacyNames)
    }

    @Test
    fun uncachedNotificationIsPortableConditionalAndSharesOneCachePolicy() {
        val mainRoot = locateDirectory("app/src/main/java", "src/main/java")
        fun source(path: String) = File(mainRoot, path).readText().replace("\r\n", "\n")

        val schema = source("com/newoether/agora/data/SettingsPreferenceSchema.kt")
        val manager = source("com/newoether/agora/data/SettingsManager.kt")
        val repository = source("com/newoether/agora/data/repository/SettingsRepository.kt")
        val archive = source("com/newoether/agora/data/PortableSettingsArchive.kt")
        val settingsPage = source("com/newoether/agora/ui/settings/SettingsSearchPage.kt")
        val startup = source("com/newoether/agora/viewmodel/StartupMaintenanceCoordinator.kt")
        val rag = source("com/newoether/agora/viewmodel/RagManager.kt")

        assertTrue(schema.contains("booleanPreferencesKey(\"show_uncached_notification\")"))
        assertTrue(manager.contains("it[SHOW_UNCACHED_NOTIFICATION] ?: true"))
        assertTrue(manager.contains("prefs.remove(SHOW_UNCACHED_NOTIFICATION)"))
        assertTrue(repository.contains("hot(settingsManager.showUncachedNotification, true)"))
        assertTrue(archive.contains("put(\"showUncachedNotification\""))
        assertTrue(archive.contains("obj.boolean(\"showUncachedNotification\")"))

        val autoCacheGroup = settingsPage
            .substringAfter("title = stringResource(R.string.auto_cache_title)")
            .substringBefore("title = stringResource(R.string.search_methods_title)")
        val autoCacheIndex = autoCacheGroup.indexOf("R.string.auto_cache)")
        val reminderIndex = autoCacheGroup.indexOf("R.string.show_uncached_notification)")
        assertTrue(autoCacheGroup.contains("if (!autoCacheEnabled)"))
        assertTrue(autoCacheIndex >= 0 && reminderIndex > autoCacheIndex)

        assertFalse(startup.contains("handleUncachedMessages"))
        assertFalse(startup.contains("getIndexableMessageCount"))
        assertFalse(startup.contains("getEmbeddingCountByModel"))

        val modelSwitchPolicy = rag
            .substringAfter("fun setActiveEmbeddingModel(id: String)")
            .substringBefore("fun cacheMessagesForModel")
        assertTrue(modelSwitchPolicy.contains("if (settings.getAutoCacheEnabled())"))
        assertTrue(modelSwitchPolicy.contains("cacheMessagesForModel(id, silent = true)"))
        assertTrue(modelSwitchPolicy.contains("settings.getShowUncachedNotification()"))
        assertFalse(modelSwitchPolicy.contains("embedding_model_caching"))

        val resourceRoot = locateDirectory("app/src/main/res", "src/main/res")
        listOf(
            "values",
            "values-ar",
            "values-de",
            "values-es",
            "values-fr",
            "values-ja",
            "values-ko",
            "values-pt-rBR",
            "values-ru",
            "values-vi",
            "values-zh",
            "values-zh-rTW",
        ).forEach { directory ->
            val strings = File(resourceRoot, "$directory/strings.xml").readText()
            assertTrue("Missing title in $directory", strings.contains("name=\"show_uncached_notification\""))
            assertTrue("Missing description in $directory", strings.contains("name=\"show_uncached_notification_desc\""))
        }
    }

    private fun locateDirectory(vararg candidates: String): File {
        var directory = File(requireNotNull(System.getProperty("user.dir"))).canonicalFile
        repeat(8) {
            candidates.map { path -> File(directory, path) }
                .firstOrNull(File::isDirectory)
                ?.let { return it }
            directory = directory.parentFile ?: error("Reached filesystem root")
        }
        error("Unable to locate ${candidates.joinToString()}")
    }
}
