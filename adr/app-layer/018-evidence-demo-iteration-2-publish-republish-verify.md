# ADR-018: Evidence Demo Lifecycle — Publish, Republish, Replay, and Verify

## Status

Accepted — lifecycle and bounded full-workflow load implemented and validated

The number is local to the `adr/app-layer` series. Root-level ADR-018, if one
exists, is unrelated.

## Date

2026-07-18

## Parent and related decisions

- [ADR-013](013-first-party-integration-connectors-and-effect-demo.md) owns the
  accepted first-party connectors and first no-code evidence scenario.
- [ADR-013.2](013.2-deterministic-composite-state-machine.md) owns the stock
  gated composite profile used by the demo.
- [ADR-010](010-deterministic-effect-system.md) owns deterministic effects and
  exactly-once result incorporation over at-least-once execution.
- [App-layer open items](open_item.md) records the completed ADR in its
  inventory; delivery was tracked as `INT-010` while active.

## 0. In plain words

The first demo proves one complete evidence workflow and deliberately replays
the same workflow on a repeated `demo.sh run`. The replay is safe: new command
envelopes finalize, but the authenticated evidence record, state root, three
effect identities, archived object, IPFS pin, and Kafka event remain unchanged.

That behavior is useful for an idempotency acceptance test, but it is not the
best default operator experience. A user naturally expects to be able to:

1. publish another evidence item while the three nodes keep running;
2. publish immutable version 2 of an existing item;
3. verify an existing item without submitting a message or advancing the tip;
4. request an explicit replay only when testing idempotency; and
5. publish a bounded set of independent evidence IDs through the complete
   workflow under controlled concurrency.

Iteration 2 adds those operations to the no-code launcher and runner. It also
closes an omission in the unreleased stock `evidence-v1-gated` profile: the
registry already understands the frozen `REPUBLISH` command, but the gated
release workflow admitted only `SUBMIT`. Because Yano is still in preview, the
profile has not been merged or released, and its databases are disposable, we
correct that profile in place before declaring v1 complete. It does not require
a new effect type, core runtime change, or evidence wire shape.

## 1. Observed gap

On 2026-07-18 a retained demo had one authenticated record:

```text
evidenceId       inspection-2026-0716
business version 1
status           READY
effects          object.put, ipfs.pin, kafka.publish
state root       2d5b93d59a25d9f57b676607806b8211548560ad4a1bfdafa54cdb74c158bea4
```

The sample was changed from batch `BATCH-2026-0716` to
`BATCH-2026-0718`, but the generated runner configuration still selected
`inspection-2026-0716`. `demo.sh run` returned
`EXTERNAL_STATE_MISMATCH`. Four gated-workflow envelopes finalized and moved
the tip from 15 to 19, while the state root stayed unchanged and no new effect
was emitted.

This is consensus-correct. The existing version-1 record is immutable and the
changed bytes are not an exact replay. The demo UX is nevertheless deficient:

- `evidence-id` is generated during `up`, so another item requires a node
  stop/start merely to change scenario input;
- `run` conflates first publication, explicit replay, and verification;
- the runner stages bytes and computes an unpinned CID before it discovers a
  retained-record mismatch;
- changed bytes for an existing ID produce the broad
  `EXTERNAL_STATE_MISMATCH` instead of directing the user to `REPUBLISH`;
- version 2 is implemented in the state machine and contracts but is not
  exposed by the demo CLI; and
- the stock gated release wrapper accidentally narrows its embedded evidence
  command to `SUBMIT`, despite the evidence-v1 registry defining and testing
  `REPUBLISH`; and
- the report UI shows the latest scenario run without clearly separating
  business-record versions, scenario-run history, and finalized no-op replay
  envelopes.

The failed attempt did not alter authenticated evidence or create connector
effects. The mutable staging object may contain the newly supplied bytes;
archive versions, authenticated receipts, IPFS pins, and Kafka records remain
bound to the accepted version.

## 2. Goals and non-goals

### 2.1 Goals

- Publish multiple evidence IDs without restarting Yano or connectors.
- Republish an existing ID as the exact next immutable business version.
- Verify latest or historical evidence without external writes, app messages,
  new blocks, effects, or anchors.
