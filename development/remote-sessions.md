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
