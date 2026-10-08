package com.newoether.agora.tool

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class WebSearchRetrievalTest {
    @Test
    fun automaticReadBudgetsRemainBounded() {
        assertEquals(3_000, WEB_SEARCH_AUTO_READ_MAX_CHARS)
        assertEquals(3, WEB_SEARCH_AUTO_READ_RESULT_COUNT)
        assertEquals(6, WEB_SEARCH_AUTO_READ_CANDIDATE_COUNT)
    }

    @Test
    fun focusedExcerptCanReachRelevantPassagePastLeadingBudget() {
        val leading = "unrelated introduction ".repeat(240)
        val target = "Jerman mati blagoslov Franc revolver"
        val fullText = leading + target + " trailing context".repeat(240)

        val excerpt = checkNotNull(
            selectWebSearchPageExcerpt(
                fullText = fullText,
                query = "Jerman mati blagoslov",
                maxChars = 600,
            )
        )

        assertTrue(excerpt.start > 0)
        assertTrue(excerpt.text.contains(target))
    }

    @Test
    fun focusedExcerptFallsBackToLeadingTextWithoutMultipleMatches() {
        val fullText = "leading evidence ".repeat(80) + "isolated needle"
        val excerpt = checkNotNull(
            selectWebSearchPageExcerpt(
                fullText = fullText,
                query = "needle absent-term",
                maxChars = 120,
            )
        )

        assertEquals(0, excerpt.start)
        assertEquals(fullText.take(120), excerpt.text)
    }

    @Test
    fun thinOrBlankPageDoesNotBecomeAnAutomaticExcerpt() {
        assertFalse(isUsefulWebSearchPage("Too short"))
        assertTrue(isUsefulWebSearchPage("x".repeat(WEB_SEARCH_MIN_READABLE_PAGE_CHARS)))

        val result = buildJsonObject {
            put("title", "Example")
            put("url", "https://example.test")
        }
        val unchanged = addWebSearchPageExcerpt(result, "", "example")
        assertSame(result, unchanged)
    }
}
