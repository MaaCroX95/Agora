package com.newoether.agora.ui.remote

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.remote.RemoteUsage
import com.newoether.agora.remote.RemoteViewModel
import com.newoether.agora.ui.motion.MotionAwareModalBottomSheet
import com.newoether.agora.ui.motion.MotionAwareCircularProgressIndicator

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RemoteUsageSheet(vm: RemoteViewModel, onDismiss: () -> Unit) {
    var usage by remember { mutableStateOf<RemoteUsage?>(null) }
    var loading by remember { mutableStateOf(true) }
    LaunchedEffect(vm) { usage = vm.usage(); loading = false }
    MotionAwareModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        com.newoether.agora.ui.components.DialogWindowEdgeToEdge()
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.remote_usage), style = MaterialTheme.typography.titleLarge)
            if (loading) MotionAwareCircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            else if (usage?.limits.isNullOrEmpty()) Text(stringResource(R.string.remote_usage_unavailable),
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            usage?.limits?.forEach { limit ->
                Text(limit.name.ifBlank { limit.id }, style = MaterialTheme.typography.titleMedium)
                listOfNotNull(limit.primary, limit.secondary).forEach { window ->
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(stringResource(R.string.remote_usage_remaining, window.remainingPercent), style = MaterialTheme.typography.bodyMedium)
                        LinearProgressIndicator(progress = { window.remainingPercent / 100f }, modifier = Modifier.fillMaxWidth())
                        window.windowDurationMins?.let { minutes ->
                            Text(stringResource(R.string.remote_usage_window, minutes), style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        window.resetsAt?.let { seconds ->
                            Text(stringResource(R.string.remote_usage_resets,
                                android.text.format.DateUtils.getRelativeTimeSpanString(seconds * 1000).toString()),
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}