- Preserve an explicit replay operation for deterministic-idempotency tests.
- Detect identity/content/version conflicts before staging bytes or submitting
  commands.
- Keep Compose and normal/host deployment behavior identical.
- Make reports and UI distinguish operations, versions, state changes, no-op
  replays, and scenario-run history.
- Keep the latest publication in a stable overview while an independently
  selected evidence/version remains open, and show a bounded exact JSON copy
  only after the runner has verified both the immutable object and IPFS bytes.
- Keep every record created by the completed v1 profile immutable and preserve
  historical proofs across later publications and retained restart.
- Provide a bounded load/soak command that measures complete, independently
  verified publications rather than only HTTP submission throughput.

### 2.2 Non-goals

- Mutating or deleting an accepted evidence version.
- Turning the reference scenario into a generic workflow engine.
- Adding a new connector or effect type.
- Changing frozen evidence/effect CBOR or state-key encodings.
- Preserving the current preview profile digest or disposable demo databases;
  they predate the complete v1 business lifecycle.
- Claiming that the inspection statement is true; the demo proves finalized
  authorization and connector observations only.
- Solving regulated-data erasure or encrypt-before-staging policy.

## 3. CLI decision

Scenario inputs become operation-scoped arguments. Deployment configuration
continues to own endpoints, credentials, connector targets, chain identity,
membership, and the composite profile, but it no longer fixes one business
evidence ID for the lifetime of a running deployment.

### 3.1 Commands

```bash
# A new immutable version-1 record; the ID must be absent.
./demo.sh publish \
  --instance manual-demo \
  --evidence-id inspection-2026-0718 \
  --sample-file /absolute/path/inspection-0718.json

# Exact next version of an existing record; the prior version must be terminal.
./demo.sh republish \
  --instance manual-demo \
  --evidence-id inspection-2026-0718 \
  --business-version 2 \
  --sample-file /absolute/path/inspection-0718-correction.json

# Read-only verification of latest or one exact historical version.
./demo.sh verify \
  --instance manual-demo \
  --evidence-id inspection-2026-0718 \
  --business-version latest

# Explicitly prove that the same accepted command is a deterministic no-op.
./demo.sh replay \
  --instance manual-demo \
  --evidence-id inspection-2026-0718 \
  --business-version 2 \
  --sample-file /absolute/path/inspection-0718-correction.json
```

Every command also accepts the existing network, deployment, instance, and
data-root selectors. `publish`, `republish`, `verify`, and `replay` require an
already running instance and never prepare or start it implicitly.

`--business-version` is explicit for `republish` and must equal
`latestVersion + 1`. A conflict returns the stable `VERSION_CONFLICT` code; the
runner never silently chooses a version after external work has begun.

### 3.2 Existing `run` command

While Yano remains in preview, change `run` into the safe guided operation:

- absent evidence ID: behave as `publish`;
- existing evidence with byte-identical expected inputs: behave as `verify`;
- existing evidence with different bytes or destinations: fail before external
  mutation with `REPUBLISH_REQUIRED` and print the expected next version.

The old tip-advancing behavior moves to explicit `replay`. Documentation and
acceptance tests must not continue describing a repeated `run` as the replay
test after this change.

### 3.3 Bounded `load` command

```bash
./demo.sh load --instance lifecycle-demo \
  --count 100 --concurrency 4 --id-prefix july-inspections \
  --sample-file samples/inspection-certificate.json
```

`load` is a batch of normal version-1 `publish` operations, not a privileged
bulk-write path. It freezes these limits:

- `count` is 1 through 50,000;
- `concurrency` is 1 through 16 and cannot exceed `count`;
- `id-prefix` is explicit and produces stable IDs
  `<prefix>-000001` through `<prefix>-NNNNNN`;
- one launcher operation lock covers the complete run, while one isolated
  Yano/S3/IPFS/Kafka client environment is owned by each worker;
- the bounded launcher copy of one sample is read-only input to every item;
- every item performs normal absence preflight, gated publication, finality,
  effect completion, external re-read, proof verification, and reporting; and
- reusing a prefix is not an overwrite or resume operation: existing IDs fail
  with `EVIDENCE_ALREADY_EXISTS`.

