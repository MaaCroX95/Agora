package com.newoether.agora.ui.chat

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaStateCrossfadeSourceContractTest {
    @Test
    fun bubbleAndFullscreenMediaUseFixedCrossfadeStates() {
        val bubble = source("ui/chat/AttachmentThumbnail.kt")
            .substringAfter("private fun MessageMediaThumbnail(")
        val zoom = source("ui/chat/ZoomableImageItem.kt")

        listOf(bubble, zoom).forEach { media ->
            assertTrue(media.contains("MediaLoadPresentation.LOADING"))
            assertTrue(media.contains("MediaLoadPresentation.LOADED"))
            assertTrue(media.contains("MediaLoadPresentation.FAILED"))
            assertTrue(media.contains("onError ="))
            assertTrue(media.contains("Crossfade("))
            assertTrue(media.contains("modifier = Modifier.fillMaxSize()"))
            assertTrue(media.contains("MEDIA_STATE_CROSSFADE_MILLIS"))
            assertTrue(media.contains("MEDIA_LOADING_INDICATOR_STROKE_WIDTH"))
        }
        assertFalse(bubble.contains("messageMediaThumbnail:$"))
        assertFalse(zoom.contains("if (imageSize == Size.Zero)"))
    }

    @Test
    fun generatedAndToolImagesCrossfadeInsideTheWholeViewport() {
        val source = source("ui/chat/message/ToolResultContent.kt")
        val generated = source
            .substringAfter("internal fun GeneratedImageThumbnail(")
            .substringBefore("private fun GeneratedImagePendingDots(")
        val toolImage = source.substringAfter("private fun ToolImagePreview(")

        listOf(generated, toolImage).forEach { media ->
            assertTrue(media.contains("MediaLoadPresentation.LOADING"))
            assertTrue(media.contains("MediaLoadPresentation.LOADED"))
            assertTrue(media.contains("MediaLoadPresentation.FAILED"))
            assertTrue(media.contains("Crossfade("))
            assertTrue(media.contains("modifier = Modifier.fillMaxSize()"))
        }
        assertTrue(generated.contains("Icons.Default.BrokenImage"))
        assertTrue(toolImage.contains("onError ="))
        assertTrue(toolImage.contains("strokeWidth = MEDIA_LOADING_INDICATOR_STROKE_WIDTH"))
        assertFalse(source.contains("ToolImagePreviewState"))
        assertFalse(source.contains("animateFloatAsState"))
        assertFalse(source.contains("graphicsLayer"))
    }

    @Test
    fun attachmentAdmissionOwnsTheOnlyUploadHaptic() {
        val composer = source("ui/chat/bottombar/ChatComposerState.kt")
        val reportUnsupported = composer
            .substringAfter("fun reportUnsupportedFiles(")
            .substringBefore("fun reportCameraPreparationFailure(")
        val bottomBar = source("ui/chat/bottombar/ChatBottomBar.kt")

        assertFalse(reportUnsupported.contains("haptics."))
        val pickerAdmission = bottomBar
            .substringAfter("fun importUris(")
            .substringBefore("val clipboardImageReceiver")
        assertTrue(pickerAdmission.contains("} && imported) haptics.selection()"))
        assertTrue(
            Regex("""haptics\.selection\(\)""").findAll(pickerAdmission).count() == 1,
        )
        val cameraAdmission = composer
            .substringAfter(".onSuccess { imported ->")
            .substringBefore(".onFailure { failure ->")
        assertTrue(cameraAdmission.contains("if (imported) {"))
        assertTrue(cameraAdmission.contains("haptics.selection()"))
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
