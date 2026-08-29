package com.newoether.agora.ui.settings

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DataControlImportStrategySourceContractTest {
    @Test
    fun unifiedImportPageUsesSharedStrategyControls() {
        val page = sourceFile(
            "app/src/main/java/com/newoether/agora/ui/settings/datacontrol/SettingsDataControlPage.kt",
        ).readText().normalizeLines()

        assertEquals(3, Regex("""\bPillTabSwitcher\(""").findAll(page).count())
        assertFalse(page.contains("StrategyChip("))
        assertFalse(page.contains("FilterChip("))
        assertTrue(page.contains("var claudeImportStrategy by remember"))
        assertTrue(page.contains("var gptImportStrategy by remember"))
        assertTrue(
            Regex(
                """importClaudeChat\(\s*uri,\s*claudeImportStrategy,\s*finalIds,""",
            ).containsMatchIn(page),
        )
        assertTrue(
            Regex(
                """importGptChat\(\s*uri,\s*gptImportStrategy,\s*finalIds,""",
            ).containsMatchIn(page),
        )
    }

    @Test
    fun externalReplaceRequiresTheSharedDestructiveConfirmation() {
        val page = sourceFile(
            "app/src/main/java/com/newoether/agora/ui/settings/datacontrol/SettingsDataControlPage.kt",
        ).readText().normalizeLines()

        assertTrue(page.contains("pendingExternalReplace = true to finalIds"))
        assertTrue(page.contains("pendingExternalReplace = false to finalIds"))
        assertTrue(page.contains("pendingExternalReplace?.let { (isClaude, selectedIds) ->"))
        assertTrue(page.contains("R.string.external_import_replace_confirm_title"))
        assertTrue(page.contains("fontWeight = FontWeight.Bold"))
        assertTrue(page.contains("contentColor = MaterialTheme.colorScheme.error"))
        assertTrue(
            Regex(
                """importClaudeChat\(\s*uri,\s*DataImporter\.ImportStrategy\.REPLACE,\s*selectedIds,""",
            ).containsMatchIn(page),
        )
        assertTrue(
            Regex(
                """importGptChat\(\s*uri,\s*DataImporter\.ImportStrategy\.REPLACE,\s*selectedIds,""",
            ).containsMatchIn(page),
        )
    }

    @Test
    fun replaceConfirmationResourcesHaveSupportedLocaleParity() {
        val keys = listOf(
            "external_import_replace_confirm_title",
            "external_import_replace_confirm_message",
            "external_import_replace_confirm_button",
        )
        val directories = listOf(
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
        )

        directories.forEach { directory ->
            val strings = sourceFile("app/src/main/res/$directory/strings.xml").readText()
            keys.forEach { key ->
                assertTrue("Missing $key in $directory", strings.contains("name=\"$key\""))
            }
        }

        val defaults = sourceFile("app/src/main/res/values/strings.xml").readText()
        assertTrue(
            defaults.contains(
                "<string name=\"external_import_replace_confirm_title\">" +
                    "Replace Existing Conversations?</string>",
            ),
        )
    }

    @Test
    fun legacyClaudePageAndStrategyTypeAreRemoved() {
        val mainRoot = sourceFile("app/src/main/java")
        val legacyPage = File(
            mainRoot,
            "com/newoether/agora/ui/settings/SettingsClaudeImportPage.kt",
        )
        assertFalse(legacyPage.exists())

        val kotlinSources = mainRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }
        kotlinSources.forEach { source ->
            val text = source.readText()
            assertFalse(source.path, text.contains("SettingsClaudeImportPage"))
            assertFalse(
                source.path,
                text.contains("com.newoether.agora.ui.settings.ImportStrategy"),
            )
        }
    }

    private fun sourceFile(relativePath: String): File {
        var directory = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        repeat(8) {
            val candidate = File(directory, relativePath)
            if (candidate.exists()) return candidate
            directory = directory.parentFile ?: return@repeat
        }
        error("Unable to locate Agora source: $relativePath")
    }

    private fun String.normalizeLines(): String = replace("\r\n", "\n")
}
