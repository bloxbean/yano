# ADR-019: Governance, AdaPot, DRep Distribution, and Active Stake REST APIs

## Status

 Implemented through current-state/read-only phase

## Date

2026-04-29

## Context

Yano should expose more production REST APIs for ledger state that is already
available in the node chainstate. The requested API groups are:

- current governance proposals, including active and lifecycle statuses where
  possible,
- proposals by epoch, when the data is available,
- current AdaPot values and AdaPot values by epoch,
- DRep distribution for a DRep in the current epoch and by epoch,
- total active stake in the current epoch and by epoch,
- active stake for a stake address in the current epoch and by epoch.

The public API should follow Blockfrost paths and response shapes where Yano has
equivalent backing data. Where Yano does not retain equivalent data yet, the API
must make that explicit instead of returning incomplete historical results.

Blockfrost references checked:

- OpenAPI source: <https://github.com/blockfrost/openapi>
- Raw OpenAPI file checked for current paths and schemas:
  <https://raw.githubusercontent.com/blockfrost/openapi/master/openapi.yaml>

Relevant Blockfrost paths:

- `GET /governance/proposals`
- `GET /governance/proposals/{tx_hash}/{cert_index}`
- `GET /governance/proposals/{gov_action_id}`
- `GET /governance/proposals/{tx_hash}/{cert_index}/votes`
- `GET /governance/dreps`
- `GET /governance/dreps/{drep_id}`
- `GET /governance/dreps/{drep_id}/delegators`
- `GET /epochs/{number}/stakes`
- `GET /epochs/{number}/stakes/{pool_id}`
- `GET /network`

Blockfrost exposes current treasury and reserves through `/network`, but it does
not expose a full AdaPot endpoint with deposits, fees, distributed rewards, and
undistributed rewards. Yano's AdaPot API is therefore a Yano extension.

## Existing Data Sources

| Data | Existing source | Existing read shape | Notes |
| --- | --- | --- | --- |
| AdaPot by epoch | `LedgerStateProvider.getAdaPot(epoch)` | point lookup | Already exposed by `EpochResource`. |
| Latest AdaPot | `LedgerStateProvider.getLatestAdaPot(maxEpoch)` | backward scan over epochs | Already exposed by `EpochResource`. |
| Active proposals | `GovernanceStateStore.getAllActiveProposals()` | scan active proposal prefix | Available only while proposal remains active. |
| Active proposal by id | `GovernanceStateStore.getProposal(txHash, govIdx)` | point lookup | Available only while proposal remains active. |
| Proposal votes | `GovernanceStateStore.getVotesForProposal(txHash, govIdx)` | prefix scan by active proposal | Votes are removed when proposal is removed. |
| Pending ratified proposals | `GovernanceStateStore.getPendingEnactments()` | small scan | Contains ids only, no ratified epoch field. |
| Pending expired proposals | `GovernanceStateStore.getPendingDrops()` | small scan | Contains ids only, no expired epoch field. |
| Historical enacted/dropped/expired proposals | not retained in RocksDB | unavailable | Optional parquet proposal-status exports may exist, but are not guaranteed chainstate. |
| DRep distribution by epoch | `GovernanceStateStore.getDRepDistribution(epoch)` | scan DRep distribution prefix | Regular key/script DReps are stored; virtual DReps are exported only. |
| DRep state | `GovernanceStateStore.getDRepState(...)` | point lookup | Internal only today; not exposed through `NodeAPI`. |
| Active stake snapshot by epoch | `cfEpochSnapshot` in `DefaultAccountStateStore` | point lookup possible, full epoch scan possible | Snapshot amount is UTXO balance plus reward balance at snapshot creation. |
| Latest stake snapshot epoch | `LedgerStateProvider.getLatestSnapshotEpoch()` | metadata lookup | Already exposed by provider. |
| Snapshot pruning | `yaci.node.account-state.snapshot-retention-epochs` | configured retention | Old stake snapshots can be deleted. |

