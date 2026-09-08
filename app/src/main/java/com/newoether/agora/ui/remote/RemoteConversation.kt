package com.newoether.agora.ui.remote

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.data.repository.SettingsRepository
import com.newoether.agora.model.StableMessageList
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.remote.*
import com.newoether.agora.ui.chat.*
import com.newoether.agora.ui.chat.bottombar.*
import com.newoether.agora.ui.common.LocalAgoraHaptics
import com.newoether.agora.ui.components.AnimatedBlobBackground
import com.newoether.agora.ui.components.clearFocusOnTap
import com.newoether.agora.ui.motion.LocalAgoraMotionPolicy
import com.newoether.agora.util.gradientBlur

@Composable
internal fun RemoteConversation(
    state: RemoteState, vm: RemoteViewModel, settings: SettingsRepository, active: Boolean, onBack: () -> Unit,
) {
    val owner = state.owner ?: return
    val session = state.session ?: return
    val density = LocalDensity.current
    val motion = LocalAgoraMotionPolicy.current
    val haptics = LocalAgoraHaptics.current
    val blur by settings.blurEffectsEnabled.collectAsState(initial = false)
    val amoled by settings.amoledEnabled.collectAsState(initial = false)
    val inlineMath by settings.parseInlineDollarMath.collectAsState(initial = false)
    val stickToBottom by settings.stickToBottom.collectAsState(initial = true)
    val toolCallDisplayMode by settings.toolCallDisplayMode.collectAsState()
    val thinkingSegmentDisplayMode by settings.thinkingSegmentDisplayMode.collectAsState()
    val autoExpandActiveGroup by settings.autoExpandActiveGroup.collectAsState()
    var expanded by remember(owner) { mutableStateOf(false) }
    BackHandler(active && expanded) { expanded = false }
    val spacer = rememberComposerSpacerAnimation(expanded, motion.allowSpatialTransitions, with(density) { 44.dp.toPx() })
    val field = remember(owner) { TextFieldState(state.drafts[owner].orEmpty()) }
    val focus = remember { FocusRequester() }
    val attempt = state.attempts[owner]
    val running = state.runtime?.isRunning == true
    val ready = !session.readOnly && state.runtime?.status in setOf("idle", "active", "ready")
    val generationVisible = running && state.messages.any {
        it.role == "user" && it.turnId == state.runtime?.activeTurnId
    }
    var activeMenu by remember(owner) { mutableStateOf<String?>(null) }
    LaunchedEffect(owner, field) { snapshotFlow { field.text.toString() }.collect { vm.editDraft(owner, it) } }
    var clearedAttempt by remember(owner) {
        mutableStateOf(attempt?.takeIf { it.delivery == RemoteDelivery.DELIVERED }?.clientId)
    }
    var shownBusyAttempt by remember(owner) { mutableStateOf(clearedAttempt) }
    val acceptedPendingClear = attempt?.delivery == RemoteDelivery.DELIVERED && clearedAttempt != attempt.clientId
    val submitting = attempt?.delivery in setOf(RemoteDelivery.SUBMITTING, RemoteDelivery.ACCEPTED) || acceptedPendingClear
    LaunchedEffect(attempt, shownBusyAttempt) {
        if (attempt?.delivery == RemoteDelivery.DELIVERED && clearedAttempt != attempt.clientId && shownBusyAttempt == attempt.clientId) {
            clearedAttempt = attempt.clientId
            if (state.drafts[owner].isNullOrEmpty() && field.text.toString() == attempt.text) {
                field.edit { replace(0, length, "") }
            }
            expanded = false
            haptics.confirm()
        }
    }
    val messages = remember(state.messages, state.runtime) { projectRemoteMessages(state.messages, state.runtime) }
    val streaming = messages.lastOrNull()?.takeIf { it.status == MessageStatus.SENDING }
    val payloads = rememberUpdatedState(remember(messages) { messages.associateBy { it.id } })
    val observe = remember(owner) { { id: String -> snapshotFlow { payloads.value[id] } } }
    val messageState = rememberUpdatedState(messages)
    val ime = WindowInsets.ime.getBottom(density)
    val scroll = rememberChatScrollCoordinator(owner, ime)
    val animatedScrollRequest by vm.animatedScrollRequest.collectAsState()
    var barHeightPx by remember { mutableFloatStateOf(0f) }
    val barHeight = with(density) { barHeightPx.toDp() }
    var initiallyPositioned by remember(owner) { mutableStateOf(false) }
    val switching = !initiallyPositioned
    scroll.BindLayoutObservation(owner, owner, ime, density)
    scroll.BindImeEffects(owner, messageState, density, barHeight, 0.dp, ime)
    scroll.BindRequestEffects(owner, false, generationVisible, false, switching, false, false, null, animatedScrollRequest,
        messageState, density, motion, barHeight, 0.dp, onAnimatedScrollFinished = vm::completeAnimatedScroll)
    val renderMessages = rememberScrollIsolatedMessages(owner, messageState, scroll.listState,
        bypassScrollIsolation = scroll.absoluteBottomScrollPhase.isActive || scroll.streamingTailController.isAutoFollowing)
    LaunchedEffect(owner, state.runtime != null) {
        if (state.runtime != null && !initiallyPositioned) {
            scroll.settleOpenedConversation(messageState)
            initiallyPositioned = true
        }
    }
    val follow = streamingTailAvailability(
        generationActive = generationVisible,
        blocked = switching || !motion.allowProgrammaticScrollMotion,
        programmaticHandoff = scroll.imeBottomAnchorState.active ||
            scroll.absoluteBottomScrollPhase.isActive || animatedScrollRequest?.conversationId == owner,
    )
    var confirmUnknown by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).clearFocusOnTap()
        .onSizeChanged { scroll.recordViewportHeight(it.height) }) {
        val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
        if (!amoled) AnimatedBlobBackground(centerAlpha = if (dark) 0.02f else 0f,
            quarterAlpha = if (dark) 0.01f else 0f, blurRadius = 40f, dark = dark,
            blurEnabled = blur, motionEnabled = false)
        Scaffold(containerColor = Color.Transparent, contentWindowInsets = WindowInsets(0, 0, 0, 0), topBar = {
            ChatTopBar(false, emptyList(), session.id, session.title, 0, 0,
                onNavigateBack = onBack, onOpenDrawer = onBack, onSystemPromptClick = {}, onNewChat = vm::newSession,
                newChatEnabled = active && !state.controlling,
                moreMenuContent = { dismiss ->
                    DropdownMenuItem(text = { Text(stringResource(R.string.remote_refresh)) },
                        leadingIcon = { Icon(Icons.Default.Refresh, null) }, enabled = active,
                        onClick = { dismiss(); vm.refresh() })
                    if (state.historyCursor != null) DropdownMenuItem(
                        text = { Text(stringResource(R.string.remote_load_more)) },
                        leadingIcon = { Icon(Icons.Default.History, null) }, enabled = active && !state.loading,
                        onClick = { dismiss(); vm.loadMore() })
                })
        }) { _ ->
            Box(Modifier.fillMaxSize()) {
                MessageList(messages = StableMessageList(renderMessages.value), allMessages = StableMessageList(messages),
                    authoritativeMessages = StableMessageList(messages), conversationId = owner,
                    state = scroll.listState, messageActionsEnabled = false, readOnlyActions = true, parseInlineDollarMath = inlineMath,
                    isLoading = generationVisible, isSwitching = switching, streamingMessage = streaming,
                    streamingAutoFollowEnabled = follow.enabled && stickToBottom,
                    streamingAutoFollowPaused = follow.paused,
                    streamingTailWithinAttachThreshold = scroll.isWithinAbsoluteBottomAttachThreshold,
                    streamingTailController = scroll.streamingTailController,
                    toolCallDisplayMode = toolCallDisplayMode, thinkingSegmentDisplayMode = thinkingSegmentDisplayMode,
                    autoExpandActiveGroup = autoExpandActiveGroup,
                    modifier = Modifier.fillMaxSize().gradientBlur(blurAtTopDp = if (blur) 8f else 0f,
                        blurAtBottomDp = 0f, fadeHeightDp = 40f, bottomOverlayHeight = barHeight + 12.dp),
                    bottomBarHeight = barHeight, viewportHeight = scroll.viewportHeightPx,
                    messageHeights = scroll.messageHeights, observeMessage = observe,
                    programmaticScrollActive = animatedScrollRequest?.conversationId == owner,
                    onMessageHydrated = scroll::recordMessageHydrated,
                    lifecycleAppearanceRegistry = scroll.messageLifecycleAppearanceRegistry,
                    lifecycleEntranceTargetMessageId = animatedScrollRequest?.takeIf { it.conversationId == owner }?.targetMessageId,
                    contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 140.dp, bottom = barHeight + 8.dp))
                ChatBottomScrollButton(shouldShowAbsoluteBottomButton(false, false, messages.isNotEmpty(), running,
                    scroll.listState.layoutInfo.totalItemsCount > 1, scroll.listState.canScrollForward,
                    scroll.isNearAbsoluteBottom, false, scroll.absoluteBottomScrollPhase,
                    scroll.imeBottomAnchorState.active), barHeight) {
                    scroll.requestAbsoluteBottomScroll()
                }
            }
        }
        if (session.readOnly) {
            Surface(modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .onSizeChanged { barHeightPx = it.height.toFloat() }) {
                Column(Modifier.navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp)) {
                    RemoteReadStatus(state) { if (state.failure == RemoteFailure.SESSION_BUSY) vm.resumeSession() else vm.refresh() }
                    if (session.canResume) TextButton(onClick = vm::resumeSession,
                        enabled = active && !state.controlling) { Text(stringResource(R.string.remote_resume_history)) }
                    else Text(stringResource(R.string.remote_history_read_only), style = MaterialTheme.typography.labelMedium)
                }
            }
        } else ChatComposerSurface(expanded, { barHeightPx = it }, Modifier.align(Alignment.BottomCenter), spacer.outerHeightPx) {
            ChatComposerLayout(field, focus, scroll::setComposerInputFocused, expanded, spacer.isRunning,
                onExpand = { expanded = true }, onCollapse = { expanded = false },
                statusContent = {
                    RemoteReadStatus(state, vm::refresh)
                    ComposerStatusColumn(state.queued, { it.id }) { QueuedMessageRow(text = it.text) }
                    val status = when (attempt?.delivery) {
                        RemoteDelivery.UNKNOWN -> R.string.remote_unknown
                        RemoteDelivery.REJECTED -> R.string.remote_rejected
                        else -> null
                    }
                    if (status != null) Text(stringResource(status), Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (attempt?.delivery == RemoteDelivery.UNKNOWN) TextButton(onClick = { confirmUnknown = true }) {
                        Text(stringResource(R.string.remote_check))
                    }
                },
                controls = {
                    ComposerControlGroup {
                        ComposerModelSelector(
                            displayText = state.models.firstOrNull { it.id == state.runtime?.model }?.name
                                ?: state.runtime?.model ?: stringResource(R.string.remote_model_unavailable),
                            isModelValid = state.runtime?.model != null, expanded = activeMenu == "model",
                            enabled = active && ready && !state.controlling && state.models.isNotEmpty(),
                            onClick = { activeMenu = "model" }, onDismissRequest = { activeMenu = null },
                            menuContent = {
                                state.models.forEach { model -> DropdownMenuItem(
                                    text = { Text(model.name) },
                                    trailingIcon = { if (model.id == state.runtime?.model) Icon(Icons.Default.Check, null) },
                                    onClick = { activeMenu = null; vm.setModel(model.id) },
                                ) }
                            },
                        )
                        ComposerContextIndicator(
                            estimatedTokens = state.runtime?.contextTokens, tokenBudget = state.runtime?.contextWindow,
                            expanded = activeMenu == "context", onClick = { activeMenu = "context" },
                            onDismissRequest = { activeMenu = null },
                        )
                    }
                    val showStop = running && field.text.isBlank()
                    ComposerSendButton(isActionable = active && ready && !state.controlling && !submitting && (showStop || field.text.isNotBlank()) &&
                        attempt?.delivery != RemoteDelivery.UNKNOWN,
                        isBusy = submitting, showStop = showStop,
                        onBusyShown = { shownBusyAttempt = attempt?.clientId }) {
                        if (showStop) vm.stop() else { vm.editDraft(owner, field.text.toString()); vm.send() }
                    }
                })
        }
        ChatLoadingOverlay(visible = switching && !state.loading && !state.error)
    }
    if (confirmUnknown) AlertDialog(onDismissRequest = { confirmUnknown = false },
        title = { Text(stringResource(R.string.remote_confirm), fontWeight = FontWeight.Bold) },
        text = { Text(stringResource(R.string.remote_unknown)) },
        confirmButton = { TextButton(onClick = { vm.acknowledgeUnknown(owner); confirmUnknown = false }) { Text(stringResource(R.string.ok)) } },
        dismissButton = { TextButton(onClick = { confirmUnknown = false }) { Text(stringResource(R.string.cancel)) } })
}
