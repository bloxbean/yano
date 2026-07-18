# ADR-020: Pipelined Evidence Load and Committed Workflow Capacity

## Status

Accepted and implemented

The number is local to the `adr/app-layer` series. Root-level ADR-020, if one
exists, is unrelated.

## Date

2026-07-18

## Parent and related decisions

- [ADR-013](013-first-party-integration-connectors-and-effect-demo.md) owns the
  connector contracts and the first no-code evidence scenario.
- [ADR-013.2](013.2-deterministic-composite-state-machine.md) owns committed
  component/workflow effect quotas and the stock evidence composite.
- [ADR-016](016-authenticated-appchain-consensus-profile-and-typed-runtime-limits.md)
  owns the framework-wide block and effect caps consumed here.
- [ADR-018](018-evidence-demo-iteration-2-publish-republish-verify.md) owns the
  lifecycle commands and the first bounded full-workflow load command.
- [ADR-019](019-reusable-domain-actor-registry-and-role-aware-approvals.md) is
  deliberately deferred and is not a dependency of this work.
- [App-layer open items](open_item.md) tracks delivery as `PERF-001`.

## 0. In plain words

The first `load` implementation runs several complete evidence lifecycles at
once, but each worker waits for one item's proposal, approval, release,
external effects, Kafka continuation, proofs, and anchors before starting its
next item. It is a correctness/soak tool, not a capacity runner.

More importantly, the current stock composite deliberately reserves only:

```text
release workflow quota       2 effects per block
effects emitted per release  2
                              --
maximum releases             1 per block

notification workflow quota  1 effect per block
effects per notification     1
                              --
maximum notifications        1 per block
```

The observed approximately `0.092` completed evidence workflows per second on
a roughly ten-second block cadence is therefore expected. Removing the client
semaphore would not increase valid capacity; it could place more release
messages in one proposal than the committed workflow quota permits.

This ADR makes two coordinated changes:

1. replace the artificial one-workflow allocation with an explicit,
   authenticated evidence capacity; and
2. add an optional bounded pipeline that keeps preparation, prerequisites,
   approval, release, effects, and verification busy for different evidence
   items at the same time.

The existing lifecycle load remains available as the simple functional mode.
The pipeline does not bypass state machines, finality, effects, external
re-reads, or proofs.

## 1. Goals and non-goals

### 1.1 Goals

- Permit a tested number greater than one of independent evidence releases and
  notifications in one app block.
- Commit the capacity through the canonical composite profile so members
  cannot apply different quotas.
- Derive every component/workflow reservation from one bounded capacity value
  and reject impossible configurations at startup.
- Keep the current full-lifecycle load mode behavior and report compatibility.
- Add a dependency-safe, bounded pipeline with backpressure and no unbounded
  task/thread/queue growth.
- Preserve exact per-item ordering while allowing different items to occupy
  different stages concurrently.
- Drain every requested item to verified success or a stage-specific terminal
  failure before the CLI returns.
- Report app-message, workflow, effect, and fully verified throughput
  separately; do not call one ambiguous number “TPS”.
- Work in Compose and host deployments and against an already-running demo
  instance with a fresh ID prefix.
- Require no app-chain consensus-core change.

### 1.2 Non-goals

- An unlimited or automatically self-tuning consensus profile.
- A scientific cross-product benchmark claim without controlled hardware,
  warm-up, workload, duration, and resource observations.
- Bypassing registry, approval, release, finality, connectors, result
  incorporation, independent external reads, or proof verification.
- Running high-count public-network anchor transactions implicitly.
- Changing ADR-019 or simulating domain actors before it is implemented.
- Turning the demo into a generic load-testing framework.
- Guaranteeing that connector capacity equals consensus capacity.

## 2. Decision summary

1. Add the committed setting:

   ```properties
   machines.composite.evidence-capacity-per-block=<positive integer>
   ```

2. Freeze a dependency-light `EvidenceWorkflowCapacityV1` contract used by the
   stock preset, demo runner, and tests.
3. Set the unreleased stock v1 default to eight workflows per block. The demo
   explicitly renders the same value and pins the resulting profile digest.
4. For a gated profile with capacity `C`, reserve:

   ```text
   evidence-release workflow  2C effects/block
   evidence-notify workflow    C effects/block
   evidence component          C effects/block
   total                       4C effects/block
   ```

5. For the compatibility direct profile, reserve `2C` for release and `2C`
   for direct evidence commands/result continuations: also `4C` total.
6. Require `4C <= effects.max-per-block` and
   `C <= block.max-messages`; use checked arithmetic and a frozen absolute
   upper bound.
7. Capacity is part of canonical composite profile bytes through descriptor
   quotas. Local drift produces a different profile digest/marker and fails.
