# Message Generation Architecture Contract

Status: authoritative development contract, 2026-08-13.

This document is required context for every Agora development task. It defines two global and
orthogonal message contracts. Features such as Compact consume these contracts; they must not
create parallel feature-specific definitions.

All software behavior must conform to these contracts, including normal execution, concurrency,
Room transactions, UI projection, errors, Stop/cancellation, recovery, automation, tools, queue
handoff, and legacy compatibility. Conflicting old code, tests, or documentation must be corrected;
they do not authorize a feature-local exception or parallel contract.

Implementation should reuse the existing ordinary pipeline, concepts, state owners, and objects to
the greatest practical extent. Robustness is the primary design objective: contract correctness,
identity fencing, failure atomicity, deterministic recovery, and cancellation safety outrank
cosmetic abstraction. Add an abstraction only for a cohesive invariant, a real side-effect
boundary, or multiple genuine consumers.

## 1. Terms and strict separation

A **generation boundary** identifies the visible messages produced by one generation. It is used
only to locate Regenerate scope and to decide ownership of per-generation status and bottom action
controls.

A **context boundary** identifies the oldest message included in one Provider request. It is used
only by ordinary context/API-path assembly.

The generation-boundary resolver must never select, truncate, reorder, or assemble Provider
context. The context assembler must never infer UI action ownership or Regenerate range.

## 2. Global generation-boundary contract

1. Every real USER message starts a generation boundary, regardless of neighboring message types
   or legacy Run identity.
2. All messages produced inside one generation share one nonblank `runId`. A Run is one indivisible
   generation group; no boundary may be created inside it.
3. A change to a different nonblank `runId` starts a new generation group. Protocol rows,
   Compact rows, and ordinary assistant rows all participate in this Run grouping.
4. Every actual send/generation admission creates a fresh Run ID. It must never reactivate an old
   Run. This includes the Send button, one claimed FIFO queue drain, automatic/manual Compact,
   Recompact, and Regenerate. Provider passes and tool rounds that continue the same admitted
   generation remain inside that generation's Run.
5. Blank legacy Run IDs do not authorize destructive guessing. A real USER remains a hard boundary;
   new writes must always use a nonblank fresh Run ID.

`MessageGenerationBoundaryResolver` is the shared owner of this definition. Compact, Delete,
Recompact, Regenerate, rendering, and status presentation may consume it but may not add local
boundary exceptions.

## 3. UI and Regenerate consequences

- Every real USER message always owns its bottom action controls.
- One Run group owns one generation status presentation.
- The last ordinary assistant output in a Run group owns that group's assistant action controls.
- Adjacent assistants from different Runs remain independently actionable even when an intervening
  Compact is deleted.
- Ordinary Regenerate targets only the selected generation group, creates a fresh Run, and does not
  absorb an adjacent Run.
- Same-position replacement is an output-target option, not a new generation contract. Recompact
  creates a fresh Run and replaces only the selected Compact row at its existing message ID and
  parent. It must not create a branch, clear suffix messages, rewrite descendants, or mutate any
  other message.
- Deleting a Compact deletes only that row and reparents only its direct message children to the
  deleted row's former parent. It must not merge surviving generation groups.

### 3.1 Conversation recency

`Conversation.lastUpdated` records the time of the most recent durable conversation-tree mutation
caused directly by a manual user operation. Both conditions are required: the operation must be
manual, and it must actually modify the durable conversation tree. The stored value is the mutation
time, not an earlier intent, queue, or UI-event time.

Manual Send, Edit, Regenerate, message deletion, Compact, Recompact, and Compact deletion update
conversation recency when their durable tree mutation commits. A manually queued Send does not
update recency when it enters the queue; it updates recency only when the queued input is actually
sent and committed to the tree, using that commit-time timestamp.

Branch selection does not update recency because it changes only the selected view through an
existing tree. Conversation-title edits, automatic title generation, and manual title generation do
not update recency because they do not modify the conversation tree. No automatic Compact lifecycle
step updates recency, including Compact creation, generation, settlement, handoff, or continuation.
Task and Loop tree writes never update recency, including Task `Run Now`, because the later durable
writes are automation-owned rather than direct manual tree edits.

## 4. Global context-boundary contract

For every ordinary Provider request:

1. Start at the request's latest selected parent message.
2. Walk upward through the durable `parentId` chain.
3. The nearest Compact on that chain whose generation ended normally and without error
   (`MessageStatus.SUCCESS`) is the context boundary.
4. That successful Compact is the topmost context message. Older ancestors are excluded.
5. Failed, stopped, in-flight, missing, or off-branch Compact rows are not context boundaries.
6. If no successful Compact exists on the selected ancestor chain, context continues to the oldest
   reachable ancestor.
7. Provider projection converts the successful Compact capsule into a transient USER summary whose
   text is exactly `<context_summary>\nSUMMARY\n</context_summary>`. The marker exists only in the
   prepared API request; Room, UI projection, context usage, and retained-message projection keep
   the durable raw summary. If no real USER message follows that Compact and no existing API-only
   initial USER prompt terminates the request, shared request preparation appends `Please continue.`
   as API-only USER input. Consecutive USER input is canonicalized with one blank line while
   preserving the boundary position and following message order.
   A Compact generation may echo the request-only wrapper. Every streaming UI snapshot, Room
   checkpoint, and terminal message must normalize complete or partial wrapper markers before
   publication or persistence, including both `ChatMessage.text` and answer segment content.

`GenerationApiPathBuilder` and the ordinary Provider message projection own this contract.
Manual/automatic Compact and Recompact use this same ordinary path from their requested graph
position. `MessageGenerationBoundaryResolver` has no role here.

## 5. Shared generation lifecycle

Compact is an ordinary generation with only the declared minimal differences: message identity and
UI, haptic exclusion, selected model and generation parameters, tools disabled, Compact system
prompt, and one frozen API-only Compact invocation. It reuses ordinary admission, fresh Run
creation, context/API-path assembly, Provider execution, streaming/checkpoints, Stop/cancellation,
terminal settlement, recovery, and queue release.

The Compact invocation is appended by the shared pre-Provider request projection as the final USER
message. It is request-only configuration: it participates in exact token accounting but is never
written to Room, rendered as a visible message, assigned a Run boundary, or used to alter durable
parentage. The configured Compact summary instructions remain the complete system prompt; the final
USER turn only invokes that behavior. A saved custom Compact prompt replaces the built-in system
prompt in full, with no hidden prefix, suffix, or mandatory guardrail added by Agora. The built-in
default therefore owns its provenance, task-state, prior-summary reconciliation, language, fidelity,
and anti-recursion rules, while a custom prompt intentionally assumes responsibility for all of
those semantics. The API-only invocation is tagged as application-generated control input and must
not be represented by the built-in default as human intent, pending work, or the next action. This
guarantees a valid terminal input role even when the durable Compact parent is an Assistant message.

After a durable tool result and a successful Compact, continuation priority is:

`Compact SUCCESS -> FIFO queued user message -> loop`

The queue claim/check and loop admission must remain linearized so guidance is neither duplicated
nor lost. A non-successful or anomalous Compact is a hard automatic-handoff boundary: it starts
neither queued generation nor loop generation.

Foreground-service ownership is best-effort process-priority assistance for in-process generation,
not a Run or Provider admission prerequisite. `GenerationManager` attempts to acquire Agora's
foreground-service lease when execution is not externally managed. An unavailable or rejected
start records that no lease was acquired and generation continues through the same canonical path;
it must not create a terminal error, retry, delay, alternate execution path, or shadow lifecycle.
Completion releases the lease only when acquisition actually succeeded. Task and Loop Workers keep
using their externally managed WorkManager foreground execution. Process death does not recreate a
coroutine or Provider stream. During interactive App startup, orphaned durable Runs remain dormant:
Agora must not enumerate conversations or Runs, instantiate per-conversation runtime state, or run
recovery validation. The ordinary orphaned-Run recovery contract begins only after the user explicitly
opens that exact conversation and may inspect and recover only that owner.

