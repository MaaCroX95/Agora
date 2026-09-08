# Remote Sessions

Status: current owner-approved Filo integration, 2026-09-08. Earlier staged plans and
superseded UI decisions are retained in Git and the single active task log.

## Ownership and protocol

Remote connects Agora to an independent Filo service. The current Codex adapter supports
the original desktop owner and explicitly admitted dormant history; Filo's REDLINES.md
and HARNESS.md govern native ownership and product qualification. Never patch Codex,
redirect its global backend, write native transcripts or acquire a competing writer.
Failed desktop discovery/dispatch cannot fall back to another host, queue or fork.

Remote transport, saved connections and transient presentation are independent of Room,
LlmProvider, GenerationManager and ordinary conversation drafts. Codex is durable truth.
Only the selected session owns Remote history/subscription. Changing selection, connection,
visibility or read generation cancels old reads and rejects late results. In-flight writes
stay bound to their original owner. No automatic POST retry or input replay is allowed.

Protocol 2 requires Bearer authentication, explicit service address, native-steer delivery
and live-messages output. Idle Send starts a native turn; active Send steers the exact
original turn. Stop requires its native active ID. HTTP acceptance and native client-ID
visibility are separate confirmations; unknown delivery is reconciled without blind resend.
Native approvals remain with their owner. Remote attachments, approvals, transcript
editing, branching and tool-execution controls are unsupported.

## Devices and local persistence

Remote sits below Tasks in their original joined drawer group and opens SettingsOverlayHost.
Device selection precedes Sessions; Add Device is a separate shared settings page.
Devices renders its scaffold and locally saved rows independently of all network checks.
No loading text, bar or overlay belongs here. The original MCP status dot reports
idle/connecting/connected/error. A failed or delayed network check never hides the list.

RemoteConnectionStore owns the atomic no-backup connection file and Keystore-encrypted
tokens. Plaintext fallback and overwriting corrupt storage are prohibited. Local restoration
does not select a device, open history, resume or send. Names survive owner recreation;
old URL-as-title entries use the localized Device label until explicitly edited. Add/Edit
provides a Name input and saves its trimmed value. The configured name renders immediately;
network hostname never replaces it. URLs appear only on the address line. Name edits preserve
credentials and viewed-turn IDs; late connection checks cannot revive removed devices.

Save is the only editor submission action. Offline configurations may be saved; successful
persistence returns to Devices before connecting. Invalid input/storage failure retains the
editor. Back without Save discards it. A submitted save may finish after navigation but
cannot take over the newer page. Duplicate storage actions are fenced. Edit atomically
replaces the selected address/token without colliding with another device; Delete confirms
and removes only that local connection. Failure retains it. The row menu is Edit/Delete.
Fields reuse MCP labeled inputs and gray URL/token examples, with no submitted defaults.
Helper copy explains installing Filo on the target and entering its IP or URL, not Tailscale.
Visible entry/save starts coalesced connection checks; no periodic device-health loop.

Load failures use the original application Snackbar, positioned by the original composer
inset owner in chat and the system inset on Devices/Sessions. Preserve the current page and
loaded content; no permanent inline error rows. Read retry is bound to the current selection
and cannot replay sends, settings, admission or other writes.

Storage and protocol errors stay distinct from connection errors. Diagnostics contain only
random owner identity, operation, elapsed time, counts, exception type and HTTP status.
Never log addresses, credentials, session IDs, bodies or message content. Unsaved secrets,
selected sessions, drafts and submission attempts remain transient and are never replayed.

## Sessions and history

Use the existing settings scaffolds, guarded page transitions and rows. Only visible rows
request bounded native statuses. The original 18dp/2dp generating circle has priority over
the 8dp unread dot; retain 200ms fades. Real completed-turn identity owns unread state.
Successful visible history read marks that completion viewed in the encrypted local store;
loading/failed reads do not. Never change native Codex read state.

Sessions automatically loads the next cursor at the laid-out list bottom. Preserve rows
and scroll position, reject repeated cursors, fence late pages and retain explicit error
retry. There is no Refresh or Load More button. Sessions alone shows a bottom 4dp progress
bar for actual loading/paging/control work: opacity enter/exit 300ms, fixed thickness,
initially hidden transition state, retained composition until exit finishes. No overlay.

Selecting eligible history is the explicit one-time admission after its readable page loads.
Preserve ID/history and enable composer/SSE only after native acceptance. Failure remains
readable. No Continue this conversation button, background admission or automatic POST
retry. Unavailable read-only history cannot subscribe or mutate; occupied writers fail safely.

