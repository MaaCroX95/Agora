package com.newoether.agora.ui.chat

import androidx.compose.animation.core.Easing
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlin.math.abs
import kotlin.math.sqrt

internal data class FeedbackScrollStartupSpec(
    val durationMillis: Long,
    val easing: Easing,
) {
    init {
        require(durationMillis > 0L)
    }
}

/**
 * Motion parameters for the frame-driven feedback scroll.
 *
 * Both exact and still-unmeasured targets use the same closed-loop controller. Their separate
 * gains let a distant target travel quickly while retaining the long error-proportional ease-out
 * once its final geometry is known. A caller may add a short [startup] envelope without replacing
 * that shared feedback response.
 */
internal data class FeedbackScrollSpec(
    val measuredTargetTimeConstantSeconds: Float = 0.09f,
    val unmeasuredTargetTimeConstantSeconds: Float = 0.16f,
    val measuredTargetMaximumVelocityViewportsPerSecond: Float = 16f,
    val unmeasuredTargetMaximumVelocityViewportsPerSecond: Float = 52f,
    val maximumFrameStepViewportFraction: Float = 0.82f,
    val startup: FeedbackScrollStartupSpec? = null,
) {
    init {
        require(measuredTargetTimeConstantSeconds > 0f)
        require(unmeasuredTargetTimeConstantSeconds > 0f)
        require(measuredTargetMaximumVelocityViewportsPerSecond > 0f)
        require(unmeasuredTargetMaximumVelocityViewportsPerSecond > 0f)
        require(maximumFrameStepViewportFraction in 0f..1f)
    }
}

internal val DefaultFeedbackScrollSpec = FeedbackScrollSpec()

internal fun physicalEdgeScrollStepPx(
    direction: Float,
    exactErrorPx: Float?,
    targetAdjacent: Boolean,
    previousVelocityPxPerSecond: Float,
    elapsedSeconds: Float,
    viewportSizePx: Float,
    minimumStepPx: Float,
): Float {
    if (
        direction == 0f ||
        elapsedSeconds <= 0f ||
        viewportSizePx <= 0f
    ) {
        return 0f
    }

    val normalizedDirection = if (direction < 0f) -1f else 1f
    val exactError = exactErrorPx?.takeIf(Float::isFinite)
    if (exactError != null && abs(exactError) <= 0.05f) return 0f
    val movementDirection = when {
        exactError == null -> normalizedDirection
        exactError < 0f -> -1f
        else -> 1f
    }

    val maximumVelocity = viewportSizePx * when {
        exactError != null -> 10f
        targetAdjacent -> 5f
        else -> 18f
    }
    val acceleration = viewportSizePx * 28f
    val deceleration = viewportSizePx * 42f
    val desiredSpeed = if (exactError != null) {
        minOf(
            maximumVelocity,
            sqrt(2f * deceleration * abs(exactError)),
        )
    } else {
        maximumVelocity
    }
    val observedSpeed =
        (previousVelocityPxPerSecond * movementDirection).coerceAtLeast(0f)
    val currentSpeed = if (exactError != null || targetAdjacent) {
        minOf(observedSpeed, desiredSpeed)
    } else {
        observedSpeed
    }
    val speedDeltaLimit =
        (if (desiredSpeed >= currentSpeed) acceleration else deceleration) * elapsedSeconds
    val nextSpeed = currentSpeed +
        (desiredSpeed - currentSpeed).coerceIn(-speedDeltaLimit, speedDeltaLimit)
    var step = movementDirection * maxOf(minimumStepPx, nextSpeed * elapsedSeconds)
    if (exactError != null) {
        step = step.coerceIn(
            minOf(0f, exactError),
            maxOf(0f, exactError),
        )
    } else {
        val maximumStepFraction = if (targetAdjacent) 0.12f else 0.28f
        step = step.coerceIn(
            -viewportSizePx * maximumStepFraction,
            viewportSizePx * maximumStepFraction,
        )
    }
    return step
}