8. Replace binary one-per-block load gates with fair `C`-permit release and
   notification gates. A permit is held through message finality so no more
   than `C` same-stage commands are pending for one block.
9. Add `load --load-mode lifecycle|pipeline`; `lifecycle` remains the default.
10. Add a bounded `--max-in-flight` option for pipeline mode. Existing
    `--concurrency` controls the bounded workers used by each stage.
11. Use explicit stages: prepare, prerequisites, approval, release, effects,
    and verify.
12. Preserve per-item reports and add a schema-v2 aggregate load report with
    mode, capacity, in-flight bound, stage counts/rates/latencies, and failure
    stage.

## 3. Authenticated capacity contract

### 3.1 Why the capacity is consensus-relevant

Composite quota enforcement occurs during deterministic block application. If
two members used different release quotas, one could accept a block containing
several releases while another rejects it. The capacity cannot be an
executor-only tuning knob.

The canonical profile already commits every component and workflow
`maxEffectsPerBlock`. `EvidenceWorkflowCapacityV1` derives those exact values
from `C`; no second runtime interpretation is allowed.

### 3.2 Default and bounds

The preview stock default is:

```text
C = 8 evidence workflows per block
required effects.max-per-block >= 32
```

The packaged demo uses `effects.max-per-block=128`, leaving headroom while
preventing one evidence profile from claiming the whole framework cap.

The accepted capacity is bounded by all of:

```text
C >= 1
C <= EvidenceWorkflowCapacityV1.MAX_CAPACITY
C <= consensusProfile.maxBlockMessages
4 * C <= consensusProfile.effectsMaxPerBlock
```

Malformed, zero, negative, overflowing, or excessive values fail provider
construction before networking starts.

### 3.3 Profile and retained-history consequence

Changing `C` changes canonical descriptor quotas and therefore the composite
profile digest. It is not a live local tuning operation.

- New/fresh demo chains select the new stock v1 digest.
- Existing preview demo histories are disposable and intentionally rejected.
- A retained production chain would require a packaged target profile plus
  ADR-015 governance/readiness/height activation.

The demo identity document records the effective capacity. The runner's local
copy is used only for safe admission pacing and reporting; the independently
pinned composite digest remains the authority for the active deterministic
profile.

## 4. Load modes

### 4.1 Lifecycle mode

```bash
./demo.sh load --load-mode lifecycle \
  --count 100 --concurrency 4 --id-prefix lifecycle \
  --sample-file samples/inspection-certificate.json
```

This is ADR-018 behavior: each worker owns one environment and finishes one
full workflow before starting its next assigned ID. It remains the default and
the clearest correctness/failure-isolation mode.

It benefits from the higher committed quota when several lifecycle workers
reach release or notification together, but it is not intended to keep every
stage saturated.

### 4.2 Pipeline mode

```bash
./demo.sh load --load-mode pipeline \
  --count 1000 --concurrency 4 --max-in-flight 64 \
  --id-prefix pipeline \
  --sample-file samples/inspection-certificate.json
```

Pipeline mode processes each ID through:

```text
PREPARE
  bounded read + absence proof + stage object + compute CID/commands
      |
      v
PREREQUISITES
  submit registry and exact approval proposal; wait for both finalities
      |
      v
APPROVAL
  submit approval; wait for finality
      |
      v
RELEASE
  submit the exact release under a fair C-permit gate; wait for finality
      |
      v
EFFECTS
  wait for authenticated storage results; submit explicit notification when
  required; wait for READY under the fair notification gate
      |
      v
VERIFY
  cluster/root agreement + effect proofs + S3/IPFS/Kafka re-read + optional L1
```

An item enters a later stage only after its earlier dependency is finalized.
The pipeline does not rely on proposer/mempool order for correctness.

### 4.3 Bounded execution and backpressure

- `count` is bounded to 1 through 50,000 for the demo tool. Active pipeline
  work remains independently bounded by `max-in-flight`; completed summaries,
  report files, and total duration still grow linearly with `count`.
- `concurrency` remains bounded; pipeline workers are fixed platform threads
  with one reusable client environment per worker.
- `max-in-flight` is bounded and cannot exceed `count` or the frozen demo
  maximum of 5,000 workflow items. It is not a mempool-message count: one
  workflow submits multiple dependency-ordered messages and waits at every
  finality boundary.
- Every inter-stage queue is bounded by `max-in-flight`.
- When a later stage or connector slows, earlier stages block rather than
  allocate unbounded tasks or mutate more external staging state.
- One item is processed by only one stage at a time.
- Failure records the exact stage and bypasses later mutation stages.
- A systemic interruption stops admission, preserves interruption, closes all
  workers/clients, and aborts without publishing a misleading partial aggregate.
  Ordinary per-item failures still drain to a complete stage-attributed report.
