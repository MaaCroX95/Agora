# Remote Sessions

Status: owner-approved existing-session chat with public activity display, 2026-09-07.

## Boundary

Remote appears directly below Tasks in the drawer and opens the existing overlay host.
The first connector is Filo for existing Codex sessions, including sessions owned by
Codex desktop. Device selection precedes session selection. The original Codex process
retains execution ownership. Agora must not resume the session in a new process.

The owner explicitly requires independent Remote transport and state. Room,
LlmProvider, ordinary conversation draft/submission and GenerationManager remain the
ordinary-chat owners. Remote does not write to them. Codex remains durable truth;
Remote caches selected-session presentation in memory and owns its separate saved connections.

## Shared presentation

Use MessageList and its existing message/Markdown renderers, ChatComposerLayout,
ChatComposerSurface, ComposerSendButton, and ChatScrollCoordinator directly. Queue rows,
top-bar action capsules and the bottom-scroll button also share their original owners.
No copied Remote message list,
bubbles, Markdown, TextField, scrollbar or scroll animation implementation is allowed.
Ordinary bindings retain their original behavior. Shared scroll effects accept explicit
completion callbacks; native Remote messages supply the shared hydration callback.
Unsupported history mutation and branch actions are disabled at the list binding.
Connection/session navigation follows the existing Settings scaffold, rows and guarded
navigation; top-level presentation ownership follows SettingsOverlayHost.

## Initial protocol and behavior

Filo v1 authenticates with a Bearer token over an explicit LAN/Tailscale address.
The client lists existing sessions, loads paginated messages and public activity, submits
text to Codex's native queue and refreshes persisted output. It never retries a POST
automatically. An unknown submission result remains visible for manual reconciliation;
it must not be presented as a definite failure and blindly resent.

Connections are saved explicitly before connecting in a Remote-only atomic file under
the app's no-backup directory. Device names and canonical service addresses accompany
Keystore-encrypted tokens. Encryption must succeed before writing; plaintext fallback
is rejected. Corrupt or undecryptable storage is preserved and reported, not overwritten.
Remote owner recreation restores the device list without selecting a device, reading
conversation history or submitting messages. Visible device-list entry checks connection
status independently; selecting a device admits its session read after protocol validation.
Removing a connection updates storage before removing its runtime client and local state;
failure retains the entry. Concurrent connection changes are serialized. Network errors
never remove saved devices. Storage failures remain visible and can be retried.
Connection and read errors distinguish network, authentication, invalid connection input,
protocol/data, service and storage failures. Remote lifecycle and failure events use the
existing DeveloperDiagnostics capture when enabled. Events contain a random owner ID,
operation, elapsed time, device count, exception type and HTTP status only; never addresses,
tokens, session IDs, exception messages, response bodies or conversation content.
Drafts, selected sessions and submission attempts remain in memory and are never replayed.
Tokens never enter ordinary settings, logs, saved instance state or conversation storage.
Backgrounding or dismissing Remote stops its read polling. In-flight native submissions
remain bound to their original session; reopening the UI never resubmits them.
Changing devices or sessions cancels old reads and rejects stale results by connection
and session identity. A send remains bound to its originating session. Draft clearing
requires successful queue acceptance and the unchanged submitted text.

Native queue dispatch may take about ten seconds and requires the existing session to
be loaded in Codex. Persisted-message refresh is not token streaming. Stop, remote
approvals, tool execution controls, attachments, editing, branching and model settings
remain outside this slice; actual capability labels must not imply their availability.

## Owner-requested device UI alignment | 2026-09-07

The Drawer Tasks and Remote actions form one visually joined group. Use the existing
grouped-card geometry: 24 dp outer corners, 5 dp adjoining corners, and a 2 dp inter-item
gap. Both actions use 52 dp height, retaining horizontal geometry, colors and click behavior.

The Remote root follows the MCP Settings list/add structure: saved devices appear in
the device list, with a separate shared SettingsAddItem below the device rows. Device
addition has its own page; the root no longer contains the address/token form. Reuse
the existing Settings scaffold, row components and guarded page transitions. Existing
shared chat presentation and independent Remote transport remain authoritative.

