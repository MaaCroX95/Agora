package com.newoether.agora.ui.chat.bottombar

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.model.SelectedAttachment
import com.newoether.agora.ui.chat.message.COMPOSER_ICON_CROSSFADE_DURATION_MS
import com.newoether.agora.ui.common.LocalAgoraHaptics
import com.newoether.agora.ui.motion.MotionAwareCircularProgressIndicator as CircularProgressIndicator
import com.newoether.agora.viewmodel.ConversationComposerSnapshot
import com.newoether.agora.viewmodel.ConversationComposerSubmissionController
import com.newoether.agora.viewmodel.ConversationComposerSubmissionSnapshot

private enum class ComposerActionIcon {
    BUSY,
    STOP,
    SEND,
}

@Composable
internal fun ComposerSendButton(
    textFieldState: TextFieldState,
    ownerId: String,
    snapshot: ConversationComposerSnapshot,
    submissionController: ConversationComposerSubmissionController,
    submission: ConversationComposerSubmissionSnapshot,
    isLoading: Boolean,
    isSwitching: Boolean,
    isStopping: Boolean = false,
    isModelValid: Boolean,
    onStopGeneration: () -> Unit,
    onCollapse: () -> Unit,
) {
    val haptics = LocalAgoraHaptics.current

    val textIsEmpty = textFieldState.text.isBlank()
    val attachmentsIsEmpty = snapshot.attachments.isEmpty()
    val showStop = isLoading && !isStopping && textIsEmpty && attachmentsIsEmpty
    val canSend = snapshot.loaded &&
        (textFieldState.text.isNotBlank() || snapshot.attachments.isNotEmpty()) &&
        isModelValid && !isSwitching && !isStopping && !submission.isFrozen
    val isActionable = submission.isWaiting || showStop || canSend
    val icon = when {
        isStopping || submission.isSubmitting || submission.isAcceptedPendingClear ->
            ComposerActionIcon.BUSY
        submission.isWaiting -> ComposerActionIcon.BUSY
        showStop -> ComposerActionIcon.STOP
        else -> ComposerActionIcon.SEND
    }
    val containerColor by animateColorAsState(
        targetValue = if (isActionable) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        animationSpec = tween(durationMillis = 400),
        label = "fabContainer",
    )
    val contentColor by animateColorAsState(
        targetValue = if (isActionable) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = tween(durationMillis = 400),
        label = "fabContent",
    )

    Surface(
        onClick = {
            when {
                submission.isWaiting -> {
                    haptics.selection()
                    submissionController.cancelWaiting(ownerId)
                }
                showStop -> onStopGeneration()
                canSend -> submissionController.submit(
                    ownerId = ownerId,
                    text = textFieldState.text.toString(),
                    attachmentIds = snapshot.attachments.map(SelectedAttachment::localId),
                )
            }
        },
        enabled = isActionable,
        modifier = Modifier.size(46.dp),
        shape = CircleShape,
        color = containerColor,
        contentColor = contentColor,
        shadowElevation = 0.dp,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Crossfade(
                targetState = icon,
                animationSpec = tween(
                    durationMillis = COMPOSER_ICON_CROSSFADE_DURATION_MS,
                    easing = LinearEasing,
                ),
                label = "composerActionIcon",
            ) { renderedIcon ->
                when (renderedIcon) {
                    ComposerActionIcon.BUSY -> CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 3.dp,
                        color = contentColor,
                    )
                    ComposerActionIcon.STOP -> Icon(
                        Icons.Default.Stop,
                        stringResource(R.string.action),
                        modifier = Modifier.size(24.dp),
                    )
                    ComposerActionIcon.SEND -> Icon(
                        Icons.Default.ArrowUpward,
                        stringResource(R.string.action),
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
        }
    }
}
