package com.newoether.agora.remote

import com.newoether.agora.ui.chat.ConversationSearchMatch
import com.newoether.agora.ui.chat.findConversationSearchMatches
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Search scans one native page at a time and retains match locations, never all history bodies. */
internal class RemoteHistorySearch {
    private var revision = 0L
    private var cursors = emptyList<String?>()
    private var locations = emptyMap<String, Int>()

    fun clear() { revision++; cursors = emptyList(); locations = emptyMap() }

    suspend fun find(query: String, read: suspend (String?) -> RemoteConversationPage): List<ConversationSearchMatch> {
        val generation = ++revision
        val pages = mutableListOf<String?>()
        val hits = mutableListOf<List<ConversationSearchMatch>>()
        val visited = mutableSetOf<String?>()
        var cursor: String? = null
        var adjacent: RemoteConversationPage? = null
        do {
            currentCoroutineContext().ensureActive()
            require(visited.add(cursor)) { "Filo history cursor did not advance" }
            val page = read(cursor)
            val key = "$generation:${pages.size}"
            pages += page.pageCursor ?: cursor
            val boundary = projectRemoteMessages(page.messages).associate { it.id to it.text.length }
            val messages = (page.messages + adjacent?.messages.orEmpty()).distinctBy { it.id }
            hits += findConversationSearchMatches(projectRemoteMessages(messages), query)
                .filter { it.start < (boundary[it.messageId] ?: 0) }
                .map { it.copy(pageKey = key) }
            adjacent = page
            cursor = page.nextCursor
        } while (cursor != null)
        currentCoroutineContext().ensureActive()
        if (generation != revision) return emptyList()
        cursors = pages
        locations = pages.indices.associate { "$generation:$it" to it }
        return hits.asReversed().flatten()
    }

    data class Target(val cursor: String?, val adjacent: List<String?>, val newer: List<String?>)
    fun target(match: ConversationSearchMatch): Target? {
        val index = locations[match.pageKey] ?: return null
        val newer = cursors.take(index).asReversed()
        return Target(cursors[index], newer.take(1), newer.drop(1))
    }
}
