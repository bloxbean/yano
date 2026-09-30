# amaru-validator

An optional transaction validator for Yano. It runs Pragma's
[Amaru](https://github.com/pragma-org/amaru) Conway ledger rules, compiled to WebAssembly, inside
the JVM. The design is in
[ADR-057](../adr/057-optional-amaru-wasm-transaction-validator.md).

- The Rust crate that builds the `.wasm` is [`../amaru-validator-wasm`](../amaru-validator-wasm/README.md).
  Its interface is in [`INTERFACE.md`](../amaru-validator-wasm/INTERFACE.md).
- This module loads the `.wasm` with Endive (`run.endive`, the continuation of Chicory). The
  module is compiled to JVM bytecode at build time (AOT), and the runtime compiler is never on
  the classpath. It implements ADR-056's `LedgerValidationEngine` as engine `amaru`
  (`org.yanoproject.ledger.amaru`).
- Only a build run with `-PwithAmaru=true` contains this module. Default builds, releases,
  Docker images, native images and Maven Central publications do not. With any other build,
  `yano.validation.engine=amaru` stops startup with a clear message.

## When to use it

| Role | Configuration | What it gives you |
|---|---|---|
| Admission engine | `yano.validation.engine: amaru` | Mempool admission and block selection go through Amaru's phase-1 rules. Plutus runs on Scalus by default. |
| Admission shadow | `shadow-engines: amaru` next to another admission engine | Every admission is also validated by Amaru. The verdict never changes; disagreements are counted and dumped. |
| Shadow-sync engine | `shadow-sync: true`, `shadow-sync-engines: java-julc,amaru` | Every transaction of every applied Conway block is re-validated against its pre-block state. Observe only. |
| Test oracle | `:ledger-conformance:test -PwithAmaru=true …` | The reference engine for ADR-056's conformance harness (baseline, mutation matrices), run with `phase2 = full`. |

Amaru is Haskell-cross-checked (276 scenarios at the pinned tag). A Java-vs-Amaru disagreement is
resolved against Haskell, not by copying Amaru.

## 1. Build the wasm module

The crate sits outside the Gradle build. Its [README](../amaru-validator-wasm/README.md) has the
full procedure. In short, you need:

- **rustup.** `rust-toolchain.toml` pins the nightly that Amaru pins (`nightly-2026-09-04` today)
  and the `wasm32-wasip1` target. Both install automatically.
- **A C compiler that targets wasm32**, for `blst` and `secp256k1-sys`. Apple clang cannot do
  this. Use [wasi-sdk](https://github.com/WebAssembly/wasi-sdk) through `WASI_SDK_PATH` (CI uses
  `wasi-sdk-34`), or [zig](https://ziglang.org) 0.13.0 on `PATH` or through `ZIG`.
- **`wasm-opt`** from binaryen (CI uses `version_132`), and `python3`.

```sh
cd amaru-validator-wasm
cargo test --locked                                # native: all 276 scenarios, both modes
WASI_SDK_PATH=/opt/wasi-sdk scripts/build-wasm.sh  # or: ZIG=/path/to/zig scripts/build-wasm.sh
# -> target/wasm-out/amaru_validator.wasm and amaru_validator.wasm.sha256
#    (under $CARGO_TARGET_DIR/wasm-out when that is set)
```

**Version pinning.** `AMARU_VERSION` holds the Amaru `tag=`, `commit=` and `toolchain=`.
`Cargo.toml` depends on that tag, and `Cargo.lock` pins the exact commit. The module embeds
`AMARU_VERSION` plus `crate=<Cargo.toml version>`, returns it from `amaru_version()`, and Yano logs
it at startup (`Amaru validator ready: … module tag=… commit=… toolchain=… crate=…`).

CI (`.github/workflows/amaru-wasm.yml`) builds the same module on Linux with wasi-sdk. It
publishes the module and a bundle with licences as a workflow artifact, and attaches them to `v*`
releases.

## 2. Build Yano with it

Add `-PwithAmaru=true`. The task `prepareWasm` then provides the module in one of three ways:

| Property | Source of the module |
|---|---|
| `-PamaruWasm=/abs/path/amaru_validator.wasm` | A module you built or downloaded. If `<file>.sha256` sits next to it, the module is checked against it. Use an absolute path. |
| `-PamaruBuild=local` | Runs `amaru-validator-wasm/scripts/build-wasm.sh` (needs the toolchains above), then checks the `.sha256` it writes. |
| neither | Downloads the release asset pinned by `amaruWasmReleaseTag` and `amaruWasmSha256`. **No release carries the module yet**, so both are empty and the task fails, naming the two options above. |

`generateAmaruAot` then compiles the module to JVM bytecode with Endive's build-time compiler
(about 3.4 MB of classes plus a 1.7 MB `.meta` resource, packaged in the jar). Four functions
(indices 21, 188, 241, 392) exceed the JVM method-size limit and run in Endive's interpreter; the
build prints a warning for each. `-PamaruInterpreterFallback` (default `WARN`) controls this.

```sh
W=/abs/path/amaru_validator.wasm

# JVM distribution: app/build/distributions/yano-<version>.zip
./gradlew :app:yanoDistZip -PskipSigning=true -PwithAmaru=true -PamaruWasm=$W

# native distribution (GraalVM 25)
./gradlew :app:yanoNativeDistZip -Dquarkus.native.enabled=true -Dquarkus.package.jar.enabled=false \
  -PskipSigning=true -PwithAmaru=true -PamaruWasm=$W

# module tests: the scenario gate and golden tests need the corpus (a clone of pragma-org/amaru
# at the tag in AMARU_VERSION, or its crates/amaru-ledger/tests/data/transaction directory)
./gradlew :amaru-validator:test -PwithAmaru=true -PamaruWasm=$W -PamaruScenariosDir=<amaru clone>

# Phase C devnet parity (engine amaru against java-julc, about two minutes)
./gradlew :amaru-validator:test --tests '*AmaruDevnetParityTest' -PwithAmaru=true -PamaruWasm=$W \
  -PledgerRulesGate=true
```

`./gradlew projects -PwithAmaru=true` lists `:amaru-validator`; without the flag it is not there.

## 3. Enable it at runtime

The properties live under `yano.validation` in `app/src/main/resources/application.yml`. You can
also pass them as `-D` system properties, for example `-Dyano.validation.engine=amaru`.

```yaml
yano:
  validation:
    engine: amaru               # scalus (default) | java-julc | java-scalus | amaru
    amaru:
      phase2: scalus            # scalus (default): Amaru phase one + Scalus Plutus | amaru: Amaru for both
      pool-size: 0              # Amaru instances; 0 = validation threads + 2
      timeout-ms: 2000          # a call that takes longer is rejected and its instance replaced
      max-abandoned: 2          # stuck calls tolerated before the engine turns unhealthy
      max-memory-pages: 2048    # linear-memory limit per instance, 64 KiB pages (128 MiB)
```

**Phase 2.** With `phase2: scalus`, Amaru runs phase one and the node's Scalus
`ScriptPhaseEvaluator` runs the Plutus scripts. That is the default for admission. `phase2: amaru`
(or `full`) lets Amaru run the scripts too; oracle runs use it. ExUnits evaluation
(`TransactionEvaluator`) does not change in either mode.

**As a shadow.** Shadows run asynchronously on frozen copies of each admission and never change the
verdict. Disagreements are counted in `yano_validation_disagreements_total{engine,rule}`, and
`shadow-dump-dir` writes a replay bundle for each.

```yaml
# Amaru shadows the default (legacy Scalus) admission:
engine: scalus
shadow-engines: amaru
shadow-dump-dir: /var/lib/yano/shadow-dumps

# or Amaru admits, and the Java engine shadows it (an admission shadow needs the opt-in):
engine: amaru
shadow-engines: java-julc
java-engine:
  experimental: true
```

**Shadow sync.** `shadow-sync-engines: java-julc,amaru` runs both engines on every Conway block the
node applies, and writes findings to `shadow-sync-report` (JSONL) and `shadow-sync-dump-dir`. It is
observe only. Two things to know:

- Amaru is slower than the Java engines: admission p50 was 1.2–1.4 ms under block production
  (ADR-057 Phase C), and one pass over the scenarios took 2.17 ms per scenario against 0.41–0.55 ms
  for `java-scalus` and `java-julc` (`ledger-conformance/docs/baseline-2026-09.md`). Block
  application waits when shadow sync falls behind, so `amaru` slows a sync from genesis.
- Blocks at protocol version 9 give Amaru engine failures (`ENGINE.EraNotSupported`, no verdict).

The public-network shadow-sync gate of ADR-056 Phase 7c ran `java-julc,java-scalus`. No
public-network run with `amaru` is recorded yet.

**Pool size.** Each role (admission, admission shadow, shadow sync) creates its own engine with its
own pool. `pool-size: 0` resolves to `ValidationEngineBootstrap.VALIDATION_THREADS` (4: admission,
the rebuild worker and two shadow workers) plus `AmaruEngineFactory.EXTRA_CALLERS` (2: the rebuild
worker and block selection), so 6 instances per engine today. Each
instance runs on its own platform thread with a 256 MiB stack. That stack is reserved address
space, committed only as deep as a call goes. Linear memory is on the heap and grows up to
`max-memory-pages`.

**Health and failures.**

- A trap, a timeout, an undecodable response or a module error rejects that one transaction with
  `ENGINE.AmaruEngineFailure`, and the instance is replaced.
- State the ledger view cannot provide rejects with `ENGINE.LedgerStateUnavailable`, without
  calling the module.
- After `max-abandoned` calls that did not stop, the engine is unhealthy until restart. Every call
  then fails closed with `ENGINE.AmaruEngineUnhealthy`. The readiness check `validation-engine`
  goes down only for the admission engine; an unhealthy shadow keeps it up with
  `shadowUnhealthy` set, and shadow-sync engines never gate it.
  `yano_validation_engine_healthy{engine}` (and `yano_validation_shadow_sync_engine_healthy`)
  report each engine.
- A missing module, or one with an unsupported interface version, stops startup.

**Rollback.** Set `engine` back to `scalus`, `java-julc` or `java-scalus`, and remove `amaru` from
`shadow-engines` and `shadow-sync-engines`. There is no persisted state.

### Native image

Build with the native command above. Only the build-time AOT classes and the `.meta` resource are
used, so nothing compiles wasm at run time. The module ships its own `META-INF/native-image`
configuration: `resource-config.json` for the `.meta` resource and the engine's service file, and
`reflect-config.json` for `AmaruEngineFactory`. ADR-057 Phase E checked JVM-vs-native parity with
`JAR=… NATIVE=… qa/harness/ledger-rules-native-parity.sh amaru "11 10"`. That covered admission,
block selection, `java-julc` as a shadow, shadow sync with `java-julc,amaru`, the instance pool,
health, metrics and shutdown. The verdicts were identical at PV 10 and 11.

## Known divergences and limits

- **Conway from protocol version 10 only.** Below 10, or for a non-Conway body, the engine answers
  `ENGINE.EraNotSupported` (ADR-057 invariant 6).
- **Transactions Amaru accepts and Haskell rejects** (recorded on the conformance mutants,
  `phase2 = full`):
  - a PlutusV3 script whose CBOR is followed by one more byte (Haskell:
    `UTXOW.MalformedScriptWitnesses`);
  - a pool registration whose VRF key hash another pool already uses, at PV 11 (Haskell:
    `POOL.VRFKeyHashAlreadyRegistered`). The request carries pool ids only.
- **Rejected by both, under a different name:**
  - A pool delegation from an unregistered credential (the Phase C divergence). Haskell reports
    `DELEG.StakeKeyNotRegisteredDELEG`. Amaru accepts, and the adapter then cannot derive the
    effects, so it fails closed with `ENGINE.AmaruEngineFailure`. `AmaruKnownDivergencesTest`
    pins it.
  - A DRep update of an unregistered DRep gives `ENGINE.AmaruEngineFailure` the same way.
  - A pool metadata hash that is not 32 bytes gives `ENGINE.DecodingFailure`, where Haskell's
    `POOL` rejects it.
  - Constructor names: scenario 00280 (`OutputTooBigUTxO` for Haskell's
    `OutputBootAddrAttrsTooBig`), non-ADA collateral (`ValueNotConservedUTxO` for
    `CollateralContainsNonADA`), and `VotersDoNotExist` for PV 11's `UnelectedCommitteeVoters`.
  - Amaru stops at its first failure, while Haskell lists all of them.
- **`phase2: scalus` gaps are covered by the evaluator.** Amaru's phase-one mode does not report
  `MalformedScriptWitnesses` or `CollectErrors`, so the engine calls the Scalus evaluator whenever
  a transaction has redeemers or Plutus witness scripts, or spends an input carrying a reference
  script.
- **CCL decoding is still needed** for the MEMPOOL step and for effects. A transaction Amaru
  accepts but CCL cannot decode fails closed.
- **Speed.** Wasm runs about 15–20 times slower than native Amaru. That is fine for admission, but
  not for bulk validation.

ADR-056 (Phase 3a and 3b results) and `ledger-conformance/docs/baseline-2026-09.md` hold the full
list and the Haskell references.

## Upgrading the pinned Amaru version

Follow the runbook in [`../amaru-validator-wasm/README.md`](../amaru-validator-wasm/README.md#upgrading-amaru):
pick the tag, bump `Cargo.toml` and `AMARU_VERSION`, re-check `[patch]` sections, re-seed
`Cargo.lock` from Amaru's, adapt the wrapper, and run the gates. On the Yano side:

1. **Bump the crate version** in `amaru-validator-wasm/Cargo.toml` whenever the failure mapping
   (`src/failure.rs`) or any other host-visible behaviour changes. The conformance harness reads
   that version and refuses a module whose `amaru_version()` reports another `crate=`
   (`AmaruReferenceEngine.verifyModule`: "The Amaru module is stale …"). A stale module would
   otherwise skew every baseline silently.
2. Bump `ABI_VERSION` and `INTERFACE.md` only if the request or response documents change. The
   Java side refuses a module whose `abi_version()` it does not support
   (`AmaruTransactionValidator.SUPPORTED_ABI_VERSION`).
3. Rebuild the module and run, with `-PwithAmaru=true -PamaruWasm=<new module>`:
   - `:amaru-validator:test` with the corpus of the new tag: the scenario gate in both modes, the
     golden request encoding and request equivalence. The scenario count is pinned
     (`AmaruScenarioLoader.EXPECTED_SCENARIOS`).
   - `AmaruDevnetParityTest` with `-PledgerRulesGate=true`. If a known divergence is fixed,
     `AmaruKnownDivergencesTest` fails; shrink the allow-list.
   - `:ledger-conformance:test`, then `conformanceReport` to regenerate the baseline, and the
     blueprint known-failure list (`amaru-known-failures.txt`) against the new checkout.
4. Once a release carries the module, set `amaruWasmReleaseTag` and `amaruWasmSha256` to it.
5. The release notes name the Amaru tag and commit.

## Benchmarks

Two opt-in tests measure latency, startup and memory. They are skipped unless you pass
`-PengineBenchmark=true`. Run them on an otherwise idle machine; ADR-057 Phase E has the exact
commands and what each figure means.

- `ledger-conformance` `EngineLatencyBenchmarkTest`: warm per-transaction p50, p90 and p99 for
  `java-julc`, `java-scalus` and `amaru` on the Amaru scenarios. It writes
  `ledger-conformance/build/conformance/engine-latency.md`.
- `amaru-validator` `AmaruFootprintBenchmarkTest`: cold module load and first instance, warm
  instantiation, and retained heap per instance. It writes
  `amaru-validator/build/benchmark/amaru-footprint.md`.