The optional Automation Wake Lock is a default-off execution-side lease, not a Run, admission,
queue, Worker, foreground-service, or recovery owner. Both Task and Loop entry paths acquire it only
around the shared `TaskExecutionEngine` execution boundary after serialized admission and release it
with structured `finally` on every success, busy/early result, failure, and cancellation. Acquisition
failure records diagnostics but does not reject, retry, or fork execution. WorkManager retains its
independent scheduled-work wake ownership; the app setting must not create a second lifecycle or keep
the device awake between executions.

Foreground Chat terminal attention is conversation-aware. A conversation is visible only while the
app is foreground, Chat owns top-level presentation, and that exact conversation is selected;
Settings, Tasks, media/PDF preview, and text preview therefore hide it, while the drawer does not.
After durable settlement, ordinary SUCCESS or ERROR marks an invisible source conversation unread
and posts its stable per-conversation notification. A visible source conversation keeps neither.
Queued/continuation SUCCESS remains an interim boundary and does not notify. Compact SUCCESS may
mark unread but never notifies; Compact ERROR marks unread and notifies; STOPPED stays silent. Error
notifications use the complete formatted generation error rather than replacing available detail
with a generic failure body. The durable read boundary runs only for a foreground, presented Chat
conversation; after its unread write succeeds, it cancels that conversation's stable notification
without affecting any other conversation. Headless Task/Loop execution retains its independent
foreground/background notification policy.

The continuous answering haptic texture has one side-effect owner: `AnsweringHapticEffect`. It may
run only while haptics are enabled, the app is foreground, Chat owns top-level presentation, and the
selected runtime snapshot exposes an ordinary active MODEL message in `SENDING` state with an active
Answer segment. `ConversationSelectionController` is the single binding owner for selected
conversation identity and that conversation's canonical runtime snapshot: every ID publication first
reads the target registry snapshot synchronously, publishes that bound snapshot, invalidates the old
collector, and accepts later emissions only for the still-current ID and binding generation. New Chat
publishes the neutral snapshot when it clears the selected ID. The Room-load-delayed render mirror and
an independently sampled ID/snapshot pair must never drive answering haptics. Compact, a terminal or
non-Answer message, Settings, Tasks, media/PDF preview, text preview, app background, or disabling
haptics makes the texture ineligible and must stop it immediately, including when the effect leaves
composition. The drawer remains part of Chat presentation and does not suppress the texture.
Citation metadata never ends an Answer phase; an actual tool or thought segment still does, even
when its renderer hides it. Discrete confirmation, interruption and deletion feedback may pause the
actuator but do not revoke the current Chat effect's texture request. Its existing pending resume
must be cancelled when that effect stops, so a stale callback cannot revive an inactive texture.
Generation code and overlay code must not start or stop the waveform independently or introduce a
second haptic owner, shadow state, delay, compensation, or fallback path.

## 6. Review blockers

A change is invalid if it:

- uses generation boundaries to assemble Provider context;
- uses context boundaries to merge UI generations or choose Regenerate scope;
- reuses or reactivates a terminal Run for a new send/generation;
- adds a Compact-specific boundary or generation lifecycle;
- adds a Provider descriptor, capabilities/policy object, adapter layer, wrapper, interface, or
  factory without a demonstrated cohesive invariant, real side-effect boundary, or multiple
  genuine stable consumers and without removing an existing responsibility or duplication;
- mutates suffix/neighbor messages during same-position replacement;
- merges actions or status across different Run IDs;
- treats a non-successful Compact as a context boundary;
- omits, summarizes, redacts, caps, truncates, drops, or replaces any information from the complete
  formatted generation error in a later Provider request whose selected context includes the failed
  MODEL row.

Focused tests must cover each rule, including legacy blank IDs, protocol rows, failed/stopped
Compact rows, nearest-successful-ancestor selection, fresh-Run Recompact, suffix isolation, and
concurrent queue-versus-loop admission.

## 7. Module boundaries and responsibilities

| Module | Owns | Must not own |
|---|---|---|
| `ChatMessage`, `RunEntity`, status/identity models | Durable vocabulary and small identity predicates. | UI grouping, context walks, orchestration, or side effects. |
| `MessageGenerationBoundaryResolver` | Pure visible-range grouping for one generation by real USER and Run identity. | Context assembly, graph writes, branch mutation, Provider payloads, or Compact-specific rules. |
| `RunUiProjection` | Pure action/status/branch-control presentation derived from generation boundaries. | Generation admission, context selection, or database mutation. |
| `ConversationRuntimeReducer` + `ConversationGenerationState` + mailbox | The single in-process authority for one conversation's slot, accepted effects, Stop barriers, and stale-result rejection. | Provider protocol parsing, Room transaction bodies, or feature-specific context logic. |
| `ConversationExecutionCoordinator` | Per-conversation execution serialization around admitted work. | A second Run state, queue ownership, or result acceptance. |
| `GenerationRequestBuilder` | One immutable admission snapshot: selected model, Provider registry, generation parameters, tools/memory/attachment policy. | Provider execution or live settings reads after admission. |
| `GenerationApiPathBuilder` | Read-only durable parent-chain walk, nearest-successful-Compact context boundary, Provider message projection input, and fixed Provider config. | UI generation boundaries, Run creation, queue policy, or writes. |
| `StandardGenerationContinuationLauncher` | The ordinary fresh-Run graph transaction for a continuation and the optional same-row output target. | A Compact lifecycle, Provider implementation, context reconstruction, or old-Run restart. |
| `BoundRunGenerationLauncher` | Binding one already-created fresh Run to the shared generation tail and identified callbacks. | Run graph creation, boundary inference, or terminal writes outside shared settlement. |
| `GenerationManager` | Mailbox-authorized Provider/tool execution, streaming overlay/checkpoints, generic bounded final text projection, and terminal-effect request. | Branch selection, UI grouping, Compact policy, queue reordering, or independent lifecycle state. |
| `GenerationTerminalSettlementController` and finalization executors | Identified terminal Room effects and two-barrier settlement integration. | Admission, context assembly, or action presentation. |
| `ConversationRegenerationService` | Resolve one global generation boundary, revalidate it under the normal lock, and request a fresh ordinary generation branch. | Custom grouping, suffix-wide deletion, or context construction. |
| `ConversationCompactController` | Adapt Compact model/config/system prompt/tool disablement, message identity/UI target, retained-summary text projection, and call the ordinary launcher. | A Provider runner, state machine, old-Run restart, custom context path, or custom queue lifecycle. |
| `ContextCompactor` | Threshold/retention calculations and pure Compact text formatting helpers only. | Provider transport, streaming, settlement, Run ownership, or graph mutation. |
| `ChatContextCompactDao` through the sole `ChatDao` | Atomic same-row fresh-Run substitution and target-only Compact deletion/necessary rewiring. | Context semantics, UI boundaries, Provider decisions, or broad graph reconstruction. |
| queue guidance state + `queueMutationMutex` | FIFO ownership, claim revision, exact front requeue, and linearized queue-versus-loop admission. | Holding locks during Provider/network work or attaching guidance to an old Run. |

Room remains the durable source of truth. The streaming overlay is a temporary projection of one
durable message identity and cannot become a parallel message graph.

## 8. Binding module behavior

### 8.1 Ordinary Send and queue drain

