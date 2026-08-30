# Semantic Search Architecture Contract

Status: authoritative development contract, 2026-08-14.

This document is required context for changes to embedding-cache reads, semantic conversation search,
RAG ranking, or the search eligibility query. Semantic search must remain bounded by one database
page plus the requested top candidates; corpus growth must not translate into Android heap growth.

## 1. Observable behavior

- Query embedding generation, model selection, API-key resolution, and user-visible tool behavior
  remain owned by the existing RAG/provider path.
- Searchable sources exclude Task conversations, non-USER/non-MODEL rows, blank or short source
  text, and synthetic tool/result/Compact rows.
- Similarity is cosine similarity. Candidates must be strictly above the configured RAG threshold,
  ordered by descending score, and limited to the requested count.
- Stable embedding row id is the deterministic tie-breaker for equal scores.
- Invalid dimensions, malformed byte lengths, and non-finite scores are skipped without aborting
  the remaining corpus and without logging message content.

## 2. Bounded data flow

1. ChatDao reads a minimal projection containing only embedding row id, message id, embedding
   bytes, and declared dimension.
2. The DAO uses stable keyset pagination (id > afterId, ORDER BY id, bounded LIMIT). It never
   materializes the complete model corpus for semantic search.
3. The selector scores one page at a time directly from the durable BIG_ENDIAN bytes and does not
   allocate a decoded FloatArray for every row.
4. A bounded worst-first top-K heap retains at most the requested result count across all pages.
5. Only the final bounded message-id set is expanded into complete searchable MessageEntity rows.
6. The final expansion revalidates search visibility and minimum source length before returning.

Peak application memory is therefore proportional to one configured page, the cached query vector, the
bounded top-K heap, and the final bounded message set. It is not proportional to embedding-row
count or total cached text.

## 3. Ownership

| Owner | Responsibility | Prohibited responsibility |
|---|---|---|
| ChatDao | Eligibility join, minimal projection, deterministic keyset page. | Full-corpus semantic list or score/ranking policy. |
| ConversationRepository | Pass through the bounded page contract. | Reassembling pages into one collection. |
| BoundedSemanticEmbeddingSelector | Vector validation, page-by-page scoring, strict threshold, bounded top-K, stable ranking. | Room access, Provider calls, message visibility policy, or cache mutation. |
| RagToolProvider | Query embedding, selector orchestration, final bounded message expansion, tool result projection. | Full-corpus materialization or a second ranking implementation. |

## 4. Failure and concurrency behavior

- A malformed cache row cannot fail the whole search.
- A page must be strictly ordered and advance the keyset; a broken loader fails instead of looping.
- Message deletion or visibility changes between scoring and final expansion may only remove a
  candidate. They must not expose a hidden row.
- Search is read-only. It must not delete/rebuild cache rows, increase the heap limit, or retry the
  complete scan as a correctness mechanism.
- Logs may contain aggregate row counts, invalid-row counts, dimensions, and scores, but no source
  message text, embedding bytes, credentials, or conversation content.

## 5. Cache-count presentation

Conversation Search cache-count status is a retained aggregate projection, not a page-owned sequence
of per-model scans. `RagManager` starts one eager refresh when it is created, coalesces overlapping
page-entry or mutation refresh requests, and immediately exposes its last complete snapshot. One
bounded DAO aggregate returns cached counts grouped by configured model id while the indexable-message
total is read independently; no query returns message text or embedding blobs. The embeddings schema
maintains a model-leading index so model-count work is not forced through the `(messageId, modelId)`
unique index. Page entry may request freshness but must not blank the retained snapshot or issue N+1
sequential model counts. Cache/model mutations keep their existing explicit refresh ownership.

No timer, polling loop, periodic Worker, or continuously invalidating Room Flow is introduced for this
status. Startup/page refresh failure retains the previous snapshot and logs only aggregate diagnostics.
Semantic ranking remains governed by the bounded search path above; count optimization cannot
materialize, decode, rank, delete, or rebuild embedding rows.

## 6. Automatic cache backfill and reminder

`Auto Cache` remains enabled by default and owns both incremental indexing of newly persisted eligible
messages and full uncached detection at application startup or active Embedding-model change. When an
active model has uncached messages and no cache run is already active, enabled Auto Cache starts the
existing full model cache runner with silent presentation. That automatic run emits no uncached,
caching, success, completion, partial-failure, or setup-failure Snackbar. Manual cache and recache
actions retain their existing feedback.

`Show Uncached Notification` is a separate default-on portable setting. It is consulted only while
Auto Cache is disabled. In that state, startup and active-model detection may emit the existing
uncached-message Snackbar with its exact manual cache action when the setting is enabled; disabling it
leaves the messages uncached and emits no reminder. The Settings row is placed directly below Auto
Cache and is not shown while Auto Cache is enabled.

## 7. Required verification

Focused verification must cover aggregate count mapping, configured models with no rows, coalesced
refresh, retained-snapshot behavior, the model-leading migration/index, and absence of page-owned N+1
count loops. Semantic-search verification must still cover multiple pages, ranking across page boundaries, strict threshold
exclusion, bounded retained candidates, deterministic equal-score ordering, empty results,
dimension/byte-shape corruption, non-finite vectors, and a source/DAO contract preventing the
unbounded full-list hot path from returning.
