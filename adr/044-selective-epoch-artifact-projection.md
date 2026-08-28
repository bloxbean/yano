# ADR-044: Select Epoch Artifact Projections Explicitly

## Status

Accepted and implemented

## Date

2026-08-27

## Related decisions

- [ADR-039](in-progress/039-canonical-projection-outbox.md) — canonical
  projection outbox, epoch artifact representations, artifact identity and
  fail-closed recovery.
- [ADR-043](043-shelley-only-epoch-artifact-staging.md) — epoch staged-file
  evidence begins only after a complete Shelley+ source epoch.
- [ADR-045](045-epoch-artifact-gaps-and-resumable-capture.md) — per-dataset
  coverage outcomes, explicit gaps and future capture after a failure.
- [ADR-046](046-archive-aware-epoch-artifact-apis.md) — archive-aware public
  reads over the coverage model established here and in ADR-045.

## Context

Projection history has two kinds of dataset with different capture models:

1. block sections, produced once for each canonical main block; and
2. epoch artifacts, produced while an epoch transition is evaluated or read
   from an immutable epoch-keyed generation.

Block sections are individually selectable with
`yano.history.projection.sections`. For example, the wallet profile selects
only:

```yaml
yano:
  history:
    projection:
      sections: address-transaction:v1
```

The section set is part of `ProjectionIdentity`, is checked on every startup,
and determines which datasets the read facade advertises.

Epoch artifacts are different today. `ProjectionHistoryService` always uses
`ProjectionArtifactContracts.shipped()` and therefore always installs and
advertises all five shipped epoch datasets:

- `reward`;
- `epoch-stake`;
- `ada-pot`;
- `drep-distribution`; and
- `governance-proposal-status`.

The always-on rule was originally intentional. Epoch artifacts are not block
sections and therefore do not appear in the section fingerprint. A simple
runtime toggle would let a node stop capturing an artifact while continuing to
present the same block projection identity. The archive could then look healthy
while containing permanent epoch holes.

That concern remains valid, but always enabling every epoch artifact is not the
only safe answer. ADR-039 already introduced a separate durable
`ProjectionArtifactIdentity`, containing each selected dataset's schema version,
codec version, representation and reconstructibility. The missing capability is
to derive that identity from an explicit configuration and use the same selected
identity consistently throughout initialization, capture, recovery, status and
read routing.

### Why boundary-derived datasets have different reconstructibility

All five datasets are finalized at an epoch boundary, but boundary timing does
not determine reconstructibility. The relevant question is:

> After the boundary has passed, does the node still retain a durable source
> containing every field required by the archive row, under the exact semantics
> that produced it?

`RECONSTRUCTIBLE` does not mean cheap, and `IRREPRODUCIBLE` does not mean
nondeterministic. In this contract, `IRREPRODUCIBLE` means **not backfillable
from the state retained by a normally configured node**. A corrected full replay
from genesis can reproduce every deterministic ledger result, but that is an
archive rebuild, not an in-place backfill.

The current storage audit gives the following answer.