- Composer pre-acceptance work freezes an immutable draft-owner, destination, tap-ordered
  model/settings snapshot, accepted text, and ordered `READY` attachment membership before it invokes
  ordinary admission. New Chat admission inserts a workspace-store read barrier, so the snapshot
  includes all writes ordered before the tap and excludes later writes. Conditional singleton
  consumption combines those tap-time fields with the attachment states settled before acceptance;
  a surviving newer workspace row loses only the accepted draft fields. Conversation selection may
  change while that work waits, but accepted input uses only the frozen identity and never re-reads
  the visible conversation or current model to choose its destination.
- The TextField remains enabled throughout pre-acceptance and generation. Authoritative settlement is
  revision-aware: it clears only the accepted text and attachment membership, preserving any text
  typed after the tap even when that visible edit reaches the controller after freeze begins.
- One accepted Send creates one fresh Run, durable USER input, and MODEL placeholder atomically. A
  durable acceptance whose draft clear fails enters a non-resendable clear-only recovery state.
- Direct and queued Send acceptance preserve focus, IME visibility, and expanded Composer state.
  Accepted draft clearing owns no presentation dismissal. Explicit navigation, user gestures, and
  the drawer threshold retain the dismissal ownership defined in application-ui.md.
- One claimed FIFO drain enters the same Send transaction and creates a fresh Run.
- Input queued while another generation owns the slot stays memory-owned until a legal boundary.
- Claim failure returns the exact batch to the front; durable success transfers ownership exactly
  once. No item may be lost, duplicated, reordered, or attached to the terminal origin Run.
- Normal completion and Stop settlement emit one shared process queue-drain signal. UI owner
  detachment/rebinding may hand that signal off, but it cannot replace it with a Stop-only callback
  or discard a pending FIFO batch.

### 8.2 Regenerate

- Locate scope only through `MessageGenerationBoundaryResolver`.
- Revalidate the visible boundary against the durable graph while serialized.
- The selected boundary's terminal ordinary assistant is the target; an adjacent different Run is
  outside scope.
- Ordinary Regenerate creates a fresh Run and branch using the boundary's real USER source, or the
  first assistant's parent when no real USER is present.
- Its visual transition scrolls to that direct generation parent. An ordinary assistant target still
  resolves to its parent, while an explicit Compact target remains anchored on the Compact itself.
  This presentation rule does not change generation boundaries, Provider context, Run parentage, or
  branch selection.
- It must not use the generation boundary to assemble Provider context. The ordinary API-path
  builder does that from the new request parent.

### 8.3 Recompact

- Recompact is ordinary same-position regeneration with a Compact output target.
- It creates a fresh Run and never reactivates the target's terminal Run.
- The target message ID and parent stay fixed. Only the target row's generation-owned fields,
  including its fresh Run identity, may change.
- No new message branch or selected-message edge is created. Every suffix/descendant message row,
  order, parent, content, status, model, and Run identity remains unchanged.
- The old independently owned Compact Run is substituted atomically in the Run graph; failure rolls
  the whole replacement back.

### 8.4 Compact deletion

- Delete exactly the selected Compact row.
- Reparent only direct message children to the deleted row's former parent.
- Preserve every other message field and never delete a suffix subtree.
- Repair only selections and dedicated Run ancestry necessary to keep surviving graphs valid.
- Surviving messages are still grouped by the global Run contract; deletion cannot merge Runs.

For ordinary structural message-branch deletion, selected-message and selected-Run repair use the
same sibling order. If the selected branch is deleted, choose the immediate surviving later sibling
first, fall back to the immediate surviving earlier sibling only when no later sibling exists, and
remove the selection when neither exists. Message and Run selections must not diverge. This ordering
rule does not broaden the deleted subtree, change synthetic-row filtering, or move transaction/file
cleanup ownership.

### 8.5 Tool-result continuation priority

After a durable tool result:

1. evaluate and run automatic Compact;
2. require that exact Compact message to settle durably with `MessageStatus.SUCCESS`;
3. drain pending or already-claimed FIFO user guidance;
4. admit the no-input loop only if neither exists.

The final queue check and loop admission are one linearized decision. Compact settlement cannot
clear the queue before this decision. ERROR, STOPPED, cancellation, setup/launch failure, missing
message/status, stale identity, or any other Compact anomaly stops this automatic chain. Pending or
claimed guidance remains owned and ordered but is not automatically invoked or cleared after that
failure; only a later explicit user action may resume ordinary queue admission.

### 8.6 Compact UI

Compact may own a capsule renderer, message label/menu, haptic exclusion, and stable presentation.
Every durable Compact is an independent message and owns exactly one standalone LazyColumn item.
The canonical message-list grouping must end and emit any active ordinary USER/assistant turn before
the Compact, emit a singleton turn keyed by the Compact message ID, and leave no active turn that
could absorb a following message. Participant compatibility values, Run association,
SENDING/THINKING/TOOL_CALLING/terminal/error status, and blank summary text never permit a Compact
to merge with a preceding or following turn. This UI item boundary does not change Room identity,
generation boundaries, Provider context, or Compact rendering semantics.
Its outer minimum height/padding and 32 dp icon/action slots remain stable across
SENDING/THINKING/terminal/error transitions. The palette, leading icon, and label are keyed to one
`ContextCompactPillPresentation` transition; palette values interpolate and the leading icon
crossfades inside its fixed slot. The label uses that same transition's `AnimatedContent`: its content
fades while one `SizeTransform` animates directly between the outgoing and incoming label sizes.
No outer `animateContentSize` may wrap retained Crossfade children, and ordinary recomposition,
message-content changes, menu changes, or parent layout changes must not become a second size owner.
Reduced Motion snaps the label size while retaining the allowed opacity transition. The capsule Row
uses a 7 dp horizontal inset and 7 dp spacing between each slot so its 32 dp leading icon slot and 32
dp overflow-action touch target are visually balanced around the text. Both slots render an 18 dp
glyph; minimum height, menu behavior, and action enablement remain unchanged. UI specialization
cannot redefine generation or context contracts.

The final real-USER or standalone Compact turn is the semantic tail anchor. The physical final rendered
turn holds the remaining tail-region minimum height; these are separate responsibilities. Before
assigning that minimum, subtract the measured content height from the semantic anchor itself through
every turn before the physical holder, and clamp the remainder at zero. The holder then contributes
the larger of that remainder or its own content height, so the complete anchored region is exactly
the larger of the base viewport minimum or its actual content. Therefore
`[USER, ASSISTANT, COMPACT, ASSISTANT]` remains positioned from Compact while Compact and every later
standalone turn stay adjacent and all unused capacity appears only after the complete tail. This rule
does not merge turns or change message identity, ordering, grouping, scrolling, or search.

When the Compact detail Bottom Sheet is open and the ordinary durable message is
SENDING/answering with no real Markdown output, it shows the localized equivalent of
`Context compacting...` in the Material primary color. The placeholder enters and leaves with fade animations. Its shared
empty-stream rendering receives an 8 dp internal top inset so the status line does not crowd the
Bottom Sheet divider. As soon as real output exists, the placeholder fades out and the body renders
normally.

A terminal Compact error remains visible in both locations:

- the detail Bottom Sheet places the shared neutral-gray generation error bar beside the Markdown
  body;
- the capsule uses a theme-derived neutral-gray palette independently of the neutral terminal bar,
  without changing its bounds, and shows an error icon plus the localized equivalent of
  `Compact error`. Its container uses alpha-adjusted `surfaceVariant`, its icon uses
  `onSurfaceVariant`, and its text uses alpha-adjusted `onSurfaceVariant`; a semantic error color or
  hard-coded gray is forbidden.

A stopped Compact is a non-error terminal presentation. Its capsule keeps the same stable bounds,
shows a stopped icon plus the localized equivalent of `Compact stopped`, and emits no Snackbar. A
failed Compact may emit only the persisted ordinary generation error segment; generated answer/summary text is never
an error channel. Missing error detail uses a localized short fallback.

