package com.newoether.agora.tool

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SearxngSearchUrlTest {
    @Test
    fun searxngUsesInstanceDefaultsAndCanonicalizesTrailingSlash() {
        val url = searxngSearchUrl(
            configuredBaseUrl = "https://search.example.test/",
            query = "hello world/中文",
        )

        assertEquals(
            "https://search.example.test/search?q=hello+world%2F%E4%B8%AD%E6%96%87&format=json",
            url,
        )
        assertFalse(url.contains("engines="))
        assertFalse(url.contains(".test//search"))
    }
}

class WebSearchPageExcerptTest {
    @Test
    fun automaticExcerptBudgetIsThreeThousandCharacters() {
        assertEquals(3_000, WEB_SEARCH_AUTO_READ_MAX_CHARS)
    }

    @Test
    fun excerptIsCappedAndPreservesExistingResultMetadata() {
        val result = JsonObject(
            mapOf(
                "title" to JsonPrimitive("Example"),
                "url" to JsonPrimitive("https://example.test/article"),
                "description" to JsonPrimitive("Search snippet"),
                "score" to JsonPrimitive(0.91),
            )
        )
        val fullText = "x".repeat(WEB_SEARCH_AUTO_READ_MAX_CHARS + 137)

        val enriched = addWebSearchPageExcerpt(result, fullText)

        assertEquals("Example", (enriched["title"] as JsonPrimitive).content)
        assertEquals("Search snippet", (enriched["description"] as JsonPrimitive).content)
        assertEquals("0.91", (enriched["score"] as JsonPrimitive).content)
        assertEquals(
            WEB_SEARCH_AUTO_READ_MAX_CHARS,
            (enriched["page_excerpt"] as JsonPrimitive).content.length,
        )
        assertTrue((enriched["page_excerpt_truncated"] as JsonPrimitive).content.toBoolean())
        assertEquals(
            fullText.length,
            (enriched["page_total_chars"] as JsonPrimitive).content.toInt(),
        )
    }

    @Test
    fun shortExcerptIsNotMarkedTruncated() {
        val result = JsonObject(mapOf("url" to JsonPrimitive("https://example.test")))
        val fullText = "Readable article text"

        val enriched = addWebSearchPageExcerpt(result, fullText)

        assertEquals(fullText, (enriched["page_excerpt"] as JsonPrimitive).content)
        assertFalse((enriched["page_excerpt_truncated"] as JsonPrimitive).content.toBoolean())
        assertEquals(
            fullText.length,
            (enriched["page_total_chars"] as JsonPrimitive).content.toInt(),
        )
    }

    @Test
    fun blankReadableTextLeavesOriginalResultUntouched() {
        val result = JsonObject(mapOf("url" to JsonPrimitive("https://example.test")))

        val enriched = addWebSearchPageExcerpt(result, "   ")

        assertSame(result, enriched)
        assertFalse(enriched.containsKey("page_excerpt"))
    }
}