The owner approved this amendment on 2026-09-07. The device group uses an icon/title/
description empty row when no devices are saved; restoration or storage failure must
not masquerade as a known empty list. Save is the only editor submission action.
Unsubmitted Back saves nothing. Invalid input or storage failure retains the editor;
successful storage returns to the device list, where connection status is reported.
Back from sessions returns to the device list. The existing RemoteViewModel
owns the add-page flag alongside device/session navigation, and the shared transition
host protects outgoing pages. Unsaved credentials remain page-local and never use saved state.

An explicitly submitted connection can finish saving after Back, but the ViewModel's
selection generation prevents its completion from taking over the
newer page. Backgrounding only stops reads and does not change that selection generation.
Duplicate saves and reopening the editor remain disabled during storage operations.
No automatic submission retry or new routing owner is introduced.

## Owner-requested MCP save/status correction | 2026-09-07

Approved by the owner: the editor uses MCP's labeled fields, helper text below the URL,
and a top-right Save action. Offline configurations can be saved. Each device row shows
its connection state with MCP's status presentation; failures never discard credentials.
Row clicks open sessions. The right-side three-dot menu contains exactly Edit and Delete.
Edit reuses the populated form; changing the address replaces the original configuration
atomically and cannot overwrite another device. Delete follows the MCP confirmation flow
and removes only the local connection. Back without Save discards form changes.
Checks run after saving and on visible device-list entry, coalesce in-flight checks, and
reject results from replaced/deleted clients. No periodic device-health loop is added.

## Approved reasoning/tool presentation | 2026-09-07

The owner requests removing the default Keep this session notice from the Remote
composer and displaying Thinking and tool calls. Existing rejection/unknown-delivery
feedback remains meaningful and independent of that default notice. The owner approved
this presentation extension, Filo plugin update and configured Agora installation.

Use Codex's public reasoning summaries and tool records, mapped into existing
MessageSegment thought/tool presentation. Reuse the ordinary display preferences,
grouping, details, Markdown and scroll components. Remote transport/state and original
Codex execution ownership stay separate from Agora-owned generation. Preserve native
identity/order, pagination and no automatic POST retries. This increment
continues refreshing persisted records; visible summaries and recorded tool state are
not evidence of a subscribed live token stream or a complete live execution snapshot.

History requests opt in with includeActivity=true. Legacy text-only responses remain
readable and old clients keep their text projection. Only public nonempty summaries
are displayed; raw hidden reasoning and binary tool media are never transported.
Consecutive assistant records within the same native turn become ordered answer,
thought and tool segments in one existing ChatMessage. Its first native ID remains
the presentation identity; native IDs still own cached records and tool-call identity.
A real user record or another native turn always ends that presentation group.
Native tool states and durations are preserved; partial output uses toolProgress until
the recorded state is terminal. Turn status does not create artificial live Thinking
timers or generation state. Tool details remain owned by MessageList and observe the
latest projected message by ID. Ordinary tool/Thinking preferences apply unchanged.

## Verification

Verify authenticated transport, strict protocol compatibility, projection identity,
pagination, cancellation/stale-result rejection and no automatic POST retries. Verify
actual queue execution in the original native owner separately from service startup.
Compile and focused tests cover shared ordinary bindings. Run the full project build
and configured deployment at completion; device UI acceptance remains owner-tested.

## Requested session/composer parity extension | 2026-09-07

Pending requirements closure and scoped implementation approval: replace the session
list refresh with New Session; show Add/More in the applicable conversation top bar;
reuse the ordinary model selector/context composer controls, send-progress indicator,
animated send scroll and generation activity dot. The owner requires full existing UI
reuse. This request expands the earlier display-only scope; creation/execution ownership,
applicable menu actions and actual native model/context/running capabilities must be
verified before implementation. Do not infer generation from a stored turn snapshot or
implement cosmetic controls without their defined behavior. Original ordinary-chat owners
and the independent Remote transport boundary remain authoritative.

