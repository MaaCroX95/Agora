package com.newoether.agora.ui.chat

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import com.newoether.agora.ui.motion.MotionAwareCircularProgressIndicator as CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.model.ChatMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect

internal const val HISTORICAL_MESSAGE_CROSSFADE_MS = 200
internal enum class HistoricalMessageHydrationPhase {
    LOADING,
    READY,
    FAILED,
}
internal fun historicalMessagePayloadReady(
    phase: HistoricalMessageHydrationPhase,
    streamingOverlay: Boolean,
): Boolean = streamingOverlay || phase == HistoricalMessageHydrationPhase.READY

internal data class HistoricalMessageHydrationState(
    val phase: HistoricalMessageHydrationPhase,
    val message: ChatMessage? = null,
)
internal fun initialHistoricalMessageHydrationState(
    cached: ChatMessage?,
    streamingOverlay: Boolean,
): HistoricalMessageHydrationState = when {
    streamingOverlay -> HistoricalMessageHydrationState(HistoricalMessageHydrationPhase.READY)
    cached != null -> HistoricalMessageHydrationState(
        HistoricalMessageHydrationPhase.READY,
        cached,
    )
    else -> HistoricalMessageHydrationState(HistoricalMessageHydrationPhase.LOADING)
}
internal fun observedHistoricalMessageHydrationState(
    expectedMessageId: String,
    message: ChatMessage?,
): HistoricalMessageHydrationState =
    if (message?.id == expectedMessageId) {
        HistoricalMessageHydrationState(HistoricalMessageHydrationPhase.READY, message)
    } else {
        HistoricalMessageHydrationState(HistoricalMessageHydrationPhase.FAILED)
    }

@Composable
internal fun rememberHistoricalMessageHydrationState(
    messageId: String,
    cached: ChatMessage?,
    streamingOverlay: Boolean,
    retryKey: Int,
    observeMessage: (String) -> Flow<ChatMessage?>,
): State<HistoricalMessageHydrationState> {
    val initial = if (retryKey == 0) {
        initialHistoricalMessageHydrationState(cached, streamingOverlay)
    } else {
        initialHistoricalMessageHydrationState(null, streamingOverlay)
    }
    return produceState(
        initialValue = initial,
        messageId,
        cached?.id,
        streamingOverlay,
        retryKey,
        observeMessage,
    ) {
        value = initial
        if (streamingOverlay) return@produceState
        try {
            observeMessage(messageId).collect { message ->
                value = observedHistoricalMessageHydrationState(messageId, message)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            value = HistoricalMessageHydrationState(HistoricalMessageHydrationPhase.FAILED)
        }
    }
}

@Composable
internal fun HistoricalMessageHydrationCrossfade(
    messageId: String,
    phase: HistoricalMessageHydrationPhase,
    modifier: Modifier,
    onRetry: () -> Unit,
    content: @Composable () -> Unit,
) {
    Crossfade(
        targetState = phase,
        modifier = modifier.fillMaxWidth(),
        animationSpec = tween(HISTORICAL_MESSAGE_CROSSFADE_MS),
        label = "historicalMessagePayload-$messageId",
    ) { renderedPhase ->
        when (renderedPhase) {
            HistoricalMessageHydrationPhase.READY -> content()
            HistoricalMessageHydrationPhase.LOADING -> Box(
                modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(28.dp),
                    strokeWidth = 3.dp,
                )
            }
            HistoricalMessageHydrationPhase.FAILED -> Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.tool_state_failed),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.labelMedium,
                )
                TextButton(onClick = onRetry) {
                    Text(stringResource(R.string.retry))
                }
            }
        }
    }
}