The existing debug endpoint `GET /api/debug/epoch-snapshot/{epoch}` returns the
entire stake snapshot and computes total active stake by scanning all entries.
That shape is too heavy to promote directly.

## Current Endpoint Inventory (2026-04-30)

Current production API coverage before this implementation pass:

| API | Current status | Backing data exists now? | Decision for this pass |
| --- | --- | --- | --- |
| `GET /epochs/latest/adapot` | Present in `EpochResource` | Yes | Keep as-is. |
| `GET /epochs/{number}/adapot` | Present in `EpochResource` | Yes | Keep as-is. |
| `GET /epochs/adapots` | Present in `EpochResource` | Yes | Keep as-is. |
| `GET /governance/proposals` | Missing | Yes, for current active proposals | Implement current-state list. |
| `GET /governance/proposals?status=active` | Missing | Yes | Implement. |
| `GET /governance/proposals?status=pending_ratified` | Missing | Partially; pending ids plus still-present proposal records | Implement as a Yano current-state extension. |
| `GET /governance/proposals?status=pending_expired` | Missing | Partially; pending ids plus still-present proposal records | Implement as a Yano current-state extension. |
| `GET /governance/proposals?submitted_epoch=N` | Missing | Yes, for current proposal records only | Implement filter against current proposal state. |
| `GET /governance/proposals/{tx_hash}/{cert_index}` | Missing | Yes, while proposal remains in current state | Implement. |
| `GET /governance/proposals/{gov_action_id}` | Missing | Formatting exists through CCL; decode helper not currently available | Defer route until CCL decode support or an accepted parser is added. Responses can still include formatted `gov_action_id`. |
| `GET /governance/proposals/{tx_hash}/{cert_index}/votes` | Missing | Yes, while proposal remains in current state | Implement. |
| `GET /governance/dreps` | Missing | Yes, current DRep state scan exists | Implement current-state paginated list if cheap enough with in-memory sort/page. |
| `GET /governance/dreps/{drep_id}` | Missing | Yes, current DRep state plus latest DRep distribution | Implement. |
| `GET /governance/dreps/{drep_id}/delegators` | Missing | Not efficiently; would need scanning current delegations and has no historical view | Defer. |
| `GET /governance/dreps/{drep_id}/distribution` | Missing | Yes, via latest retained DRep distribution epoch | Implement. |
| `GET /governance/dreps/{drep_id}/distribution/{epoch}` | Missing | Yes, if epoch distribution is retained | Implement. |
| `GET /epochs/latest/stake/total` | Missing; debug snapshot can compute it | Yes, by scanning latest retained stake snapshot | Implement with clear O(N) cost and no full entry list. |
| `GET /epochs/{number}/stake/total` | Missing; debug snapshot can compute it | Yes, by scanning retained stake snapshot | Implement with clear O(N) cost and no full entry list. |
| `GET /accounts/{stake_address}/stake` | Missing | Yes, point lookup can be added over `cfEpochSnapshot` | Implement. |
| `GET /accounts/{stake_address}/stake/{epoch}` | Missing | Yes, if epoch snapshot is retained | Implement. |
| `GET /epochs/{number}/stakes` | Missing | Technically yes, but full snapshot listing is heavy and paging is not index-friendly | Defer. |
| `GET /epochs/{number}/stakes/{pool_id}` | Missing | Yes, by scanning retained epoch snapshot and filtering by pool | Implement as Blockfrost-style paged pool delegator rows. |
| `GET /epochs/{number}/stake/pool/{pool_id}` | Missing | Yes, by scanning retained epoch snapshot and filtering by pool | Implement as a Yano aggregate extension for total pool active stake. |
| `GET /network` | Missing | Partially; max supply from genesis, treasury/reserves from AdaPot, total supply derived, active stake from latest snapshot aggregate | Implement partial Blockfrost-compatible shape; omit unsupported fields. |

Debug endpoint promotion review:

- `GET /api/debug/adapot/{epoch}` and `GET /api/debug/adapot-chain` are
  superseded by the production AdaPot endpoints and should not be promoted.
