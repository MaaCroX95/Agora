# Application UI Contract

Status: authoritative development contract, 2026-08-15.

This document owns durable application-level UI behavior that is not part of message generation,
citations, semantic search, or Web Search. Current explicit user requirements override older
presentation code and translations.

## Global English UI title capitalization
English title-like UI copy must use conventional Title Case. This is a hard UI constraint, not a
page-specific preference. It applies to page and sheet titles, section and group headings, setting
row headlines, dialog titles, menu commands, action labels, and other standalone labels that name a
surface or command. Major words are capitalized; articles, coordinating conjunctions, and short
prepositions remain lowercase unless they are the first or last word. Approved examples include
`Service Tier`, `Developer Options`, `Stick to Bottom`, and `Import from Claude`.

Technical acronyms remain uppercase, including MCP, API, URL, HTTP, SSE, SSH, PDF, and GGUF.
Product names and deliberately mixed-case technical names retain their official casing. Descriptions,
helper text, placeholders, body copy, and status sentences use sentence case instead. Non-English
locales follow their native casing and punctuation conventions.

Call sites must consume correctly authored resources. They must not apply a generic runtime title-case
transformation, because that would corrupt acronyms, product names, user-authored names, and locale
rules. Focused resource or source-contract tests must pin English title values for audited surfaces
and preserve locale key parity.

## 1. Motion ownership and accessibility

Application UI motion consumes the shared Agora motion policy. Spatial press, size, and scale motion
must snap to the stable resting presentation when Reduced Motion disables spatial transitions.
Opacity-only transitions may remain only where their owning component contract allows them.

A screen may reuse an established motion language directly without creating another global animation
owner. Interaction state stays local to the interactive control and must not alter navigation,
validation, persistence, or completion semantics.

## 2. Onboarding primary action

The onboarding Continue/Get Started action preserves its full-width role, page validation, paging,
completion callback, enabled state, colors, and label semantics.

The action has no custom press-driven size, inset, or content-scale animation. It remains at its
stable geometry of 32 dp horizontal inset, 48 dp height, and 1f content scale while pressed and at
rest. The ordinary Material Button indication remains available, but the action does not own a
`MutableInteractionSource`, pressed-state collector, spring, tween, or other custom press-motion
state. Its existing outer layout, shape, color, enabled state, navigation, and completion behavior
remain unchanged.

## 3. Settings category copy

The Generation Settings category description names only its actual category content and is the direct
localized equivalent of `LLM parameters`. It must not mention the context window. This copy change
does not remove or relocate Context Settings, alter the Generation Settings destination, or change
any stored generation parameter.

The default resource and every supported locale must define the same key set. App-owned strings are
localized in the current Android locale; hard-coded English must not replace resource-backed UI copy.

## 4. Chat composer dropdown icon parity

The chat-bottom attachment `+` dropdown and tools `...` dropdown use explicit 24 dp leading
icons/images in every menu row, matching the Material default size used by the user-message
long-press dropdown. Their 16 dp trigger icons remain unchanged. Menu shape, row geometry, 12 dp
icon-label gap, labels, badges, switches, ordering, enablement, and click behavior remain unchanged.

The monochrome Google Search and OpenAI Search provider icons inherit the dropdown's current Compose
content color. They therefore remain legible across light and dark themes and retain inherited
disabled-state alpha; neither row hard-codes a light or dark tint. Provider artwork, icon size,
spacing, labels, badges, switches, availability, ordering, and interaction remain unchanged.

## 5. Chat bottom-bar answer fade

