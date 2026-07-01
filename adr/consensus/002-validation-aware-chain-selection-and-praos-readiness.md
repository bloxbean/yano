# ADR-CONSENSUS-002: Validation-Aware Chain Selection And Praos Readiness

## Status

Accepted. Phases 0 through 4 were implemented on 2026-07-01; Phases 5
through 8 remain pending.

## Date

2026-07-01

## Implementation Status

| Phase | Status | Notes |
| --- | --- | --- |
| Phase 0: ADR And Safety Gates | Implemented | `trust-policy=validated` now requires a header validation level that can produce evidence. `level: none` and `body-level: none` remain valid for trusted-indexer use cases. |
| Phase 1: Validation Evidence Model | Implemented | `HeaderValidationEvidence` was added in `:consensus`; `CandidateHeader` now carries era, validation evidence, optional VRF output, and body availability/validation flags. Runtime validation results expose accepted stages and rejection stage/reason. |
| Phase 2: Fragment And Intersection Store | Implemented | Added bounded per-peer candidate fragments, previous-hash continuity checks, canonical intersection checks, and pruning. |
| Phase 3: Deterministic Cardano-Oriented Comparator | Implemented | Added a comparator with explicit comparison reasons for longer chain, density, validated VRF tie-break, and deterministic fallback. The trusted/quorum strategy now uses this comparator and respects trust policy. |
| Phase 4: Header Validation Pipeline Refactor | Implemented | `ShelleyHeaderValidator` is now a compatibility facade over a staged `HeaderValidationPipeline` with `structural`, `kes-signature`, and `opcert-signature` stages plus library builder APIs for add/disable/override. |
| Phase 5: Praos VRF Proof Validation | Pending | Requires nonce-provider wiring and VRF proof verification against era-specific header data. |
| Phase 6: Ledger-View Praos Validation | Pending | Requires rollback-safe pool VRF key mapping, active stake lookup, leader threshold validation, and op-cert state. |
| Phase 7: Body Integrity Before Adoption | Pending | Requires body hash/size validation and selection gating when body-before-adoption is enabled. |
| Phase 8: Runtime Controller Integration | Pending | Requires a runtime controller only after validation and selection inputs are complete. |

## Context

ADR-CONSENSUS-001 created the `:consensus` module and moved the pure
candidate-header fan-in and conservative chain-selection strategy out of
`runtime`.

That extraction deliberately did not claim full Cardano relay consensus parity.
The current selector is intentionally shallow:

- it compares candidate block numbers and hash quorum;
- it does not maintain peer-local candidate fragments;
- it does not prove continuity from a candidate tip back to a canonical
  intersection;
- it does not use body availability before adoption;
- it does not receive explicit validation evidence as selection input;
- it does not implement Cardano/Ouroboros tie-breaking;
- it keeps selected-upstream switching in runtime.

Yano also has an important non-relay use case: trusted upstream indexing. For
that mode an operator may intentionally disable header/body validation and rely
on a single trusted upstream, because validation cost and implementation
maturity are not always worth the operational complexity.

The next consensus step must therefore support both:

1. trusted/controlled upstream modes where validation can be completely disabled;
2. relay-style multi-peer modes where untrusted candidates require layered
   validation evidence before they can affect canonical selection.

## Reference Implementation Findings

The canonical Haskell node wires Cardano mode as a hard-fork composition of
Byron and Shelley-based eras. Shelley through Alonzo use TPraos-style protocol
state; Babbage and later use Praos-style protocol state. Protocol construction
loads Byron genesis, Shelley genesis, Alonzo genesis, initial Shelley/Praos
nonce from the Shelley genesis hash, and optional leader credentials.

Reference node implementations keep these concerns separate:

- peer/network lifecycle and chain-sync sessions;
- peer eligibility and best-peer tracking;
- header chain selection over peer tips/fragments;
- header cryptographic validation;
- ledger-view validation for stake pool, VRF key, op-cert, and active stake;
- body integrity validation;
- full ledger/block validation;
- block production and leader checks.

