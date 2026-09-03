package com.newoether.agora.ui.chat.message

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.newoether.agora.ui.motion.LocalAgoraMotionPolicy
import kotlinx.coroutines.delay

internal const val CITATION_TERMINAL_PROJECTION_SIZE_DURATION_MS =
    CITATION_CAPSULE_FADE_DURATION_MS
private const val CITATION_TERMINAL_PROJECTION_SETTLE_FALLBACK_MS =
    CITATION_TERMINAL_PROJECTION_SIZE_DURATION_MS + 160L

internal fun citationProjectionRequiresTerminalHandoff(
    presentedProjection: CitationMarkdownProjection?,
    targetProjection: CitationMarkdownProjection?,
): Boolean = presentedProjection != targetProjection

/**
 * Keeps the currently presented projection mounted until the list anchor is armed, then commits a
 * terminal citation update through one persistent Markdown subtree. Only terminal projection
 * changes are size-animated; ordinary streaming growth continues to measure immediately.
 */
@Composable
internal fun CitationTerminalProjectionHost(
    animationKey: String,
    projection: CitationMarkdownProjection?,
    isStreaming: Boolean,
    onLayoutMutationStarted: (String) -> Unit,
    onLayoutMutationSettled: (String) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (
        projection: CitationMarkdownProjection?,
        isStreaming: Boolean,
    ) -> Unit,
) {
    val allowSpatialTransitions = LocalAgoraMotionPolicy.current.allowSpatialTransitions
    val mutationKey = "$animationKey:terminal-citation-projection"
    val currentLayoutMutationStarted by rememberUpdatedState(onLayoutMutationStarted)
    val currentLayoutMutationSettled by rememberUpdatedState(onLayoutMutationSettled)
    var presentedProjection by remember(animationKey) { mutableStateOf(projection) }
    var animateTerminalSize by remember(animationKey) { mutableStateOf(false) }
    var mutationActive by remember(animationKey) { mutableStateOf(false) }
    var mutationSequence by remember(animationKey) { mutableIntStateOf(0) }
    var completedMutationSequence by remember(animationKey) { mutableIntStateOf(0) }

    SideEffect {
        if (isStreaming) {
            presentedProjection = projection
            animateTerminalSize = false
        }
    }

    LaunchedEffect(animationKey, isStreaming, projection, allowSpatialTransitions) {
        if (isStreaming) {
            if (mutationActive) {
                mutationActive = false
                currentLayoutMutationSettled(mutationKey)
            }
            return@LaunchedEffect
        }
        if (!citationProjectionRequiresTerminalHandoff(presentedProjection, projection)) {
            return@LaunchedEffect
        }

        mutationSequence += 1
        val activeSequence = mutationSequence
        if (!mutationActive) {
            mutationActive = true
            currentLayoutMutationStarted(mutationKey)
        }
        withFrameNanos { }
        animateTerminalSize = allowSpatialTransitions
        presentedProjection = projection

        if (allowSpatialTransitions) {
            delay(CITATION_TERMINAL_PROJECTION_SETTLE_FALLBACK_MS)
            if (mutationActive && mutationSequence == activeSequence) {
                completedMutationSequence = activeSequence
            }
        } else {
            withFrameNanos { }
            withFrameNanos { }
            if (mutationActive && mutationSequence == activeSequence) {
                mutationActive = false
                currentLayoutMutationSettled(mutationKey)
            }
        }
    }

    LaunchedEffect(completedMutationSequence) {
        if (completedMutationSequence == 0) return@LaunchedEffect
        withFrameNanos { }
        withFrameNanos { }
        if (mutationActive && completedMutationSequence == mutationSequence) {
            animateTerminalSize = false
            mutationActive = false
            currentLayoutMutationSettled(mutationKey)
        }
    }

    DisposableEffect(mutationKey) {
        onDispose {
            if (mutationActive) currentLayoutMutationSettled(mutationKey)
        }
    }

    val terminalPending =
        !isStreaming && citationProjectionRequiresTerminalHandoff(presentedProjection, projection)
    val displayedProjection = if (isStreaming) projection else presentedProjection
    val displayedIsStreaming = isStreaming || terminalPending
    Box(
        modifier = modifier.animateContentSize(
            animationSpec = if (animateTerminalSize) {
                tween(
                    durationMillis = CITATION_TERMINAL_PROJECTION_SIZE_DURATION_MS,
                    easing = LinearEasing,
                )
            } else {
                snap()
            },
            alignment = Alignment.TopStart,
            finishedListener = { initialSize, targetSize ->
                if (mutationActive && animateTerminalSize && initialSize != targetSize) {
                    completedMutationSequence = mutationSequence
                }
            },
        ),
    ) {
        content(displayedProjection, displayedIsStreaming)
    }
}