Concurrency means independent end-to-end client workflows, not unrestricted
co-batching of their consensus messages. ADR-020 supersedes the original
one-release allocation with committed `evidence-capacity-per-block`. `load`
therefore shares two fair, capacity-sized load-only lanes across workers:

- a release lane covers the quota-sensitive prerequisite/release submissions
  through release finality; and
- an independent notification lane covers notification submission through
  notification finality.

Each permit remains held through that command's finality, so at most the
profile's authenticated capacity can be pending for one block. The two lanes
can overlap each other. Staging, connector execution, result
incorporation, external re-reads, proof verification, reporting, and other
workers remain concurrent. Ordinary `publish`, `republish`, `verify`,
`replay`, and guided `run` do not use these load-only lanes. The normal
`block.max-messages=64` configuration is unchanged.

When devnet storage effects are gated on `L1_ANCHORED`, all load workers share
a throttled coordinator that invokes Yano's existing `force-anchor` admin
operation while awaiting effect eligibility or final anchor coverage. This
prevents a finite load from waiting for unrelated future app messages to
trigger the next threshold anchor. It adds no state-machine, runtime, or
framework API. The runner treats each trigger as best effort and still fails
closed at its normal deadline if members do not observe a valid covering
anchor.

The command reports requested/succeeded/failed counts, duration, successful
end-to-end publications per second, latency min/p50/p95/max, bounded failure
counts, and at most 32 failure samples. ADR-020's optional pipeline mode adds
bounded stage queues, stage-specific failure/rate/latency data, and separate
app-message/effect/verified-workflow throughput. Individual scenario reports remain
available under the ordinary report history, whose UI view is intentionally
bounded to the newest 20. A separate latest-load report preserves the aggregate
and is exposed read-only at `/api/v1/load/latest`.

This is a functional load/soak tool, not a scientific benchmark. Client
construction, quota-safe workflow coordination, finality, connector
acknowledgement, and independent verification are included in measured
latency. It does not bypass the state machine or claim raw consensus-message
capacity.

The iteration-2 lifecycle implementation remains the default. See
[ADR-020](020-pipelined-evidence-load-and-committed-workflow-capacity.md) for
the committed multi-release capacity and optional pipeline execution model.

Load v1 is refused for public-network anchor-enabled profiles. A high-count
command must not implicitly create or spend many preview/preprod anchor
transactions. Devnet may exercise its default scripted anchor, while APP_FINAL
load testing remains valid on explicitly configured public relay profiles
without anchoring.

## 4. Operation preflight and ordering

The runner must complete this sequence before any connector write or app-chain
submission:

1. acquire the per-instance scenario-operation lock;
2. validate and bounded-read the operation input and compute its digest;
3. obtain a proof-verified evidence query at one committed root;
4. confirm three-member chain/profile agreement;
5. enforce operation-specific absence, ownership, terminal-status, version,
   content-digest, and configured-destination rules;
6. only for an accepted new version, compute its CID and complete the connector
   commands and destination fingerprints;
7. for replay, reconstruct the exact retained storage command without staging
   or adding the input to IPFS.

If the query/proof surface is unavailable or members disagree, fail closed.
Absence is never inferred from a timeout or inaccessible member.

The only pre-proof bootstrap case is a pristine chain before its first app
block, because the authenticated composite-profile marker is itself first
written during block application. At height 0, all three members must report
the same expected state machine, height 0, and the all-zero pre-genesis root.
For a composite, each node must also return canonical active-profile bytes at
that exact snapshot whose domain-separated digest equals the launcher's pinned
profile digest. No app block means no component state can exist. Any nonzero
height requires the normal MPF profile and non-inclusion proofs. This exception
cannot be used for a retained chain.

Only after preflight succeeds may the runner stage the object, interact with
Kubo, or submit the gated workflow. A mismatch must therefore leave staging,
IPFS, Kafka, the app-chain tip, and the report's referenced accepted state
unchanged.

Inputs must be bounded regular files, never symlinks, and must pass the same
strict path/ownership/UTF-8 or opaque-byte rules in Compose and host mode. The
launcher copies the validated input into a private, operation-scoped runtime
location or mounts only that exact canonical file read-only; arbitrary host
paths are not exposed to a container.

New publications use a version-explicit relative object key:

