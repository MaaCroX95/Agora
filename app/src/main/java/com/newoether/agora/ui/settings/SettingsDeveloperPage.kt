package com.newoether.agora.ui.settings

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import androidx.core.content.FileProvider
import com.newoether.agora.diagnostics.CapturedDiagnosticText
import com.newoether.agora.diagnostics.DeveloperConversationInspection
import com.newoether.agora.diagnostics.DeveloperConversationInspector
import com.newoether.agora.diagnostics.DeveloperDiagnostics
import com.newoether.agora.diagnostics.DeveloperTestLab
import com.newoether.agora.diagnostics.DeveloperTestResult
import com.newoether.agora.diagnostics.DiagnosticBundleExporter
import com.newoether.agora.diagnostics.DiagnosticEvent
import com.newoether.agora.diagnostics.DiagnosticEventPayload
import com.newoether.agora.diagnostics.DiagnosticExportFormat
import com.newoether.agora.diagnostics.DiagnosticSnapshot
import com.newoether.agora.diagnostics.forDisplay
import com.newoether.agora.viewmodel.ChatViewModel
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsDeveloperPage(
    viewModel: ChatViewModel,
    onBack: () -> Unit,
    onDisabled: () -> Unit,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val enabled by viewModel.settings.developerOptionsEnabled.collectAsState()
    val diagnostics by DeveloperDiagnostics.snapshots.collectAsState()
    val customProviders by viewModel.settings.customProviders.collectAsState()
    val displayDiagnostics = remember(diagnostics, customProviders) {
        diagnostics.forDisplay(customProviders)
    }
    val currentConversation by viewModel.currentConversation.collectAsState()
    val messages by viewModel.messages.collectAsState()
    val totalTokens by viewModel.totalTokens.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    var showDisableDialog by remember { mutableStateOf(false) }
    var selectedEvent by remember { mutableStateOf<DiagnosticEvent?>(null) }
    var detailDialog by remember { mutableStateOf<Pair<String, String>?>(null) }
    var testResults by remember { mutableStateOf<List<DeveloperTestResult>?>(null) }
    val runtimeTransitions = currentConversation?.id
        ?.let(viewModel::developerRuntimeTraceSnapshot)
        .orEmpty()
    val conversationInspection = remember(
        currentConversation,
        messages,
        totalTokens,
        isLoading,
        runtimeTransitions,
        customProviders,
    ) {
        DeveloperConversationInspector.inspect(
            conversation = currentConversation,
            messages = messages,
            totalTokens = totalTokens,
            isLoading = isLoading,
            runtimeTransitions = runtimeTransitions,
        )?.forDisplay(customProviders)
    }
    val captureDescription = diagnostics.captureDescription()
    val inspectorTitle = stringResource(R.string.developer_options_inspector)
    val testLabTitle = stringResource(R.string.developer_options_test_lab)
    val exportShareTitle = stringResource(R.string.developer_options_export_share_title)
    val exportFailedMessage = stringResource(R.string.developer_options_export_failed)
    val hasDiagnostics = diagnostics.session != null || diagnostics.events.isNotEmpty()
    val visibleEvents = displayDiagnostics.events.takeLast(MAX_VISIBLE_EVENTS)
    val timelineItems: List<@Composable () -> Unit> = if (visibleEvents.isEmpty()) {
        listOf({
            SettingsItem(
                headlineContent = {
                    Text(stringResource(R.string.developer_options_timeline_empty))
                },
                leadingContent = {
                    Icon(Icons.Default.Timeline, contentDescription = null)
                },
            )
        })
    } else {
        visibleEvents.asReversed().map { event ->
            {
                DiagnosticTimelineItem(event) {
                    selectedEvent = event
                }
            }
        }
    }

    selectedEvent?.let { event ->
        AlertDialog(
            onDismissRequest = { selectedEvent = null },
            title = {
                Text(
                    text = "#" + event.sequence + " " + event.payload.eventTypeName(),
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                )
            },
            text = {
                SelectionContainer {
                    Text(
                        text = event.fullDetails(),
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier
                            .heightIn(max = 520.dp)
                            .verticalScroll(rememberScrollState()),
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

    detailDialog?.let { (title, details) ->
        AlertDialog(
            onDismissRequest = { detailDialog = null },
            title = { Text(title, fontWeight = FontWeight.Bold) },
            text = {
                SelectionContainer {
                    Text(
                        text = details,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier
                            .heightIn(max = 520.dp)
                            .verticalScroll(rememberScrollState()),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { detailDialog = null }) {
                    Text(stringResource(R.string.ok))
                }
            },
        )
    }

    if (showDisableDialog) {
        AlertDialog(
            onDismissRequest = { showDisableDialog = false },
            icon = { Icon(Icons.Default.BugReport, contentDescription = null) },
            title = {
                Text(
                    text = stringResource(R.string.developer_options_disable_title),
                    fontWeight = FontWeight.Bold,
                )
            },
            text = { Text(stringResource(R.string.developer_options_disable_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDisableDialog = false
                        coroutineScope.launch {
                            DeveloperDiagnostics.disableAndClear()
                            viewModel.settings.setDeveloperOptionsEnabled(false)
                            onDisabled()
                        }
                    },
                ) {
                    Text(stringResource(R.string.developer_options_disable_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDisableDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    CollapsingSettingsScaffold(
        title = stringResource(R.string.developer_options_title),
        onBack = onBack,
    ) {
        SettingsGroupColumn {
            SettingsGroup(
                title = stringResource(R.string.developer_options_status_group),
                items = listOf({
                    SettingsItem(
                        headlineContent = { Text(stringResource(R.string.settings_developer)) },
                        supportingContent = {
                            Text(stringResource(R.string.developer_options_enabled_desc))
                        },
                        leadingContent = {
                            Icon(Icons.Default.BugReport, contentDescription = null)
                        },
                        trailingContent = {
                            Switch(
                                checked = enabled,
                                onCheckedChange = { checked ->
                                    if (!checked) showDisableDialog = true
                                },
                            )
                        },
                        modifier = Modifier.clickable(enabled = enabled) {
                            showDisableDialog = true
                        },
                    )
                }),
            )

            SettingsGroup(
                title = stringResource(R.string.developer_options_capture_group),
                items = listOf({
                    SettingsItem(
                        headlineContent = {
                            Text(stringResource(R.string.developer_options_capture))
                        },
                        supportingContent = { Text(captureDescription) },
                        leadingContent = {
                            Icon(Icons.Default.Visibility, contentDescription = null)
                        },
                        trailingContent = {
                            Switch(
                                checked = diagnostics.isCaptureActive,
                                onCheckedChange = { active ->
                                    coroutineScope.launch {
                                        if (active) {
                                            DeveloperDiagnostics.startCapture()
                                        } else {
                                            DeveloperDiagnostics.pauseCapture()
                                        }
                                    }
                                },
                            )
                        },
                        modifier = Modifier.clickable {
                            coroutineScope.launch {
                                if (diagnostics.isCaptureActive) {
                                    DeveloperDiagnostics.pauseCapture()
                                } else {
                                    DeveloperDiagnostics.startCapture()
                                }
                            }
                        },
                    )
                }, {
                    SettingsItem(
                        headlineContent = {
                            Text(stringResource(R.string.developer_options_clear_diagnostics))
                        },
                        supportingContent = {
                            Text(stringResource(R.string.developer_options_clear_diagnostics_desc))
                        },
                        leadingContent = {
                            Icon(Icons.Default.DeleteSweep, contentDescription = null)
                        },
                        modifier = Modifier.clickable(enabled = hasDiagnostics) {
                            coroutineScope.launch {
                                DeveloperDiagnostics.clear()
                            }
                        },
                    )
                }),
            )

            SettingsGroup(
                title = stringResource(R.string.developer_options_timeline_group),
                items = timelineItems,
            )

            SettingsGroup(
                title = stringResource(R.string.developer_options_inspector_group),
                items = listOf({
                    SettingsItem(
                        headlineContent = { Text(inspectorTitle) },
                        supportingContent = {
                            val inspection = conversationInspection
                            if (inspection == null) {
                                Text(stringResource(R.string.developer_options_inspector_empty))
                            } else {
                                Text(
                                    "conversation=" +
                                        inspection.conversationIdHash.take(SHORT_ID_LENGTH) +
                                        " · messages=" + inspection.messageCount +
                                        " · runtime=" + inspection.runtimeTransitions.size,
                                    fontFamily = FontFamily.Monospace,
                                )
                            }
                        },
                        leadingContent = {
                            Icon(Icons.Default.Info, contentDescription = null)
                        },
                        modifier = Modifier.clickable(enabled = conversationInspection != null) {
                            conversationInspection?.let { inspection ->
                                detailDialog = inspectorTitle to
                                    DeveloperConversationInspector.format(inspection)
                            }
                        },
                    )
                }),
            )

            SettingsGroup(
                title = stringResource(R.string.developer_options_export_group),
                items = listOf({
                    SettingsItem(
                        headlineContent = {
                            Text(stringResource(R.string.developer_options_export))
                        },
                        supportingContent = {
                            Text(stringResource(R.string.developer_options_export_desc))
                        },
                        leadingContent = {
                            Icon(Icons.Default.Share, contentDescription = null)
                        },
                        modifier = Modifier.clickable(
                            enabled = hasDiagnostics || conversationInspection != null,
                        ) {
                            val snapshotForExport = diagnostics
                            coroutineScope.launch {
                                runCatching {
                                    shareDiagnosticBundle(
                                        context = context,
                                        snapshot = snapshotForExport,
                                        chooserTitle = exportShareTitle,
                                    )
                                }.onFailure {
                                    viewModel.emitSnackbar(exportFailedMessage)
                                }
                            }
                        },
                    )
                }),
            )

            SettingsGroup(
                title = stringResource(R.string.developer_options_test_lab_group),
                items = listOf({
                    SettingsItem(
                        headlineContent = { Text(testLabTitle) },
                        supportingContent = {
                            val results = testResults
                            Text(
                                if (results == null) {
                                    stringResource(R.string.developer_options_test_lab_desc)
                                } else {
                                    results.count(DeveloperTestResult::passed).toString() +
                                        "/" + results.size + " PASS"
                                },
                            )
                        },
                        leadingContent = {
                            Icon(Icons.Default.Science, contentDescription = null)
                        },
                        modifier = Modifier.clickable {
                            val results = DeveloperTestLab.runAll()
                            testResults = results
                            detailDialog = testLabTitle to formatTestResults(results)
                        },
                    )
                }),
            )
        }
    }
}

@Composable
private fun DiagnosticSnapshot.captureDescription(): String = when {
    isCaptureActive -> stringResource(
        R.string.developer_options_capture_sensitive_desc,
        events.size,
        droppedEventCount,
    )
    session != null -> stringResource(
        R.string.developer_options_capture_stopped_desc,
        events.size,
        droppedEventCount,
    )
    else -> stringResource(R.string.developer_options_capture_off_desc)
}

@Composable
private fun DiagnosticTimelineItem(
    event: DiagnosticEvent,
    onClick: () -> Unit,
) {
    SettingsItem(
        headlineContent = {
            Text(
                text = "#" + event.sequence + " " + event.payload.summary(),
                fontFamily = FontFamily.Monospace,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = event.context.summary().takeIf(String::isNotBlank)?.let { summary ->
            {
                Text(
                    text = summary,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

private fun DiagnosticEventPayload.summary(): String = when (this) {
    is DiagnosticEventPayload.HttpStage -> buildString {
        append(stage)
        append(" · ")
        append(elapsedMillis)
        append(" ms")
        if (attributes.isNotEmpty()) {
            append(" · ")
            append(attributes.entries.joinToString { (key, value) -> "$key=$value" })
        }
    }
    is DiagnosticEventPayload.RuntimeTransition ->
        commandType + " · " + oldState + " → " + newState
    is DiagnosticEventPayload.HttpRequest ->
        "HTTP " + method + " request · bodyChars=" + body.originalLength
    is DiagnosticEventPayload.HttpResponseBody ->
        "HTTP response body · code=" + code + " · chars=" + body.originalLength
    is DiagnosticEventPayload.WireLine ->
        "wire line " + lineNumber + " · chars=" + line.originalLength
    is DiagnosticEventPayload.ParsedStreamEvent -> buildString {
        append("parsed ")
        append(eventType)
        if (attributes.isNotEmpty()) {
            append(" · ")
            append(attributes.entries.joinToString { (key, value) -> "$key=$value" })
        }
    }
}

private fun DiagnosticEventPayload.eventTypeName(): String = when (this) {
    is DiagnosticEventPayload.HttpStage -> "HttpStage"
    is DiagnosticEventPayload.RuntimeTransition -> "RuntimeTransition"
    is DiagnosticEventPayload.HttpRequest -> "HttpRequest"
    is DiagnosticEventPayload.HttpResponseBody -> "HttpResponseBody"
    is DiagnosticEventPayload.WireLine -> "WireLine"
    is DiagnosticEventPayload.ParsedStreamEvent -> "ParsedStreamEvent"
}

private fun com.newoether.agora.diagnostics.DiagnosticRequestContext.summary(): String =
    listOfNotNull(
        requestKind?.let { "kind=$it" },
        provider?.let { "provider=$it" },
        model?.let { "model=$it" },
        requestId?.let { "request=" + it.take(SHORT_ID_LENGTH) },
        conversationIdHash?.let { "conversation=" + it.take(SHORT_ID_LENGTH) },
        runId?.let { "run=" + it.take(SHORT_ID_LENGTH) },
        pass?.let { "pass=$it" },
    ).joinToString(" · ")

private fun DiagnosticEvent.fullDetails(): String = buildString {
    appendLine(context.summary())
    appendLine("timestampMillis=" + timestampMillis)
    when (val data = payload) {
        is DiagnosticEventPayload.HttpStage -> {
            appendLine("stage=" + data.stage)
            appendLine("elapsedMillis=" + data.elapsedMillis)
            data.attributes.forEach { (key, value) -> appendLine(key + "=" + value) }
        }
        is DiagnosticEventPayload.RuntimeTransition -> {
            appendLine("command=" + data.commandType)
            appendLine("state=" + data.oldState + " → " + data.newState)
            appendLine("effectId=" + data.effectId.orEmpty())
            appendLine("effects=" + data.effectTypes.joinToString())
        }
        is DiagnosticEventPayload.HttpRequest -> {
            appendLine("method=" + data.method)
            appendLine("url=" + data.url.display())
            appendLine("headers:")
            data.headers.forEach { (name, value) -> appendLine(name + ": " + value) }
            appendLine("body:")
            append(data.body.display())
        }
        is DiagnosticEventPayload.HttpResponseBody -> {
            appendLine("code=" + data.code)
            append(data.body.display())
        }
        is DiagnosticEventPayload.WireLine -> {
            appendLine("lineNumber=" + data.lineNumber)
            append(data.line.display())
        }
        is DiagnosticEventPayload.ParsedStreamEvent -> {
            appendLine("eventType=" + data.eventType)
            data.attributes.forEach { (key, value) -> appendLine(key + "=" + value) }
            data.content?.let {
                appendLine("content:")
                append(it.display())
            }
        }
    }
}

private fun CapturedDiagnosticText.display(): String = buildString {
    append(value)
    if (truncated) {
        appendLine()
        append("[TRUNCATED originalChars=")
        append(originalLength)
        append("]")
    }
}

private fun formatTestResults(results: List<DeveloperTestResult>): String = buildString {
    appendLine(
        results.count(DeveloperTestResult::passed).toString() +
            "/" + results.size + " PASS",
    )
    results.forEach { result ->
        appendLine()
        append(result.id)
        append(": ")
        append(if (result.passed) "PASS" else result.detail)
    }
}

private suspend fun shareDiagnosticBundle(
    context: Context,
    snapshot: DiagnosticSnapshot,
    chooserTitle: String,
) {
    val bundle = withContext(Dispatchers.Default) {
        DiagnosticBundleExporter.export(
            snapshot = snapshot,
            format = DiagnosticExportFormat.REDACTED_JSON,
        )
    }
    val sendIntent = withContext(Dispatchers.IO) {
        val shareDirectory = File(context.cacheDir, "shared").apply { mkdirs() }
        val file = File(shareDirectory, "agora_diagnostics.json").apply {
            writeText(bundle, Charsets.UTF_8)
        }
        val uri = FileProvider.getUriForFile(
            context,
            context.packageName + ".fileprovider",
            file,
        )
        Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
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

private const val MAX_VISIBLE_EVENTS = 40
private const val SHORT_ID_LENGTH = 12
