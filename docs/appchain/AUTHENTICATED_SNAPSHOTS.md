# Authenticated Snapshots

Authenticated snapshots are an optional cross-cutting app-chain capability for large, immutable
period datasets. They keep a small descriptor chain in the application's primary authenticated
state and place each period in a separate logical authenticated store. Cardano History uses this
for epoch stake and DRep distributions; custom applications can declare their own canonical series.

Without this capability, state machines behave as before. Enabling it is a fresh-chain choice and
does not add hidden writes to other applications.

## Storage and verification profiles

| Profile | Verification | Typical use |
|---|---|---|
| `mpf-blake2b256-v1` | Off-chain and Cardano on-chain | Public claims anchored through the SCRIPT state root |
| `jmt-blake2b256-v1` | Off-chain only | Lower online storage where validator verification is not required |

Every sealed descriptor binds the chain genesis and application identity, series and sequence,
schema, dataset epoch/boundary, source dataset commitment, secondary profile/fingerprint/proof wire,
entry count, coverage, previous descriptor, secondary root, and completion status. A nested proof
therefore consists of the L1-anchored primary root, a primary proof for the descriptor, a secondary
fact proof, and the canonical semantic predicate.

An absence claim is accepted only when the same descriptor proves the dataset complete. The MPF
reference validator binds descriptor and fact coordinates through script parameters; they are not
caller-selected redeemer metadata.

Every declared series must also provide exactly one
`AuthenticatedSnapshotSourceCommitmentV1`. The runtime folds each canonical chunk through that
adapter and refuses to seal unless the recomputed source commitment equals the value declared at
`begin`. A custom state machine therefore cannot turn an arbitrary claimed source hash into an
authenticated descriptor merely by supplying matching secondary entries. Adapter algorithm and
wire-version identifiers are immutable series identity and must match exactly at startup.

## Configuration

The capability is chain-local. A state machine must declare at least one matching series.

```properties
yano.app-chain.chains[0].capabilities.authenticated-snapshots.enabled=true
yano.app-chain.chains[0].capabilities.authenticated-snapshots.series=l1-epoch-stake-v1.distribution,l1-epoch-governance-v1.drep-distribution
yano.app-chain.chains[0].capabilities.authenticated-snapshots.archive-directory=/srv/yano/appchain-snapshot-archives

yano.app-chain.chains[0].capabilities.authenticated-snapshots.proof-service.concurrency=32
yano.app-chain.chains[0].capabilities.authenticated-snapshots.retention.enabled=false
yano.app-chain.chains[0].capabilities.authenticated-snapshots.retention.keep-online-count=10
yano.app-chain.chains[0].capabilities.authenticated-snapshots.retention.evict-after-archive=true
yano.app-chain.chains[0].capabilities.authenticated-snapshots.retention.interval-seconds=3600

# Experimental, MPF-only archive compaction. Disabled by default.
yano.app-chain.chains[0].capabilities.authenticated-snapshots.storage.mpf-pruning-enabled=false

# Independently assignable lifecycle-administration credential realm.
yano.app-chain.api.snapshot-admin-keys=<dedicated-operator-key>
```

The profile belongs to the state-machine series declaration. In the Cardano History showcase
product (index is deployment-specific):

```properties
yano.app-chain.chains[12].machines.epoch-stake.snapshot-profile=mpf-blake2b256-v1
yano.app-chain.chains[12].machines.epoch-governance.drep-snapshot-profile=mpf-blake2b256-v1
```

Node-local archive, retention, proof concurrency, and pruning choices are excluded from commitment
identity. Series/schema/profile selection affects canonical state and must agree at genesis.

## HTTP and client surface

All read paths use ordinary app-chain read authorization. Lifecycle changes require the dedicated
`snapshot-admin` access level, configured separately from generic full and submit-scoped keys.

