# Web Search Product Contract

Status: authoritative, 2026-08-27.

This contract owns Agora's generic Web Search provider settings and execution, plus the boundary
between that feature and provider-hosted native web search.

## 1. Terms and product boundary

Agora has two distinct capabilities:

- Generic Web Search is a tool executed by `WebSearchToolProvider` using the provider selected on
  the Web Search settings page.
- Native provider-hosted web search is owned by the selected model Provider: OpenAI-compatible
  Responses `web_search` or Gemini Google Search grounding, executed through that Provider's normal
  transport.

Native provider-hosted search is not a generic Web Search provider. It must not gain a second
standalone provider row, credential, base URL, request adapter, or compatibility path.

## 2. Generic provider contract

The generic provider dialog must expose providers in this exact order:

1. DuckDuckGo
2. Brave
3. Kagi
4. Serper
5. Tavily
6. SearXNG

OpenAI must not appear in the generic provider set, provider dialog, API-key settings surface,
localized provider resources, or `WebSearchToolProvider` transport/normalization branches.
DuckDuckGo is the deterministic default and first visible option.

## 3. Compatibility and persistence

- Normalize stored provider values case-insensitively against the current supported set.
- Normalize removed legacy `openai` values and all unknown values to `duckduckgo`.
- New writes may contain only a provider from the current supported set.
- Do not destructively purge an inert encrypted legacy credential merely to remove the executable
  provider surface. Inert legacy data must never reactivate or expose the retired provider.

Compatibility does not authorize restoring retired UI or execution paths. Any material change to
this product boundary or provider order requires explicit user confirmation and a contract update.

## 4. Ownership

- `SettingsContracts.kt` owns supported-value normalization and the default.
- `SettingsWebSearchPage.kt` owns generic provider presentation and exact visible order.
- `WebSearchToolProvider.kt` owns generic provider execution, normalized result enrichment, and
  explicit `web_fetch` page reading.
- Provider configuration, OpenAI-native search availability, `BaseOpenAiProvider`, and
  `GeminiProvider` own their separate provider-hosted search paths.

No owner may infer the other capability from a matching company name or legacy stored value.

## 5. Native provider-hosted availability, request, and presentation

- An official OpenAI Provider or a custom Provider selected as OpenAI-compatible, together with
  Responses API enabled, is sufficient to show `OpenAI Search` in the conversation UI. No
  model-name allowlist, capability probe, local capability table, or extra relay declaration may
  hide it. The paired Service Tier availability and request contract belongs to
  `message-generation.md`.
- When the user enables OpenAI Search, the immutable generation snapshot carries that choice and the
  existing OpenAI-compatible Responses request includes the native `web_search` tool. Do not create
  another transport, tool Provider, or request adapter.
- If the official service, model, or relay rejects the tool or request, persist its bounded ordinary
  generation error and display the shared neutral text-only generation terminal presentation. Do not
  silently fall back to generic Web Search or Chat Completions, auto-disable the setting, or use a
  Snackbar-only error path.
- Every OpenAI Responses `web_search_call` output item must appear in the ordinary message
  timeline as one `OpenAI Search` tool block.
- Gemini candidate `groundingMetadata` must become one completed durable `google_search` hosted-tool
  block. Its result keeps the full grounding metadata and normalized source `results`, but the exact
  `google_search` segment is excluded at the shared UI presentation boundary: it renders no search
  card and contributes nothing to `Called x tools`. It must not call `WebSearchToolProvider` or reuse
  generic search settings. Generic `web_search`, OpenAI `openai_search`, Code Execution, and all other
  tool segments remain visible.
- Provider-hosted calls are display-only. They must never become a local `ToolCallRequest`, execute
  through `WebSearchToolProvider`, consume generic provider credentials, or start a tool
  continuation round.
- The added/done events for one provider call must update the same stable block. Completed and
  failed provider statuses must settle that block terminally; Stop settles an incomplete block as
  stopped through the standard generation lifecycle.
- Hosted search answer citations follow the complete lifecycle in
  [citations.md](citations.md): structured Provider metadata is durable answer metadata and renders
  as numbered answer/source references. Hiding Gemini's `google_search` presentation never removes,
  replaces, or executes its durable hosted-tool block.
