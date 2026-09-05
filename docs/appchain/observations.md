# Certified observations (preview)

Generic observations are disabled by default. This page describes ADR-037's
one-shot and recurring exact-value implementation; aggregation and graduation
remain later milestones. Height cadence measures chain progress, not minutes.

## Profile selection

`observations.profile-cbor-hex` supplies the canonical `ObservationProfileV1`
envelope. Its bytes are committed independently of the consensus profile.
Omit the setting for the canonical disabled profile. Changing a retained
chain's profile is not an in-place configuration upgrade: the runtime rejects
a profile mismatch. Preserve old configurations when replaying old chains.

The original state-codec/round-rules version 1 supports one-shot APP_HEIGHT
requests with matching report and subscription expiry anchors. Select both
`stateCodecVersion=2` and `roundRulesVersion=2` for recurring requests and
separate report windows. Keep the other versions at 1, except that
`logicalTimeVersion=2` enables verified L1-slot scheduling and requires
positive `l1.stability-depth` plus the normal verified L1 feed.

V2 active-member definitions use `ObservationHashes.activeMemberRuleDigest()`
as their reporter selector. Each round pins its own actual member keys,
membership epoch, fault bound and report quorum. The definition threshold is
a floor; a round requires at least its opening finality quorum. Incompatible
admin membership changes are rejected, and incompatible governed activations
are void commands. Reporter count bounds must accommodate intended changes.

## Scheduling

Emit `ObservationIntent` through the supplied `AppObservationEmitter` only
during deterministic application/effect/observation callbacks. Public
parameters are opaque to the host and must fit the definition's bound.

- First due must be strictly future in the selected effective logical anchor.
- Cadence zero is one-shot. Positive cadence advances from the previous
  scheduled due, never the current height/slot.
- Completion code 0 continues through the last eligible due anchor; code 1
  stops at the first VALUE. An exhausted failed subscription is EXPIRED.
- Each recurring report deadline is the scheduled due plus the retained
  report window, clipped to subscription expiry.
- Only one round per subscription is open. Backlogged rounds retain their
  original due values and are processed in bounded later passes.
- Every round also has an app-height lifetime cap. Inclusion grace is in app
  heights, including for slot-based subscriptions.
- V2 allows at most 1,024 creations per block, in addition to active
  chain/application/definition quotas. Cancellation does not refund the
  block's emission ordinal.

For L1-slot mode, the host commits the maximum verified slot seen so far.
Zero references retain that high-water value. Member heartbeat envelopes only
wake the sequencer: their bodies cannot advance the clock. There is one
durable heartbeat outbox entry per node, and the profile bounds ticks per
block. An idle L1-less chain still does not acquire elapsed-time semantics.

## Bounds and status

`observations.workers` is 1–64, default 4. The executor queue holds at most that
many additional tasks. The same value caps new node-wide requests per second;
each definition additionally permits at most four per second. Consequently
any host/domain is also bounded by the node-wide cap. There is no timer or
thread per subscription.

The coordinator admits at most 1,024 queued tasks and reserves at most 16 MiB
of estimated decoded-message storage, charging four times encoded bytes plus
record overhead. Saturated work is retried from committed state and durable
reports/certificates; it does not alter deterministic outcomes.

`observations.journal.max-entries` defaults to 100,000 and
`observations.journal.max-bytes` to 256 MiB. Never copy this journal between
node identities. Signing material is persisted before invoking the signer;
signatures/reports and ready certificates are also synchronously durable.

Node status includes `genericObservations`: acquisition/failure counts,
journal usage, backpressure events, worker/coordinator queue usage, active
subscriptions, open rounds, high-water slot, readiness and the preview flag.
Readiness describes local runtime capacity, not a guarantee that sources or
the consensus quorum are available. No endpoint credentials or evidence bytes
are included in operational status.

## Root-fixed audit queries

Enabled observation chains reserve the `yano/observations/` query prefix.
Queries use the normal bounded query lane and return a committed height/root.
They read authenticated state, never node-local reports or worker state.

| Query suffix | Request bytes | Payload |
|---|---|---|
| `subscription` | 32-byte subscription ID | canonical subscription record |
| `round` | subscription ID + unsigned big-endian 64-bit round number | canonical round record |
| `result` | 32-byte result ID | canonical result record |
| `profile` | empty | canonical profile envelope |
| `counts` | empty | v2 active/open counts, two big-endian 64-bit integers |
| `high-water-slot` | empty | v2 high-water slot, big-endian 64-bit integer |

An absent record returns an empty payload. V1 has no v2 counts/high-water
leaf. Round numbers must fit the nonnegative Java `long` range.

## Offline index repair

Startup checks subscription/open-round commitments, due/open indexes,
per-application/definition counters and v2 scheduler summaries. A mismatch
fails startup. Do not erase the journal or start a new ledger with the same
signing key as a shortcut: doing so may forget observation or consensus locks.

The runtime recovery entry points are `ObservationLedgerRebuilder.replay`
and `install`. These are offline embedding/tooling APIs, not HTTP admin calls:

1. Stop the affected node and retain a recoverable copy of its entire ledger.
2. Supply a separate empty destination, the original profile/membership-aware
   kernel, and a fresh initialized copy of the original decorated state machine.
3. Replay every retained finalized block. Every computed state root must match
   the original. Acquisition providers are never started during replay.
4. Audit the candidate, then install only its observation index column family
   into the original ledger. The installer requires identical tip/hash/root.
5. Restart the original ledger and check observation readiness and audit state.

Replay destinations are marked as repair artifacts and cannot run as nodes.
Installation preserves the original consensus locks, sender sequence state,
observation signing journal and all other column families. Its synchronous
in-progress marker prevents startup after a partial install; rerun the
offline install to recover. Missing finalized history or replay divergence
requires restoring verified history/configuration, never bypassing a check.

## Qualification commands

```text
./gradlew :core-api:test :runtime:test --tests '*Observation*'
YANO_OBSERVATION_SCALE=1 ./gradlew :runtime:test --tests '*ObservationKernelTest'
YANO_OBSERVATION_HTTPS_LIVE=1 ./gradlew :runtime:test --tests '*ObservationRuntimeClusterTest'
./gradlew test
```

When switching an opt-in environment flag without source changes, force the
test task to rerun. The scale case creates 100,000 authenticated subscription
records over bounded blocks, closes/reopens RocksDB, audits the indexes, and
resumes bounded round opening. This is not the later five-node Preprod soak.
