# ADR-037 Phase 4 implementation and review — 2026-09-06

Status: **implementation, self-review and local milestone validation complete**.
Ready for integration; this is not Phase 5 graduation or independent review.

Integration branch: `feat/generic-observation-framework-adr` at `300186dd1`.
Host milestone: `milestone/adr-037-phase-4`.
Companion milestone: `milestone/adr-037-phase-4` in the isolated
`/private/tmp/yano-x-adr-037-phase-3` worktree. The root Yano X checkout is not
an implementation workspace.

## Host checkpoints

- `aae48497a`: bounded signed Merkle inclusion evidence, pinned HTTPS adapter,
  local webhook wake hints, API level 7, protocol/docs/tests.
- `443fa91fa`: L1 journal finalized-cursor encoding fix and test-lifecycle fixes
  discovered while qualifying the workflow and reviewing integration CI.
- `d3adba688`: complete certified-header REST transport, shared block/COMMIT
  digest helper and API level 8; no change to block-v3 commitment bytes.

The coordinated consumer artifact version is `0.1.0-pre14-d3adba688`, with its
matching ordinary JVM ZIP. Maven Local is an explicitly enabled development
input, not evidence of remotely available/public artifacts.

Companion checkpoint `f0abb173` contains the shipment lifecycle reference,
SDK wake/current-header verification, exact host pin, guides and compatibility
fixture updates. It is a milestone checkpoint, not a qualification claim.
Companion follow-up `03face6f` pins retained-template reproduction and updates
the evidence-client header fixture without changing deployable scripts.

## Implementation boundary

The proof profile is `ed25519-merkle-inclusion-v1`, motivated by signed shipment
receipt batches. It binds the subscription parameter digest, source and value
in domain-separated leaves; branch direction is explicit and depth is bounded
to twenty siblings. The signed root envelope binds the existing round and
freshness context. It proves positive inclusion in an authorized attestor's
commitment, not physical truth, completeness, non-membership, or Cardano
settlement. ADR section 14.1 records this scoped design decision.

Wake ingress accepts only a subscription ID and schedules a best-effort local
attempt. It cannot open rounds, supply claims, extend collection, or bypass
acquisition/evidence/rate bounds. Periodic acquisition remains the fallback.
Both report and wake endpoints are explicitly classified as SUBMIT access;
the report endpoint previously inherited the more restrictive default
PRIVILEGED classification.

The companion shipment reference retains payment L1 evidence, subscription,
certified result and certificate digest, release effect identity/receipt, and
matching settlement L1 evidence in authenticated application state. Its read
query is application-specific. Shared mutable feeds remain deferred. Cadence
uses APP_HEIGHT; no wall-clock duration claim is made.

## Findings and iterations

1. Independent Blake2b-256 golden vectors were added for Merkle leaf and
   branch hashing, alongside real Ed25519 verification and malformed/bounded
   proof tests. Invalid proofs must fail before reporter journaling/signing.
2. Hints received before a round opens or after collection closes must not
   acquire. A rejected proof followed by an absent webhook must still recover
   through normal periodic acquisition. A thousand hints after completion do
   not acquire or change authenticated state.
3. Shipment callback fixtures initially used mocks whose instrumentation
   rejected Java 25 class files. Replaced them with actual protocol objects,
   canonical L1 envelopes, and recording emitters; no instrumentation bypass.
4. Integration CI at `300186dd1` passed distribution, integration and native
   jobs but failed commit-build (run `34007041933`). Two tests raced temporary
   directory cleanup against deferred ledger closure. Their terminal teardown
   now uses `close()` rather than restartable `stop()`.
5. The same CI run's pre-connect two-node diffusion test failed locally in the
   full suite. Logs showed the replay was transmitted and acknowledged before
   the receiving subsystem started: the fixture exposed its server first and
   slept one second. It now activates inbound handling before binding the
   server. The pre-connect replay assertion is retained; no timeout inflation
   or protocol-acceptance weakening was used.
