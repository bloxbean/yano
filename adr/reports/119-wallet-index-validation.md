# Issue 119 validation (in progress)

This report records measured checks, not completion of issue 119.

## JVM and wallet regression tests

- `./gradlew :runtime:test --tests '*wallet.*Test' --tests '*UtxoSubsystem*' :app:quarkusBuild --console=plain`: passed on 2026-09-06.
- Wallet-index suites: 25 tests, zero failures/errors. Includes producer-deferred Shelley genesis, effective collateral, same-block spends, fully spent first-seen, coverage gaps, undo and replacement branches.
- In the wallet checkout, `./gradlew :wallet-node-client:test :wallet-app:compileJava --console=plain`: passed. Live opt-in tests were skipped.
- Eleven new wallet tests cover first-seen zero/null/lag/incomplete handling, old-node route compatibility, persisted assets and outgoing-only history, incomplete streams, shallow/deep reorgs, mismatched completion, post-completion data and invalid transaction points.

Review found and fixed two gaps: producer initialization excluded deferred Shelley genesis from wallet coverage, and the wallet accepted malformed transaction coordinates through default numeric values. Both now have regression tests.

## Isolated live devnet probe

Artifact: JVM `app/build/yano.jar`, Java 25, heap limit 1 GiB. Fresh copied PV11 devnet configuration, epochLength 50, slotLength 0.2 seconds. HTTP 17119, node-to-node 14119. Both wallet indexes enabled; projection history disabled; block-body pruning depth zero. Test directory and retained evidence: `/private/tmp/yano-119-devnet-ttafwmir`.

Command from the test directory (log path changed for each restart):

```sh
java -Xmx1g -Dquarkus.profile=devnet -Dquarkus.http.port=17119 -Dyano.server.port=14119 -Dyano.scan.index.enabled=true -Dyano.address-first-seen.enabled=true -Dyano.history.projection.enabled=false -Dyano.chain.block-body-prune-depth=0 -jar /Users/satya/work/bloxbean/yano/app/build/yano.jar > run-1.log 2>&1
```

Observed:

- Projection service explicitly logged disabled.
- Produced blocks crossed slots 50 and 100 without production errors.
- Initial payment-credential scan returned HTTP 200, complete-from-origin coverage, the applicable genesis output and a matching done point at block 124 / slot 129.
- Genesis address first-seen returned numeric zero and complete coverage.
- After SIGTERM/restart, the saved block-124 cursor and genesis outpoint state resumed successfully through block 246 / slot 315.
- After SIGKILL/restart, the same saved cursor resumed successfully through block 883. First-seen remained zero. Coverage identity remained unchanged.
- Restart logs reported nonce restoration at the durable body tip. Numerical epoch nonce parity was not independently compared in this probe.
- Test node was stopped; logs, requests and responses are retained.

These are empty-block producer probes, not representative historical sync or wallet transaction-flow validation. They do not establish throughput, disk budgets, mainnet cardinality, or all recovery/rollback paths.

## Extraction, address validation and native follow-up

On 2026-09-06, the added extraction matrix tests passed for all ten supported
stake certificate variants, DRep certificate subjects, Shelley address headers,
withdrawals, pool owners/reward accounts, MIR and proposal return accounts.
The decoder emits reward-account fields as raw hex; review fixed the extractor
accordingly and added equivalence checks against Bech32 display forms.

Review also found that the display-address constructor rejects Byron addresses
and does not validate Shelley lengths. A dedicated validator now verifies Shelley
lengths, pointer framing, and the Byron envelope/payload/CRC32. Three address
validation tests and a Byron genesis first-seen/rollback test pass. The wallet
now ignores unrelated Byron outputs when filtering a matching transaction by
stake credential; its regression passes. These changes are not a claim of Byron
wallet recovery support.

An independent GCS golden test passes using published SipHash values, independently
calculated unsigned range mapping, and a literal Rice bitstream. The codec suite
now contains six tests. It does not merely round-trip production encode/decode.