internal suspend fun LazyListState.seekToPhysicalEdge(
    toEnd: Boolean,
    animate: Boolean,
    minimumStepPx: Float,
    targetTolerancePx: Float = 1.5f,
    stableFrameCount: Int = 4,
    maximumDurationMillis: Long = 30_000L,
    startup: FeedbackScrollStartupSpec? = null,
    onTargetMeasured: () -> Unit = {},
): Boolean {
    fun isCurrentEdgeReached(): Boolean {
        val layout = layoutInfo
        val layoutMeasured = layout.viewportEndOffset > layout.viewportStartOffset
        if (!layoutMeasured) return false
        if (layout.totalItemsCount <= 0) return true
        val targetIndex = if (toEnd) layout.totalItemsCount - 1 else 0
        val target = layout.visibleItemsInfo.firstOrNull { item -> item.index == targetIndex }
            ?: return false
        if (!canScrollBackward && !canScrollForward) return true
        val exactErrorPx = if (toEnd) {
            val contentEnd = layout.viewportEndOffset - layout.afterContentPadding
            (target.offset + target.size - contentEnd).toFloat()
        } else {
            val contentStart = layout.viewportStartOffset + layout.beforeContentPadding
            (target.offset - contentStart).toFloat()
        }
        val atBoundary = if (toEnd) !canScrollForward else !canScrollBackward
        return atBoundary && abs(exactErrorPx) <= targetTolerancePx
    }

    if (!animate) {
        val startedAtNanos = withFrameNanos { it }
        while (currentCoroutineContext().isActive) {
            val frameNanos = withFrameNanos { it }
            if ((frameNanos - startedAtNanos) / 1_000_000L >= maximumDurationMillis) return false
            val layout = layoutInfo
            if (layout.viewportEndOffset <= layout.viewportStartOffset) continue
            val targetIndex = if (toEnd) layout.totalItemsCount - 1 else 0
            if (targetIndex < 0) return true
            scrollToItem(targetIndex)
            break
        }
        var stableFrames = 0
        while (currentCoroutineContext().isActive) {
            val frameNanos = withFrameNanos { it }
            if ((frameNanos - startedAtNanos) / 1_000_000L >= maximumDurationMillis) break
            stableFrames = if (isCurrentEdgeReached()) stableFrames + 1 else 0
            if (stableFrames >= stableFrameCount) return true
        }
        return false
    }

    var reached = false
    scroll(MutatePriority.Default) {
        var previousFrameNanos = withFrameNanos { it }
        val startedAtNanos = previousFrameNanos
        var velocityPxPerSecond = 0f
        var stableFrames = 0
        var blockedFrames = 0

        while (currentCoroutineContext().isActive) {
            val frameNanos = withFrameNanos { it }
            if ((frameNanos - startedAtNanos) / 1_000_000L >= maximumDurationMillis) break
            val elapsedSeconds =
                (frameNanos - previousFrameNanos)
                    .coerceIn(1L, 50_000_000L) / 1_000_000_000f
            previousFrameNanos = frameNanos

            val layout = layoutInfo
            val visibleItems = layout.visibleItemsInfo
            val layoutMeasured = layout.viewportEndOffset > layout.viewportStartOffset
            if (!layoutMeasured) {
                velocityPxPerSecond = 0f
                stableFrames = 0
                continue
            }
            if (layout.totalItemsCount <= 0) {
                velocityPxPerSecond = 0f
                stableFrames += 1
                if (stableFrames >= stableFrameCount) {
                    reached = true
                    break
                }
                continue
            }
            if (visibleItems.isEmpty()) {
                velocityPxPerSecond = 0f
                stableFrames = 0
                continue
            }

            val targetIndex = if (toEnd) layout.totalItemsCount - 1 else 0
            val target = visibleItems.firstOrNull { item -> item.index == targetIndex }
            if (target != null) onTargetMeasured()
            val exactErrorPx = target?.let { item ->
                if (toEnd) {
                    val contentEnd =
                        layout.viewportEndOffset - layout.afterContentPadding
                    (item.offset + item.size - contentEnd).toFloat()
                } else {
                    val contentStart =
                        layout.viewportStartOffset + layout.beforeContentPadding
                    (item.offset - contentStart).toFloat()
                }
            }
            if (isCurrentEdgeReached()) {
                velocityPxPerSecond = 0f
                stableFrames += 1
                if (stableFrames >= stableFrameCount) {
                    reached = true
                    break
                }
                continue
            }
            stableFrames = 0

            val firstVisibleIndex = visibleItems.minOf { item -> item.index }
            val lastVisibleIndex = visibleItems.maxOf { item -> item.index }
            val targetAdjacent = if (toEnd) {
                lastVisibleIndex >= targetIndex - 1
            } else {
                firstVisibleIndex <= 1
            }
            val direction = if (toEnd) 1f else -1f
            val viewportSizePx =
                (layout.viewportEndOffset - layout.viewportStartOffset)
                    .coerceAtLeast(1)
                    .toFloat()
            var requestedStep = physicalEdgeScrollStepPx(
                direction = direction,
                exactErrorPx = exactErrorPx,
                targetAdjacent = targetAdjacent,
                previousVelocityPxPerSecond = velocityPxPerSecond,
                elapsedSeconds = elapsedSeconds,
                viewportSizePx = viewportSizePx,
                minimumStepPx = minimumStepPx,
            )
            requestedStep = applyFeedbackScrollStartup(
                adaptiveStepPx = requestedStep,
                elapsedNanos = frameNanos - startedAtNanos,
                startup = startup,
            )
            if (abs(requestedStep) <= 0.05f) {
                velocityPxPerSecond = 0f
                blockedFrames += 1
            } else {
                val consumed = scrollBy(requestedStep)
                velocityPxPerSecond = consumed / elapsedSeconds
                blockedFrames = if (abs(consumed) <= 0.05f) blockedFrames + 1 else 0
            }
            if (blockedFrames >= 12) break
        }
    }
    return reached
}

