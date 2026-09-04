package com.newoether.agora.ui.chat.bottombar

import com.newoether.agora.model.AttachmentImportState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AttachmentPreviewPresentationTest {
    @Test
    fun reducerCoversEveryImportAndMediaOutcome() {
        val cases = listOf(
            Case(
                unavailable = true,
                importState = AttachmentImportState.READY,
                type = "image",
                media = AttachmentMediaLoadState.SUCCESS,
                expected = AttachmentPreviewPresentation.UNAVAILABLE,
            ),
            Case(
                importState = AttachmentImportState.PROCESSING,
                type = "image",
                media = AttachmentMediaLoadState.LOADING,
                expected = AttachmentPreviewPresentation.IMPORT_LOADING,
            ),
            Case(
                importState = AttachmentImportState.FAILED,
                type = "image",
                media = AttachmentMediaLoadState.ERROR,
                expected = AttachmentPreviewPresentation.IMPORT_FAILED,
            ),
            Case(
                type = "file",
                media = AttachmentMediaLoadState.LOADING,
                expected = AttachmentPreviewPresentation.READY_FILE,
            ),
            Case(
                type = "pdf",
                media = AttachmentMediaLoadState.LOADING,
                expected = AttachmentPreviewPresentation.READY_PDF,
            ),
            Case(
                type = "video",
                hasVideoFrame = false,
                media = AttachmentMediaLoadState.LOADING,
                expected = AttachmentPreviewPresentation.READY_VIDEO_PLACEHOLDER,
            ),
            Case(
                type = "video",
                hasVideoFrame = true,
                media = AttachmentMediaLoadState.LOADING,
                expected = AttachmentPreviewPresentation.MEDIA_LOADING,
            ),
            Case(
                type = "image",
                media = AttachmentMediaLoadState.SUCCESS,
                expected = AttachmentPreviewPresentation.MEDIA_SUCCESS,
            ),
            Case(
                type = "image",
                media = AttachmentMediaLoadState.ERROR,
                expected = AttachmentPreviewPresentation.MEDIA_ERROR,
            ),
        )

        assertEquals(
            AttachmentPreviewPresentation.entries.toSet() - AttachmentPreviewPresentation.INITIAL,
            cases.mapTo(linkedSetOf(), Case::expected),
        )
        cases.forEach { case ->
            assertEquals(
                case.expected,
                attachmentPreviewPresentation(
                    unavailable = case.unavailable,
                    importState = case.importState,
                    type = case.type,
                    hasVideoFrame = case.hasVideoFrame,
                    mediaLoadState = case.media,
                ),
            )
        }
    }

    @Test
    fun canonicalCrossfadeCanRepresentEveryDirectedStateTransition() {
        val states = AttachmentPreviewPresentation.entries
        val directedEdges = states.flatMap { from -> states.map { to -> from to to } }

        assertEquals(states.size * states.size, directedEdges.size)
        assertTrue(directedEdges.contains(
            AttachmentPreviewPresentation.INITIAL to
                AttachmentPreviewPresentation.IMPORT_LOADING,
        ))
        assertTrue(directedEdges.contains(
            AttachmentPreviewPresentation.MEDIA_LOADING to
                AttachmentPreviewPresentation.MEDIA_SUCCESS,
        ))
        assertTrue(directedEdges.contains(
            AttachmentPreviewPresentation.MEDIA_LOADING to
                AttachmentPreviewPresentation.MEDIA_ERROR,
        ))
        assertTrue(directedEdges.contains(
            AttachmentPreviewPresentation.IMPORT_FAILED to
                AttachmentPreviewPresentation.IMPORT_LOADING,
        ))
    }

    private data class Case(
        val unavailable: Boolean = false,
        val importState: AttachmentImportState = AttachmentImportState.READY,
        val type: String,
        val hasVideoFrame: Boolean = false,
        val media: AttachmentMediaLoadState,
        val expected: AttachmentPreviewPresentation,
    )
}
