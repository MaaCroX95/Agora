package com.newoether.agora.ui.chat.bottombar

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
    STOPPING,
    PENDING,
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
    var observedAcceptedVersion by remember(ownerId) {
        mutableLongStateOf(submission.acceptedVersion)
    }
    LaunchedEffect(submission.acceptedVersion) {
        if (submission.acceptedVersion != observedAcceptedVersion) {
            observedAcceptedVersion = submission.acceptedVersion
            onCollapse()
        }
    }
    val textIsEmpty = textFieldState.text.isBlank()
    val attachmentsIsEmpty = snapshot.attachments.isEmpty()
    val showStop = isLoading && !isStopping && textIsEmpty && attachmentsIsEmpty
    val canSend = snapshot.loaded &&
        (textFieldState.text.isNotBlank() || snapshot.attachments.isNotEmpty()) &&
        isModelValid && !isSwitching && !isStopping && !submission.isFrozen
    val isActionable = submission.isWaiting ||
        ((isLoading || canSend) && !isSwitching && !isStopping && !submission.isSubmitting)
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
    FloatingActionButton(
        onClick = {
            when {
                submission.isWaiting -> {
                    haptics.selection()
                    submissionController.cancelWaiting(ownerId)
                }
                isSwitching || isStopping || submission.isSubmitting -> Unit
                showStop -> onStopGeneration()
                canSend -> submissionController.submit(
                    ownerId = ownerId,
                    text = textFieldState.text.toString(),
                    attachmentIds = snapshot.attachments.map(SelectedAttachment::localId),
                )
            }
        },
        containerColor = containerColor,
        contentColor = contentColor,
        modifier = Modifier.size(46.dp),
        shape = CircleShape,
        elevation = FloatingActionButtonDefaults.elevation(0.dp, 0.dp, 0.dp, 0.dp),
    ) {
        val fabIcon = when {
            isStopping || submission.isSubmitting -> ComposerActionIcon.STOPPING
            submission.isWaiting -> ComposerActionIcon.PENDING
            showStop -> ComposerActionIcon.STOP
            else -> ComposerActionIcon.SEND
        }
        Crossfade(
            targetState = fabIcon,
            animationSpec = tween(
                durationMillis = COMPOSER_ICON_CROSSFADE_DURATION_MS,
                easing = LinearEasing,
            ),
            label = "composerActionIcon",
        ) { icon ->
            when (icon) {
                ComposerActionIcon.STOPPING,
                ComposerActionIcon.PENDING -> CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp,
                    color = if (submission.isWaiting) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
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
