package com.newoether.agora.ui.chat

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.SnapshotStateMap
import com.newoether.agora.model.ChatMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withTimeoutOrNull

@Stable
internal class ConversationInteractionState internal constructor(
    initialSearchActive: Boolean = false,
    initialSearchQuery: String = "",
    initialSearchMatchIndex: Int = -1,
) {
    var searchActive by mutableStateOf(initialSearchActive)
        private set
    var searchQuery by mutableStateOf(initialSearchQuery)
        private set
    var searchMatchIndex by mutableIntStateOf(initialSearchMatchIndex)
        private set
    var shareSelectionActive by mutableStateOf(false)
        private set
    var selectedShareMessageIds by mutableStateOf<Set<String>>(emptySet())
        private set

    private fun resetForConversation() {
        searchActive = false
        searchQuery = ""
        searchMatchIndex = -1
        shareSelectionActive = false
        selectedShareMessageIds = emptySet()
    }

    private fun replaceSearchMatchIndex(index: Int) {
        searchMatchIndex = index
    }

    private fun reconcileShareSelection(selectableIds: Set<String>) {
        selectedShareMessageIds = selectedShareMessageIds.intersect(selectableIds)
    }

    internal fun updateSearchQuery(query: String) {
        searchMatchIndex = -1
        searchQuery = query
    }

    internal fun previousSearchMatch(): Boolean {
        if (searchMatchIndex <= 0) return false
        searchMatchIndex -= 1
        return true
    }

    internal fun nextSearchMatch(lastIndex: Int): Boolean {
        if (searchMatchIndex !in 0 until lastIndex) return false
        searchMatchIndex += 1
        return true
    }

    internal fun dismissSearch() {
        searchActive = false
        searchQuery = ""
        searchMatchIndex = -1
    }

    internal fun activateSearch() {
        shareSelectionActive = false
        selectedShareMessageIds = emptySet()
        searchActive = true
    }

    internal fun activateShareSelection() {
        dismissSearch()
        selectedShareMessageIds = emptySet()
        shareSelectionActive = true
    }

    internal fun dismissShareSelection() {
        shareSelectionActive = false
        selectedShareMessageIds = emptySet()
    }

    internal fun toggleShareMessage(messageId: String) {
        selectedShareMessageIds =
            if (messageId in selectedShareMessageIds) {
                selectedShareMessageIds - messageId
            } else {
                selectedShareMessageIds + messageId
            }
    }

    internal fun toggleAllShareMessages(selectableIds: Set<String>) {
        selectedShareMessageIds =
            if (selectableIds.isNotEmpty() && selectedShareMessageIds.containsAll(selectableIds)) {
                emptySet()
            } else {
                selectableIds
            }
    }

    internal fun takeShareSelection(): Set<String> {
        val selection = selectedShareMessageIds
        if (selection.isNotEmpty()) dismissShareSelection()
        return selection
    }

    @Composable
    @OptIn(kotlinx.coroutines.FlowPreview::class)
    internal fun project(
        currentConversationId: String?,
        messages: State<List<ChatMessage>>,
        listState: LazyListState,
        searchMessages: (String, String) -> Flow<List<ChatMessage>>,
    ): ConversationInteractionProjection {
        val selectedPathIds = messages.value.mapTo(linkedSetOf()) { message -> message.id }
        val searchPayloadMessages by remember(
            currentConversationId,
            searchActive,
            searchQuery,
            searchMessages,
        ) {
            if (searchActive && currentConversationId != null && searchQuery.isNotBlank()) {
                searchMessages(currentConversationId, searchQuery)
            } else {
                flowOf(emptyList())
            }
        }.collectAsState(initial = emptyList())
        val messagesForSearch = if (searchActive) {
            searchPayloadMessages.filter { message -> message.id in selectedPathIds }
        } else {
            emptyList()
        }
        val messagesForSelection = if (shareSelectionActive) messages.value else emptyList()
        val selectableShareMessageIds = remember(messagesForSelection) {
            messagesForSelection.mapTo(linkedSetOf()) { it.id }
        }
        val searchMatchDistances = remember(currentConversationId) {
            mutableStateMapOf<String, Float>()
        }
        val searchMatches = remember(messagesForSearch, searchQuery) {
            findConversationSearchMatches(messagesForSearch, searchQuery)
        }
        val searchTurns = remember(messages.value) {
            buildMessageListTurns(messages.value)
        }
        val searchTurnIndexByMessageId = remember(searchTurns) {
            buildMap {
                searchTurns.forEachIndexed { index, turn ->
                    turn.messages.forEach { message -> put(message.id, index) }
                }
            }
        }

        LaunchedEffect(searchActive, searchQuery, searchMatches, currentConversationId) {
            if (!searchActive || searchQuery.isBlank() || searchMatches.isEmpty()) {
                replaceSearchMatchIndex(-1)
                searchMatchDistances.clear()
                return@LaunchedEffect
            }
            searchMatchDistances.clear()
            val visibleDistances = withTimeoutOrNull(250L) {
                snapshotFlow {
                    searchMatchDistances
                        .filterKeys { key -> searchMatches.any { it.key == key } }
                        .toMap()
                }
                    .filter { it.isNotEmpty() }
                    .debounce(32L)
                    .first()
            }.orEmpty()
            val exactVisibleIndex = nearestVisibleConversationSearchMatchIndex(
                searchMatches,
                visibleDistances,
            )
            if (exactVisibleIndex != null) {
                replaceSearchMatchIndex(exactVisibleIndex)
                return@LaunchedEffect
            }
            val layout = listState.layoutInfo
            val viewportCenter = (layout.viewportStartOffset + layout.viewportEndOffset) / 2
            val anchorTurn = layout.visibleItemsInfo
                .minByOrNull { item ->
                    kotlin.math.abs((item.offset + item.size / 2) - viewportCenter)
                }
                ?.index
                ?: listState.firstVisibleItemIndex
            replaceSearchMatchIndex(
                nearestConversationSearchMatchIndex(
                    matches = searchMatches,
                    turnIndexByMessageId = searchTurnIndexByMessageId,
                    anchorTurnIndex = anchorTurn,
                )
            )
        }
        LaunchedEffect(currentConversationId) {
            resetForConversation()
        }
        LaunchedEffect(selectableShareMessageIds) {
            reconcileShareSelection(selectableShareMessageIds)
        }

        return remember(
            this,
            selectableShareMessageIds,
            searchMatchDistances,
            searchMatches,
        ) {
            ConversationInteractionProjection(
                state = this,
                selectableShareMessageIds = selectableShareMessageIds,
                searchMatchDistances = searchMatchDistances,
                searchMatches = searchMatches,
            )
        }
    }

    companion object {
        val Saver = listSaver<ConversationInteractionState, Any>(
            save = { state -> listOf(state.searchActive, state.searchQuery) },
            restore = { restored ->
                ConversationInteractionState(
                    initialSearchActive = restored[0] as Boolean,
                    initialSearchQuery = restored[1] as String,
                )
            },
        )
    }
}

