# ADR-037 Phase 0 implementation report

Date: 2026-09-04

Milestone branch: `milestone/adr-037-phase-0`

Integration branch: `feat/generic-observation-framework-adr`

## Outcome

Phase 0 freezes the generic-observation v1 contract without enabling external
acquisition or production scheduling.

- Added strict fixed-array deterministic-CBOR contracts for definitions,
  subscriptions, rounds, reports, certificates, results, profiles, and ticks.
- Added domain-separated definition, profile, subscription, report,
  certificate, result, and reporter-set identities.
- Added pure evidence, signature, reconciliation, certificate-verification,
  and result-admission contracts.
- Added exact-value quorum policy and explicit quorum safety/liveness checks.
- Added the exact observation state/topic namespaces and fail-closed handling
  for unknown reserved topics.
- Added backward-compatible observation-aware state-machine callbacks and a
  rejecting emitter while the canonical profile is disabled.
- Added `observationProfileDigest` to `ConsensusContext` v3 and the mandatory
  height-1 `~yano/obs/profile/v1` marker.
- Added `SystemInputKernel` as the production/conformance integration seam.

## Frozen review decisions

Phase 0 resolved all section 24.1 questions in the ADR: malformed result inputs
reject before state lookup; result/tick inputs use positive durable member
sequences; the tick is only a verified-L1-slot wake hint; L1 high-water uses a
committed maximum; API anchor and app-height bounds are explicit; cancellation
is audit-only; and provider identity versus routing settings are classified.

## Adversarial review and iteration

The implementation review found and fixed:

1. Reporter-set digests were initially opaque. Verification now recomputes the
   digest from the pinned authorized keys and checks the round reporter count.
2. The first round model checked counts but not quorum intersection/liveness.
   It now enforces `2q-n>f`, `q<=n-f`, `2r-p>g`, and `r<=p-g`, including the
   `n=5,q=4,f=1` model case.
3. A lower report threshold could initially be accepted without uniqueness.
   The definition now commits to certificate-local uniqueness and verification
   requires it whenever `r<q`.
4. Reporter uniqueness was initially global. It is now per `(reporter, source)`,
   preserving the Phase 3 two-stage reporter-per-source model while rejecting
   equivocation within one source.
5. Source identity and policy configuration were under-specified in the first
   codec draft. Definition identity now separately commits to source schema,
   logical source configuration, fixed policy parameters, and selectable-policy
   schema/bounds.
6. Existing hand-built engine test roots omitted the new disabled-profile
   marker. The fixture now models the intentional fresh-chain cutover.

## Verification

- Canonical disabled profile bytes, digest, and height-1 state root are golden.
- Every v1 record round-trips through a strict canonical decoder.
- Cross-record conformance digests are golden-tested.
- 5,000 deterministic malformed-input iterations exercise every decoder.
- Tests cover signature-field binding, cross-round replay, wrong reporter set,
  insufficient source diversity, duplicate/equivocating reports, different
  sufficient subsets with one result identity, stale no-op precedence, and
  active-round conflicting-result rejection.
- `:core-api:test` passes.
- `:runtime:test` passes (with the repository's existing skipped tests and JVM
  deprecation/native-access warnings).
