# Import and Export Contract

Status: authoritative product, persistence, and compatibility contract, 2026-08-28.

This document owns native `.agora` archives, portable settings, import strategies, secret transport,
and automatic-backup compatibility. Public manuals describe the user workflow; this contract defines
the exact development boundary. Current explicit user requirements override older archives or prose.

## 1. Canonical owners

- `NativeBackupFormat` owns archive version support and stable entry names.
- `DataExporter` owns category selection, manifest emission, and archive writing.
- `DataImporter` owns archive validation, category decisions, ordered restoration, and partial-error
  reporting.
- `PortableSettingsArchive` is the only allowlist for portable `settings.json` fields.
- `SettingsManager.resetPortableSettingsForImport` is the exact Settings `REPLACE` reset boundary.
- `NativeBackupSecretsPolicy` is the only owner of opt-in secret capture and restoration.
- Conversation, attachment, Memory, Skill, System Prompt, and custom-font owners retain their normal
  persistence and conflict rules during transport; import/export does not create shadow stores.
- Unreadable conversation or draft image, video, PDF, and file references remain in their original
  attachment order as typed unavailable placeholders. Placeholders retain the filename when known,
  carry no device URI/path, and are never exposed as readable or previewable content.

Adding a DataStore key does not make it portable. A setting enters an archive only after this
contract, export, restore, Replace reset, and focused compatibility tests are updated together.

## 2. Archive envelope and categories

The native format is a ZIP containing `manifest.json`. The manifest records
`agora_export_version`, app version, export time, selected category keys, and whether a secret payload
is present. Current code writes version 4 and accepts versions 1 through 4. An unsupported version is
rejected before category restoration.

| Category | Stable payload boundary |
| --- | --- |
| `conversations` | `conversations.json` plus archive-safe image, video, and draft media entries. Conversation-scoped settings travel with their conversation, not in `settings.json`. |
| `memories` | Active Memory, Memory database Markdown/metadata, and Skill database Markdown/metadata below `memories/`. |
| `system_prompts` | `system_prompts.json`. The active prompt reference remains a portable Settings field and is resolved against imported/current prompt identity. |
| `settings` | `settings.json` containing only the allowlist below, plus `custom_font/font` when the selected custom font is readable. |
| `api_keys` | Plain JSON `api_keys.json`, emitted only when the API Keys category and explicit include-secrets choice are both active. |

The archive never treats a manifest category as proof that its payload is valid. Missing, malformed,
or incompatible entries produce category errors without reinterpreting another entry as a fallback.
Archive validation completes before any category mutation or resource extraction. Entry names must be
relative forward-slash paths with no empty, `.` or `..` segment, drive prefix, backslash ambiguity,
or duplicate/colliding file-directory identity. All non-resource entries together are limited to
256 MiB expanded metadata, and every entry's streamed byte count and CRC must match the ZIP record.

Conversation image/video/draft resources, legacy `images/` and `videos/` resources, and the recognized
custom-font entry are storage-backed resources. Normal attachment resources have no fixed byte or
entry-count ceiling when storage is sufficient; there is no standalone total ZIP entry-count limit.
The archive cache copy and every selected resource extraction preflight destination capacity, check
space again while streaming, and delete partial files on failure. The custom-font owner retains its
separate 64 MiB limit. Sandbox and proot payloads remain excluded rather than becoming resources.

A conversation export reads conversation settings before entering Room, then captures Conversations,
Runs, paged Messages, Tasks, Loops, and every raw media reference into a temporary typed JSONL spool
inside one `ChatDatabase` transaction. The transaction performs no destination, ZIP, or media I/O.
Only after it returns may export open the destination, read media, rewrite archive paths, and emit
`conversations.json`. The spool is deleted on success, failure, and coroutine cancellation.

## 3. Portable `settings.json` allowlist

The following JSON field names are the complete current portable allowlist.