The owner clarified that the requested conversation top bar is the opened chat page
inside Remote. The original ordinary-chat page is the UI reference, not a requested
behavior change target. Its More menu reuses the ordinary presentation and includes
only operations actually supported by Remote. System Prompt and other unsupported
actions are omitted rather than displayed as disabled placeholders. This does not
authorize adding unrelated session operations. The owner selected the connected host's
default working directory for New Session, without a project/directory chooser on each
click. Resolve that default from the host environment/configuration; do not substitute
the Filo source/plugin directory or inherit the previously viewed session directory.

The owner requests steer as the default Send behavior, superseding the installed
native-queue behavior for this pending extension. During generation, Send targets the
active turn in its original Codex owner. Idle sending starts a new turn. A failed steer
must not silently become a queued submission or resume the session in another process.
Submission acknowledgement, unknown-delivery protection, animated send scroll and
generation presentation must use the actual accepted native operation and state.
Implementation remains pending original-owner capability verification and scoped approval.

## UI implementation directed separately from host attachment | 2026-09-07

The owner directs proceeding with the requested Remote UI now, superseding the
blanket dependency of all presentation work on original-host attachment. Reuse
ChatTopBar Add/More and its dropdown shell; current supported menu entries are
Refresh and, when available, Load Earlier Messages. New Session occupies the Add
slot with disabled/accessibility-unavailable presentation until its host operation
exists. Ordinary chat retains its existing actions and defaults.

Remote accepted sends reuse ScrollRequestCoordinator and ChatScrollCoordinator
for a one-shot absolute-bottom feedback request against the selected projected tail
(the native tool/thought records can share one visible message ID).
The request is emitted only on first acceptance, never on rejection/unknown or
repeated history reconciliation; navigation clears it and completion matches its
exact ID. Busy presentation lasts through acceptance until unchanged composer
text is cleared. Preserve edited drafts and original-session submission identity.

Model/context controls share the ordinary visual owner and geometry. Until the
host provides usable operations/data, the requested complete layout uses disabled
model selection and unknown context, without numeric zero or fabricated budget.
No historic turn/tool snapshot creates live generation state. True original-host
steer, New Session execution, model changes and the live generation dot remain
backend capability work; the installed native-queue transport is unchanged here.

## Approved shared-host functional implementation | 2026-09-07

The owner explicitly approves the shared local App Server and the current desktop
CODEX_APP_SERVER_WS_URL startup dependency. This supersedes disabled-control and
native-queue behavior above. Desktop switching is arranged after source/test
preparation; do not resume a real task into a competing still-running old host.

Protocol 2 provides direct idle turn/start, exact active turn/steer without queue
fallback, new sessions using the shared host default cwd, native model changes,
context telemetry, Stop and live SSE snapshots driven by native item/status events.
The shared native host survives Filo service stop/reinstall. Approval decisions
remain desktop-owned in this increment. Never reject another client's requests.

Remote submission distinguishes an HTTP/native receipt from a user message actually
appearing in the native conversation. Keep the shared send button busy through that
confirmation and composer clearing. Once the exact client ID is visible, clear only
the unchanged draft and request one ordinary animated scroll against that native
user message ID. Neither queue admission, the old history tail, replayed snapshots,
nor an arbitrary minimum timer is a send-completion signal. Unknown POST outcomes
are reconciled by client ID and never automatically resent.

Selected-session SSE cancellation and native snapshot application use existing owner
and read-generation guards. Disconnection invalidates current runtime truth while
preserving saved devices, history and drafts. Only native active state drives shared
MessageList generation/dot/stream presentation; use the native active turn identity
for Stop. Model/context use the extracted ordinary controls, real model catalog and
measured usage; unavailable telemetry remains unknown. Normal conversations and their
backend remain unchanged. Existing messages, composer, scroll and motion rendering
remain the only UI implementation. New/More actions contain only supported operations.

The existing ComposerSendButton now optionally acknowledges when its existing BUSY
Crossfade has settled and a frame has presented it. Remote retains accepted composer
clearing until that acknowledgement, so a fast receipt cannot remove the spinner
before it is shown. The animation specification and normal-chat defaults are
unchanged; no minimum-duration timer or duplicate spinner is introduced. Native
active turn state projects into the existing empty assistant/streaming message
presentation and tail controller; idle/unknown state cannot create an activity dot.