Alternative node source review shows the same practical layering: a selector
tracks eligible peer tips, compares chains by length/density/tie-break evidence,
uses VRF output only when it is available, and keeps header-only crypto
validation separate from body/transaction/stake-pool validation. That is the
shape Yano should adopt, but Yano should avoid copying any implementation
structure that conflicts with its runtime generation-fencing and subsystem
boundaries.

This separation matches Yano's current direction: `:p2p` manages peers,
`:consensus` owns selection policy, and `runtime` composes Yaci sessions,
ledger state, storage, body fetch, and generation-fenced recovery.

## Cardano Consensus Requirement Inventory

Cardano-compatible validation-aware selection is not one validation step. It is
a stack of independently testable requirements.

### Era And Protocol Dispatch

- Byron-era blocks are PBFT/Ouroboros-classic and are not covered by Shelley
  Praos header validation.
- Shelley, Allegra, Mary, and Alonzo use TPraos chain-dependent state.
- Babbage and later use Praos chain-dependent state.
- Validation must dispatch by era and protocol version rather than assuming one
  header layout or nonce rule.
- Hard-fork boundaries must come from configured genesis/era data and observed
  ledger protocol state, not hardcoded network constants.

### Header Crypto Requirements

- Decode the era-specific header body and preserve the exact bytes used for
  signatures and hashes.
- Verify the header hash from the encoded header body.
- Verify KES signature over the signed header body.
- Verify KES period is derived from slot and `slotsPerKESPeriod`.
- Verify KES evolution is within `maxKESEvolutions`.
- Verify the operational certificate binds the hot KES key to the cold pool key.
- Verify the VRF proof against the header VRF verification key and the correct
  epoch nonce.
- Extract the VRF output for selection tie-breaking only after VRF proof
  validation succeeds.

### Ledger-View Requirements

- Provide the epoch nonce for the candidate's slot, including epoch-boundary
  cases.
- Provide active slot coefficient, security parameter, KES parameters, and
  protocol version for the candidate's era/epoch.
- Persist pool registration data with cold key hash, VRF key hash, pledge,
  owners, relays, and retirement status.
- Provide a rollback-safe mapping from VRF key hash to active pool at the
  candidate slot/epoch.
- Provide active pool stake and total active stake from the correct stake
  snapshot.
- Verify the leader threshold using VRF output, active stake ratio, and active
  slot coefficient.
- Verify op-cert sequence/counter state when the rollback-safe state is
  available.

### Fragment And Selection Requirements

- Track bounded per-peer fragments, not just isolated tips.
- Prove previous-hash continuity inside each candidate fragment.
- Prove intersection with the canonical chain within the configured rollback
  window.
- Compute density over the required comparison window before using density as a
  tie-break.
- Use VRF tie-break only after the corresponding header evidence is accepted.
- Keep peer eligibility and chain quality separate: a peer can be connected and
  hot but still not eligible to drive canonical adoption.
- Execute selected-upstream switches only through runtime generation fencing.

### Body And Ledger Requirements

- When configured, fetch the body before adoption.
- Verify body hash and body size against the header.
- Later, validate transactions and ledger transition rules before adoption when
  Yano has a rollback-safe pre-adoption ledger view.
- Canonical ledger apply remains runtime-owned; `:consensus` decides, but does
  not mutate canonical state.

## Current Yano Feasibility

Yano already has several required building blocks:

- `:consensus` contains candidate state, header fan-in, and a strategy SPI.
- `runtime` already has generation-fenced selected-upstream switching and
  canonical apply boundaries.
- Header validation is optional and pluggable through `HeaderValidator`.
- Body validation has a framework with a default no-op validator.
- `ShelleyHeaderValidator` performs structural validation, header hash checks,
  KES signature verification, KES period checks, and operational-certificate
  cold signature verification.
- Block production code already has CCL crypto-ext integrations for VRF proving,
  VRF verification, KES signing, Praos leader threshold checks, epoch nonce
  evolution, and Shelley/Praos genesis parameters.
- Genesis and epoch parameters expose `securityParam`, `activeSlotsCoeff`,
  `slotsPerKESPeriod`, `maxKESEvolutions`, protocol versions, and slot/epoch
  calculation.
