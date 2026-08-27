package com.newoether.agora.tool

import androidx.core.text.HtmlCompat
import com.newoether.agora.api.DuckDuckGoScraper
import com.newoether.agora.api.HttpClient
import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.api.ToolFunction
import com.newoether.agora.api.ToolParameters
import com.newoether.agora.api.ToolProperty
import com.newoether.agora.data.normalizeWebSearchProvider
import com.newoether.agora.util.Constants
import com.newoether.agora.viewmodel.GenerationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.TimeUnit

internal const val WEB_SEARCH_AUTO_READ_RESULT_COUNT = 3
internal const val WEB_SEARCH_AUTO_READ_MAX_CHARS = 3_000

internal fun searxngSearchUrl(configuredBaseUrl: String, query: String): String {
    val baseUrl = configuredBaseUrl.ifBlank { "https://searx.be" }.trimEnd('/')
    val encodedQuery = java.net.URLEncoder.encode(query, Charsets.UTF_8.name())
    return "$baseUrl/search?q=$encodedQuery&format=json"
}

internal fun kagiSearchRequestBody(query: String, numResults: Int): String =
    Json.encodeToString(
        buildJsonObject {
            put("query", query)
            put("workflow", "search")
            put("limit", numResults.coerceIn(1, 10))
        }
    )

internal fun normalizeKagiSearchResponse(
    responseBody: String,
    query: String,
    numResults: Int,
): String {
    val root = Json.parseToJsonElement(responseBody) as? JsonObject
    val data = root?.get("data") as? JsonObject
    val searchResults = data?.get("search") as? JsonArray
        ?: return buildJsonObject {
            put("type", "web_search")
            put("query", query)
            put("error", "no_results")
        }.toString()

    val normalizedResults = buildJsonArray {
        var added = 0
        for (element in searchResults) {
            if (added >= numResults.coerceIn(1, 10)) break
            val result = element as? JsonObject ?: continue
            val url = (result["url"] as? JsonPrimitive)?.content.orEmpty()
            if (url.isBlank()) continue
            add(
                buildJsonObject {
                    put("title", (result["title"] as? JsonPrimitive)?.content.orEmpty())
                    put("url", url)
                    put("description", (result["snippet"] as? JsonPrimitive)?.content.orEmpty())
                }
            )
            added++
        }
    }
    if (normalizedResults.isEmpty()) {
        return buildJsonObject {
            put("type", "web_search")
            put("query", query)
            put("error", "no_results")
        }.toString()
    }

    return buildJsonObject {
        put("type", "web_search")
        put("query", query)
        put("results", normalizedResults)
    }.toString()
}

internal fun webSearchProviderDisplayName(provider: String): String = when (provider) {
    "kagi" -> "Kagi"
    "serper" -> "Serper"
    "tavily" -> "Tavily"
    "searxng" -> "SearXNG"
    "duckduckgo" -> "DuckDuckGo"
    else -> "Brave Search"
}

