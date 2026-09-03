package com.newoether.agora.viewmodel

import com.newoether.agora.data.local.MessageEntity
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextCompactGraphAnchorTest {
    @Test
    fun persistedCompactTextFreezesRecentMessagesWithoutOpaqueSignatures() {
        val tool = entity("tool_round", "user", Participant.MODEL, 2)
            .toUiChatMessage { it }
            .copy(
                segments = listOf(
                    MessageSegment(
                        type = "tool",
                        toolName = "shell",
                        toolArgs = "{\"command\":\"echo hi\"}",
                        signature = "must-not-leak",
                    ),
                ),
            )
        val result = entity("result_round", tool.id, Participant.USER, 3)
            .toUiChatMessage { it }
            .copy(
                segments = listOf(
                    MessageSegment(
                        type = "tool",
                        toolName = "shell",
                        toolResult = "hi",
                        signature = "must-not-leak",
                    ),
                ),
            )

        val text = buildPersistedCompactText(" summary ", listOf(tool, result))

        assertEquals(
            "summary\n\n--- Recent messages (verbatim) ---\n\n" +
                "[Assistant tool request: shell]\n{\"command\":\"echo hi\"}\n\n" +
                "[Tool result: shell]\nhi",
            text,
        )
        assertFalse(text.contains("must-not-leak"))
    }

    @Test
    fun compactOutputNormalizationStripsCompleteApiWrapper() {
        assertEquals(
            "summary",
            normalizeContextCompactOutput(
                "\n<context_summary>\r\nsummary\r\n</context_summary>\n",
            ),
        )
    }

    @Test
    fun compactOutputNormalizationSuppressesPartialWrapperMarkers() {
        assertEquals("", normalizeContextCompactOutput("<context_sum"))
        assertEquals(
            "summary",
            normalizeContextCompactOutput("<context_summary>\nsummary\n</context_"),
        )
    }

    @Test
    fun compactOutputNormalizationLeavesPlainSummaryUnchanged() {
        val summary = "  plain summary\nwith <xml> content  "
        assertEquals(summary, normalizeContextCompactOutput(summary))
    }

    @Test
    fun automaticSplitExcludesCurrentEmptyPlaceholderButKeepsDurableUserBoundary() {
        val oldUser = entity("old-user", null, Participant.USER, 1).copy(text = "old")
        val oldModel = entity("old-model", "old-user", Participant.MODEL, 2).copy(text = "answer")
        val currentUser = entity("current-user", "old-model", Participant.USER, 3).copy(text = "new")
        val placeholder = entity("placeholder", "current-user", Participant.MODEL, 4).copy(
            status = MessageStatus.SENDING,
        )

        val split = com.newoether.agora.api.util.splitContextForCompactRetention(
            compactSplitMessages(
                listOf(oldUser, oldModel, currentUser, placeholder).map {
                    it.toUiChatMessage { text -> text }
                }
            ),
            retainMessages = 1,
        )

        assertEquals(listOf("old-user", "old-model"), split.prefix.map { it.id })
        assertEquals(listOf("current-user"), split.retained.map { it.id })
    }

    @Test
    fun automaticThresholdUsesCeilingBoundsAndLongArithmetic() {
        assertEquals(1, automaticCompactTokenThreshold(1, 50))
        assertEquals(2_500, automaticCompactTokenThreshold(5_000, 49))
        assertEquals(4_500, automaticCompactTokenThreshold(5_000, 90))
        assertEquals(5_000, automaticCompactTokenThreshold(5_000, 100))
        assertEquals(5_000, automaticCompactTokenThreshold(5_000, 101))
        assertEquals(
            1_932_735_283,
            automaticCompactTokenThreshold(Int.MAX_VALUE, 90),
        )
    }

    @Test
    fun automaticEligibilityDoesNotExposeCompactingBeforeThreshold() {
        val path = listOf(
            entity("old-user", null, Participant.USER, 1).copy(text = "old"),
            entity("old-model", "old-user", Participant.MODEL, 2).copy(text = "answer"),
            entity("current-user", "old-model", Participant.USER, 3).copy(text = "new"),
        ).map { it.toUiChatMessage { text -> text } }

        assertFalse(
            automaticCompactNeeded(
                path = path,
                contextLimit = Int.MAX_VALUE,
                retainLogicalMessages = 1,
            ),
        )
    }

    @Test
    fun automaticEligibilityRequiresARealCompactablePrefix() {
        val path = listOf(
            entity("current-user", null, Participant.USER, 1).copy(text = "new"),
        ).map { it.toUiChatMessage { text -> text } }

        assertFalse(
            automaticCompactNeeded(
                path = path,
                contextLimit = 1,
                retainLogicalMessages = 1,
            ),
        )
    }

    @Test
    fun automaticEligibilityStartsAboveThresholdWhenOlderPrefixExists() {
        val path = listOf(
            entity("old-user", null, Participant.USER, 1).copy(text = "old context"),
            entity("old-model", "old-user", Participant.MODEL, 2).copy(text = "old answer"),
            entity("current-user", "old-model", Participant.USER, 3).copy(text = "new request"),
        ).map { it.toUiChatMessage { text -> text } }

        assertTrue(
            automaticCompactNeeded(
                path = path,
                contextLimit = 1,
                retainLogicalMessages = 1,
            ),
        )
    }

    @Test
    fun automaticEligibilityCountsFrozenUserTemplatesUsedByDispatch() {
        val path = listOf(
            entity("old-user", null, Participant.USER, 1).copy(text = "old"),
            entity("old-model", "old-user", Participant.MODEL, 2).copy(text = "answer"),
            entity("current-user", "old-model", Participant.USER, 3).copy(text = "new"),
        ).map { it.toUiChatMessage { text -> text } }
        val rawUsage = com.newoether.agora.api.util.contextWindowUsage(
            path,
            tokenBudget = Int.MAX_VALUE,
        ).estimatedTokenCount
        val threshold = rawUsage + 1

        assertFalse(
            automaticCompactNeeded(
                path = path,
                contextLimit = threshold,
                retainLogicalMessages = 1,
            ),
        )
        assertTrue(
            automaticCompactNeeded(
                path = path,
                contextLimit = threshold,
                retainLogicalMessages = 1,
                userPrepend = "large provider-visible prefix ".repeat(20),
            ),
        )
    }

    @Test
    fun automaticEligibilityIgnoresHistoryBeforeNearestCompact() {
        val path = listOf(
            entity("old-user", null, Participant.USER, 1).copy(text = "very old context"),
            entity("compact_boundary", "old-user", Participant.MODEL, 2).copy(text = "summary"),
            entity("current-user", "compact_boundary", Participant.USER, 3).copy(text = "new request"),
        ).map { it.toUiChatMessage { text -> text } }

        assertFalse(
            automaticCompactNeeded(
                path = path,
                contextLimit = 1,
                retainLogicalMessages = 1,
            ),
        )
    }

    @Test
    fun automaticEligibilityKeepsCompleteToolRoundInRetainedSuffix() {
        val oldUser = entity("old-user", null, Participant.USER, 1)
            .copy(text = "old context")
            .toUiChatMessage { text -> text }
        val tool = entity("tool_round", "old-user", Participant.MODEL, 2)
            .toUiChatMessage { text -> text }
            .copy(
                segments = listOf(
                    MessageSegment(
                        type = "tool",
                        toolName = "test_tool",
                        toolArgs = "{}",
                        toolCallId = "call-1",
                    )
                )
            )
        val result = entity("result_round", "tool_round", Participant.USER, 3)
            .copy(text = "result")
            .toUiChatMessage { text -> text }
            .copy(
                segments = listOf(
                    MessageSegment(
                        type = "tool",
                        toolName = "test_tool",
                        toolArgs = "{}",
                        toolResult = "result",
                        toolCallId = "call-1",
                    )
                )
            )
        val continuation = entity("continuation", "result_round", Participant.MODEL, 4)
            .copy(text = "answer")
            .toUiChatMessage { text -> text }

        val compactable = compactSplitMessages(listOf(oldUser, tool, result, continuation))
        val split = com.newoether.agora.api.util.splitContextForCompactRetention(
            compactable,
            retainMessages = 2,
        )

        assertEquals(listOf("old-user"), split.prefix.map { it.id })
        assertEquals(
            listOf("tool_round", "result_round", "continuation"),
            split.retained.map { it.id },
        )
        assertTrue(
            automaticCompactNeeded(
                path = listOf(oldUser, tool, result, continuation),
                contextLimit = 1,
                retainLogicalMessages = 1,
            ),
        )
    }

    @Test
    fun automaticEligibilityUsesOnlySelectedConversationBranch() {
        val selectedRoot = entity("selected-root", null, Participant.USER, 1)
            .copy(text = "selected")
        val unselectedRoot = entity("unselected-root", null, Participant.USER, 2)
            .copy(text = "unselected old context")
        val unselectedAnswer = entity("unselected-answer", "unselected-root", Participant.MODEL, 3)
            .copy(text = "unselected answer")
        val unselectedCurrent = entity("unselected-current", "unselected-answer", Participant.USER, 4)
            .copy(text = "unselected new request")

        assertFalse(
            automaticCompactNeeded(
                entities = listOf(
                    selectedRoot,
                    unselectedRoot,
                    unselectedAnswer,
                    unselectedCurrent,
                ),
                selectedChildren = mapOf(null to selectedRoot.id),
                contextLimit = 1,
                retainLogicalMessages = 1,
            ),
        )
    }

    private fun entity(
        id: String,
        parentId: String?,
        participant: Participant,
        sequence: Long,
    ) = MessageEntity(
        id = id,
        conversationId = "conversation",
        parentId = parentId,
        text = "",
        status = MessageStatus.SUCCESS,
        participant = participant,
        timestamp = sequence,
        runId = "run",
        runSequence = sequence,
    )
}