- `GET /api/debug/epoch-snapshot/{epoch}` should not be promoted because it
  returns the full snapshot. The production API should use the same retained
  snapshot data only for point lookups or aggregate totals.
- reward-input, pool-parameter, block-count, fee, retired-pool, and
  deregistered-account debug endpoints remain diagnostic endpoints. They are not
  Blockfrost-compatible surfaces and are outside this ADR's production scope.

## Decisions

### API Scope

Implement only read APIs backed by existing chainstate data. Do not add new
RocksDB indexes or new write paths for this API phase.

Add read-only provider abstractions where needed, but implement them using the
existing column families and keys:

- governance read provider for active proposals, DRep state, DRep distribution,
  and proposal votes,
- epoch stake read provider methods for point stake lookup and total stake scan.

The endpoint layer can format Bech32 ids using Cardano Client Lib helpers through
the shared `CardanoBech32Ids` utility. Bech32 ids should remain presentation
data and must not be stored in RocksDB.

### Proposal History Limitation

Current RocksDB state can support active proposals. It cannot support a complete
Blockfrost-compatible historical proposal list or complete proposal-by-epoch
lifecycle history because proposals and votes are removed from active state at
enact/drop/expiry processing.

The proposal submission metadata kept for DRep expiry calculation is not enough
for API responses. It has submission slot, epoch, and lifetime, but not proposal
body, deposit, return address, action type, votes, or final status.

Therefore:

- `active` proposal APIs are supportable now.
- `pending_ratified` and `pending_expired` can be exposed only as current
  pending lifecycle states, with limited epoch/status fields.
- complete `ratified`, `enacted`, `dropped`, and historical `expired` proposal
  APIs require a future history source.
- a future implementation may read existing parquet `proposal_status` exports
  when enabled, but the production API must return "not available" when those
  files are absent.

For this pass the proposal `status` query should support:

- `active`: current proposals not marked as pending enactment/drop,
- `pending_ratified`: proposals currently in the pending-enactment set,
- `pending_expired`: proposals currently in the pending-drop set,
- `all`: union of the three current-state sets.

Blockfrost historical statuses such as `ratified`, `enacted`, `dropped`, and
historical `expired` remain unsupported until a proposal lifecycle history
source exists. Do not silently map those historical statuses to current pending
state.

### AdaPot

Keep the existing Yano extension endpoints:

```text
GET /epochs/latest/adapot
GET /epochs/{number}/adapot
GET /epochs/adapots?from={epoch}&to={epoch}&page=1&count=20&order=asc
```

These are already cheap and production-shaped.

Add a partial Blockfrost-compatible `/network` endpoint. The initial endpoint
should expose only fields backed by existing data:

- `supply.max` from Shelley genesis `maxLovelaceSupply`,
- `supply.total` as `supply.max - latestAdaPot.reserves`,
- `supply.treasury` from latest AdaPot,
- `supply.reserves` from latest AdaPot,
- `stake.active` from latest retained active-stake snapshot aggregate.

Omit unsupported fields for now:

- `supply.circulating`,
- `supply.locked`,
- `stake.live`.

Do not return hardcoded or guessed values for omitted fields. If the DTO uses
nullable fields, configure JSON serialization to omit nulls.

### DRep Distribution

Support regular key-hash and script-hash DReps from the existing DRep
distribution snapshot. Virtual DReps are currently not stored in RocksDB DRep
distribution keys, so they should return `404` or a clear unsupported response
unless a future source is added.

Add a point lookup method against the existing DRep distribution key:

```java
Optional<BigInteger> getDRepDistribution(int epoch, int drepType, String drepHash)
```

This avoids scanning the entire epoch distribution for the common
`/governance/dreps/{drep_id}` read.

### Active Stake

Active stake means the epoch stake snapshot amount, not live current UTXO
balance. It is the value used for epoch/reward/governance calculations and is
stored as:

```text
UTXO controlled by stake credential + reward balance
```

