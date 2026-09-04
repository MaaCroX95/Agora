package com.newoether.agora.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.newoether.agora.ui.components.DialogWindowEdgeToEdge
import com.newoether.agora.ui.components.DialogWindowNoSystemDim

private const val MEDIA_PREVIEW_BACKDROP_ENTER_DURATION_MS = 220
private const val MEDIA_PREVIEW_BACKDROP_EXIT_DURATION_MS = 180

/**
 * Hosts media in a dedicated window so it sits above source sheets. Sheets opened from the
 * viewer are composed afterward and therefore remain above the viewer window.
 */
@Composable
internal fun FullScreenMediaPreviewDialog(
    currentUrls: List<String>?,
    currentIndex: Int,
    currentPdfPages: List<String>,
    currentPdfSelectedPages: Set<Int>,
    currentPdfSelectionEnabled: Boolean,
    currentPdfTogglePage: ((Int) -> Unit)?,
    enter: EnterTransition,
    exit: ExitTransition,
    onHidden: () -> Unit,
    onClose: () -> Unit,
    onNavigate: (Int) -> Unit,
    onMessage: (String) -> Unit,
    hapticsEnabled: Boolean,
) {
    val visibilityTransition = updateTransition(
        targetState = currentUrls != null,
        label = "mediaPreviewDialog",
    )
    val backdropAlpha by visibilityTransition.animateFloat(
        transitionSpec = {
            tween(
                durationMillis = if (false isTransitioningTo true) {
                    MEDIA_PREVIEW_BACKDROP_ENTER_DURATION_MS
                } else {
                    MEDIA_PREVIEW_BACKDROP_EXIT_DURATION_MS
                },
            )
        },
        label = "mediaPreviewBackdropAlpha",
    ) { visible ->
        if (visible) 1f else 0f
    }
    LaunchedEffect(visibilityTransition) {
        snapshotFlow {
            visibilityTransition.currentState to visibilityTransition.isRunning
        }.collect { (currentState, isRunning) ->
            if (!currentState && !isRunning) onHidden()
        }
    }

    var retainedUrls by remember { mutableStateOf<List<String>?>(null) }
    var retainedIndex by remember { mutableIntStateOf(0) }
    var retainedPdfPages by remember { mutableStateOf<List<String>>(emptyList()) }
    var retainedPdfSelectionEnabled by remember { mutableStateOf(false) }
    var retainedPdfTogglePage by remember { mutableStateOf<((Int) -> Unit)?>(null) }
    LaunchedEffect(currentUrls, currentIndex) {
        currentUrls?.let { urls ->
            retainedUrls = urls
            retainedIndex = currentIndex
            retainedPdfPages = currentPdfPages
            retainedPdfSelectionEnabled = currentPdfSelectionEnabled
            retainedPdfTogglePage =
                if (currentPdfSelectionEnabled) currentPdfTogglePage else null
        }
    }

    val urls = retainedUrls ?: return
    if (
        !visibilityTransition.currentState &&
        !visibilityTransition.targetState &&
        !visibilityTransition.isRunning
    ) {
        return
    }
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
        ),
    ) {
        DialogWindowEdgeToEdge()
        DialogWindowNoSystemDim()
        Box(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = backdropAlpha }
                    .background(Color.Black),
            )
            visibilityTransition.AnimatedVisibility(
                visible = { it },
                enter = enter,
                exit = exit,
                modifier = Modifier.fillMaxSize(),
            ) {
                FullScreenMediaViewer(
                    urls = urls,
                    initialIndex = retainedIndex,
                    pdfPages = retainedPdfPages,
                    pdfSelectedPages = if (
                        retainedPdfPages.isNotEmpty() &&
                        retainedPdfSelectionEnabled
                    ) {
                        currentPdfSelectedPages
                    } else {
                        null
                    },
                    onTogglePdfPage = retainedPdfTogglePage,
                    onClose = onClose,
                    onNavigate = onNavigate,
                    onMessage = onMessage,
                    hapticsEnabled = hapticsEnabled,
                )
            }
        }
    }
}