| Method and path below `/app-chain/chains/{chainId}` | Result |
|---|---|
| `GET /snapshots?series=&cursor=&limit=` | Root-bound, opaque-cursor catalog page |
| `GET /snapshots/{series}/{sequence}` | Canonical descriptor |
| `POST /snapshots/{series}/{sequence}/proof` | Primary + secondary root-fixed proof bundle |
| `POST /snapshots/proof/verify` | Strict verification using either a node-confirmed anchor or caller-pinned root/identity |
| `GET /snapshots/status` | Series/profile, local lifecycle, proof capacity, retention health |
| `POST /admin/snapshots/{series}/{sequence}/archive` | Queue verified archive creation |
| `POST /admin/snapshots/{series}/{sequence}/restore` | Queue verified local restore |
| `POST /admin/snapshots/{series}/{sequence}/evict` | Queue rollback-safe local eviction |
| `GET /admin/snapshots/jobs` | List durable lifecycle jobs |
| `GET /admin/snapshots/jobs/{jobId}` | Inspect one durable job |

Catalog cursors bind filter, height, root, series, and next sequence. They cannot silently continue
against a different root. Proof serving uses a bounded permit pool; overload returns HTTP 429 with
`Retry-After`. A snapshot that is not online returns unavailable, never a false absence.

Proof generation accepts `{ "keyHex": "...", "anchorHeight": 123 }`; `anchorHeight` is optional.
When present, it selects that exact retained, L1-confirmed primary root instead of the latest one.
The pure verification endpoint consumes the canonical bundle returned as `bundleCborHex`:

```json
{
  "bundleCborHex": "...",
  "trustMode": "local-anchor"
}
```

For `caller-pinned-root`, provide the complete independently authenticated context:
`expectedChainId`, `expectedAnchorMode`, `expectedPrimaryProfile`, `expectedPrimaryRootHex`,
`expectedChainGenerationIdHex`, `expectedApplicationProfileDigestHex`, `expectedAnchoredHeight`,
`expectedBlockHashHex`, `expectedAnchorTransactionHash`, and `expectedL1Slot`. Unsupported fields and
non-canonical or oversized CBOR are rejected; individual proof fields cannot be substituted around
the canonical bundle.

The Java `AppChainClient` exposes the same catalog, descriptor, proof, verification, and admin-job
operations. Product-specific clients should translate semantic coordinates but must reuse this
proof surface rather than invent a second proof format.

The verification request defaults to `trustMode=local-anchor`. For offline or independent use,
select `caller-pinned-root` and supply the complete anchor context listed above; the endpoint then
performs cryptographic verification against those caller-pinned inputs and clearly returns that
trust label. The caller remains
responsible for authenticating the root from Cardano. Prefer the Java verifier when the complete L1
anchor-validation workflow runs outside the proof-serving node.

## Archive, restore, and optional MPF pruning

Archive/restore/evict are node-local operations and do not change consensus state. Members may use
different online retention policies while agreeing on the same descriptor and app root.

An archive is published only after the complete root-reachable store is opened and its identity,
entry count, and root are verified. Unreachable construction garbage is never copied into an MPF
archive. Reachable corruption fails the operation. The temporary archive is read and verified
before atomic publication. Archives are physically namespaced by a digest of chain ID, chain
generation, application profile, series, and sequence, preventing generations from sharing a file.
Restore validates bounds, profile, descriptor, records, and reconstructed root before making the
snapshot online. The lifecycle is durably set to `RESTORING` before importing records, proof reads
remain blocked by the write lease and lifecycle gate, and interrupted restore is reconciled to
unavailable/archived-only on restart before it can be retried. A partial import is never advertised
as online.

Every MPF archive traverses the sealed root and contains only reachable nodes. Optional MPF pruning
additionally deletes unreachable nodes from the online RocksDB namespace after verification; it
does not change the archive format or authenticated root. The production-adapter 1.3M-entry qualification
produced a roughly 272 MB portable MPF archive and restore reproduced byte-identical proofs. Online pruning
remains disabled by default while multi-epoch retained-root and public-network soak evidence
accumulates.

Eviction is permitted only after finality and outside rollback retention. When policy requires an
archive, the archive must reopen and verify first. Removing one member's local copy does not require
a consensus message and does not force peers to remove theirs.

## Showcase

```bash
# MPF, with on-chain-capable nested proofs. Snapshot-capable datasets are
# selected explicitly by the Cardano History preset.
./showcase.sh quickstart --profile light --instance history-mpf \
  --cardano-history-profile full

# Optional MPF archive-time reachable-node pruning.
./showcase.sh quickstart --profile light --instance history-mpf-pruned \
  --cardano-history-profile full \
  --enable-authenticated-snapshot-mpf-pruning=cardano-history-chain

# JMT, off-chain verification only.
./showcase.sh quickstart --profile light --instance history-jmt \
  --cardano-history-profile full \
  --authenticated-snapshot-profile jmt-blake2b256-v1
```

