# Agora Development Contracts

Status: authoritative mandatory development entry, 2026-08-13.

Every Agora development task must read this document before planning or editing. It then must read
each module contract whose code, data, UI, persistence, automation, or behavior is in scope. These
documents are executable product/architecture contracts, not optional background material.

## 1. Authority and update rule

Current explicit user requirements have the highest product authority. The applicable documents
under this folder persist those requirements across tasks. Production code, tests, older
architecture prose, and historical behavior must align with them.

When a user changes a contract:

1. update the applicable module contract before or together with implementation;
2. map the requirement to one owning module and focused verification;
3. update conflicting code, tests, and older documentation;
4. do not preserve the conflict through a feature-local exception or a parallel contract.

Runtime source remains evidence of current implementation, but current implementation does not
override an explicit contract.

## 2. Mandatory core contracts

- Reuse the ordinary pipeline, state owners, durable transactions, concepts, and objects to the
  maximum practical extent.
- All software behavior must match its core contracts in normal, concurrent, cancelled, failed,
  stopped, recovered, automated, and legacy-compatible paths.
- Room is durable truth. A streaming overlay or UI projection is not a second graph or state owner.
- One conversation has one process lifecycle authority and at most one durable live Run.
- Every newly admitted send/generation receives a fresh identity. Terminal work is never reopened.
- Asynchronous results are accepted only with exact conversation/owner/Run/pass/effect identity.
- Checks and writes that establish one invariant belong in one atomic transaction or one serialized
  decision boundary.
- Pure policies stay pure. UI grouping, context assembly, state transition, Provider execution, and
  persistence are separate responsibilities.
- Fail closed on stale identity, graph drift, unsafe legacy state, missing/cyclic ancestry, partial
  transaction results, and ambiguous ownership.
- Protect user data first: never lose, duplicate, reorder, broaden-delete, or silently overwrite
  messages, queue entries, attachments, branches, or terminal output.

## 3. Concurrency and robustness invariants

1. The conversation mailbox/reducer is the single in-process transition authority.
2. The Room active-slot constraint and transaction predicates are the durable concurrency fence.
3. External Provider/tool work starts only after the exact durable Run binds successfully.
4. Stop and natural completion obey their defined coroutine and persistence barriers.
5. Checkpoint work closes before terminal persistence so an old snapshot cannot resurrect streaming.
6. Queue ownership is explicit; claim failure returns the exact batch and successful commit
   transfers it once.
7. Locks protect only bounded decisions/transactions and are not held across network, Provider,
   tool, UI, or long-running suspension.
8. UI-derived graph targets and selections are re-read at the serialized/transactional boundary.
9. Replacement/deletion transactions modify only their declared target and structurally necessary
   metadata.
10. Cancellation, process death, retries, duplicate callbacks, and out-of-order completion must
    converge to a deterministic durable state.

The design goal is to minimize failure surface and failure modes: one execution path, one state
authority, narrow mutation capabilities, short lock scopes, fresh identities, atomic graph
changes, bounded persistence, and explicit stale-result rejection.

## 4. Abstraction and growth principles

- Do not create a new concept, state machine, controller, interface, wrapper, factory, or data
  object when an existing owner plus a parameter can express the behavior safely.
- Extract only for a cohesive invariant, a real side-effect/transaction boundary, or multiple
  genuine consumers.
- This is a mandatory review gate: every proposed abstraction must name which of those three
  conditions it satisfies, why the existing owner plus parameters is insufficient, and which
  existing responsibility or duplication the new abstraction removes. Missing evidence blocks the
  change.
- "Reuse" does not authorize speculative `Descriptor`, `Capabilities`, `Policy`, `Strategy`,
  `Adapter`, configuration-wrapper, or pass-through objects. Do not introduce a data object merely
  to rename, regroup, or shuttle fields already owned safely by an existing object.
- Prefer a direct protocol-local branch or one additional parameter when it is clearer and has one
  owner. Generalize only after real shared behavior exists; do not build an object model for
  hypothetical Providers, transports, models, or future consumers.
- Do not allow a simple controller/manager to accumulate unrelated admission, context, Provider,
  persistence, and UI responsibilities. Split along ownership boundaries, not arbitrary style.
- Prefer generic rules driven by durable fields over feature names, message prefixes, or UI
  location. Type prefixes may select rendering/protocol decoding, not a second lifecycle.
- Compatibility logic stays narrow and read-side/transactional. New writes always obey current
  contracts.
- Comments state ownership and invariants. Tests assert behavior, race/failure outcomes, and
  non-mutation guarantees rather than source spelling.
- Simpler architecture means fewer authorities and paths, not fewer safety checks.

## 5. Universal prohibited behaviors

Never:

- create a parallel generation, queue, Stop, settlement, context, or branch pipeline for a feature;
- infer Provider context from UI generation grouping, or infer Regenerate scope from context
  truncation;
- generate on a terminal/old Run ID;
- accept a stale or partially identified asynchronous result;
- hold a shared lock while awaiting external or long-running work;
- mutate suffix/neighbor messages as an implementation shortcut;
- turn a partial database update into a successful result;
- add a feature-specific merge/boundary exception when a global durable rule exists;
- weaken validators or tests to make an implementation pass;
- claim runtime/UI correctness from compilation alone.

## 6. Module contract registry

| Scope | Required module contract |
|---|---|
| Message generation, Run lifecycle, queue, tools, Compact, Regenerate, message actions/status, or Provider context | [message-generation.md](message-generation.md) |
| Embedded llama.cpp FIFO admission, Chat/Embedding residency, identity switching, Stop, or idle offload | [local-model-runtime.md](local-model-runtime.md) |
| Provider structured citations, citation persistence, marker cleanup, answer/source projection, citation copy/search/import/export, or citation accessibility | [citations.md](citations.md) |
| Embedding-cache reads, semantic conversation search, RAG ranking, or search eligibility | [semantic-search.md](semantic-search.md) |
| Generic Web Search providers/settings/tool execution or native provider-hosted web search | [web-search.md](web-search.md) |
| Persistent Skill files, Skill catalog prompt projection, Skill tools/settings, or Skill archive transport | [skills.md](skills.md) |
| Application-level onboarding motion, settings category copy, or other non-message global UI behavior | [application-ui.md](application-ui.md) |
| Native `.agora` archive categories, settings portability, import strategies, secrets, or backup compatibility | [import-export.md](import-export.md) |
| Shared Settings page structure, interaction, copy, localization, or documentation entry points | [settings-ui-ux.md](settings-ui-ux.md) |

Add a module document when a user defines durable behavior for another subsystem. Each module
document must describe current code ownership, allowed and forbidden responsibilities, concrete
behavior/state/data flow, concurrency and transaction boundaries, failure behavior, and required
verification. Keep one authority per contract; do not duplicate normative text across modules.

Documents under [`baselines/`](baselines/) are explicitly historical and non-authoritative. They
preserve migration or audit evidence only and never override the module contracts in this registry.

## 7. Development completion gate

Before completion:

1. re-read this document and every applicable module contract;
2. review the complete task diff against every touched invariant and prohibited behavior;
3. run focused success, race, cancellation, stale-result, rollback, and non-mutation tests;
4. run the project-defined full build gate after the final code change;
5. separate build/deploy evidence from unverified device UI behavior;
6. update module documentation whenever the accepted behavior or ownership changed.
