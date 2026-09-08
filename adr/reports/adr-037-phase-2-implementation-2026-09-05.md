# ADR-037 Phase 2 implementation report

Integration: `feat/generic-observation-framework-adr`.
Milestone: `milestone/adr-037-phase-2`.
Status: implementation, implementation self-review and milestone validation complete.

## Delivery

- Version-selected recurring scheduling, retained report-window offsets,
  first-VALUE completion, bounded sequential backlog and deadline clipping.
- Verified L1-slot high-water commitments and a bounded durable member
  heartbeat outbox; hint bodies are never clock authority.
- Explicit open-round and application/definition quota indexes, with atomic
  finalized-block staging and constant-time per-block quota updates.
- Startup integrity checks and offline replay/install tooling that preserves
  the original node's signing and consensus safety state.
- Profile-compatible membership activation, including old-round reporter
  retention and new-round quorum selection.
- Bounded worker/coordinator queues, byte admission and request throttling;
  local readiness/status and root-fixed audit queries.
- [Preview operator guide](../../docs/appchain/observations.md).

## Design/review findings and iteration

1. The v1 subscription did not retain a distinct recurring report window.
   V2 appends that field, selected jointly with round-rules v2. V1 encodings
   and the disabled-profile golden vector are preserved.
2. Advancing recurrence from current time would skip backlog. The scheduler
   now advances exactly one cadence from the previous scheduled due.
3. A completed recurring round remained in the committed open-round scan
   during the same block. The expiry sweep now checks staged round identity
   and pending/open status before acting.
4. Scanning all historical rounds/subscriptions was not scale-safe. Explicit
   indexes and point counters replace those scans in the hot path.
5. The application quota originally counted only creations in the current
   block. It now counts committed active subscriptions plus staged changes.
6. Creating 100,000 subscriptions in one block exhausted the default test JVM
   heap. V2 now has a deterministic 1,024-creation block cap; qualification
   accumulates 1,000 records per block over 100 blocks. Quota accounting was
   also changed from repeated scans to cached delta updates.
7. Definition-pinned initial keys prevented membership evolution. V2 selects
   the active-members rule and pins actual keys/quorum per opened round.
   Admin and governed changes must preserve the observation safety envelope.
8. A replay destination lacks the original node's pending vote/signing locks.
   It is now an explicitly non-runnable repair artifact. Installation replaces
   only the observation indexes in the original ledger, with a crash fence.
9. An unbounded executor queue and count-only coordinator admission could
   accumulate too much work. Worker queues, coordinator count/bytes and
   node/definition request rates are now independently bounded.
10. Acquisition may finish after a round closes. The worker rechecks committed
    eligibility before acquisition, after acquisition and before signing.
11. GitHub distribution/native smoke checks still expected plugin API level 4
    after Phase 1 introduced level 5. Their shared assertion is corrected.
    Two Linux tests also raced temporary-directory cleanup against restartable
    `stop()`; terminal fixture teardown now uses `close()` and drains resources.

## Validation evidence

Passed during the milestone:

- Core codec/identity tests, including v1 vectors and v2 truncation/framing.
- Observation kernel/runtime/journal/HTTPS/network regression suites.
- Recurrence across restart, first-VALUE completion, clipped final deadlines,
  sequential expired backlog, and verified-slot zero-reference retention.
- Forged future tick bodies cannot advance the high-water clock; excessive
  ticks reject before state commit.
- Five-member `n=5,q=4,f=1` round, different sufficient report subsets,
  retirement of an old reporter, and a new `n=6,q=5,f=1` round.
- Four abrupt child-process exits (`Runtime.halt`, no graceful RocksDB close):
  unsigned candidate durable, signed-before-report, report durable, and ready
  certificate durable. Restart preserves the exact signing choice.
- Nine finalized-block fault boundaries, asserting all-or-none round/index
  visibility and commitment integrity after restart.
- Missing due-index detection, root-matching offline replay and repair install.
- Opt-in 100,000-record committed MPF/RocksDB creation/recovery qualification:
  `YANO_OBSERVATION_SCALE=1 ./gradlew :runtime:test --tests '*ObservationKernelTest'`
  passed in a 20-second Gradle run without increasing the test heap.

The full repository suite passed in 11m 38s (124 actionable tasks), and the
live raw-HTTPS/signed-attestation three-node regression passed in 18s. The
post-CI-fix JVM distribution/catalog smoke and affected lifecycle regressions
passed in 1m 22s. The native distribution/catalog smoke passed in 2m 16s
using Oracle GraalVM 25.3.4.1+1.1 on macOS arm64. The final kernel/three-node
attestation regression passed in 18s, including on-time acquisition followed
by omitted proposals until the inclusive final grace height. The opt-in scale
and raw-HTTPS tests were skipped in that final focused run; their separate
successful runs are recorded above. `git diff --check` passed.
No five-node Preprod or independent review
claim is made by this milestone; those are Phase 5 gates.
