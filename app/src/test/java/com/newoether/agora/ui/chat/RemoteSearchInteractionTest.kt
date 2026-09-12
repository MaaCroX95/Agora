package com.newoether.agora.ui.chat

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.Participant
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class RemoteSearchInteractionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun progressiveCountRetainsSelectionAndDoesNotRestartOnHydrationRetry() {
        val current = ConversationSearchMatch("current", 0, 6, 0)
        val older = ConversationSearchMatch("older", 0, 6, 0)
        val matches = MutableStateFlow(listOf(current))
        var hydrationRetry by mutableIntStateOf(0)
        var hydrationEnabled by androidx.compose.runtime.mutableStateOf(true)
        var searches = 0
        lateinit var interaction: ConversationInteractionProjection
        compose.setContent {
            val messages = remember { mutableStateOf(listOf(
                ChatMessage(id = "current", text = "needle", participant = Participant.USER),
            )) }
            val loader: suspend (String, List<String>) -> List<ChatMessage> = remember(hydrationRetry) {
                { _, _ -> error("Unused local loader must never run for Remote") }
            }
            val all = remember(hydrationEnabled) {
                { _: String ->
                    searches++
                    if (hydrationEnabled) matches else kotlinx.coroutines.flow.emptyFlow()
                }
            }
            interaction = rememberConversationInteractionState("owner", messages,
                rememberLazyListState(), loader, all)
            Text("${interaction.searchMatchIndex + 1}/${interaction.searchMatches.size}")
        }
        compose.runOnIdle {
            interaction.activateSearch()
            interaction.updateSearchQuery("needle")
            interaction.recordSearchMatchDistance(current.key, 0f)
        }
        compose.waitForIdle()
        compose.onNodeWithText("1/1").assertExists()
        compose.runOnIdle { matches.value = listOf(older, current) }
        compose.waitForIdle()
        compose.onNodeWithText("2/2").assertExists()
        compose.runOnIdle { hydrationRetry++ }
        compose.waitForIdle()
        compose.onNodeWithText("2/2").assertExists()
        compose.runOnIdle { assertEquals(1, searches) }
        compose.runOnIdle { hydrationEnabled = false }
        compose.waitForIdle()
        compose.runOnIdle { hydrationEnabled = true }
        compose.waitForIdle()
        compose.onNodeWithText("2/2").assertExists()
        compose.runOnIdle { assertEquals(3, searches) }
    }
}