All app-owned Compact settings, delete/recompact actions, boundary messages, streaming/status chrome,
and known preflight/launch failure reasons must resolve through Android resources in the current
locale. Domain owners carry a semantic `CompactFailureReason`, an optional nonblank external detail,
and the affected message identity; they do not manufacture user-facing English. One narrow
presentation resolver is shared by the manual and automatic UI consumers. Nonblank Provider or
persisted error detail remains verbatim diagnostic content and is never translated. Internal
invariant/debug exceptions are not user-visible resources.

Both terminal presentations derive from the ordinary durable message status/error fields. They do
not own a Compact state machine or infer failure from missing text.

### 8.7 Shared streaming Markdown UI

The complete binding requirements are maintained in [Shared Streaming Markdown UI Contract](streaming-markdown.md).
Read that contract for every change in this scope.

### 8.8 Empty output and automatic handoff

Provider completion with no answer, thought, follow-up, guidance, or other successful output is an
ordinary generation error. Terminal persistence must include a nonblank error value so every
consumer can render the shared error bar.

For every applicable remote Provider, a zero-output parse/protocol failure presented through the
localized `The server response could not be read.` error and a response-body read exception whose
cause chain contains that exact phrase both enter the Provider's existing retry sequence. Eligibility
is evaluated only from the current Provider pass: it must have produced no nonblank Answer, Thought,
Tool, hosted-tool, or other Provider output. An active `SENDING` state with no output in that pass and
an established but empty Answer both remain zero-output. A Provider pass started after a completed
Tool call is evaluated independently: output from the preceding pass does not disqualify the new
`SENDING` pass. Once the current pass produces any Provider output, either failure is terminal and
must not replay it. This rule reuses the existing initial request plus at most five retries, delays,
`Retrying` presentation, cancellation, and exhaustion behavior; it does not classify unrelated
`IOException`, DNS, timeout, or other transport detail as retryable.

Every Compact Run success-gates all automatic handoff, not only the no-input loop and regardless of
whether the caller is foreground UI, Task, or Loop. Its ordinary launcher installs the queue-release
suppression before the generation Job can start. Durable
SUCCESS removes exactly that Run's suppression before settlement, allowing the ordinary queue
release and then the existing queue-before-loop decision. ERROR, STOPPED, cancellation, missing or
still-active status, setup failure, launch failure, stale identity, and exceptions leave the
suppression in place; settlement consumes it without starting another Provider request.

Consecutive origin-Run and Compact-Run suppressions are counted. A single boolean is invalid because
the origin release and very fast Compact completion can settle in either order and would otherwise
consume each other's decision. Failure never clears, drops, duplicates, or reorders queued user
input; it leaves that input pending for a later explicit user action.

### 8.9 Provider-hosted output and OpenAI-compatible controls

An official OpenAI Provider or a custom Provider selected as OpenAI-compatible, together with
Responses API enabled, is sufficient to expose both `OpenAI Search` and `Service Tier` in the
conversation UI. No model-name allowlist, capability-discovery request, local capability registry,
or extra relay declaration may suppress those controls. This is a positive availability rule; it
does not redefine any separately supported Service Tier surface outside Responses.

The immutable generation snapshot freezes both choices. When OpenAI Search is enabled, the existing
OpenAI-compatible Responses request includes the native `web_search` tool. When Service Tier is
enabled, that same request includes the normalized selected `service_tier` value. Recognized values
are `auto`, `default`, `flex`, `scale`, `priority`, `fast`, and `ultrafast`; normalization must
preserve each spelling rather than collapse a recognized tier to `auto`. Chat Completions omits this
Responses-only field. The ordinary Provider owns request serialization; UI visibility must not
create a second request path.

Every OpenAI-compatible Chat request forwards a captured non-null `temperature`, `max_tokens`,
`top_p`, `frequency_penalty`, and `presence_penalty` without model-family remapping. Thinking is a
separate protocol-local control. Alibaba Qwen hybrid families serialize top-level
`enable_thinking`, and serialize `thinking_budget` only while thinking plus the budget control are
enabled. Qwen 3.8 Max/Flash serialize their documented `reasoning_effort` values, or
`thinking_budget`, never both. Documented Qwen thinking-only models reject a disabled-thinking
request locally before HTTP. Groq maps only its documented Qwen 3.6, Qwen 3.8, and GPT-OSS model IDs
to their respective effort value sets; GPT-OSS rejects off/`none` locally. Custom OpenAI Chat
relays map raw Qwen 3.8 model names to `none`/`low`/`medium`/`xhigh`; unrelated model names remain
untouched. OpenAI Responses forwards temperature, max output tokens, and top-p but has no native
frequency/presence fields in this request contract, so those penalties are protocol N/A rather than
silently approximated.

Anthropic keeps temperature/top-p only on its legacy request families and has no native
frequency/presence penalty fields. With legacy manual thinking enabled, temperature is omitted and
top-p is forwarded only in the protocol-compatible 0.95–1 range; with thinking off, both captured
values are forwarded. Manual-thinking families use `thinking.type=enabled` plus the captured budget;
adaptive families use `thinking.type=adaptive` plus `output_config.effort`.
Sonnet 5 and Opus 5 serialize `thinking.type=disabled` when thinking is off, without `display` or
`budget_tokens`, and retain their effort control. Fable 5, Mythos 5, and Mythos Preview reject off
locally before HTTP. Opus 5 does the same when off is combined with `xhigh` or `max`; high and lower
efforts remain valid. Legacy non-thinking/default-off families continue omitting `thinking` rather
than receiving a current-only disabled shape.

Ollama requests always carry the native top-level `think` control. Ordinary and unknown model names
use a Boolean matching the captured thinking toggle. Native `gpt-oss` model names use the required
`low`/`medium`/`high` effort strings; off or `none` is impossible and fails locally before HTTP.
Ollama continues forwarding temperature, top-p, and max tokens as `options.temperature`,
`options.top_p`, and `options.num_predict`. Frequency/presence penalties are protocol N/A and are
not approximated with Ollama's different repeat-penalty semantics.

Embedded Local requests forward temperature, top-p, max tokens, frequency penalty, and presence
penalty through `LlamaChatEngine` into both text and multimodal llama.cpp generation. Missing
penalties use neutral zero; native sampling applies the configured frequency/presence values with a
neutral repeat penalty rather than dropping or approximating either control.

OpenAI Responses reasoning summaries are public summary content, not raw chain-of-thought. When
thinking is enabled on an official or custom OpenAI-compatible Responses transport, the request opts
into the most detailed available summary with `reasoning.summary = auto`. Summary text deltas enter
the ordinary `ThoughtChunk` path and therefore form normal durable thinking segments and thinking
blocks. Deltas with the same `output_index` and `summary_index` remain contiguous; a change in either
index inserts exactly one blank line between summary parts. Bold text or a Markdown heading in the
current summary part supplies the thinking-card title with its marker removed, matching Gemini.
Disabling thinking suppresses both the summary request and its presentation.

Provider-hosted tools use non-executing hosted-tool stream events. They may create and settle durable
ordinary tool segments, but they cannot authorize local execution, enter the tool-effect reducer, or
fabricate a tool-result continuation round. Whether a durable hosted segment is presented is an
independent UI policy. Provider semantic termination still owns whether the request succeeded; Stop
and errors use the shared generation settlement.

Tool visibility begins at the canonical creation event, not at execution start. A Provider emits a
`ToolCallUpdate` as soon as a structured Tool block is observable. `GenerationManager` immediately
publishes every newly created segment. For a `ToolCallsRequest`, it upserts every call first and then
publishes one snapshot containing the complete batch before any Tool execution begins. A terminal-only
text-recovery batch follows the same publication rule even though no earlier structured block existed.
The batch executor's later per-call running updates do not own creation visibility. UI placeholders,
delays, retries, or shadow Tool state must not substitute for this overlay publication boundary.