- All clients and worker threads close after the batch drains or aborts.

### 4.4 Direct and explicit continuation

- `direct`: the evidence result callback emits Kafka after storage results;
  the EFFECTS stage waits for READY.
- `explicit`: EFFECTS waits for STORAGE_READY, submits the notification under
  the notification gate, waits for finality, then waits for READY.

Both modes use the same `C` reservation. The unused path remains reserved in
the stock profile so one profile digest does not depend on local traffic mix.

### 4.5 Anchoring

Devnet L1-gated pipeline runs retain the shared throttled force-anchor
coordinator. APP_FINAL runs measure app-chain/connector throughput without L1
cadence. Aggregate reports name the gate and whether anchoring was required;
the per-item verification reports retain the detailed anchor observations.

Public anchor-enabled high-count load remains refused. Preview/preprod
capacity testing requires an explicitly non-anchored/APP_FINAL profile or a
separately reviewed spend plan.

## 5. Measurement contract

One evidence workflow is not one app-chain transaction. A publish normally
contains registry, proposal, approval, release, result, and possibly explicit
notification messages plus three external effects. ADR-020 therefore reports
separate dimensions.

### 5.1 Aggregate fields

- requested, attempted, succeeded, and failed workflows;
- load mode, concurrency, max in flight, and committed workflow capacity;
- required finality gate and whether L1 anchoring was enabled;
- total duration and fully verified workflows per second;
- total submitted/finalized normal messages when observable;
- terminal evidence READY count;
- stage success/failure counts;
- stage throughput over the batch interval;
- per-stage min/p50/p95/max latency;
- end-to-end min/p50/p95/max latency;
- maximum observed in-flight count; and
- bounded failure samples with stage and stable error code.

### 5.2 Interpretation

The CLI and UI label rates explicitly:

```text
workflow release rate
effect/READY rate
fully verified workflow rate
```

They do not label `successfulPerSecond` as consensus TPS. A controlled future
benchmark may additionally sample node metrics for finalized messages, block
fill, effects, CPU, memory, disk, and connector lag.

Warm-up/steady-window/drain benchmarks are a follow-up presentation feature;
this ADR's count-bounded pipeline first proves correct staged saturation.

## 6. Failure and correctness rules

- Every ID is unique and preflight proves absence at an authenticated root.
- Reusing a prefix remains a failure, not resume or overwrite.
- Registry/proposal submissions may be concurrent for one item, but approval
  waits for proposal finality.
- Release waits for registry, proposal, and approval finality.
- Release input is hash-bound to the approved evidence command.
- Storage effects and results remain ordinary ADR-010 effects.
- Verification starts only after terminal READY.
- A PASS batch means every requested item passed external re-reads, effect
  proofs, finality checks, and member/root agreement.
- Partial success produces a FAIL aggregate while preserving successful
  per-item reports.
- Stage failures never get reclassified as generic throughput success.
- Exact retries remain idempotent; uncertain submission is reconciled through
  finalized state/message identity rather than blind mutation.

## 7. Implementation plan

### Phase 20.1 — capacity contract and profile

- Add `EvidenceWorkflowCapacityV1` to composite contracts.
- Parse and validate the stock setting.
- Derive component/workflow quotas and update conformance tests.
- Render capacity in host/Compose node config and demo identity/config.
- Recompute and pin all gate/continuation profile digests.

### Phase 20.2 — pipeline engine

- Extract dependency-safe publication stage operations from
  `EvidenceScenario` without changing normal scenario behavior.
- Add bounded stage queues/workers, fair capacity gates, terminal accounting,
  interruption, cleanup, and failure-stage attribution.
- Add CLI parsing for `--load-mode` and `--max-in-flight`.

### Phase 20.3 — reporting and UI

- Add aggregate report schema v2 while continuing to read/display v1 reports.
- Show mode, capacity, verified rate, maximum in flight, and stage summaries.
- Keep individual verified evidence reports and content previews unchanged.

### Phase 20.4 — tests and iteration

- Unit-test capacity arithmetic, profile identity, parser bounds, gates,
  scheduling, backpressure, stage order, partial failure, and cleanup.
- Run focused module tests and complete demo launcher/Compose contract tests.
- Run real three-node lifecycle and pipeline tests in explicit/direct modes.
- Exercise non-default capacities in conformance tests, prove the default
  eight-release block shape, and retain real default-capacity observations
  without claiming hardware-independent TPS.

## 8. Acceptance criteria

1. The default stock gated profile admits up to eight release workflows in one
   block without exceeding any committed effect quota.
2. Nine concurrently pending releases cannot poison proposal construction;
   the fair gate admits at most eight until earlier releases finalize.
