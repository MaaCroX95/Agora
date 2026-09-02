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
import com.newoether.agora.ui.motion.MotionAwareCircularProgressIndicator as CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.newoether.agora.R
import com.newoether.agora.model.AttachmentImportState
import com.newoether.agora.model.SelectedAttachment
import com.newoether.agora.ui.common.LocalAgoraHaptics
import com.newoether.agora.ui.chat.message.COMPOSER_ICON_CROSSFADE_DURATION_MS
import com.newoether.agora.viewmodel.ConversationComposerController
import com.newoether.agora.viewmodel.ConversationComposerSnapshot
import com.newoether.agora.viewmodel.SendAcceptance
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
private enum class ComposerActionIcon {
    STOPPING,
    PENDING,
    STOP,
    SEND,
}
/** Temporary UI bridge until the canonical submission lifecycle replaces it in Phase 8. */
@Composable
internal fun ComposerSendButton(
    textFieldState: TextFieldState,
    ownerId: String,
    controller: ConversationComposerController,
    snapshot: ConversationComposerSnapshot,
    isLoading: Boolean,
    isSwitching: Boolean,
    isStopping: Boolean = false,
    isModelValid: Boolean,
    onSendMessage: suspend (
        String,
        List<SelectedAttachment>,
        suspend () -> Unit,
    ) -> SendAcceptance?,
    onStopGeneration: () -> Unit,
    onCollapse: () -> Unit,
) {
    val haptics = LocalAgoraHaptics.current
    val submitScope = rememberCoroutineScope()
    var waitingJob by remember(ownerId) { mutableStateOf<Job?>(null) }
    var isWaiting by remember(ownerId) { mutableStateOf(false) }
    var isSubmitting by remember(ownerId) { mutableStateOf(false) }
    val textIsEmpty = textFieldState.text.isBlank()
    val attachmentsIsEmpty = snapshot.attachments.isEmpty()
    val showStop = isLoading && !isStopping && textIsEmpty && attachmentsIsEmpty
    val canSend = snapshot.loaded &&
        (textFieldState.text.isNotBlank() || snapshot.attachments.isNotEmpty()) &&
        isModelValid && !isSwitching && !isStopping && !isWaiting && !isSubmitting
    val isBusy = isStopping || isWaiting || isSubmitting
    val isActionable = (isLoading || canSend) && !isSwitching && !isBusy
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
            if (isSwitching || isStopping) return@FloatingActionButton
            when {
                showStop -> onStopGeneration()
                isWaiting -> {
                    haptics.selection()
                    waitingJob?.cancel()
                    waitingJob = null
                    isWaiting = false
                }
                canSend -> {
                    val submittedText = textFieldState.text.toString()
                    val frozenIds = snapshot.attachments.map(SelectedAttachment::localId)
                    waitingJob = submitScope.launch {
                        var retained = false
                        try {
                            controller.load(ownerId)
                            retained = true
                            isWaiting = true
                            controller.awaitProcessing(ownerId, frozenIds.toSet())
                            isWaiting = false
                            val currentById = controller.state(ownerId).value.attachments
                                .associateBy(SelectedAttachment::localId)
                            val readyAttachments = frozenIds.mapNotNull { id ->
                                currentById[id]?.takeIf {
                                    it.importState == AttachmentImportState.READY
                                }
                            }.map { attachment ->
                                attachment.copy(storage = attachment.storage.transferForSend())
                            }
                            if (submittedText.isBlank() && readyAttachments.isEmpty()) return@launch
                            isSubmitting = true
                            onSendMessage(submittedText, readyAttachments) { onCollapse() }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } finally {
                            if (retained) {
                                withContext(NonCancellable) { controller.release(ownerId) }
                            }
                            isWaiting = false
                            isSubmitting = false
                            waitingJob = null
                        }
                    }
                }
            }
        },
        containerColor = containerColor,
        contentColor = contentColor,
        modifier = Modifier.size(46.dp),
        shape = CircleShape,
        elevation = FloatingActionButtonDefaults.elevation(0.dp, 0.dp, 0.dp, 0.dp)
    ) {
        val fabIcon = when {
            isStopping || isSubmitting -> ComposerActionIcon.STOPPING
            isWaiting -> ComposerActionIcon.PENDING
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
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