`generate_image` alone uses a 600,000 ms outer execution budget and the same 600,000 ms budget for
its generation request and returned-image download. Every other Tool keeps its ordinary configured
execution timeout. A successful image is persisted through `ToolImageStore` and returned in the
owning `ToolExecutionResult.images`; the overlay copies it to that exact Tool call and
`MessageSegment.toolImages`. No provider-local pending queue, conversation drain, or new
message-level generated-image tail may own current output. Existing `ChatMessage.images` rows remain
read-compatible and retain their legacy full-width renderer, but new generated images never enter
that path.

The visible `generate_image` segment is a hard ordered presentation boundary in Compact, Grouped
Timeline, ordinary Timeline, and the Compact/Grouped Bottom Sheet presentation mode. Its current
information card ends immediately after that call. The fixed left-aligned `300 x 300 dp` slot is
rendered next, and every later image call, Tool, Thought, Transcription, or Answer starts after that
slot. Group and slot identity depend only on the append-only segment/detail position, never on the
pending, failed, or successful payload, so lifecycle updates cannot replace, regroup, resize, or
otherwise rewrite the preceding prefix. Multiple image calls establish multiple boundaries in their
original segment order. In Grouped/Compact and ordinary Timeline, the information card immediately
before the generated-image slot has exactly `8 dp` of bottom separation. This matches the existing
`8 dp` top separation owned by a later independent Timeline information card. Expanded grouped-card
spacing, the ordinary `2 dp` within-group separation, answer spacing, the Thinking Bottom Sheet, and
the fixed image-slot geometry remain unchanged.

On the boundary's first visible frame, the owning card is presented collapsed and one collapsed
value is committed through the existing expansion map. Compact and Bottom Sheet preserve the first
card's existing expansion and appearance identity during this transition. The image-boundary claim
is session-scoped and one-shot: terminal completion does not collapse again, and a later manual
expansion remains effective. The slot owns one draw-only 0.90-to-1.0 scale plus opacity entrance
through the existing segment appearance registry. Its allocated geometry remains fixed while the
contents Crossfade. Pending content is a light-neutral dot matrix over a light-neutral background.
The dot field stays at least `16 dp` inside every slot edge; dot radius uses a fixed physical
falloff from the invisible anchor: distance `0..150 dp` maps linearly to factor `1..0`, clamps beyond
that range, then squares the factor before interpolating the existing minimum and maximum radii. The
anchor's random targets and complete smooth travel stay at least `32 dp` inside every edge. Each
target traversal lasts `1,300 ms`, twice the prior motion speed. Normal motion continues
target-to-target while Pending. The minimum dot radius remains `0.7 dp` and the maximum is `3.9 dp`;
the existing center bounds account for that maximum so complete dot edges still retain the full
`16 dp` inset. Reduced Motion freezes the anchor at the center
without removing the opacity transition. Pending, decoded, and failed content are the only semantic
states of one full-`300 x 300 dp` 200 ms Crossfade owner. A terminal failure fills that complete
slot and shows a centered Material `BrokenImage`; it never first paints a corner-sized failure icon.
For a completed attachment, the Coil request receives the explicit pixel size derived from the
`300 dp` slot so decoding never waits for the painter's first draw; the Pending matrix remains until
decode succeeds, then Crossfades to a `ContentScale.Crop` image that reuses the ordinary media-open
callback. Tool-detail image previews follow the same full-viewport loading/success/failure Crossfade
and use the shared 4 dp indeterminate loading stroke.
Remote `view_image` previews retain this exact presentation but admit their image request only when
the Tool preview is expanded. The square viewport exists before the request starts and remains fixed
through download and decode; the decoded image fades in without changing surrounding geometry.

In the Tool detail Bottom Sheet, a `generate_image` result keeps the same `24 dp` outer horizontal
content padding as ordinary Tool text. Its rounded preview is centered and fills that padded content
width as a `width x width` square. `ContentScale.Crop` center-crops only the source bitmap inside the
square; it does not remove container margins or cap the square by sheet height. Arguments, labels,
result text, and the image therefore share the same outer edges. Non-generation Tool images retain
their existing aspect-ratio sizing and `ContentScale.Fit` behavior.

Local Sandbox and Conch share one shell-tool baseline: foreground command/workdir validation,
bounded retained output and cancellation propagation; bounded typed `file_read`; 1MB UTF-8
`file_write`; backend-native `file_edit`; and home-default glob/grep with the documented caps,
truncation metadata, regex failures, and line-content bounds. Conch durable background jobs,
foreground continuation after the client wait budget, and `view_image` are explicit extensions rather
than baseline behavior that Local must imitate. SSH may implement the transport-neutral interfaces,
but it is not the authority for the Local/Conch baseline.

Local Sandbox package installation and upgrade share one dependency download closure. APKINDEX
records are read completely through their blank-line boundary or EOF. Virtual dependency providers
prefer an already-installed candidate present in the index, otherwise the highest repository
provider priority, with stable name ordering for ties. Index order must not pull a competing shell
provider into a transaction. Existing newer packages are never downloaded for downgrade; Alpine's
`apk` remains responsible for validating and applying the complete transaction.

Structured Provider citations follow [citations.md](citations.md). Protocol routers emit structured
citation events rather than answer `TextChunk` or tool events. The existing streaming segment
overlay and bounded checkpoint/terminal persistence retain accepted citation segments for the
identified Run, while Provider history and token/context projection exclude them. Citations do not
create a second generation lifecycle, change semantic termination, or append synthetic source text
to the durable answer. Presentation recognizes a plain proxy artifact formed by `cite` plus one or
more `turn<digits><kind><digits>` Provider source IDs. Complete IDs already present in citation
metadata become the existing adjacent native inline tokens and grouped capsule; a possible trailing
partial artifact is withheld while streaming, and unmatched or malformed artifacts are stripped at
terminal display and copy/export cleanup. Stored answer text, citation identity, numbering, and URL
safety remain unchanged.

A message card with visible tool segments but no real `thought` segment displays only
`Called x tools`. Terminal failed or stopped visible tool segments contribute to that count even
without a result payload; an active group continues to display the current tool name. Message-level
thought duration is a fallback only when at least one thought segment
exists; it must not turn a tool-only card into `Thought for xs, called x tools`.
Gemini keeps its hosted output protocol-local. Candidate `groundingMetadata` becomes a completed,
durable `google_search` hosted block with normalized `results` and full grounding metadata. The shared
UI segment-preparation boundary excludes that exact tool name from compact, grouped timeline,
ordinary timeline, and thinking-detail presentation, so it produces neither a `Google Search` card
nor a `Called x tools` count; generic `web_search`, OpenAI `openai_search`, and other tools remain
visible. This presentation rule does not change request serialization, hosted-tool settlement,
persistence, replay, citation extraction, source order, or failure behavior. An `executableCode` part
starts a visible `code_execution` block displayed as `Code Execution`; the matching
`codeExecutionResult` completes that same block. Code and output are not duplicated into answer text.
Persisted Code Execution segments replay to later Gemini requests as typed executable-code and
code-execution-result model parts in their original order. Multiple pairs remain ordered, and an
unmatched executable-code part leaves a tool in flight so semantic termination fails closed.

If the official service, selected model, or compatible relay rejects `web_search`,
`service_tier`, reasoning summary, or the Responses request itself, that failure is an ordinary
generation error. Persist the provider's bounded error text and render it through the shared neutral
text-only generation terminal presentation. Do not silently retry without the parameter, fall back
to Chat Completions or generic Web Search, auto-disable a setting, show the generated response as an
error, or use a Snackbar-only or parallel error presentation.