In normal, non-expanded composer mode, the existing 40 dp vertical fade is an alpha mask on the
conversation foreground, not a separately painted background-color cover. Its zero lead, normal-only
12 dp host lift, measured bottom-bar height, and animated composer-expansion spacer place the mask at
the same screen coordinates as the existing fade without moving the chat-bottom Surface or changing
`bottomBarHeightPx`. The mask uses offscreen `DstIn` composition: conversation pixels stay opaque above
the fade, become transparent through the 40 dp band, and remain transparent behind the composer, so
the one actual `AnimatedBlobBackground` below is revealed pixel-for-pixel even while it moves. Normal
mode must not sample, duplicate, freeze, or paint over that dynamic background. List/answer padding,
IME/navigation insets, composer-expansion spacer ownership, and scroll ownership remain unchanged.
Expanded composer mode receives no lift and retains its exact background-color cover with 20 dp
compact-at-screen-top gradient geometry.

## 6. MCP page-entry refresh

Entering the MCP Settings page submits exactly one refresh request for every enabled server with a
nonblank URL, except a server already in CONNECTING state. The page delegates through the ViewModel to
the process-wide `McpRegistry`; it does not create another connection authority. Public refresh entry
points return without holding the Registry lock or constructing or closing a client on the caller
thread. Runtime, client, and transport construction, replacement, close, and connection work run on
the Registry's IO dispatcher under the process-wide AppContainer `appScope`, so page destruction does
not cancel an accepted refresh.

Page-entry requests are single-flight per exact server configuration. A second page-entry refresh
coalesces with an active connection or pending build for that configuration. Every build receives a
monotonic generation ticket, and installation plus snapshot publication require the ticket, current
configuration, enabled state, nonblank URL, and runtime identity to remain current. A removed,
disabled, or replaced configuration therefore fences out stale build, connection, and error results;
stale clients are closed without replacing the newer runtime or snapshot.

Recomposition and navigation within the page's editor do not retrigger refresh. No timer, delay loop,
WorkManager job, alarm, service, background observer, or periodic polling participates. Existing
Settings reconciliation, snapshot StateFlow, retry backoff, manual refresh, and runtime identity
checks remain authoritative.

## 7. Localized category and Thinking-segment labels

The default resource and all eleven supported locale directories define localized values for
`context_title`, `context_desc`, `thinking_segment_display_mode`,
`thinking_segment_display_mode_desc`, `thinking_segment_display_card`,
`thinking_segment_display_bottom_sheet`, and `thinking_segments_title`. Localized resources must
not retain the default English text for those keys, and placeholder sets remain identical.

## 7. Appearance token-detail cleanup

Appearance does not expose the obsolete Detailed token usage toggle. ChatApp, MessageList,
MessageItem, and AssistantMessageContent do not collect or thread that unused UI value. The existing
stored preference key and settings import/export compatibility remain readable and writable so the UI
cleanup creates no migration or archive incompatibility.

## 8. Image-transcription model chooser

The primary image-transcription model chooser lists only currently enabled concrete models. It does
not inject a synthetic `No model`/null-selection row. A previously persisted null value remains
compatible: the settings summary may still show its existing no-model fallback, and nullable
settings persistence/import behavior remains unchanged.

## 9. Appearance Thinking-segment row order

When the Thinking segment display setting is available, Appearance places it immediately below the
Thought and Tool Blocks display setting and before Auto-Expand Active Group. Reordering must not
change the existing Grouped/Compact availability rule, the exact Grouped + Card Auto-Expand rule, or
any stored/effective display-mode behavior.

## 10. Settings destination rows without redundant arrows

Top-level Settings category cards do not render a right-arrow icon; the entire existing card remains
the navigation target with unchanged grouping, padding, labels, descriptions, colors, and spacing.
The Terminal page's enabled-only Manage sandbox row likewise omits only its trailing Chevron while
preserving the row click destination and the separate Sandbox enable Switch. Provider Settings omits
right arrows from built-in Provider rows, custom Provider rows, and Local Models. Custom Provider rows
retain their protocol badge but omit the spacer that existed solely between that badge and its arrow.
No destination, summary, tint, enablement, persistence, or other trailing control changes.

## 11. Full-screen text-file preview typography

