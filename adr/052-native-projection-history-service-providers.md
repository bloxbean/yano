# ADR-052: Retain Projection History Providers in Native Images

## Status

Proposed

## Date

2026-08-31

## Related decisions and evidence

- [Issue #105](https://github.com/bloxbean/yano/issues/105) records the native
  startup failure when DuckLake projection history is enabled.
- [PR #108](https://github.com/bloxbean/yano/pull/108) supplies the Scalus
  `1.1.1` base on which this change is stacked. Its native acceptance run
  reproduced issue #105 and proceeded only by explicitly disabling projection.
- [ADR-039](in-progress/039-canonical-projection-outbox.md) makes the canonical
  projection outbox and `ProjectionSink` the sole archive write path.
- [ADR-046](046-archive-aware-epoch-artifact-apis.md) defines the history reads
  backed by the completed projection archive.
- [ADR-047](047-remove-residual-legacy-history-machinery.md) removes the old
  replay-worker archive and leaves projection as the only history writer.
- ADR-053 is the proposed stacked follow-up that binds staged facts after the
  boundary block becomes canonical. It removes this ADR's three producer-mode
  exclusions without changing native provider reachability; its document ships
  on that separate lifecycle branch.
- [ADR-051 Phase 1/2 evidence](reports/adr-051-phase-1-2-2026-08-31.md)
  records the failed native devnet launch and the projection-disabled deviation.
- [`application-projection.yml`](../app/config/application-projection.yml)
  selects all four block projections and all five epoch-level projections when
  the `projection` profile is composed after a network profile.
- The embedded [preprod AdaPot oracle](../ledger-state/src/main/resources/expected_ada_pots_preprod.json)
  covers epochs 5 through 280, including the epoch-192 baseline and the epoch
  193/194 boundaries selected for the executed comparison.

## Decision summary

Yano will make the DuckLake archive module own native-image reachability for
both of its ServiceLoader providers and will execute the projection path in the
native CI gate.

1. `archive-store-ducklake` will retain the no-argument constructors of
   `DuckLakeProjectionSinkProvider` and `DuckLakeArchiveBackendProvider` in
   module-owned native-image metadata. The module will also own precise resource
   metadata for its two ServiceLoader descriptors. Additional resource, JNI or
   class-initialization metadata discovered by the executed native gates is in
   scope, but every addition must be justified by a reproduced failure.
2. Tests in the DuckLake module will require the provider classes, descriptors,
   and native metadata to agree exactly. Adding, removing, or renaming a provider
   without updating native reachability must fail a normal JVM build.
3. A dedicated native projection-history smoke will start the already-built
   Yano distribution with the `devnet,projection` profiles. It must cross epoch
   boundaries 0→1→2, prove all four selected block sections plus `epoch_stake`
   and `ada_pot`, and exercise projection-backed reads. The smoke names and
   excludes `reward`, `drep_distribution`, and
   `governance_proposal_status` because a pre-existing block-producing-mode
   ordering defect prevents their staged writers from resolving the boundary
   block on both JVM and native. Gate D proves those three through the
   unaffected fetched-block path.
4. The `native-core` CI job will run that smoke against the same binary already
   used for distribution and plugin-catalog verification. A native build that
   starts only after projection is disabled is a failure.
5. Native and JVM binaries will project the same preprod range from a
   byte-identical stopped epoch-192 baseline, crossing epoch 193 and optionally
   epoch 194. Their block-section rows, epoch-artifact rows, coverage and
   coordinates must match; AdaPot values must additionally match the embedded
   preprod oracle. Per-dataset row counts prove generation independently of
   cross-leg equality.
6. Only after the preprod differential passes, JVM and native binaries will run
   mainnet epochs 0 through 100 as an explicitly bounded Byron-genesis parity
   check. This final gate asserts genesis capture against the configured genesis
   file and requires JVM/native equivalence for the remaining bounded rows. It
   is not a broader Byron-correctness or performance validation.

This decision does not replace ServiceLoader, make the archive module an
application compile-time dependency, alter projection schemas, change history
configuration defaults, or change chainstate formats.

## Context

The application depends on `archive-api` and `archive-core` at implementation
time and includes `archive-store-ducklake` at runtime. The DuckLake module
publishes two service descriptors:

```text
ProjectionSinkProvider -> DuckLakeProjectionSinkProvider
ArchiveBackendProvider -> DuckLakeArchiveBackendProvider
```

The JVM distribution discovers both providers. The native image includes the
service descriptor resource pattern, but the closed-world analysis does not
retain the provider implementation required by the descriptor. With the
`devnet` profile, where projection is enabled by default, startup fails before
history can open:

```text
ServiceConfigurationError: ProjectionSinkProvider:
Provider DuckLakeProjectionSinkProvider not found
```

The projection writer is the first failing lookup. Registering only that class
would leave the read-side `ArchiveBackendProvider` exposed to the same defect
when history endpoints open the archive, so both implementations are one
correctness unit.

The existing native checks do not cover this path. Distribution verification
inspects the ZIP. `nativePluginCatalogSmoke` starts the binary with automatic
sync disabled and does not enable projection. A binary can therefore pass both
checks while the default native devnet profile is unusable and projection-backed
history cannot be served on any network.

The full `projection` profile is broader than the ServiceLoader lookup that
fails first. Its block sections are:

- `transaction:v1`;
- `utxo-history:v1`;
- `account-events:v1`; and
- `address-transaction:v1`.

Its epoch artifacts are:

- `reward:v1`;
- `epoch-stake:v1`;
- `ada-pot:v1`;
- `drep-distribution:v1`; and
- `governance-proposal-status:v1`.

Epoch artifacts execute at epoch boundaries through a different collector and
coverage path from ordinary block sections. A one-block startup smoke cannot
prove their native reachability or correctness. The DuckLake implementation
also carries DuckDB JDBC, SQLite JDBC and generated `ducklake` and
`sqlite_scanner` extension resources. Fixing the first missing provider may
therefore expose further closed-world resource, JNI or initialization failures.
Those failures are part of issue #105 when they prevent the configured
projection profile from working in native mode.

## Decision drivers

- The native and JVM distributions must support the same built-in DuckLake
  projection and history features.
- Provider reachability must be owned next to the provider rather than by an
  unrelated application-wide list that can drift.
- Both the write-side and read-side ServiceLoader contracts must remain valid.
- All block and epoch projections selected by `application-projection.yml` must
  work in native mode, not only the provider lookup and first block write.
- The regression gate must execute the native binary and prove persisted
  projection progress, not infer correctness from packaging or liveness.
- Native output must be checked against a JVM same-range oracle so empty or
  incorrect rows cannot pass as mere progress.
- Native CI should build the expensive image once and reuse that exact artifact
  for all runtime smokes.
- The change must remain narrowly stacked on PR #108 and avoid unrelated archive
  semantics or configuration changes.

## Invariants

1. The DuckLake JAR contains exactly one provider for
   `ProjectionSinkProvider` and exactly one provider for
   `ArchiveBackendProvider`.
2. The two descriptor entries resolve to concrete, public provider classes with
   usable no-argument constructors.
3. The provider portion of native-image metadata retains exactly those two
   provider constructors and exactly those two service descriptors. Additional
   driver, JNI and extension entries are allowed only in separately tested,
   evidence-named sets.
4. JVM ServiceLoader discovery continues to return both DuckLake providers.
5. A native launch reaches liveness without a `ServiceConfigurationError` or
   projection initialization error while preserving the devnet profile's
   projection selection, sink and enabled settings. Isolated ports and absolute
   chainstate, history and temporary paths are allowed test overrides.
6. Native projection coverage reports `enabled=true`, carries a bound identity,
   reports no error, and eventually reports `genesisCaptured=true`.
7. At least one canonical block is produced and the projection watermark or
   coordinate advances through a real block. Startup alone is not acceptance.
8. The history read facade opens the same DuckLake archive in native mode, so
   the read-side provider is exercised rather than only inspected.
9. The smoke uses an isolated temporary chainstate and history directory and
   leaves no retained process.
10. Projection is never disabled or changed to sink `none` in the acceptance
    path.
11. Native devnet crosses boundaries 0→1→2 and records complete `epoch_stake`
    generations for epochs 0 and 1 and a complete `ada_pot` generation for
    epoch 2 without gaps. It continues to select all five configured epoch
    artifacts, but explicitly excludes `reward`, `drep_distribution`, and
    `governance_proposal_status` from producer-mode completion until their
    pre-existing boundary-ordering defect is fixed. Gate D must execute and
    compare those three on the fetched-block path; a valid zero-row generation
    there is accepted only when its receipt proves the writer ran and the
    equivalent JVM fixture has the same row count.
12. `transaction`, `utxo_history`, `account_event`, and
    `address_transaction` block sections are populated and queryable in native
    mode.
13. For the recorded preprod epoch-192 baseline window, native and JVM
    projection rows, coverage intervals and committed coordinates are identical
    after normalization of explicitly non-semantic values such as run-local
    paths. Each of the nine datasets also reports a per-epoch row count. Every
    count expected to be non-zero must be non-zero; a zero is accepted only when
    predicted before comparison, justified from chain activity and matched by
    the JVM leg.
14. Native preprod AdaPot rows for every selected boundary equal the embedded
    treasury, reserves, fees, deposits and UTXO values exactly. This is an
    absolute oracle for one dataset; the other eight rely on generation checks
    plus exact JVM/native semantic equality.
15. Mainnet is not started before the preprod differential passes and is never
    run beyond epoch 100 for this issue. The bounded mainnet run is the final
    gate and exists only to exercise Byron genesis with JVM/native parity.

## Detailed decision

### 1. Put native reachability in the provider module

Add module-owned Native Image metadata under a non-colliding path such as:

```text
archive-store-ducklake/src/main/resources/META-INF/native-image/
  com.bloxbean.cardano/yano-archive-store-ducklake/
    reflect-config.json
    resource-config.json
```

The reflection configuration retains, at minimum, the declared constructors of:

- `com.bloxbean.cardano.yano.archive.ducklake.DuckLakeProjectionSinkProvider`;
- `com.bloxbean.cardano.yano.archive.ducklake.DuckLakeArchiveBackendProvider`.

The resource configuration names, at minimum, the corresponding
`META-INF/services/...ProjectionSinkProvider` and
`META-INF/services/...ArchiveBackendProvider` descriptors. It does not use a
broad `META-INF/services/.*` pattern.

Provider-owned metadata is preferred to an app-level list because the provider
module is independently publishable and runtime-only. Its JAR must carry the
metadata needed by every native consumer. It is also preferred to introducing a
Quarkus deployment module solely to emit two `ServiceProviderBuildItem`s; that
would add an extension lifecycle and build-time dependency direction without a
runtime behavior that requires one.

If Gate A's build-input probe proves that Oracle GraalVM 25.3 ignores
dependency-owned metadata in the Quarkus build, stop and record the generated
native-image inputs before changing direction. The allowed fallback is
application-generated constructor metadata derived from the two service
descriptors, not a second handwritten provider list. That fallback must preserve
the exact-set drift test below.

The two provider entries are the first slice, not an assumption that all native
work is known in advance. After each slice, rerun the native path and capture the
next concrete failure. Resource entries for bundled DuckDB/SQLite native
libraries or DuckDB extensions, JNI entries, or `--initialize-at-run-time`
settings are added only when that evidence requires them. Each addition must be
owned by the narrowest module that can carry it and must gain an exact metadata
contract test. Speculative catch-all reflection, JNI or resource patterns are
not permitted.

The metadata contract intentionally compares exact entry sets. When a later
evidence-led slice adds DuckDB or SQLite metadata, that test must first turn red
and its expected set must be updated deliberately in the same change. It must
not be weakened to a subset or `contains` assertion merely to accept new
entries; the failure is the drift signal, following ADR-051's
`swapsExactlyOneScalusDefaultValidator` precedent.

#### DuckDB and SQLite native enablement

The providers are only the first reachable objects. Opening them reaches three
additional closed-world surfaces in the same runtime-only module:

- `PackagedDuckDbExtensionLoader.extract(...)` reads versioned
  `duckdb-extensions/<version>/<platform>/ducklake.sha256`,
  `sqlite_scanner.sha256`, and their `.duckdb_extension` payloads through
  `getResourceAsStream`;
- `DuckDbManager` loads `org.duckdb.DuckDBDriver` with `Class.forName`; and
- `DuckLakeTransactionLocator` loads `org.sqlite.JDBC` with `Class.forName`.

The drivers also carry platform JNI libraries and classes that own native
handles. ADR-052 therefore includes the narrow resource, reflection, JNI and
runtime-initialization metadata needed to open both drivers and load both
packaged DuckDB extensions. Prefer metadata already published by the JDBC JARs;
do not duplicate it when the exact Oracle GraalVM build inputs prove it is
present and effective.

DuckDB connection acquisition is a separate boundary from archive-provider
discovery. `DuckDbManager` owns an explicit `DuckDBDriver` instance and calls
that driver's `connect` method because Quarkus initializes `DriverManager` at
image build time; it does not replace or bypass the `ProjectionSinkProvider`
and `ArchiveBackendProvider` ServiceLoader contracts. Those provider contracts
remain descriptor-driven and are still guarded by the exact-set metadata test.

The driver runtime-initialization entry is retained for the direct mechanism,
not to restore `DriverManager` registration. It ensures explicit construction
executes `DuckDBDriver.<clinit>` in the runtime process and establishes its
mutable scheduler, locks and registries there instead of inheriting their
build-time state. The later JNI load remains owned by `DuckDBNative` and must be
classified independently if it fails.

The SQLite transaction-locator connection follows the same explicit-driver
boundary: `DuckLakeTransactionLocator` owns an `org.sqlite.JDBC` instance and
calls `connect` directly because Quarkus' build-time `DriverManager` state is
not a runtime registry. Unlike DuckDB, sqlite-jdbc already ships and activates
`org.sqlite.nativeimage.SqliteJdbcFeature`; the Oracle native build lists that
feature and it owns SQLite's platform libraries, resources and JNI metadata.
The SQLite path therefore adds no handwritten reachability metadata or
distribution sidecar. Any later SQLite native failure must first be reported
against that upstream feature rather than duplicating its registrations.

Native history remains a supported distribution capability because Yano also
targets wallet use cases, where projection-history parity between JVM and native
packaging is product behavior rather than an optional server-only extra. The
DuckDB JNI library is therefore a native-distribution sidecar, not an embedded
image resource: each platform ZIP carries exactly its matching
`libduckdb_java.so_<platform>` file and SHA-256 record beside `yano`. This keeps
the executable size flat and lets DuckDB's existing current-executable-directory
fallback load the library without downloading or extracting it on every
restart. All native projection and restart gates run from that staged
distribution, never from a bare `app/build/yano` binary.

This layout depends specifically on DuckDB's third native-library fallback:
after the classpath lookup is empty and `System.loadLibrary("duckdb_java")`
fails, `loadFromCurrentJarDir` resolves the jar-style filename beside the
current executable. The staged native log proves that sequence. A future
DuckDB change to its current-jar-directory behavior can therefore surface as a
missing-sidecar packaging failure and must be checked when upgrading the JDBC
driver.

Sidecar selection follows the supported native-build contract. A local build
targets the Gradle host operating system and CPU; a Quarkus container build
targets Linux on the host CPU. Unknown operating systems and CPUs fail closed,
as does an explicit Docker `--platform` whose CPU differs from the host. The
distribution task extracts only that target's entry from the pinned DuckDB JDBC
JAR. This preserves the normal macOS-hosted Linux container build without a
low-level executable parser. `prepareYanoDockerNativeContext` strips only the
ZIP's top directory, leaving the sidecar beside `yano` under `yano/`; the native
Dockerfile's `COPY yano/ /app/` consequently installs both at `/app` without a
second packaging path.

Extension selection is target-aware independently of JNI sidecar naming. The
generated resource tree contains exactly `ducklake.duckdb_extension`,
`ducklake.sha256`, `sqlite_scanner.duckdb_extension`, and
`sqlite_scanner.sha256` under one
`duckdb-extensions/<duckdb-version>/<target-platform>/` directory, plus native
resource metadata naming exactly those four paths. A native container build
selects Linux extensions even on a macOS host. The packaging verifier derives
the extension target and JNI sidecar from the same build-target helper and
rejects a module JAR containing another or additional platform. This
distinction is required on macOS: DuckDB's JNI JAR uses
`osx_universal`, while the extension repository uses `osx_arm64` or
`osx_amd64`. No `duckdb-extensions/**` catch-all is retained in the image.

The first native measurement embeds these four extension resources so the
executed projection gate can proceed. Because the two raw extension payloads
are approximately 52 MB together, the resulting executable and ZIP deltas are
reported to the maintainer before this packaging choice is ratified. Moving
the checksum-verified extensions beside the executable remains an open
packaging decision; it must not be inferred from the sidecar decision.

The DuckDB JNI access list is evidence-derived, not a blanket registration. A
pinned Oracle GraalVM tracing-agent run opened projection and crossed the
devnet 0-to-1 epoch boundary, exercising reward, ada-pot, stake snapshot,
governance/DRep and artifact-finalization paths. Its hard-interrupt shutdown did
not flush metadata, so a second run used periodic writes and captured the same
eager JNI-on-load lookups before a retained-history identity mismatch stopped
projection initialization. The unified metadata contained 42 JNI-accessible
classes; curation removed the unrelated
`sun.management.VMManagementImpl` entry and the unrelated
`Boolean.getBoolean(String)` lookup, leaving 41 classes, 49 methods and 11
fields. That set is cross-checked against DuckDB Java 1.5.5.0's eager
`create_refs` table. A clean projection open proves the eager JNI-on-load
handles; later lazy lookup remains covered by the epoch-boundary and preprod
parity gates rather than claimed closed from startup alone.

If the sidecar is absent, the archive layer reports an actionable startup error
that names the expected path and tells the operator to use the complete native
distribution. `verifyCoreNativeDistribution` independently checks the exact
platform-derived filename, size, digest and checksum record, and rejects extra
DuckDB sidecars.

The current repository has no macOS/Windows platform code-signing or
notarization path for native-distribution payloads; `-PskipSigning` controls
publication signing, not executable/library signing. A future signed native
release pipeline must sign and verify the JNI sidecar under the target
platform's policy together with the executable.

Add these entries in evidence-led slices. Gate A first preserves the provider
failure. After the minimum provider metadata lands, each subsequent native run
must preserve the exact missing-resource, checksum, class-loading, JNI or
initialization failure before adding the corresponding narrow entry. Generated
extension paths must be derived from the build's DuckDB version and platform,
and their contract test must cover both extension payloads and checksums. Broad
classpath or native-library wildcards remain prohibited unless the owning
third-party artifact itself publishes and requires that pattern.

### 2. Make descriptor and metadata drift fail on the JVM

Add a focused metadata contract test to `archive-store-ducklake`. It will parse
the two service files and the two native configuration files and assert:

- one unique implementation name per service interface;
- the implementation names are exactly the two shipped provider classes;
- each class is assignable to its declared service interface;
- each class exposes the constructor required by ServiceLoader;
- the provider reflection entries contain exactly those implementation names
  and only constructor retention; any additional driver entries form a separate,
  evidence-named expected set;
- the ServiceLoader resource entries contain exactly the two escaped descriptor
  paths; generated DuckDB-extension and JNI resources form separate,
  evidence-named expected sets;
- there are no duplicate native metadata entries.

The test verifies source metadata, not native behavior. It provides a fast red
signal when providers change, while the executed native smoke remains the
closed-world acceptance gate.

### 3. Execute native projection history in the producer-mode devnet binary

Add a separate Gradle task, `nativeProjectionHistorySmoke`, that accepts the
same `-PyanoNativeBinary` / `YANO_NATIVE_BINARY` override convention as the
existing native plugin-catalog smoke. It stages the already-built native ZIP so
the executable, DuckDB sidecar, config and extension resources are tested in
their release layout. It must not invoke another native build.

The task will:

1. allocate isolated loopback ports and a temporary root;
2. start the binary with `devnet,projection`, automatic sync and lazy block
   production enabled so the control API, rather than wall-clock scheduling,
   owns every produced block;
3. point chainstate, history, DuckDB temporary files, and extension extraction
   at the temporary root;
4. leave `yano.history.projection.enabled=true` and sink `ducklake` effective;
5. await liveness, then use the devnet control API to cross epoch boundaries
   0→1→2 while producing canonical blocks;
6. derive finality from the staged devnet genesis as `2 * securityParam`, then
   poll `/history/coverage` until the committed coordinate converges exactly to
   `tip - finalityBlocks`. If a valid near-tip batch remains below its normal
   minimum, add deterministic single-slot advances until it reaches a real
   commit boundary rather than changing production batch policy or encoding an
   observed lag as a constant;
7. stop block production and require the same finalized-frontier convergence,
   captured genesis row-count/digest evidence, and independently queryable
   watermarks for all four block sections;
8. require complete `epoch_stake` coverage for epochs 0 and 1 and complete
   `ada_pot` coverage for epoch 2. Keep all five artifacts selected, but print
   the producer-mode exclusions `reward`, `drep_distribution`, and
   `governance_proposal_status` with the pre-existing-defect reason. Their
   executed row-count and zero-row parity moves to Gate D's fetched-block run;
9. call projection-backed history reads and the watermark endpoint to exercise
   `DuckLakeArchiveBackendProvider` as well as the projection sink provider;
10. fail on early process exit, timeout, provider error, unexpected projection
    error, missing progress, or non-successful history response; and
11. terminate the process gracefully, escalating to forced termination only as
   a reported test failure.

On failure, the task preserves its temporary root and prints the log tail,
effective paths, ports, binary path, and SHA-256. On success, it removes the
temporary root and reports the observed block and projection coordinate.

The exact block number is intentionally not fixed. The gate derives its
finalized target from the observed tip and staged genesis, and proves that real
blocks and two epoch boundaries crossed the canonical apply, projection outbox,
block and supported producer-mode epoch collectors, DuckLake sink, coverage
store, and history read path within the bounded timeout.

The producer-mode exclusion is an independently reproduced product defect, not
a native exception. At the unmodified PR #108 head
`de4bbdfd5dc0523682a0cd56a1de526d29beeaaf`, the packaged JVM fixture fails
while opening the epoch-1 reward writer with
`ArchiveStoreException: canonical epoch boundary is unavailable`. All three
block-producing call sites publish `prepareEpochTransitionBeforeBlock` before
the boundary block is built and stored; the staged writer synchronously asks
`ChainQuery` for that not-yet-existent block. The fetched-block path stores the
block before publishing the transition events and is unaffected. The separate
issue remains subject to maintainer approval and is not fixed in issue #105.

### 4. Compare native and JVM projection output on preprod

The executed preprod acceptance window starts from a stopped common baseline in
epoch 192 and crosses the boundary into epoch 193. It may continue through the
boundary into epoch 194 when the second boundary is inexpensive. The evidence
root retains `preprod-186-188` in its name because that was the originally
planned window; renaming a retained multi-gigabyte tree would add risk without
changing the recorded coordinates. Satya explicitly accepted one or two epochs
on 2026-09-01. The selected window remains Conway, exercises real governance
content and is fully covered by the embedded AdaPot oracle.

Create the comparison input once; do not perform two independent genesis syncs:

1. Record the stopped seed's chain tip, projection coordinate, history size and
   margin to the shipped 8 GiB soft budget. If projection has not converged to
   the exact `tip - 2 * securityParam` frontier, resume only under a guarded
   stop condition that cannot overshoot the required coordinate unnoticed.
2. Before copying, require one stable projection identity, all nine configured
   datasets reachable, zero gaps, a healthy archive and an exact committed
   frontier. This genesis-built seed establishes `genesisCaptured=true` for
   both later legs.
3. With no Yano process running, snapshot chainstate and history together as one
   seed and record their manifests/digests. Make two byte-identical copies named
   `jvm` and `native`; verify the copied manifests before either is opened. Each
   leg receives a freshly extracted matching distribution.
4. Run the JVM copy across the epoch-193 boundary and to an exact finalized
   projection frontier; continue through epoch 194 only when the first boundary
   is cheap. Stop it gracefully. Only after it exits, run the Oracle GraalVM
   25.3 native copy across the identical coordinate range. Never open either
   copy concurrently or reuse one leg's mutated state for the other.

Run only one Yano process at a time and record the effective profile, projection
selection, sink, paths, disk budgets, binary/JAR identity and toolchain before
accepting a measurement. Because both legs inherit the same genesis-built
archive and rollback history, exact row parity is meaningful and
`genesisCaptured=true` remains reachable without a second long sync.

Hold housekeeping and compaction configuration identical across both legs and
record whether either task fired, including its start/end coordinate and
result. Maintenance scheduling is wall-clock and disk-triggered, so physical
file layout, snapshot history and expired-file state are not parity fields.
Logical rows remain the hard gate. Coverage intervals, receipts and identities
are compared semantically; normalize a maintenance-only difference only when
the evidence names the task that caused it and proves no logical row, gap,
identity or coordinate changed.

Export deterministic evidence for each run:

- all rows in `transaction`, `utxo_history`, `account_event`, and
  `address_transaction` whose canonical coordinates fall within the recorded
  baseline-to-final comparison window;
- every newly complete `reward`, `epoch_stake`, `ada_pot`,
  `drep_distribution`, and `governance_proposal_status` generation produced by
  the selected epoch-193 and optional epoch-194 boundaries;
- coverage intervals, gaps, projection identities, receipts and committed
  coordinates; and
- history-directory size at each boundary and at the end of the window; and
- housekeeping and compaction events, including whether either ran in only one
  leg.

Sort by each dataset's canonical key, normalize only documented run-local
fields, and compare row counts, canonical keys and serialized semantic values.
For each of the nine datasets, the comparison report records the actual row
count per semantic epoch. Every count expected to be non-zero must be non-zero.
A legitimate zero is accepted only when its expected epoch and chain-derived
reason were written down before the comparison and the JVM leg has the same
zero-row complete generation; an unpredicted zero fails the gate. Native must
match JVM exactly. For AdaPot, also compare treasury, reserves, fees, deposits
and UTXO to `expected_ada_pots_preprod.json` for every selected epoch. AdaPot is
the one absolute dataset oracle; the other eight are protected by explicit
generation assertions plus cross-leg equality. Evidence is retained below
`/Users/satya/Downloads/yano-issue105-native-projection/preprod-186-188/`, with
separate `jvm/`, `native/`, and `comparison/` leaves.

The shipped projection disk budgets remain unchanged: soft 8 GiB, hard 32 GiB,
low-water 4 GiB. Record history size at every boundary. If actual growth departs
materially from the approved estimate or approaches a threshold, stop and
report rather than silently raising a budget.

The stopped epoch-192 seed measured 5,984,876 KiB (5.71 GiB) in its history
directory, 71.4% of the 8 GiB soft budget and below the 7.5 GB decimal early
warning. Its 11,751,496 KiB chainstate is host-capacity information, not part of
the archive ingest-gate budget. The two offline comparison copies multiply host
storage, but do not change the per-history-directory budget. Record actual free
space and per-leg usage before launch. Treat 7.5 GB observed history usage as
an early warning and obtain a maintainer decision before the soft threshold is
reached.

### 5. Exercise mainnet Byron genesis last, with known limitations

Only after the preprod Gate D differential has completed successfully, run one
additional JVM/native parity test from mainnet genesis through the end of epoch
100. Do not start this run earlier, do not sync beyond epoch 100, and do not
extend it into a full mainnet validation. Its evidence root sits beside the
preprod evidence, for example
`/Users/satya/Downloads/yano-issue105-native-projection/mainnet-0-100/`, with
separate seed, JVM, native, and comparison leaves.

Epochs 0 through 100 exercise Byron genesis bootstrap and the mainnet Byron
genesis UTXO/AVVM distributions, which the Shelley-era preprod window does not.
This is also the only gate whose genesis input exercises the full mainnet AVVM
distribution rather than preprod's single genesis row. As soon as genesis
capture completes, before waiting for epoch 100, the gate independently parses
`app/config/network/mainnet/byron-genesis.json`, derives the expected AVVM row
count and lovelace total, and compares both with the projection. For the current
file those derived values are 14,505 rows and 31,112,484,745,000,000 lovelace.
The assertion must not hardcode them. It records the genesis digest and later
requires the JVM and native legs to produce that same digest.

The remainder of the bounded run requires exact JVM/native semantic
equivalence for projected rows, coverage, receipts, identities and coordinates.
Report the observed blocks per second, including any material change by era.
Issue #88 may help interpret a measured slow rate, but the gate does not
pre-declare a bottleneck or a completion time and does not tune production code
as part of issue #105.

### 6. Run the devnet smoke in native CI and release workflows

Add `:app:nativeProjectionHistorySmoke` to the `native-core` invocation after
the one native build and distribution verification. Keep
`nativePluginCatalogSmoke`; the tasks cover independent native reachability
surfaces.

Any workflow that advertises a native release artifact must run the same
projection smoke or invoke a shared aggregate verification task that includes
it on the platforms ratified for this ADR. Linux `native-core` coverage is
required. Windows is an explicit open decision because `dist-dev.yml` already
builds a Windows native binary and executes `nativePluginCatalogSmoke`; adding
projection coverage there also commits this PR to Windows DuckDB-extension
packaging and Windows process-start/termination behavior. The workflow must not
silently include or exempt Windows before that choice is made.

The executed native devnet smoke requested by issue #105 item 2 is proposed as
in scope for this PR, not deferred. Ratifying this ADR accepts that scope;
before ratification it remains an explicit maintainer decision. The longer
preprod JVM/native differential is an acceptance gate and retained evidence,
but need not run on every pull request.

### 7. Preserve the stacked-PR boundary

The implementation branch starts at PR #108 head and its pull request targets
`issue-106-upgrade-scalus-1.1.1`, not `main`. Its diff must contain only ADR-052,
DuckLake native-enablement metadata and tests, the native projection smoke, and
the workflow wiring required for issue #105. After PR #108 merges, the stacked
PR may be retargeted to `main` without rebasing away its review history.

## Acceptance gates

### Gate A — baseline reproduction and metadata inspection

- Preserve the issue #105 failure log from the PR #108 native binary.
- Record the binary SHA-256 and Oracle GraalVM/JDK versions.
- Inspect the native build inputs to show the service descriptor is retained
  while the provider constructor is absent before the fix.
- After each native metadata slice, retain the next failure before adding more
  GraalVM configuration; no entry is justified only by expectation.

### Gate B — module and JVM regression tests

- `:archive-modules:archive-store-ducklake:test` passes, including the exact-set
  metadata contract.
- Application unit and integration tests pass.
- A packaged JVM devnet still opens projection and passes the existing
  `DevnetProjectionArchiveIT` behavior.

Executed JVM oracle evidence is retained at
`/Users/satya/Downloads/yano-issue105-native-projection/slice5-sidecar-distribution-de4bbdfd/jni-agent-runtime/jvm-devnet-agent.log`.
The staged JVM ZIP opened the DuckLake sink and all nine datasets
(`account_event`, `ada_pot`, `address_transaction`, `drep_distribution`,
`epoch_stake`, `governance_proposal_status`, `reward`, `transaction`, and
`utxo_history`), produced through slot 1,315, and completed the devnet epoch
0-to-1 boundary at slot 1,200 with every boundary phase successful.

The run also proves that a devnet execution can mutate its extracted
distribution. Against a fresh extraction, the exact differences were a new
2.0 MiB `chainstate/` tree (29 files), new `yano.log`/`yano.log.1` files, and a
single content change in `config/network/devnet/shelley-genesis.json`:
`systemStart` was updated from `2026-06-03T11:05:36Z` to the producer start time
`2026-08-31T14:34:28Z`. Therefore JVM/native parity legs never share an
extracted distribution. Each leg receives a fresh extraction plus its own copy
of the common seed snapshot, and the post-run comparison includes both the
explicit data directories and any files created below that leg's distribution
root. This prevents launcher fallback paths or devnet genesis updates from
contaminating the other leg.

The preprod parity legs do not rewrite `shelley-genesis.json`. Source inspection
shows `resolveAndPersistGenesisTimestamp(...)` is called only by devnet producer
startup, or by slot-leader startup while `devMode=true`; non-dev slot-leader
startup reads `systemStart`, and the preprod sync-only gate does not start a
producer. Fresh extraction is still required independently for both parity
legs so the test does not rely on that implementation detail and captures any
unexpected distribution-root mutation.

### Gate C — release-parity native devnet projection smoke

- Build once with the CI Oracle GraalVM 25.3 toolchain and G1 configuration.
- `verifyCoreNativeDistribution`, `nativePluginCatalogSmoke`, and
  `nativeProjectionHistorySmoke` all run against that exact binary.
- The projection smoke crosses devnet 0→1→2 and proves captured genesis,
  finalized-frontier convergence, populated/queryable coverage for all four
  block sections, and complete producer-mode generations for `epoch_stake`
  epochs 0..1 and `ada_pot` epoch 2, with projection enabled throughout. It
  names the three producer-blocked staged artifacts as exclusions rather than
  silently accepting their absence.
- The log contains no ServiceLoader provider failure, projection initialization
  failure, packaged-extension missing-resource error, extension checksum
  mismatch, or archive degradation other than the explicitly scoped
  producer-mode staged-artifact exclusion. Both DuckDB extensions must load
  from packaged resources; runtime download is not a supported fallback.

The first executed Gate C smoke passed on 2026-09-01 against native binary
SHA-256
`09cc2786f73acee2008b0e10eafb917ca3ab78d84a9c73cc211395c23616192d`.
The lazy devnet stopped at tip 2,600; staged genesis supplied `k=100`, so the
derived finality window was 200 and the exact queryable frontier was block
2,400. Projection reached 2,400 with zero single-slot convergence nudges and
retained that coordinate after production stopped. `epoch_stake` completed
epochs 0..1, `ada_pot` completed epoch 2, all four individual block-dataset
watermarks reported range 0..2,400, and a projection-backed
address-transaction lookup returned HTTP 200. The task printed the three
producer-mode exclusions by name. It ran the existing native distribution and
did not invoke another native build or add workflow wiring.

### Gate D — preprod JVM/native differential

- Use preprod only and the recorded epoch-192 common baseline. Cross the
  epoch-193 boundary and optionally epoch 194 when the second boundary is
  inexpensive.
- Require the stopped seed to be healthy and exactly converged to its derived
  finalized frontier. Snapshot chainstate and history together, verify
  byte-identical `jvm` and `native` copies, then run the two legs serially over
  the same coordinate window with the full `projection` profile and unchanged
  disk budgets.
- Require exact semantic equality for every row of all four block sections and
  all five epoch artifacts, plus coverage and coordinate equality.
- Record the actual row count per semantic epoch for each of the nine datasets.
  Require non-zero counts wherever content is expected. Accept zero only when
  the epoch and chain-derived reason were predicted before comparison and the
  JVM leg has the same complete zero-row generation; any unpredicted zero fails.
- Require native AdaPot fields to match the embedded oracle for every selected
  epoch. This is an absolute check for one dataset; the other eight rely on
  explicit generation assertions plus exact cross-leg equality.
- Retain exports, comparison reports, logs, identities and per-boundary disk
  usage under the named issue evidence root.

### Gate E — restart durability

- Use a dedicated isolated root, separate from the CI smoke's disposable root.
- Start the same native binary, cross one boundary with all projections enabled,
  record the archive identity and coordinate, and stop gracefully without
  deleting the root.
- Restart against that retained chainstate and history directory, then cross the
  next boundary.
- Require clean provider discovery, archive identity reuse, no destructive
  reinitialization, and projection progress beyond the pre-restart coordinate.
- Remove the dedicated root only after both legs pass; preserve it with both log
  tails and effective configuration on any failure.

Gate E may run in a dedicated acceptance task if keeping the per-PR CI smoke
short is necessary, but it must complete before ADR-052 is accepted.

### Gate F — bounded mainnet Byron-genesis parity, last

- Start only after Gate D has passed; preserve the one-process-at-a-time rule.
- Run mainnet from genesis through epoch 100 and stop there. A full mainnet sync
  or any epoch beyond 100 is outside this authorization.
- Fail fast after genesis capture: derive the expected row count and lovelace
  total from `app/config/network/mainnet/byron-genesis.json`, then require the
  projection to contain exactly that set. With the current file the derived
  values are 14,505 rows and 31,112,484,745,000,000 lovelace; neither value is
  hardcoded in the assertion. Record the digest and require it to match between
  the JVM and native legs.
- Use equivalent isolated JVM/native inputs and require exact semantic parity
  for the remaining projected rows, coverage, receipts, identities and
  coordinates within the bounded window.
- Report measured blocks per second rather than assuming issue #88's historical
  rate. Record any material rate change by era; do not tune or work around it in
  this issue.
- Retain manifests, logs, exports and comparison output under the dedicated
  `mainnet-0-100` evidence root.

## Alternatives considered

### Disable projection in native profiles

Rejected. It makes the native product feature-incomplete, hides the default
devnet failure, and contradicts the projection-only history architecture.

### Register only `DuckLakeProjectionSinkProvider`

Rejected. The writer would start, but the read facade would retain the same
uncovered ServiceLoader risk for `DuckLakeArchiveBackendProvider`.

### Move providers into the application module

Rejected. It reverses the archive API dependency boundary and couples the app
at compile time to DuckLake implementation types.

### Replace ServiceLoader with direct construction or CDI injection

Rejected for this issue. Either choice changes the pluggable archive boundary
defined by the existing ADRs. Narrow native reachability metadata for the two
built-in providers, their drivers and packaged extensions preserves that
boundary.

### Use only application-wide reflection configuration

Rejected as the primary design. It can make the current binary pass while
leaving the independently published provider module incomplete and prone to
drift. It remains a constrained fallback only if build-input evidence proves
dependency-owned metadata is not consumed.

### Treat a successful native build or liveness check as acceptance

Rejected. Issue #105 already passes both kinds of indirect signal. Only a real
projection write and history read prove the closed-world runtime surface.

### Validate only devnet

Rejected. Devnet is the fast closed-world path and can exercise epoch writers,
but its tiny synthetic data is not a correctness oracle. The recorded preprod
JVM/native differential is required. A bounded mainnet 0-through-100 parity
gate then runs last to exercise the much larger Byron genesis distribution,
assert that initial capture against the genesis file, and measure the bounded
JVM/native path including its observed throughput.

## Consequences

### Positive

- Native devnet works with its shipped projection defaults.
- Native users can serve DuckLake-backed history rather than running a
  projection-disabled binary.
- Every block and epoch projection in the shipped `projection` profile gains a
  native execution and correctness gate across the devnet smoke and the
  fetched-block preprod differential; the producer-mode exclusion remains
  explicit until its separate defect is resolved.
- Write-side and read-side provider reachability have one owner and one drift
  test.
- CI catches both missing native metadata and a provider that is retained but
  fails while opening DuckLake.
- The expensive native image is reused across independent smokes.

### Costs and risks

- Native CI runs a second process-level smoke and waits for real block and
  projection progress across two epoch boundaries.
- The preprod JVM/native differential consumes time and disk and must be run
  serially with explicit paths and retained evidence.
- The smoke exercises DuckDB native code and bundled extensions, so failures may
  expose platform packaging defects beyond ServiceLoader reachability. Those
  are product failures, but their evidence must be classified precisely.
- Reflection metadata must be updated when provider implementations change.
  The exact-set test intentionally turns that maintenance obligation into a
  build failure.

## Rollback

Revert the provider metadata, metadata test, smoke task and workflow wiring as
one change. No chainstate or archive migration is required. Disabling projection
is permitted only as an explicitly documented diagnostic workaround; it is not
an accepted release state for this ADR.

## Open maintainer decisions

1. After reviewing the measured executable and ZIP deltas, choose whether the
   two checksum-verified DuckDB extension payloads remain embedded native-image
   resources or move beside the executable as distribution files.
2. Ratify that `nativeProjectionHistorySmoke` and Linux `native-core` workflow
   wiring ship in this PR rather than being deferred.
3. Choose whether Windows `dist-dev` also runs the projection smoke in this PR.
   Including it adds Windows DuckDB extension packaging and Windows process
   lifecycle to the acceptance surface. Exempting it must be explicit and leaves
   Windows native projection as a recorded coverage gap; the existing Windows
   plugin-catalog smoke alone is not projection evidence.
4. Review the measured preprod history usage and decide whether the shipped
   8 GiB soft budget remains appropriate. No agent may alter it silently or
   during a run.
5. Ratify ADR-052. Commit, push and creation of the reviewed, tested stacked PR
   are already authorized; ADR ratification does not authorize merge.

## Acceptance

ADR-052 remains `Proposed` until the metadata contract, JVM projection behavior,
release-parity native devnet projection smoke, preprod JVM/native differential,
and restart-durability gate pass and the maintainer ratifies the decision. The
ratification must also decide that the executed CI smoke ships in this PR.
Creating the branch or opening a stacked PR does not change this status.