Non-success HTTP response parsing is independent of the response Content-Type and is shared by the
OpenAI-compatible, Anthropic, Gemini, and Ollama transports. It accepts canonical nested Provider
envelopes, primitive `error` values, common top-level message fields, and JSON string roots. A
nonblank body that does not match those structures remains visible as trimmed raw text; an empty
body falls back deterministically to its HTTP status. HTTP response failures use the API-error path,
while connection, DNS, TLS, timeout, and other transport failures remain network errors. Persisted
legacy network wrappers reuse the same structured detail extraction so current and historical
presentation cannot drift.

Provider-emitted structured thought events and ordinary text both pass through one shared
Provider-pass normalization boundary before stream accumulation. Native parser authority preserves
typed native tool-call execution and suppresses generic text-rendered tool recovery; it does not
bypass shared incremental thinking-delimiter recovery for text that the native template parser leaves
unclassified. Recovery is incremental across transport chunks, recognizes supported model-emitted
channel forms, and preserves matching delimiters inside Markdown inline or fenced code as literal
text.

A compatible relay that leaves final-answer bytes in its thought field may use a supported unmatched
closing delimiter as the boundary: outside Markdown code, the prefix remains a `ThoughtChunk`, the
delimiter is removed, and the suffix plus later misrouted thought chunks become ordinary text.
Matching is case-insensitive and incremental across transport chunks. This fallback is one-way and
applies only after the wire adapter has already classified content as thought; ordinary text and code
literals are never globally stripped or reclassified. Thought title/signature metadata and the
relative order of tool, usage, citation, retry, and terminal-error events remain intact.

Rows persisted before this normalization use the same narrow condition at the shared Room projection:
only an assistant row with blank durable answer text, no nonblank answer segment, and a nonblank
suffix after a supported close in a thought segment is recovered. UI and Provider-history projection
both split that segment into thought plus answer without mutating Room, so visible history and the
next request cannot drift. A real durable answer always wins and disables compatibility recovery.

### 8.10 Provider reuse and mandatory minimum-abstraction rule

Official endpoints and compatible relays reuse the existing Provider implementation selected by the
wire protocol. A relay carrying Claude or Gemini models through an OpenAI-compatible wire contract
uses the OpenAI path; model branding must not select a second lifecycle or an Anthropic/Gemini
transport. Endpoint, authentication, and proven compatibility differences should remain constructor
parameters, existing configuration fields, or narrow overrides whenever those mechanisms are
sufficient.

Provider work must not create a general object model merely to make OpenAI, Anthropic, and Gemini
look structurally identical. Their request encoding, authentication, stream state machine,
signature/history replay, and terminal proof may remain direct protocol-local code. Reuse is
required at the existing generation lifecycle, semantic `StreamEvent`, message/tool projection, and
proven shared utility boundaries; wire-level uniformity is not a goal.

The following are binding review blockers:

- Do not add `ProviderDescriptor`, `ProviderCapabilities`, transport/policy/strategy objects,
  adapter layers, wrapper configs, factories, or interfaces by default. A proposed name or diagram
  is not evidence that an abstraction is needed.
- Do not move existing booleans or fields into a new data object merely to make the configuration
  appear cleaner. One owner and one consumer should normally remain a direct field, parameter, or
  protocol-local condition.
- A new object or interface is allowed only when the task record and review identify a cohesive
  invariant it owns, a real external/transactional side-effect boundary it isolates, or multiple
  genuine stable consumers. They must also state why the existing owner plus parameters is unsafe or
  insufficient and which existing responsibility or duplication will be removed.
- A refactor that only adds indirection, pass-through calls, mirrored types, mapping layers, or
  speculative extension points is invalid. Net object growth requires an explicit reduction in
  ownership ambiguity, duplicated behavior, or failure surface.
- Capability handling should stay as the smallest direct check in the owning Provider/configuration
  path until several real features need the exact same rule. Unknown relay behavior fails closed;
  that alone does not justify a capability framework.

### 8.11 Conversation share projection

Every conversation-sharing mode—whole conversation, selected visible messages, and one assistant
generation—uses one public-content formatter. The exported Markdown must omit every structured
`thought` segment, every `tool` segment and all of its names, arguments, progress, results, images,
and protocol metadata, legacy `MessageEntity.thoughts`, and synthetic tool/result protocol rows.
This is a read-only projection rule: sharing never deletes, rewrites, or weakens durable history,
Provider context, tool continuation state, or fork graph completeness.

The formatter preserves the selected visible branch and established ordering, completion checks,
conversation title, user text and attachment summaries, assistant answer and transcription content,
and error content. Inline text versus Markdown-file transport, share selection, and Android chooser
behavior remain transport/UI concerns and may not reintroduce private Thinking or tool payloads.

## 9. Context assembly contract in module terms

`GenerationApiPathBuilder` receives one immutable durable snapshot and a requested parent ID. It
walks only that parent chain, stops at the first successful Compact encountered upward, expands
protocol side chains without duplication, and projects Room entities once. The shared immediate
pre-Provider projection then applies image/user-template transforms and appends an optional frozen
API-only initial USER prompt. For Compact that prompt is mandatory and therefore the final request
item is USER even when the durable parent is Assistant; ordinary requests without such a configured
prompt remain unchanged.

The API-only USER prompt is counted as fixed request cost so threshold and rollout accounting cannot
omit bytes that dispatch sends. It is appended only to the initial Provider request and is not part
of retained-message calculation, Room history, generation-boundary grouping, or later tool rounds.

A Compact preflight may call this read-only ordinary builder to calculate retained-summary text.
That projection is not authoritative input for execution and cannot replace or suppress the
ordinary Provider request rebuild inside the shared generation tail.

A branch-selection change, missing parent, or corrupt chain must fail closed or produce only the
reachable safe prefix. It must never jump to a Compact on another branch.

### 9.1 Immutable materialization and single rollout ownership

The Room read side may optimize payload materialization, but it may not select a different context.
One immutable read transaction captures the selected-branch state, payload-free message topology,
and every full row reachable from the requested parent or selected visible path. The transaction
walks the same durable parent chain, applies the nearest-SUCCESS-Compact boundary regardless of
summary text content, and expands the same run-matched tool/result side chains in established order.
Only full payload rows outside that canonical path may be omitted. There is no pre-rollout,
overscan, payload-size product limit, topology-retry approximation, or alternate Compact boundary.

Conversation UI payload residency is a separate projection optimization. Payload-free topology owns
durable ordering and structural fields. The one active generation row is overlaid and rendered
directly from one atomic current render snapshot; it must not be observed through a remembered
single-value Flow, historical row hydration, or a payload cache. Composed historical rows may observe
and hydrate their full payload by stable message identity. JSON decoding and display projection occur
off the main thread. A bounded LRU may retain completed display projections, but it is never
authoritative state, never bridges a terminal transition, and never changes topology, edit identity,
or Provider-visible materialization. The top-right current-conversation search derives eligible
ordinary USER and MODEL IDs from the complete payload-free selected path, reads those payloads in
fixed 64-ID pages, restores selected-path order within each page, and retains only lightweight match
ranges. Its one matching surface is display-projected message body text: Tool/result/Compact rows,
Thinking and Tool segments, citation/source metadata, and attachment metadata never enter candidates
or counts. Canonical result order is selected-path root-to-leaf, then source range ascending within
each message. Each independently rendered Timeline Answer slice retains the global match identity
for only the source ranges inside that slice. When results first arrive, the exact visible occurrence
nearest the usable message-viewport center becomes active; Up selects the adjacent visual occurrence
above, Down selects the adjacent visual occurrence below, and neither direction wraps at an end.
`MessageList` remains the only scroll owner: it receives the canonical rendered turn order, accepts
exact glyph geometry only for the active measurement epoch, and centers that exact occurrence in
LazyColumn-local coordinates between the top bar and composer through its single progressive seek.
Search result recall is independent of LazyColumn composition and payload-cache residency;
jumping to a match continues through the existing stable message identity and per-row hydration path.
Semantic-search reads keep their separate bounded payload projection. LazyColumn eviction or
rehydration may change object lifetime only; it must not change content, generation state, or
glyph-birth metadata.