6. The shipment host-runtime test finalized payment then stalled because the
   block-scoped L1 journal became unhealthy. `stageFinalized` wrote a raw record
   key, but acknowledgment and rollback decoded a length-prefixed cursor plus
   observation identity. The focused commit/reopen/acknowledge regression
   failed with `Invalid L1 observation cursor` before the fix. The writer now
   uses the same `cursorValue` format as the authenticated cursor. The test
   additionally checks duplicate acknowledgment and the retained deep-rollback
   guard after the acknowledged record is removed. This changes a node-local
   journal value, not the authenticated cursor, block/wire format, or roots.
   No retained deployment or journal was deleted/reset. Previously affected
   retained journals need separately validated recovery; this checkpoint does
   not claim an automatic migration of malformed historical cursor values.
7. Full companion regression exposed a composition fixture signing bare block
   hashes instead of COMMIT digests. Review then found the SDK itself rebuilding
   obsolete headers and verifying bare-hash signatures, while REST omitted the
   additional current-format fields. API 8 exposes the complete header and
   shared commitment helper. The updated SDK requires an independently pinned,
   height-specific consensus context and rejects obsolete headers and bare-hash
   signatures. The fixture is corrected without weakening finality verification.
8. The full companion suite reached developer tools and found another obsolete
   proof-header fixture plus stale schema golden hashes. The schema difference
   is exactly the `observation.` L1-identity namespace added by host commit
   `4326351a0`; removing only those generated entries in memory reproduces both
   previous SHA-256 goldens exactly. The goldens now pin the current registry;
   deterministic generated-resource comparisons remain enabled.
9. The next full-suite pass reached the Cardano bridge and exposed an old
   diagnostic L1 key expectation without `eventOrdinal`. The fixture now checks
   the current `observer/anchor/ordinal/slot` shape and explicitly asserts the
   batch observer's zero ordinal. No bridge implementation or L1 identity was
   changed to accommodate the fixture.
10. Running every companion task with `--continue` found a final evidence-client
    fixture using incomplete headers and settlement template/compiler drift.
    The evidence fixture now carries current headers. Settlement source and
    templates were unchanged; an earlier compiler upgrade from Julc pre14 to
    pre16 changed fresh bytecode. A read-only pre14 diagnostic reproduced all
    four retained templates exactly. A mandatory `verifySettlementArtifacts`
    test task now isolates the original compiler for byte-for-byte reproduction;
    the normal bridge tests still use current pre16 compiler/VM APIs. Both tasks
    are dependencies of normal verification. No script artifact, address,
    validator source or retained deployment was changed or regenerated.

## Validation ledger (ongoing)

- Initial bounded proof/wake focused host tests: passed; final core golden
  vector run 2 s, runtime/REST follow-up 57 s.
- `aae48497a` exact Maven Local publication and ordinary JVM ZIP: passed, 55 s.
- Four initial shipment callback cases after removing mocks: passed, 1 s.
- Full host `test` before CI fixture fixes: failed, 9 min 4 s; runtime reported
  1,504 tests, one failure (pre-connect fixture), five explicitly skipped.
  This is not a successful full-suite result.
- New committed L1 cursor regression before fix: failed, 3 s, expected cursor
  decoding failure.
- L1 journal suite plus all three CI-failing test classes after fixes: passed,
  31 s (`/private/tmp/adr-037-phase-4-host-regressions.log`).
- `443fa91fa` exact Maven Local publication and ordinary JVM ZIP: passed,
  1 min 23 s. The checkpoint is pushed on the milestone branch; CI branch
  filters do not schedule checks for milestone-only pushes.
- Corrected exact-artifact shipment tests: eight passed, 10 s, including the
  one-node host runtime, actual signed Merkle evidence, external effect
  claim/report, synthetic stable L1 events, and disk restart/root equality.
  The subsequent three-node extension and SDK wake tests are not yet recorded
  as passing here.