- Account/ledger state stores active stake snapshots and pool stake totals.

Important gaps remain:

- `header-signature` currently does not verify the Praos VRF proof, leader
  threshold, active stake, pool VRF-key registration, or op-cert counter state.
- Pool registration persistence does not currently retain the pool VRF key hash,
  so Yano cannot map a block header's VRF key to an active pool from its own
  ledger state.
- Candidate headers do not carry validation evidence, VRF output, density-window
  facts, body availability, or a fragment/intersection proof.
- The candidate store stores individual observations, not per-peer fragments.
- The current rollback-window check does not prove that a fork intersects the
  canonical chain within the rollback window.
- Full Ouroboros Genesis-style bootstrap from arbitrary untrusted peers is out
  of scope for the current implementation; Yano still relies on trusted roots,
  snapshots, or selected peers for initial bulk sync.

Feasibility summary:

| Requirement Area | Feasibility In Current Yano | Main Missing Work |
| --- | --- | --- |
| Structural Shelley+ headers | High | Stage naming/evidence cleanup. |
| KES/opcert signature checks | High | Split current `header-signature` into named stages and expand tests. |
| VRF proof validation | Medium-high | Wire existing CCL VRF verifier to header validation with epoch nonce lookup. |
| Leader threshold validation | Medium | Persist pool VRF key mapping and expose active stake snapshot by slot/epoch. |
| Op-cert state validation | Medium-low | Persist rollback-safe op-cert counter/state. |
| Body hash/size validation | High | Add block body validator and feed result into selection gates. |
| Full ledger-block pre-adoption validation | Low-medium | Requires rollback-safe candidate ledger view, not just canonical apply. |
| Full Ouroboros Genesis bootstrap | Low | Requires separate chain database/checkpoint/trust-anchor design. |

## Decision

Create a validation-aware chain-selection design that is layered, configurable,
and library-extensible.

The default application distribution must continue to allow validation to be
fully disabled:

```yaml
yano:
  upstream:
    validation:
      level: none
      body-level: none
```

When validation is disabled, Yano must not pretend to have untrusted public-relay
consensus safety. In multi-peer relay modes, untrusted candidates without the
required validation evidence are observe-only unless the configured trust policy
explicitly allows trusted roots to drive adoption.

Out-of-box configuration should remain profile-oriented and small. Fine-grained
stage control belongs primarily in the library/builder API, with optional
advanced config only when there is a real operator need.

## Validation Layers

Validation is a pipeline. Later stages imply earlier stages unless a custom
library configuration deliberately overrides them.

### Header Layers

| Layer | Purpose | Current Status |
| --- | --- | --- |
| `none` | Accept all headers. Intended for trusted upstream indexer mode. | Implemented. |
| `structural` | Decode Shelley+ header CBOR, check shape, sizes, decoded fields, and computed header hash. | Implemented. |
| `kes-signature` | Verify KES signature over the header body and KES period bounds. | Implemented as a named stage inside the `header-signature` profile. |
| `opcert-signature` | Verify the operational certificate cold-key signature binds the hot KES key. | Implemented as a named stage inside the `header-signature` profile. |
| `vrf-proof` | Verify the header VRF proof against the VRF verification key and epoch nonce. | Not implemented. Feasible using existing CCL VRF APIs. |
| `leader-threshold` | Verify the VRF leader value is below the threshold derived from active stake and active slot coefficient. | Not implemented. Requires ledger-view stake and pool/VRF mapping. |
| `opcert-state` | Verify op-cert sequence/counter and active issuer against ledger state. | Not implemented. Requires rollback-safe ledger-view state. |
| `protocol-view` | Verify era/protocol-version/header-size constraints against the correct ledger view. | Partially available through epoch params; not wired to header validation. |

The existing `header-signature` config value remains for compatibility. It means
`structural + kes-signature + opcert-signature`; it does not imply VRF leader
eligibility until a later profile explicitly includes that stage.

### Body Layers