An open Thinking-segment Bottom Sheet is owned above the LazyColumn and stores only durable message
identity plus segment-selection mode. It never stores a copied row payload, observes the payload LRU,
or depends on the source item remaining composed. Its authoritative payload order is the current
generation snapshot, the atomic render-store payload retained for streaming-to-terminal handoff, then
a direct Room observation by message ID. Scrolling, item disposal, payload eviction, or future chunk
offload therefore cannot close, freeze, clear, or delay an already-open sheet. Group/list-first mode
recomputes its complete current Thought/Tool/Transcription index set from every authoritative snapshot,
so segments created after opening appear immediately without resetting the sheet's list/detail page.
A direct single-segment sheet keeps its selected stable detail index and dismisses only when the
message or selected authoritative segment is actually removed.

After Room projection, Provider preparation remains the only rollout authority. No DAO, loader,
Compact controller, UI projector, transcription stage, or automation caller may remove an older
eligible row because of a token estimate before that shared boundary. This optimization therefore
changes database materialization and object lifetime only; it does not change Provider-visible
ordering, attachment projection, protocol validation, context selection, or failure semantics.

### 9.2 Ordinary-generation system prompt ownership
Gemini serializes the compiled system prompt under the canonical REST JSON field
`systemInstruction`, shared by ordinary chat and dedicated internal generations. It must emit
only that field spelling, never both protobuf and JSON aliases; absent prompts omit the field.

For ordinary conversation generation, the complete Provider-visible system prompt is owned by the
user-selected structured System Prompt template. The request builder may compile that template and
resolve only the predefined variables that the user explicitly placed in it. It must not append,
prepend, wrap, or otherwise inject Active Memory, the Skill catalog, runtime metadata, tool guidance,
application instructions, or any other hidden text outside the template.
Access settings grant capabilities; they do not grant prompt-injection authority. Enabling Active
Memory or Skill access may make the corresponding predefined variable resolvable and may expose the
authorized tools, but content enters the ordinary system prompt only where the user placed that
variable. Removing a variable from the template must remove that content from the dispatched system
prompt without a construction-layer fallback.
Every predefined variable in the structured System Prompt is late-bound for each actual outbound
Provider API request. The stored template contains only ordered text and variable identities. Editing,
saving, conversation creation, context preview, queue admission, Run/message graph admission, and
construction of an immutable generation snapshot must not persist or freeze a resolved value for a
later request.
Immediately before each initial request, tool-continuation request, and transport retry is serialized,
the request path must read the variable's current authorized value and compile the complete system
prompt for that request. A later request in the same Run may therefore resolve different values.
`{current_model_id}` resolves once per dispatch from the model selected for that request. The legacy
`{model_id}` name remains a read-only compatibility alias for the same value. `{message_model_id}` is
message-scoped: ordinary User and Assistant wrappers resolve it independently from each durable
message's model identity, using an empty value when none exists. It is not a request-wide substitute.
Editor previews may use explicit example values only for presentation and must never persist them as
resolved prompt content.
Startup migrations preserve user-edited System Prompt content and all message wrappers. Only a
complete match to a known unmodified built-in template permits replacement with a newer default;
the presence of a legacy runtime tag alone never authorizes replacing a stored template. Legacy
wrapper storage may be normalized without changing its resolved content.
Context rollout and token accounting for a dispatched request must consume the exact late-bound system
prompt instance that the transport serializes. They must not estimate from an earlier resolution and
then dispatch a newly resolved prompt. Provider adapters receive the compiled prompt and must not
resolve variables, restore omitted content, or append their own system text.
This contract applies only to ordinary conversation generation. Dedicated internal generations,
including Context Compact and title generation, continue to use their own explicitly configured
special-purpose prompts and are outside this subsection.

Embedded Local Low Context Mode is the sole ordinary-generation exception to the structured Prompt
projection above. Its effective value is the nullable conversation/New Chat override when present,
otherwise the device-local Local Provider default. It applies only when the admitted Provider is the
embedded `Local` Provider; Ollama, custom Providers, and every remote Provider ignore the value.
When effective, admission and context projection treat the current structured System Prompt as
completely empty: they do not capture, compile, or resolve its template, predefined variables, Active
Memory, Skill catalog, or User/Assistant prepend/postpend items. The frozen ordinary request has no
system prompt, no prompt resolver, no ordinary tool definitions, and no Provider-native search or
code-execution tool declaration. Fixed-cost/context estimation must use that exact empty-prompt,
empty-tools request shape. The underlying settings and selected Prompt remain stored, so disabling
the mode restores normal projection. Compact and other dedicated internal generations keep their
own prompts and declared tool policy.

### 9.3 Canonical history and soft token window

The shared Provider preparation order is deterministic:

1. remove non-successful Compact rows from Provider history and project the nearest successful
   Compact as the established USER summary boundary;
2. deduplicate durable IDs, project terminal generation status on the same assistant turn, validate
   tool protocol fail-closed, remove empty normal turns, and merge consecutive ordinary roles;
3. treat one tool request and all consecutive result rows as one indivisible protocol unit while
   every ordinary canonical message is one unit;
4. scan complete units newest-to-oldest until the estimated message budget is reached, retaining at
   least the newest complete unit and one normal USER anchor even when that legal suffix exceeds the
   estimate;
5. start the dispatched history at its first normal USER and never keep an older ordinary row while
   dropping a later ordinary row on that selected branch.

The configured context value is a soft estimated request budget, not a hard byte limit.
The shared default is `262,144` tokens (`256K`, using 1,024 tokens per K). It applies when no valid
context budget is stored; explicit global/conversation values and legacy normalization remain
authoritative. This default does not increase an embedded Local model's configured `nCtx`.

Fixed request cost consists of the system prompt, complete enabled tool schemas, and the optional
API-only initial USER prompt. It is subtracted exactly once from the configured budget before
history rollout. The initial prompt is excluded from retained-history selection and appended
exactly once afterward as the final USER request item. It is never merged into Room history,
persisted, rendered, or treated as a durable retention anchor.

### 9.4 Complete conservative token accounting

Token accounting runs only after the same Provider-visible projection used by dispatch. It includes
all projected ordinary text, attachment file text, stored image transcription, user templates,
terminal annotations, tool names, arguments, results, signatures, tool-call reasoning content,
opaque continuation JSON, system prompts, complete app tool schemas, enabled Provider-native tool
descriptors, and the optional API-only initial USER prompt. Every Provider-visible image path is counted, including each image attachment, PDF
page, video frame, assistant-generated image projected to the latest USER, and tool-result image
projected to its synthetic USER turn.

Exact tokenization and visual-token pricing vary by selected model and custom Provider, so the
shared estimator remains intentionally conservative: text uses its deterministic cross-provider
heuristic and every projected image uses the established fixed per-image estimate. It may
overestimate, but it may not omit a Provider-visible category. Display-only citations, tool
progress, presentation metadata, and attachment metadata that is not serialized are excluded.

The Chat top-bar token subtitle and Bottom Bar context indicator report the same full selected canonical
context estimate plus fixed request cost; neither surface replaces that number with the already-retained
Provider window or with a sum of historical message usage. The top-bar subtitle is absent when no
canonical usage is available. When visible, it shows `~used / budget tokens`, reusing the Bottom Bar's
localized context-usage resource and `ContextBudget.compactLabel` for both numbers. The subtitle retains
the existing title measurement, clipping, and motion ownership; it never calculates context itself.
The rollout projection maps the shared canonical window back to one
contiguous eligible durable suffix on the selected branch, including complete protocol units.
Automatic Compact eligibility and retained verbatim text consume the complete selected canonical path,
not an already-rolled Provider suffix.

