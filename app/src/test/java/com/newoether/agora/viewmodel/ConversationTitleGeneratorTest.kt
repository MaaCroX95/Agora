package com.newoether.agora.viewmodel

import com.newoether.agora.model.AttachmentItem
import com.newoether.agora.model.AttachmentMeta
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.Participant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationTitleGeneratorTest {
    @Test
    fun initialTitleCollapsesWhitespaceAndFitsThirtyTwoCodePoints() {
        val prompt = "  " + "a".repeat(20) + "\n" + "b".repeat(20) + "  "

        val title = initialConversationTitle(prompt, fallback = "New Chat")

        assertEquals("a".repeat(20) + " " + "b".repeat(10) + "…", title)
        assertEquals(32, title.codePointCount(0, title.length))
    }

    @Test
    fun initialTitleDoesNotSplitSupplementaryCharacters() {
        val emoji = "\uD83D\uDE00"

        val title = initialConversationTitle(emoji.repeat(40), fallback = "New Chat")

        assertEquals(emoji.repeat(31) + "…", title)
        assertEquals(32, title.codePointCount(0, title.length))
    }

    @Test
    fun initialTitleFallsBackForBlankPrompt() {
        assertEquals("New Chat", initialConversationTitle(" \n\t ", fallback = "New Chat"))
    }

    @Test
    fun fallbackTitleCollapsesWhitespaceAndTruncates() {
        val response = "  First line\n\nSecond\tline  " + "x".repeat(80)

        val title = fallbackConversationTitle(response)

        assertEquals(60, title.length)
        assertEquals("First line Second line " + "x".repeat(37), title)
    }

    @Test
    fun fallbackTitleKeepsEmptyResponseEmpty() {
        assertEquals("", fallbackConversationTitle(" \n\t "))
    }

    @Test
    fun attachmentOnlyUserProducesTextualTitleSource() {
        val source = titleSourceText(
            ChatMessage(
                text = "",
                participant = Participant.USER,
                attachmentMeta = AttachmentMeta(
                    items = listOf(
                        AttachmentItem(
                            type = "file",
                            fileName = "requirements.pdf",
                            mimeType = "application/pdf",
                            textContent = "Release constraints",
                        ),
                    ),
                ),
            ),
        )

        assertTrue(source.contains("--- File: requirements.pdf ---"))
        assertTrue(source.contains("Release constraints"))
    }

    @Test
    fun projectedAttachmentTextIsNotDuplicated() {
        val projectedText = "--- File: requirements.pdf ---\nRelease constraints"
        val source = titleSourceText(
            ChatMessage(
                text = projectedText,
                participant = Participant.USER,
                attachmentMeta = AttachmentMeta(
                    items = listOf(
                        AttachmentItem(
                            type = "file",
                            fileName = "requirements.pdf",
                            textContent = "Release constraints",
                        ),
                    ),
                ),
            ),
        )

        assertEquals(projectedText, source)
    }

    @Test
    fun imageOnlyUserProducesStableMetadataTitleSource() {
        val source = titleSourceText(
            ChatMessage(
                text = "",
                participant = Participant.USER,
                attachmentMeta = AttachmentMeta(
                    items = listOf(
                        AttachmentItem(
                            type = "image",
                            fileName = "architecture.png",
                            mimeType = "image/png",
                        ),
                    ),
                ),
            ),
        )

        assertTrue(source.contains("architecture.png"))
        assertTrue(source.contains("image/png"))
    }
}