| Dataset | Retained source after the boundary | Classification and reason |
|---|---|---|
| `EPOCH_STAKE` | The epoch-keyed delegation snapshot contains credential, pool and amount. It is immutable for that epoch. Normal retention may prune old generations, so an outbox reference protects its generation until acknowledgement and any later backfill still requires proven retained coverage. | `RECONSTRUCTIBLE`. Every data field required by the archive row exists in the retained generation; boundary block identity/time can be resolved canonically. |
| `ADA_POT` | The final eight pot values are stored under an epoch-keyed `adaPotKey(epoch)`. There is no normal age-based deletion path; rollback removes only values beyond the rollback target. | `RECONSTRUCTIBLE`. The stored result itself survives, so recomputing the transition is unnecessary. `ATOMIC_EVIDENCE` is its capture representation only because the pot does not share the snapshot batch; representation and reconstructibility are independent. |
| `REWARD` | The live account balance is cumulative. `accumulatedRewardKey(credential)` has no epoch or reward-type component and is overwritten by later rewards. MIR and reward-rest inputs are consumed/deleted, and per-block issuer/fee inputs are pruned after their reward calculation window. The archive row additionally needs earned/spendable epoch, reward type, pool/source and exact component amount. | `IRREPRODUCIBLE` from retained state. Reconstructing the rows would require complete historical stake, pool, fee, block-production, registration and protocol-parameter inputs plus the exact reward-calculation implementation. That closure is not retained or proven. |
| `DREP_DISTRIBUTION` | The governance store persists the epoch-keyed distribution **amount**. The archive row also contains `storedExpiry`, `dormantEpochs`, `effectiveExpiry` and `active`; those values are read from mutable DRep state at the boundary. Virtual DReps are emitted to staging but are not stored in the credential-only distribution generation. | The complete row is not reconstructible from the retained distribution. It is conservatively classed `IRREPRODUCIBLE` and captured as one staged dataset so amount and state columns cannot diverge. |
| `GOVERNANCE_PROPOSAL_STATUS` | The newer proposal lifecycle snapshot retains action type, coarse status/reason, proposed epoch and expiry. It does not retain all archive columns: observation phase, the original precise decision-reason string, deposit and return address. Terminal proposals may also have left the mutable active-proposal set. | The complete archive row remains `IRREPRODUCIBLE` from retained state even though a reduced lifecycle view is reconstructible. Boundary staging preserves the complete observation. |

`DREP_DISTRIBUTION` could be described taxonomically as
`BOUNDARY_INPUTS_ONLY`: its missing fields were available at the boundary but
are not retained. Operationally that class has the same rule as
`IRREPRODUCIBLE`: it cannot backfill a historical prefix from normally retained
state. Both classes may still join a populated archive prospectively under §3.
The current enum name is persisted in the artifact contract wire form.
Reclassifying it would refuse every existing archive for no recovery or capture
benefit, so this ADR does not change the classification. A future contract
version may rename or refine the taxonomy together with an explicit archive
migration.

## Constraints

- Selection must never make a missing epoch look like an empty epoch.
- An archive must not claim a dataset whose producer is disabled.
- Existing configurations that omit the new property must retain today's
  all-artifact behavior on a fresh archive and must not acquire newly shipped
  artifacts silently on an existing archive.
- Existing archives written under the complete shipped artifact identity must
  continue to open when the property is omitted or explicitly set to `all`.
- A selected artifact may join a populated archive prospectively. The archive
  must record the first eligible semantic epoch and must not claim earlier
  epochs as complete.
- Removing a selected artifact from a populated archive remains refused. This
  ADR does not define a projected-through epoch or a safe stop-maintaining
  operation.
- Joining and backfill are distinct. Joining starts at a future eligible
  boundary; it does not synthesize earlier epochs.
- Core epoch processing, reward calculation, AdaPot accounting, governance,
  delegation snapshots and rollback semantics must remain unchanged. This is an
  archive capture decision, not permission to disable ledger state required by
  the node for other purposes.
- The rule must be network-neutral. Existing ADR-043 era gating remains the
  authority for when Shelley+ staged artifacts become applicable.
- Genesis distribution capture is not an epoch artifact and must remain active
  even when the selected epoch artifact set is empty.

## Decision

### 1. Add a separate versioned epoch-artifact selector

Add:

```text
yano.history.projection.epoch-artifacts
```

The value is a comma-separated set of versioned shipped selectors:

```text
reward:v1
epoch-stake:v1
ada-pot:v1
drep-distribution:v1
governance-proposal-status:v1
```

Two explicit aliases are supported:

- `all` — every artifact shipped by this build;
- `none` — no epoch artifact.

On a fresh archive, an omitted property means `all` for backward compatibility.
On a populated archive, omission means **preserve the stored selection**. This
prevents a build that ships a sixth artifact from silently changing an existing
five-artifact archive or refusing to start merely because the default was
re-evaluated against a newer build.

Explicit `all` means every artifact shipped by the current build. On a populated
archive it is an explicit request to enroll newly shipped artifacts at their
next eligible boundary; it does not claim the historical prefix. Explicit named
selectors behave the same way for additions. `none` on a populated archive that
already holds an artifact is a removal request and is refused.