The "current" active stake endpoint should use the latest available snapshot
epoch, not the moving chain tip's live UTXO state. If a requested epoch snapshot
has been pruned, return not available.

Do not promote `GET /api/debug/epoch-snapshot/{epoch}` directly. It returns
millions of entries on mainnet and is too expensive as a default production API.
Expose aggregate and point lookup endpoints first.

## Proposed Endpoints

All paths below are relative to `yaci.node.api-prefix`, default `/api/v1`.

### Proposals

Blockfrost-aligned current proposal list:

```text
GET /governance/proposals?status=active&page=1&count=20&order=asc
GET /governance/proposals?status=pending_ratified&page=1&count=20&order=asc
GET /governance/proposals?status=pending_expired&page=1&count=20&order=asc
GET /governance/proposals?status=all&page=1&count=20&order=asc
```

Initial support:

- default `status=active`,
- `page`, `count`, and `order` follow Blockfrost conventions,
- optional Yano extension filter `submitted_epoch={epoch}` filters proposals by
  `GovActionRecord.proposedInEpoch()`,
- return only proposals still present in current governance state.

Response fields should align with Blockfrost where available:

```json
{
  "id": "gov_action1...",
  "tx_hash": "...",
  "cert_index": 0,
  "governance_type": "parameter_change"
}
```

Specific current proposal:

```text
GET /governance/proposals/{tx_hash}/{cert_index}
```

Response fields should align with Blockfrost where available:

```json
{
  "id": "gov_action1...",
  "tx_hash": "...",
  "cert_index": 0,
  "governance_type": "parameter_change",
  "deposit": "100000000000",
  "return_address": "stake1...",
  "ratified_epoch": null,
  "enacted_epoch": null,
  "dropped_epoch": null,
  "expired_epoch": null,
  "expiration": 635
}
```

Use Cardano Client Lib for CIP-129 governance action id formatting in responses.
Implement the tx-hash/cert-index route first. Defer the one-part
`{gov_action_id}` route until a reliable decode helper is available.

Proposal votes for current proposals:

```text
GET /governance/proposals/{tx_hash}/{cert_index}/votes?page=1&count=20&order=asc
```

This is supportable while the proposal remains in current governance state.
Votes are removed when the proposal leaves active state.

Deferred proposal lifecycle endpoints:

```text
GET /governance/proposals?status=ratified
GET /governance/proposals?status=enacted
GET /governance/proposals?status=expired
GET /governance/proposals?status=dropped
GET /epochs/{number}/governance/proposals?status={status}
```

Do not implement these as full production APIs from RocksDB in this phase. The
`pending_ratified` and `pending_expired` statuses above are current pending
states only, not full Blockfrost historical proposal records.

### AdaPot

Use the existing endpoints:

```text
GET /epochs/latest/adapot
GET /epochs/{number}/adapot
GET /epochs/adapots?from={epoch}&to={epoch}&page=1&count=20&order=asc
```

Response:

```json
{
  "epoch": 627,
  "treasury": "1621148478340078",
  "reserves": "6410741060944473",
  "fees": "39737903986",
  "distributed_rewards": "6688719045189",
  "undistributed_rewards": "8435206026340",
  "rewards_pot": "18904906339411",
  "pool_rewards_pot": "15123925071529"
}
```

### Network

Blockfrost-aligned partial network endpoint:

```text
GET /network
```

Response:

```json
{
  "supply": {
    "max": "45000000000000000",
    "total": "38589258939055527",
    "treasury": "1621148478340078",
    "reserves": "6410741060944473"
  },
  "stake": {
    "active": "22987654321000000"
  }
}
```

Implementation detail:

- `supply.max` uses `NodeAPI.getGenesisParameters().maxLovelaceSupply()`,
- latest AdaPot provides `treasury` and `reserves`,
- `supply.total = supply.max - reserves`,
- `stake.active` uses the same latest retained snapshot aggregate as
  `/epochs/latest/stake/total`,
- omit `supply.circulating`, `supply.locked`, and `stake.live` until Yano has
  efficient backing aggregates for them.

