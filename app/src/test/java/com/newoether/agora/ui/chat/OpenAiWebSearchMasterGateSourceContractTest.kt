package com.newoether.agora.ui.chat

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiWebSearchMasterGateSourceContractTest {
    @Test
    fun `conversation controls preserve the global OpenAI web search master gate`() {
        val source = sourceFile(
            "app/src/main/java/com/newoether/agora/ui/chat/ConversationSettingsWorkspace.kt",
        )

        assertTrue(source.contains(
            "val globalOpenAiWebSearch by viewModel.settings.openAiWebSearchEnabled.collectAsState()",
        ))
        assertTrue(source.contains(
            "val openAiWebSearchAvailable = globalOpenAiWebSearch && openAiNativeSearchAvailable",
        ))
        assertTrue(source.contains(
            "openAiWebSearchAvailable && (conversationOverride?.openAiWebSearchEnabled ?: true)",
        ))
    }

    private fun sourceFile(relativePath: String): String {
        var directory = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        repeat(8) {
            val candidate = File(directory, relativePath)
            if (candidate.isFile) return candidate.readText()
            directory = directory.parentFile ?: error("Reached filesystem root")
        }
        error("Unable to locate $relativePath")
    }
}
