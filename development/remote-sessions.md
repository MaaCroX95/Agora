# Remote Sessions

Status: owner-approved simple-chat scope, 2026-09-06.

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
The simple-chat client lists existing sessions, loads paginated text messages, submits
text to Codex's native queue and refreshes persisted output. It never retries a POST
automatically. An unknown submission result remains visible for manual reconciliation;
it must not be presented as a definite failure and blindly resent.

Connections are saved after successful authentication in a Remote-only atomic file under
the app's no-backup directory. Device names and canonical service addresses accompany
Keystore-encrypted tokens. Encryption must succeed before writing; plaintext fallback
is rejected. Corrupt or undecryptable storage is preserved and reported, not overwritten.
Remote owner recreation restores the device list without selecting a device, reading
conversation history or submitting messages. Selecting a device admits its session read.
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
approvals, tools, attachments, editing, branching and model settings are outside this
first simple-text slice; actual capability labels must not imply their availability.

## Verification

Verify authenticated transport, strict protocol compatibility, projection identity,
pagination, cancellation/stale-result rejection and no automatic POST retries. Verify
actual queue execution in the original native owner separately from service startup.
Compile and focused tests cover shared ordinary bindings. Run the full project build
and configured deployment at completion; device UI acceptance remains owner-tested.