Native build command:

```sh
env JAVA_HOME=/Users/satya/work/java/graalvm-25.3.4.1+1.1/Contents/Home ./gradlew :app:build -Dquarkus.native.enabled=true -Dquarkus.package.jar.enabled=false -x test --console=plain
```

The first attempt without `quarkus.package.jar.enabled=false` failed because this
build cannot output both native and jar packages. Corrected builds succeeded;
the final build includes reward-account extraction and strict address validation
fixes. Final build log: `/private/tmp/yano-119-native-build-final.log`.

Native probe evidence is retained in `/private/tmp/yano-119-native-3h52irw7`.
It uses the same heap limit, shortened devnet epoch, isolated ports, flags and
archival-disabled settings as the JVM probe, with `app/build/yano` as executable.
The initial native run crossed slots 50 and 100 without production errors,
returned genesis first-seen zero, and completed an origin scan through block 67.
After rebuilding and restarting on the same database, the saved cursor and
outpoint set resumed successfully through block 403 / slot 1374. A valid unused
Byron address returned HTTP 200 with firstSeenSlot null and complete coverage.
The native test process was stopped after the probes.

This validates native request/response reflection and basic persistence, not the
pending native performance, transaction-flow, and crash/rollback matrix.

## Million-filter JVM storage spike

The four-mode synthetic storage benchmark completed one million block batches per
mode, with one million physically stored filters in each filter-enabled database.
Results, exact workload limitations, raw JSON, compiled-class hashes and RocksDB
statistics are published in [the benchmark report](119-wallet-benchmark-jvm/README.md).
This satisfies the physical-scale spike, not the complete representative-era,
JVM/native, sync-contention and body-confirmation performance matrix.

Review after the spike added fail-closed handling for unreadable derived metadata,
strict first-seen slot record checks, and canonical-point verification around
first-seen reads. The latter prevents an orphaned UTxO/index point from serving
an authoritative result while runtime rollback is still propagating.

Post-spike command `./gradlew :runtime:test --tests '*wallet.*Test' :app:test
--tests '*AddressResourceTest' --tests '*WalletScanResourceTest' --console=plain`
passed: 38 runtime tests and 8 API tests, zero failures/errors. Log retained at
`/private/tmp/yano-119-post-bench-tests.log`.

## Outstanding gates

Historical JVM/native sync throughput, true cold-cache and concurrent-sync measurements beyond the completed synthetic million-filter spike; pruning and all runtime rollback entry points; full historical input-resolution/scan comparison beyond the non-contiguous serialized output/event fixtures. JVM/native wallet-to-node restart, crash, assets, outgoing-only history and snapshot reorg now pass as detailed below. The wallet profile now enables both indexes, retains block bodies and complete UTxO processing, and removes its archival-history settings. Production recommendations remain gated on the outstanding measurements. Draft PRs preserve these outstanding acceptance gates; issue 119 is not complete.

Final wallet verification: 90 tests discovered, 86 passed and 4 opt-in tests skipped; wallet app compilation passed. Final node targeted verification: 38 runtime and 8 API tests passed. Cursor review moved canonical validation before coverage-range rejection so a cursor above a restored tip receives the rollback response (409).

## Final live transaction test

`./gradlew :app:integrationTest --tests '*WalletIndexLiveIT' --console=plain`
passed on 2026-09-06 (log: `/private/tmp/yano-119-live-final.log`). The disposable
JVM devnet runs both indexes with history projection disabled. It verifies funded
genesis slot zero, unused null, incoming transactions with minted native assets,
resumed outgoing-only history, a fully spent address retaining first-seen, full
scan equivalence for both transactions, snapshot restore removing orphaned history
and first-seen, HTTP 409 for the saved orphaned cursor, and replacement-branch
transactions. The transaction builder uses a separately funded fee payer for the
outgoing sweep. Earlier test-harness attempts failed on disabled producer networking,
reused snapshot names, and transaction builder fee/change configuration; these were
fixed before this successful run. This test exercises the node HTTP API through
CCL, not the actual wallet application's live persistence/restart path.

