package com.newoether.agora.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsContractsTest {
    @Test
    fun automaticContextCompactIsEnabledByDefault() {
        assertTrue(DEFAULT_CONTEXT_COMPACT_ENABLED)
    }

    @Test
    fun contextCompactRetainsNoRecentMessagesByDefault() {
        assertEquals(0, DEFAULT_CONTEXT_COMPACT_RETAIN_COUNT)
    }

    @Test
    fun contextCompactThresholdDefaultsToNinetyPercent() {
        assertEquals(90, DEFAULT_CONTEXT_COMPACT_THRESHOLD_PERCENT)
        assertEquals(50..100, CONTEXT_COMPACT_THRESHOLD_PERCENT_RANGE)
    }

    @Test
    fun defaultContextCompactPromptPreservesConversationLanguages() {
        val prompt = BuiltInPrompts.CONTEXT_COMPACT_SYSTEM.lowercase()
        assertTrue(prompt.contains("same language"))
        assertTrue(prompt.contains("do not translate"))
    }

    @Test
    fun legacyPromptContentResolvesToOneCustomSystemItem() {
        val prompt = SystemPromptEntry(
            title = "Legacy",
            content = "Preserve this prompt",
        )

        val resolved = prompt.resolvedSystemItems.single()
        assertEquals(PromptItemType.CUSTOM, resolved.type)
        assertEquals("Preserve this prompt", resolved.value)
    }

    @Test
    fun explicitSystemItemsTakePrecedenceOverLegacyContent() {
        val explicit = listOf(
            PromptTemplateItem(type = PromptItemType.CUSTOM, value = "Explicit"),
        )

        assertEquals(
            explicit,
            SystemPromptEntry(
                title = "Current",
                content = "Legacy",
                systemItems = explicit,
            ).resolvedSystemItems,
        )
    }

    @Test
    fun explicitUserItemsTakePrecedenceOverLegacyMessageWrappers() {
        val currentPrompt = PredefinedVariables.promptItem()
        val entry = SystemPromptEntry(
            title = "Current",
            userItems = listOf(
                PromptTemplateItem(type = PromptItemType.CUSTOM, value = "current-before"),
                currentPrompt,
                PromptTemplateItem(type = PromptItemType.CUSTOM, value = "current-after"),
            ),
            userPrependItems = listOf(
                PromptTemplateItem(type = PromptItemType.CUSTOM, value = "legacy-before"),
            ),
            userPostpendItems = listOf(
                PromptTemplateItem(type = PromptItemType.CUSTOM, value = "legacy-after"),
            ),
        )
        val resolved = PredefinedVariables.splitMessageTemplate(entry.resolvedUserItems)
        assertEquals(listOf("current-before"), resolved.beforePrompt.map(PromptTemplateItem::value))
        assertEquals(listOf("current-after"), resolved.afterPrompt.map(PromptTemplateItem::value))
        assertEquals(currentPrompt.id, entry.resolvedUserItems.single(PredefinedVariables::isPromptItem).id)
    }
    @Test
    fun legacyMessageWrappersAreUsedOnlyWhenStructuredUserItemsAreMissing() {
        val entry = SystemPromptEntry(
            title = "Legacy",
            userPrependItems = listOf(
                PromptTemplateItem(type = PromptItemType.CUSTOM, value = "legacy-before"),
            ),
            userPostpendItems = listOf(
                PromptTemplateItem(type = PromptItemType.CUSTOM, value = "legacy-after"),
            ),
        )
        val resolved = PredefinedVariables.splitMessageTemplate(entry.resolvedUserItems)
        assertEquals(listOf("legacy-before"), resolved.beforePrompt.map(PromptTemplateItem::value))
        assertEquals(listOf("legacy-after"), resolved.afterPrompt.map(PromptTemplateItem::value))
        assertEquals(1, entry.resolvedUserItems.count(PredefinedVariables::isPromptItem))
    }
    @Test
    fun messageTemplatesContainExactlyOneStructuralPromptItem() {
        val firstPrompt = PredefinedVariables.promptItem()
        val entry = SystemPromptEntry(
            title = "Structured",
            userItems = listOf(
                PromptTemplateItem(type = PromptItemType.CUSTOM, value = "before"),
                firstPrompt,
                PromptTemplateItem(type = PromptItemType.CUSTOM, value = "middle"),
                PredefinedVariables.promptItem(),
                PromptTemplateItem(type = PromptItemType.CUSTOM, value = "after"),
            ),
            assistantItems = listOf(
                PromptTemplateItem(type = PromptItemType.CUSTOM, value = "assistant suffix"),
            ),
        )

        assertEquals(1, entry.resolvedUserItems.count(PredefinedVariables::isPromptItem))
        assertEquals(
            firstPrompt.id,
            entry.resolvedUserItems.single(PredefinedVariables::isPromptItem).id,
        )
        assertEquals(
            listOf("before"),
            PredefinedVariables.splitMessageTemplate(entry.resolvedUserItems)
                .beforePrompt.map(PromptTemplateItem::value),
        )
        assertEquals(
            listOf("middle", "after"),
            PredefinedVariables.splitMessageTemplate(entry.resolvedUserItems)
                .afterPrompt.map(PromptTemplateItem::value),
        )
        assertEquals(1, entry.resolvedAssistantItems.count(PredefinedVariables::isPromptItem))
        assertTrue(PredefinedVariables.isPromptItem(entry.resolvedAssistantItems.last()))
    }

    @Test
    fun currentAndMessageModelVariablesHaveDistinctScopes() {
        assertTrue(PredefinedVariables.CURRENT_MODEL_ID in PredefinedVariables.ALL)
        assertTrue(PredefinedVariables.MESSAGE_MODEL_ID in PredefinedVariables.ALL)
        assertFalse(PredefinedVariables.MODEL_ID in PredefinedVariables.ALL)
        assertFalse(PredefinedVariables.CURRENT_MODEL_ID in PredefinedVariables.PER_MESSAGE_VARS)
        assertTrue(PredefinedVariables.MESSAGE_MODEL_ID in PredefinedVariables.PER_MESSAGE_VARS)
    }

    @Test
    fun conversationSettingsReportsWhetherAnyOverrideExists() {
        assertTrue(ConversationSettings().isAllNull())
        assertFalse(ConversationSettings(openAiWebSearchEnabled = false).isAllNull())
        assertFalse(ConversationSettings(shellEnabled = false).isAllNull())
    }
    @Test
    fun removedOpenAiGenericSearchProviderFallsBackToDuckDuckGo() {
        assertEquals("duckduckgo", normalizeWebSearchProvider(" OpenAI "))
        assertEquals("duckduckgo", normalizeWebSearchProvider(" unknown "))
        assertEquals("kagi", normalizeWebSearchProvider(" KAGI "))
    }
}
