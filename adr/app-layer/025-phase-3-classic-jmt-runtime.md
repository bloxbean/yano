# ADR-025 Phase 3 — classic Blake2b-256 JMT runtime

Status: implemented and qualified against the pinned CCL development baseline.
This is not production approval while CCL `0.8.0-pre5-dev1` remains a development
release.

## 1. Implemented boundary

The runtime now accepts the explicit `jmt-blake2b256-v1` chain-generation
identity and requires CCL's exact `classic-radix16-blake2b256-v1` persistent
format descriptor. `jmt-poseidon-bls12381-v1` remains fail-closed before
RocksDB is opened.

`SharedRocksDbJmtStore` implements the public CCL `JmtStore` contract over five
column families owned by the app ledger:

- `jmt_nodes`;
- `jmt_values`;
- `jmt_roots`;
- `jmt_stale`; and
- `jmt_metadata`.

CCL alone calculates hashes, nodes, stale-node indexes, roots, and native proof
bytes. Its normal `CommitBatch.commit()` boundary is adapted into a
non-persisting prepared update. The runtime subsequently stages that frozen
update into the same sync-enabled RocksDB `WriteBatch` as the finalized block,
certificate, message/query indexes, framework effects, state identity, current
root, and tip. No JMT side database or second commit protocol exists.

The JMT version is exactly the finalized app height. Even a block with no
logical state mutations publishes a root record at its height, so historical
lookup is always height-addressed rather than inferred from root equality.

## 2. Values and logical deletion

Ordinary application values enter CCL byte-for-byte. This preserves the JMT
roots and proofs frozen by the Phase 0 vectors; the runtime does not wrap every
value in a Yano envelope.

`AppStateWriter.delete()` writes the reserved canonical value
`yano:jmt:logical-tombstone:v1\0`. Supplying that exact value through `put()` is
rejected. Logical reads return absence, while a native proof reports
`TOMBSTONED` and carries an inclusion proof plus the committed tombstone bytes.
Physical absence reports `ABSENT` and a CCL non-inclusion proof. This backend
tombstone is distinct from an authenticated-map `REVOKED` entry, which remains
an ordinary present application value containing the contract's canonical
revocation state.

## 3. Retention, snapshots, and integrity

Pruning retains the requested horizon and every later root, removes roots
strictly below it, removes nodes made stale at or before it, and keeps the value
history sentinel needed to read the horizon. The latest version/root never
changes. Proof requests below the watermark return unavailable. Pruning is an
exclusive maintenance operation; proof reads and prepared commits use CCL's
namespace access coordinator.

RocksDB deletion does not immediately reclaim filesystem space. Operators must
schedule compaction separately and must not interpret post-prune file size as
the number of live records.

Snapshot ledger format 3 lists all 13 app-ledger column families, including the
five JMT families. Restore binds the profile, format fingerprint, genesis id,
height, block hash, and state root before service startup.

Startup and on-demand integrity checks bind the app tip to the JMT latest
version/root and run CCL's bounded full format/node/value/stale-index checker.
The current CCL inspection bound is 1,000,000 stored records. A larger retained
store fails closed as `truncated`; it is not implicitly declared healthy. A
streaming/configurable full-scan mode is required before qualifying larger
datasets.

## 4. Qualification evidence

Automated tests cover:

- byte-identical roots and native proofs versus
  `JellyfishMerkleTree`/`InMemoryJmtStore` from pinned CCL;
- side-effect-free candidate calculation and one shared durable batch;
- current and historical inclusion, both CCL non-inclusion forms, and logical
  tombstone classification/verification;
- no-op versions, restart, checkpoint restore, retention, and pruning;
- every prepare/stage/write/verification fault seam, with restart observing
  only the complete old or complete new state;
- malformed format metadata and missing-node integrity failures;
- independent-member deterministic roots across 20 restart-per-height commits;
- real three-member, threshold-two finality followed by late-member protocol
  catch-up and continued all-member finalization; and
- authenticated-map replay across three independent members plus explicit
  restart, checkpoint restore, state probes, and catch-up continuation.

The focused Phase 3 tests and the complete `core-api`, `appchain-stdlib`, and
`runtime` suites pass.

## 5. Measured development-machine envelope

Reproduction command:

```bash
./gradlew :runtime:benchmarkAdr025ClassicJmtRuntime
```

The 2026-08-03 run used an Apple M4 Max MacBook Pro (16 cores, 128 GB), macOS
26.0.1, Java 25.0.2, RocksDB WAL, `WriteOptions.sync(true)`, one shared batch,
100,000 new 16-byte keys, 128-byte values, 1,000 versions, 100 keys per version,
and 128 verified retained proofs.

| Observation | Result |
|---|---:|
| Ingest | 8,631 keys/s (11.59 s total) |
| Sync prepared commit p50 / p95 / p99 / max | 10.54 / 12.73 / 14.04 / 48.41 ms |
| Proof p50 / p95 / p99 / max | 0.170 / 0.243 / 0.647 / 2.303 ms |
| Mean native proof | 2,259.45 bytes |
| Restart/open | 592.26 ms |
| Bounded full integrity | 1,399.69 ms |
| RocksDB checkpoint | 25.64 ms |
| Ledger before prune | 53,761,986 bytes |
| Snapshot | 53,394,355 bytes |
| Prune to height 500 | 220,431 records in 146.76 ms |
| Ledger after prune, before compaction | 57,847,947 bytes |

These are observations from one development machine, not latency or throughput
guarantees. The qualified envelope in this phase is 100,000 retained keys and
1,000 versions under the stated workload. The 1 million, 5 million, and 10
million key cells are deliberately unclaimed because the current bounded CCL
full inspection path has not been qualified for them. The selected default
therefore remains MPF, and classic JMT remains explicitly selected at genesis.