| Layer | Purpose | Current Status |
| --- | --- | --- |
| `none` | Skip body validation. | Implemented. |
| `body-integrity` | Decode block body, verify body hash and declared body size against the header. | Not implemented. |
| `tx-basic` | Validate transaction era/size/fee/ex-unit limits using protocol params. | Partially available in ledger-rule code; not wired to body pipeline. |
| `ledger-block` | Apply full ledger transition rules against a rollback-safe ledger view. | Not implemented as pre-adoption validation. Canonical apply remains runtime-owned. |

## Configuration Model

Application config should expose a simple profile surface:

```yaml
yano:
  upstream:
    validation:
      level: none              # none | structural | header-signature | praos-lite | praos-ledger
      body-level: none         # none | body-integrity | ledger-block
```

Proposed profile meanings:

| Profile | Header stages |
| --- | --- |
| `none` | none |
| `structural` | structural |
| `header-signature` | structural, kes-signature, opcert-signature |
| `praos-lite` | structural, kes-signature, opcert-signature, vrf-proof |
| `praos-ledger` | structural, kes-signature, opcert-signature, vrf-proof, leader-threshold, opcert-state, protocol-view |

Operational meaning:

| Use Case | Recommended Validation | Adoption Policy |
| --- | --- | --- |
| Trusted single upstream indexer | `level: none`, `body-level: none` | Single trusted peer may drive canonical state. |
| Trusted failover / HA indexer | `none` or `structural` | Only configured trusted roots may drive canonical state. |
| Relay observation near tip | `structural` or `header-signature` | Observe and score peers; do not let untrusted candidates cause rollback. |
| Experimental public relay | `praos-lite` plus quorum/trusted roots | Reject impossible crypto, but still do not treat as full Praos safety. |
| Public relay target | `praos-ledger` plus fragment/body gates | Candidate for untrusted adoption once required ledger-view providers exist. |

`praos-lite` is useful for rejecting malformed or cryptographically impossible
headers, but it is not sufficient for untrusted public-relay adoption because a
peer can still present headers from an unregistered or stake-less key.

`praos-ledger` is the first profile that can be considered a candidate for
untrusted relay-style adoption. It must fail fast at startup until all required
ledger-view providers are implemented.

Library users need a lower-level API:

```java
HeaderValidationPipeline.builder()
    .useProfile("praos-lite")
    .addValidator("custom-policy", customValidator)
    .disableValidator("opcert-state")
    .overrideValidator("leader-threshold", customLeaderValidator)
    .build();
```

Builder semantics:

- `useProfile(name)` installs the default validators for that maturity profile.
- `addValidator(id, validator)` appends a custom validator after the profile's
  default validators unless an explicit order is supplied by a future API.
- `disableValidator(id)` removes a named validator from the profile.
- `overrideValidator(id, validator)` replaces the default implementation while
  preserving the same stage id and evidence semantics.
- The pipeline result must report every accepted, skipped, overridden, and
  rejected stage so chain selection can explain why a candidate was accepted or
  observe-only.
- Application config may enable profiles; library code may compose exact stages.

The builder API is the right place for custom validators and overrides. The
application YAML should not become a long list of low-level toggles by default.

## Chain Selection Requirements

Validation evidence must be an explicit chain-selection input. Candidate headers
should be enriched with:

- peer id and trust class;
- slot, block number, block hash, previous hash;
- era/protocol version when known;
- VRF output when available;
- validation profile and accepted stages;
- rejection stage/reason when rejected;
- whether the corresponding body is available and body-integrity validated;
- received/updated timestamps.

A candidate can be adopted only if all configured policy gates pass:

1. Candidate is ahead of the current canonical tip according to the configured
   chain comparison rule.
2. Candidate fragment intersects canonical chain within the rollback window.
3. The fragment is continuous from intersection to candidate tip.
4. The candidate satisfies the configured trust policy:
   - trusted peer;
   - peer-distinct quorum for the same candidate chain;
   - validation profile accepted;
   - or a configured combination of the above.
5. If `require-body-before-adoption` is true, the candidate body is available
   and passes the configured body validation level.
6. Runtime can generation-fence the selected-upstream switch and rollback/apply
   path.

## Cardano/Ouroboros Tie-Break Direction

