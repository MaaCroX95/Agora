package com.newoether.agora.ui.chat.message

import org.junit.Assert.assertEquals
import org.junit.Test

class WebToolPresentationNameTest {
    @Test
    fun canonicalAndLegacyGenericWebNamesShareExistingPresentation() {
        assertEquals(ToolKind.WEB_SEARCH, ToolPresentationResolver.kindForToolName("agora_web_search"))
        assertEquals(ToolKind.WEB_SEARCH, ToolPresentationResolver.kindForToolName("web_search"))
        assertEquals(ToolKind.WEB_FETCH, ToolPresentationResolver.kindForToolName("agora_web_fetch"))
        assertEquals(ToolKind.WEB_FETCH, ToolPresentationResolver.kindForToolName("web_fetch"))
    }
}
