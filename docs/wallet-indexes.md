# Optional wallet indexes

Implementation is under validation for issue #119. The general node defaults keep
both indexes disabled. The wallet profile enables both and no longer enables
archival history projection. Production resource validation remains outstanding.

Enable the desired capabilities **before the first sync into a fresh database**:

```yaml
yano:
  address-first-seen:
    enabled: true
  scan:
    index:
      enabled: true
    max-concurrent: 2
  utxo:
    enabled: true
  filters:
    utxo:
      enabled: false
  chain:
    block-body-prune-depth: 0
```

`yano.filters.utxo.enabled` controls selective UTxO storage, which must be disabled
for these indexes. It is unrelated to the new per-block scan filter. Plugin UTxO
filters are likewise incompatible. No archival/history backend is needed.

The features are independent: first-seen needs its own continuous history, while
scans need their filters and retained canonical block bodies. First-seen survives
spending and ordinary body pruning. Scan queries that cross missing coverage or
unavailable bodies fail; they do not silently scan all bodies as a fallback.

Existing databases do not acquire complete coverage by enabling a flag. A fresh
sync is required. Turning a flag off while canonical blocks advance breaks its
continuity. Turning it off and back on without missing blocks can preserve the
existing proof. There is no index backfill or automatic historical repair.

## Canonical coordinates and coverage

Coordinates are JSON objects with `blockNumber`, `slot`, and a lowercase 64-digit
hex `blockHash`. Origin is exactly:

```json
{"blockNumber":-1,"slot":0,"blockHash":"0000000000000000000000000000000000000000000000000000000000000000"}
```

Slot zero is a real slot. Origin is distinguished by block number -1. Coverage
contains `enabled`, `completeFromOrigin`, `from`, `indexedThrough`, `identity`, and
`unavailableReason`. Both the indexed coordinate and live tip are reported so a
client can detect lag. A query is authoritative only through its indexed point.

## Exact-address first-seen

```http
GET /api/v1/addresses/{address}/first-seen
```

The response contains `firstSeenSlot`, `coverage`, and `liveTip`. A number,
including zero, means the complete decoded address received an effective output
at that slot. `null` means it has never received one through `indexedThrough`,
only when origin-to-point completeness is established. Addresses sharing a payment
credential remain distinct. Valid outputs, collateral returns and applicable
genesis outputs count; ordinary outputs of phase-2-invalid transactions do not.

Malformed addresses return 400. Disabled, missing, incompatible or incomplete
indexes return 503, never an authoritative null or a misleading positive slot.
A wallet must not count a null toward its discovery gap if the index is behind
the live tip. Receive and change branches retain independent discovery counters.

## Streaming transaction scan

```http
POST /api/v1/scan
Content-Type: application/json
Accept: application/x-ndjson
```

Example initial request; replace the credential hash with the account's stake hash:

```json
{
  "version": 1,
  "credentials": [{"role":"stake","type":"key","hash":"01010101010101010101010101010101010101010101010101010101"}],
  "after": {"blockNumber":-1,"slot":0,"blockHash":"0000000000000000000000000000000000000000000000000000000000000000"},
  "knownOutputs": []
}
```

`after` is required and exclusive. Optional `to` is inclusive and must be within
available coverage; omitted `to` pins the current indexed end. Credentials contain
`role` (`payment`, `stake`, or the scoped `drep` support below), `type` (`key` or
`script`), and a 28-byte hex hash. Supply 1–200 credentials.

The response is newline-delimited JSON, with these record types:

- `ready`: the effective end in `point`, plus coverage and live tip.
- `genesis`: applicable genesis outputs for an origin scan, without a transaction hash.
- `transaction`: confirmed `txHash`, canonical `point`, `blockTime` (Unix seconds),
  `valid`, effective `inputs` and `outputs`.
- `progress`: the most recently processed point.
- `done`: successful completion at the exact pinned end.
- `rollback` or `error`: unsuccessful termination; do not advance durable state.

Inputs are outpoints (`txHash`, `index`). Outputs include their outpoint, address,
lovelace, assets (`policyId`, `assetName`, `quantity`), creation slot/block/hash,
collateral-return flag, and available datum/reference-script fields. A matching
transaction includes all its effective inputs and outputs, including unrelated
addresses. Clients must independently check ownership before counting funds.
Preserve asset quantities as arbitrary-precision integers.