## Wallet-to-node recovery and stronger fixture review

The companion wallet PR now contains `WalletIndexLiveTest`. It launches only its
own temporary devnet and talks through the actual `YanoNodeClient` and
`YanoNodePorts`, recreating wallet objects from persisted history between queries.
It checks genesis discovery, native assets, outgoing-only history and fully spent
first-seen, graceful node restart, forced process termination/restart, snapshot
reorg removing orphaned history/outpoints, and replacement transactions. Both JVM
and native runs passed on 2026-09-06. Each owned node was stopped after the test.

- JVM evidence: `/var/folders/9x/p4g24d210kq8hngwdmh5mwrm0000gn/T/yano-wallet-119-live-16518682916464853004`
- Native evidence: `/var/folders/9x/p4g24d210kq8hngwdmh5mwrm0000gn/T/yano-wallet-119-live-15444983075084036656`
- Logs: `/private/tmp/yano-119-wallet-live-jvm.log`, `/private/tmp/yano-119-wallet-live-native.log`
- Native rebuild: `/private/tmp/yano-119-native-wallet-build.log`; includes the corrupt-undo rollback safeguard below.

Review found that the earlier test's `Account(..., index)` varied payment keys but
retained the same stake credential. The old stake-query test therefore did not
prove outgoing-only discovery independently of the recipient's output. Both live
tests now use separate public test mnemonics/stake credentials. The node-only test
passed again with this correction (`/private/tmp/yano-119-distinct-stake-live.log`).

Malformed wallet undo now makes only that feature unavailable, with a fresh-sync
reason, while allowing the canonical UTxO rollback to commit. A regression corrupts
an undo record, checks restored spent inputs and removed orphan outputs, verifies
the independent filter rollback, and applies a replacement block without reviving
the invalid first-seen index.

`WalletSerializedEraTest` passes against ten upstream Pallas CBOR fixtures from
Shelley, Allegra, Mary, Alonzo, Babbage and Conway. Source provenance, byte digests,
license and a Python raw-CBOR reference walker are tracked under
`runtime/src/test/resources/wallet/eras`. Exact per-transaction output/event
credential counts and digests match, including decoded withdrawals, certificates,
pool owners/reward accounts, MIR recipients and proposal return accounts; every
expected credential matches its filter. These non-contiguous fixtures lack prior
UTxOs and do not establish full historical input resolution or scan throughput.

## Pruning, checkpoint gaps and canonical continuity

The wallet runtime suite now has 47 passing tests. Additional checks exercise the
real UTxO pruner and block-body pruner: first-seen persists for a fully spent address,
old undo is removed while permanent filters remain, an active scan fails without
completion after required bodies disappear, and new scans reject that body gap.
Exact rollback within the retained window preserves older first-seen entries;
rollback below the ordinary floor is rejected. The legacy origin rollback path
bypasses that floor, so its wallet coverage is explicitly checked to become
unavailable when undo is missing.

Real chain-state header-only rollback preserves applied wallet coverage. A body
rollback invalidates an active scan before derived rollback runs; the normal
`RollbackEvent` then restores first-seen and still invalidates the old scan.
Restoring a checkpoint created with both wallet features disabled invalidates
active scans and leaves first-seen unavailable even after new blocks. Filters can
begin a later interval, but origin scans cannot claim complete coverage. Each
independent flag rejects disabled UTxO storage, built-in selective storage, and
nonempty plugin storage-filter chains.