## Approved independent-host connection phase | 2026-09-07 | Codex via Conch
### Owner instruction (verbatim)
> 可以这么做，先实现一下已有功能吗，让我先能连通quantum

The owner accepts the preceding original App Server + independent Filo client architecture and explicitly requests an initial working connection to quantum. This authorizes the bounded implementation and deployment below. Existing-desktop attachment is deferred for this phase; it is not claimed as supported, and all normal-Codex safety redlines remain binding.

Plan:
1. Filo connection phase (<500 changed lines target; hard ceiling 1000): retain withdrawn desktop/install/package entrypoints; add a separately named standalone gateway entrypoint connected to an explicitly configured original loopback host. Scope all session operations to a durable allowlist of sessions created by this gateway. Reuse native history/models/new/send/steer/stop/SSE. Use readonly/never-approval defaults for newly created Remote sessions while remote approvals remain unsupported. Fix client disconnection so it cannot hang or kill the native host. Verify scope rejection and lifecycle behavior.
2. Agora compatibility phase: only Agora-Remote-Control on remote-control; preserve original Agora. Admit explicitly declared standalone mode, retaining all current rendering/transport behavior and clear host labeling. Focused protocol checks, then one fresh build.ps1 and configured deployment after backend verification.
3. Quantum launch/acceptance phase: native host and gateway are separately supervised under the actual ordinary desktop account, with startup isolated from the existing Codex desktop. Do not write CODEX_APP_SERVER_WS_URL/FORCE_CLI or replace Codex. Authenticate the Filo endpoint over the existing tailnet; preserve or securely transfer its token without logs/chat exposure. Verify native-host survival on gateway stop, reconnection, normal desktop health, and actual phone connection/new message/stream/stop where supported.
4. Persist exact revisions/artifacts and evidence. Use focused local checkpoint commits at coherent boundaries; no push/public release. A working standalone preview is not full desktop-session qualification.

Initial state: Filo main 6e2d30d; owned HARNESS.md/REDLINES.md and docs/ changes from interface research exist and are preserved. Agora isolated remote-control 6038eb8e, clean. Old Windows Filo service remains disabled/stopped. Earlier crash-probe/graceful-close failure evidence remains intact. Current task log remains the one authoritative Filo log.

## Owner-directed history and presentation update (2026-09-07)
Show original Quantum sessions and paginated history through read-only native APIs.
A session with readOnly=true must use history GET and never subscribe, send, stop,
or update models. Its composer is replaced by a read-only label. Filo-created
standalone sessions retain send/steer/stop. Remote removes Loading plain text,
uses the original covered-layout settling and streaming follow policies, supplies
the original lifecycle entrance target, and waits for the active native user
message before creating an assistant indicator. Read-only user bubble actions
reuse Copy, Select text and Info, with mutation menu entries hidden.

## Owner-directed history continuation and progress bars | 2026-09-07
The current instruction explicitly supersedes the read-only-only historical phase. An ordinary history with canResume=true shows Continue this conversation. Only explicit POST resume acceptance enables the existing composer and SSE; preserve messages and selected session identity. Do not auto-submit, fork, resume on browse, or navigate on a stale response. Native single-writer conflicts remain readable and display the occupied-session explanation. Ready denotes released historical ownership that must be reacquired before the next send, not active generation. Backend may use original Codex public resume in a short-lived process for dormant history; no core injection or competing writer is permitted. Generation still runs in the single native owner.
The owner clarified circular loading on 2026-09-07. Use the existing MotionAwareCircularProgressIndicator (20 dp, 2 dp stroke, centered like SettingsMemoryPage) for actual history/list loading, pagination and control requests in the existing Remote status position, including read-only history. No Loading plain text or fabricated percentage. Respect existing reduced-motion behavior. Success, failure, cancellation and navigation end their own progress state. Page transition keys use session identity, so capability updates do not reopen the page.

