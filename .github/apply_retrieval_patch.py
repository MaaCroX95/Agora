from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected exactly one match, found {count}: {old[:120]!r}")
    return text.replace(old, new, 1)


provider_path = Path("app/src/main/java/com/newoether/agora/tool/WebSearchToolProvider.kt")
text = provider_path.read_text()

text = replace_once(
    text,
    'internal const val WEB_SEARCH_AUTO_READ_RESULT_COUNT = 3\ninternal const val WEB_SEARCH_AUTO_READ_MAX_CHARS = 3_000\n\n',
    '',
    'provider constants',
)

old_add = '''internal fun addWebSearchPageExcerpt(
    result: JsonObject,
    fullText: String,
    maxChars: Int = WEB_SEARCH_AUTO_READ_MAX_CHARS,
): JsonObject {
    val excerpt = fullText.take(maxChars.coerceAtLeast(1))
    if (excerpt.isBlank()) return result

    return JsonObject(result.toMutableMap().apply {
        put("page_excerpt", JsonPrimitive(excerpt))
        put("page_excerpt_truncated", JsonPrimitive(fullText.length > excerpt.length))
        put("page_total_chars", JsonPrimitive(fullText.length))
    })
}

'''
text = replace_once(text, old_add, '', 'provider old add excerpt helper')

old_search_desc = '''description = "Search the web for factual information, verification, current or niche information, and sources relevant to the user's question. Use this whenever external information can improve factual accuracy, verify a claim, resolve uncertainty, or provide up-to-date or source-backed details. For specific factual questions you are not highly confident about, prefer searching over relying on memory; do not reserve web search only for recent events. Results include search snippets plus light page excerpts from the top readable results.",'''
new_search_desc = '''description = "Search the web for factual information, verification, current or niche information, and sources relevant to the user's question. Use this whenever external information can improve factual accuracy, verify a claim, resolve uncertainty, or provide up-to-date or source-backed details. For specific factual questions you are not highly confident about, prefer searching over relying on memory; do not reserve web search only for recent events. Prefer primary or authoritative sources for precise claims. Treat snippets and page excerpts as evidence only for details they actually support; if sources conflict or a needed detail is not supported, search again or use web_fetch instead of filling the gap from memory. Results include search snippets plus light page excerpts from the top readable results.",'''
text = replace_once(text, old_search_desc, new_search_desc, 'search tool description')

old_fetch_desc = '''description = "Fetch and read the full text content of a web page. Use this after web_search when you need more detail or context than the light page excerpt returned with search results.",'''
new_fetch_desc = '''description = "Fetch and read a web page when search excerpts are not enough to support a specific claim. Prefer this over inferring missing details from memory, especially for exact dates, relationships, quotes, events, or disputed facts. For long pages, pass query with focus terms for the exact detail you need so the returned text can target the relevant passage.",'''
text = replace_once(text, old_fetch_desc, new_fetch_desc, 'fetch tool description')

old_props = '''                        "url" to ToolProperty("string", "The URL of the page to fetch."),
                        "maxChars" to ToolProperty("integer", "Maximum characters of text to return (default 8000, max 100000). If the result has \\"truncated\\": true, call again with a larger maxChars to get more.")
'''
new_props = '''                        "url" to ToolProperty("string", "The URL of the page to fetch."),
                        "query" to ToolProperty("string", "Optional focus terms or phrase for a specific fact. On long pages, use this to return a relevant passage instead of only the beginning."),
                        "maxChars" to ToolProperty("integer", "Maximum characters of text to return (default 8000, max 100000). If the result has \\"truncated\\": true and more context is needed, call again with a larger maxChars.")
'''
text = replace_once(text, old_props, new_props, 'fetch tool properties')

start_marker = '    private suspend fun enrichWebSearchResponse(response: String): String = coroutineScope {\n'
end_marker = '    private fun fetchReadablePage(url: String): String? {\n'
start = text.index(start_marker)
end = text.index(end_marker, start)
new_enrich = '''    private suspend fun enrichWebSearchResponse(response: String): String = coroutineScope {
        val root = Json.parseToJsonElement(response) as? JsonObject
            ?: return@coroutineScope response
        val results = root["results"] as? JsonArray
            ?: return@coroutineScope response
        if (results.isEmpty()) return@coroutineScope response

        val query = (root["query"] as? JsonPrimitive)?.content.orEmpty()
        val enriched = results.toMutableList()
        val candidateLimit = minOf(results.size, WEB_SEARCH_AUTO_READ_CANDIDATE_COUNT)
        var nextIndex = 0
        var successfulReads = 0

        // Fill up to three useful excerpts in provider-result order. Start with three concurrent
        // reads; when one fails or is too thin to be useful, spend only the missing slots on later
        // candidates. The common case remains three requests and the result list is never reordered.
        while (nextIndex < candidateLimit && successfulReads < WEB_SEARCH_AUTO_READ_RESULT_COUNT) {
            val needed = WEB_SEARCH_AUTO_READ_RESULT_COUNT - successfulReads
            val batchEnd = minOf(nextIndex + needed, candidateLimit)
            val batch = (nextIndex until batchEnd).map { index ->
                async {
                    val element = results[index]
                    val result = element as? JsonObject ?: return@async index to element
                    val url = (result["url"] as? JsonPrimitive)?.content.orEmpty()
                    if (!isHttpUrl(url)) return@async index to element

                    val page = fetchReadablePage(url) ?: return@async index to element
                    index to addWebSearchPageExcerpt(result, page, query)
                }
            }.awaitAll()

            batch.forEach { (index, element) ->
                enriched[index] = element
                if (hasWebSearchPageExcerpt(element)) successfulReads++
            }
            nextIndex = batchEnd
        }

        JsonObject(root.toMutableMap().apply {
            put("results", JsonArray(enriched))
        }).toString()
    }

'''
text = text[:start] + new_enrich + text[end:]

