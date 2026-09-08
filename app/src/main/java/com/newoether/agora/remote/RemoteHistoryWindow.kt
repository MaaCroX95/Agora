package com.newoether.agora.remote

/** Only payloads are evicted. Native page bookmarks keep both directions readable. */
internal class RemoteHistoryWindow(
    private val maxBytes: Long = 2L * 1024 * 1024,
    private val maxPages: Int = 12,
) {
    private val pages = mutableListOf<RemoteConversationPage>()
    private val newer = ArrayDeque<String?>()
    val messages: List<RemoteMessage> get() = pages.flatMap { it.messages }.distinctBy { it.id }
    val olderCursor: String? get() = pages.firstOrNull()?.nextCursor
    val newerCursor: String? get() = newer.firstOrNull()
    val hasNewer: Boolean get() = newer.isNotEmpty()
    val weightBytes: Long get() = pages.sumOf { remoteHistoryBytes(it.messages) }
    val bridgePageLimit: Int get() = maxPages

    fun clear() { pages.clear(); newer.clear() }

    fun select(selected: List<RemoteConversationPage>, newerCursors: List<String?>) {
        clear()
        pages += selected
        newer.addAll(newerCursors)
    }

    fun latest(fresh: List<RemoteConversationPage>) {
        if (hasNewer || fresh.isEmpty()) return
        val ordered = fresh.asReversed()
        val boundary = ordered.first().messages.firstOrNull()?.id
        val prefix = mutableListOf<RemoteConversationPage>()
        var found = false
        for (page in pages) {
            val index = page.messages.indexOfFirst { it.id == boundary }
            if (index >= 0) {
                if (index > 0) prefix += page.copy(messages = page.messages.take(index))
                found = true
                break
            }
            prefix += page
        }
        pages.clear()
        if (found) pages += prefix
        pages += ordered
        trim(older = false)
    }

    fun prepend(page: RemoteConversationPage, requestCursor: String) {
        val oldIds = messages.mapTo(HashSet()) { it.id }
        pages.add(0, page.copy(messages = page.messages.filterNot { it.id in oldIds },
            pageCursor = page.pageCursor ?: requestCursor))
        trim(older = true)
    }

    fun append(cursor: String?, page: RemoteConversationPage) {
        if (!hasNewer || cursor != newer.firstOrNull()) return
        newer.removeFirst()
        val oldIds = messages.mapTo(HashSet()) { it.id }
        pages += page.copy(messages = page.messages.filterNot { it.id in oldIds })
        trim(older = false)
    }

    private fun trim(older: Boolean) {
        while (pages.size > 1 && (pages.size > maxPages || weightBytes > maxBytes)) {
            if (older) {
                // An older peer's unanchored first page is re-read at its current native tail.
                val cursor = pages.last().pageCursor
                pages.removeAt(pages.lastIndex)
                newer.addFirst(cursor)
            } else pages.removeAt(0)
        }
    }
}

internal fun remoteHistoryBytes(messages: List<RemoteMessage>): Long = messages.sumOf {
    2L * (it.text.length.toLong() + (it.activity?.arguments?.length ?: 0) +
        (it.activity?.result?.length ?: 0)) + 256L
}