### DRep Distribution

Blockfrost-aligned DRep reads:

```text
GET /governance/dreps?page=1&count=20&order=asc
GET /governance/dreps/{drep_id}
```

Use latest available DRep distribution epoch. List and detail responses should
align with the Blockfrost `drep` schema as much as current state allows:

```json
{
  "drep_id": "drep1...",
  "hex": "...",
  "amount": "123456789",
  "active": true,
  "active_epoch": 553,
  "has_script": false,
  "retired": false,
  "expired": false,
  "last_active_epoch": 620,
  "epoch": 627
}
```

Yano epoch-specific extension:

```text
GET /governance/dreps/{drep_id}/distribution
GET /governance/dreps/{drep_id}/distribution/{epoch}
```

Response:

```json
{
  "drep_id": "drep1...",
  "hex": "...",
  "epoch": 627,
  "amount": "123456789"
}
```

The endpoint must parse Bech32 or hex DRep ids in the API layer and resolve
`drepType` plus `drepHash` for the existing RocksDB key. It should not store
formatted ids.

### Total Active Stake

Yano aggregate extensions:

```text
GET /epochs/latest/stake/total
GET /epochs/{number}/stake/total
GET /epochs/{number}/stake/pool/{pool_id}
```

Response:

```json
{
  "epoch": 627,
  "active_stake": "22987654321000000",
  "snapshot_epoch": 627
}
```

Implementation detail:

- latest endpoint uses `LedgerStateProvider.getLatestSnapshotEpoch()`,
- by-epoch endpoint scans the epoch snapshot and sums `EpochDelegSnapshot.amount`,
- this is O(number of snapshot entries) and can be expensive on mainnet,
- do not return the full snapshot entry list in this endpoint.

If this endpoint becomes hot, add a future aggregate index. Do not add that write
path in this phase.

Blockfrost-compatible pool delegator rows:

```text
GET /epochs/{number}/stakes/{pool_id}?page=1&count=100&order=asc
```

Response rows:

```json
{
  "stake_address": "stake1...",
  "pool_id": "pool1...",
  "amount": "123456789"
}
```

This is also backed by a retained epoch snapshot scan. It is read-only and does
not add any new index in this phase.

### Stake Address Active Stake

Yano point lookup extensions:

```text
GET /accounts/{stake_address}/stake
GET /accounts/{stake_address}/stake/{epoch}
```

Response:

```json
{
  "stake_address": "stake1...",
  "epoch": 627,
  "amount": "74172449506030",
  "pool_id": "pool1...",
  "pool_hash": "...",
  "snapshot_epoch": 627
}
```

Implementation detail:

- parse the stake address to `(credType, credHash)`,
- point lookup `[epoch][credType][credHash]` in `cfEpochSnapshot`,
- decode `EpochDelegSnapshot(poolHash, amount)`,
- format `pool_id` through `CardanoBech32Ids.poolId(poolHash)`.

This endpoint is cheap and should be preferred over any full snapshot scan.

## Provider Changes Needed

Add read-only APIs only. No new writes or new persisted indexes in this phase.

Concrete `LedgerStateProvider` additions for stake snapshots:

```java
record EpochStake(int epoch, int credType, String credentialHash,
                  String poolHash, BigInteger amount) {}

default Optional<EpochStake> getEpochStake(int epoch, int credType, String credentialHash) {
    return Optional.empty();
}

default Optional<BigInteger> getTotalActiveStake(int epoch) {
    return Optional.empty();
}
```

Add a separate read-only governance provider in `node-api` so production
resources do not depend on `ledger-state` internals:

```java
interface GovernanceStateProvider {
    List<ProposalSummary> getProposals(String status, OptionalInt submittedEpoch);
    Optional<ProposalDetails> getProposal(String txHash, int certIndex);
    List<ProposalVote> getProposalVotes(String txHash, int certIndex);
    List<DRepInfo> getDReps();
    Optional<DRepInfo> getDRep(int drepType, String drepHash);
    Optional<BigInteger> getDRepDistribution(int epoch, int drepType, String drepHash);
    Optional<Integer> getLatestDRepDistributionEpoch(int maxEpoch);
}
```