The full-screen Markdown-file preview renders its content with the current effective App font from
`MaterialTheme.typography`; it does not replace that font with a hard-coded mono or system family.
Markdown body/list/table text, H1-H6, block code, and inline code preserve their current font sizes and
use exactly 1.1 times their source line height. H1-H6 are explicitly Bold. Link text inherits the
containing paragraph typography.

The ordinary-text preview also uses the current effective App font while retaining its exact 13 sp
font size and 20 sp line height. The already-bold filename overlay, close control, selection, scrolling,
HTML handling, link behavior, Markdown components, content padding, and file-type routing remain
unchanged.

Every full-screen preview subtype enters and exits through one of two shared top-level transition
hosts: the media host covers loading, single video, PDF, mixed image/video paging, and single image;
the text host covers Markdown and ordinary text. With spatial transitions enabled, both hosts use the
same entrance of a 220 ms fade plus a 300 ms center scale from 0.96f to 1f with
`FastOutSlowInEasing`, and the same exit of a 180 ms fade plus a 220 ms center scale from 1f to
0.96f with `FastOutLinearInEasing`. Reduced Motion retains only the corresponding timed fades.

The hosts keep their last payload through exit and release the top-level presentation owner only after
the transition settles. A confirmed video page alone retains the viewer-internal 400 ms player fade
before handing off to the shared top-level exit. Image, PDF, loading, and unresolved media pages hand
off immediately without a pager-owned delay. The mixed-media pager has no duplicate close timer or
second `onClose` owner. Media decoding, pager navigation, gestures, payload routing, shared exit
transitions, and Reduced Motion remain unchanged.

## 12. PDF page rasterization

PDF page rasterization uses the existing framework `PdfRenderer` owner for both selected pages sent
as model attachments and all-page full-screen preview generation. Every newly allocated
`ARGB_8888` page bitmap is initialized to opaque white before
`PdfRenderer.Page.render` receives it. This produces a deterministic white paper background for
PDF regions that do not paint an explicit background and prevents JPEG encoding from flattening
transparent black pixels into a black page that hides correctly rendered black glyphs.

Both consumers share one bitmap-initialization path. The change does not alter page dimensions,
1536 px long-edge scaling, JPEG quality 80, filename/storage ownership, selected-page filtering and
ordering, preview page limits, progress callbacks, cancellation cleanup, page-count behavior,
PDF-authored colors or backgrounds, viewer motion, or attachment/LLM routing. A different PDF engine
or dependency is not introduced without separate evidence of a rendering defect that remains after
opaque-white initialization.

## 13. Full-screen media window layering

A media preview opened from any Dialog-backed Bottom Sheet owns a subsequently created full-screen,
edge-to-edge Dialog window. Window order is source Bottom Sheet below media viewer below the
viewer-owned Image Actions Bottom Sheet created by long press. Compose `zIndex` is never treated as a
cross-window ordering mechanism. Closing the viewer reveals the still-owned source sheet unless that
sheet independently dismissed.

The media Dialog draws one full-size, unscaled black backdrop, disables system window dimming, and
owns that backdrop's alpha through the same retained visibility transition. The backdrop fades from
transparent to black on entry and black to transparent on exit while the media-content layer keeps the
existing shared fade/scale transition. Closing therefore reveals the underlying owner continuously
instead of holding a fully black frame until Dialog destruction, while a content scale below 1f still
cannot expose the square corners of a scaled black rectangle. Exact transition durations/easings,
last-payload retention, Reduced Motion, pager gestures, and confirmed-video-only close waiting remain
unchanged. The long-press Image Actions sheet remains a modal window created after and above the media
Dialog. Its system window dim is disabled so the sheet-owned animated scrim is the only black overlay;
long press must not introduce a one-frame opaque dim flash before the scrim fade.

## 14. Composer clipboard images

