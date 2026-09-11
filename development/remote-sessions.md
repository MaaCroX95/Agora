# Remote Sessions

Status: current owner-approved Filo integration, 2026-09-08. Earlier staged plans and
superseded UI decisions are retained in Git and the single active task log.

## Ownership and protocol

Remote connects Agora to an independent Filo service. The current Codex adapter supports
the original desktop owner for execution and bounded read-only native history for browsing.
Isolated native helpers may handle peripheral operations such as model catalog, rename
and archive. Filo's REDLINES.md and HARNESS.md govern ownership and qualification. Never patch Codex,
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
Native approvals remain with their owner. Remote attachment uploads, approvals, transcript
editing, branching and tool-execution controls are unsupported.

## Devices and local persistence

Remote sits below Tasks in their original joined drawer group and opens SettingsOverlayHost.
Both drawer buttons use the owner-requested 46 dp height, joined 24/5 dp corner shapes and
2 dp inter-button gap. Their icons, labels, focus clearing and navigation remain unchanged.
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
Placeholder text inherits the original field defaults without an extra opacity reduction.
Helper copy explains installing Filo on the target and entering its IP or URL, not Tailscale.
Visible entry/save starts coalesced connection checks; no periodic device-health loop.

Load failures use the original application Snackbar, positioned by the original composer
inset owner in chat and the system inset on Devices/Sessions. Preserve the current page and
loaded content; no permanent inline error rows. Read retry is bound to the current selection
and cannot replay sends, settings or other writes.

Storage and protocol errors stay distinct from connection errors.
Device health is independent of a native session read: an HTTP/service/protocol error must
not mark a reachable device offline. A network read failure checks authenticated info before
changing device health; retries show Connecting, and fresh snapshots restore native readiness.
One interrupted live GET with successful health may recover on the existing three-second
retry before showing a Snackbar. Persistent failures show one notice per failure category
until a fresh snapshot succeeds; true health/authentication failures remain immediate.
Server SSE error events are service failures, not network outages. Recovery never retries
POST, and selection/visibility changes cancel recovery and reject late health results.
Failed live subscriptions may read one bounded snapshot from the same selected native owner
per retry so available history still renders. That snapshot never restores live control
readiness; only a successful live subscription snapshot does. No alternate host is admitted.
Safe failure stages, categories, exception/cause types and HTTP status use the existing
privacy-aware log wrapper and logging preferences; never include error messages or enable
diagnostic capture implicitly.

Diagnostics contain only
random owner identity, operation, elapsed time, counts, exception type and HTTP status.
Never log addresses, credentials, session IDs, bodies or message content. Unsaved secrets,
selected sessions, drafts and submission attempts remain transient and are never replayed.

## Sessions and history

Use the existing settings scaffolds, guarded page transitions and rows. Each catalog page
returns its row statuses in the same HTTP response; do not issue a follow-up status request.
While visible, refresh only catalog pages containing visible rows after the normal interval. The original 18dp/2dp generating circle has priority over
the 8dp unread dot; retain 200ms fades. Real completed-turn identity owns unread state.
Successful visible history read marks that completion viewed in the encrypted local store;
loading/failed reads do not. Never change native Codex read state.
Cache the last confirmed row status by device and session for the Remote owner lifetime.
Navigation and failed/unknown status reads retain it; visible-row reads and real chat snapshots
update it. Removing a device/session or replacing a connection clears its cached entries.
Each catalog response includes exact available row status, active-turn ID, completed-turn ID and
native unread state. Initial entry and later list pages merge those fields together. Visibility
must not immediately trigger another network request. Subsequent polling uses the same catalog
endpoint and its page cursor, never a separate status endpoint. Unknown/failed native status
preserves the last confirmed value; it cannot fabricate idle, completion or execution authority.
This is presentation state only; Filo verifies native ownership/state at dispatch, and Stop requires its exact native turn.

Sessions automatically loads the next cursor at the laid-out list bottom. Preserve rows
and scroll position, reject repeated cursors, fence late pages and retain explicit error
retry. There is no Refresh or Load More button. Sessions alone shows a bottom 4dp progress
bar for actual loading/paging/control work: opacity enter/exit 300ms, fixed thickness,
initially hidden transition state, retained composition until exit finishes. No overlay.

