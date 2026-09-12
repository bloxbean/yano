# Wallet discovery & scans

Coverage-aware first-seen lookup and streaming wallet scans.

Canonical URL: https://getyano.dev/node/wallet-indexes/

These indexes are preview functionality. The general node defaults keep
both indexes disabled. The wallet profile enables both and no longer enables
archival history projection. Production resource validation remains outstanding.

## Upgrading from the pre-contributor wallet index

The contributor-based wallet index requires a **fresh sync database**. Existing
wallet tables do not contain the new host-owned availability/rollback metadata;
their presence does not prove complete index history. Upgrading the executable
alone will leave these indexes unavailable, and later blocks will not repair them.
Startup warns when existing wallet history is unavailable under the new gate.

Stop the node, retain the old database as a backup, configure a new storage path,
and sync with the wallet flags enabled from the beginning. Do not run two nodes
against the same database. There is no migration or automatic backfill in preview.

## Configuration

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

External indexes use `yano.utxo.index-contributors`, separately from the built-in
wallet flags above. A JAR in `plugins/` is discovered but does not automatically
activate its contributor:

```yaml
yano:
  utxo:
    enabled: true
    index-contributors:
      - type: example.output-index
        enabled: true
        config: {}
```

`type` is the provider selector; `enabled` defaults to false; `config` contains
plugin-specific scalar values. `wallet` is reserved and cannot appear in this list.
Plugin allow/deny policy still applies. Restart after changing selection, and use
a fresh sync for complete history. See the
[external example](https://github.com/bloxbean/yano/blob/fd7fe406e9364689a3e829b79f82707488cebf1e/examples/utxo-output-index/README.md).

Address-decoding failures retain one representative error per feature and block,
not a complete list of bad addresses. Good addresses in that block are still
indexed. Filter scans report incomplete blocks; first-seen queries cannot claim
historical completeness while a relevant error remains.

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
- `warning`: a block whose wallet indexing or scan extraction is incomplete, with
  its canonical `point`, diagnostic `error`, and `complete: false`.
- `done`: successful completion at the exact pinned end, with `complete: true`.
- `incomplete`: terminal partial results at the pinned end, with `complete: false`.
  No `done` follows this record. Do not advance a durable recovery cursor.
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
Save the cursor and this state atomically, only after validating `done` with
`complete: true`. The node
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

Known per-block extraction failures do not stop indexing other addresses or later
blocks. The same atomic block batch stores successfully decoded first-seen rows,
a partial credential filter, and a diagnostic in the `wallet_index_errors` RocksDB
column family. Keys are index-kind plus block number; values contain the block hash
and a representative failure reason. The repair unit is the full block, so this is
not a separate queue entry for every failed output. Canonical block bodies retain
the original transaction/output details. Errors are retained across restarts and
undo pruning, and removed with reverted blocks in the atomic rollback batch.

A scan crossing a recorded error emits a warning and reads that block regardless
of filter matches, returning whatever confirmed matches it can extract. Affected
streams finish with `incomplete`; ranges entirely before or after the error can
finish with `done` (resumed scans still require complete `knownOutputs`). The
`ready.coverage` coordinates describe structural index continuity; only the terminal
record establishes whether the requested scan was complete. First-seen answers
remain unavailable while their history contains unresolved first-seen errors.

There is no automatic repair operation yet. Upgrading does not reconstruct filters
or origin completeness already lost by older versions. Unknown gaps, missing block
bodies, corrupt metadata and reorgs still fail closed rather than returning a
successful partial scan.

On reorg, discard uncommitted stream changes. Retry an earlier saved canonical
cursor together with its matching history/outpoint snapshot. If none survives,
restart at origin. Hash validation catches a reorg even when the client missed
its notification. The wallet currently retains two durable boundaries and can
restart at origin for deeper reorgs; it does not mix an earlier cursor with newer
outpoints. Changing chain identity invalidates that saved state.

## Scope and resources

Stake scanning is suitable for ordinary CIP-1852 base addresses. It does
not cover enterprise addresses or resolve pointer stake credentials. A stake
match does not prove payment ownership. DRep support covers the named certificate
subjects, not all votes or governance events. Byron wallet recovery, epoch rewards,
nontransaction refunds and arbitrary historical transaction-by-hash lookup are
outside this API. No global transaction-location index is created.

Address decoding and payment/stake credential extraction use Cardano Client Lib
(CCL), including its `ByronAddress` decoder. Historical addresses may contain
trailing bytes: these are retained for exact-address identity without imposing a
separate strict address parser. Pointer addresses contribute their payment
credential only; stake-pointer resolution remains unsupported and does not
invalidate scan coverage.

Planning estimates remain 2–3 GB for filters and roughly 100–200 bytes per distinct
ever-seen address, plus undo/metadata and temporary WAL/compaction headroom. These
costs are additive; retaining bodies also costs disk if switching from a pruning
configuration. They are planning estimates, not measured production resource recommendations. Validate disk growth, retained bodies, and scan workload for your deployment.