text = replace_once(
    text,
    '            htmlToReadableText(html).takeIf { it.isNotBlank() }',
    '            htmlToReadableText(html).takeIf(::isUsefulWebSearchPage)',
    'readability floor',
)

old_focus = '''        val url = (args["url"] as? JsonPrimitive)?.content
            ?: return buildJsonObject { put("type", "web_fetch"); put("error", "no_url") }.toString()
        val maxChars = (try {
'''
new_focus = '''        val url = (args["url"] as? JsonPrimitive)?.content
            ?: return buildJsonObject { put("type", "web_fetch"); put("error", "no_url") }.toString()
        val focusQuery = (args["query"] as? JsonPrimitive)?.content.orEmpty()
        val maxChars = (try {
'''
text = replace_once(text, old_focus, new_focus, 'web_fetch focus query')

old_fetch_body = '''            val fullText = htmlToReadableText(html)
            val text = fullText.take(maxChars)
            buildJsonObject {
                put("type", "web_fetch")
                put("url", url)
                put("text", text)
                put("truncated", fullText.length > text.length)
                put("totalChars", fullText.length)
            }.toString()
        } catch (e: Exception) {
'''
new_fetch_body = '''            val fullText = htmlToReadableText(html)
            val excerpt = selectWebSearchPageExcerpt(fullText, focusQuery, maxChars)
            val text = excerpt?.text.orEmpty()
            buildJsonObject {
                put("type", "web_fetch")
                put("url", url)
                put("text", text)
                put("truncated", fullText.length > text.length)
                put("totalChars", fullText.length)
                put("excerptStart", excerpt?.start ?: 0)
                put("focused", focusQuery.isNotBlank() && (excerpt?.start ?: 0) > 0)
            }.toString()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
'''
text = replace_once(text, old_fetch_body, new_fetch_body, 'web_fetch focused extraction')
provider_path.write_text(text)

helper_path = Path("app/src/main/java/com/newoether/agora/tool/WebSearchRetrieval.kt")
if helper_path.exists():
    raise RuntimeError(f"unexpected existing file: {helper_path}")
helper_path.write_text(r'''package com.newoether.agora.tool

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
''')

test_path = Path("app/src/test/java/com/newoether/agora/tool/WebSearchToolProviderTest.kt")
tests = test_path.read_text()
tests = replace_once(
    tests,
    '''    fun automaticExcerptBudgetIsThreeThousandCharacters() {
        assertEquals(3_000, WEB_SEARCH_AUTO_READ_MAX_CHARS)
    }''',
    '''    fun automaticExcerptBudgetIsThreeThousandCharacters() {
        assertEquals(3_000, WEB_SEARCH_AUTO_READ_MAX_CHARS)
        assertEquals(3, WEB_SEARCH_AUTO_READ_RESULT_COUNT)
        assertEquals(6, WEB_SEARCH_AUTO_READ_CANDIDATE_COUNT)
    }''',
    'test constants',
)
old_tail = '''    @Test
    fun blankReadableTextLeavesOriginalResultUntouched() {
        val result = JsonObject(mapOf("url" to JsonPrimitive("https://example.test")))

        val enriched = addWebSearchPageExcerpt(result, "   ")

        assertSame(result, enriched)
        assertFalse(enriched.containsKey("page_excerpt"))
    }
}
'''
new_tail = '''    @Test
    fun blankReadableTextLeavesOriginalResultUntouched() {
        val result = JsonObject(mapOf("url" to JsonPrimitive("https://example.test")))

        val enriched = addWebSearchPageExcerpt(result, "   ")

        assertSame(result, enriched)
        assertFalse(enriched.containsKey("page_excerpt"))
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
    fun thinPageDoesNotConsumeAutomaticReadSlot() {
        assertFalse(isUsefulWebSearchPage("Too short"))
        assertTrue(isUsefulWebSearchPage("x".repeat(WEB_SEARCH_MIN_READABLE_PAGE_CHARS)))
    }
}
'''
tests = replace_once(tests, old_tail, new_tail, 'test tail')
test_path.write_text(tests)