```text
<evidence-id>/v<business-version>/inspection-certificate.bin
```

Replay reconstructs the exact stored command for the requested immutable
version rather than silently migrating it. Chains prepared with the earlier
incomplete preview profile are deliberately recreated because the corrected
v1 profile has a different committed digest.

## 5. Publish and republish semantics

### 5.1 Publish

`publish` requires a proof-verified absent evidence head and creates business
version 1 through the existing gated composite sequence:

```text
registry prerequisite
  -> exact command proposal
  -> approval
  -> evidence.release.v1
  -> object.put + ipfs.pin
  -> incorporated storage results
  -> NOTIFY or activated direct continuation
  -> kafka.publish
  -> READY
```

If the ID exists, `publish` returns `EVIDENCE_ALREADY_EXISTS`; it never turns
the request into a replay or republish implicitly.

### 5.2 Republish

`republish` constructs the frozen `RepublishEvidenceCommandV1` and routes that
exact command through the corrected stock gated approval/release workflow. The
release envelope's wire shape already contains a bounded encoded evidence
command, so it is broadened from accepting only `SUBMIT` to accepting the two
storage commands, `SUBMIT` and `REPUBLISH`; `NOTIFY` remains on its dedicated
gated route. It succeeds
only when:

- the evidence head exists;
- the caller uses the same authenticated owner identity as the head;
- the requested version is exactly `latest + 1`;
- the prior immutable version exists and has a terminal status that permits
  republishing; and
- all new connector commands and destination fingerprints are canonical.

Version 2 emits new version-scoped object and IPFS effects, followed by a new
version-scoped Kafka notification. Version 1 remains independently queryable
and proof-verifiable; latest moves atomically to version 2.

## 6. Verify and replay semantics

### 6.1 Verify

`verify` is strictly read-only with respect to Yano and external systems. It:

- proof-verifies the head, requested immutable version, active composite
  profile, effect records, and terminal results at a bound finalized root;
- re-reads the exact archived object version and bytes;
- verifies the exact CID content and required pin state;
- audits the Kafka record, reserved headers, partition, offset, and effect ID;
- verifies finality and the applicable Cardano anchor; and
- writes a credential-free local verification report.

It must not stage input, add content to IPFS, submit any message, requeue an
effect, publish Kafka data, request or force an anchor, or advance the
app-chain tip. The
acceptance test snapshots all those surfaces before and after verification.

### 6.2 Replay

`replay` is the only command whose purpose is to finalize a new envelope for an
already accepted command. It first proves that the supplied input and resolved
destinations reconstruct the exact accepted command. It then submits the
gated workflow and verifies:

- tip/messages may advance;
- authenticated state root and business head/version do not change;
- no new effect identity is emitted;
- no new archive version, IPFS mutation, or logical Kafka event is created;
  and
- the report labels the operation `REPLAY_NOOP` rather than another
  publication.

## 7. Errors and reporting

Replace ambiguous user-facing mismatch failures with stable operation errors:

| Code | Meaning |
|---|---|
| `EVIDENCE_ALREADY_EXISTS` | `publish` selected an existing ID. |
| `EVIDENCE_NOT_FOUND` | `republish`, `verify`, or `replay` selected an absent ID/version. |
| `REPUBLISH_REQUIRED` | Existing ID is bound to different content or destinations. |
| `VERSION_CONFLICT` | Requested republish version is not exact `latest + 1`. |
| `OWNER_MISMATCH` | The submitting identity does not own the evidence head. |
| `PRIOR_VERSION_NOT_TERMINAL` | Latest version cannot yet be republished. |
| `REPLAY_INPUT_MISMATCH` | Replay input does not reconstruct the accepted command. |
| `MEMBER_STATE_DISAGREEMENT` | Members do not expose one safe preflight root/profile. |

`EXTERNAL_STATE_MISMATCH` remains appropriate for a post-acceptance discrepancy
between authenticated receipts and independently observed connector state; it
is not used for a predictable publish-versus-republish choice.

Reports add:

- operation: `PUBLISH`, `REPUBLISH`, `VERIFY`, or `REPLAY_NOOP`;
- requested and latest business versions;
- preflight and final committed height/root;
- whether authenticated state changed;
- submitted envelope and emitted-effect counts;
- historical object/CID/Kafka identities; and
- a clear separation between business evidence and scenario-run identity.

