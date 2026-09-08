package com.newoether.agora.remote

import org.junit.Assert.*
import org.junit.Test

class RemoteHistoryWindowTest {
    private fun page(index: Int, text: String = "x".repeat(16000)) = RemoteConversationPage(
        listOf(RemoteMessage("message-$index", "turn-$index", null, "assistant", text, index.toLong())),
        if (index == 0) null else "page-${index - 1}", emptyList(), pageCursor = "page-$index",
    )

    @Test fun historyLargerThanTheOldLimitRemainsReadableInBothDirectionsWithBoundedPayloads() {
        val window = RemoteHistoryWindow(maxBytes = 100000, maxPages = 4)
        window.latest(listOf(page(1024)))
        for (index in 1023 downTo 0) {
            assertEquals("page-$index", window.olderCursor)
            window.prepend(page(index), "page-$index")
            assertTrue(window.weightBytes <= 100000)
            assertEquals("message-$index", window.messages.first().id)
        }
        assertNull(window.olderCursor)
        val forward = window.messages.map { it.id }.toMutableSet()
        while (window.hasNewer) {
            val cursor = window.newerCursor!!
            window.append(cursor, page(cursor.substringAfter("page-").toInt()))
            forward += window.messages.map { it.id }
            assertTrue(window.weightBytes <= 100000)
        }
        assertEquals((0..1024).map { "message-$it" }.toSet(), forward)
        assertEquals("message-1024", window.messages.last().id)
    }

    @Test fun incomingGenerationCannotReplaceTheHistoryWindowTheUserIsReading() {
        val window = RemoteHistoryWindow(maxPages = 2)
        window.latest(listOf(page(5)))
        window.prepend(page(4), "page-4")
        window.prepend(page(3), "page-3")
        val before = window.messages
        window.latest(listOf(page(6)))
        assertEquals(before, window.messages)
        window.append(window.newerCursor, page(5))
        window.latest(listOf(page(6), page(5)))
        assertEquals(listOf("message-5", "message-6"), window.messages.map { it.id })
    }

    @Test fun nativeEditsReplaceOnlyTheOverlappingTailAndRepeatedSnapshotsDoNotGrowStorage() {
        val window = RemoteHistoryWindow()
        window.latest(listOf(page(2)))
        window.prepend(page(1), "page-1")
        repeat(100) { window.latest(listOf(page(2, "edited"))) }
        assertEquals(listOf("message-1", "message-2"), window.messages.map { it.id })
        assertEquals("edited", window.messages.last().text)
        window.latest(listOf(page(20)))
        assertEquals(listOf("message-20"), window.messages.map { it.id })
        assertEquals("page-19", window.olderCursor)
    }

    @Test fun missingReplayCursorOnOlderPeersCanStillReturnToTheLiveTail() {
        val window = RemoteHistoryWindow(maxPages = 1)
        window.latest(listOf(page(2).copy(pageCursor = null)))
        window.prepend(page(1), "page-1")
        assertTrue(window.hasNewer)
        assertNull(window.newerCursor)
        window.append(null, page(3))
        assertFalse(window.hasNewer)
        assertEquals("message-3", window.messages.single().id)
    }

    @Test fun transportPartsPreserveInteriorNewlinesUnicodeAndNativeBubbleIdentity() {
        for (role in listOf("user", "assistant")) {
            val first = RemoteMessage("native", "turn", null, role, "First\n", 1,
                nativeId = "native", textContinues = true)
            val last = first.copy(id = "filo-part:native:6", text = "🙂 last\n", textOffset = 6, textContinues = false)
            val projected = projectRemoteMessages(listOf(first, last)).single()
            assertEquals("native", projected.id)
            assertEquals("First\n🙂 last", projected.text)
            if (role == "assistant") assertEquals("First\n🙂 last", projected.segments!!.single().content)
        }
    }
}