Opening a conversation loads its complete lightweight topology: native identities, order,
groups and revisions. Bounded wire pages are internal transport only. They never define a
visible window, evict message positions or trigger scroll compensation. Original MessageList
and LazyColumn request composed message bodies through observeMessage; original payload LRU
and MessagePayloadProjector bound retained bodies. Search uses the original full-topology ID
scan, body hydration, highlights and scroll owner. Reconnecting merges native changes into
the resident topology; payload reads and cache eviction cannot change its IDs or order.
A real user or different turn ends an assistant group. Remote DTOs remain separate from
ChatMessage/MessageSegment presentation; only public summaries and tool records are mapped.
Tool progress/results and genuine timing retain native semantics. Trim only terminal CR/LF
in presentation text, not interior whitespace, tool payloads or original cached records.

## Exact original ChatApp presentation

Reuse MessageList, Markdown/glyph fade, ChatTopBar, composer/TextField, message menus,
ChatScrollCoordinator, ChatLaunchInteractionEffects and bottom-scroll button directly.
No invented Remote renderer, scroll algorithm, keyboard behavior or animation.
Unsupported message mutations are hidden; Copy, Select text and Info remain available.
Top More contains Search only, with original highlights and navigation. Absent/ID titles
display New Chat. Context capsule appears only for available native telemetry; unknown
context shows an empty circular indicator without a dash or fabricated numeric usage.

The chat loading cover is the original content-area ChatApp block before the composer:
48dp/5dp circle, 200ms opacity. Its lifetime is initial history opening/scroll settling,
ended by readiness or error. No Remote-wide cover, extra pointer interceptor, or loading
cover for ordinary settings changes/generation. Devices/Sessions retain their own behavior.

The native active turn plus native user acknowledgement drives streaming presentation.
Never create the assistant dot before input exists. Appended SSE snapshot text supplies
StreamingTextDelta boundaries to the original fade tracker; initial history/rewrites do not
invent token events. No artificial typing timers. The last card is active only when generation
is active AND no newer block lies below it; stale tool state cannot animate a middle card.
Use the original active-card expansion/collapse. Unknown completed thought duration uses
exact English fallback Thought for a while; when the card contains tools it remains
Thought for a while, called X tools. The fallback only replaces the duration, never the
native tool count. Genuine timing retains original duration text.

Reuse original ComposerSendButton: active + empty draft means Stop, text means Send/steer,
submission/Stop settlement means Busy. Keep pending Stop through both HTTP success and
native end/replacement of that exact turn, regardless of arrival order. Failure clears
pending presentation without pretending idle. Client-ID confirmation clears only unchanged
submitted text, after the original Busy acknowledgement, and emits one original animated
scroll. Navigation and repeated reconciliation cannot duplicate it.

## New Chat, models and settings

New Chat is local until Send: immediate original keyboard, no background creation/request.
The top plus is NOP on an existing draft. The original attachment plus/menu is present with
Camera/Photos/Videos/Files callbacks NOP. Model catalog reads admitted by prior navigation
may finish; draft entry/refresh does not start them. Actual model loading says Loading…,
not Model Unavailable. Display cached native defaults or local choices without fabrication.

Both original and Remote model dropdowns share the leading check for the selected model.
Thinking and Service Tier use the original shared details menu/panels and native per-model
capabilities, with ordinary ChatApp defaults unchanged. Ultra remains Ultra. Unsupported
none/budget/tier options are absent/disabled; unknown current values stay unknown.
Each explicit setting change issues one request and reads native truth; failure preserves
the prior value and settles the panel feedback gate. Settings apply to subsequent turns.
Draft choices apply after one native creation and before first Send. If settings fail after
known creation, explicit retry uses the same native ID. Unknown creation/send stays guarded.

## Memory limits and verification

Filo bounds conversation pages to 256KiB/128 records with explicit tool previews. Android
limits SSE lines/HTTP bodies to 1MiB before UTF-8 allocation, including missing delimiters.
Retained payloads use a bounded window with native replay bookmarks. Eviction cannot make
any valid conversation permanently unenterable; both older and newer content remain readable.
Long text parts preserve native identity, Unicode and internal whitespace. No larger heap or
OutOfMemoryError recovery. Native records remain intact. Layered pages must not duplicate
desktop live turns; off-page native user acknowledgement still supports valid streaming.
Older history does not acquire active-tail presentation or get replaced by incoming SSE.
Paging starts only at the actual list edge, never merely because a huge first bubble is visible.

Verify auth, exact native capability/owner/turn routing, bounded transport/history,
cancellation, stale results, original renderer bindings, local-first Devices and persistence
races. Use focused tests while iterating, then the full build and matching deployment.
Service startup, unit tests, APK hash/install and actual UI acceptance are distinct evidence.
Physical phone UI acceptance belongs to the owner; no taps/screenshots/UI dumps are implied.
