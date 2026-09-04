package com.newoether.agora.ui.chat

import com.newoether.agora.TopLevelPresentation
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import com.newoether.agora.viewmodel.ConversationGenerationSnapshot
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnsweringHapticEligibilityTest {
    private val answer = ChatMessage(
        id = "answer",
        text = "hello",
        participant = Participant.MODEL,
        status = MessageStatus.SENDING,
        segments = listOf(MessageSegment(type = "answer", content = "hello")),
    )

    @Test
    fun ordinaryAnswerOnChatIsEligible() {
        assertTrue(answeringHapticEligible(activeSnapshot(), TopLevelPresentation.CHAT))
    }

    @Test
    fun compactAndEveryBlockingPresentationAreIneligible() {
        assertFalse(
            answeringHapticEligible(
                activeSnapshot().copy(
                    streamingMessage = answer.copy(id = "compact_stream"),
                ),
                TopLevelPresentation.CHAT,
            ),
        )
        TopLevelPresentation.entries
            .filterNot { it == TopLevelPresentation.CHAT }
            .forEach { presentation ->
                assertFalse(answeringHapticEligible(activeSnapshot(), presentation))
            }
    }

    @Test
    fun inactiveSnapshotIsIneligible() {
        assertFalse(
            answeringHapticEligible(
                ConversationGenerationSnapshot(),
                TopLevelPresentation.CHAT,
            ),
        )
    }

    @Test
    fun nonAnswerSegmentsAndTerminalMessagesAreIneligible() {
        assertFalse(
            answeringHapticEligible(
                activeSnapshot().copy(
                    streamingMessage = answer.copy(
                        text = "",
                        segments = listOf(MessageSegment(type = "thought", content = "thinking")),
                    ),
                ),
                TopLevelPresentation.CHAT,
            ),
        )
        assertFalse(
            answeringHapticEligible(
                activeSnapshot().copy(
                    streamingMessage = answer.copy(status = MessageStatus.SUCCESS),
                ),
                TopLevelPresentation.CHAT,
            ),
        )
    }

    private fun activeSnapshot() = ConversationGenerationSnapshot(
        conversationId = "conversation",
        streamingMessage = answer,
        isLoading = true,
        isGenerating = true,
    )
}