The UI lists retained evidence identities and immutable business versions
separately from scenario activity. The main overview always follows the newest
publication; selecting an older version opens a persistent detail modal that
is not overwritten by polling. After object-version and IPFS byte verification,
the runner stores an owner-readable presentation copy bounded to 256 KiB. The
credential-free UI pretty-prints valid JSON and the browser hashes the exact
displayed UTF-8 text against the report. The UI service receives no connector
or Yano administrator credentials. Reports created before this addition remain
usable; a read-only `verify` materializes their presentation copy.

## 8. Compatibility and architecture impact

- No core-api, effect-runtime, plugin-SPI, connector wire, or evidence command
  encoding change is expected.
- The executable semantics and therefore trust-root digest of the unreleased
  `evidence-v1-gated` consensus profile change before its first release. The
  old preview digest is not supported as a production compatibility target.
- Existing preview/demo databases using the incomplete digest must be removed
  and recreated. No released chain migration is required.
- Existing evidence CBOR, keys, records, proofs, and effect receipts remain
  frozen.
- `evidence-v1-gated` remains the stock profile name; creating an artificial
  `evidence-v2-gated` profile would preserve an unreleased omission and is
  explicitly rejected.
- The runner and launcher gain operation-scoped input; generated deployment
  configuration remains credential- and endpoint-scoped.
- Compose and host deployments use the same runner commands and validation.
- The release component must preflight both submit and republish business
  invariants before appending the document trail, so an invalid republish is an
  atomic deterministic no-op rather than a partial workflow update.

## 9. Delivery phases

| Phase | Scope | Exit condition |
|---|---|---|
| 18.1 | Correct the unreleased gated v1 lifecycle and add submit/republish consensus tests | The same approved release route atomically accepts valid v1 and exact-next-version commands and rejects invalid updates as no-ops. |
| 18.2 | Operation-scoped IDs/files, preflight, stable errors, and read-only `verify` | Existing record verifies with zero tip, state, effect, or connector mutation. |
| 18.3 | Multiple `publish` operations without node restart; safe `run` compatibility | Two IDs with distinct bytes become independently READY in one retained cluster. |
| 18.4 | Gated `republish` and historical-version verification | Version 2 becomes latest while version 1 remains byte/proof stable. |
| 18.5 | Explicit `replay`, report history, and UI distinction | Replay advances envelopes only and is visibly labelled a no-op. |
| 18.6 | Crash/fault, retained restart, Compose/host parity, docs and release review | All acceptance gates below pass on the packaged tree. |
| 18.7 | Bounded full-workflow load and aggregate reporting | Controlled concurrency publishes independent IDs, partial failure is truthful, and repeated prefixes cannot mutate retained evidence. |
| 18.8 | Stable latest overview, server-paginated/searchable evidence library, persistent detail modal, and externally verified JSON presentation copy | Selecting any retained evidence/version remains stable across polling/page changes and displays content only after object/IPFS integrity checks. |

## 10. Acceptance gates

1. Preserve the frozen evidence command bytes and prove the corrected gated v1
   profile handles submit, republish, replay, and invalid transitions
   deterministically across restart/replay.
2. Publish two evidence IDs with distinct documents without stopping nodes.
3. Republish one ID from version 1 to 2; query and verify both exact versions.
4. Reject version skips, stale versions, non-owner updates, non-terminal prior
   versions, malformed inputs, symlinks, oversized files, and destination drift.
5. Changed bytes for an existing ID fail before staging, IPFS, messages,
   effects, Kafka, anchors, or tip changes.
6. `verify` leaves app-chain tip/root, effect count, object versions, IPFS pin
   state, and Kafka offsets unchanged and never requests an anchor;
   independently scheduled background anchoring is outside the command's
   mutation boundary.
7. `replay` may add finalized envelopes but leaves business state and external
   logical outcomes unchanged.
8. Crash after each external/consensus boundary reconciles to one version and
   one terminal tuple per effect.
9. Retained restart preserves all historical versions and operation reports.
10. Compose and host mode produce equivalent authenticated and external
   evidence.