| Group | Portable fields |
| --- | --- |
| Model selection | `selectedModel`, `customModels`, `enabledModels`, `modelAliases` |
| Context | `contextTokenBudget`, `visualizeContextRollout`, `contextCompactEnabled`, `contextCompactModel`, `contextCompactPrompt`, `contextCompactRetainCount`, `contextCompactThresholdPercent` |
| Provider and reasoning | `codeExecutionEnabled`, `googleSearchEnabled`, `thinkingEnabled`, `thinkingLevel`, `thinkingBudgetEnabled`, `thinkingBudgetTokens`, `openAiServiceTierEnabled`, `openAiServiceTier`, `openAiResponsesApiEnabled`, `providerBaseUrls` |
| Title generation | `titleGenerationEnabled`, `titleGenerationModel`, `titleGenerationPrompt`, `titleGenerationNotificationsEnabled` |
| Tool access | `accessPastConversations`, `accessSavedMemories`, `accessActiveMemory`, `accessSkills` |
| Search and embedding | `ragSearchEnabled`, `modelSearchMethod`, `manualSearchMethod`, `remoteEmbeddingModels`, `activeRemoteEmbeddingModelId`, `searchContextWindow`, `searchMatchLimit`, `ragThreshold`, `autoCacheEnabled`, `showUncachedNotification` |
| Language, Web Search, and image generation | `appLanguage`, `webSearchEnabled`, `webSearchProvider`, `webSearchNumResults`, `webSearchBaseUrl`, `imageGenEnabled`, `imageGenModel`, `imageGenSize`, `autoUpdateCheck` |
| Image transcription | `imageTranscriptionEnabled`, `imageTranscriptionEnabledModels`, `imageTranscriptionModel`, `imageTranscriptionBatchSize`, `imageTranscriptionPrompt` |
| Shell, automation, custom Providers, and MCP | `shellEnabled`, `shellConfirmEnabled`, secret-free `shellDevices`, `automationToolsEnabled`, `exactExecutionEnabled`, `customProviders`, secret-free `mcpServers` |
| Proxy | `proxyEnabled`, `proxyType`, `proxyHost`, `proxyPort`, `proxyUsername`, `proxyBypass` |
| Appearance | `showDocumentationFab`, `themeMode`, `colorScheme`, `dynamicColor`, `blurEffectsEnabled`, `reduceMotion`, `stickToBottom`, `parseInlineDollarMath`, `hapticsEnabled`, `detailedTokenUsage`, `toolCallDisplayMode`, `thinkingSegmentDisplayMode`, `autoExpandActiveGroup`, `schemeStyle` |
| Custom font | `fontPreference`; `customFontName` only when `custom_font/font` is included. The device file path is never portable. |
| Generation defaults | Nullable `defaultTemperature`, `defaultMaxTokens`, `defaultTopP`, `defaultFrequencyPenalty`, `defaultPresencePenalty` |
| Prompt selection | Nullable `activeSystemPromptId` |

Nullable fields are deliberately emitted as JSON null when unset. Their presence distinguishes
"clear this portable value" from an older archive that has no opinion about the field.

`accessSkillsModify` has no independent archive field. Settings `REPLACE` clears its explicit
device value so the restored setting follows the portable `accessSkills` compatibility fallback;
`MERGE` leaves an existing explicit modify value unchanged.

Only remote Embedding configurations are portable. They are stripped of API keys and local file
paths. Local Embedding configurations and all GGUF paths remain device-local.

## 4. Device-local exclusions

The following state must not enter `settings.json`, must not be restored from it, and must survive a
Settings `REPLACE` import unless a separate selected category owns it:

- Local Chat model records, GGUF paths, multimodal projector paths, Local Embedding model records,
  and imported model files.
- `local_model_idle_retention_minutes` / Local model idle retention. It persists in this device's
  DataStore, defaults to five minutes, and is not exported, imported, or cleared by Replace.
- Sandbox enabled/shared-storage state, sandbox files, and pending/runtime Sandbox attachment state.
- Developer Options, first-launch/onboarding/rating state, message counters, and other installation
  lifecycle metadata.
- Automatic-backup enabled state, schedule, destination, retention, last-run timestamp, and derived
  model-fetch/cache fingerprints.
- API keys, active API-key IDs, Web Search keys, proxy password, Shell credentials, remote Embedding
  keys, and MCP headers; these belong only to the explicit secret category.
- Conversation-scoped settings; these travel only with an exported conversation.
- Custom-font filesystem paths. The font bytes use the dedicated Settings-category entry.

Derived Provider/model catalogs and endpoint-resolution probes are never restored. Replace invalidates
derived portable model caches so stale discovery results cannot masquerade as imported configuration.

## 5. Secret transport

