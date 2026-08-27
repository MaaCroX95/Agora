package com.newoether.agora.viewmodel

import android.content.Context
import com.newoether.agora.R
import com.newoether.agora.data.ConversationSettings
import com.newoether.agora.data.CustomProviderConfig
import com.newoether.agora.data.MemoryManager
import com.newoether.agora.data.SkillManager
import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.data.repository.SettingsRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationRequestBuilderProviderDisplayTest {
    @Test
    fun `conversation tool overrides are reflected immediately in effective settings`() {
        val settings = mockk<SettingsRepository>()
        every { settings.conversationSettings } returns MutableStateFlow(
            mapOf(
                "conversation" to ConversationSettings(
                    webSearchEnabled = false,
                    shellEnabled = false,
                    openAiWebSearchEnabled = false,
                )
            )
        )
        every { settings.maxContextWindow } returns MutableStateFlow(128_000)
        every { settings.defaultTemperature } returns MutableStateFlow(null)
        every { settings.defaultMaxTokens } returns MutableStateFlow(null)
        every { settings.defaultTopP } returns MutableStateFlow(null)
        every { settings.defaultFrequencyPenalty } returns MutableStateFlow(null)
        every { settings.defaultPresencePenalty } returns MutableStateFlow(null)
        every { settings.codeExecutionEnabled } returns MutableStateFlow(false)
        every { settings.googleSearchEnabled } returns MutableStateFlow(false)
        every { settings.openAiWebSearchEnabled } returns MutableStateFlow(true)
        every { settings.thinkingEnabled } returns MutableStateFlow(true)
        every { settings.thinkingLevel } returns MutableStateFlow("medium")
        every { settings.thinkingBudgetEnabled } returns MutableStateFlow(false)
        every { settings.thinkingBudgetTokens } returns MutableStateFlow(4096)
        every { settings.openAiServiceTierEnabled } returns MutableStateFlow(false)
        every { settings.openAiServiceTier } returns MutableStateFlow("auto")
        every { settings.webSearchEnabled } returns MutableStateFlow(true)
        every { settings.shellEnabled } returns MutableStateFlow(true)
        val builder = GenerationRequestBuilder(
            settings = settings,
            convRepo = mockk<ConversationRepository>(),
            memoryManager = mockk<MemoryManager>(),
            skillManager = mockk<SkillManager>(),
            providerRegistry = mockk<ProviderRegistry>(),
            ragManager = mockk<RagManager>(),
            appContext = mockk<Context>(),
            pendingConversationSettings = MutableStateFlow<ConversationSettings?>(null),
            onSnackbar = {},
        )

        val effective = builder.buildEffectiveConversationSettings("conversation")

        assertEquals(false, effective.webSearchEnabled)
        assertEquals(false, effective.shellEnabled)
        assertEquals(false, effective.openAiWebSearchEnabled)
    }

    @Test
    fun `missing custom provider credentials show alias instead of stable id`() {
        val providerId = "custom-provider-00000000-0000-4000-8000-000000000001"
        val providerAlias = "Relay X"
        val modelId = "$providerId:model"
        val settings = mockk<SettingsRepository>()
        val providerRegistry = mockk<ProviderRegistry>()
        val appContext = mockk<Context>()
        val snackbars = mutableListOf<String>()

        every { settings.resolveActiveKey(providerId) } returns null
        every { settings.customProviders } returns MutableStateFlow(
            listOf(CustomProviderConfig(name = providerAlias, id = providerId))
        )
        every { providerRegistry.providerForModel(modelId) } returns providerId
        every { providerRegistry.isConfigured(providerId, "") } returns false
        every {
            appContext.getString(R.string.no_api_key_for_provider, providerAlias)
        } returns "No credentials configured for $providerAlias."

        val builder = GenerationRequestBuilder(
            settings = settings,
            convRepo = mockk<ConversationRepository>(),
            memoryManager = mockk<MemoryManager>(),
            skillManager = mockk<SkillManager>(),
            providerRegistry = providerRegistry,
            ragManager = mockk<RagManager>(),
            appContext = appContext,
            pendingConversationSettings = MutableStateFlow<ConversationSettings?>(null),
            onSnackbar = snackbars::add,
        )

        assertNull(builder.resolveProviderKey(modelId))
        assertEquals(1, snackbars.size)
        assertTrue(snackbars.single().contains(providerAlias))
        assertFalse(snackbars.single().contains(providerId))
    }
}