The Chat composer TextField participates in Compose Foundation receive-content dispatch for
`image/*`. A clipboard paste may contribute one or multiple URI-backed images. Handled image items
immediately enter the existing `ChatComposerState.onPickImages` private-copy, progress, rejection,
preview, removal, draft, and send lifecycle; transient clipboard URIs are never persisted as the
attachment's durable path.

Only supported image URI items are consumed. Text and every unsupported clipboard item are returned
to the TextField/platform so native text paste, cursor replacement, selection, undo, IME, and
accessibility behavior remain intact. A mixed clipboard payload can therefore insert its text at the
current selection and add its images as attachments. MIME resolution is defensive and copy failure
uses localized existing/new attachment rejection presentation without crashing or leaving a phantom
attachment.

## 16. Drawer conversation-list loading and search progress

The conversation drawer observes only the conversation fields required by navigation, selection, display, and the system-prompt dialog; it never materializes draft text, draft attachment metadata, or branch-selection blobs for that list. Its first emitted snapshot is loading, distinct from a genuinely empty library, and a motion-aware circular indicator fades in and out over the list area.

Conversation search exposes a separate in-flight state from the moment a nonblank query is accepted through debounce and the existing literal/semantic query. Its circular indicator fades in and out in the search field, does not alter query debounce or ranking, and cancellation, clearing, or failure cannot leave a stuck indicator. The retained prior result may remain visible while a new query is pending.

The drawer's first-list state is not a second conversation authority or a new search architecture; Room remains the durable source and the existing search methods remain authoritative.

## 17. Model alias display fallback

A model alias is presentation text, never a model identity. An explicit nonblank alias stored under
the complete model ID remains authoritative. When none exists, the shared model-display resolver
derives a human-readable fallback from the API model name without persisting it. Provider requests,
routing, capabilities, pricing, history, import/export, grouping, deduplication, and settings keys
continue to use the complete original model ID.

Inference removes a provider path for display and recognizes only bounded family-specific suffixes:
the exact `:batch` and `:free` variants, a terminal Claude `fast` serving marker, valid Claude snapshot
dates and version grammar, Amazon Nova's terminal API revision, and an exact terminal Gemini
`preview` marker. A nonterminal Gemini `preview` token and every generic-family `preview` token remain.
Core family, tier, size, speed, capability, version, and every DeepSeek numeric token remain. Unknown
or malformed names receive separator/case humanization without deleting ambiguous tokens such as a
date, number, `vN`, `latest`, `fast`, or colon suffix. The resolver is deterministic, case-insensitive
for recognized grammar, and idempotent for its formatted output.

Qwen tokens with an immediately adjacent numeric version insert one display space between the brand
and version, so `qwen3.8` becomes `Qwen 3.8`. This brand-specific spacing does not change the raw ID
or silently alter the formatting of other brand-number tokens.

Exact standalone tokens use their approved product casing: `glm` becomes `GLM`, `mimo` becomes
`MiMo`, `minimax` becomes `MiniMax`, and `a3b`, `e4b`, `a70b`, `oss`, and `tts` become `A3B`, `E4B`,
`A70B`, `OSS`, and `TTS`. Matching is case-insensitive but does not rewrite substrings or establish a
generic rule for unknown abbreviations or letter-number-letter tokens.

Settings Models renders the resolved alias as every model row's headline and the raw API model name
as supporting text, so distinct IDs remain distinguishable even when serving variants share one
fallback. Search matches provider/raw ID, explicit alias, and inferred fallback without coalescing
results. Existing-model alias editors are seeded with the resolved fallback when no explicit alias
exists. Saving that seed unchanged does not materialize it in DataStore; editing it creates an
explicit alias, while clearing an explicit alias restores fallback behavior. The new-custom-model
form remains blank until the user enters an alias.

## 20. Local Sandbox outcome feedback

Local Sandbox install, remove, upgrade, and reset outcomes are process-local buffered one-shot events.
An outcome produced while no UI collector exists remains queued for the next collector. Pending
outcomes retain production order, and each outcome is consumed by one collector exactly once. An
Activity recreation must not replay an outcome that the previous collector already consumed.