`DefaultAccountStateStore` can implement this provider by delegating to the
existing `GovernanceStateStore`. The provider must expose immutable API records,
not `GovActionRecord` or `GovernanceStateStore` itself. Keep write-capable
governance store internals inside `ledger-state`.

Implementation notes:

- DRep Bech32 parsing should use Cardano Client Lib (`GovId.toDrep`) where
  possible. Bare 28-byte hex identifiers are ambiguous between key and script
  DReps; support them only with an explicit type parameter or by returning
  `400` when both key/script resolution are possible.
- `gov_action_id` formatting can use Cardano Client Lib
  (`GovId.govAction(txHash, index)`). The one-part route should wait for a
  reliable decode helper.
- Stake address parsing can reuse/extract the existing account resource parsing
  logic. Pointer-address UTXO balances are not part of stake credential active
  stake snapshots.
- `return_address` in governance proposal responses should be formatted as a
  reward/stake Bech32 address in the API layer where possible; keep the stored
  reward account hex as internal data.

## Error Semantics

Use these initial rules:

- `400` for invalid stake address, DRep id, proposal id, epoch, pagination, or
  order parameters.
- `404` when a well-formed object is not found or data is not available.
- `410` may be used later when the server can prove the requested epoch was
  pruned. If not provable, use `404` with `data not available or pruned`.
- `503` when the underlying subsystem is disabled, for example AdaPot,
  governance, or account-state snapshots.

Pagination should follow the existing API convention:

- `page >= 1`,
- `1 <= count <= 100`,
- `order=asc|desc`.

## Performance Review

| Endpoint | Expected cost | Production readiness |
| --- | --- | --- |
| `/epochs/{number}/adapot` | point lookup | Ready. |
| `/epochs/latest/adapot` | bounded backward epoch scan | Ready. |
| `/governance/proposals?status=active` | scan current proposal state | Acceptable; active proposal set is small. |
| `/governance/proposals?status=pending_ratified\|pending_expired` | scan small pending id set plus proposal point lookups | Acceptable; current-state only. |
| `/governance/proposals/{tx_hash}/{cert_index}` | point lookup | Ready for current proposals. |
| `/governance/proposals/{tx_hash}/{cert_index}/votes` | prefix scan for one current proposal | Acceptable with pagination. |
| `/governance/dreps` | current DRep state scan plus latest distribution merge | Acceptable if page/count are capped; revisit if DRep count grows sharply. |
| `/governance/dreps/{drep_id}` | point lookup after provider addition | Ready after read method. |
| `/governance/dreps/{drep_id}/distribution/{epoch}` | point lookup after provider addition | Ready after read method. |
| `/network` | latest AdaPot lookup + latest active stake aggregate scan | Supportable, with omitted unsupported fields. |
| `/epochs/{number}/stake/total` | full epoch snapshot scan | Supportable, but potentially expensive. |
| `/epochs/{number}/stakes/{pool_id}` | full epoch snapshot scan filtered by pool, sorted/page in memory | Supportable, but potentially expensive. |
| `/epochs/{number}/stake/pool/{pool_id}` | full epoch snapshot scan filtered by pool | Supportable, but potentially expensive. |
| `/accounts/{stake_address}/stake/{epoch}` | point lookup | Ready after read method. |
| full `/epochs/{number}/stakes` listing | full snapshot scan plus pagination problem | Defer; not requested for first pass. |
| historical proposal lifecycle by epoch | no RocksDB source | Defer until parquet/history provider or new index. |

## Implementation TODO

- [x] Add read-only provider method for stake credential active stake:
      `getEpochStake(epoch, credType, credentialHash)`.
- [x] Add read-only provider method for total active stake:
      `getTotalActiveStake(epoch)`.
- [x] Add `GET /accounts/{stake_address}/stake` using the latest retained
      snapshot epoch.