The UI projection reloads whenever the visible conversation ID or exact durable
`selectedBranchesJson`, selected model, normalized context budget, durable message projection, or
request-configuration invalidation input changes. The existing projector publishes one
identity-fenced state: loading may carry only the previous canonical usage to prevent a transient zero
presentation, but it has no retained IDs; a completed success may contain a valid empty retained set;
and a completed failure has neither usage nor retained IDs. A superseded request may never publish
over a newer identity.

Rollout may consume a projection only when it is completed, not loading, not failed, and its
conversation ID and `selectedBranchesJson` exactly match the visible conversation. Branch-switch
and deletion covers wait without a fixed projection timeout for matching completion, then for the
existing graph/layout settlement. Matching failure is completion for cover release only: rollout
stays disabled and presentation remains neutral; failure or loading must never be interpreted as an
empty all-rolled-out context.

Context rollout visualization is presentation-only. A MODEL message in `SENDING`, `THINKING`,
`TOOL_CALLING`, or `TRANSCRIBING` is generation-in-progress and must remain at normal opacity even
when its durable ID is not yet present in the retained-history projection. After that message reaches
a terminal status, the existing canonical retained-history projection determines its rollout
presentation. Only a message already classified as rolled out receives the legacy whole-message
`Modifier.alpha(0.38f)` presentation, so its complete rendered subtree dims together. This visual
rule never inserts the in-progress row into Provider history, token accounting, Compact input, or
retained-message calculation.

## 10. Concurrency and failure-safety principles

1. **Single process authority.** Only the conversation mailbox/reducer accepts lifecycle
   transitions. Controllers execute accepted effects; they do not maintain shadow state.
2. **One durable live Run.** The Room active-slot constraint and transactional preconditions are
   mandatory. A fresh Run is inserted only when no other live Run exists.
3. **Fresh identity per admission.** Run IDs are generated before the durable transaction and never
   reused to restart terminal work.
4. **Identity fencing.** Asynchronous Provider, tool, checkpoint, Stop, and finalization results are
   accepted only for the expected conversation, owner token, Run, pass, and effect ID.
5. **Durable-before-external.** Provider execution begins only after the Run/message graph commits
   and the process state binds that exact Run.
6. **Terminal-before-handoff.** Compact, queue, or loop continuation starts only after the origin
   Run's legal terminal boundary. Checkpoint writers close before terminal persistence.
7. **Short lock scope.** Queue mutexes and graph/selection locks protect only decisions and
   transactions; never hold them across Provider streams, tool execution, or UI waits.
8. **Revalidation.** UI-derived targets, parent links, terminal status, Run ownership, and selected
   edges are re-read inside the serialized/transactional boundary.
9. **Atomic replacement.** Same-row fresh-Run substitution either updates the target and Run graph
   completely or changes nothing. Non-target message rows are immutable inputs.
10. **Cancellation robustness.** Cancellation cannot strand a SENDING row, lose a claimed queue
    lease, reopen a terminal Run, or bypass both coroutine and durable settlement barriers. A Stop
    persistence failure keeps the slot occupied; only after that exact failure is recorded may a
    later Stop reissue the same finalization effect identity. Concurrent duplicates and stale
    identities remain rejected. If cancellation or failure is delivered at a suspending Run-graph
    commit boundary, the owner must re-read the exact proposed Run before treating the transaction
    as uncommitted or allowing the process slot to release.
11. **Bounded persistence.** Final transforms may change only declared presentation text and the
    shared persistence guard is reapplied afterward. For an aggregate whose trimmable JSON-string
    payload already proves it exceeds the byte budget, that guard measures escaped UTF-8 payload
    bytes, derives fixed metadata/JSON overhead from a placeholder projection, performs all
    largest-field-first reductions in memory, and encodes the bounded aggregate once. It must not
    repeatedly serialize an unbounded checkpoint aggregate. The exact encoded UTF-8 bound remains
    authoritative, and protected Provider continuation state fails explicitly rather than becoming
    SQL NULL.
12. **Fail closed.** Missing/cyclic parents, shared legacy Runs that cannot be substituted safely,
    selection drift, stale identity, and partial transaction results reject the operation rather
    than guessing or broadening mutation scope.

## 11. Abstraction and growth principles

- Prefer an existing ordinary owner over a new feature layer. Configuration data and target
  parameters are preferred to another controller/state machine.
- Keep a rule pure when it is pure. Boundary and presentation policies should be deterministic
  functions with focused tests.
- Extract a module only when it owns a cohesive invariant, a real external/transactional side
  effect boundary, or multiple genuine consumers. Do not create pass-through wrappers, type
  aliases, one-call factories, or speculative interfaces.
- Conversely, do not let a simple owner grow into unrelated responsibilities. If a file starts
  mixing admission, context, Provider execution, persistence, and UI policy, split along the
  ownership table above rather than by arbitrary line count.
- Durable fields such as `parentId`, `runId`, status, and selected edges drive generic policy.
  Message prefixes may identify presentation/protocol types but must not create parallel lifecycle
  semantics.
- Compatibility handling belongs at the narrow read/transaction boundary and must not pollute the
  normal path. New writes obey current contracts; unsafe legacy states fail closed.
- Comments explain ownership and invariants, not a second algorithm. Tests assert observable
  contracts, not source spelling.
- No architecture claim is complete until focused concurrency/failure tests, both flavor unit
  suites, the project source-size/architecture gates, and the required build succeed.

## 12. Required verification ownership

| Contract | Minimum focused proof |
|---|---|
| Generation grouping | Real USER hard boundaries; same-Run protocol/assistant rows remain one group; every Run transition separates groups; blank legacy IDs are safe. |
| Action/status projection | Every real USER has actions; only each Run group's terminal ordinary assistant has assistant actions/status; different Runs remain separate. |
| Fresh-Run admission | Send, queue drain, Compact, Recompact, and Regenerate never restart a terminal Run. |
| Context boundary | Parent-chain nearest successful Compact wins; closer ERROR/STOPPED/SENDING Compact is ignored; off-branch Compact is unreachable. |
| Recompact isolation | Same message ID/parent, fresh Run, unchanged selections and byte-for-byte unchanged non-target message rows/suffix. |
| Delete isolation | Target-only delete, direct-child reparent, unchanged surviving rows, independent Run presentation. |
| Priority | Only Compact SUCCESS permits handoff; then pending and already-claimed queue guidance beat loop and the no-guidance path admits loop once. ERROR/STOPPED/cancellation/anomaly starts neither. |
| Request terminal role | Compact dispatch appends one non-durable initial USER invocation after an Assistant or tool-result parent; provider-visible input ends USER and fixed token accounting includes it. |
| Provider-hosted output | OpenAI-compatible Chat requests serialize applicable numeric and model-specific thinking controls; Responses requests preserve all seven recognized `service_tier` values and serialize enabled `web_search` plus reasoning summaries; impossible thinking-off requests fail before HTTP; summary indices preserve part boundaries and headings supply titles; OpenAI Search and Gemini Google Search/Code Execution settle display-only tool blocks without local execution; Gemini Code Execution replays typed parts and fails closed when a result is missing. |
| Races and failures | Stop before/after bind, consecutive origin/Compact release suppressions in both settlement orders, selection drift, missing target/status, transaction rollback, stale callbacks, checkpoint-versus-terminal ordering, and queue claim failure. |
| UI stability | Compact row/pill vertical bounds do not change across progress and terminal content; entrance is draw-only and does not alter apparent vertical spacing; message and Thinking Tool terminal text reuse the shared neutral body-text tokens and alpha without Segment error cards. |
