package com.newoether.agora.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class DefaultSystemPromptTest {
    @Test
    fun sentDateExample_includesEnglishWeekday() {
        assertEquals("2026-05-09 Sat", PredefinedVariables.EXAMPLE_VALUES[PredefinedVariables.SENT_DATE])
    }

    @Test
    fun titleForLocale_usesChineseDefaultForChineseLocale() {
        assertEquals("Default", DefaultSystemPrompt.titleForLocale(Locale.ENGLISH))
        assertEquals("\u9ed8\u8ba4", DefaultSystemPrompt.titleForLocale(Locale.SIMPLIFIED_CHINESE))
        assertEquals("\u9810\u8a2d", DefaultSystemPrompt.titleForLocale(Locale.forLanguageTag("zh-Hant")))
        assertEquals("Predeterminado", DefaultSystemPrompt.titleForLocale(Locale.forLanguageTag("es")))
        assertEquals("Par d\u00e9faut", DefaultSystemPrompt.titleForLocale(Locale.FRENCH))
    }

    @Test
    fun create_includesActiveMemoryAndToolPolicy_omitsRuntimeContext() {
        val entry = DefaultSystemPrompt.create(Locale.ENGLISH)
        val systemPrompt = PredefinedVariables.compile(
            entry.systemItems,
            mapOf(
                PredefinedVariables.ACTIVE_MEMORY to "User prefers concise answers."
            )
        )

        assertFalse(systemPrompt.contains("<agora_runtime_context>"))
        assertFalse(systemPrompt.contains("<current_date>"))
        assertFalse(systemPrompt.contains("<current_time>"))
        assertTrue(systemPrompt.contains("<active_memory_context>\nUser prefers concise answers.\n</active_memory_context>"))
        assertTrue(systemPrompt.contains("Shell and device files:"))
        assertTrue(systemPrompt.contains("configured shell server or the Local Sandbox"))
        assertTrue(systemPrompt.contains("for factual or externally verifiable questions, prefer using it before substantial reasoning"))
        assertTrue(systemPrompt.contains("Ground the facts first, then reason and synthesize from retrieved evidence"))
        assertFalse(systemPrompt.contains("generate_image"))
    }

    @Test
    fun migrateLegacyWebSearchGuidance_updatesOnlyLegacyStockParagraph() {
        val current = DefaultSystemPrompt.create(Locale.ENGLISH)
        val legacyGuidance =
            "Use web_search for current, time-sensitive, or uncertain facts. Use web_fetch when a search result needs source-level detail. Prefer primary or official sources for technical, legal, medical, financial, or high-impact claims. When web search is used, cite sources and distinguish sourced facts from inference."
        val legacyEntry = current.copy(
            systemItems = current.systemItems.map { item ->
                if (item.type == PromptItemType.CUSTOM && DefaultSystemPrompt.WEB_SEARCH_GUIDANCE in item.value) {
                    item.copy(value = item.value.replace(DefaultSystemPrompt.WEB_SEARCH_GUIDANCE, legacyGuidance))
                } else {
                    item
                }
            }
        )

        val migrated = DefaultSystemPrompt.migrateLegacyWebSearchGuidance(legacyEntry)
        val systemPrompt = PredefinedVariables.compile(migrated.systemItems, emptyMap())

        assertTrue(systemPrompt.contains(DefaultSystemPrompt.WEB_SEARCH_GUIDANCE))
        assertFalse(systemPrompt.contains(legacyGuidance))
        assertEquals(current.userPrependItems, migrated.userPrependItems)
        assertEquals(current.userPostpendItems, migrated.userPostpendItems)
    }

    @Test
    fun hasOldRuntimeContext_detectsOldEntries() {
        val entry = DefaultSystemPrompt.create(Locale.ENGLISH)
        assertFalse(DefaultSystemPrompt.hasOldRuntimeContext(entry))

        // Simulate an old entry by injecting a custom item with the legacy tag
        val oldItems = entry.systemItems.toMutableList()
        oldItems.add(0, PromptTemplateItem(type = PromptItemType.CUSTOM, value = "prefix <agora_runtime_context> old content"))
        val oldEntry = entry.copy(systemItems = oldItems)
        assertTrue(DefaultSystemPrompt.hasOldRuntimeContext(oldEntry))
    }

    @Test
    fun unmodifiedPreviousDefaultIsMigratedInPlace() {
        val previous = DefaultSystemPrompt.previousVersionForMigration(Locale.ENGLISH)
            .copy(id = "default-id")
        val migrated = migrateUnmodifiedBuiltInDefault(
            prompts = listOf(previous),
            locale = Locale.ENGLISH,
        ).single()
        val current = DefaultSystemPrompt.create(Locale.ENGLISH)

        assertEquals("default-id", migrated.id)
        assertEquals(
            current.resolvedSystemItems.map { it.type to it.value },
            migrated.resolvedSystemItems.map { it.type to it.value },
        )
        assertEquals(
            current.resolvedUserItems.map { it.type to it.value },
            migrated.resolvedUserItems.map { it.type to it.value },
        )
        assertEquals(
            current.resolvedAssistantItems.map { it.type to it.value },
            migrated.resolvedAssistantItems.map { it.type to it.value },
        )
        assertTrue(migrated.userPrependItems.isEmpty())
        assertTrue(migrated.userPostpendItems.isEmpty())
    }

    @Test
    fun localizedPreviousDefaultTitlesAreMigrated() {
        val locales = listOf(
            Locale.ENGLISH,
            Locale.forLanguageTag("ar"),
            Locale.GERMAN,
            Locale.forLanguageTag("es"),
            Locale.FRENCH,
            Locale.JAPANESE,
            Locale.KOREAN,
            Locale.forLanguageTag("pt-BR"),
            Locale.forLanguageTag("ru"),
            Locale.SIMPLIFIED_CHINESE,
            Locale.forLanguageTag("zh-Hant"),
        )
        val previousEntries = locales.mapIndexed { index, locale ->
            DefaultSystemPrompt.previousVersionForMigration(locale)
                .copy(id = "localized-default-$index")
        } + DefaultSystemPrompt.previousVersionForMigration(Locale.ENGLISH)
            .copy(id = "mixed-case-default", title = "dEfAuLt")
        val migrated = migrateUnmodifiedBuiltInDefault(
            prompts = previousEntries,
            locale = Locale.ENGLISH,
        )
        val currentSystem = DefaultSystemPrompt.create(Locale.ENGLISH)
            .resolvedSystemItems.map { it.type to it.value }

        assertEquals(previousEntries.map { it.id }, migrated.map { it.id })
        assertEquals(previousEntries.map { it.title }, migrated.map { it.title })
        migrated.forEach { entry ->
            assertEquals(
                currentSystem,
                entry.resolvedSystemItems.map { it.type to it.value },
            )
        }
    }

    @Test
    fun modifiedPreviousDefaultsAreNotMigrated() {
        val previous = DefaultSystemPrompt.previousVersionForMigration(Locale.ENGLISH)
        val contentModified = previous.copy(
            systemItems = previous.systemItems + PromptTemplateItem(
                type = PromptItemType.CUSTOM,
                value = "custom",
            ),
        )
        val titleModified = previous.copy(title = "My Default")

        assertEquals(
            listOf(contentModified, titleModified),
            migrateUnmodifiedBuiltInDefault(
                prompts = listOf(contentModified, titleModified),
                locale = Locale.ENGLISH,
            ),
        )
    }

    @Test
    fun create_wrapsUserMessagesWithSentDateAndTimeMetadata() {
        val entry = DefaultSystemPrompt.create(Locale.ENGLISH)
        val userTemplate = PredefinedVariables.splitMessageTemplate(entry.resolvedUserItems)
        val prefix = PredefinedVariables.compile(
            userTemplate.beforePrompt,
            mapOf(
                PredefinedVariables.SENT_DATE to "2026-05-09 Sat",
                PredefinedVariables.SENT_TIME to "21:35:10"
            ),
            emptyMap()
        )
        val suffix = PredefinedVariables.compile(userTemplate.afterPrompt, emptyMap(), emptyMap())
        val assistantTemplate = PredefinedVariables.splitMessageTemplate(entry.resolvedAssistantItems)

        assertEquals("<agora_user_message sent_date=\"2026-05-09 Sat\" sent_time=\"21:35:10\">\n", prefix)
        assertEquals("\n</agora_user_message>", suffix)
        assertTrue(assistantTemplate.beforePrompt.isEmpty())
        assertTrue(assistantTemplate.afterPrompt.isEmpty())
    }
}