- [x] Add `GET /accounts/{stake_address}/stake/{epoch}` using a point lookup
      in `cfEpochSnapshot`.
- [x] Add `GET /epochs/latest/stake/total` using the latest retained snapshot
      epoch and a snapshot aggregate scan.
- [x] Add `GET /epochs/{number}/stake/total` using a snapshot aggregate scan.
- [x] Add `GET /epochs/{number}/stakes/{pool_id}` using a snapshot scan
      filtered by pool hash, returning Blockfrost-style paged delegator rows.
- [x] Add `GET /epochs/{number}/stake/pool/{pool_id}` using a snapshot
      aggregate scan filtered by pool hash.
- [x] Add `GET /network` with supported `supply.max`, `supply.total`,
      `supply.treasury`, `supply.reserves`, and `stake.active` fields.
- [x] Omit unsupported `/network` fields: `supply.circulating`,
      `supply.locked`, and `stake.live`.
- [x] Return `404` when the requested epoch snapshot is not retained or not
      available.
- [x] Add tests for point lookup, missing/pruned epoch, latest snapshot
      resolution, total active stake aggregation, pool active stake aggregation,
      and partial `/network` projection.
- [x] Add read-only governance proposal, vote, DRep, and DRep distribution
      endpoints backed by existing current state.
- [x] Add shared API-layer hex validation helpers in `CardanoHex`; keep actual
      encoding/decoding on the existing `HexUtil`.
- [x] Add shared `CardanoBech32Ids` helpers for pool ids, DRep ids, DRep
      Blockfrost hex projection, governance action ids, and address formatting.

## Implementation Phases

### Phase 1: Read Provider Plumbing

- Add a read-only governance provider abstraction in `node-api`.
- Implement it in `DefaultAccountStateStore` by delegating to the existing
  `GovernanceStateStore`.
- Add read-only epoch stake lookup and total-stake scan methods.
- Add DRep distribution point lookup using the existing key.
- Add latest DRep distribution epoch lookup using existing distribution keys
  only. Prefer a bounded seek/scan over loading all epochs.
- Add tests around read methods using small RocksDB fixtures.

No new chainstate writes should be introduced in this phase.

### Phase 2: Cheap Production Endpoints

- Add `GovernanceResource` for current proposals, proposal details, and proposal
  votes.
- Add DRep list, DRep detail, and DRep distribution endpoints.
- Add account active stake point lookup endpoints.
- Keep existing AdaPot endpoints as-is unless response polish is needed.
- Add resource tests with mocked providers or small fixture stores.

Do not implement the one-part `gov_action_id` route in this phase unless CCL
provides a reliable decoder. Include formatted `gov_action_id` in responses.

### Phase 3: Expensive Aggregate Endpoint

- Add total active stake endpoints.
- Add pool active stake endpoint.
- Add partial `/network` endpoint.
- Stream the snapshot scan and return only the aggregate.
- Add a clear warning in logs or metrics if the scan is slow.
- Revisit a persisted aggregate only if this endpoint becomes performance
  sensitive.

### Phase 4: Historical Proposal Source, Deferred

Choose one future source before implementing historical proposal endpoints:

- parquet-backed reader for existing `proposal_status` exports, enabled only
  when export files are configured and present,
- or a new rollback-safe RocksDB proposal lifecycle history index.

Do not expose complete `ratified`, `enacted`, `expired`, or `dropped` proposal
history until one of those sources exists.

### Phase 5: Deferred Blockfrost Surfaces

Defer these endpoints because they either need a history source, a new index, or
clearer supply semantics:

- `GET /governance/proposals/{gov_action_id}`,
- `GET /governance/dreps/{drep_id}/delegators`,
- full `GET /epochs/{number}/stakes` listing.

## Implementation Notes: 2026-04-30

Implemented the read-only phase without adding new chainstate write paths:

- `AccountStateReadStore` in `node-api` defines API-facing read records and
  read methods.
- `DefaultAccountStateReadStore` in `ledger-state` performs RocksDB reads and
  scans against existing column families.