@Stable
internal class ConversationInteractionProjection internal constructor(
    private val state: ConversationInteractionState,
    val selectableShareMessageIds: Set<String>,
    private val searchMatchDistances: SnapshotStateMap<String, Float>,
    val searchMatches: List<ConversationSearchMatch>,
) {
    val searchActive: Boolean get() = state.searchActive
    val searchQuery: String get() = state.searchQuery
    val searchMatchIndex: Int get() = state.searchMatchIndex
    val shareSelectionActive: Boolean get() = state.shareSelectionActive
    val selectedShareMessageIds: Set<String> get() = state.selectedShareMessageIds

    fun updateSearchQuery(query: String) {
        searchMatchDistances.clear()
        state.updateSearchQuery(query)
    }

    fun previousSearchMatch(): Boolean = state.previousSearchMatch()

    fun nextSearchMatch(): Boolean = state.nextSearchMatch(searchMatches.lastIndex)

    fun dismissSearch() = state.dismissSearch()

    fun activateSearch() = state.activateSearch()

    fun activateShareSelection() = state.activateShareSelection()

    fun dismissShareSelection() = state.dismissShareSelection()

    fun toggleShareMessage(messageId: String) = state.toggleShareMessage(messageId)

    fun toggleAllShareMessages() = state.toggleAllShareMessages(selectableShareMessageIds)

    fun takeShareSelection(): Set<String> = state.takeShareSelection()

    fun recordSearchMatchDistance(key: String, distance: Float) {
        searchMatchDistances[key] = distance
    }

}

@Composable
internal fun rememberConversationInteractionState(
    currentConversationId: String?,
    messages: State<List<ChatMessage>>,
    listState: LazyListState,
    searchMessages: (String, String) -> Flow<List<ChatMessage>> = { _, _ -> flowOf(emptyList()) },
): ConversationInteractionProjection {
    val state = rememberSaveable(saver = ConversationInteractionState.Saver) {
        ConversationInteractionState()
    }
    return state.project(
        currentConversationId = currentConversationId,
        messages = messages,
        listState = listState,
        searchMessages = searchMessages,
    )
}
