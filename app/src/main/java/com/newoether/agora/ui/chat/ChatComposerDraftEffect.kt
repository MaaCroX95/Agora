package com.newoether.agora.ui.chat

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import com.newoether.agora.util.DebugLog
import com.newoether.agora.viewmodel.ChatViewModel
import com.newoether.agora.viewmodel.ConversationComposerController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val DRAFT_TEXT_DEBOUNCE_MS = 300L
private const val DRAFT_PERSIST_RETRY_COUNT = 2
private const val DRAFT_PERSIST_RETRY_DELAY_MS = 80L

@Composable
internal fun ComposerDraftLifecycleEffect(
    ownerId: String,
    controller: ConversationComposerController,
    viewModel: ChatViewModel,
    textFieldState: TextFieldState,
) {
    LaunchedEffect(ownerId) {
        viewModel.loadingDraft = true
        val loaded = try {
            controller.loadSelected(ownerId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            DebugLog.e("AgoraUI", "Failed to load composer draft for $ownerId", failure)
            return@LaunchedEffect
        } finally {
            viewModel.loadingDraft = false
        }
        try {
            if (!loaded.loaded) return@LaunchedEffect
            if (textFieldState.text.toString() != loaded.text) {
                textFieldState.edit { replace(0, length, loaded.text) }
            }

            var projectedTextVersion = loaded.textProjectionVersion
            var dirtyText: String? = null

            suspend fun projectAuthoritativeText() {
                val current = controller.state(ownerId).value
                if (current.textProjectionVersion != projectedTextVersion) {
                    projectedTextVersion = current.textProjectionVersion
                    dirtyText = null
                    if (textFieldState.text.toString() != current.text) {
                        textFieldState.edit { replace(0, length, current.text) }
                    }
                }
            }

            suspend fun persistIfCurrent(text: String): Boolean {
                var failureCount = 0
                while (controller.state(ownerId).value.text == text) {
                    if (controller.persistText(ownerId, text)) return true
                    if (controller.state(ownerId).value.text != text) return true
                    if (failureCount >= DRAFT_PERSIST_RETRY_COUNT) return false
                    failureCount += 1
                    delay(DRAFT_PERSIST_RETRY_DELAY_MS * failureCount)
                }
                return true
            }

            try {
                coroutineScope {
                    launch {
                        controller.state(ownerId)
                            .map { it.textProjectionVersion }
                            .distinctUntilChanged()
                            .collect { textProjectionVersion ->
                                if (textProjectionVersion != projectedTextVersion) {
                                    projectAuthoritativeText()
                                }
                            }
                    }
                    snapshotFlow { textFieldState.text.toString() }
                        .distinctUntilChanged()
                        .collectLatest { text ->
                            if (
                                controller.state(ownerId).value.textProjectionVersion !=
                                    projectedTextVersion
                            ) {
                                projectAuthoritativeText()
                                return@collectLatest
                            }
                            if (controller.state(ownerId).value.text == text) return@collectLatest
                            dirtyText = text
                            controller.updateText(ownerId, text)
                            delay(DRAFT_TEXT_DEBOUNCE_MS)
                            if (persistIfCurrent(text) && dirtyText == text) dirtyText = null
                        }
                }
            } finally {
                dirtyText?.let { text ->
                    withContext(NonCancellable) {
                        persistIfCurrent(text)
                    }
                }
            }
        } finally {
            withContext(NonCancellable) {
                controller.releaseSelected(ownerId)
            }
        }
    }
}