Yano's final target is Cardano/Ouroboros-style comparison, not `keep-current`
for relay mode.

The selector should evolve through explicit stages:

1. `longer-chain`: higher block number wins.
2. `denser-chain`: when block number is equal, compare density facts over the
   protocol-defined security/density window once fragment data is available.
3. `vrf-tiebreak`: when density is equal and VRF output is available and
   validated, lower VRF output wins deterministically.
4. `stable-fallback`: if VRF evidence is unavailable, use a deterministic
   fallback such as slot/hash/peer id, but mark the decision as lower-confidence.

`keep-current-ha-only` remains valid only for trusted HA/failover modes. It is
not valid for public relay tracking because it can leave two honest relays on
different equal-length forks longer than necessary.

## Ouroboros Genesis Scope

This ADR does not implement full Ouroboros Genesis bootstrap from arbitrary
untrusted peers.

Full Genesis-style sync requires more than tip comparison:

- a chain database or fragment store that can retain and compare competing
  historical fragments;
- density and intersection rules over genesis/security windows;
- protection against long-range and eclipse attacks;
- ledger-state forecasting at candidate points;
- verified immutable/checkpoint trust anchors or equivalent assumptions;
- peer selection and adversarial peer limits.

Yano should keep initial bulk sync conservative: trusted-single,
trusted-failover, or configured/bootstrap roots. Multi-peer fan-in should first
be made safe near tip with validation-aware selection before Yano claims fully
untrusted Genesis bootstrap behavior.

## Required Ports

The next implementation should introduce protocol-neutral ports in or below
`:consensus` and implement them in runtime:

```text
CandidateFragmentStore
CanonicalChainView
CandidateBodyAvailability
HeaderValidationEvidence
HeaderValidationPipeline
BodyValidationPipeline
PraosLedgerView
SelectionDecisionExecutor
ConsensusClock
```

Responsibilities:

- `CandidateFragmentStore`: per-peer bounded fragments, continuity, density
  facts, pruning.
- `CanonicalChainView`: read-only canonical points, rollback window, current
  tip, and header lookup by hash/slot.
- `CandidateBodyAvailability`: whether body exists and whether body-integrity
  validation succeeded.
- `HeaderValidationEvidence`: immutable result of configured validation stages.
- `HeaderValidationPipeline`: ordered validators with profile/default/custom
  composition.
- `BodyValidationPipeline`: body validators with default no-op behavior.
- `PraosLedgerView`: epoch nonce, active slot coefficient, slots/KES period,
  max KES evolutions, pool VRF mapping, active stake, total active stake,
  op-cert state, protocol version/header-size view.
- `SelectionDecisionExecutor`: runtime-only execution of selected-upstream
  switch, body fetch scheduling, rollback/apply, and generation fencing.
- `ConsensusClock`: deterministic time source for tests and stale candidate
  cleanup.

## Implementation Plan

### Phase 0: ADR And Safety Gates

- Add this ADR.
- Document profile semantics and explicitly state that `header-signature` does
  not mean full Praos validation.
- Add config validation so `trust-policy=validated` requires a validation level
  that can actually produce validation evidence.

Acceptance:

- Operators can keep `level: none` and `body-level: none`.
- Multi-peer untrusted adoption cannot be enabled by ambiguous validation names.

### Phase 1: Validation Evidence Model

- Add validation evidence records in `:consensus`.
- Extend `CandidateHeader` or add `CandidateObservation` with validation status,
  accepted stages, rejection stage/reason, era, and optional VRF output.
- Keep old constructors/adapters simple for compatibility.

Acceptance:

- Selector tests can distinguish trusted, unvalidated, structurally validated,
  header-signature validated, and rejected candidates.

### Phase 2: Fragment And Intersection Store

- Replace or extend single-header candidate storage with bounded per-peer
  fragments.
- Verify previous-hash continuity for candidate fragments.
- Verify candidate intersection against canonical chain within the rollback
  window.
- Prune by slot/window and capacity.

Acceptance:

- A single disconnected longer header cannot be adopted.
- A fork outside the rollback window is rejected or observe-only.
- Memory usage remains bounded.