Selecting history preserves its native ID and starts bounded reads and original-owner
subscription. Compose the original input bar immediately, allowing a local draft while
history and native state are pending. There is no history-admission registry, resume POST,
read-only composer notice or Continue this conversation button. Failed operations retain
readable content and report through Snackbar; they do not disable explicit Send retry.
Execution requires the original owner and cannot fall back to a competing helper writer.

Opening publishes only the latest bounded packet and settles at its bottom, including when
it contains only tool/thinking records. Never scan older packets for ordinary text or a group
boundary before publication. Older pages load only at the actual upward list edge; each
request publishes one bounded packet and never merges into an existing rendered fragment.
Thinking/Tool Call groups may span pages.
Legacy continuation hints never trigger group completion. Existing live-tail updates still
bridge genuine gaps before merging. Bodies stay in the original bounded LRU. Each node
retains its physical packet bookmark. Page boundaries add no Spacer or gap.
Same-turn assistant fragments omit duplicate message-shell padding and the 6dp/16dp
assistant spacers at their seam. Ordinary adjacent-message spacing stays identical in
total and belongs to the preceding item; the initial inset owns only the first leading
space. Prepending therefore never changes an existing fragment's internal content origin.
Older pages load on demand at the actual list edge; no
initial complete-history scan or per-message network waterfall. Page bodies prime the
original payload LRU before publication. Remote uses its existing byte budget rather than the
ordinary 16-row viewport entry cap, so a packet cannot evict its own small rows during admission.
Completed Markdown is parsed off the UI thread with the original parser, preprocessing and reference
links, then passed to the original renderer on its first frame. Parsed trees count toward the same
8 MiB payload budget; active streaming keeps its existing incremental path. Eviction re-reads bounded native page bookmarks.
Already admitted IDs and presentation-page membership remain resident and immutable when
older pages arrive or body caches are evicted. Existing rendered fragments never merge with
an incoming older fragment. ChatMessage.displayPageId expresses this boundary to the
canonical MessageList turn builder; ordinary null-boundary grouping remains unchanged.
Automatic history paging must compare the authoritative first message ID with the measured
index-zero LazyColumn key. Topology publication, scroll-isolated render publication and
measurement are separate steps; an old layout must not trigger another cursor request.
Regression verification must keep the pointer down across both publication steps, confirm
a real page arrived, and assert the original visible message and coordinates remain stable.
Include fast consecutive responses that otherwise push the anchor outside the lazy key map.
During a held pull at the history edge, page publication remains immediate. The original
OverscrollEffect retains its stretch: the same positive pull cannot start consuming newly
prepended rows and implicitly release it. Reversing the gesture or releasing uses the exact
original effect and physics. No delayed publication or requestScrollToItem correction.
LazyColumn stable keys preserve the current drag/fling position without a second scroll
actor, scrollToItem restoration, delayed correction or structural window trimming.
While an older page loads, the original 20dp/2dp circular indicator appears in the existing
top boundary inset with 300ms opacity enter/exit. Start exit immediately when the page is
published or the list can scroll back away from that boundary, even if a request is pending.
Its fixed slot never adds a list item or
changes content padding, message geometry, keys or scroll position.
The chat subtitle renders Online/Offline/Connecting beside the exact Devices McpStatusDot,
with its shared state colors; a literal bullet glyph is not a status indicator.
Hiding or reconnecting suspends control readiness but preserves the last native generation
presentation. Only a new native snapshot may complete a group and trigger auto-collapse.
Viewport mutation anchors use the actually measured visual key, never an old-layout index
into newly prepended rows. Drag ownership lasts until gesture Stop/Cancel even while held
stationary or generation has ended; hydration/card mutations cannot claim that viewport.
Original MessageList, body observation, Search highlights and scroll owners remain shared.
Explicit Search may read older pages with cancellation, without changing admitted positions.
Reconnecting updates the native tail without replacing the historical reading position.
Active-body observation is keyed to its owner/message and begins with the admitted cached
body. Suspending observation is not deletion. The original bounded MessageList payload
cache retains rendered streaming bodies for the transition back to ordinary observation;
returning must not replace the last answer with an empty stub or replay offline text.
A real user or different turn ends an assistant group. Remote DTOs remain separate from
ChatMessage/MessageSegment presentation; only public summaries and tool records are mapped.
Native imageView records expose only an opaque revision until their Tool preview is actually
expanded. That preview first reserves its fixed square geometry, then reads real image bytes by
authenticated message identity and Crossfades from its centered circular loading state to the
decoded image without resizing. Reuse ToolImageStore streaming validation/atomic files, original ToolImageAttachment thumbnails
and the existing root media preview. Image files live only in the private disposable Remote
cache (128MiB/64files, two concurrent image reads). Inline answer images use server-parsed
message/revision/index references and the original Markdown transformer with authenticated
private files; retain original Markdown text for copy/search and reuse root image preview.
Inline images reserve the original generated-image300dp square viewport before download,
with8dp corners and centered Crop. Publish each completed image independently. The overlay
uses the original motion-aware28dp/3dp circular indicator and200ms opacity crossfade on
entry and exit; cached images do not wait for another image. Failed reads settle into the
original broken-image presentation plus Snackbar, never an endless loading state. Geometry
and text remain unchanged through download, decode, failure and retry.
Metadata exposes only the image count. Native and ordinary durable tool images
remain untouched. Each image retains the original 20MiB media-store bound. Search reads text
without fetching images; missing/unsupported images preserve the card and conversation and
report a Snackbar. No base64 image data enters topology/SSE and no arbitrary path read is
exposed. Opening another session or changing the exact message revision cancels old hydration and
rejects stale results. Collapsed cards, body observation, paging and Search never download Tool
images; reopening an expanded preview reuses the private image cache.
Tool progress/results and genuine timing retain native semantics. Trim only terminal CR/LF
in presentation text, not interior whitespace, tool payloads or original cached records.