The Sandbox manager and its transient queue share the process lifetime owned by `AppContainer`'s
flavor factory. Foreground ViewModels, generation tools, and headless Task/Loop execution borrow the
same F-Droid manager and must not cancel or close it when a consumer lifecycle ends. Package
install, remove, and upgrade work therefore remains available to later consumers in the same process.
Only an explicit Sandbox reset may cancel the manager scope, and reset must replace that scope before
continuing. The queue is not persisted or restored after process death, mirrored through a durable
flag, or represented as retained UI state. The Play flavor exposes an empty outcome stream.
## 21. Tasks Once date-picker mode transition

The Tasks Once date picker uses Material3's modal `DatePickerDialog` at its stable 568 dp container
height. Material3 remains the sole owner of `DatePickerState.displayMode`, selected-date state,
calendar/input `AnimatedContent`, focus, keyboard interaction, and the mode-toggle transition. Agora
does not mirror the mode, delay or retry keyboard handoff, or animate the Dialog window's
wrap-content height. Confirmation, cancellation, selectable-date validation, formatting, colors, and
schedule persistence remain unchanged.

## 22. Debug test-model visibility

When Developer Options and Debug Model are enabled, the existing `debug` model participates in the
canonical Chat-enabled model set and uses the display alias `Debug`. Every ordinary Chat model
chooser consuming that set, including manual Compact, receives the same model and alias collection;
manual Compact has no private injection or separate policy. The hidden Debug Provider remains a
generation-only implementation detail and never appears in Provider Settings, provider editors,
Models Settings, Tasks, Context Settings, title generation, transcription settings, or another
configuration surface. No new UI, Provider configuration, API-key field, or model-list architecture
is introduced for this test model.

## 23. Conversation-owned attachment import and pre-acceptance Send

Every Composer attachment enters one durable, conversation-owned import lifecycle at selection
time. The attachment tile appears immediately, Agora copies the source into app-private staging,
and all required image normalization, video frame extraction, PDF rendering, ordinary-file text
reading, or Local Sandbox copying begins before Send. `PROCESSING`, `READY`, and `FAILED` are
persisted with the draft for both ordinary conversations and the New Chat workspace. Navigating to
another conversation does not cancel or transfer work; returning shows the same live state. After
process death, `PROCESSING` restarts from its immutable private staged source, `FAILED` remains
retryable, and an unavailable staged source becomes `FAILED`. Legacy drafts without import state
are `READY`. The existing `unavailable` value remains reserved for backup/import restoration when
the attachment resource cannot be restored and never represents import failure.

An image's `READY` private path is its final normalized artifact. The immutable staged image remains
separate until the `READY` draft write succeeds, then becomes reclaimable. A crash after output
creation but before that write therefore restarts from the original staged bytes rather than
compressing the output again. `READY` video frames, selected PDF page images, bounded ordinary-file
text, and Local Sandbox paths are likewise complete import results. Send performs no decoding,
scaling, compression, frame extraction, PDF rendering, file text read, or additional ownership copy.
Provider file reads, Base64, JSON serialization, and upload remain request encoding after accepted
input and are not attachment import work.

A `PROCESSING` tile shows its overlay and freely rotating indeterminate circular progress indicator
through independent Crossfades. A `FAILED` tile remains in place with a gray exclamation overlay;
tapping that overlay retries the complete import from private staging. Failed attachments do not
disable Send and are excluded from the accepted result. `READY` tiles have no processing overlay.

Tapping Send freezes that draft owner's exact text, model/settings snapshot, and attachment
membership. Text editing, add/remove, and retry actions are disabled until the request leaves its
pre-acceptance lifecycle. `WAITING` waits for every frozen attachment's processing coroutine to
exit, then preserves Composer order while retaining only `READY` results; zero successful
attachments is valid when the frozen text independently permits Send. Tapping the spinning Send
control during `WAITING` cancels only that Send request, keeps attachment processing alive, restores
editing, and releases the deletion lock. `SUBMITTING` is not cancellable. Failure before accepted
input returns the same frozen Composer to editable `IDLE`. Authoritative acceptance clears that
owner's complete Composer, including failed tiles.