Review found and fixed two continuity holes. Coverage now requires consecutive
canonical block numbers (origin permits the chain's first numbered block at 0 or 1).
The runtime also validates both the previous applied point and the current point
against canonical chain storage before committing wallet index progress. A missed
same-height rollback therefore cannot be concealed by applying a later canonical
block over orphaned first-seen records. Regressions cover both a skipped UTxO apply
with complete canonical headers/bodies and advancement after a missed rollback.

`/private/tmp/yano-119-coverage-and-smoke.log` records the 47 runtime tests and a
corrected 1,000-block historical replay smoke run. The API/live node suites passed
again after these changes (`/private/tmp/yano-119-canonical-api-live.log`).

## Historical replay measurement harness

`benchmarkWalletHistory` opens a retained source RocksDB read-only and creates four
new destination databases. It replays the same canonical prefix through real
Byron/Shelley-family decoding, header/body persistence, UTxO apply, index staging
and ordinary undo pruning. It records CPU, wall time, allocation/GC, apply latency,
physical filter counts, per-index SST sizes, disk samples and confirmed warm scans.
All destination column families are flushed before measuring SST sizes.

The source `/Users/satya/Downloads/yano-cluster/chainstate-preprod` has canonical
numbers 1–5,039,478 and no block-pruner cursor. A read-only probe found retained
sample bodies from Byron through Conway. This sampling is not a proof that every
body exists; replay fails if any required header/body is missing. The 1,000-block
smoke run passed all four modes (`/private/tmp/yano-119-history-smoke-v2/report.json`).
It contains 45 Byron main blocks and 955 Shelley blocks, not a representative
million-block measurement.

Reproduce against an appropriate retained database, with a destination that does
not already exist:

```sh
./gradlew :runtime:benchmarkWalletHistory \
  -PwalletHistorySource=/absolute/path/to/source-chainstate \
  -PwalletHistoryOutput=/absolute/path/to/fresh-benchmark-directory \
  -PwalletHistoryGenesis=/absolute/path/to/network/genesis-directory \
  -PwalletHistoryBlocks=1000000
```

The first million-block attempt was intentionally terminated while still in its
baseline mode after the continuity review identified required runtime changes.
Its partial data under `/private/tmp/yano-119-history-million` must not be used as
final performance evidence. Even a completed replay excludes network, consensus
and other ledger stores; it does not replace native, true cold-cache,
concurrent-sync or independent full-history reference measurements.

Native wallet recovery was rebuilt and rerun on commit `314e776` after the canonical
continuity changes. The test passed with all three owned native process startups
reporting that commit. Evidence directory:
`/var/folders/9x/p4g24d210kq8hngwdmh5mwrm0000gn/T/yano-wallet-119-live-10222115598539104397`.
Logs: `/private/tmp/yano-119-native-canonical-build.log` and
`/private/tmp/yano-119-native-canonical-live.log`. An initial rerun reused a Gradle
cache result; that was discarded. The wallet live task now disables caching and
up-to-date reuse when an external artifact is supplied, and the successful rerun
explicitly executed the test against the rebuilt binary.

The corrected million-block historical run uses
`/private/tmp/yano-119-history-million-v2`, with progress in the adjacent `.log`.
It is pending measurement evidence, not an acceptance pass. The requirement audit
is tracked in `119-wallet-index-acceptance.md`.


## Final handoff — 2026-09-06

The owner accepted the completed devnet validation and deferred preprod sync to
manual testing. The read-only preprod million-block replay was intentionally
stopped during baseline (last logged progress: 600,000 blocks); it provides no
completed four-mode comparison. No further automated network/performance runs
are required for this handoff.

The final scan asset serialization uses canonical lowercase hex from raw asset-name
bytes, matching the UTxO API and preserving empty and non-UTF8 asset names.
A focused scanner regression covers these cases. The optional historical verifier
and independent Python raw-CBOR oracle passed a 1,000-block preprod smoke dataset,
including transaction/output digests and exact first-seen entries; this is not a
full historical acceptance or performance result.

Final focused scanner tests (7) and the real devnet `WalletIndexLiveIT` pass
after the asset-name byte fix. The wallet scan API tests also passed in the
preceding targeted run. Log: `/private/tmp/yano-119-final-devnet.log`.
