package com.newoether.agora.ui.chat

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal class HistoricalMessageSettlementGate {
    private var scheduled = false
    private var settled = false

    fun trySchedule(): Boolean {
        if (scheduled || settled) return false
        scheduled = true
        return true
    }

    fun settle(): Boolean {
        if (settled) return false
        settled = true
        return true
    }
}

/**
 * Holds only the visible viewport anchor across payload hydration, Markdown parsing, and the
 * row-level payload crossfade. Conversation visibility is owned by payload readiness and must
 * never wait for Markdown parsing or this settlement job.
 */
@Composable
internal fun rememberHistoricalMessageViewportSettlement(
    messageId: String,
    phase: HistoricalMessageHydrationPhase,
    isStreamingOverlay: Boolean,
    isSwitching: Boolean,
    isScrollInProgress: Boolean,
    state: LazyListState,
    turns: List<MessageListTurn>,
    mutationAnchorLock: MessageListMutationAnchorLock,
    pendingSettlements: MutableMap<String, Job>,
    mutationScope: CoroutineScope,
): () -> Unit {
    val mutationKey = "hydrate:$messageId"
    var hadLoading by remember(messageId) {
        mutableStateOf(phase == HistoricalMessageHydrationPhase.LOADING)
    }
    if (phase == HistoricalMessageHydrationPhase.LOADING) {
        SideEffect { hadLoading = true }
    }
    val currentTurns by rememberUpdatedState(turns)
    val settlementGate = remember(messageId) { HistoricalMessageSettlementGate() }

    fun finishAfterRenderedContent() {
        if (!settlementGate.trySchedule()) return
        val retainCrossfade = hadLoading
        lateinit var settlementJob: Job
        settlementJob = mutationScope.launch {
            try {
                if (retainCrossfade) delay(HISTORICAL_MESSAGE_CROSSFADE_MS.toLong())
                withFrameNanos { }
                withFrameNanos { }
                mutationAnchorLock.finish(mutationKey)?.let { anchor ->
                    state.restoreMessageListViewportAnchor(currentTurns, anchor)
                }
            } finally {
                hadLoading = false
                settlementGate.settle()
                pendingSettlements.remove(mutationKey, settlementJob)
            }
        }
        pendingSettlements.remove(mutationKey)?.cancel()
        pendingSettlements[mutationKey] = settlementJob
    }

    LaunchedEffect(messageId, phase, isStreamingOverlay, isSwitching, isScrollInProgress) {
        if (isStreamingOverlay) {
            settlementGate.settle()
            return@LaunchedEffect
        }
        val layoutStable = messageListLayoutMode(
            isSwitching = isSwitching,
            isScrollInProgress = isScrollInProgress,
        ) == MessageListLayoutMode.STABLE
        if (hadLoading && layoutStable && !mutationAnchorLock.isActive(mutationKey)) {
            mutationAnchorLock.begin(
                key = mutationKey,
                candidate = state.captureMessageListViewportAnchor(currentTurns),
            )?.let { anchor -> state.restoreMessageListViewportAnchor(currentTurns, anchor) }
        }
        if (phase == HistoricalMessageHydrationPhase.FAILED) {
            finishAfterRenderedContent()
        }
    }
    DisposableEffect(mutationKey) {
        onDispose {
            pendingSettlements.remove(mutationKey)?.cancel()
            mutationAnchorLock.finish(mutationKey)
        }
    }
    return ::finishAfterRenderedContent
}
