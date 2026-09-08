package com.newoether.agora.ui.remote

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import com.newoether.agora.ui.motion.MotionAwareCircularProgressIndicator
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
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

@OptIn(ExperimentalMaterial3Api::class)
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
    val newChatEntry = remember(owner) { state.composerFocusOwner == owner }
    ChatLaunchInteractionEffects(
        initialComposerFocusReady = active && ready && state.composerFocusOwner == owner,
        inputFocusRequester = focus,
        onShowLaunchContent = {},
        onInitialFocusRequested = { vm.completeComposerFocus(owner) },
    )
    val generationVisible = running && state.messages.any {
        it.role == "user" && it.turnId == state.runtime?.activeTurnId
    }
    var activeMenu by remember(owner) { mutableStateOf<String?>(null) }
    var lastModelDismissTime by remember(owner) { mutableLongStateOf(0L) }
    var lastContextDismissTime by remember(owner) { mutableLongStateOf(0L) }
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
    val streaming = messages.lastOrNull()?.takeIf {
        it.status in setOf(MessageStatus.SENDING, MessageStatus.THINKING, MessageStatus.TOOL_CALLING, MessageStatus.TRANSCRIBING)
    }
    val payloads = rememberUpdatedState(remember(messages) { messages.associateBy { it.id } })
    val observe = remember(owner) { { id: String -> snapshotFlow { payloads.value[id] } } }
    val messageState = rememberUpdatedState(messages)
    val ime = WindowInsets.ime.getBottom(density)
    val scroll = rememberChatScrollCoordinator(owner, ime)
    val focusManager = LocalFocusManager.current
    val searchMessages: suspend (String, List<String>) -> List<com.newoether.agora.model.ChatMessage> = remember(owner) {
        { _, ids -> ids.mapNotNull { payloads.value[it] } }
    }
    val interaction = rememberConversationInteractionState(owner, messageState, scroll.listState, searchMessages)
    BackHandler(active && interaction.searchActive) {
        interaction.dismissSearch()
        focusManager.clearFocus()
    }
    val animatedScrollRequest by vm.animatedScrollRequest.collectAsState()
    var barHeightPx by remember { mutableFloatStateOf(0f) }
    val barHeight = with(density) { barHeightPx.toDp() }
    var initiallyPositioned by remember(owner) { mutableStateOf(false) }
    val switching = !initiallyPositioned
    scroll.BindLayoutObservation(owner, owner, ime, density)
    scroll.BindImeEffects(owner, messageState, density, barHeight, 0.dp, ime)
    scroll.BindRequestEffects(owner, false, generationVisible, false, switching, interaction.searchActive, false, null, animatedScrollRequest,
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
        blocked = switching || interaction.searchActive || !motion.allowProgrammaticScrollMotion,
        programmaticHandoff = scroll.imeBottomAnchorState.active ||
            scroll.absoluteBottomScrollPhase.isActive || animatedScrollRequest?.conversationId == owner,
    )
    LaunchedEffect(owner, active, switching, interaction.searchActive, state.historyCursor, state.loading, state.loadingMore, state.error) {
        if (!active || switching || state.historyCursor == null || state.loading || state.loadingMore || state.error) return@LaunchedEffect
        if (interaction.searchActive) vm.loadMore()
        else snapshotFlow { scroll.listState.firstVisibleItemIndex == 0 }.collect { atTop ->
            if (atTop) vm.loadMore()
        }
    }
    var confirmUnknown by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).clearFocusOnTap()
        .onSizeChanged { scroll.recordViewportHeight(it.height) }) {
        val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
        if (!amoled) AnimatedBlobBackground(centerAlpha = if (dark) 0.02f else 0f,
            quarterAlpha = if (dark) 0.01f else 0f, blurRadius = 40f, dark = dark,
            blurEnabled = blur, motionEnabled = false)
        Scaffold(containerColor = Color.Transparent, contentWindowInsets = WindowInsets(0, 0, 0, 0), topBar = {
            ChatTopBar(
                isNewChatMode = false, conversations = emptyList(),
                currentConversationId = session.id, currentConversationTitle = session.displayTitle(stringResource(R.string.new_chat)),
                totalTokens = state.runtime?.contextTokens ?: 0,
                contextTokenBudget = state.runtime?.contextWindow ?: 0,
                contextAvailable = state.runtime?.contextTokens != null && state.runtime?.contextWindow != null,
                searchActive = interaction.searchActive, searchQuery = interaction.searchQuery,
                searchMatchIndex = interaction.searchMatchIndex, searchMatchCount = interaction.searchMatches.size,
                onSearchQueryChange = interaction::updateSearchQuery,
                onSearchPrevious = { if (interaction.previousSearchMatch()) haptics.selection() },
                onSearchNext = { if (interaction.nextSearchMatch()) haptics.selection() },
                onSearchDismiss = { interaction.dismissSearch(); focusManager.clearFocus() },
                onNavigateBack = onBack, onOpenDrawer = onBack, onSystemPromptClick = {}, onNewChat = vm::newSession,
                newChatEnabled = active && !state.controlling,
                moreMenuContent = { dismiss ->
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.conversation_search)) },
                        leadingIcon = { Icon(Icons.Default.Search, null) },
                        enabled = active && !switching,
                        onClick = { dismiss(); interaction.activateSearch() },
                    )
                },
            )
        }) { _ ->
            Box(Modifier.fillMaxSize()) {
                MessageList(messages = StableMessageList(renderMessages.value), allMessages = StableMessageList(messages),
                    authoritativeMessages = StableMessageList(messages), conversationId = owner,
                    state = scroll.listState, messageActionsEnabled = false, readOnlyActions = true, parseInlineDollarMath = inlineMath,
                    isLoading = generationVisible, isSwitching = switching, streamingMessage = streaming,
                    searchQuery = if (interaction.searchActive) interaction.searchQuery else "",
                    activeSearchMatch = interaction.searchMatches.getOrNull(interaction.searchMatchIndex),
                    onSearchMatchDistance = interaction::recordSearchMatchDistance,
                    onSearchTurnsChanged = interaction::recordSearchTurns,
                    streamingAutoFollowEnabled = follow.enabled && stickToBottom,
                    streamingAutoFollowPaused = follow.paused,
                    streamingTailWithinAttachThreshold = scroll.isWithinAbsoluteBottomAttachThreshold,
                    streamingTailController = scroll.streamingTailController,
                    toolCallDisplayMode = toolCallDisplayMode, thinkingSegmentDisplayMode = thinkingSegmentDisplayMode,
                    autoExpandActiveGroup = autoExpandActiveGroup,
                    modifier = Modifier.fillMaxSize().gradientBlur(blurAtTopDp = if (blur) 8f else 0f,
                        blurAtBottomDp = 0f, fadeHeightDp = 40f, bottomOverlayHeight = barHeight + with(density) { spacer.outerHeightPx.toDp() } + 12.dp),
                    bottomBarHeight = barHeight, viewportHeight = scroll.viewportHeightPx,
                    messageHeights = scroll.messageHeights, observeMessage = observe,
                    programmaticScrollActive = animatedScrollRequest?.conversationId == owner,
                    onMessageHydrated = scroll::recordMessageHydrated,
                    lifecycleAppearanceRegistry = scroll.messageLifecycleAppearanceRegistry,
                    lifecycleEntranceTargetMessageId = animatedScrollRequest?.takeIf { it.conversationId == owner }?.targetMessageId,
                    contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 140.dp, bottom = barHeight + 8.dp))
                ChatBottomScrollButton(
                    shouldShowAbsoluteBottomButton(
                        isNewChatMode = newChatEntry && messages.isEmpty(),
                        isSwitching = switching,
                        conversationContentReady = initiallyPositioned,
                        shareSelectionActive = false,
                        hasItems = scroll.listState.layoutInfo.totalItemsCount > 1,
                        canScrollForward = scroll.listState.canScrollForward,
                        isNearBottom = scroll.isNearAbsoluteBottom,
                        isStreamingAutoFollowing = scroll.streamingTailController.isAutoFollowing,
                        scrollPhase = scroll.absoluteBottomScrollPhase,
                        competingProgrammaticScrollActive = scroll.imeBottomAnchorState.active,
                    ),
                    barHeight,
                ) {
                    scroll.requestAbsoluteBottomScroll()
                }

                AnimatedVisibility(
                    visible = switching && !newChatEntry && !state.error,
                    enter = fadeIn(animationSpec = tween(200)),
                    exit = fadeOut(animationSpec = tween(200))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background),
                        contentAlignment = Alignment.Center
                    ) {
                        MotionAwareCircularProgressIndicator(
                            modifier = Modifier.size(48.dp),
                            strokeWidth = 5.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
        if (session.readOnly) {
            Surface(modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .onSizeChanged { barHeightPx = it.height.toFloat() }) {
                Column(Modifier.navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp)) {
                    RemoteReadStatus(state) { if (session.canResume) vm.resumeSession() else vm.refresh() }
                    if (!session.canResume) Text(stringResource(R.string.remote_history_read_only), style = MaterialTheme.typography.labelMedium)
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
                        AttachmentAddMenu(
                            enabled = active && !submitting,
                            onCamera = {}, onPhotos = {}, onVideos = {}, onFiles = {},
                        )
                        ComposerModelSelector(
                            displayText = state.models.firstOrNull { it.id == state.runtime?.model }?.name
                                ?: state.runtime?.model ?: stringResource(R.string.remote_model_unavailable),
                            isModelValid = state.runtime?.model != null, expanded = activeMenu == "model",
                            enabled = active && ready && !state.controlling && state.models.isNotEmpty(),
                            onClick = {
                                val now = System.currentTimeMillis()
                                if (activeMenu == "model") activeMenu = null
                                else if (now - lastModelDismissTime > 200) activeMenu = "model"
                            },
                            onDismissRequest = {
                                if (activeMenu == "model") {
                                    activeMenu = null
                                    lastModelDismissTime = System.currentTimeMillis()
                                }
                            },
                            menuContent = {
                                val sortedModels = remember(state.models) { state.models.sortedBy { it.id.lowercase() } }
                                sortedModels.forEach { model ->
                                    DropdownMenuItem(
                                        text = { Text(model.name) },
                                        onClick = {
                                            haptics.selection()
                                            vm.setModel(model.id)
                                            activeMenu = null
                                            lastModelDismissTime = 0L
                                        },
                                    )
                                }
                            },
                        )
                        ComposerContextIndicator(
                            estimatedTokens = state.runtime?.contextTokens, tokenBudget = state.runtime?.contextWindow,
                            expanded = activeMenu == "context",
                            onClick = {
                                val now = System.currentTimeMillis()
                                if (activeMenu == "context") activeMenu = null
                                else if (now - lastContextDismissTime > 200) activeMenu = "context"
                            },
                            onDismissRequest = {
                                if (activeMenu == "context") {
                                    activeMenu = null
                                    lastContextDismissTime = System.currentTimeMillis()
                                }
                            },
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
    }
    if (confirmUnknown) AlertDialog(onDismissRequest = { confirmUnknown = false },
        title = { Text(stringResource(R.string.remote_confirm), fontWeight = FontWeight.Bold) },
        text = { Text(stringResource(R.string.remote_unknown)) },
        confirmButton = { TextButton(onClick = { vm.acknowledgeUnknown(owner); confirmUnknown = false }) { Text(stringResource(R.string.ok)) } },
        dismissButton = { TextButton(onClick = { confirmUnknown = false }) { Text(stringResource(R.string.cancel)) } })
}
