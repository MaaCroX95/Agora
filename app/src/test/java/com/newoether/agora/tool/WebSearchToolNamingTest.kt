package com.newoether.agora.tool

import com.newoether.agora.viewmodel.GenerationContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebSearchToolNamingTest {
    @Test
    fun genericDefinitionsUseAgoraNamespaceAndLegacyAliasesRemainExecutable() {
        val provider = WebSearchToolProvider()
        val definitions = provider.definitions(GenerationContext(webSearchEnabled = true))

        assertEquals(
            listOf("agora_web_search", "agora_web_fetch"),
            definitions.map { it.function.name },
        )
        assertFalse(definitions.any { it.function.name == "web_search" || it.function.name == "web_fetch" })
        assertTrue(provider.handles("agora_web_search"))
        assertTrue(provider.handles("agora_web_fetch"))
        assertTrue(provider.handles("web_search"))
        assertTrue(provider.handles("web_fetch"))
    }
}
