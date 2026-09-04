package com.newoether.agora.api.util

import org.junit.Assert.assertEquals
import org.junit.Test

class WebToolWireNameTest {
    @Test
    fun legacyGenericWebNamesAreNamespacedForOpenAiCompatibleWire() {
        assertEquals("agora_web_search", openAiCompatibleWireToolName("web_search"))
        assertEquals("agora_web_fetch", openAiCompatibleWireToolName("web_fetch"))
        assertEquals("agora_web_search", openAiCompatibleWireToolName("agora_web_search"))
        assertEquals("openai_search", openAiCompatibleWireToolName("openai_search"))
        assertEquals("google_search", openAiCompatibleWireToolName("google_search"))
    }
}