## Full-screen Remote loading overlay | 2026-09-08
Owner: loading bar去掉，模仿原始聊天页面做overlay全屏.
This supersedes the inline 20dp loading indicator amendment. Remote uses the original ChatApp full-content theme-background overlay, centered MotionAwareCircularProgressIndicator at48dp/5dp stroke and200ms opacity transitions. It covers session list/history/device save/restore/control loading, including the bottom composer, consumes underlying pointer input, and preserves Back cancellation/navigation. Generation alone never shows this overlay. Remove inline loading status rows, bars and plain Loading text. Existing Remote state epochs terminate loading on success/failure/cancellation; retain native message/composer/scroll ownership.


## Owner correction: page-specific loading and original ChatApp layering | 2026-09-08 | Codex
Owner: Remote has no loading cover; display saved devices immediately with existing status dots. Sessions has only a bottom loading bar, no overlay. Chat overlay must reproduce past ChatApp, not an invented Remote-wide cover.
This explicitly supersedes the preceding full-Remote overlay amendment. Correction scope: isolated Agora RemoteScreen/RemoteConversation; restore ChatApp exactly to pre-extraction source and remove the added ChatLoadingOverlay file. Copy the original loading block into the corresponding Scaffold content Box before the composer, with its existing48dp/5dp circle and200ms fades. Its lifetime is initial history opening/scroll settling only, terminated by errors; no generic control/save/generation overlay and no new pointer interceptor. Sessions uses shared motion-aware linear progress at bottom while actual list/pagination/new-session requests are pending. Device rows retain existing status dots.


## Direct historical selection and unavailable context | 2026-09-08 | Codex
Owner removes Continue this conversation because existing native owner attachment now works. Selecting an eligible history is the explicit admission action; attempt once for that selection after loading its readable history, then enable the existing stream/composer only after native acceptance. No list/background/reconnect/pagination admission and no message submission. A failed admission preserves history; no automatic POST retry. Native owner discovery remains first and cannot be bypassed on failure. Remove the redundant Continue control and eligible-history read-only label. Retry is explicit existing error action.
Owner: context N/A shows an empty circular bar without a dash. Reuse existing determinate context indicator at zero for unavailable telemetry, retaining unknown accessibility state and disabled interaction; do not invent usage.


## Composer model-menu parity correction | 2026-09-08 | Codex
Owner reports the model dropdown styling was invented. Read original Agora ChatBottomBar read-only: surfaceContainer/16dp shared menu, ordinary text rows without selected trailing check, provider/model ordering, selection haptic, toggle behavior,200ms dismiss gate and immediate reopen after selection. Isolated Remote retains the shared menu shell and copies these original row and interaction semantics for native Codex models. Remove added Check icon. No original Agora edit.


## Search-only conversation menu and real title context | 2026-09-08 | Codex
Owner removes conversation-menu Refresh and Load More, keeping only Search. Reuse ChatTopBar search capsule, ConversationInteractionState scan/navigation and MessageList highlights/positioning. Remote history pages load on reaching the top and while search is active; cancellation, original read epochs and error feedback remain authoritative. No alternative search UI or scrolling algorithm. Sessions-list pagination is separate from this conversation-menu request.
Owner requests context in upper-left title capsule when available. Bind actual native used/budget telemetry into original ChatTopBar subtitle. Add an optional availability flag defaulting to original totalTokens>0, so Remote can show a known zero while hiding missing telemetry; original callers retain behavior.


## UI completion: text boundary, New Chat, original keyboard, attachment menu | 2026-09-08 | Codex
Owner directs prioritizing UI. Native HTTP sample from dedicated qualification history shows six assistant commentary records each already end in one actual newline; sampled user/final records do not. No literal backslash-n suffix was found. Remote projection forwarded native terminal line breaks into segment rendering. Remove terminal CR/LF only from presentation text/answer/thought segments; preserve original Remote cache, interior newlines, indentation/spaces, literal escapes and tool payloads. Add regression coverage.
Owner requires New Chat instead of UUID: localize only absent/ID fallback titles, preserving native named titles and IDs. Reuse ChatLaunchInteractionEffects for new-session focus; acknowledge once after its original50ms focus step. Preserve existing TextField and ChatScrollCoordinator IME behavior. No recurring autofocus after reconnection/history opening.
Owner explicitly authorizes bottom plus attachment menu as UI-only NOP. Insert exact AttachmentAddMenu into existing ComposerControlGroup with original Camera/Photos/Videos/Files rows; callbacks do nothing. No capture, picker, upload or remote tool behavior.


