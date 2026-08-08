# ADR-031 Phase 0: Characterization and clean-baseline decision ledger

**Status:** Complete
**Date:** 2026-08-08
**Parent:** [ADR-031](031-composable-state-machine-foundation-and-portable-proofs.md)

## 1. Purpose

This record closes ADR-031 Phase 0. It identifies behavior that later phases must preserve by
meaning, identifies preview contracts that must be removed, and records the test baseline before
the clean-break refactor. It is not a promise to reproduce preview roots, bytes, APIs, or storage.

## 2. Characterized baseline

The following existing properties are intentional:

| Area | Retained semantic property | Characterization evidence |
|---|---|---|
| Block execution | One deterministic application transition per candidate/finalized block and one atomic post-state commitment | `AppChainEngine`, `PreparedStateCommitRuntimeTest`, `AppChainTwoNodeSmokeTest` |
| State persistence | Block, authenticated state, effects, and operational indexes commit atomically | `AppLedgerStore`, runtime restart/snapshot tests |
| Admission | Proposal admission can depend on candidate height and the committed pre-state | `AppStateMachine.validateForBlock`, engine identity/adversarial tests |
| Effects | Effect intents and incorporated results are deterministic consensus data; external execution stays outside state transition | `FxEffectsM1Test`–`FxEffectsM5Test`, effect conformance tests |
| Composition | Routes, namespaces, query ownership, effect ownership, and profile identity are explicit and deterministic | composite isolation, routing/effects, profile, and governance suites |
| Authenticated state | MPF and classic JMT proofs are interpreted only with their genesis-bound commitment identity | state commitment and proof-verifier suites |
| L1 observations | Accepted facts are sequenced in app blocks, rechecked by followers, and replayable without consulting live historical L1 state | `L1ObservationServiceTest`, system-topic admission tests |
| Authorization | ADR-019 actor revisions, role policy, approvals, one-use consumption, and governed-map coverage are the reusable semantic foundation | role-workflow and authenticated-map suites/golden vectors |
| Evidence | Message finalization, supplied-content binding, committed application state, and content availability are distinct claims | evidence and proof-verifier tests |

## 3. Preview contracts intentionally broken

The following are implementation debt, not retained behavior:

| Preview contract | Phase that removes/replaces it |
|---|---|
| Two- and three-argument `AppStateMachine.apply` overload chain | Phase 1 |
| Effect-result overload delegation retained for source/binary compatibility | Phase 1 |
| Legacy query overload without a committed query context | Phase 1 |
| Public `CompositeComponent` lifecycle SPI | Phases 1 and 3 |
| `StateMachineComponentAdapter` and synthetic routed `AppBlock` construction | Phases 1 and 3 |
| Composite blanket admission for declared reserved routes | Phase 1 |
| Workflow reuse by fabricating messages/blocks and recursively calling machines | Phase 2 |
| Stock transition logic embedded only in whole state-machine adapters | Phase 2 |
| Multiple application-facing actor/approval paths | Phase 4 |
| Raw-prefix/storage-shaped proof API as the supported developer surface | Phase 5 |
| Vendor-branded commitment/proof identifiers (`ccl-*`, `zeroj-*`) | Phase 5 |
| Product-local eUTxO MPF normalization/verifier as the only on-chain route | Phase 6 |
| Preview anchor datum lacking full commitment/genesis/application binding | Phase 6 |
| Composition-dependent assembly in foundational stock modules and forwarding façades | Phase 7 |

No alias, reader, adapter, migration, or dual-write path will be added for these contracts.

## 4. Clean-baseline invariants

All later phases must maintain these invariants:

1. Consensus execution depends only on finalized/candidate block data, immutable chain-generation
   configuration, and the committed pre-state.
2. A node replaying app blocks does not need historical Cardano ledger state.
3. State mutations, effect requests, approval consumption, and receipts for one accepted action are
   planned and committed atomically.
4. Composition never changes original global block/message identity and never exposes sibling state
   outside declared views.
5. Every public proof binds a typed claim to chain genesis, commitment profile/fingerprint, block,
   height, root, and the appropriate trust source.
6. A commitment proves inclusion/non-inclusion of supplied data, not ongoing data availability.
7. On-chain MPF verification binds the proof root to an independently located anchor output.
8. Identifiers describe algorithms and wire contracts; provider compatibility is metadata backed by
   conformance vectors.

## 5. Initial baseline execution

The following suites passed on Java 25 before Phase 1 changes:

```text
./gradlew :core-api:test :runtime:test :appchain-composite:test \
  :appchain-client:test :appchain-stdlib:test :appchain-role-workflow:test
```

Result: `BUILD SUCCESSFUL` on 2026-08-08.

Later phase gates use focused tests first, then this aggregate baseline. New golden vectors are
generated only after the replacement contract is selected; old vector equality is diagnostic, not
an acceptance criterion.

## 6. Phase-0 gate

Phase 0 is complete because retained semantics and intended breaks are explicit, the principal
implementation locations are mapped in ADR-031 §17, and the representative pre-refactor suites are
green. Phase 1 may replace the execution SPI without preserving preview API or binary compatibility.