- Visible generic and provider-hosted search results share one clean result-list presentation in the
  tool detail sheet. Result title, snippet, and URL/source must form three unambiguous semantic tiers:
  the 16 sp-equivalent SemiBold title is dominant, the 13 sp Normal snippet is subordinate, and the
  11-12 sp URL/source is tertiary in primary color. Rows use deliberate internal spacing and subtle
  separators rather than equal-weight filled cards. Presentation must preserve result order and the
  existing title, URL, and snippet fallback data without changing search execution or settlement.
  Every result whose URL passes the shared HTTP(S) citation-safety policy is one full-row activation
  target. Its Material ripple is bounded and clipped to one 12 dp rounded rectangle, and
  activation opens the safe URL through the Compose URI handler without haptics or a separate Intent
  path. A missing or unsafe URL stays non-clickable. Only the Web Search result host reduces the
  conventional sheet-side outer inset from 24 dp to 16 dp, so every card, divider, ripple, and
  activation target extends 8 dp toward both screen edges. Title, snippet, and URL content stay on
  the established 24 dp text line through an 8 dp alignment inset inside that expanded host, while
  each row keeps 12 dp vertical padding. The rounded boundary must not add a fill or elevation,
  change any other tool-detail inset, or replace the existing separator treatment.
- A visible Web Search Tool Segment specializes only EMPTY/COMPLETED result content. FAILED and
  STOPPED remain on the shared Thinking Tool terminal path and use the same neutral gray body text as
  ordinary message terminal content, with no error/stopped bar, card background, or rounded container.

## 6. Generic search result enrichment

- A successful generic `web_search` keeps the selected provider's normalized result order and
  existing metadata, then automatically attempts a light read of the first three HTTP(S) result
  pages before returning the tool result to the model.
- Each successful light read adds at most 2,500 characters of readable page text to that same result
  as `page_excerpt`, together with `page_excerpt_truncated` and `page_total_chars`. Existing title,
  URL, description/content, provider score, answer, and result ordering must remain intact.
- The three light reads may execute concurrently, but they remain one bounded `web_search` tool
  execution. They must not create additional visible tool calls, Provider passes, generation Runs,
  or continuation paths.
- A missing, non-HTTP(S), unreadable, empty, timed-out, or otherwise failed individual result page
  leaves that search result unchanged. One failed light read must not fail or discard an otherwise
  successful search response. Cancellation still propagates through the ordinary tool/generation
  lifecycle rather than being converted into a page-read miss.
- `web_fetch` remains the explicit deeper-reading tool. The model may call it after `web_search` when
  the light excerpt is insufficient, and the ordinary agent/tool continuation loop remains the sole
  owner of that follow-up.
- Enrichment does not imply a harness-level search-first gate. Enabling generic Web Search exposes
  the ordinary tools; it does not force a `web_search` call before the model's first Provider pass.

## 7. Failure and security behavior

- API-backed generic providers fail with provider-specific missing-credential errors.
- SearXNG validates and uses its configured URL; DuckDuckGo uses its existing public-search path.
- Unsupported values fail closed through normalization to DuckDuckGo; they must not silently call
  an official OpenAI endpoint.
- Generic search must never reuse a model-provider key, URL, or service-tier setting.
- Native search must use the selected conversation provider's established configuration and
  transport, not a hidden generic-search credential.

## 8. Required verification

Changes touching this subsystem must verify:

1. the exact visible provider order and DuckDuckGo-first default;
2. absence of a generic OpenAI provider, resources, settings branch, and transport branch;
3. legacy `openai` and unknown-value fallback to DuckDuckGo;
4. official and custom OpenAI-compatible Providers show OpenAI Search whenever Responses is enabled,
   without a model capability lookup or extra relay declaration;
5. an enabled search serializes the native `web_search` tool in the actual Responses request;
6. Provider rejection persists bounded error text and renders the shared neutral text-only message
   terminal presentation without silent fallback, auto-disablement, or a Snackbar-only path;
7. `web_search_call` added/done lifecycle renders one terminal `OpenAI Search` display-only block
   without local execution;
8. Gemini grounding metadata renders one `Google Search` display-only block with normalized sources
   and retained full metadata, without generic-search execution or credentials;
9. generic `web_search` preserves normalized result metadata/order while adding no more than three
   light page excerpts of no more than 2,500 characters each;
10. a failed individual page read leaves that result unchanged and does not convert a successful
    generic search into a search failure, while cancellation remains propagating;
11. `web_fetch` remains available for deeper explicit reads and no forced search-first generation
    path is introduced;
12. relevant resource contracts, focused tests, the complete scoped diff, and the project full build.

Compilation alone is not proof of visible order or correct capability ownership.