## Exact original ChatApp presentation

Reuse MessageList, Markdown/glyph fade, ChatTopBar, composer/TextField, message menus,
ChatScrollCoordinator, ChatLaunchInteractionEffects and bottom-scroll button directly.
No invented Remote renderer, scroll algorithm, keyboard behavior or animation.
Unsupported message mutations are hidden; Copy, Select text and Info remain available.
Top More contains Search only, with original highlights and navigation. Absent/ID titles
display New Chat. Remote title subtitle shows a bullet plus Online, Offline or Connecting
from its real connection/read state, replacing context usage there. The composer context
indicator retains native telemetry; unknown context shows an empty circular indicator
without a dash or fabricated numeric usage.

The chat loading cover is the original content-area ChatApp block before the composer:
48dp/5dp circle, 200ms opacity. Its lifetime is initial history opening/scroll settling,
ended by readiness or error. No Remote-wide cover, extra pointer interceptor, or loading
cover for ordinary settings changes/generation. Devices/Sessions retain their own behavior.

The native active turn plus native user acknowledgement drives streaming presentation.
Never create the assistant dot before input exists. Appended SSE snapshot text supplies
StreamingTextDelta boundaries to the original fade tracker; initial history/rewrites do not
invent token events. No artificial typing timers. A history-first inactive observation cannot
permanently close a card: later authoritative generation reactivates it through the original
expansion animation and layout-mutation owner. Continuous activity never repeats an expansion.
The last card is active only when generation
is active AND no newer block lies below it; stale tool state cannot animate a middle card.
Use the original active-card expansion/collapse. Unknown completed thought duration uses
exact English fallback Thought for a while; when the card contains tools it remains
Thought for a while, called X tools. The fallback only replaces the duration, never the
native tool count. Genuine timing retains original duration text.

Errors and unknown runtime never disable an explicit Send with nonblank input. Filo decides
whether the original owner can accept that request; failures show the bounded actual HTTP/SSE
error text in the existing Snackbar, with the localized category as fallback. Never log this
text or credentials. An unconfirmed previous delivery keeps Send clickable and presents the
existing check action in Snackbar before any new attempt; no automatic retry or duplicate send.
Reuse original ComposerSendButton: active + empty draft means Stop, text means Send/steer,
submission/Stop settlement means Busy. Keep pending Stop through both HTTP success and
native end/replacement of that exact turn, regardless of arrival order. Failure clears
pending presentation without pretending idle. Client-ID confirmation clears only unchanged
submitted text, after the original Busy acknowledgement, and emits one original animated
scroll. Navigation and repeated reconciliation cannot duplicate it.

## New Chat, models and settings

