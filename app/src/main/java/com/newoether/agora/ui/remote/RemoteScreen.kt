package com.newoether.agora.ui.remote

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.newoether.agora.remote.RemoteConnectionStore
import java.io.File
import com.newoether.agora.R
import com.newoether.agora.SettingsOverlayHost
import com.newoether.agora.data.repository.SettingsRepository
import com.newoether.agora.remote.RemoteState
import com.newoether.agora.remote.RemoteViewModel
import com.newoether.agora.ui.settings.*
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
    var forward by remember { mutableStateOf(true) }
    val back = {
        forward = false
        focus.clearFocus()
        when {
            state.session != null -> vm.selectSession(null)
            state.deviceId != null -> vm.selectDevice(null)
            else -> onBack()
        }
    }
    BackHandler(active, back)
    val target = state.deviceId to state.session
    GuardedAnimatedContent(targetState = target, forward = forward) { page ->
        var retained by remember(page) { mutableStateOf(state) }
        val current = page == target
        SideEffect { if (current) retained = state }
        val displayed = if (current) state else retained
        when {
            page.second != null -> RemoteConversation(displayed, vm, settings, active && current, back)
            page.first != null -> CollapsingSettingsLazyScaffold(
                title = stringResource(R.string.remote_sessions), onBack = back,
                actions = { IconButton(onClick = vm::refresh, enabled = current) {
                    Icon(Icons.Default.Refresh, stringResource(R.string.remote_refresh))
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
                            headlineContent = { Text(session.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                            supportingContent = { Text(session.cwd, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            leadingContent = { Icon(Icons.Default.ChatBubbleOutline, null) },
                        )
                    } })
                }
                if (displayed.sessionCursor != null) item {
                    TextButton(onClick = vm::loadMore, enabled = current) { Text(stringResource(R.string.remote_load_more)) }
                }
            }
            else -> RemoteDevices(displayed, vm, back) { forward = true }
        }
    }
}

@Composable
private fun RemoteDevices(state: RemoteState, vm: RemoteViewModel, onBack: () -> Unit, onForward: () -> Unit) {
    var address by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    CollapsingSettingsScaffold(title = stringResource(R.string.remote_title), onBack = onBack) {
        if (state.restoring) Text(stringResource(R.string.loading_label), Modifier.padding(16.dp))
        if (state.storageError) TextButton(onClick = vm::restoreConnections,
            enabled = !state.restoring && !state.connecting) {
            Text(stringResource(R.string.remote_storage_failed))
        }
        if (state.devices.isNotEmpty()) SettingsGroup(
            title = stringResource(R.string.remote_devices),
            items = state.devices.map { device -> {
                SettingsItem(
                    modifier = Modifier.clickable(enabled = !state.restoring && !state.connecting) {
                        onForward(); vm.selectDevice(device.id)
                    },
                    headlineContent = { Text(device.name) },
                    supportingContent = { Text(device.address) },
                    leadingContent = { Icon(Icons.Default.Computer, null) },
                    trailingContent = { IconButton(onClick = { vm.removeDevice(device.id) },
                        enabled = !state.restoring && !state.connecting) {
                        Icon(Icons.Default.LinkOff, stringResource(R.string.remote_remove),
                            tint = MaterialTheme.colorScheme.error)
                    } },
                )
            } },
        )
        SettingsGroup(title = stringResource(R.string.remote_connect), items = listOf({
            SettingsIconContent(Icons.Default.Link) {
                Text(stringResource(R.string.remote_connection_hint), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(value = address, onValueChange = { address = it }, singleLine = true,
                    label = { Text(stringResource(R.string.remote_address)) },
                    placeholder = { Text("http://100.x.y.z:7435") },
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp))
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = token, onValueChange = { token = it }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                    label = { Text(stringResource(R.string.remote_token)) },
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp))
                Spacer(Modifier.height(12.dp))
                Button(onClick = { onForward(); vm.connect(address, token) },
                    enabled = !state.restoring && !state.connecting && address.isNotBlank() && token.isNotBlank(),
                    modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Text(stringResource(if (state.connecting) R.string.loading_label else R.string.remote_connect))
                }
            }
        }))
        if (state.error) Text(stringResource(R.string.remote_failed), Modifier.padding(16.dp),
            color = MaterialTheme.colorScheme.error)
    }
}

@Composable
internal fun RemoteReadStatus(state: RemoteState, retry: () -> Unit) {
    if (state.error) TextButton(onClick = retry) { Text(stringResource(R.string.remote_failed)) }
    else if (state.loading) Text(stringResource(R.string.loading_label), Modifier.padding(16.dp))
}