internal fun addWebSearchPageExcerpt(
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

class WebSearchToolProvider : ToolProvider {
    private val webClient = HttpClient.client.newBuilder()
        .callTimeout(Constants.NETWORK_TOOL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(Constants.NETWORK_TOOL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()

    override fun definitions(ctx: GenerationContext): List<ToolDefinition> {
        if (!ctx.webSearchEnabled) return emptyList()
        return listOf(
            ToolDefinition(function = ToolFunction(
                name = "web_search",
                description = "Search the web for factual information, verification, current or niche information, and sources relevant to the user's question. Use this whenever external information can improve factual accuracy, verify a claim, resolve uncertainty, or provide up-to-date or source-backed details. For specific factual questions you are not highly confident about, prefer searching over relying on memory; do not reserve web search only for recent events. Results include search snippets plus light page excerpts from the top readable results.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "query" to ToolProperty("string", "The search query to execute."),
                        "num_results" to ToolProperty("integer", "Number of results to return (1-10, default 5).")
                    ),
                    required = listOf("query")
                )
            )),
            ToolDefinition(function = ToolFunction(
                name = "web_fetch",
                description = "Fetch and read the full text content of a web page. Use this after web_search when you need more detail or context than the light page excerpt returned with search results.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "url" to ToolProperty("string", "The URL of the page to fetch."),
                        "maxChars" to ToolProperty("integer", "Maximum characters of text to return (default 8000, max 100000). If the result has \"truncated\": true, call again with a larger maxChars to get more.")
                    ),
                    required = listOf("url")
                )
            ))
        )
    }

    override suspend fun execute(
        name: String,
        arguments: String,
        ctx: GenerationContext,
    ): String = withContext(Dispatchers.IO) {
        when (name) {
            "web_search" -> executeWebSearch(arguments, ctx)
            "web_fetch" -> executeWebFetch(arguments, ctx)
            else -> "Unknown tool: $name"
        }
    }

    override fun handles(name: String): Boolean = name in setOf("web_search", "web_fetch")

    private suspend fun executeWebSearch(arguments: String, ctx: GenerationContext): String {
        val argsStr = arguments.ifBlank { "{}" }
        val args = Json.decodeFromString<Map<String, kotlinx.serialization.json.JsonElement>>(argsStr)
        val query = (args["query"] as? JsonPrimitive)?.content
            ?: return buildJsonObject { put("type", "web_search"); put("error", "no_query") }.toString()
        val numResults = ((args["num_results"] as? JsonPrimitive)?.content?.toIntOrNull() ?: ctx.webSearchNumResults).coerceIn(1, 10)
        val provider = normalizeWebSearchProvider(ctx.webSearchProvider)

        return try {
            // DuckDuckGo is a scraper, not an API — handle it separately.
            if (provider == "duckduckgo") {
                val scraper = DuckDuckGoScraper(webClient)
                return when (val r = scraper.search(query, numResults)) {
                    is DuckDuckGoScraper.SearchResponse.Success -> {
                        val rawResults = buildJsonArray {
                            r.results.forEach { result ->
                                add(buildJsonObject {
                                    put("title", result.title)
                                    put("url", result.url)
                                    put("description", result.snippet)
                                })
                            }
                        }
                        enrichWebSearchResponse(
                            buildJsonObject {
                                put("type", "web_search")
                                put("query", query)
                                put("results", rawResults)
                            }.toString()
                        )
                    }
                    is DuckDuckGoScraper.SearchResponse.Error -> {
                        buildJsonObject {
                            put("type", "web_search")
                            put("query", query)
                            put("error", r.type.name.lowercase())
                            put("message", r.message)
                        }.toString()
                    }
                }
            }

            val apiKey = ctx.webSearchApiKeys[provider].orEmpty()
            if (provider != "searxng" && apiKey.isBlank()) {
                return buildJsonObject {
                    put("type", "web_search")
                    put("query", query)
                    put("error", "no_api_key")
                    put("provider", webSearchProviderDisplayName(provider))
                }.toString()
            }
            val body = when (provider) {
                "kagi" -> HttpClient.post(
                    "https://kagi.com/api/v1/search",
                    kagiSearchRequestBody(query, numResults),
                    mapOf(
                        "Accept" to "application/json",
                        "Authorization" to "Bearer $apiKey",
                    ),
                    callTimeoutMillis = Constants.NETWORK_TOOL_TIMEOUT_MS,
                )
                "serper" -> HttpClient.post(
                    "https://google.serper.dev/search",
                    Json.encodeToString(buildJsonObject { put("q", query); put("num", numResults) }),
                    mapOf("X-API-KEY" to apiKey),
                    callTimeoutMillis = Constants.NETWORK_TOOL_TIMEOUT_MS,
                )
                "tavily" -> HttpClient.post(
                    "https://api.tavily.com/search",
                    Json.encodeToString(buildJsonObject {
                        put("api_key", apiKey)
                        put("query", query)
                        put("max_results", numResults)
                        put("search_depth", "advanced")
                        put("include_answer", true)
                    }),
                    emptyMap(),
                    callTimeoutMillis = Constants.NETWORK_TOOL_TIMEOUT_MS,
                )
                "searxng" -> {
                    // Don't pin engines=google,brave: many public/self-hosted SearXNG instances
                    // disable those engines (rate-limited/require config), and pinning them yields
                    // an empty result set. Letting the instance use its own default-enabled engines
                    // matches how other SearXNG clients behave. Send a browser-like User-Agent so
                    // bot-filtering instances don't 403 us (same reason web_fetch sets one).
                    HttpClient.fetchModels(
                        searxngSearchUrl(ctx.webSearchBaseUrl, query),
                        mapOf("User-Agent" to Constants.WEB_FETCH_USER_AGENT),
                        callTimeoutMillis = Constants.NETWORK_TOOL_TIMEOUT_MS,
                    )
                }
                else -> HttpClient.fetchModels(
                    "https://api.search.brave.com/res/v1/web/search?q=${java.net.URLEncoder.encode(query, "UTF-8")}&count=$numResults",
                    mapOf("Accept" to "application/json", "X-Subscription-Token" to apiKey),
                    callTimeoutMillis = Constants.NETWORK_TOOL_TIMEOUT_MS,
                )
            } ?: return buildJsonObject { put("type", "web_search"); put("query", query); put("error", "no_response") }.toString()

            if (provider == "kagi") {
                return enrichWebSearchResponse(normalizeKagiSearchResponse(body, query, numResults))
            }

            val json: Map<String, kotlinx.serialization.json.JsonElement> = Json.decodeFromString(body)

            if (provider == "tavily") {
                val resultsArray = json["results"]?.jsonArray
                    ?: return buildJsonObject { put("type", "web_search"); put("query", query); put("error", "no_results") }.toString()
                if (resultsArray.isEmpty())
                    return buildJsonObject { put("type", "web_search"); put("query", query); put("error", "no_results") }.toString()
                val answer = (json["answer"] as? JsonPrimitive)?.content
                val rawResults = buildJsonArray {
                    for (element in resultsArray) {
                        val obj = element.jsonObject
                        add(buildJsonObject {
                            put("title", (obj["title"] as? JsonPrimitive)?.content ?: "")
                            put("url", (obj["url"] as? JsonPrimitive)?.content ?: "")
                            put("content", (obj["content"] as? JsonPrimitive)?.content ?: "")
                            val score = (obj["score"] as? JsonPrimitive)?.content?.toFloatOrNull()
                            if (score != null) put("score", score)
                        })
                    }
                }
                return enrichWebSearchResponse(
                    buildJsonObject {
                        put("type", "web_search")
                        put("query", query)
                        if (!answer.isNullOrBlank()) put("answer", answer)
                        put("results", rawResults)
                    }.toString()
                )
            }

            val resultsArray = when {
                json.containsKey("organic") -> json["organic"]?.jsonArray
                json.containsKey("web") -> {
                    val web = json["web"]?.jsonObject
                    web?.get("results")?.jsonArray
                }
                json.containsKey("results") -> json["results"]?.jsonArray
                else -> null
            } ?: return buildJsonObject { put("type", "web_search"); put("query", query); put("error", "no_results") }.toString()

            if (resultsArray.isEmpty())
                return buildJsonObject { put("type", "web_search"); put("query", query); put("error", "no_results") }.toString()

            val rawResults = buildJsonArray {
                for (element in resultsArray) {
                    val obj = element.jsonObject
                    add(buildJsonObject {
                        put("title", (obj["title"] as? JsonPrimitive)?.content ?: "")
                        put("url", (obj["link"] as? JsonPrimitive)?.content ?: (obj["url"] as? JsonPrimitive)?.content ?: "")
                        put("description", (obj["snippet"] as? JsonPrimitive)?.content ?: (obj["content"] as? JsonPrimitive)?.content ?: (obj["description"] as? JsonPrimitive)?.content ?: "")
                    })
                }
            }
            enrichWebSearchResponse(
                buildJsonObject {
                    put("type", "web_search")
                    put("query", query)
                    put("results", rawResults)
                }.toString()
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            buildJsonObject {
                put("type", "web_search")
                put("query", query)
                put("error", "search_error")
                put("message", e.message ?: "")
            }.toString()
        }
    }

    private suspend fun enrichWebSearchResponse(response: String): String = coroutineScope {
        val root = Json.parseToJsonElement(response) as? JsonObject
            ?: return@coroutineScope response
        val results = root["results"] as? JsonArray
            ?: return@coroutineScope response
        if (results.isEmpty()) return@coroutineScope response

        val enriched = results.mapIndexed { index, element ->
            async {
                if (index >= WEB_SEARCH_AUTO_READ_RESULT_COUNT) return@async element
                val result = element as? JsonObject ?: return@async element
                val url = (result["url"] as? JsonPrimitive)?.content.orEmpty()
                if (!isHttpUrl(url)) return@async element

                val page = fetchReadablePage(url) ?: return@async element
                addWebSearchPageExcerpt(result, page)
            }
        }.awaitAll()

        JsonObject(root.toMutableMap().apply {
            put("results", JsonArray(enriched))
        }).toString()
    }

    private fun fetchReadablePage(url: String): String? {
        return try {
            val html = HttpClient.fetchModels(
                url,
                mapOf(
                    "User-Agent" to Constants.WEB_FETCH_USER_AGENT,
                    "Accept" to "text/html,application/xhtml+xml,*/*",
                ),
                callTimeoutMillis = Constants.NETWORK_TOOL_TIMEOUT_MS,
            ) ?: return null
            htmlToReadableText(html).takeIf { it.isNotBlank() }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    private fun isHttpUrl(url: String): Boolean =
        url.startsWith("https://", ignoreCase = true) || url.startsWith("http://", ignoreCase = true)

    private suspend fun executeWebFetch(arguments: String, ctx: GenerationContext): String {
        val argsStr = arguments.ifBlank { "{}" }
        val args = Json.decodeFromString<Map<String, kotlinx.serialization.json.JsonElement>>(argsStr)
        val url = (args["url"] as? JsonPrimitive)?.content
            ?: return buildJsonObject { put("type", "web_fetch"); put("error", "no_url") }.toString()
        val maxChars = (try {
            (args["maxChars"] as? JsonPrimitive)?.content?.toIntOrNull()
        } catch (_: Exception) { null } ?: 8000).coerceIn(1, 100_000)

        return try {
            val html = HttpClient.fetchModels(url, mapOf(
                "User-Agent" to Constants.WEB_FETCH_USER_AGENT,
                "Accept" to "text/html,application/xhtml+xml,*/*"
            ), callTimeoutMillis = Constants.NETWORK_TOOL_TIMEOUT_MS)
                ?: return buildJsonObject { put("type", "web_fetch"); put("url", url); put("error", "no_response") }.toString()
            val fullText = htmlToReadableText(html)
            val text = fullText.take(maxChars)
            buildJsonObject {
                put("type", "web_fetch")
                put("url", url)
                put("text", text)
                put("truncated", fullText.length > text.length)
                put("totalChars", fullText.length)
            }.toString()
        } catch (e: Exception) {
            buildJsonObject {
                put("type", "web_fetch")
                put("url", url)
                put("error", "fetch_error")
                put("message", e.message ?: "")
            }.toString()
        }
    }

    /**
     * Extracts readable text from an HTML page.
     *
     * Strips non-content blocks (comments, script/style/noscript/svg/head), then lets
     * [HtmlCompat] decode entities and flatten the remaining markup while keeping block
     * elements as line breaks. Extraction runs over the whole (capped) HTML and the caller
     * truncates the resulting *text* — so article content past the page's boilerplate is no
     * longer cut off, and entities (—, ’, accents, numeric refs) are decoded correctly
     * instead of being dropped to spaces.
     */
    private fun htmlToReadableText(rawHtml: String): String {
        val stripped = rawHtml
            .take(Constants.MAX_WEB_FETCH_HTML_LENGTH)
            .replace(Regex("<!--[\\s\\S]*?-->"), " ")
            .replace(
                Regex("<(script|style|noscript|svg|head)\\b[^>]*>[\\s\\S]*?</\\1>", RegexOption.IGNORE_CASE),
                " "
            )
            // Drop common page chrome so navigation/menus/footers don't eat the text budget.
            .replace(
                Regex("<(nav|header|footer|aside)\\b[^>]*>[\\s\\S]*?</\\1>", RegexOption.IGNORE_CASE),
                " "
            )
        val text = HtmlCompat.fromHtml(stripped, HtmlCompat.FROM_HTML_MODE_COMPACT).toString()
        return text
            .replace(Regex("[ \\t\\x0B\\u000C\\r]+"), " ") // collapse intra-line whitespace
            .replace(Regex(" *\\n *"), "\n")               // trim around line breaks
            .replace(Regex("\\n{3,}"), "\n\n")             // collapse blank-line runs
            .trim()
    }
}