/**
 * Applies an optional startup envelope to the feedback controller's output. Once the envelope
 * reaches one, [coalescedScrollStep] is returned unchanged, preserving the controller's existing
 * long adaptive ease-out as the target becomes measured and the remaining error shrinks.
 */
internal fun applyFeedbackScrollStartup(
    adaptiveStepPx: Float,
    elapsedNanos: Long,
    startup: FeedbackScrollStartupSpec?,
): Float {
    if (adaptiveStepPx == 0f || startup == null) return adaptiveStepPx
    val durationNanos = startup.durationMillis * 1_000_000L
    val progress =
        (elapsedNanos.coerceAtLeast(0L).toFloat() /
            durationNanos.toFloat())
            .coerceIn(0f, 1f)
    return adaptiveStepPx * startup.easing.transform(progress).coerceIn(0f, 1f)
}

internal suspend fun LazyListState.animateToAbsoluteTop(
    estimatedItemSizePx: Float,
    minimumStepPx: Float,
    feedbackSpec: FeedbackScrollSpec = DefaultFeedbackScrollSpec,
): Boolean {
    require(estimatedItemSizePx > 0f)
    return seekToPhysicalEdge(
        toEnd = false,
        animate = true,
        minimumStepPx = minimumStepPx,
        startup = feedbackSpec.startup,
    )
}

/**
 * Progressively seeks a LazyColumn item without `animateScrollToItem`.
 *
 * Compose intentionally teleports across very long distances in `animateScrollToItem` to avoid
 * composing every intermediate item. That optimization is correct for programmatic positioning,
 * but it is visible as a final jump in a user-facing search animation. This actor instead owns one
 * scroll mutation, advances by a bounded amount on every display frame, and retargets against the
 * item's measured geometry as soon as it is composed.
 *
 * User input has a higher mutation priority and therefore cancels this actor immediately.
 */
