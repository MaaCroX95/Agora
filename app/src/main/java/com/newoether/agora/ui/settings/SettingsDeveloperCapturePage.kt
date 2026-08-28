package com.newoether.agora.ui.settings

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.newoether.agora.R
import com.newoether.agora.diagnostics.DeveloperDiagnostics
import com.newoether.agora.diagnostics.DiagnosticCaptureState
import com.newoether.agora.diagnostics.DiagnosticEvent
import com.newoether.agora.diagnostics.DiagnosticEventPayload
import com.newoether.agora.diagnostics.DiagnosticExportFormat
import com.newoether.agora.diagnostics.DiagnosticRequestContext
import com.newoether.agora.diagnostics.DiagnosticSnapshot
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

private enum class CaptureViewMode {
    SUMMARY,
    RAW,
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsDeveloperCapturePage(
    onBack: () -> Unit,
    onExportFailed: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val snapshot by DeveloperDiagnostics.snapshots.collectAsState()
    val isDragged by listState.interactionSource.collectIsDraggedAsState()
    var viewMode by rememberSaveable { mutableStateOf(CaptureViewMode.SUMMARY) }
    var followLatest by rememberSaveable { mutableStateOf(true) }
    var selectedEvent by remember { mutableStateOf<DiagnosticEvent?>(null) }
    var showClearConfirmation by remember { mutableStateOf(false) }
    var showExportMenu by remember { mutableStateOf(false) }
    val hasCaptureData = snapshot.hasCaptureData()
    val chooserTitle = stringResource(R.string.developer_options_export_share_title)

    LaunchedEffect(isDragged, listState.canScrollForward) {
        when {
            isDragged && listState.canScrollForward -> followLatest = false
            !listState.canScrollForward -> followLatest = true
        }
    }
    LaunchedEffect(snapshot.events.lastOrNull()?.sequence, viewMode, followLatest) {
        if (followLatest && snapshot.events.isNotEmpty()) {
            listState.scrollToLatestCaptureEvent(snapshot.events.size)
        }
    }

    selectedEvent?.let { event ->
        val eventDetails = remember(event, viewMode) { event.details(viewMode) }
        AlertDialog(
            onDismissRequest = { selectedEvent = null },
            title = {
                Text(
                    text = "#${event.sequence} ${event.payload.typeName()}",
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                )
            },
            text = {
                SelectionContainer {
                    Text(
                        text = eventDetails,
                        modifier = Modifier
                            .heightIn(max = 520.dp)
                            .verticalScroll(rememberScrollState()),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { selectedEvent = null }) {
                    Text(stringResource(R.string.ok))
                }
            },
        )
    }

    if (showClearConfirmation) {
        AlertDialog(
            onDismissRequest = { showClearConfirmation = false },
            title = { Text(stringResource(R.string.developer_options_clear_diagnostics)) },
            text = {
                Text("Clear captured events and counters? Capture state and session are preserved.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearConfirmation = false
                        scope.launch { DeveloperDiagnostics.clear() }
                    },
                ) {
                    Text("Clear")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirmation = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    CollapsingSettingsLazyScaffold(
        title = stringResource(R.string.developer_options_capture),
        onBack = onBack,
        listState = listState,
        contentHorizontalPadding = 0.dp,
        floatingActionButton = {
            if (!followLatest && snapshot.events.isNotEmpty()) {
                CaptureTooltip(label = "Jump to latest") {
                    SmallFloatingActionButton(
                        onClick = { followLatest = true },
                    ) {
                        Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Jump to latest")
                    }
                }
            }
        },
    ) {
        item(key = "capture-controls") {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                CaptureToolbar(
                    snapshot = snapshot,
                    hasCaptureData = hasCaptureData,
                    exportExpanded = showExportMenu,
                    onStart = { scope.launch { DeveloperDiagnostics.startCapture() } },
                    onPause = { scope.launch { DeveloperDiagnostics.pauseCapture() } },
                    onClear = { showClearConfirmation = true },
                    onExportMenuChange = { showExportMenu = it },
                    onExport = { format ->
                        showExportMenu = false
                        scope.launch {
                            try {
                                val flushed = DeveloperDiagnostics.flush()
                                shareDiagnosticCapture(
                                    context = context,
                                    snapshot = flushed,
                                    format = format,
                                    chooserTitle = chooserTitle,
                                )
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                onExportFailed()
                            }
                        }
                    },
                )
                Spacer(Modifier.height(8.dp))
                CaptureViewSelector(
                    selected = viewMode,
                    onSelected = { viewMode = it },
                )
            }
        }

        item(key = "capture-summary") {
            CaptureSnapshotSummary(snapshot)
        }

        if (snapshot.events.isEmpty()) {
            item(key = "capture-empty") {
                Text(
                    text = "No captured events.",
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 28.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        } else {
            items(snapshot.events, key = DiagnosticEvent::sequence) { event ->
                CaptureEventRow(
                    event = event,
                    viewMode = viewMode,
                    onClick = { selectedEvent = event },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CaptureToolbar(
    snapshot: DiagnosticSnapshot,
    hasCaptureData: Boolean,
    exportExpanded: Boolean,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onClear: () -> Unit,
    onExportMenuChange: (Boolean) -> Unit,
    onExport: (DiagnosticExportFormat) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        CaptureIconAction(
            label = "Start",
            icon = Icons.Default.PlayArrow,
            enabled = snapshot.state != DiagnosticCaptureState.RUNNING,
            onClick = onStart,
        )
        CaptureIconAction(
            label = "Pause",
            icon = Icons.Default.Pause,
            enabled = snapshot.state == DiagnosticCaptureState.RUNNING,
            onClick = onPause,
        )
        CaptureIconAction(
            label = "Clear",
            icon = Icons.Default.DeleteSweep,
            enabled = hasCaptureData,
            onClick = onClear,
        )
        Box {
            CaptureIconAction(
                label = "Export",
                icon = Icons.Default.FileUpload,
                enabled = hasCaptureData,
                onClick = { onExportMenuChange(true) },
            )
            DropdownMenu(
                expanded = exportExpanded,
                onDismissRequest = { onExportMenuChange(false) },
            ) {
                DropdownMenuItem(
                    text = { Text("Raw JSON") },
                    onClick = { onExport(DiagnosticExportFormat.RAW_JSON) },
                )
                DropdownMenuItem(
                    text = { Text("Redacted JSON") },
                    onClick = { onExport(DiagnosticExportFormat.REDACTED_JSON) },
                )
                DropdownMenuItem(
                    text = { Text("Summary Text") },
                    onClick = { onExport(DiagnosticExportFormat.SUMMARY_TEXT) },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CaptureIconAction(
    label: String,
    icon: ImageVector,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    CaptureTooltip(label) {
        IconButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.size(48.dp),
        ) {
            Icon(icon, contentDescription = label)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CaptureTooltip(
    label: String,
    content: @Composable () -> Unit,
) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(
            TooltipAnchorPosition.Above,
        ),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState(),
        content = { content() },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CaptureViewSelector(
    selected: CaptureViewMode,
    onSelected: (CaptureViewMode) -> Unit,
) {
    val options = CaptureViewMode.entries
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, option ->
            SegmentedButton(
                selected = selected == option,
                onClick = { onSelected(option) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
                label = {
                    Text(
                        when (option) {
                            CaptureViewMode.SUMMARY -> "Summary"
                            CaptureViewMode.RAW -> "Raw"
                        },
                    )
                },
            )
        }
    }
}

@Composable
private fun CaptureSnapshotSummary(snapshot: DiagnosticSnapshot) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Text(
            text = buildString {
                append(snapshot.state.name.lowercase().replaceFirstChar(Char::uppercase))
                append(" · ")
                append(snapshot.events.size)
                append(" events · ")
                append(formatBytes(snapshot.retainedPayloadBytes))
            },
            fontWeight = FontWeight.Medium,
        )
        Text(
            text = "Dropped ${snapshot.droppedEventCount} · " +
                "Evicted ${snapshot.evictedEventCount} · " +
                "Truncated ${snapshot.truncatedPayloadCount}",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
        snapshot.session?.let { session ->
            Text(
                text = "Session ${session.id.take(12)} · next #${snapshot.nextSequence}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
    HorizontalDivider()
}

@Composable
private fun CaptureEventRow(
    event: DiagnosticEvent,
    viewMode: CaptureViewMode,
    onClick: () -> Unit,
) {
    val rowDetails = remember(event, viewMode) {
        if (viewMode == CaptureViewMode.RAW) event.rawDetails() else null
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        when (viewMode) {
            CaptureViewMode.SUMMARY -> {
                Text(
                    text = "#${event.sequence} ${event.payload.summary()}",
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodyMedium,
                )
                event.context.summary().takeIf(String::isNotBlank)?.let { context ->
                    Text(
                        text = context,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            CaptureViewMode.RAW -> {
                Text(
                    text = checkNotNull(rowDetails),
                    maxLines = 8,
                    overflow = TextOverflow.Ellipsis,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
}

private suspend fun LazyListState.scrollToLatestCaptureEvent(eventCount: Int) {
    if (eventCount <= 0) return
    val targetIndex = eventCount + 1
    snapshotFlow { layoutInfo.totalItemsCount }.first { it > targetIndex }
    scrollToItem(targetIndex)
}

private fun DiagnosticSnapshot.hasCaptureData(): Boolean =
    session != null || events.isNotEmpty() || droppedEventCount > 0L ||
        evictedEventCount > 0L || truncatedPayloadCount > 0L

private fun DiagnosticEventPayload.summary(): String = when (this) {
    is DiagnosticEventPayload.RuntimeTransition -> "$commandType · $oldState -> $newState"
    is DiagnosticEventPayload.HttpStage -> "$stage · $elapsedMillis ms"
    is DiagnosticEventPayload.HttpRequest -> "HTTP $method · bodyChars=${body.originalLength}"
    is DiagnosticEventPayload.HttpResponseBody -> "HTTP response · code=$code · chars=${body.originalLength}"
    is DiagnosticEventPayload.WireLine -> "wire line $lineNumber · chars=${line.originalLength}"
    is DiagnosticEventPayload.ParsedStreamEvent -> "parsed $eventType"
}

private fun DiagnosticEventPayload.typeName(): String = when (this) {
    is DiagnosticEventPayload.RuntimeTransition -> "RuntimeTransition"
    is DiagnosticEventPayload.HttpStage -> "HttpStage"
    is DiagnosticEventPayload.HttpRequest -> "HttpRequest"
    is DiagnosticEventPayload.HttpResponseBody -> "HttpResponseBody"
    is DiagnosticEventPayload.WireLine -> "WireLine"
    is DiagnosticEventPayload.ParsedStreamEvent -> "ParsedStreamEvent"
}

private fun DiagnosticRequestContext.summary(): String = listOfNotNull(
    requestKind?.let { "kind=$it" },
    provider?.let { "provider=$it" },
    model?.let { "model=$it" },
    requestId?.let { "request=${it.take(12)}" },
    conversationIdHash?.let { "conversation=${it.take(12)}" },
    runId?.let { "run=${it.take(12)}" },
    pass?.let { "pass=$it" },
).joinToString(" · ")

private fun DiagnosticEvent.details(viewMode: CaptureViewMode): String = when (viewMode) {
    CaptureViewMode.SUMMARY -> buildString {
        appendLine("#$sequence ${payload.summary()}")
        context.summary().takeIf(String::isNotBlank)?.let(::appendLine)
        append("timestampMillis=$timestampMillis")
    }
    CaptureViewMode.RAW -> rawDetails()
}

private val captureEventJson = Json {
    classDiscriminator = "payloadType"
    encodeDefaults = true
    prettyPrint = true
}

private fun DiagnosticEvent.rawDetails(): String =
    captureEventJson.encodeToString(DiagnosticEvent.serializer(), this)

private fun formatBytes(bytes: Long): String = when {
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024L -> String.format("%.1f KiB", bytes / 1024.0)
    else -> String.format("%.1f MiB", bytes / (1024.0 * 1024.0))
}

private suspend fun shareDiagnosticCapture(
    context: Context,
    snapshot: DiagnosticSnapshot,
    format: DiagnosticExportFormat,
    chooserTitle: String,
) {
    val content = withContext(Dispatchers.Default) {
        com.newoether.agora.diagnostics.DiagnosticBundleExporter.export(snapshot, format)
    }
    val sendIntent = withContext(Dispatchers.IO) {
        val extension = if (format == DiagnosticExportFormat.SUMMARY_TEXT) "txt" else "json"
        val mimeType = if (extension == "txt") "text/plain" else "application/json"
        val formatName = format.name.lowercase().replace('_', '-')
        val shareDirectory = File(context.cacheDir, "shared").apply { mkdirs() }
        val file = File(shareDirectory, "agora-diagnostics-$formatName.$extension").apply {
            writeText(content, Charsets.UTF_8)
        }
        val uri = FileProvider.getUriForFile(
            context,
            context.packageName + ".fileprovider",
            file,
        )
        Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri("Agora diagnostics", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
    withContext(Dispatchers.Main.immediate) {
        val chooser = Intent.createChooser(sendIntent, chooserTitle)
        if (context !is Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    }
}