An origin scan seeds applicable genesis funds. A resumed scan must supply
`knownOutputs`: the **complete relevant unspent output set at `after`**, using the
same output objects from prior records. The maximum is 10,000 tracked outputs.
Save the cursor and this state atomically, only after validating `done`. The node
checks structural validity, creation bounds, query relevance and duplicate
outpoints; it cannot prove that a caller did not deliberately omit a relevant
output. A cursor alone cannot establish outgoing attribution. Never resume from a
progress record without the matching complete state.

The server limits active scans with `yano.scan.max-concurrent` (default 2, range
1–16). Blocking streaming runs off the HTTP I/O thread; output backpressure slows
the scan. Disconnect/cancellation releases request state. Invalid requests return
400, orphaned cursors 409, and unavailable coverage/bodies/capacity 503 when detected
before streaming. Failures discovered after headers produce a terminal error or
rollback record when possible. EOF, a timeout, or an incomplete final line is
**not** successful completion, even if some transactions arrived.

On reorg, discard uncommitted stream changes. Retry an earlier saved canonical
cursor together with its matching history/outpoint snapshot. If none survives,
restart at origin. Hash validation catches a reorg even when the client missed
its notification. The wallet currently retains two durable boundaries and can
restart at origin for deeper reorgs; it does not mix an earlier cursor with newer
outpoints. Changing chain identity invalidates that saved state.

## Scope and resources

See [ADR-054](../adr/054-optional-wallet-indexes.md) for the exact credential-event
matrix. Stake scanning is suitable for ordinary CIP-1852 base addresses. It does
not cover enterprise addresses or resolve pointer stake credentials. A stake
match does not prove payment ownership. DRep support covers the named certificate
subjects, not all votes or governance events. Byron wallet recovery, epoch rewards,
nontransaction refunds and arbitrary historical transaction-by-hash lookup are
outside this API. No global transaction-location index is created.

Planning estimates remain 2–3 GB for filters and roughly 100–200 bytes per distinct
ever-seen address, plus undo/metadata and temporary WAL/compaction headroom. These
costs are additive; retaining bodies also costs disk if switching from a pruning
configuration. They are not measured production recommendations. See the
[validation report](../adr/reports/119-wallet-index-validation.md) for completed
checks and outstanding performance gates.

## Reproducing the storage spike

Run only against a new output directory:

```sh
./gradlew :runtime:benchmarkWalletIndexes \
  -PwalletBenchmarkDirectory=/private/tmp/yano-wallet-benchmark-new \
  -PwalletBenchmarkBlocks=1000000
```

The task refuses an existing destination and retains all four databases. It uses
a 1 GiB JVM heap and the production RocksDB column-family setup. Each mode commits
one batch per synthetic block with an identical baseline cursor write. Enabled
first-seen processing observes four addresses per block from one million distinct
base addresses; credentials and block hashes have deterministic pseudorandom
bytes, avoiding artificial compression from zero-filled hashes. Filter cardinality
cycles through 0, 8, 40 and 80. Undo retention is 86,400 blocks.

The report records apply wall/CPU time, per-block p50/p99 latency, main-thread
allocation, GC time, logical batch bytes, compacted SST sizes per wallet column
family, sampled peak directory size, and post-close directory size. Raw RocksDB
statistics retain compaction and stall counters. Scan passes reopen the database
and then repeat for query cardinalities 1, 10 and 200; every filter is physically
read and checked for continuity. Queries are intentionally disjoint from inserted
elements, so candidate counts measure false positives.

This spike excludes ledger validation, effective credential extraction, normal
UTxO writes, canonical body reads and wallet confirmation. It is not historical
sync throughput. Address generation is outside the timed apply section; modes
run in a fixed order and are not a statistically controlled CPU comparison.
Reopening clears RocksDB caches but does not evict the OS page cache. Peak disk
is sampled, not an exact upper bound, and logical batch bytes are distinct from
physical disk writes. Use the raw RocksDB counters alongside those measurements.
Representative-era replay, true cold storage, native performance, and contention
with live sync are deferred follow-up validation; the owner accepted devnet
validation for the implementation handoff and will test preprod sync manually.

Native asset `policyId` and `assetName` values in scan outputs use lowercase hex,
matching the UTxO API. An empty asset name is the empty string; arbitrary binary
asset names are preserved without decoding them as display text.