## Original bottom-scroll and streaming parity | 2026-09-08 | Codex
Owner requires all UI to match ChatApp with no invented experience. Bottom-button review found a real argument-wiring defect: Remote passed native running into shareSelectionActive, hiding the original button throughout generation. Correct with named original arguments, actual switching/readiness, actual streaming-follow ownership and original IME competition; same ChatBottomScrollButton and scroll coordinator.
Original MessageList/AssistantMessageContent already owns document-level glyph fade, tail activity dot and GroupedSegmentAutoExpansionController. Timeline auto expansion already uses isStreaming && blockEnd == segments.size. Keep this unchanged. Map live native last segment to original THINKING/TOOL_CALLING/SENDING and provide the active snapshot to the existing streaming slot. Stop/completion removes live status. No simulated token timer or separate dot/gradient/card renderer.


## Sessions bottom progress animation | 2026-09-08 | Codex
Owner confirms a horizontal loading bar fixed at screen bottom, entering by growing from zero thickness or rising. Use existing AnimatedVisibility primitives:200ms bottom-anchored expand/fade and shrink/fade, reduced-motion fade only. Actual loading/loadingMore/new-session-control flags own visibility; saved devices and chat pages never inherit it. This completes the earlier static bottom-bar correction.


## Shared selected-model leading check | 2026-09-08 | Codex
Owner explicitly clarifies: check icon belongs to current model's LEFT in the dropdown, in both ordinary and Remote bottom bars. Add one shared ComposerModelMenuItem used by both existing selectors; keep shared shell/type/size and reserve the same leading slot on unselected rows. Model operations remain original/native respectively. This supersedes the previous no-check correction with an explicitly requested leading mark.
Full build f1094f9c hit source-size guard: existing source-contract test was999lines and the two-assert replacement addedone. Combine the two conditions into one assertion; retain both checks and limit, no guard exemption. No failed build deployed.


## Sessions progress: fixed thickness, 300ms opacity only | 2026-09-08 | Codex via Conch
Owner: progress bar should fade, without thickness changes,300ms. This supersedes the previous bottom expand/shrink animation. Scope: RemoteScreen.kt loading-bar enter/exit only and the owning contract. Keep existing4dp size, bottom position and loading flags. Use fadeIn(tween(300)) and fadeOut(tween(300)); remove the now-unused spatial-motion binding/imports. No new tests for this reversible presentation-only change. Verify diff, run one fresh build.ps1, then deploy.ps1 after the matching build succeeds and verify installed APK hash. Filo service and native Codex remain running.


## Composer details UI only | 2026-09-08 | Codex via Conch
Owner explicitly chooses UI-only Thinking and Service Tier. Reproduce original ChatBottomBar MoreVert button, menu shell/rows/switch geometry and original ThinkingControlPanel/OpenAiServiceTierControlPanel sheets. Menu navigation works; setting callbacks are NOP and do not persist or send requests. Original default presentation values are placeholders, not claimed remote telemetry. Preserve ordinary ChatApp behavior and the original Agora checkout.


