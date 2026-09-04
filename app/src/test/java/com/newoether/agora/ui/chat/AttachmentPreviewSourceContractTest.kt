package com.newoether.agora.ui.chat

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AttachmentPreviewSourceContractTest {
    @Test
    fun everyAttachmentOutcomeUsesTheCanonicalCrossfadePresentation() {
        val source = source("ui/chat/bottombar/AttachmentPreviewRow.kt")

        assertTrue(source.contains("mutableStateOf(AttachmentPreviewPresentation.INITIAL)"))
        assertTrue(source.contains("targetState = presentedState"))
        assertTrue(source.contains("label = \"attachmentPresentation\""))
        assertTrue(source.contains("label = \"attachmentCaption\""))
        assertTrue(source.contains("tween(ATTACHMENT_STATUS_CROSSFADE_MS)"))
        assertTrue(source.contains("is AsyncImagePainter.State.Success"))
        assertTrue(source.contains("is AsyncImagePainter.State.Error"))
        assertTrue(source.contains("is AsyncImagePainter.State.Loading"))
        assertTrue(source.contains("AttachmentPreviewPresentation.IMPORT_LOADING"))
        assertTrue(source.contains("AttachmentPreviewPresentation.IMPORT_FAILED"))
        assertTrue(source.contains("AttachmentPreviewPresentation.MEDIA_LOADING"))
        assertTrue(source.contains("AttachmentPreviewPresentation.MEDIA_SUCCESS"))
        assertTrue(source.contains("AttachmentPreviewPresentation.MEDIA_ERROR"))
        assertFalse(source.contains("label = \"attachmentProcessingOverlay\""))
        assertFalse(source.contains("label = \"attachmentStatusIndicator\""))
        assertFalse(source.contains("coil.compose.AsyncImage("))
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