The secret payload contains Provider API-key records and active IDs, Web Search API keys, proxy
password, Shell API keys and SSH passwords keyed by stable device ID, remote Embedding API keys keyed
by model ID, and MCP headers keyed by server ID. Version 1-3 name-keyed Shell API keys are accepted
only for legacy compatibility.

`api_keys.json` is plain JSON inside the ZIP; Android Keystore envelopes are not portable. It is
therefore emitted only after explicit include-secrets selection. Structural Settings payloads always
strip those values. Secret restore ignores orphan records that have no matching structural owner and
reports warnings rather than inventing devices, models, or servers.

## 6. Import strategies

Every archive category has an independent decision:

- `SKIP` performs no mutation for that category.
- `MERGE` preserves unrelated local state and adds or updates only imported identities according to
  the category owner. For Settings, only fields present in the archive are applied.
- `REPLACE` replaces only the selected category. It never broadens into an unselected category or
  device-local state.

Settings `REPLACE` first clears exactly the portable preference allowlist, then restores fields
present in the archive. Fields absent from an older archive resolve to current defaults. Device-local
exclusions are intentionally not cleared. Composite records such as custom Providers, remote
Embedding models, Shell devices, MCP servers, and custom fonts use their dedicated merge/identity and
secret-remapping rules rather than raw map replacement.

Secret `MERGE` retains unmatched local secrets and merges imported records by stable or semantic
identity. Secret `REPLACE` clears replaceable secret values for existing structural owners when the
archive omits them, but it does not create an owner for an orphan secret.

ChatGPT and Claude imports expose `MERGE` and `REPLACE` for the selected conversations. `MERGE`
preserves every unrelated local conversation and adds only imported graph identities that are not
already present. `REPLACE` deletes every existing conversation graph and leaves only the selected
imported conversations. The external replacement validates the complete selected graph before any
delete and performs the delete plus Conversation, Run, and Message writes in one Room transaction.
The unified Settings UI must present a second destructive confirmation before starting external
`REPLACE`, explicitly stating that all existing conversations will be deleted and only the selected
imported conversations will remain.

## 7. Legacy compatibility and failure behavior

- Unknown JSON fields are ignored; known fields are normalized and validated by their current owner.
- Legacy `maxContextWindow` maps to `contextTokenBudget`.
- Legacy `activeEmbeddingModelId` is accepted when the current
  `activeRemoteEmbeddingModelId` field is absent.
- Legacy `active_system_prompt_id` maps to `activeSystemPromptId`.
- Archives before version 4 may restore `extra_settings.json` and the legacy custom-font layout.
- Provider/model references are remapped when stable custom-Provider identities are allocated or
  reused. Active references are accepted only when their target survives import.
- A category failure is reported with its category and does not convert malformed input into a
  successful default. Temporary custom-font files are deleted when installation fails.
- Missing individual attachment resources do not fail an otherwise valid export or automatic backup.
  The successful result reports the total unavailable-resource count, and restore keeps each durable
  placeholder disabled with its type, filename, and relative attachment order intact.
- Import must never delete or overwrite data outside the selected category and strategy boundary.

## 8. Required verification

Focused tests for any archive or setting change must prove:

1. export and restore use the documented JSON name and compatible type;
2. Settings `REPLACE` clears the portable key and preserves every device-local exclusion;
3. `MERGE` leaves absent fields unchanged and explicit null clears nullable portable fields;
4. structural Settings export contains no secret or device-local path;
5. opt-in secrets round-trip without creating orphan structural owners;
6. supported old versions and aliases restore deterministically, while unsupported versions fail;
7. archive category selection cannot mutate an unselected category;
8. conversation media, Memory/Skill files, System Prompts, and custom fonts keep their owner-specific
   conflict, cleanup, and rollback behavior;
9. archive validation rejects unsafe or duplicate paths before mutation, caps aggregate non-resource
   metadata at 256 MiB, verifies streamed size and CRC, and enforces cache/destination capacity before
   and during copy without a standalone resource entry-count limit;
10. unreadable attachment resources preserve order, type, and filename as disabled placeholders, and
   successful manual and automatic backups report the complete unavailable-resource count;
11. the default and every maintained public manual remain consistent with this contract.

The project full build remains required after implementation changes. Build success alone does not
prove SAF access, large-archive streaming, device storage, or user-visible conflict handling.