3. Profile construction fails for inconsistent capacity/global limits.
4. Changing capacity changes the canonical profile digest.
5. All four demo digest roots (APP_FINAL/L1_ANCHORED × explicit/direct) match
   independently constructed stock profiles.
6. Lifecycle mode remains source/CLI compatible when new options are omitted.
7. Pipeline stage order is preserved for every item while at least two stages
   make progress concurrently in the scheduling test.
8. `max-in-flight` is enforced under a deliberately blocked later stage.
9. A stage failure cannot execute later mutation stages for that item.
10. Direct and explicit continuation both reach READY and pass independent
    connector/proof verification.
11. Aggregate schema v2 reports mode, capacity, stage metrics, failure stage,
    and fully verified workflow rate.
12. Host and Compose launchers render identical consensus capacity and runner
    expectations.
13. No app-chain consensus-core module changes are required.
14. Focused tests, demo launcher tests, and the relevant three-node E2E suites
    pass before the ADR becomes Accepted/implemented.

### 8.1 Implementation and acceptance evidence

All four functional combinations passed on 2026-07-18 against fresh,
three-member devnet chains with script anchoring enabled:

| Continuation | Load mode | Workload | Result |
|---|---|---:|---|
| explicit | pipeline | 8 items, concurrency 4, max in flight 8 | PASS; all eight releases shared one block and all reports reached the same final root |
| explicit | lifecycle | 2 items, concurrency 2 | PASS; legacy/default mode remained functional |
| direct | pipeline | 8 items, concurrency 8, max in flight 8 | PASS; all eight releases shared one block and all reports reached the same final root |
| direct | lifecycle | 2 items, concurrency 2 | PASS; legacy/default mode remained functional |

Independent `verify` completed for a pipeline-created item in both
continuation profiles. Each verification re-read the immutable S3 version and
IPFS bytes, checked Kafka acknowledgement, finality/state proofs, and confirmed
three-member adoption of the Cardano anchor. The explicit pipeline's release
block contained exactly eight `evidence.release.v1` commands; the direct
pipeline independently demonstrated the same block shape. Both acceptance
clusters stopped cleanly after testing.

The explicit pipeline observation completed eight verified workflows in about
40 seconds at concurrency 4; the direct observation completed eight in about
40 seconds at concurrency 8. A second explicit, capacity-shaped run completed
eight in about 34 seconds. These are acceptance observations from one local
Docker deployment, not stable benchmarks or Yano TPS claims.

The following automated gates also passed:

- `:appchain-composite-contracts:test`;
- `:appchain-composite:test`, including governance, restart, and snapshot
  coverage;
- `:appchain-evidence-demo-runner:test`;
- the Compose contract test and demo-launcher contract test; and
- shell syntax, Java line-length, and whitespace validation.

Capacity arithmetic, invalid-limit rejection, profile identity/digest changes,
queue bounds, stage ordering, failure isolation, report schema compatibility,
and exact default eight-release block behavior have focused automated tests.

### 8.2 Operational load-bound amendment — 2026-07-19

The demo runner now accepts up to 50,000 workflows per invocation and up to
5,000 pipeline workflow items in flight. Concurrency remains capped at 16
workers per stage, every inter-stage queue remains bounded, and prepared tasks
retain only compact commands/hashes rather than document bodies. Parser,
request/report invariant, and launcher contract tests cover both upper bounds.

This amendment increases the available soak envelope; it does not claim that
5,000 messages are simultaneously resident in the mempool. A workflow submits
multiple messages across dependency and finality boundaries, so direct mempool
occupancy/ingress saturation requires a separate burst-admission workload and
belongs to the broader `APP-010` performance characterization.

## 9. Consequences

### 9.1 Benefits

- The stock profile no longer imposes an artificial one-evidence-per-block
  ceiling.
- Pipeline utilization exposes the actual bottleneck among consensus,
  effects, S3, IPFS, Kafka, proofs, and anchors.
- Capacity is explicit, authenticated, testable, and visible in reports.
- The original correctness-oriented load mode remains simple and available.

### 9.2 Costs

- The stock profile digest changes and old preview demo state is disposable.
- More in-flight external operations increase resource use and failure-surface
  complexity.
- Multiple stage environments create more clients/connections than lifecycle
  mode; strict bounds and cleanup are mandatory.
- Higher configured quotas increase worst-case deterministic work per block
  and must be covered by load/resource evidence.

## 10. Recommendation

Implement both changes together. Pipelining against the old quota would mostly
measure the deliberate one-release-per-block allocation, while increasing the
quota without a bounded pipeline would underutilize it and obscure the actual
bottleneck.

Keep `lifecycle` as the default functional mode and make `pipeline` explicit.
Treat the resulting rates as evidence for this exact profile and deployment,
not as a universal Yano TPS claim.
