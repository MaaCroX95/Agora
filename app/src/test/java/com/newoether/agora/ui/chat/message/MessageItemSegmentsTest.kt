package com.newoether.agora.ui.chat.message

import androidx.compose.ui.unit.dp
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import com.newoether.agora.model.ThinkingSegmentDisplayModes
import com.newoether.agora.model.ToolCallDisplayModes

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageItemSegmentsTest {

    @Test
    fun toolOnlySegmentsDoNotUseMessageThoughtDuration() {
        val segments = listOf(
            MessageSegment(type = "tool", toolName = "web_search", toolResult = "{}"),
        )

        assertEquals(null, thoughtDurationMs(segments, fallbackMs = 4_000L))
    }

    @Test
    fun uiSegmentPreparationHidesOnlyExactGeminiGoogleSearchTool() {
        val merged = mergeAdjacentSegments(
            listOf(
                MessageSegment(type = "answer", content = "Answer"),
                MessageSegment(type = "tool", toolName = "google_search", toolResult = "{}"),
                MessageSegment(type = "tool", toolName = "web_search", toolResult = "{}"),
                MessageSegment(type = "tool", toolName = "openai_search", toolResult = "{}"),
                MessageSegment(type = "tool", toolName = "code_execution", toolResult = "{}"),
                MessageSegment(type = "tool", toolName = "Google_Search", toolResult = "{}"),
            ),
        )

        assertEquals(
            listOf("web_search", "openai_search", "code_execution", "Google_Search"),
            merged.filter { it.type == "tool" }.map { it.toolName },
        )
        assertEquals("Answer", merged.first().content)
    }

    @Test
    fun realThoughtSegmentMayUseMessageThoughtDuration() {
        val segments = listOf(
            MessageSegment(type = "thought", content = "Reasoning"),
            MessageSegment(type = "tool", toolName = "web_search", toolResult = "{}"),
        )

        assertEquals(4_000L, thoughtDurationMs(segments, fallbackMs = 4_000L))
    }

    @Test
    fun persistedThoughtSegmentDurationWinsOverMessageFallback() {
        val segments = listOf(
            MessageSegment(type = "thought", content = "Reasoning", durationMs = 1_500L),
            MessageSegment(type = "thought", content = "More", durationMs = 500L),
        )

        assertEquals(2_000L, thoughtDurationMs(segments, fallbackMs = 4_000L))
    }

    @Test
    fun timelineInfoBlockUsesCompactTopSpacingWithoutVisibleMessageAbove() {
        assertEquals(0.dp, timelineInfoTopPaddingExtra(false))
    }

    @Test
    fun timelineInfoBlockUsesNormalSeparationAfterVisibleMessage() {
        assertEquals(8.dp, timelineInfoTopPaddingExtra(true))
    }

    @Test
    fun settingsStyleSegmentGroupPositionsCoverSingleFirstMiddleAndLast() {
        assertEquals(
            SegmentGroupPosition.SINGLE,
            segmentGroupPosition(hasPrevious = false, hasNext = false),
        )
        assertEquals(
            SegmentGroupPosition.FIRST,
            segmentGroupPosition(hasPrevious = false, hasNext = true),
        )
        assertEquals(
            SegmentGroupPosition.MIDDLE,
            segmentGroupPosition(hasPrevious = true, hasNext = true),
        )
        assertEquals(
            SegmentGroupPosition.LAST,
            segmentGroupPosition(hasPrevious = true, hasNext = false),
        )
    }

    @Test
    fun timelineSegmentGroupsBreakAtVisibleAnswers() {
        val segments = listOf(
            MessageSegment(type = "thought", content = "Thinking"),
            MessageSegment(type = "tool"),
            MessageSegment(type = "answer", content = "Answer"),
            MessageSegment(type = "transcription"),
        )

        assertEquals(SegmentGroupPosition.FIRST, timelineSegmentGroupPosition(segments, 0))
        assertEquals(SegmentGroupPosition.LAST, timelineSegmentGroupPosition(segments, 1))
        assertEquals(SegmentGroupPosition.SINGLE, timelineSegmentGroupPosition(segments, 3))
    }

    @Test
    fun timelineSegmentGroupsFollowRenderedOrderAcrossSkippedSegments() {
        val segments = listOf(
            MessageSegment(type = "error", content = "Earlier error"),
            MessageSegment(type = "thought", content = "Thinking"),
            MessageSegment(type = "answer"),
            MessageSegment(type = "error", content = "Skipped error"),
            MessageSegment(type = "tool"),
            MessageSegment(type = "answer"),
            MessageSegment(type = "transcription"),
            MessageSegment(type = "error", content = "Later error"),
        )

        assertEquals(SegmentGroupPosition.FIRST, timelineSegmentGroupPosition(segments, 1))
        assertEquals(SegmentGroupPosition.MIDDLE, timelineSegmentGroupPosition(segments, 4))
        assertEquals(SegmentGroupPosition.LAST, timelineSegmentGroupPosition(segments, 6))
    }

    @Test
    fun timelineSegmentGroupInvalidIndicesFailClosed() {
        val segments = listOf(MessageSegment(type = "tool"))

        assertEquals(SegmentGroupPosition.SINGLE, timelineSegmentGroupPosition(segments, -1))
        assertEquals(SegmentGroupPosition.SINGLE, timelineSegmentGroupPosition(segments, 1))
    }

    @Test
    fun thinkingSegmentDisplayPolicyCoversVisibilityEffectiveModeAndAutoExpansion() {
        assertFalse(ThinkingSegmentDisplayModes.isAvailableFor(ToolCallDisplayModes.TIMELINE))
        assertTrue(ThinkingSegmentDisplayModes.isAvailableFor(ToolCallDisplayModes.GROUPED_TIMELINE))
        assertTrue(ThinkingSegmentDisplayModes.isAvailableFor(ToolCallDisplayModes.COMPACT))
        assertEquals(
            ThinkingSegmentDisplayModes.CARD,
            ThinkingSegmentDisplayModes.effectiveMode(
                ThinkingSegmentDisplayModes.BOTTOM_SHEET,
                ToolCallDisplayModes.TIMELINE,
            ),
        )
        assertEquals(
            ThinkingSegmentDisplayModes.BOTTOM_SHEET,
            ThinkingSegmentDisplayModes.effectiveMode(
                ThinkingSegmentDisplayModes.BOTTOM_SHEET,
                ToolCallDisplayModes.GROUPED_TIMELINE,
            ),
        )
        assertTrue(
            ThinkingSegmentDisplayModes.allowsAutoExpand(
                ThinkingSegmentDisplayModes.CARD,
                ToolCallDisplayModes.GROUPED_TIMELINE,
            ),
        )
        assertFalse(
            ThinkingSegmentDisplayModes.allowsAutoExpand(
                ThinkingSegmentDisplayModes.BOTTOM_SHEET,
                ToolCallDisplayModes.GROUPED_TIMELINE,
            ),
        )
        assertFalse(
            ThinkingSegmentDisplayModes.allowsAutoExpand(
                ThinkingSegmentDisplayModes.CARD,
                ToolCallDisplayModes.COMPACT,
            ),
        )
    }

    @Test
    fun reducedMotionRetainsExpandedLayoutUntilCollapseFadeSettles() {
        assertTrue(
            retainExpandedLayoutDuringFade(
                currentExpanded = true,
                targetExpanded = false,
            )
        )
        assertFalse(
            retainExpandedLayoutDuringFade(
                currentExpanded = false,
                targetExpanded = false,
            )
        )
    }

    @Test
    fun reducedMotionReservesExpandedLayoutAsExpansionFadeStarts() {
        assertTrue(
            retainExpandedLayoutDuringFade(
                currentExpanded = false,
                targetExpanded = true,
            )
        )
    }

    @Test
    fun streamingSegmentAnimatesOnlyOnItsFirstSessionAppearance() {
        val registry = SegmentAppearanceRegistry()
        val key = "message:timeline:0"

        assertTrue(registry.shouldAnimate(key, isStreaming = true))
        registry.markSeen(key)
        assertFalse(registry.shouldAnimate(key, isStreaming = true))
    }

    @Test
    fun streamingFadeTimelineSurvivesLazyItemDisposal() {
        val registry = SegmentAppearanceRegistry()
        val key = "message:answer"
        val first = registry.streamingFadeTracker(key)
        first.update(
            text = "old",
            nowMs = 1_000L,
        )

        val recreated = registry.streamingFadeTracker(key)
        val sample = recreated.update(
            text = "oldnew",
            nowMs = 1_200L,
        )

        assertSame(first, recreated)
        assertArrayEquals(
            longArrayOf(1_000L, 1_000L, 1_000L, 1_200L, 1_200L, 1_200L),
            sample.birthTimesMs,
        )
    }

    @Test
    fun historicalSegmentNeverReplaysAnEntrance() {
        val registry = SegmentAppearanceRegistry()

        assertFalse(
            registry.shouldAnimate(
                key = "message:timeline:0",
                isStreaming = false,
            )
        )
    }

    @Test
    fun segmentContainerAndCardBodyHaveIndependentFirstAppearances() {
        val registry = SegmentAppearanceRegistry()
        val segmentKey = "message:timeline:0"
        val cardKey = "$segmentKey:card"

        registry.markSeen(segmentKey)

        assertFalse(registry.shouldAnimate(segmentKey, isStreaming = true))
        assertTrue(registry.shouldAnimate(cardKey, isStreaming = true))
    }

    @Test
    fun everyStreamingSegmentTypeGetsOneFirstAppearance() {
        val registry = SegmentAppearanceRegistry()

        listOf("answer", "thought", "tool", "transcription").forEachIndexed { index, type ->
            val key = segmentAppearanceKey(
                messageId = "message",
                mergedIndex = index,
                segment = MessageSegment(type = type),
            )
            assertTrue("$type must animate when first inserted", registry.shouldAnimate(key, true))
            registry.markSeen(key)
            assertFalse("$type must not replay while updating", registry.shouldAnimate(key, true))
        }
    }

    @Test
    fun segmentAppearanceIdentityIgnoresStreamingPayloadGrowth() {
        val partial = MessageSegment(
            type = "tool",
            toolName = "shell",
            toolArgs = """{"command":"cp"}""",
        )
        val complete = partial.copy(
            toolArgs = """{"command":"cp source destination"}""",
            toolResult = "done",
        )

        assertEquals(
            segmentAppearanceKey("message", 2, partial),
            segmentAppearanceKey("message", 2, complete),
        )
        assertNotEquals(
            segmentAppearanceKey("message", 2, partial),
            segmentAppearanceKey("message", 3, partial),
        )
    }

    @Test
    fun detailAndContainerIdentitiesIgnoreStreamingPayloadGrowth() {
        val partial = MessageSegment(
            type = "tool",
            toolName = "shell",
            toolArgs = """{"command":"cp"}""",
        )
        val complete = partial.copy(
            toolArgs = """{"command":"cp source destination"}""",
            toolResult = "done",
        )

        assertEquals(
            detailSegmentAppearanceKey("message", 1, partial),
            detailSegmentAppearanceKey("message", 1, complete),
        )
        assertEquals("message:compact", compactSegmentBlockAppearanceKey("message"))
        assertEquals(
            "message:group:1",
            groupedSegmentBlockAppearanceKey("message", 1),
        )
    }

    @Test
    fun activeGroupedSegmentExpandsOnlyOnce() {
        val controller = GroupedSegmentAutoExpansionController()
        val key = "message:group:0"

        assertEquals(
            GroupedSegmentAutoExpansionAction.EXPAND,
            controller.update(key, isActive = true, enabled = true),
        )
        assertEquals(
            GroupedSegmentAutoExpansionAction.NONE,
            controller.update(key, isActive = true, enabled = true),
        )
    }

    @Test
    fun groupedSegmentCollapsesOnceWhenItStopsBeingActive() {
        val controller = GroupedSegmentAutoExpansionController()
        val key = "message:group:0"

        controller.update(key, isActive = true, enabled = true)

        assertEquals(
            GroupedSegmentAutoExpansionAction.COLLAPSE,
            controller.update(key, isActive = false, enabled = true),
        )
        assertEquals(
            GroupedSegmentAutoExpansionAction.NONE,
            controller.update(key, isActive = false, enabled = true),
        )
    }

    @Test
    fun stoppedToolMessageRequestsOneExistingCollapse() {
        val segments = listOf(
            MessageSegment(
                type = "tool",
                toolName = "shell",
                toolArgs = "{}",
                toolCallId = "call",
            ),
        )
        val active = ChatMessage(
            id = "message",
            text = "",
            participant = Participant.MODEL,
            status = MessageStatus.TOOL_CALLING,
            segments = segments,
        )
        val stopped = active.copy(status = MessageStatus.STOPPED)
        val controller = GroupedSegmentAutoExpansionController()
        val key = "message:group:0"

        val activeContent = compactSegmentHasActiveContent(
            segs = segments,
            message = active,
            useLiveStatus = true,
        )
        val stoppedContent = compactSegmentHasActiveContent(
            segs = segments,
            message = stopped,
            useLiveStatus = true,
        )
        assertTrue(activeContent)
        assertFalse(stoppedContent)
        assertEquals(
            GroupedSegmentAutoExpansionAction.EXPAND,
            controller.update(key, isActive = activeContent, enabled = true),
        )
        assertEquals(
            GroupedSegmentAutoExpansionAction.COLLAPSE,
            controller.update(key, isActive = stoppedContent, enabled = true),
        )
        assertEquals(
            GroupedSegmentAutoExpansionAction.NONE,
            controller.update(key, isActive = stoppedContent, enabled = true),
        )
    }

    @Test
    fun historicalGroupedSegmentNeverAutoExpands() {
        val controller = GroupedSegmentAutoExpansionController()
        val key = "message:group:0"

        assertEquals(
            GroupedSegmentAutoExpansionAction.NONE,
            controller.update(key, isActive = false, enabled = true),
        )
        assertEquals(
            GroupedSegmentAutoExpansionAction.NONE,
            controller.update(key, isActive = true, enabled = true),
        )
    }

    @Test
    fun enablingAutomationWhileAGroupIsActiveCanExpandIt() {
        val controller = GroupedSegmentAutoExpansionController()
        val key = "message:group:0"

        assertEquals(
            GroupedSegmentAutoExpansionAction.NONE,
            controller.update(key, isActive = true, enabled = false),
        )
        assertEquals(
            GroupedSegmentAutoExpansionAction.EXPAND,
            controller.update(key, isActive = true, enabled = true),
        )
    }

    @Test
    fun timelineAnswerSlicesKeepOnlyTheirOwnGlobalMatchKeys() {
        val first = "needle"
        val second = "xx needle yy"
        val firstRange = 0 until first.length
        val secondRange = (first.length + 3) until (first.length + 9)
        val spec = SearchHighlightSpec(
            query = "needle",
            activeRange = secondRange,
            activeKey = "message:${secondRange.first}:${secondRange.last + 1}",
            matchKeys = listOf(
                "message:${firstRange.first}:${firstRange.last + 1}",
                "message:${secondRange.first}:${secondRange.last + 1}",
            ),
            sourceRanges = listOf(firstRange, secondRange),
            onMatchPosition = { _, _, _ -> },
        )

        val firstSlice = spec.forSourceSlice(sliceStart = 0, sliceLength = first.length)
        val secondSlice = spec.forSourceSlice(
            sliceStart = first.length,
            sliceLength = second.length,
        )

        assertEquals(listOf("message:0:6"), firstSlice.matchKeys)
        assertEquals(listOf(0 until 6), firstSlice.sourceRanges)
        assertEquals(null, firstSlice.activeKey)
        assertEquals(listOf("message:9:15"), secondSlice.matchKeys)
        assertEquals(listOf(3 until 9), secondSlice.sourceRanges)
        assertEquals(3 until 9, secondSlice.activeRange)
        assertEquals("message:9:15", secondSlice.activeKey)
    }

    @Test
    fun legacyFailedRowWithAnswerSegmentsDoesNotRenderItsAnswerAsTheErrorDetail() {
        val message = ChatMessage(
            text = "Generated answer",
            status = MessageStatus.ERROR,
            participant = Participant.MODEL,
            segments = listOf(MessageSegment(type = "answer", content = "Generated answer")),
        )

        assertEquals(
            AssistantErrorContent(
                answerText = "Generated answer",
                errorText = "Failed to generate",
            ),
            assistantErrorContent(message, message.segments.orEmpty(), "Failed to generate"),
        )
    }

    @Test
    fun explicitTerminalErrorIsIndependentFromGeneratedAnswer() {
        val segments = listOf(
            MessageSegment(type = "answer", content = "Generated answer"),
            MessageSegment(type = "error", content = "Stream ended unexpectedly"),
        )
        val message = ChatMessage(
            text = "Generated answer",
            status = MessageStatus.ERROR,
            participant = Participant.MODEL,
            segments = segments,
        )

        assertEquals(
            AssistantErrorContent(
                answerText = "Generated answer",
                errorText = "Stream ended unexpectedly",
            ),
            assistantErrorContent(message, segments, "Failed to generate"),
        )
    }
}