internal suspend fun LazyListState.smoothSeekToItem(
    targetIndex: () -> Int,
    targetErrorPx: (LazyListItemInfo) -> Float?,
    estimatedErrorPx: () -> Float?,
    exactTargetReady: () -> Boolean,
    minimumStepPx: Float,
    targetTolerancePx: Float = 1.5f,
    stableFrameCount: Int = 4,
    maximumDurationMillis: Long = 30_000L,
    feedbackSpec: FeedbackScrollSpec = DefaultFeedbackScrollSpec,
): Boolean {
    var reached = false
    scroll(MutatePriority.Default) {
        var previousFrameNanos = withFrameNanos { it }
        val startedAtNanos = previousFrameNanos
        var stableFrames = 0
        var blockedFrames = 0

        while (currentCoroutineContext().isActive) {
            val frameNanos = withFrameNanos { it }
            if ((frameNanos - startedAtNanos) / 1_000_000L >= maximumDurationMillis) break

            val frameDurationNanos =
                (frameNanos - previousFrameNanos)
                    .coerceIn(1L, 50_000_000L)
            val elapsedSeconds = frameDurationNanos / 1_000_000_000f
            previousFrameNanos = frameNanos

            val layout = layoutInfo
            val itemCount = layout.totalItemsCount
            if (itemCount <= 0 || layout.visibleItemsInfo.isEmpty()) {
                stableFrames = 0
                continue
            }

            val resolvedTargetIndex = targetIndex().coerceIn(0, itemCount - 1)
            val visibleTarget = layout.visibleItemsInfo
                .firstOrNull { item -> item.index == resolvedTargetIndex }
            val viewportSizePx =
                (layout.viewportEndOffset - layout.viewportStartOffset)
                    .coerceAtLeast(1)
                    .toFloat()

            val error = if (visibleTarget != null) {
                targetErrorPx(visibleTarget)
            } else {
                val firstVisible = layout.visibleItemsInfo.minBy { item -> item.index }
                val lastVisible = layout.visibleItemsInfo.maxBy { item -> item.index }
                val direction = when {
                    resolvedTargetIndex < firstVisible.index -> -1f
                    resolvedTargetIndex > lastVisible.index -> 1f
                    else -> if (resolvedTargetIndex < firstVisibleItemIndex) -1f else 1f
                }
                val estimated = estimatedErrorPx()
                    ?.takeIf { value -> value.isFinite() && value != 0f }
                direction * maxOf(
                    abs(estimated ?: 0f),
                    viewportSizePx * 0.75f,
                )
            }

            if (error == null || !error.isFinite()) {
                stableFrames = 0
                continue
            }

            if (
                visibleTarget != null &&
                exactTargetReady() &&
                abs(error) <= targetTolerancePx
            ) {
                stableFrames += 1
                if (stableFrames >= stableFrameCount) {
                    reached = true
                    break
                }
                continue
            }
            stableFrames = 0

            // Far-away content can move quickly, but never by more than 82% of a viewport in one
            // frame. The target-near path is deliberately slower so the final centering visibly
            // decelerates instead of snapping.
            val targetIsMeasured = visibleTarget != null
            val maximumVelocityPxPerSecond = viewportSizePx *
                if (targetIsMeasured) {
                    feedbackSpec.measuredTargetMaximumVelocityViewportsPerSecond
                } else {
                    feedbackSpec.unmeasuredTargetMaximumVelocityViewportsPerSecond
                }
            val adaptiveStep = coalescedScrollStep(
                errorPx = error,
                elapsedSeconds = elapsedSeconds,
                timeConstantSeconds = if (targetIsMeasured) {
                    feedbackSpec.measuredTargetTimeConstantSeconds
                } else {
                    feedbackSpec.unmeasuredTargetTimeConstantSeconds
                },
                maximumVelocityPxPerSecond = maximumVelocityPxPerSecond,
                minimumStepPx = minimumStepPx,
            )
            val step = applyFeedbackScrollStartup(
                adaptiveStepPx = adaptiveStep,
                elapsedNanos = frameNanos - startedAtNanos,
                startup = feedbackSpec.startup,
            ).coerceIn(
                -viewportSizePx * feedbackSpec.maximumFrameStepViewportFraction,
                viewportSizePx * feedbackSpec.maximumFrameStepViewportFraction,
            )

            if (abs(step) <= 0.05f) continue
            val consumed = scrollBy(step)
            blockedFrames = if (abs(consumed) <= 0.05f) blockedFrames + 1 else 0
            // At a physical list boundary the requested center can be impossible (for example,
            // the first match with no content above it). Do not spin forever or introduce a hard
            // corrective jump.
            if (blockedFrames >= 12) {
                reached = visibleTarget != null && abs(error) <= targetTolerancePx * 2f
                break
            }
        }
    }
    return reached
}
