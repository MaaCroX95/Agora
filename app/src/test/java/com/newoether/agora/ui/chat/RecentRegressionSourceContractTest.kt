package com.newoether.agora.ui.chat

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecentRegressionSourceContractTest {
    @Test
    fun attachmentPainterRemainsDrawnWhileLoading() {
        val source = source("ui/chat/bottombar/AttachmentPreviewRow.kt")
        val loading = source.substringAfter(
            "AttachmentPreviewPresentation.MEDIA_LOADING -> {",
        ).substringBefore("AttachmentPreviewPresentation.MEDIA_SUCCESS")

        assertTrue(loading.contains("Image("))
        assertTrue(loading.contains("painter = mediaPainter"))
        assertTrue(loading.contains("CircularProgressIndicator("))
    }

    @Test
    fun mediaUrlsAndIndexArePublishedAtomically() {
        val activity = source("MainActivity.kt")
        val dialog = source("ui/chat/FullScreenMediaPreviewDialog.kt")

        assertTrue(activity.contains("MediaPreviewTarget(urls, index)"))
        assertTrue(activity.contains("MediaPreviewTarget(pages, idx)"))
        assertFalse(activity.contains("fullScreenMediaUrls"))
        assertFalse(activity.contains("fullScreenMediaIndex"))
        assertTrue(dialog.contains("LaunchedEffect(currentUrls, currentIndex)"))
    }

    @Test
    fun deleteDialogLeavesBeforeDeletionAndSelectedDeleteWaitsForOverlay() {
        val item = source("ui/chat/message/MessageItem.kt")
        val confirm = item.substringAfter("val onConfirmDelete = {")
            .substringBefore("if (pending.deletesConversation)")
        val effect = item.substringAfter("LaunchedEffect(confirmedDelete)")
            .substringBefore("pendingDelete?.let")
        val lifecycle = source("viewmodel/ConversationLifecycleController.kt")
        val deleteBody = lifecycle.substringAfter("scope.launch(ioDispatcher)")
            .substringBefore("return true")
        val dialogHost = source("ui/chat/ChatAppDialogHost.kt")
        val conversationConfirm = dialogHost.substringAfter("ChatDeleteConfirmDialog(")
            .substringBefore("onDismiss = state::dismissDelete")
        val selectedConfirm = conversationConfirm.substringAfter("if (id == currentConversationId)")
            .substringBefore("} else {")
        val backgroundConfirm = conversationConfirm.substringAfter("} else {")

        assertTrue(confirm.indexOf("pendingDelete = null") < confirm.indexOf("confirmedDelete = pending"))
        assertTrue(effect.indexOf("withFrameNanos") < effect.indexOf("onDeleteConversation"))
        assertTrue(effect.indexOf("withFrameNanos") < effect.indexOf("onDelete("))
        assertTrue(
            deleteBody.indexOf("beginSelectedDeleteTransition") <
                deleteBody.indexOf("tryWithConversationLock"),
        )
        assertTrue(
            conversationConfirm.indexOf("state.beginDelete(id)") <
                conversationConfirm.indexOf("if (id == currentConversationId)"),
        )
        assertTrue(
            selectedConfirm.indexOf("state.completeDelete(id)") <
                selectedConfirm.indexOf("deleteConversation()"),
        )
        assertTrue(
            backgroundConfirm.indexOf("withFrameNanos") <
                backgroundConfirm.indexOf("viewModel.currentConversationId.value"),
        )
        assertTrue(
            backgroundConfirm.indexOf("viewModel.currentConversationId.value") <
                backgroundConfirm.indexOf("deleteConversation()"),
        )
    }

    private fun source(relativePath: String): String =
        File(mainSourceRoot(), "com/newoether/agora/$relativePath")
            .readText()
            .replace("\r\n", "\n")

    private fun mainSourceRoot(): File {
        var directory = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        while (true) {
            val candidate = File(directory, "app/src/main/java")
            if (candidate.isDirectory) return candidate
            directory = directory.parentFile ?: error("Unable to locate app/src/main/java")
        }
    }
}