- `DefaultAccountStateStore` delegates the new read-only API methods to the
  read store.
- `GovernanceResource` exposes current-state proposal, vote, DRep, and DRep
  distribution endpoints.
- `EpochResource` exposes total active stake, pool active stake aggregate, and
  Blockfrost-style pool delegator stake rows.
- `AccountStateResource` exposes stake-address active stake point lookups.
- `NetworkResource` exposes partial Blockfrost-compatible network supply/stake
  data.
- `CardanoHex` centralizes API hex validation. Encoding and decoding remain on
  the existing `HexUtil`.
- `CardanoBech32Ids` centralizes API-layer Cardano Client Lib formatting and
  parsing for stake, pool, DRep, governance action, and address ids.

Resource tests added or extended:

- account stake point lookups,
- governance proposals, votes, DReps, and DRep distribution,
- epoch stake total and pool stake reads,
- partial `/network`,
- shared hex and Bech32 helper projection.

Verification commands run:

```text
../gradlew :node-api:test :node-app:test --tests '*GovernanceResourceTest' --tests '*AccountStateResourceBalanceTest' --tests '*EpochResourceStakeTest' --tests '*NetworkResourceTest'
../gradlew :node-app:build
```

Both completed successfully. Test output still shows existing Quarkus/JBoss
Java 25 shutdown warnings and a warning for the local
`config/application.yml.working` file; they did not fail the build.

Runtime smoke test against the local preprod jar and Blockfrost preprod:

- `/network` supply fields matched Blockfrost for `max`, `total`, `treasury`,
  and `reserves`.
- `/network.stake.active` did not match Blockfrost. Yano currently returns the
  latest retained epoch snapshot aggregate (`/epochs/latest/stake/total`, epoch
  284 in this run), while Blockfrost reports latest epoch active stake. This
  needs a follow-up semantics/index decision before changing write logic.
- Sample proposal
  `3e0090440118719ea4192e4c14b6c2568eab560f50982d18fb5dbd5fdba54360#0`
  matched Blockfrost for id, tx hash, cert index, governance type, deposit,
  return address, and expiration after the read-only projection fix.
- Sample DRep
  `drep1ygpuetneftlmufa97hm5mf3xvqpdkyw656hyg6h20qaewtg3csnkc` matched
  Blockfrost for DRep id, Blockfrost hex, amount, active, active epoch,
  has-script, retired, expired, and last-active-epoch after the read-only
  projection fix.

Review findings to carry forward:

- DRep distribution records are written as snapshots but are not currently
  rollback-cleaned by a dedicated delta. The new API only reads this data; a
  future write-path fix should make DRep distribution rollback semantics explicit
  before relying on old epochs after rollback.
- Proposal lifecycle history is incomplete from current RocksDB state. Active
  and pending current-state proposals are available, but historical ratified,
  enacted, dropped, and expired proposal records require a history source.
- Proposal vote API can read current votes by proposal, but the current stored
  value does not retain the vote transaction hash/cert index needed for exact
  Blockfrost vote schema.
- Proposal `governance_description` is not projected yet. Exact Blockfrost
  description output needs action-specific JSON projection from the stored
  governance action body.
- Expensive stake endpoints scan retained snapshots and sort/page in memory.
  They are acceptable for initial testing but should get persisted aggregates or
  bounded streaming if they become hot on mainnet.
- Governance reads are not performed under a single RocksDB snapshot, so a read
  during an epoch-boundary commit can theoretically observe mixed current-state
  data. This can be improved later with an explicit read snapshot.

## Open Questions

1. Should total active stake scans be enabled by default, or protected by a
   separate API flag because each request scans a full epoch snapshot?
2. Should proposal lifecycle history prefer parquet reads first, or should Yano
   add a small rollback-safe lifecycle index later?
3. Should `/network` later grow persisted aggregates for `supply.circulating`,
   `supply.locked`, and `stake.live`, or should those fields remain omitted
   until another indexing mode is introduced?
