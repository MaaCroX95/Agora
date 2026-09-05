package com.newoether.agora.ui.chat.message

import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import com.newoether.agora.model.ToolExecutionStates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExperimentalGenerationUiPresentationTest {
    @Test
    fun `inline generation activity only fills the intentional gaps`() {
        assertEquals(
            AssistantInlineActivityMode.EMPTY,
            assistantInlineActivityMode(
                generationActive = true,
                hasAnswer = false,
                hasVisibleInfoSegment = false,
                retryText = null,
            ),
        )
        assertEquals(
            AssistantInlineActivityMode.RETRY,
            assistantInlineActivityMode(
                generationActive = true,
                hasAnswer = true,
                hasVisibleInfoSegment = true,
                retryText = "Retrying 1/5",
            ),
        )
        assertEquals(
            AssistantInlineActivityMode.NONE,
            assistantInlineActivityMode(
                generationActive = true,
                hasAnswer = true,
                hasVisibleInfoSegment = false,
                retryText = null,
            ),
        )
        assertEquals(
            AssistantInlineActivityMode.NONE,
            assistantInlineActivityMode(
                generationActive = false,
                hasAnswer = false,
                hasVisibleInfoSegment = false,
                retryText = "Retrying 1/5",
            ),
        )
    }

    @Test
    fun `stopping hides the inline dot while retaining only its status slot`() {
        val active = assistantInlineActivityPresentation(
            generationActive = true,
            isStopping = false,
            hasAnswer = false,
            hasVisibleInfoSegment = false,
            retryText = null,
        )
        val stopping = assistantInlineActivityPresentation(
            generationActive = true,
            isStopping = true,
            hasAnswer = false,
            hasVisibleInfoSegment = false,
            retryText = null,
        )
        val stopped = assistantInlineActivityPresentation(
            generationActive = false,
            isStopping = false,
            hasAnswer = false,
            hasVisibleInfoSegment = false,
            retryText = null,
        )

        assertEquals(AssistantInlineActivityMode.EMPTY, active.mode)
        assertFalse(active.retainLayout)
        assertEquals(AssistantInlineActivityMode.NONE, stopping.mode)
        assertTrue(stopping.retainLayout)
        assertTrue(stopping.retainLayout && stopping.mode == AssistantInlineActivityMode.NONE)
        assertEquals(AssistantInlineActivityMode.NONE, stopped.mode)
        assertFalse(stopped.retainLayout)
    }

    @Test
    fun `retry reveal respects graphemes bounded timing and directional caret motion`() {
        assertEquals(
            listOf(0, 2, 3),
            retryGraphemeBoundaries("A\u0301B").toList(),
        )
        assertEquals(225, retryRevealDurationMillis(3))
        assertEquals(600, retryRevealDurationMillis(100))
        assertTrue(shouldAnimateRetryEntrance(entranceStarted = false, allowSpatialTransitions = true, graphemeCount = 3))
        assertFalse(shouldAnimateRetryEntrance(entranceStarted = true, allowSpatialTransitions = true, graphemeCount = 3))
        assertFalse(shouldAnimateRetryEntrance(entranceStarted = false, allowSpatialTransitions = false, graphemeCount = 3))
        assertFalse(shouldAnimateRetryEntrance(entranceStarted = false, allowSpatialTransitions = true, graphemeCount = 0))
        assertEquals(0f, retryGraphemeAlpha(progress = 0f, index = 0), 0.001f)
        assertEquals(0.5f, retryGraphemeAlpha(progress = 0.5f, index = 0), 0.001f)
        assertEquals(1f, retryGraphemeAlpha(progress = 1f, index = 0), 0.001f)
        assertEquals(0f, retryGraphemeAlpha(progress = 1f, index = 1), 0.001f)
        assertEquals(
            15f,
            retryCaretPosition(
                progress = 1.5f,
                caretPositions = floatArrayOf(0f, 10f, 20f),
            ),
            0.001f,
        )
        assertEquals(
            5f,
            retryCaretPosition(
                progress = 1.5f,
                caretPositions = floatArrayOf(20f, 10f, 0f),
            ),
            0.001f,
        )
    }

    @Test
    fun `historical assistant rendering reports ready only after every answer parser`() {
        val twoAnswers = AssistantRenderReadiness(setOf(1, 3))
        assertFalse(twoAnswers.claimSynchronousReady())
        assertFalse(twoAnswers.markReady(1))
        assertFalse(twoAnswers.markReady(1))
        assertTrue(twoAnswers.markReady(3))
        assertFalse(twoAnswers.markReady(3))

        val noMarkdown = AssistantRenderReadiness(emptySet())
        assertTrue(noMarkdown.claimSynchronousReady())
        assertFalse(noMarkdown.claimSynchronousReady())
        assertFalse(noMarkdown.markReady(0))
    }

    @Test
    fun `current tail card stays loading throughout active generation`() {
        assertTrue(
            compactSegmentShowsLoading(
                hasActiveContent = false,
                generationActive = true,
                isCurrentCard = true,
            ),
        )
        assertTrue(
            compactSegmentShowsLoading(
                hasActiveContent = true,
                generationActive = false,
                isCurrentCard = false,
            ),
        )
        assertFalse(
            compactSegmentShowsLoading(
                hasActiveContent = false,
                generationActive = true,
                isCurrentCard = false,
            ),
        )
        assertFalse(
            compactSegmentShowsLoading(
                hasActiveContent = false,
                generationActive = false,
                isCurrentCard = true,
            ),
        )
    }

    @Test
    fun `only active generation lets active segments drive card loading`() {
        val activeTool = MessageSegment(
            type = "tool",
            toolState = ToolExecutionStates.RUNNING,
        )
        val backgroundTool = activeTool.copy(
            toolState = ToolExecutionStates.BACKGROUND_RUNNING,
        )
        val finishedTool = activeTool.copy(toolState = ToolExecutionStates.SUCCEEDED)
        val thought = MessageSegment(type = "thought", content = "reasoning")
        val transcription = MessageSegment(type = "transcription", content = "image text")

        assertFalse(
            compactSegmentHasActiveContent(
                segs = listOf(activeTool, backgroundTool),
                message = message(MessageStatus.SUCCESS),
                useLiveStatus = true,
            ),
        )
        assertTrue(
            compactSegmentHasActiveContent(
                segs = listOf(activeTool),
                message = message(MessageStatus.TOOL_CALLING),
                useLiveStatus = true,
            ),
        )
        // A detached background job is not active content — it must not occupy the loading
        // indicator once its tool round ends.
        assertFalse(
            compactSegmentHasActiveContent(
                segs = listOf(backgroundTool),
                message = message(MessageStatus.TOOL_CALLING),
                useLiveStatus = false,
            ),
        )
        assertTrue(
            compactSegmentHasActiveContent(
                segs = listOf(thought),
                message = message(MessageStatus.THINKING),
                useLiveStatus = true,
            ),
        )
        assertTrue(
            compactSegmentHasActiveContent(
                segs = listOf(transcription),
                message = message(MessageStatus.TRANSCRIBING),
                useLiveStatus = true,
            ),
        )
        assertFalse(
            compactSegmentHasActiveContent(
                segs = listOf(finishedTool, thought, transcription),
                message = message(MessageStatus.SUCCESS),
                useLiveStatus = true,
            ),
        )
    }

    private fun message(status: MessageStatus): ChatMessage = ChatMessage(
        text = "",
        status = status,
        participant = Participant.MODEL,
    )
}
