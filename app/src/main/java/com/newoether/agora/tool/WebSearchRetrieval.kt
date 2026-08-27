package com.newoether.agora.tool

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal const val WEB_SEARCH_AUTO_READ_RESULT_COUNT = 3
internal const val WEB_SEARCH_AUTO_READ_CANDIDATE_COUNT = 6
internal const val WEB_SEARCH_AUTO_READ_MAX_CHARS = 3_000
internal const val WEB_SEARCH_MIN_READABLE_PAGE_CHARS = 160

internal data class WebSearchPageExcerpt(
    val text: String,
    val start: Int,
)

/**
 * Selects a bounded passage from a readable page.
 *
 * Without a useful focus query this preserves the old leading-text behavior. For a long page and a
 * query with at least two distinct matching terms, it chooses the earliest window containing the
 * largest number of distinct query terms. Requiring multiple matches prevents one accidental or
 * hallucinated query token from dragging the excerpt to an unrelated passage.
 */
internal fun selectWebSearchPageExcerpt(
    fullText: String,
    query: String,
    maxChars: Int = WEB_SEARCH_AUTO_READ_MAX_CHARS,
): WebSearchPageExcerpt? {
    if (fullText.isBlank()) return null
    val limit = maxChars.coerceAtLeast(1)
    if (fullText.length <= limit) return WebSearchPageExcerpt(fullText, 0)

    val fallback = WebSearchPageExcerpt(fullText.take(limit), 0)
    val terms = query
        .lowercase()
        .split(Regex("[^\\p{L}\\p{N}]+"))
        .asSequence()
        .filter { it.length >= 3 }
        .distinct()
        .toList()
    if (terms.size < 2) return fallback

    val lowerText = fullText.lowercase()
    val maxStart = fullText.length - limit
    val candidateStarts = linkedSetOf(0)
    for (term in terms) {
        var fromIndex = 0
        var matches = 0
        while (matches < 8) {
            val match = lowerText.indexOf(term, fromIndex)
            if (match < 0) break
            candidateStarts += (match - limit / 3).coerceIn(0, maxStart)
            fromIndex = match + term.length
            matches++
        }
    }

    var bestStart = 0
    var bestScore = 0
    for (start in candidateStarts) {
        val end = minOf(fullText.length, start + limit)
        val window = lowerText.substring(start, end)
        val score = terms.count { window.contains(it) }
        if (score > bestScore || (score == bestScore && start < bestStart)) {
            bestStart = start
            bestScore = score
        }
    }

    if (bestScore < 2 || bestStart == 0) return fallback
    return WebSearchPageExcerpt(
        text = fullText.substring(bestStart, minOf(fullText.length, bestStart + limit)),
        start = bestStart,
    )
}

internal fun addWebSearchPageExcerpt(
    result: JsonObject,
    fullText: String,
    query: String = "",
    maxChars: Int = WEB_SEARCH_AUTO_READ_MAX_CHARS,
): JsonObject {
    val excerpt = selectWebSearchPageExcerpt(fullText, query, maxChars) ?: return result
    if (excerpt.text.isBlank()) return result

    return JsonObject(result.toMutableMap().apply {
        put("page_excerpt", JsonPrimitive(excerpt.text))
        put("page_excerpt_start", JsonPrimitive(excerpt.start))
        put("page_excerpt_truncated", JsonPrimitive(fullText.length > excerpt.text.length))
        put("page_total_chars", JsonPrimitive(fullText.length))
    })
}

internal fun hasWebSearchPageExcerpt(element: JsonElement): Boolean =
    element is JsonObject && element.containsKey("page_excerpt")

internal fun isUsefulWebSearchPage(text: String): Boolean =
    text.trim().length >= WEB_SEARCH_MIN_READABLE_PAGE_CHARS
