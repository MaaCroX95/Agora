package com.newoether.agora.ui.chat

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiNativeSearchWiringTest {
    @Test
    fun `chat and generation paths wire native OpenAI search end to end`() {
        val root = locateMainSourceRoot()
        val chatApp = File(root, "com/newoether/agora/ui/chat/ChatApp.kt").readText()
        val requestBuilder = File(
            root,
            "com/newoether/agora/viewmodel/GenerationRequestBuilder.kt",
        ).readText()
        val contracts = File(
            root,
            "com/newoether/agora/viewmodel/GenerationContracts.kt",
        ).readText()

        listOf(
            "openAiWebSearchAvailable = openAiWebSearchAvailable",
            "openAiWebSearchEnabled = openAiWebSearchEnabled",
            "onOpenAiWebSearchToggle =",
        ).forEach { wiring ->
            assertTrue("ChatApp must wire $wiring", wiring in chatApp)
        }
        listOf(
            "responsesApiEnabled = isResponsesApiEnabledForProvider(",
            "effectiveSettings.openAiWebSearchEnabled == true && responsesApiEnabled",
        ).forEach { wiring ->
            assertTrue("generation request must wire $wiring", wiring in requestBuilder)
        }
        assertTrue("GenerationConfig must carry Responses API", "val responsesApiEnabled" in contracts)
        assertTrue("GenerationConfig must carry native search", "val openAiWebSearchEnabled" in contracts)
    }

    @Test
    fun `chat and generation paths wire compact threshold and provider transport`() {
        val root = locateMainSourceRoot()
        val chatApp = File(root, "com/newoether/agora/ui/chat/ChatApp.kt").readText()
        val requestBuilder = File(
            root,
            "com/newoether/agora/viewmodel/GenerationRequestBuilder.kt",
        ).readText()
        val contracts = File(
            root,
            "com/newoether/agora/viewmodel/GenerationContracts.kt",
        ).readText()
        val compactor = File(
            root,
            "com/newoether/agora/viewmodel/ContextCompactor.kt",
        ).readText()
        val compactController = File(
            root,
            "com/newoether/agora/viewmodel/ConversationCompactController.kt",
        ).readText()
        val standardLauncher = File(
            root,
            "com/newoether/agora/viewmodel/StandardGenerationContinuationLauncher.kt",
        ).readText()
        val boundLauncher = File(
            root,
            "com/newoether/agora/viewmodel/BoundRunGenerationLauncher.kt",
        ).readText()

        assertTrue(
            "ChatApp must collect the compact threshold",
            "settings.contextCompactThresholdPercent.collectAsState()" in chatApp,
        )
        assertTrue(
            "ChatApp must pass the compact threshold",
            "contextCompactThresholdPercent = compactThresholdPercent" in chatApp,
        )
        assertTrue(
            "automatic Compact must freeze the threshold",
            "thresholdPercent = settings.contextCompactThresholdPercent.value" in requestBuilder,
        )
        assertTrue(
            "automatic Compact must freeze the selected provider transport",
            "responsesApiEnabled = isResponsesApiEnabledForProvider(" in requestBuilder,
        )
        assertTrue("AutomaticCompactConfig must carry the threshold", "val thresholdPercent" in contracts)
        assertTrue("AutomaticCompactConfig must carry Responses API", "val responsesApiEnabled" in contracts)
        assertTrue(
            "ContextCompactor must apply the configured threshold",
            "automaticCompactTokenThreshold(" in compactor &&
                "config.thresholdPercent" in compactor,
        )
        assertTrue(
            "Compact must delegate admission to the ordinary continuation launcher",
            "continuationLauncher().launch(" in compactController,
        )
        assertTrue(
            "ordinary continuation must own the bound generation launch",
            "boundRunGenerationLauncher().launch(" in standardLauncher,
        )
        assertTrue(
            "the ordinary GenerationManager must own provider execution",
            "val result = generationManager.generate(" in boundLauncher,
        )
    }

    @Test
    fun `conversation service tier stays wired and standalone OpenAI search stays retired`() {
        val root = locateMainSourceRoot()
        val chatApp = File(root, "com/newoether/agora/ui/chat/ChatApp.kt").readText()
        val serviceTier = File(
            root,
            "com/newoether/agora/ui/chat/OpenAiConversationServiceTier.kt",
        ).readText()
        val settingsContracts = File(
            root,
            "com/newoether/agora/data/SettingsContracts.kt",
        ).readText()
        val webSearchProvider = File(
            root,
            "com/newoether/agora/tool/WebSearchToolProvider.kt",
        ).readText()
        val settingsPage = File(
            root,
            "com/newoether/agora/ui/settings/SettingsWebSearchPage.kt",
        ).readText().replace("\r\n", "\n")

        listOf(
            "openAiServiceTierAvailable =",
            "openAiServiceTierEnabled =",
            "openAiServiceTier =",
            "onOpenAiServiceTierToggle =",
            "onOpenAiServiceTierChange =",
        ).forEach { wiring -> assertTrue("ChatApp must wire $wiring", wiring in chatApp) }
        assertTrue(
            "service-tier toggle must persist a conversation override",
            "it.copy(openAiServiceTierEnabled = enabled)" in serviceTier,
        )
        assertTrue(
            "service-tier selection must persist a normalized conversation override",
            "it.copy(openAiServiceTier = OpenAiServiceTiers.normalize(tier))" in serviceTier,
        )
        assertFalse("generic provider set must exclude OpenAI", "\"openai\"" in settingsContracts)
        assertFalse(
            "generic Web Search must not own an OpenAI Responses request",
            "\"openai\" -> HttpClient.post(" in webSearchProvider,
        )
        assertFalse(
            "standalone OpenAI must not be selectable in Web Search settings",
            "web_search_openai" in settingsPage,
        )
        assertTrue(
            "DuckDuckGo must be the first Web Search provider",
            "val providers = listOf(\n" +
                "                        \"duckduckgo\" to R.string.web_search_duckduckgo,\n" +
                "                        \"brave\" to R.string.web_search_brave," in settingsPage,
        )
    }

    @Test
    fun `fork menu label is punctuation free without changing confirmation title`() {
        val root = locateMainSourceRoot()
        val topBar = File(root, "com/newoether/agora/ui/chat/ChatTopBar.kt").readText()
        val dialogs = File(root, "com/newoether/agora/ui/chat/ChatDialogs.kt").readText()
        val resourceRoot = File(requireNotNull(root.parentFile), "res")
        val localizedMenus = resourceRoot.listFiles()
            .orEmpty()
            .filter { it.isDirectory && it.name.startsWith("values") }
            .map { File(it, "strings.xml") }
            .filter(File::isFile)
            .mapNotNull { file ->
                Regex("""<string name="conversation_fork_menu">([^<]+)</string>""")
                    .find(file.readText())
                    ?.groupValues
                    ?.get(1)
            }

        assertTrue("top bar must use the dedicated menu label", "conversation_fork_menu" in topBar)
        assertTrue("confirmation dialog must keep its question title", "conversation_fork" in dialogs)
        assertTrue("every existing locale must define the menu label", localizedMenus.size >= 12)
        assertTrue(
            "menu labels must not contain question punctuation",
            localizedMenus.all { label ->
                '?' !in label && '؟' !in label && '？' !in label
            },
        )
    }

    @Test
    fun `top bar reserves fixed trailing actions without fixing title capsule`() {
        val root = locateMainSourceRoot()
        val topBar = File(root, "com/newoether/agora/ui/chat/ChatTopBar.kt")
            .readText()
            .replace("\r\n", "\n")
        val normalBar = topBar
            .substringAfter("// Reserve the trailing capsule first")
            .substringBefore("@Composable\nprivate fun ChatTopBarCapsule")

        assertTrue("title host must own only the remaining width", ".weight(1f)" in normalBar)
        assertTrue(
            "title capsule must remain adaptive with its existing maximum",
            "Modifier.fillMaxHeight().widthIn(max = 260.dp)" in normalBar,
        )
        assertTrue(
            "actions capsule must have a fixed leading gap",
            "Spacer(modifier = Modifier.width(16.dp))" in normalBar,
        )
        assertTrue(
            "actions capsule must retain its full natural width",
            ".fillMaxHeight()\n                        .width(98.dp)" in normalBar,
        )
        assertFalse(
            "the old flexible sibling spacer allowed the title to compress the actions capsule",
            "Spacer(modifier = Modifier.weight(1f))" in normalBar,
        )
        assertFalse("title capsule must not become fixed width", ".width(260.dp)" in normalBar)
    }

    private fun locateMainSourceRoot(): File {
        var directory = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        repeat(8) {
            listOf(
                File(directory, "app/src/main/java"),
                File(directory, "src/main/java"),
            ).firstOrNull(File::isDirectory)?.let { return it }
            directory = directory.parentFile ?: error("Reached filesystem root")
        }
        error("Unable to locate the main Java source directory")
    }
}
