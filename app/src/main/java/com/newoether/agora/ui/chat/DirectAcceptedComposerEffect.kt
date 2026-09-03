package com.newoether.agora.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import com.newoether.agora.viewmodel.ChatViewModel
import kotlinx.coroutines.flow.collect

/**
 * Direct foreground acceptance is the only Composer event that dismisses the IME. This binding
 * lives above the owner-specific bottom bar so New Chat publication cannot dispose it mid-event.
 */
@Composable
internal fun BindDirectAcceptedComposerEffects(
    viewModel: ChatViewModel,
    onCollapse: () -> Unit,
) {
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    LaunchedEffect(viewModel, focusManager, keyboardController) {
        viewModel.conversationComposerSubmission.directAcceptedEffects.collect { accepted ->
            val originStillVisible = if (accepted.newChatEntryId == null) {
                viewModel.currentConversationId.value == accepted.conversationId
            } else {
                viewModel.isNewChatMode.value &&
                    viewModel.currentConversationId.value == null &&
                    viewModel.newChatEntryId.value == accepted.newChatEntryId
            }
            if (originStillVisible) {
                focusManager.clearFocus()
                keyboardController?.hide()
                onCollapse()
            }
        }
    }
}