- Full host `./gradlew test -PskipSigning=true --no-parallel` after the cursor
  and lifecycle fixes: **passed, 11 min 21 s**
  (`/private/tmp/adr-037-phase-4-host-tests-fixed.log`).
- Follow-up shipment and SDK ingress tests: **passed, 26 s**, including one
  and three real N2N-connected host nodes, quorum certificates, proof retrieval,
  identical roots/profiles/manifests, full-cluster disk restart parity, the
  pre-receipt candidate bound, post-receipt direct matching, and strict bounded
  report/wake receipts. L1 block events and the external executor are synthetic
  fixtures; no Cardano transactions were submitted.

- API-8 core tests and focused REST proof tests: passed, 27 s. Shared header
  hashes and COMMIT digests match full blocks across views; existing core golden
  tests pass (`/private/tmp/adr-037-phase-4-header-tests.log`).
- `d3adba688` exact Maven Local publication and ordinary JVM ZIP: passed,
  1 min 12 s (`/private/tmp/adr-037-phase-4-api8-publication.log`).
  The earlier full-host pass precedes this API-8 checkpoint.
- API-8 companion SDK, composition-client and shipment tests: passed, 30 s
  (`/private/tmp/adr-037-phase-4-api8-consumer-tests.log`). The one/three-node
  workflow now verifies actual host state proofs through the SDK and rejects
  a wrong context pin. Parser cases reject missing context/view/proposer/
  justification fields and obsolete versions; cryptographic cases reject
  bare-hash signatures. An additional legacy composite proof fixture was
  updated to the complete header shape after its expected strict-parser failure.
- API-8 packaged JVM plugin-catalog smoke: passed, 42 s
  (`/private/tmp/adr-037-phase-4-api8-jvm-catalog.log`).
- API-8 GraalVM native ZIP and native plugin-catalog/health smoke: passed,
  2 min 39 s (`/private/tmp/adr-037-phase-4-api8-native-catalog.log`). Native
  catalog fingerprint: `sha256:9e0509f1ab3d12f82c48616501d09a67df92a183c3715dff3dcc9d4ef00fd719`.
- Retained settlement artifact reproduction under original compiler: passed,
  1 s. Mandatory isolated reproduction plus current bridge conformance and
  evidence-client regressions: passed, 4 s
  (`/private/tmp/adr-037-phase-4-artifact-proof-regressions.log`).
- Full companion `test verifyArtifactInventory verifyJvmOnlyBuild --continue`:
  **passed**, incremental final gate run 1 s, 223 tasks (221 already validated
  up-to-date tasks), 52 inventoried artifacts and 18 runtime bundles
  (`/private/tmp/adr-037-phase-4-api8-yano-x-all-gates-fixed.log`). Earlier
  failing passes and focused reruns above are retained, not reported as passes.
- Exact companion `0.1.0-adr037-03face6f` distribution/plugin pack built and
  `distributionCheck` passed against ordinary host JVM ZIP
  `0.1.0-pre14-d3adba688`, 1 min 7 s
  (`/private/tmp/adr-037-phase-4-api8-distribution.log`).

## Milestone review and handoff

The Phase 4 deliverables are implemented: bounded signed Merkle evidence,
non-authoritative wake hints with periodic fallback, authenticated lifecycle
evidence linking, and the payment/shipment/release/settlement reference. Its
application-specific read view covers the optional view requirement without
introducing a shared mutable-feed protocol. Tests retain the distinction between
attestor assertions, effect receipts and host-validated Cardano facts.

Review checked canonical commitments, bounds, trust pinning, report/wake access,
duplicate and overflow behavior, restart identity/root parity, exact plugin
compatibility and preservation of existing settlement artifacts. This is
implementation self-review, not the independent review required for graduation.

Phase 5 Preprod, remote artifact availability, soak, independent review and
all-green remote CI remain separate graduation gates. Preview and
disabled-by-default status is unchanged. Previously malformed local L1 cursors
also require a separately validated recovery procedure; no retained data was
rewritten. The prior integration CI failure must be rechecked after merging;
local success is not substituted for remote CI.
