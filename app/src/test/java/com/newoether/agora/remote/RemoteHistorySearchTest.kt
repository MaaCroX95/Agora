package com.newoether.agora.remote

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class RemoteHistorySearchTest {
    private fun page(index: Int, message: RemoteMessage = RemoteMessage("m-$index", "t-$index", null,
        "assistant", "needle $index", index.toLong())) = RemoteConversationPage(
        listOf(message), if (index == 0) null else "page-${index - 1}", emptyList(), pageCursor = "page-$index",
    )

    @Test fun fullHistorySearchKeepsNativeOrderAndCanReopenTheMatchWithEveryNewerPageReachable() = runTest {
        val search = RemoteHistorySearch()
        val reads = mutableListOf<Int>()
        val matches = search.find("needle") { cursor ->
            val index = cursor?.substringAfter("page-")?.toInt() ?: 99
            reads += index
            page(index)
        }
        assertEquals((99 downTo 0).toList(), reads)
        assertEquals((0..99).map { "m-$it" }, matches.map { it.messageId })
        val target = search.target(matches.first())!!
        assertEquals("page-0", target.cursor)
        assertEquals(listOf("page-1"), target.adjacent)
        assertEquals((2..99).map { "page-$it" }, target.newer)
        val window = RemoteHistoryWindow(maxPages = 3)
        window.select(listOf(page(0), page(1)), target.newer)
        while (window.hasNewer) {
            val cursor = window.newerCursor!!
            window.append(cursor, page(cursor.substringAfter("page-").toInt()))
        }
        assertEquals("m-99", window.messages.last().id)
        search.clear()
        assertNull(search.target(matches.first()))
    }

    @Test fun splitTextSearchFindsBoundaryMatchesOnceAndReplaysTheirOriginalOffsets() = runTest {
        val first = RemoteMessage("native", "turn", null, "assistant", "head\nnee", 1,
            groupId = "group", nativeId = "native", textContinues = true)
        val last = first.copy(id = "part", text = "dle end", textOffset = first.text.length, textContinues = false)
        val pages = listOf(page(0, first), page(1, last))
        val search = RemoteHistorySearch()
        val hits = search.find("needle") { cursor -> pages[if (cursor == null) 1 else cursor.substringAfter("page-").toInt()] }
        assertEquals(1, hits.size)
        assertEquals(first.text.indexOf("nee"), hits.single().start)
        val target = search.target(hits.single())!!
        assertEquals("page-0", target.cursor)
        assertEquals(listOf("page-1"), target.adjacent)
        val text = projectRemoteMessages(listOf(first, last)).single().text
        assertEquals("needle", text.substring(hits.single().start, hits.single().endExclusive))
    }

    @Test fun repeatedNativeGroupAcrossPagesHasDistinctSearchLocations() = runTest {
        val search = RemoteHistorySearch()
        val hits = search.find("needle") { cursor ->
            val index = cursor?.substringAfter("page-")?.toInt() ?: 5
            page(index).let { it.copy(messages = it.messages.map { m -> m.copy(turnId = "turn", groupId = "group") }) }
        }
        assertEquals(6, hits.size)
        assertEquals(6, hits.map { it.key }.toSet().size)
    }
}