Use `./showcase.sh snapshots status|list|descriptor|archive|restore|evict|job` for the packaged
operator flow. Use the native Cardano History console for proof generation/export, or the standalone
CLI for portable proof and independent-anchor verification. The deployment marker retains the
selected series/profile and node-local pruning scope so restarts cannot silently target another
prepared instance.

## Operational rules

- Back up the primary app-chain database and snapshot archives as one recovery plan. A descriptor
  without any available archive remains verifiable from already-issued proofs but cannot serve a
  new proof locally.
- Monitor proof saturation, archive jobs, local lifecycle, free disk, and latest complete sequence.
- Do not infer absence from a missing HTTP value. Require a valid non-membership proof and complete
  descriptor at the anchored root.
- JMT proofs must be labelled off-chain only. A chain requiring Cardano validator consumption must
  use MPF at both primary and secondary levels.
- Deep rollback below finalized epoch attestations is fail-closed. Production release remains gated
  by the ADR-027 deep-rollback and long-running public-network qualification requirements. The
  runtime persists a `DISPUTED` lineage latch, refuses proof/archive/restore/evict operations after
  the violation, and reports the retained reason after restart.
- Proof-service concurrency covers the complete nested operation: the request must obtain a permit
  before either the historical primary descriptor proof or the secondary proof is generated.
- Ordinary application writes and logical snapshot operations share the configured per-block
  operation and byte budgets. Generated backend nodes have a separate bounded physical-work limit;
  implementations must not describe that internal expansion as extra consensus messages.
- Source commitment providers that claim the same series across composed machines are reusable only
  when algorithm, wire version, and `compatibilityId()` match exactly. This permits identical
  implementations across generations while rejecting semantically different adapters.
- Startup reconciles epoch observations left in proposer `OFFERED` or follower `READY` state after
  a crash by scanning finalized app-block execution contexts
  before re-offering work. This closes the block-commit/spool-ack
  window without requiring an L1 publisher callback to block. A durable app-height cursor starts
  at job creation and advances after each scan, so normal reconciliation never rescans genesis or
  monopolizes the spool monitor during block reads.
- Observation jobs are offered in epoch order independently for each observer. A later stake (or
  governance) epoch is never opened while that observer still has an unfinalized logical snapshot,
  but different observers may progress concurrently. During certified historical catch-up, a late
  member may lack an epoch-boundary job that occurred before its retained L1 window; the threshold
  certificate can vouch for that missing fact. An existing local job with different bytes remains a
  hard mismatch, and live proposals never receive this relaxation.
- Snapshot lifecycle jobs hold a generation-use lease until their RocksDB work completes. Completed
  audit records are bounded to the newest 4,096 entries; active jobs are never pruned. Each retained
  job records a SHA-256-derived, non-secret principal identifier, never the API key itself.
- A Cardano anchor datum independently authenticates chain/application/profile, height, block hash,
  and state root. The archive transaction hash and L1 slot in the transport are locator metadata
  unless the caller also verifies the selected Cardano output/transaction and supplies them through
  the complete caller-pinned context.
- The local `DISPUTED` lineage latch applies only to `local-anchor` verification. A complete
  `caller-pinned-root` context is evaluated independently; its response marks local dispute status
  as not applicable rather than allowing an unrelated serving-node lineage to downgrade the result.
- The Java verifier is the independent trust boundary. It checks release-matched primary and
  secondary profile metadata, genesis identities, application-profile digest, descriptor key and
  bytes, proof wires, anchor coordinates, and statement/bundle commitments against a caller-trusted
  L1 root. The server verification endpoint is a node-assisted diagnostic and is not a substitute
  for independently validating the Cardano anchor transaction.

## Qualification evidence and limits

The retained 1.3M-entry MPF and JMT measurements exercise the secondary runtime directly: ingest,
restart, archive, eviction, restore, parallel proof generation, and local cryptographic
verification. Their QPS and latency figures are **not HTTP end-to-end throughput**. HTTP hot/mixed,
cold-restore, 1/8/32/64-client overload, memory/file-descriptor/IOPS, and concurrent-consensus impact
remain separate cluster qualification evidence and must be labelled as such in release reports.
