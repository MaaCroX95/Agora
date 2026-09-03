package com.newoether.agora.ui.chat.message

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolResultContentSourceContractTest {
    @Test
    fun `Web Search results keep semantic tiers and use rounded full-row link ripples`() {
        val source = source(locateMainSourceRoot(), "ToolResultContent.kt")
        val segmentDetailSheet = source(locateMainSourceRoot(), "SegmentDetailSheet.kt")
        val webSearch = source
            .substringAfter("private fun WebSearchResult(")
            .substringBefore("private fun IndexedCodeLine(")

        assertTrue(webSearch.contains("style = ChatType.body,"))
        assertTrue(webSearch.contains("fontWeight = FontWeight.SemiBold"))
        assertTrue(webSearch.contains("style = ChatType.thoughtBody,"))
        assertTrue(webSearch.contains("style = ChatType.micro,"))
        assertTrue(webSearch.contains("HorizontalDivider("))
        assertFalse(webSearch.contains(".background("))
        assertTrue(webSearch.contains("val uriHandler = LocalUriHandler.current"))
        assertTrue(webSearch.contains("val resultShape = RoundedCornerShape(12.dp)"))
        assertTrue(webSearch.contains("val safeUrl = remember(url) { CitationPolicy.safeHttpUrl(url) }"))
        assertTrue(webSearch.contains("enabled = safeUrl != null"))
        assertTrue(webSearch.contains("runCatching { uriHandler.openUri(destination) }"))
        assertTrue(
            source.contains("internal fun toolDetailHorizontalPadding(segment: MessageSegment): Dp"),
        )
        assertTrue(source.contains("ToolKind.WEB_SEARCH -> 16.dp"))
        assertTrue(source.contains("else -> 24.dp"))
        assertTrue(
            segmentDetailSheet.contains(
                ".padding(horizontal = toolDetailHorizontalPadding(detailSeg))",
            ),
        )
        assertTrue(
            segmentDetailSheet.contains(
                ".padding(horizontal = toolDetailHorizontalPadding(seg))",
            ),
        )
        val toolDetail = source
            .substringAfter("internal fun ToolDetailContent(")
            .substringBefore("private enum class ToolImagePreviewState")
        assertTrue(
            toolDetail.contains(
                "val contentAlignmentModifier = if (presentation.kind == ToolKind.WEB_SEARCH)",
            ),
        )

        val clipPosition = webSearch.indexOf(".clip(resultShape)")
        val clickablePosition = webSearch.indexOf(".clickable(")
        val paddingPosition = webSearch.indexOf(
            ".padding(horizontal = 8.dp, vertical = 12.dp)",
        )
        assertTrue(clipPosition >= 0)
        assertTrue(clickablePosition > clipPosition)
        assertTrue(paddingPosition > clickablePosition)

        val titlePosition = webSearch.indexOf("text = title")
        val snippetPosition = webSearch.indexOf("text = snippet")
        val urlPosition = webSearch.indexOf("text = url")
        assertTrue(titlePosition >= 0)
        assertTrue(snippetPosition > titlePosition)
        assertTrue(urlPosition > snippetPosition)
    }

    @Test
    fun `Completed wait for job uses the shell exit summary`() {
        val source = source(locateMainSourceRoot(), "MessageItemToolLabels.kt")
        val completedSummary = source
            .substringAfter("private fun completedSummary(")

        assertTrue(
            completedSummary.contains(
                "ToolKind.SHELL_JOB_WAIT -> shellToolSummary(presentation)",
            ),
        )
        assertFalse(completedSummary.contains("tool_waited_shell_job"))
    }

    private fun source(root: File, name: String): String =
        File(root, "com/newoether/agora/ui/chat/message/$name").readText()

    private fun locateMainSourceRoot(): File {
        var directory = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        repeat(8) {
            listOf(
                File(directory, "app/src/main/java"),
                File(directory, "src/main/java"),
            ).firstOrNull(File::isDirectory)?.let { return it }
            directory = directory.parentFile ?: error("Reached filesystem root")
        }
        error("Unable to locate the main Java source directory")
    }
}