New Chat is local until Send: immediate original keyboard, no background creation/request.
Within chat, New Chat replaces the current conversation in place using the original title,
composer and scroll owners; the enclosing settings navigation must not create another page.
The top plus is NOP on an existing draft. The original attachment plus/menu is present with
Camera/Photos/Videos/Files callbacks NOP. Model catalog reads admitted by prior navigation
may finish; draft entry/refresh does not start them. Actual model loading says Loading…,
not Model Unavailable. Display cached native defaults or local choices without fabrication.

Both original and Remote model dropdowns share the leading check for the selected model.
Remote Thinking and Service Tier use plain rows in the original details dropdown. Those rows
open the original bottom sheets and slider panels; neither the dropdown nor panel exposes a
toggle. Remote sheets omit the panels' top header/description item and its spacing.
Native None, when supported, and default tier remain selectable on their sliders.
Unknown current values do not open a falsely preselected panel.
Ordinary ChatApp defaults and panels remain unchanged. Ultra remains Ultra. Unsupported
none/budget/tier options are absent/disabled; unknown current values stay unknown.
Each explicit setting change issues one request and reads native truth; failure preserves
the prior value and settles the panel feedback gate. Settings apply to subsequent turns.
Draft choices apply after one native creation and before first Send. If settings fail after
known creation, explicit retry uses the same native ID. Unknown creation/send stays guarded.
The owner-approved creation flow must let the original desktop create and own the task,
including its first-input receipt and initial settings. A helper-created empty task is not
delivery. This native first-send implementation and acceptance remain incomplete; desktop
page switching and input verification are currently paused by the owner.

Read-only HTTP requests may recover from a closed pooled connection on a fresh connection.
This includes catalog, history, model, image and event-stream reads. Native mutations
(creation, Send/steer, Stop, settings, rename and archive) must never be automatically
replayed after an ambiguous transport failure. Authentication failures and redirects
retain their existing boundaries. Verify both read recovery and exactly-once write attempts
against a server that closes a warmed connection before response headers.

## Memory limits and verification

Native failed-turn messages use the original assistant error segment and MessageStatus.ERROR,
which render through the original neutral grey GenerationErrorBar/GenerationTerminalText.
Preserve preceding answers and tools, and keep one stable error identity per native turn.
Transport errors remain Snackbar notices. Missing live state must never invent a native error
or mark a failed turn as generating. Error messages do not disable an explicit Send retry.
No delivery-status text/card belongs above the composer. Rejected/unknown delivery details
use the original Snackbar. Clicking Send with unknown prior delivery shows the existing
check action in that Snackbar; acknowledging it clears the guard without sending any input.

Filo bounds conversation pages to 256KiB/128 records with explicit tool previews. Android
limits SSE lines/HTTP bodies to 1MiB before UTF-8 allocation, including missing delimiters.
Retained payloads use a bounded cache with native replay bookmarks. Eviction cannot make
any valid conversation permanently unenterable; both older and newer content remain readable.
Long text parts preserve native identity, Unicode and internal whitespace. No larger heap or
OutOfMemoryError recovery. Native records remain intact. Layered pages must not duplicate
desktop live turns; off-page native user acknowledgement still supports valid streaming.
Older history does not acquire active-tail presentation or get replaced by incoming SSE.
Paging starts only at the actual list edge, never merely because a huge first bubble is visible.
Prepending primes the bounded Remote body cache before publishing topology. MessageList must
read that prepared body synchronously for its first composition; an asynchronous Flow emission
must not insert an empty, short-lived row into an active drag. The original local caller keeps
its existing hydration path. Verify repeated 300ms pulls with no pause across page arrivals.

Verify auth, exact native capability/owner/turn routing, bounded transport/history,
cancellation, stale results, original renderer bindings, local-first Devices and persistence
races. Use focused tests while iterating, then the full build and matching deployment.
Service startup, unit tests, APK hash/install and actual UI acceptance are distinct evidence.
Physical phone UI acceptance belongs to the owner; no taps/screenshots/UI dumps are implied.

Sessions rows expose the original three-dot dropdown with Rename and Archive and reuse
ChatRenameDialog/ChatDeleteConfirmDialog with archive-specific copy. The owner requires
only Codex thread/archive, preserving history and synchronizing the desktop archive
notification. Neither desktop ownership nor generation disables Archive. Update/remove
a row only after confirmed native success; failures use Snackbar and never hide the task.
Permanent deletion, local record deletion and stopping a writer are prohibited. Cancel stale list reads before a mutation;
late results cannot alter another selected device and repeated pending clicks do not resend.
