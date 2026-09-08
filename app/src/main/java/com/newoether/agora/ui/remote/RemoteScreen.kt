package com.newoether.agora.ui.remote

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import com.newoether.agora.ui.motion.LocalAgoraMotionPolicy
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
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
import com.newoether.agora.remote.displayTitle
import com.newoether.agora.remote.RemoteState
import com.newoether.agora.remote.RemoteViewModel
import com.newoether.agora.remote.RemoteDeviceStatus
import com.newoether.agora.mcp.McpConnectionStatus
import com.newoether.agora.ui.settings.*
import com.newoether.agora.ui.motion.MotionAwareLinearProgressIndicator
import com.newoether.agora.ui.common.LocalAgoraHaptics
import com.newoether.agora.ui.common.rememberAgoraHaptics

@Composable
internal fun RemoteOverlay(
    visible: Boolean,
    settings: SettingsRepository,
    onDismiss: () -> Unit,
    onExitFinished: () -> Unit,
) {
    val context = LocalContext.current.applicationContext
    val remote: RemoteViewModel = viewModel {
        RemoteViewModel(RemoteConnectionStore(File(context.noBackupFilesDir, "remote-connections.json")))
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
            RemoteScreen(remote, settings, visible, onDismiss)
        }
    }
}

@Composable
private fun RemoteScreen(vm: RemoteViewModel, settings: SettingsRepository, active: Boolean, onBack: () -> Unit) {
    val state by vm.state.collectAsState()
    val focus = LocalFocusManager.current
    val motion = LocalAgoraMotionPolicy.current
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
    val target = Triple(state.deviceId, state.session?.id, if (state.addingDevice) state.editedDeviceId.orEmpty() else null)
    Box(Modifier.fillMaxSize()) {
    GuardedAnimatedContent(targetState = target, forward = forward) { page ->
        var retained by remember(page) { mutableStateOf(state) }
        val current = page == target
        SideEffect { if (current) retained = state }
        val displayed = if (current) state else retained
        when {
            page.third != null -> RemoteAddDevice(displayed, vm, back) { forward = false; focus.clearFocus() }
            page.second != null -> RemoteConversation(displayed, vm, settings, active && current, back)
            page.first != null -> CollapsingSettingsLazyScaffold(
                title = stringResource(R.string.remote_sessions), onBack = back,
                actions = { IconButton(onClick = { forward = true; vm.newSession() }, enabled = current && active && !displayed.controlling) {
                    Icon(Icons.Default.Add, stringResource(R.string.new_chat))
                } },
            ) {
                item { RemoteReadStatus(displayed, vm::refresh) }
                if (!displayed.loading && displayed.sessions.isEmpty() && !displayed.error) {
                    item { Text(stringResource(R.string.remote_empty), Modifier.padding(16.dp)) }
                }
                if (displayed.sessions.isNotEmpty()) item {
                    SettingsGroup(title = displayed.devices.firstOrNull { it.id == displayed.deviceId }?.name.orEmpty(),
                        items = displayed.sessions.map { session -> {
                        SettingsItem(
                            modifier = Modifier.clickable(enabled = current) {
                                forward = true; focus.clearFocus(); vm.selectSession(session)
                            },
                            headlineContent = { Text(session.displayTitle(stringResource(R.string.new_chat)), maxLines = 2, overflow = TextOverflow.Ellipsis) },
                            supportingContent = { Text(if (session.readOnly && !session.canResume) stringResource(R.string.remote_history_read_only) + " · " + session.cwd else session.cwd,
                                maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            leadingContent = { Icon(Icons.Default.ChatBubbleOutline, null) },
                        )
                    } })
                }
                if (displayed.sessionCursor != null) item {
                    TextButton(onClick = vm::loadMore, enabled = current && !displayed.loading && !displayed.loadingMore) { Text(stringResource(R.string.remote_load_more)) }
                }
            }
            else -> RemoteDevices(displayed, vm, back) { forward = true }
        }
    }
    AnimatedVisibility(
        visible = state.deviceId != null && state.session == null && !state.addingDevice &&
            (state.loading || state.loadingMore || state.controlling),
        modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding(),
        enter = if (motion.allowSpatialTransitions) {
            expandVertically(expandFrom = Alignment.Bottom, animationSpec = tween(200)) + fadeIn(tween(200))
        } else fadeIn(tween(200)),
        exit = if (motion.allowSpatialTransitions) {
            shrinkVertically(shrinkTowards = Alignment.Bottom, animationSpec = tween(200)) + fadeOut(tween(200))
        } else fadeOut(tween(200)),
    ) {
        MotionAwareLinearProgressIndicator(Modifier.fillMaxWidth().height(4.dp))
    }
    }
}

@Composable
private fun RemoteDevices(state: RemoteState, vm: RemoteViewModel, onBack: () -> Unit, onForward: () -> Unit) {
    var deleteId by remember { mutableStateOf<String?>(null) }
    val enabled = !state.restoring && !state.saving
    CollapsingSettingsScaffold(title = stringResource(R.string.remote_title), onBack = onBack) {
        if (state.storageError) TextButton(onClick = vm::restoreConnections,
            enabled = enabled) {
            Text(stringResource(R.string.remote_storage_failed))
        }
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
                                Text(device.name, Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                McpStatusDot(status)
                            }
                        },
                        supportingContent = {
                            Column {
                                Text(device.address, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (device.failure != null) Text(remoteFailureText(device.failure), color = MaterialTheme.colorScheme.error)
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
            text = { Text(stringResource(R.string.remote_delete_message, device.name)) },
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
    var address by remember { mutableStateOf(initial?.address.orEmpty()) }
    var token by remember { mutableStateOf(initial?.token.orEmpty()) }
    CollapsingSettingsScaffold(
        title = stringResource(if (state.editedDeviceId == null) R.string.remote_add_device else R.string.remote_edit_device),
        onBack = onBack,
        actions = { IconButton(onClick = { onForward(); vm.saveDevice(address, token) },
            enabled = !state.restoring && !state.saving && address.isNotBlank() && token.isNotBlank()) {
            Icon(Icons.Default.Save, stringResource(R.string.save))
        } },
    ) {
        SettingsGroup(title = stringResource(R.string.remote_connection), items = listOf({
            SettingsIconContent(Icons.Default.Link) {
                McpLabeledField(label = stringResource(R.string.remote_address), value = address,
                    onValueChange = { address = it }, keyboardType = KeyboardType.Uri,
                    supportingText = stringResource(R.string.remote_connection_hint))
            }
        }, {
            SettingsIconContent(Icons.Default.Key) {
                McpLabeledField(label = stringResource(R.string.remote_token), value = token,
                    onValueChange = { token = it }, keyboardType = KeyboardType.Password, password = true)
            }
        }))
        if (state.storageError) Text(stringResource(R.string.remote_save_failed), Modifier.padding(16.dp),
            color = MaterialTheme.colorScheme.error)
        if (state.error) Text(remoteFailureText(state.failure), Modifier.padding(16.dp),
            color = MaterialTheme.colorScheme.error)
    }
}

@Composable
internal fun RemoteReadStatus(state: RemoteState, retry: () -> Unit) {
    if (state.error) TextButton(onClick = retry) { Text(remoteFailureText(state.failure)) }
}

@Composable
private fun remoteFailureText(failure: RemoteFailure?): String = stringResource(when (failure) {
    RemoteFailure.NETWORK -> R.string.remote_network_failed
    RemoteFailure.AUTHENTICATION -> R.string.remote_auth_failed
    RemoteFailure.CONFIGURATION -> R.string.remote_configuration_failed
    RemoteFailure.PROTOCOL -> R.string.remote_protocol_failed
    RemoteFailure.SESSION_BUSY -> R.string.remote_session_busy
    RemoteFailure.SERVICE -> R.string.remote_service_failed
    RemoteFailure.STORAGE -> R.string.remote_storage_failed
    else -> R.string.remote_failed
})
