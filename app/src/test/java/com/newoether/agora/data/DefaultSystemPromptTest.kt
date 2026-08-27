package com.newoether.agora.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class DefaultSystemPromptTest {
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
    fun create_wrapsUserMessagesWithSentDateAndTimeMetadata() {
        val entry = DefaultSystemPrompt.create(Locale.ENGLISH)
        val prefix = PredefinedVariables.compile(
            entry.userPrependItems,
            mapOf(
                PredefinedVariables.SENT_DATE to "2026-06-17",
                PredefinedVariables.SENT_TIME to "21:35:10"
            ),
            emptyMap()
        )
        val suffix = PredefinedVariables.compile(entry.userPostpendItems, emptyMap(), emptyMap())

        assertEquals("<agora_user_message sent_date=\"2026-06-17\" sent_time=\"21:35:10\">\n", prefix)
        assertEquals("\n</agora_user_message>", suffix)
    }
}
