package com.newoether.agora.ui.remote

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.newoether.agora.remote.RemoteConnectionStore
import com.newoether.agora.remote.RemoteFailure
import java.io.File
import com.newoether.agora.R
import com.newoether.agora.SettingsOverlayHost
import com.newoether.agora.data.repository.SettingsRepository
import com.newoether.agora.remote.remoteDeviceName
import com.newoether.agora.remote.displayTitle
import com.newoether.agora.remote.RemoteState
import com.newoether.agora.remote.RemoteViewModel
import com.newoether.agora.remote.RemoteDeviceStatus
import com.newoether.agora.mcp.McpConnectionStatus
import com.newoether.agora.ui.settings.*
import com.newoether.agora.ui.chat.DrawerConversationIndicator
import com.newoether.agora.ui.chat.resolveDrawerConversationIndicator
import com.newoether.agora.ui.motion.MotionAwareCircularProgressIndicator
import com.newoether.agora.ui.motion.MotionAwareLinearProgressIndicator
import com.newoether.agora.ui.common.LocalAgoraHaptics
import com.newoether.agora.ui.common.rememberAgoraHaptics

@Composable
internal fun RemoteOverlay(
    visible: Boolean,
    settings: SettingsRepository,
    onDismiss: () -> Unit,
    onExitFinished: () -> Unit,
    onMessage: (String, String?, (() -> Unit)?) -> Unit,
    onSnackbarOffsetChanged: (androidx.compose.ui.unit.Dp) -> Unit,
) {
    val context = LocalContext.current.applicationContext
    val remote: RemoteViewModel = viewModel {
        RemoteViewModel(RemoteConnectionStore(File(context.noBackupFilesDir, "remote-connections.json")))
    }
    val messageHandler by rememberUpdatedState(onMessage)
    LaunchedEffect(remote, visible) {
        if (!visible) return@LaunchedEffect
        remote.notices.collect { notice ->
            if (remote.isNoticeCurrent(notice)) messageHandler(
                context.getString(remoteFailureResource(notice.failure)),
                if (notice.canRetryRead) context.getString(R.string.retry) else null,
                if (notice.canRetryRead) ({ remote.retryNotice(notice) }) else null,
            )
        }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(remote, visible, lifecycle) {
        fun update() = remote.setVisible(visible && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        val observer = LifecycleEventObserver { _, _ -> update() }
        lifecycle.addObserver(observer)
        update()
        onDispose { lifecycle.removeObserver(observer); remote.setVisible(false) }
    }
    SettingsOverlayHost(visible, onDismiss, onExitFinished = onExitFinished) {
        val hapticsEnabled by settings.hapticsEnabled.collectAsState(initial = false)
        CompositionLocalProvider(LocalAgoraHaptics provides rememberAgoraHaptics(hapticsEnabled)) {
            RemoteScreen(remote, settings, visible, onDismiss, onSnackbarOffsetChanged)
        }
    }
}

@Composable
private fun RemoteScreen(vm: RemoteViewModel, settings: SettingsRepository, active: Boolean, onBack: () -> Unit,
    onSnackbarOffsetChanged: (androidx.compose.ui.unit.Dp) -> Unit) {
    val state by vm.state.collectAsState()
    val inset = maxOf(WindowInsets.ime.asPaddingValues().calculateBottomPadding(),
        WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding())
    SideEffect { if (active && state.session == null) onSnackbarOffsetChanged(inset) }
    val focus = LocalFocusManager.current
    var forward by remember { mutableStateOf(true) }
    val back = {
        forward = false
        focus.clearFocus()
        when {
            state.session != null -> vm.selectSession(null)
            state.deviceId != null -> vm.selectDevice(null)
            state.addingDevice -> vm.selectDevice(null)
            else -> onBack()
        }
    }
    BackHandler(active, back)
    val target = Triple(state.deviceId, state.owner, if (state.addingDevice) state.editedDeviceId.orEmpty() else null)
    Box(Modifier.fillMaxSize()) {
    GuardedAnimatedContent(targetState = target, forward = forward) { page ->
        var retained by remember(page) { mutableStateOf(state) }
        val current = page == target
        SideEffect { if (current) retained = state }
        val displayed = if (current) state else retained
        when {
            page.third != null -> RemoteAddDevice(displayed, vm, back) { forward = false; focus.clearFocus() }
            page.second != null -> RemoteConversation(displayed, vm, settings, active && current, back, onSnackbarOffsetChanged)
            page.first != null -> {
                val listState = rememberLazyListState()
                val visibleRows = remember { mutableStateMapOf<String, Boolean>() }
                LaunchedEffect(current, active, displayed.deviceId, displayed.sessions) {
                    if (!current || !active) return@LaunchedEffect
                    snapshotFlow { displayed.sessions.map { it.id }.filter { visibleRows[it] == true } }
                        .collect { vm.observeSessions(page.first!!, it) }
                }
                LaunchedEffect(current, active, displayed.sessionCursor, displayed.loading, displayed.loadingMore, displayed.error) {
                    if (!current || !active || displayed.sessionCursor == null ||
                        displayed.loading || displayed.loadingMore || displayed.error) return@LaunchedEffect
                    snapshotFlow {
                        listState.layoutInfo.visibleItemsInfo.any { it.key == "sessions" } && !listState.canScrollForward
                    }.collect { atBottom -> if (atBottom) vm.loadMore() }
                }
                CollapsingSettingsLazyScaffold(
                    listState = listState,
                    title = stringResource(R.string.remote_sessions), onBack = back,
                    actions = {
                        IconButton(onClick = { forward = true; vm.newSession() }, enabled = current && active && !displayed.controlling) {
                            Icon(Icons.Default.Add, stringResource(R.string.new_chat))
                        }
                    },
                ) {
                    if (!displayed.loading && displayed.sessions.isEmpty() && !displayed.error) {
                        item { Text(stringResource(R.string.remote_empty), Modifier.padding(16.dp)) }
                    }
                    if (displayed.sessions.isNotEmpty()) item(key = "sessions") {
                        SettingsGroup(
                            title = displayed.devices.firstOrNull { it.id == displayed.deviceId }?.name.orEmpty(),
                            items = displayed.sessions.map { session -> {
                                key(session.id) {
                                    DisposableEffect(session.id) { onDispose { visibleRows.remove(session.id) } }
                                    SettingsItem(
                                        modifier = Modifier.onGloballyPositioned { coordinates ->
                                            val bounds = coordinates.boundsInWindow()
                                            val shown = bounds.width > 0 && bounds.height > 0
                                            if (shown) visibleRows[session.id] = true else visibleRows.remove(session.id)
                                        }.clickable(enabled = current) {
                                            forward = true; focus.clearFocus(); vm.selectSession(session)
                                        },
                                        headlineContent = { Text(session.displayTitle(stringResource(R.string.new_chat)), maxLines = 2, overflow = TextOverflow.Ellipsis) },
                                        supportingContent = { Text(if (session.readOnly && !session.canResume) stringResource(R.string.remote_history_read_only) + " · " + session.cwd else session.cwd,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                        leadingContent = { Icon(Icons.Default.ChatBubbleOutline, null) },
                                        trailingContent = {
                                            RemoteSessionIndicator(resolveDrawerConversationIndicator(
                                                isGenerating = displayed.sessionStatuses[session.id]?.status == "active",
                                                isSelected = false,
                                                hasUnreadGeneration = displayed.hasUnreadGeneration(session.id),
                                            ))
                                        },
                                    )
                                }
                            } },
                        )
                    }
                }
            }
            else -> RemoteDevices(displayed, vm, back) { forward = true }
        }
    }
    val progressVisible = remember { androidx.compose.animation.core.MutableTransitionState(false) }
    SideEffect {
        progressVisible.targetState = state.deviceId != null && state.session == null && !state.addingDevice &&
            (state.loading || state.loadingMore || state.controlling)
    }
    AnimatedVisibility(
        visibleState = progressVisible,
        modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding(),
        enter = fadeIn(tween(300)),
        exit = fadeOut(tween(300)),
    ) {
        MotionAwareLinearProgressIndicator(Modifier.fillMaxWidth().height(4.dp))
    }
    }
}

/** Exact original drawer indicator sizes, priority, color and fade timing. */
@Composable
private fun RemoteSessionIndicator(indicator: DrawerConversationIndicator) {
    val unreadDescription = stringResource(R.string.conversation_unread_generation)
    Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
        AnimatedVisibility(
            visible = indicator == DrawerConversationIndicator.GENERATING,
            enter = fadeIn(tween(200)), exit = fadeOut(tween(200)),
        ) {
            MotionAwareCircularProgressIndicator(
                modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary,
            )
        }
        AnimatedVisibility(
            visible = indicator == DrawerConversationIndicator.UNREAD,
            enter = fadeIn(tween(200)), exit = fadeOut(tween(200)),
        ) {
            Box(Modifier.size(8.dp).background(MaterialTheme.colorScheme.primary, CircleShape)
                .semantics { contentDescription = unreadDescription })
        }
    }
}

@Composable
private fun RemoteDevices(state: RemoteState, vm: RemoteViewModel, onBack: () -> Unit, onForward: () -> Unit) {
    var deleteId by remember { mutableStateOf<String?>(null) }
    val enabled = !state.restoring && !state.saving
    CollapsingSettingsScaffold(title = stringResource(R.string.remote_title), onBack = onBack) {
        SettingsGroup(
            title = stringResource(R.string.remote_devices),
            items = buildList {
                if (state.devices.isEmpty() && !state.restoring && !state.storageError) add {
                    SettingsItem(
                        headlineContent = { Text(stringResource(R.string.remote_no_devices),
                            color = MaterialTheme.colorScheme.onSurfaceVariant) },
                        supportingContent = { Text(stringResource(R.string.remote_no_devices_desc)) },
                        leadingContent = { Icon(Icons.Default.Computer, null) },
                    )
                }
                state.devices.forEach { device -> add { key(device.id) {
                    var menuOpen by remember { mutableStateOf(false) }
                    val status = when (device.status) {
                        RemoteDeviceStatus.IDLE -> McpConnectionStatus.IDLE
                        RemoteDeviceStatus.CONNECTING -> McpConnectionStatus.CONNECTING
                        RemoteDeviceStatus.CONNECTED -> McpConnectionStatus.CONNECTED
                        RemoteDeviceStatus.ERROR -> McpConnectionStatus.ERROR
                    }
                    SettingsItem(
                        modifier = Modifier.clickable(enabled = enabled) {
                            onForward(); vm.selectDevice(device.id)
                        },
                        headlineContent = {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(device.name.ifBlank { stringResource(R.string.remote_device) }, Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                McpStatusDot(status)
                            }
                        },
                        supportingContent = {
                            Column {
                                Text(device.address, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        },
                        leadingContent = { Icon(Icons.Default.Computer, null) },
                        trailingContent = { Box {
                            IconButton(onClick = { menuOpen = true }, enabled = enabled) {
                                Icon(Icons.Default.MoreVert, stringResource(R.string.options))
                            }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false },
                                shape = RoundedCornerShape(12.dp), containerColor = MaterialTheme.colorScheme.surfaceContainer,
                                tonalElevation = 16.dp) {
                                DropdownMenuItem(text = { Text(stringResource(R.string.edit)) }, enabled = enabled,
                                    leadingIcon = { Icon(Icons.Default.Edit, null) },
                                    onClick = { menuOpen = false; onForward(); vm.editDevice(device.id) })
                                DropdownMenuItem(text = { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) },
                                    enabled = enabled, leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
                                    onClick = { menuOpen = false; deleteId = device.id })
                            }
                        } },
                    )
                } } }
                add {
                    SettingsAddItem(label = stringResource(R.string.remote_add_device),
                        enabled = enabled,
                        onClick = { onForward(); vm.addDevice() })
                }
            },
        )
    }
    state.devices.firstOrNull { it.id == deleteId }?.let { device ->
        AlertDialog(onDismissRequest = { deleteId = null },
            title = { Text(stringResource(R.string.remote_delete_title)) },
            text = { Text(stringResource(R.string.remote_delete_message, device.name.ifBlank { stringResource(R.string.remote_device) })) },
            confirmButton = { TextButton(enabled = enabled, onClick = { vm.removeDevice(device.id); deleteId = null }) {
                Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
            } },
            dismissButton = { TextButton(onClick = { deleteId = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun RemoteAddDevice(state: RemoteState, vm: RemoteViewModel, onBack: () -> Unit, onForward: () -> Unit) {
    val initial = remember { vm.editorConnection() }
    var name by remember { mutableStateOf(remoteDeviceName(initial?.name.orEmpty())) }
    var address by remember { mutableStateOf(initial?.address.orEmpty()) }
    var token by remember { mutableStateOf(initial?.token.orEmpty()) }
    CollapsingSettingsScaffold(
        title = stringResource(if (state.editedDeviceId == null) R.string.remote_add_device else R.string.remote_edit_device),
        onBack = onBack,
        actions = { IconButton(onClick = { onForward(); vm.saveDevice(address, token, name) },
            enabled = !state.restoring && !state.saving && address.isNotBlank() && token.isNotBlank()) {
            Icon(Icons.Default.Save, stringResource(R.string.save))
        } },
    ) {
        SettingsGroup(title = stringResource(R.string.remote_connection), items = listOf({
            SettingsIconContent(Icons.Default.Computer) {
                McpLabeledField(label = stringResource(R.string.shell_device_name), value = name,
                    onValueChange = { name = it }, placeholder = stringResource(R.string.remote_device))
            }
        }, {
            SettingsIconContent(Icons.Default.Link) {
                McpLabeledField(label = stringResource(R.string.remote_address), value = address,
                    onValueChange = { address = it }, keyboardType = KeyboardType.Uri,
                    supportingText = stringResource(R.string.remote_connection_hint),
                    placeholder = stringResource(R.string.remote_address_placeholder))
            }
        }, {
            SettingsIconContent(Icons.Default.Key) {
                McpLabeledField(label = stringResource(R.string.remote_token), value = token,
                    onValueChange = { token = it }, keyboardType = KeyboardType.Password, password = true,
                    placeholder = stringResource(R.string.remote_token_placeholder))
            }
        }))
    }
}

private fun remoteFailureResource(failure: RemoteFailure?): Int = when (failure) {
    RemoteFailure.NETWORK -> R.string.remote_network_failed
    RemoteFailure.AUTHENTICATION -> R.string.remote_auth_failed
    RemoteFailure.CONFIGURATION -> R.string.remote_configuration_failed
    RemoteFailure.PROTOCOL -> R.string.remote_protocol_failed
    RemoteFailure.SESSION_BUSY -> R.string.remote_session_busy
    RemoteFailure.CONTENT_TOO_LARGE -> R.string.remote_history_too_large
    RemoteFailure.SERVICE -> R.string.remote_service_failed
    RemoteFailure.STORAGE -> R.string.remote_storage_failed
    else -> R.string.remote_failed
}