Switching conversations cannot cancel, redirect, duplicate, or clear the frozen request. From Send
tap through authoritative acceptance and exact-owner clearing, Delete Conversation is disabled for
the origin and the controller rejects deletion races below the dialog. A New Chat request selects
its newly created conversation only if the user still occupies the originating New Chat workspace;
otherwise it appears in the list without taking focus. A process restart restores the durable draft
and attachment imports but never automatically replays an unaccepted Send request.

Attachment paging preserves occurrence identity. Send emits successful attachment artifacts and
metadata in one traversal of Composer order. Composer and durable-message viewers assign pager
indices while constructing their filtered media sequence; they never recover an occurrence with
`indexOf` on a URI or path, so duplicate values and mixed attachment types open the tapped item.

## 15. Verification

Focused verification must cover the onboarding action's fixed 32 dp inset and 48 dp height, absence
of custom press-size/inset/content-scale state, and unchanged action semantics, Generation Settings description, locale key/value
parity for the Context and Thinking-segment labels, absence of the removed context-window wording,
24 dp leading-icon parity across both chat-bottom dropdowns without resizing their triggers,
theme-adaptive Google Search and OpenAI Search icon color without fixed light/dark tint, absence
of the Detailed token usage Appearance row and dead chat-side parameter threading, the Tool Blocks ->
Thinking segment -> Auto-Expand Appearance row order with unchanged predicates, the normal-only
0 dp gradient lead with unchanged 40 dp width and 20 dp expanded behavior, and scoped Settings-arrow
absence with preserved category/Sandbox/Provider click destinations, Sandbox Switch, and custom
protocol badge. PDF rasterization verification must cover one shared opaque-white bitmap initializer
used by both render paths, initialization before every framework page render, and unchanged
scaling/JPEG/page-selection/progress/cancellation behavior. Full-screen text-preview verification
must cover current App-font inheritance in
both Markdown and ordinary-text paths, exact 1.1 Markdown line-height scaling, explicit Bold H1-H6,
unchanged Markdown font sizes, and the unchanged 13 sp / 20 sp ordinary-text metrics. It must also
cover both shared full-screen transition hosts, the exact fade/scale durations and easings, Reduced
Motion's fade-only fallback, last-payload retention, release only after settled exit, confirmed-video
close waiting, immediate non-video handoff, and absence of a duplicate pager close delay. Media
verification also covers Dialog-over-sheet ordering, viewer-owned action-sheet ordering, unscaled
full-screen backdrop, and no scale-below-one corner exposure. Composer verification covers single and
multiple image URI paste, mixed image/text pass-through, unsupported content pass-through, immediate
private-copy routing, and failure cleanup. Model-alias verification covers explicit precedence, all
approved family-specific suffixes, generic preservation of ambiguous tokens, casing/separator
normalization, idempotence, inferred search, duplicate-display preservation, raw-ID supporting text,
and unchanged-fallback non-persistence. Tasks Once verification covers the fixed 568 dp Material
modal height, Material3 ownership of display mode and keyboard interaction, and absence of shadow
mode, delay, retry, or window-size animation. Debug-model verification covers one canonical
Chat-enabled model/alias set shared by ordinary Chat and manual Compact while every Provider Settings
and other configuration surface remains free of Debug Provider/model integration. Local Sandbox
outcome verification covers emission before collection, ordered pending outcomes, one-time sequential
display and consumption, absence of replay after collector recreation, every
install/remove/upgrade/reset success and failure path, the empty Play stream, and the absence of
persistence or retained UI state. The project-defined full build gate remains required after final
code or resource changes.