11. UI and docs explain business records versus blocks/messages versus scenario
    reports without requiring source-code knowledge.
12. Load limits and generated IDs are validated before any managed state or
    external connection is created.
13. A successful concurrent load verifies every requested item end to end and
    leaves all members at one height/root with the exact evidence set.
14. A partial load exits nonzero, retains every individual attempt report, and
    writes a bounded credential-free aggregate with stable failure codes.
15. Reusing a completed prefix produces only `EVIDENCE_ALREADY_EXISTS` failures
    and does not change tip, root, effects, object versions, IPFS pins, or Kafka
    offsets.
16. The same runner implementation serves Compose and host modes; load is
    rejected for a public anchor-enabled retained profile.
17. The Evidence Explorer keeps latest publication state separate from the
    selected version, rejects unsafe content paths, never renders untrusted
    HTML, and refuses to display a missing, oversized, non-JSON, or
    digest-mismatched presentation copy.
18. The retained evidence catalog is fetched in bounded newest-first pages of
    20 with strict server-side filtering and Previous/Next navigation; the
    latest overview remains global while browsing or filtering older pages.

ADR-018 is accepted because all phases are implemented, reviewed, tested,
documented, and demonstrated against retained three-member clusters.

## 11. Implementation and verification evidence

All eight delivery phases are complete. The accepted implementation keeps the
profile name `evidence-v1-gated`; its workflow product version and committed
profile digest changed before first release. No `evidence-v2-gated` profile or
migration compatibility was introduced for the incomplete preview profile.

The automated verification run on 2026-07-18 included:

- the complete `appchain-composite-contracts`, `appchain-composite`, and
  `appchain-evidence-demo-runner` test suites;
- restart and snapshot conformance at every publish/terminalize/republish
  boundary, including an exact replay whose finalized envelope changes neither
  the state root nor effect inventory;
- atomic no-op checks for premature republish, a non-owner release, a version
  skip, conflicting replay, malformed commands, and unsafe sample-file bounds;
- `demo-launcher-test.sh`, including lifecycle argument and cleanup contracts;
- `compose-contract.sh` and `deployment-parity-contract.sh`; and
- the existing ADR-013 connector/fencing, crash-reconciliation, and
  Compose/host parity suites, which remain unchanged because ADR-018 adds no
  connector, executor, effect-runtime, or deployment-mode implementation.

The bounded-load amendment was then exercised on a fresh retained
three-member Compose cluster with the normal 64-message block cap. A six-item
run with concurrency three completed six independently verified publications
in 84,991 ms. Each generated ID (`e2e-load-final2-000001` through
`e2e-load-final2-000006`) reached `READY`; every per-item report and the
aggregate report was `PASS`; all members converged at height 36 with root
`689b3d2c9d22a9f2af6820e77bff727e417c3770cd167770bbe2ed9ad0cf386b` and
anchor height 36; and no proposer-tick failure was observed. Repeating the
same prefix returned six `EVIDENCE_ALREADY_EXISTS` failures in a bounded
aggregate, exited nonzero, and left height, root, and anchor unchanged. This
demonstrates both quota-safe concurrent workflow progress and immutable-prefix
rejection without a bulk consensus path.

An isolated retained three-member Compose cluster then demonstrated:

1. publication of `inspection-product-a` version 1;
2. publication of `inspection-product-b` version 1 without a restart;
3. guided `run` selecting read-only verification for unchanged bytes;
4. rejection of changed bytes as `REPUBLISH_REQUIRED` with unchanged tip/root;
5. republishing product A as exact version 2 while version 1 remained
   independently proof- and byte-verifiable;
6. explicit version-2 replay advancing one finalized envelope while preserving
   the authenticated root and Kafka end offset;
7. retained restart followed by successful verification of product A versions
   1 and 2; and
8. rejection of a version skip, mismatched replay bytes, and publishing an
   existing ID, with all three members retaining the same height and root.

This E2E run also found and closed a height-zero bootstrap edge. Before the
first application block, the composite evidence component has no MPF namespace
yet. The runner now accepts absence at pristine genesis only after every member
reports height zero and the empty root and the authenticated composite-profile
query matches the deployment-pinned profile digest. From height one onward,
normal proof-verified evidence queries are mandatory.