contract_path = Path("development/web-search.md")
contract = contract_path.read_text()
old_contract_reads = '''- A successful generic `web_search` keeps the selected provider's normalized result order and
  existing metadata, then automatically attempts a light read of the first three HTTP(S) result
  pages before returning the tool result to the model.
- Each successful light read adds at most 3,000 characters of readable page text to that same result
  as `page_excerpt`, together with `page_excerpt_truncated` and `page_total_chars`. Existing title,
  URL, description/content, provider score, answer, and result ordering must remain intact.
- The three light reads may execute concurrently, but they remain one bounded `web_search` tool
  execution. They must not create additional visible tool calls, Provider passes, generation Runs,
  or continuation paths.
- A missing, non-HTTP(S), unreadable, empty, timed-out, or otherwise failed individual result page
  leaves that search result unchanged. One failed light read must not fail or discard an otherwise
  successful search response. Cancellation still propagates through the ordinary tool/generation
  lifecycle rather than being converted into a page-read miss.
'''
new_contract_reads = '''- A successful generic `web_search` keeps the selected provider's normalized result order and
  existing metadata, then attempts to fill up to three useful light-read excerpts in that same order.
  The common case reads the first three HTTP(S) results concurrently. If a candidate is missing,
  unreadable, empty, too thin to be useful, timed out, or otherwise fails, later results may fill the
  missing slot, examining at most the first six candidates. The response must still contain no more
  than three automatic excerpts and must never reorder the provider results.
- Each successful light read adds at most 3,000 characters of readable page text to that same result
  as `page_excerpt`, together with `page_excerpt_start`, `page_excerpt_truncated`, and
  `page_total_chars`. Existing title, URL, description/content, provider score, answer, and result
  ordering must remain intact. On long pages, when at least two distinct search-query terms support a
  more relevant passage, the bounded excerpt may come from that passage instead of mechanically from
  character zero; a weak or single-term match falls back to the leading text.
- Automatic reads may execute concurrently in bounded batches, but they remain one `web_search` tool
  execution. They must not create additional visible tool calls, Provider passes, generation Runs,
  or continuation paths.
- A failed individual result page leaves that search result unchanged and allows a later candidate to
  fill the automatic-read quota. One failed light read must not fail or discard an otherwise
  successful search response. Cancellation still propagates through the ordinary tool/generation
  lifecycle rather than being converted into a page-read miss.
'''
contract = replace_once(contract, old_contract_reads, new_contract_reads, 'contract read semantics')

old_guidance = '''- The `web_search` tool description must present search as a general factual-grounding and
  verification capability, not as a feature reserved for recent news. It must explicitly cover
  factual verification, current or niche/specific information, uncertainty resolution, and
  source-backed details. For specific factual questions where the model is not highly confident,
  it should prefer searching over relying on memory.
- `web_fetch` remains the explicit deeper-reading tool. The model may call it after `web_search` when
  the light excerpt is insufficient, and the ordinary agent/tool continuation loop remains the sole
  owner of that follow-up.
'''
new_guidance = '''- The `web_search` tool description must present search as a general factual-grounding and
  verification capability, not as a feature reserved for recent news. It must explicitly cover
  factual verification, current or niche/specific information, uncertainty resolution, and
  source-backed details. For specific factual questions where the model is not highly confident,
  it should prefer searching over relying on memory, prefer primary/authoritative evidence for
  precise claims, and search/fetch again instead of inventing details when excerpts are insufficient
  or sources conflict.
- `web_fetch` remains the explicit deeper-reading tool. It accepts an optional focus `query`; on long
  pages that query may select a relevant bounded passage instead of only the leading text. The model
  should use it after `web_search` for exact claims not directly supported by snippets/excerpts, and
  the ordinary agent/tool continuation loop remains the sole owner of that follow-up.
'''
contract = replace_once(contract, old_guidance, new_guidance, 'contract guidance')

contract = replace_once(
    contract,
    '''9. generic `web_search` preserves normalized result metadata/order while adding no more than three
   light page excerpts of no more than 3,000 characters each;''',
    '''9. generic `web_search` preserves normalized result metadata/order while adding no more than three
   light page excerpts of no more than 3,000 characters each, filling failed/thin early reads from
   later candidates without examining beyond the first six results;''',
    'contract verification 9',
)
contract = replace_once(
    contract,
    '''12. `web_fetch` remains available for deeper explicit reads and no forced search-first generation
    path is introduced;
13. relevant resource contracts, focused tests, the complete scoped diff, and the project full build.''',
    '''12. query-focused excerpt selection can reach relevant text beyond a long page's beginning while
    weak query matches safely fall back to leading text;
13. `web_fetch` remains available for deeper explicit reads, supports optional query-focused passage
    selection, and no forced search-first generation path is introduced;
14. relevant resource contracts, focused tests, the complete scoped diff, and the project full build.''',
    'contract verification tail',
)
contract_path.write_text(contract)