### Phase 3: Deterministic Cardano-Oriented Comparator

- Add chain comparison policy with explicit confidence:
  `LONGER`, `DENSER`, `VRF_TIEBREAK`, `FALLBACK`.
- Use block number first.
- Add density comparison only after fragment density facts are available.
- Add VRF-output tie-break only after VRF output is present and accepted by the
  validation pipeline.
- Keep `keep-current-ha-only` out of multi-peer relay modes.

Acceptance:

- Equal-length forks converge deterministically when enough evidence exists.
- Missing VRF evidence is visible in the decision reason/status.

### Phase 4: Header Validation Pipeline Refactor

- Refactor current `HeaderValidator` implementation into named stages.
- Preserve `none`, `structural`, and `header-signature` behavior.
- Add library builder APIs for add/disable/override validators.
- Keep application config profile-based.

Acceptance:

- Existing configs continue to work.
- Library users can add custom rules without forking runtime.

### Phase 5: Praos VRF Proof Validation

- Add `vrf-proof` stage using existing CCL VRF verifier APIs.
- Add a read-only epoch nonce provider derived from canonical nonce state or
  persisted nonce checkpoints.
- Support TPraos/Praos era differences in nonce contribution extraction.

Acceptance:

- Tampered VRF proof is rejected.
- Missing epoch nonce fails closed for validation profiles that require VRF.
- `level: none` still bypasses the stage.

### Phase 6: Ledger-View Praos Validation

- Persist pool VRF key hash in pool registration state and history.
- Expose lookup from VRF key hash to active pool at epoch.
- Expose active pool stake and total active stake for the correct stake snapshot
  epoch.
- Validate leader threshold using VRF leader value, sigma, and active slot
  coefficient.
- Add op-cert state validation once op-cert counter/state is persisted
  rollback-safely.

Acceptance:

- A header from an unregistered or stake-less VRF key cannot satisfy
  `praos-ledger`.
- A header whose VRF output is above threshold cannot satisfy `praos-ledger`.

### Phase 7: Body Integrity Before Adoption

- Implement `body-integrity` validator: body decode, body hash, declared body
  size, and header/body consistency.
- Feed body availability/validation evidence into chain selection when
  `require-body-before-adoption` is true.

Acceptance:

- Header-only candidates cannot trigger canonical switch when body-before-adopt
  is required.

### Phase 8: Runtime Controller Integration

- Introduce a `ChainSelectionController` only after phases 1-7 define clean
  inputs and outputs.
- Keep execution in runtime through `SelectionDecisionExecutor`.
- Preserve generation fencing around selected-upstream switch and apply.

Acceptance:

- Consensus policy is unit-testable without Yaci or RocksDB.
- Runtime remains the only code that switches peers or mutates canonical state.

## Testing Plan

- Unit tests for every validation stage.
- Property-style tests for fragment continuity and rollback-window checks.
- Comparator tests for longer, density, VRF tie-break, and fallback behavior.
- Compatibility tests for `none`, `structural`, and `header-signature` config.
- Preprod/mainnet smoke tests with validation disabled, structural validation,
  and later `praos-lite`.
- Negative tests with tampered KES signature, VRF proof, previous hash, body
  hash, and invalid leader threshold.
- Restart/rollback tests proving validation evidence and nonce/ledger view do
  not advance ahead of canonical storage.

## Consequences

Positive:

- Keeps trusted-indexer mode fast and simple.
- Makes validation maturity explicit instead of hiding it behind one boolean.
- Gives library users clean custom validation hooks.
- Creates a realistic path from current conservative selection to relay-grade
  Praos-aware selection.

Tradeoffs:

- Full untrusted relay safety requires ledger-view data that Yano does not yet
  persist completely.
- More validation stages increase test matrix size.
- Full Ouroboros Genesis bootstrap remains a later, larger consensus project.

## Non-Goals

- Implement full Ouroboros Genesis bootstrap in this ADR.
- Move canonical apply or ledger state mutation into `:consensus`.
- Make validation mandatory for trusted-single/indexer mode.
- Implement block production changes.
- Claim `praos-lite` is sufficient for untrusted public-chain adoption.
