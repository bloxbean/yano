# ADR-019: Governance, AdaPot, DRep Distribution, and Active Stake REST APIs

## Status

Draft

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

### AdaPot

Keep the existing Yano extension endpoints:

```text
GET /epochs/latest/adapot
GET /epochs/{number}/adapot
GET /epochs/adapots?from={epoch}&to={epoch}&page=1&count=20&order=asc
```

These are already cheap and production-shaped.

Add a separate Blockfrost-compatible `/network` endpoint only if we want a
Blockfrost-shaped current network view. That endpoint can map current AdaPot
treasury/reserves into `supply.treasury` and `supply.reserves`, but full
Blockfrost `/network` parity would also need circulating, locked, and supply
figures that are outside this ADR.

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

Blockfrost-aligned current active proposal list:

```text
GET /governance/proposals?status=active&page=1&count=20&order=asc
```

Initial support:

- default `status=active`,
- `page`, `count`, and `order` follow Blockfrost conventions,
- optional Yano extension filter `submitted_epoch={epoch}` may filter active
  proposals by `GovActionRecord.proposedInEpoch()`,
- return only proposals still present in active state.

Response fields should align with Blockfrost where available:

```json
{
  "id": "gov_action1...",
  "tx_hash": "...",
  "cert_index": 0,
  "governance_type": "parameter_change"
}
```

Specific active proposal:

```text
GET /governance/proposals/{tx_hash}/{cert_index}
GET /governance/proposals/{gov_action_id}
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

Use Cardano Client Lib for CIP-129 governance action id formatting if available.
If the library does not expose a stable helper, implement the tx-hash/cert-index
routes first and defer `{gov_action_id}` until the id helper is verified.

Proposal votes for active proposals:

```text
GET /governance/proposals/{tx_hash}/{cert_index}/votes?page=1&count=20&order=asc
```

This is supportable for active proposals only. Votes are removed when the
proposal leaves active state.

Deferred proposal lifecycle endpoints:

```text
GET /governance/proposals?status=ratified
GET /governance/proposals?status=enacted
GET /governance/proposals?status=expired
GET /governance/proposals?status=dropped
GET /epochs/{number}/governance/proposals?status={status}
```

Do not implement these as full production APIs from RocksDB in this phase.
Current pending ratified/expired ids can be exposed later as a Yano extension,
but they are not full Blockfrost historical proposal records.

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
  "deposits": "4418026000000",
  "fees": "39737903986",
  "distributed_rewards": "6688719045189",
  "undistributed_rewards": "8435206026340",
  "rewards_pot": "18904906339411",
  "pool_rewards_pot": "15123925071529"
}
```

### DRep Distribution

Blockfrost-aligned DRep read:

```text
GET /governance/dreps/{drep_id}
```

Use latest available DRep distribution epoch. Response should align with the
Blockfrost `drep` schema as much as current state allows:

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

Possible `LedgerStateProvider` additions:

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

Possible new governance read provider in `node-api`:

```java
interface GovernanceStateProvider {
    List<ProposalSummary> getActiveProposals();
    Optional<ProposalDetails> getActiveProposal(String txHash, int certIndex);
    List<ProposalVote> getProposalVotes(String txHash, int certIndex);
    Optional<DRepInfo> getDRep(int drepType, String drepHash);
    Optional<BigInteger> getDRepDistribution(int epoch, int drepType, String drepHash);
    Optional<Integer> getLatestDRepDistributionEpoch(int maxEpoch);
}
```

Expose this provider through `NodeAPI` or through an account-state provider
wrapper. Keep write-capable `GovernanceStateStore` internal to ledger-state.

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
| `/governance/proposals?status=active` | scan current active proposals | Acceptable; active proposal set is small. |
| `/governance/proposals/{tx_hash}/{cert_index}` | point lookup | Ready for active proposals. |
| `/governance/proposals/{tx_hash}/{cert_index}/votes` | prefix scan for one active proposal | Acceptable with pagination. |
| `/governance/dreps/{drep_id}` | point lookup after provider addition | Ready after read method. |
| `/governance/dreps/{drep_id}/distribution/{epoch}` | point lookup after provider addition | Ready after read method. |
| `/epochs/{number}/stake/total` | full epoch snapshot scan | Supportable, but potentially expensive. |
| `/accounts/{stake_address}/stake/{epoch}` | point lookup | Ready after read method. |
| full `/epochs/{number}/stakes` listing | full snapshot scan plus pagination problem | Defer; not requested for first pass. |
| historical proposal lifecycle by epoch | no RocksDB source | Defer until parquet/history provider or new index. |

## Implementation Phases

### Phase 1: Read Provider Plumbing

- Add a read-only governance provider abstraction.
- Add read-only epoch stake lookup and total-stake scan methods.
- Add DRep distribution point lookup using the existing key.
- Add tests around read methods using small RocksDB fixtures.

No new chainstate writes should be introduced in this phase.

### Phase 2: Cheap Production Endpoints

- Add `GovernanceResource` for active proposals and active proposal details.
- Add DRep read endpoints.
- Add account active stake point lookup endpoints.
- Keep existing AdaPot endpoints as-is unless response polish is needed.
- Add resource tests with mocked providers or small fixture stores.

### Phase 3: Expensive Aggregate Endpoint

- Add total active stake endpoints.
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

## Open Questions

1. Should Yano add a Blockfrost-compatible `/network` endpoint now with partial
   supply data, or keep AdaPot as the explicit Yano extension for treasury and
   reserves?
2. Should total active stake scans be enabled by default, or protected by a
   separate API flag because each request scans a full epoch snapshot?
3. Should proposal lifecycle history prefer parquet reads first, or should Yano
   add a small rollback-safe lifecycle index later?