Before an explicit `all` or named addition becomes active, startup emits a
warning for each joining dataset naming its `projectedFromEpoch`, stating that
earlier epochs are `NOT_PROJECTED`, and pointing to `/history/coverage`. The same
grouped additions appear in initialization status. An operator who intended
genesis-complete history is told to use a fresh archive; a prospective join must
never be a silent reinterpretation of the word `all`.

A configured blank value is rejected; `none` must be used so absence and
deliberate empty selection cannot be confused. `all` and `none` cannot be
combined with named selectors. Duplicates are harmless but normalized. Unknown
names, unknown versions and block-section names fail initialization and report
the complete allowed set.

Example:

```yaml
yano:
  history:
    projection:
      enabled: true
      sink: ducklake
      sections: address-transaction:v1
      epoch-artifacts: reward:v1,ada-pot:v1
```

A minimal block-only wallet archive may use:

```yaml
epoch-artifacts: none
```

This is also the supported preventive configuration for deployments that do not
need reward/governance history and want no staged epoch source at all. It avoids
the class of irrelevant staging failures exposed by
[issue #91](https://github.com/bloxbean/yano/issues/91) without changing core
epoch processing.

The property is separate from `projection.sections`. A block section has one
manifest contribution per block and participates in the block completeness
cursor. An epoch artifact has a boundary coordinate, a durable source/evidence
representation, its own acknowledgement lifecycle and a separate artifact
identity. Combining both in one setting would hide these different recovery
contracts.

The block-section selector has the same upgrade-default trap. This ADR also
stabilizes that default: an omitted `projection.sections` on a populated archive
resolves to its stored section set, while a fresh archive still resolves to all
shipped sections. Explicit section changes retain the existing exact-identity
rule; prospective block-section enrollment is out of scope because it needs a
separate block-range coverage contract.

Resolution must therefore open the chainstate outbox and decode its stored
projection fingerprint before constructing the effective `ProjectionIdentity`
or opening the sink. The fingerprint already contains the canonical versioned
section names. Add one tested decoder for that stable form (or persist an
equivalent full identity alongside it); do not guess a section set from sink
tables. A malformed legacy fingerprint fails initialization with a rebuild
diagnostic.

### 2. Build one selected artifact identity and enrollment map

`ProjectionArtifactContracts` remains the registry of every artifact this build
knows how to produce. Configuration selects a subset of those shipped contracts;
it never constructs contract details supplied by the operator.

`ProjectionHistoryService` resolves one
`ProjectionArtifactIdentity selectedArtifacts` before any artifact contributor,
staging sink or drain worker is installed. Every later decision uses this object,
not a fresh call to `ProjectionArtifactContracts.shipped()`.

Contract identity and capture lifetime are related but different facts. Keep the
existing contract wire form for schema/codec compatibility and persist a
versioned enrollment alongside it:

```java
record ProjectionArtifactEnrollment(
        ArchiveDatasetId dataset,
        OptionalInt projectedFromEpoch,
        EnrollmentOrigin origin
) {}

enum EnrollmentOrigin {
    FRESH,
    PROSPECTIVE_JOIN,
    LEGACY_UNKNOWN
}
```

`projectedFromEpoch` is the first semantic artifact epoch whose normal capture
window has not already passed when enrollment becomes durable. It is derived
from the normal epoch-boundary pipeline and ADR-043 era applicability, not from
network magic or `currentEpoch + 1`. This avoids an off-by-one rule for artifacts
whose output epoch differs from their source epoch.

It is present for every fresh enrollment and prospective join. It is absent only
for a migrated preview archive whose old marker proves the contract set but not
the historical coverage start; that case has origin `LEGACY_UNKNOWN` and is
reported as `UNKNOWN_LEGACY_COVERAGE`.

Epochs before `projectedFromEpoch` have the positive coverage outcome
`NOT_PROJECTED`. This is not a stored row per epoch; it is derived from the
enrollment. `NOT_PROJECTED` means capture was never configured and is expected.
ADR-045's `GAP` means capture was configured but evidence was lost and is an
operational defect. Both refuse a complete historical query, but they must be
reported differently.

The persisted identity remains separate from `ProjectionIdentity`. This avoids a
block archive ID change while retaining the startup check that prevents artifact
holes.

On a fresh archive, including a deliberately empty selection, the selected wire
form and enrollment map are written atomically in one outbox metadata batch.
RocksDB key presence distinguishes an empty artifact identity from an archive
created before artifact identity existed.

An existing preview archive that has the old contract marker but no enrollment
metadata is not assigned a fabricated historical start. It preserves its stored
selection and reports the historical prefix as `UNKNOWN_LEGACY_COVERAGE` until a
rebuild or an audited coverage migration proves it. Capture may continue, and
newly enrolled datasets still receive an exact prospective start. This keeps the
node available without turning old table rows into an unsupported completeness
claim.

### 3. Additions join prospectively; removals remain refused

Once an archive contains acknowledged or sink data:

- removing an artifact is refused because the archive would retain historical
  rows while ceasing to maintain the dataset;
- changing schema, codec, representation or reconstructibility for an existing
  artifact remains a rebuild;
- adding any shipped artifact is allowed as a prospective enrollment, whether
  `RECONSTRUCTIBLE` or `IRREPRODUCIBLE`; and
- no addition implicitly invokes the existing reconstructible backfill model.

Selection changes take effect during initialization, not as a live runtime
toggle. The addition sequence is serialized before sync/drain starts:

1. validate the producer prerequisite and contract;
2. calculate the first capture window that has not passed;
3. durably persist its contract and `projectedFromEpoch` atomically in one
   outbox metadata batch;
4. create only that selected dataset's sink schema; and
5. install and enable its reader and contributor.

The chain cannot advance between steps 2 and 5. A crash after enrollment is
persisted restarts into the same selected enrollment and installs the producer
before synchronization resumes. A failure before durable enrollment leaves the
old selection authoritative. A crash after step 3 leaves a durable enrollment
that startup must finish before sync can run; it does not permit capture or
queries against a half-initialized dataset. Sink contract/schema installation is
idempotent because it cannot share the RocksDB transaction; initialization does
not become available or start synchronization until both stores agree.

Joining while the node is already in epoch 520 must never claim epochs 500..520,
even if the operator supplied 500. Operators do not configure the start epoch;
the runtime selects the first still-capturable semantic epoch. Earlier epochs
are `NOT_PROJECTED`, later capture is ordinary, and any failure after enrollment
is governed by ADR-045 as `PENDING`, `COMPLETE` or `GAP`.

The compatibility evaluator therefore replaces the current blanket use of
`refuseToOpen(..., ProjectionArtifactCoverage.NONE)` for additions. The existing
retained-source coverage path remains reserved for a future explicit backfill
executor that can turn a historical prefix into `COMPLETE`; it is not required
for prospective join.

Diagnostics name datasets grouped as added, removed and contract-changed. For a
newly shipped dataset they say that the build knows the dataset, the existing
archive predates it, and capture can begin only at the reported prospective
epoch. They do not describe the existing archive as broken.

A populated archive with no artifact-contract identity metadata is still
refused, even when the requested selection is `none`. Treating marker absence as
an implicit empty identity would create a one-off migration whose provenance
cannot be distinguished from an interrupted or partially written artifact
rollout. This differs from a present legacy contract identity lacking only the
new enrollment metadata, which follows the conservative
`UNKNOWN_LEGACY_COVERAGE` rule above.

### 4. Install only selected capture paths

`ProjectionHistoryService` routes only selected contracts:

- create an `EpochSnapshotArtifactReader` only for selected `EPOCH_STAKE`;
- create an `AdaPotArtifactReader` only for selected `ADA_POT`;
- bind `StagedEpochArtifactReader` sources only for selected staged-file
  datasets;
- derive `stagedFileDatasets(selectedArtifacts)` by intersecting selected
  contracts with `STAGED_FILE` representation; and
- do not install `EpochArchiveStagingService` when that intersection is empty.

The staged path already supports this: ledger writers call
`EpochArchiveStagingSink.enabled(dataset)`, and
`EpochArchiveStagingService` already owns a set of enabled datasets.

`EpochArtifactCollector` gains a selected dataset set and independently no-ops
unselected direct contributions:

- `contributeEpochStake` only when `EPOCH_STAKE` is selected;
- `contributeAdaPot` only when `ADA_POT` is selected.

If no direct artifact is selected, do not install the account-state contributor.
The default `NOOP` remains in place. Core snapshot and AdaPot processing continue
according to their ledger configuration.

### 5. Validate that every selected dataset has a producer

Before persisting the requested artifact identity, initialization verifies the
resolved producer prerequisites. At minimum:

- every non-empty epoch selection requires the account-state infrastructure
  used by the current artifact pipeline;
- `epoch-stake:v1` requires epoch snapshot amounts to be enabled;
- `ada-pot:v1` requires AdaPot tracking;
- `reward:v1` requires reward calculation;
- `drep-distribution:v1` and `governance-proposal-status:v1` require governance
  epoch processing; and
- the epoch-boundary processor needed by a selected producer must actually be
  installed.

Validation uses resolved application/runtime capabilities, not network magic or
public-network profiles. A selected producer that cannot run fails
initialization. It must not be accepted on the assumption that an epoch with no
emitted artifact means an empty dataset.

This validation does not automatically enable ledger features. Operators remain
in control of core state configuration, and a feature used elsewhere is not
disabled merely because its history artifact is unselected.

This is intentionally stricter than today's tolerant startup. For example,
selecting only `reward:v1` while `account-state.enabled=false` fails
initialization before a fresh identity or join is persisted. A selected producer
that cannot execute would otherwise convert every eligible epoch into ambiguous
absence. This compatibility change is called out in the release note.

### 6. Keep genesis bootstrap independent

`ProjectionGenesisBootstrap` is currently constructed inside epoch artifact
installation even though it captures genesis UTXO distribution, not an epoch
artifact. Move its initialization to the common projection initialization path.

`epoch-artifacts: none` must still:

- capture and verify the genesis distribution;
- set the genesis receipt/marker;
- preserve the block projection's queryable-from-genesis invariant; and
- recover an interrupted genesis bootstrap before drain.

### 7. Coverage, routing and status report the selected set

Replace uses of the complete shipped artifact set in operational state with the
selected identity:

- `coveredDatasets()` returns selected block sections plus selected artifacts;
- `artifactStatus()` and `/history/coverage` report selected artifact contracts
  and selectors, `projectedFromEpoch`, capture state, complete ranges and gaps;
- historical read initialization receives exactly that covered set; and
- an unselected epoch dataset is reported as dataset-level `NOT_SELECTED`, never
  queried as an empty table. `NOT_PROJECTED` is reserved for epochs before an
  enrolled dataset's `projectedFromEpoch`.

`ProjectionHistoryService.consistencyPoint()` currently checks requested block
datasets only. It must compare **all** requested datasets against
`coveredDatasets()`. Otherwise `/history/watermark?datasets=reward` could report
an available projection point when reward history was deliberately unselected.

The sink initializes physical **epoch-artifact** schemas only for selected
artifacts. A fresh `epoch-artifacts: none` archive therefore has no empty
reward/stake/governance artifact tables that can be mistaken for
configured-but-empty history. A prospective join initializes its schema during
the serialized join sequence. Existing block/genesis schema initialization is
unchanged: genesis capture requires the UTXO tables independently of selected
epoch artifacts. Table existence still is not sufficient evidence of coverage;
enrollment and acknowledged outcomes remain authoritative.

### 8. Rollback and restart remain dataset-agnostic

Outbox rollback already removes artifact references by canonical point, and the
routing reader reconciles surviving references after restart/rollback. Selection
does not change either algorithm:

- a selected artifact follows the existing reference, receipt,
  acknowledgement and source-release lifecycle;
- an unselected artifact never creates a reference;
- removal and contract-changing identity mismatches are rejected before drain;
- a newly added enrollment is installed before drain and is gated by its
  `projectedFromEpoch`;
- a pending reference without a selected reader fails closed rather than being
  skipped.

Enrollment is configuration history, not chain-derived state, so rollback does
not move `projectedFromEpoch` backward. If the chain rolls back before a join,
replay of earlier epochs remains `NOT_PROJECTED`; the first eligible epoch is
still the one durably promised when the dataset joined. Artifact outcomes at or
after the join follow ADR-045's exact-point rollback rules.

ADR-043's post-Byron source-epoch rule applies to the selected staged-file set
unchanged.

### 9. Legacy staging failure markers are selection-aware

An unstructured `epoch-source/FAILED` marker cannot identify a dataset, epoch or
canonical boundary and is governed by ADR-045 rather than selection parsing.

- When no staged-file artifact is selected, the marker is reported by
  `/history/coverage` but is inactive and does not block direct artifacts or
  block projection.
- It is never silently deleted merely because the staged selection is empty.
- If a staged-file artifact is later joined while the marker exists, enrollment
  is refused until an audited operator action acknowledges the legacy unknown
  failure or the archive is rebuilt. The marker cannot safely be attributed to
  the newly joined dataset.
- New failures after ADR-045 use structured per-dataset state, so this ambiguity
  does not recur.

## Classification conclusion

The three staged-file datasets must not be changed to `RECONSTRUCTIBLE` merely
because they are calculated at an epoch boundary.

- Reward rows lose both their detailed outputs and required historical inputs.
- DRep distribution retains its amount but not the complete exported row.
- Proposal lifecycle retention has improved, but still does not contain the
  complete proposal-status archive observation.

They can become reconstructible only if one of the following is implemented and
verified:

1. persist an immutable, epoch-keyed generation containing every archive field
   under a retention/lease contract; or
2. retain and version the complete deterministic input closure and provide a
   tested backfill implementation that reproduces byte/row-identical output.

Persisting the final rows as a new live-state generation would make them
reconstructible, but it would also duplicate the purpose of staged evidence and
increase live RocksDB retention. It is not automatically simpler or cheaper.

The existing staged-file representation is therefore retained. It captures the
otherwise-lost facts at the only point they are complete, lets the sink consume
them asynchronously, and deletes the staged source only after durable
acknowledgement.

## Files expected to change

Production:

1. `core-api/.../YanoPropertyKeys.java` — add the configuration key and contract.
2. `archive-api/.../ProjectionArtifactIdentity.java`, `ProjectionIdentity`,
   selector contracts and a new enrollment codec — expose stable selector names,
   decode the stored block-section fingerprint, preserve contract wire
   compatibility and record `projectedFromEpoch`.
3. `archive-core/.../ProjectionOutboxStore.java` — atomically persist selected
   contract/enrollment changes and expose them to recovery/status.
4. `archive-core/.../EpochArtifactCollector.java` — gate direct artifacts by
   selected dataset.
5. `archive-store-ducklake` initialization — create epoch schemas for
   selected/joined artifacts only while preserving block/genesis prerequisites.
6. `app/.../ProjectionHistoryService.java` — parse, validate and persist the
   selection/enrollment; install selected readers/staging; preserve genesis;
   stabilize omitted block sections; fix status and consistency routing.
7. Packaged configuration/documentation — document `all`, `none`, selectors,
   fresh/archive-preserving defaults and prospective join semantics; the wallet
   profile may explicitly choose its intended epoch set.

No change is expected in `EpochBoundaryProcessor`, reward calculation,
governance calculation, UTXO/accounting state transitions or core ledger-state
schemas. Projection outbox metadata and sink schema-initialization policy do
change.

Tests will add or update parser/identity, collector, initialization, staging,
routing and configuration-profile coverage.

## Alternatives considered

### Keep every epoch artifact always enabled

Safe but unnecessarily expensive for narrow deployments. It also prevents the
wallet profile from expressing that it serves address transaction history but
not reward/governance history.

### Put epoch names in `projection.sections`

Rejected. It would mix per-block manifest completeness with epoch evidence and
would either force fake empty sections into every block or leave the meaning of
the section cursor ambiguous.

### Use independent booleans per dataset

Rejected. Five booleans are verbose, make `all`/`none` difficult to inspect and
provide no version in the operator's selection. A versioned set matches the
existing block-section vocabulary and can be stored/reported deterministically.

### Require a fresh archive for every selection change

Rejected. A newly selected artifact can honestly begin at the next unpassed
boundary when its earlier prefix is positively represented as `NOT_PROJECTED`.
Forcing a genesis rebuild is unnecessary for future snapshot/event capture and
makes deployment configuration needlessly rigid.

### Automatically backfill reconstructible additions

Deferred. Prospective join does not require retained historical sources.
Backfill does: the application still needs proven retained coverage and an
executor. A future backfill may replace a `NOT_PROJECTED` prefix with
`COMPLETE`, but this ADR does not pretend that capability exists.

### Treat every deterministic boundary calculation as reconstructible

Rejected. Determinism from genesis is not retained-state reconstructibility.
Without retained inputs and calculation-version closure, the only available
reconstruction is a full replay, which is precisely the rebuild this contract is
designed to distinguish from safe in-place backfill.

### Reclassify DRep distribution to `BOUNDARY_INPUTS_ONLY`

Rejected for this change. The name may be more precise, but both classes refuse
historical backfill without proven complete inputs, and the classification is
persisted in existing archive identities. Both can join prospectively regardless
of classification. A taxonomy-only change should not force archive rebuilds.

## Consequences

Positive:

- Operators can pay only for the epoch history their deployment serves.
- Selection remains explicit, versioned, restart-safe and fail-closed.
- Existing default behavior and existing full-artifact archives remain
  compatible.
- A new artifact can join at a future boundary without claiming or rebuilding
  the historical prefix.
- Unselected datasets allocate no staged files, readers, outbox references or
  snapshot-retention clamps, and fresh archives do not create their sink tables.
- Core ledger calculations remain independent of archival selection.

Tradeoffs:

- Removal is still not supported on a populated archive; only prospective
  additions are allowed.
- Historical queries must understand `NOT_SELECTED`, `NOT_PROJECTED`, `GAP` and
  complete ranges. ADR-045 supplies the durable gap and resumable-capture model.
- Configuration has a second selector because block and epoch capture have
  genuinely different completeness models.
- Producer prerequisite validation may reject configurations that previously
  started but silently failed to produce meaningful epoch history.
- Existing archives without enrollment metadata retain an unknown legacy
  coverage prefix until rebuilt or audited.

## Implementation plan

1. Implement the shared coverage foundation needed by ADR-045:
   `projectedFromEpoch`, dataset-level `NOT_SELECTED`, range-level
   `NOT_PROJECTED`, and acknowledged `PENDING`/`COMPLETE` coverage. ADR-044
   prospective join must not ship without this honest enrollment status.
   Per-dataset `GAP`, pause and resume are implemented by ADR-045; capture never
   continues through an unreported hole.
2. Add the new property, selector vocabulary and parser with the fresh/populated
   omitted semantics and explicit `all`/`none`.
3. Reorder identity resolution so the outbox's stored fingerprint can stabilize
   an omitted block-section selection before sink opening; keep explicit
   block-section changes exact-match/fail-closed.
4. Resolve producer prerequisites before writing a fresh identity or join.
5. Persist/verify selected contracts and enrollment; allow prospective additions,
   refuse removal/contract changes and preserve legacy-unknown coverage.
6. Initialize epoch-artifact sink schemas only for selected datasets and make
   join initialization idempotent across crash/restart.
7. Separate genesis bootstrap from epoch artifact installation.
8. Install selected direct readers/contributor methods only.
9. Intersect staged-file contracts with selection and install no staging service
   when empty; apply the legacy-marker rules in §9.
10. Route coverage, status, read availability and consistency-point checks through
    the selected enrollment set.
11. Update packaged configuration, operator diagnostics and reconstructibility
    Javadocs while preserving existing classifications.
12. Run focused and broad regression tests, followed by fresh and mid-archive
    network selection scenarios.

## Verification plan

1. On a fresh archive, unset resolves all five shipped contracts; on a populated
   archive it preserves the stored selection when a later build ships another.
2. Explicit `all` selects every artifact shipped by the current build and joins a
   newly shipped artifact prospectively; `none` produces a present empty fresh
   identity.
3. Each individual selector and representative subsets resolve exact shipped
   contracts with deterministic ordering.
4. Blank, unknown, wrong-version, block-section, and mixed `all`/`none` values
   fail before identity persistence or contributor installation.
5. Restart with the same subset and enrollment epochs succeeds.
6. Adding any artifact on a populated archive records the first unpassed semantic
   epoch, creates its schema and captures from that boundary; all earlier epochs
   report `NOT_PROJECTED` rather than `GAP`.
7. Explicit `all` or a named addition logs every joined dataset and its exact
   projected-from epoch before capture starts; the same information appears in
   status.
8. Joining during epoch 520 cannot be configured to claim 500..520, and a crash
   at each join step either leaves the old selection or completes the same join
   before sync resumes.
9. Removing an artifact or changing an existing contract fails closed and names
   added, removed and contract-changed datasets separately.
10. A populated archive with no artifact marker is refused for both `none` and a
   non-empty selection.
11. An old contract marker without enrollment metadata opens with
    `UNKNOWN_LEGACY_COVERAGE`; it never fabricates a historical start.
12. `epoch-artifacts: none` still completes genesis capture and block-section
   projection.
13. Direct contribution emits only selected `EPOCH_STAKE`/`ADA_POT` references and
   creates a snapshot clamp only for selected epoch stake.
14. Staged writers create files/references only for selected reward, DRep and
   governance datasets; no staging service is installed when none is selected.
15. A fresh unselected dataset has no physical sink table; joining creates only
   the newly selected dataset schema.
16. A selected dataset with a disabled producer fails initialization before
    enrollment is persisted. An unselected dataset does not require or enable
   its producer.
17. `coveredDatasets`, status and coverage contain exactly the selected artifact
   set and its projected-from epochs.
18. Queries and consistency-point requests for an unselected epoch dataset report
    `NOT_SELECTED`, while epochs before a joined dataset report `NOT_PROJECTED`;
    neither returns success with zero rows.
19. Rollback and kill/restart preserve enrollment, roll back derived outcomes by
    exact point and never create an unselected reference.
20. With no staged artifact selected, a legacy `epoch-source/FAILED` marker is
    visible but non-blocking; joining a staged artifact remains refused until the
    unknown marker is explicitly resolved.
21. Existing ADR-043 mainnet/preprod/devnet era-boundary tests remain unchanged
    for every selected staged-file subset.
22. Run `:archive-modules:archive-api:test`,
    `:archive-modules:archive-core:test`,
    `:archive-modules:archive-store-ducklake:test`, `:ledger-state:test`,
    `:runtime:test` and `:app:test`.
23. Run a fresh public test network with block projection plus selected epoch
    artifacts through multiple boundaries, restart it, and verify status/query
    coverage. Repeat with `epoch-artifacts: none` and verify block history and
    genesis remain healthy.
24. On that populated `none` archive, join reward and AdaPot, cross the next
    eligible boundaries, and verify pre-join `NOT_PROJECTED`, post-join
    `COMPLETE`, metrics/status and restart behavior.

## Release note

Projection history can now select epoch artifacts independently with
`yano.history.projection.epoch-artifacts`. On a fresh archive the property
defaults to `all`; omission on an existing archive preserves its stored set.
Use `none`, `all` or a comma-separated versioned subset. A new artifact may join
a populated archive at its next eligible boundary, with earlier epochs reported
as `NOT_PROJECTED`; removal and contract changes remain refused. Selecting an
artifact whose core producer prerequisite is disabled now fails initialization
instead of starting with ambiguous empty history. Deployments that do not need
epoch history can use `none`, avoiding staged epoch-source failures such as issue
#91 without disabling core ledger processing.