## Lazy New Chat and independent model loading | 2026-09-08 | Codex via Conch
Owner requires New Chat to render and request the original keyboard immediately, with no request caused by entry. Existing RemoteViewModel owns a local draft and keeps its composer/navigation identity through native promotion. New Chat top-bar new-session plus is NOP while already a draft; existing attachment-menu callbacks remain NOP. Back before Send creates no remote session. First Send creates once, applies an explicitly selected draft model, then sends through the existing client-ID delivery path. Draft model selection is local. Duplicate clicks are fenced; late creation after navigation cannot navigate or send; failed/unknown POST retains draft without automatic retry. Native ID is used for all network calls after promotion.
Model catalog loading admitted on Sessions/ordinary history navigation is independent of history subscription and may finish while a local draft is open. Draft entry/refresh/visibility do not initiate requests. Use cached default or local selection; show existing Loading… text only while actual model/history data is pending, then unavailable if missing. Empty-draft readiness and focus do not wait for models or runtime. Fade300 sessions bar remains fixed4dp.
The existing Remote state retains a native-session to composer-owner mapping after promotion, so revisiting the created session preserves an edited draft and its submission receipt. Device removal/address replacement removes these mappings with the corresponding drafts. This is transient presentation identity only; the native session remains execution authority.


## Add Device guidance and empty-field examples | 2026-09-08 | Codex via Conch
Explain installing Filo on the target device and entering its IP address or URL; do not require or mention Tailscale in this helper. Both fields use the existing labeled input with optional gray placeholders: a sample HTTP IP/port and a visibly abbreviated hexadecimal token. Examples are presentation only, never initial values, credentials or submitted defaults. Synchronize all supported locales; existing MCP input defaults are unchanged.


## Send follows live generation and Stop settlement | 2026-09-08 | Codex via Conch
Owner requires Send to follow native generation like ordinary ChatApp. Existing SSE runtime is authoritative even when no user/assistant bubble has arrived; historic last-message status must not invent live generation. Reuse ComposerSendButton unchanged: active native generation plus empty draft shows Stop; entering text shows Send for existing steer; submitting or stopping shows the original circular Busy state and blocks duplicate actions. Stop is actionable only with a native active turn ID.
RemoteViewModel binds each pending Stop to its composer owner and exact native turn. Keep Busy until both the Stop HTTP request has settled successfully and native runtime ends/replaces that exact turn, in either arrival order. Failure or disconnection ends pending presentation without inventing idle or automatically retrying. Late responses after navigation cannot affect another owner. Normal completion received without a local Stop updates the button directly through runtime. New Chat remains entirely local until Send.


## Sessions automatic pagination | 2026-09-08 | Codex via Conch
Owner removes the Sessions Load More action. Observe the existing lazy list at its actual bottom after session content is laid out; use the existing cursor/paging owner to request once while active and not loading/in error. Preserve list rows and scroll position,300ms bottom loading-bar fades. Reject a non-advancing server cursor instead of entering an automatic request loop. A failed page keeps existing rows and exposes the existing explicit retry action; leaving cancels and fences late pages.

## Native Sessions indicators and viewed completions | 2026-09-08
Use the original drawer's18dp/2dp generating circle and8dp unread dot,200ms fades and generating priority. Only visible rows request bounded native status; background/navigation cancels and fences reads. Independent-host notLoaded is unknown, never idle. A real completed-turn identifier owns unread state: native unread or observed generation completion creates a dot, successful visible history read clears that completion, and encrypted connection storage persists viewed IDs. Opening a failed/loading history does not mark it viewed. Never write native Codex read state or transcripts. Sessions pagination runs at list bottom, removes Load More and rejects non-advancing cursors. Original bottom4dp bar remains opacity-only300ms.

## Native Thinking and Service Tier settings | 2026-09-08
Owner explicitly requests real settings, superseding the NOP-only details controls. Use the original shared panel/menu presentation and Filo's verified per-model reasoningEfforts/serviceTiers catalog. Preserve ordinary ChatApp defaults. Unsupported thinking-off and token-budget controls are disabled/absent according to native capability, not simulated. Existing-session controls update subsequent native turns and read authoritative values; active execution/Stop remain native. Native default tier may read back as default after null clearing. New Chat remains local with immediate keyboard; cached catalog/defaults and local choices apply once on first Send before message submission. A known created draft whose settings failed retries against that same native ID on explicit user Send; it never creates duplicates or sends with silently discarded choices. Ownership fences reject late responses after navigation/client replacement. No automatic POST retry; unknown message outcomes remain explicit. Physical UI acceptance remains owner-tested.
