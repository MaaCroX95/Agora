# Development Contracts

Status: authoritative development process contract.

This directory contains subsystem-specific contracts for Agora. The root repository guidance defines
which documents are authoritative and when they must be consulted; these files define durable behavior
and ownership for concrete subsystems.

## 1. Scope and authority

Use this registry to identify the applicable module contract before editing behavior covered by one of
the listed scopes. Module documents are authoritative for their subsystem unless a higher-level
repository instruction explicitly overrides them.

A module contract should describe:

- current code ownership and entry points;
- allowed and forbidden responsibilities;
- important state/data flow and persistence behavior;
- concurrency, cancellation, transaction, and rollback boundaries;
- user-visible failure behavior;
- required verification for changes.

Do not duplicate normative behavior between module contracts. Link to the owner document instead.
Historical notes and migration baselines may preserve evidence, but they do not override current
contracts.

## 2. Change discipline

Before modifying a governed subsystem:

1. identify and read the applicable module contract;
2. inspect the current implementation rather than relying on stale assumptions;
3. preserve existing invariants unless the requested behavior explicitly changes them;
4. make the smallest coherent change that satisfies the accepted behavior;
5. update the owner contract when accepted behavior or ownership changes.

## 3. Testing expectations

Verification should match the risk of the change. Prefer focused tests around the exact behavior first,
then run the repository-defined full gate after the final code change. When asynchronous state or
persistence is involved, cover success plus meaningful failure/race/cancellation/non-mutation paths.

Do not report device UI behavior as verified unless it was actually exercised on a device or emulator.
Build and unit-test evidence should be described as build/test evidence only.

## 4. UI authority

Application-wide UI behavior should follow the established design system and the applicable UI module
contract. Avoid introducing one-off visual or interaction patterns when an existing shared component or
contract already owns the behavior.

When a user provides an explicit approved reference or page specification, treat that as the target for
the governed surface. If implementation details are missing, preserve established surrounding behavior
rather than inventing unrelated changes.

## 5. Data and compatibility

Changes to persisted user data, exports/imports, provider payloads, signing identity, package identity,
or other compatibility-sensitive surfaces require an explicit compatibility story. Existing data and
installed builds must not be silently invalidated unless the migration requirement is clearly documented.

## 6. Completion standard

A change is not complete just because it compiles. Completion includes the required tests, relevant
contract updates, and verification that no unintended temporary files, debug behavior, secrets, or
migration scaffolding remain in the final diff.

Never:

- commit API keys, signing private keys, passwords, or user secrets;
- weaken a safety/compatibility invariant simply to make a test pass;
- claim runtime behavior that was not actually exercised;
- keep stale workaround code after the underlying issue is resolved;
- bypass an applicable owner contract because the change seems small.

## 7. Module contract registry

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
| Release APK signing, signing-key handling, CI version codes, or update compatibility | [release-signing.md](release-signing.md) |

Add a module document when a user defines durable behavior for another subsystem. Each module
document must describe current code ownership, allowed and forbidden responsibilities, concrete
behavior/state/data flow, concurrency and transaction boundaries, failure behavior, and required
verification. Keep one authority per contract; do not duplicate normative text across modules.

Documents under [`baselines/`](baselines/) are explicitly historical and non-authoritative. They
preserve migration or audit evidence only and never override the module contracts in this registry.

## 8. Development completion gate

Before completion:

1. re-read this document and every applicable module contract;
2. review the complete task diff against every touched invariant and prohibited behavior;
3. run focused success, race, cancellation, stale-result, rollback, and non-mutation tests;
4. run the project-defined full build gate after the final code change;
5. separate build/deploy evidence from unverified device UI behavior;
6. update module documentation whenever the accepted behavior or ownership changed.
