# ADR-057: Optional Amaru WebAssembly Transaction Validator

## Status

Accepted (2026-09-28). The design was reviewed by Fable and in four Codex
review rounds on PR #155; Codex approved it at `40dfd3168`. Satya accepted it
with the decisions recorded at the end of this ADR.

Implementation (2026-09-30), on PR #155; the results are under each phase of
the implementation plan:

| Phase | Status | Commits |
|---|---|---|
| A: Rust crate, interface, CI build | done | `312e54ad9` |
| B: Java module and engine | done | `5fb2bf944`, `88756fae1` (engine wiring, ADR-056 step 1d) |
| C: overlays and runtime parity | done, one recorded divergence (full list in Phase D) | `f54e13a33` |
| D: oracle integration | done | `e9341130e`, `88756fae1`, `cce882958` |
| E: native image | done | `b02ba6fe1`, `943a5b6e0` |
| E: latency benchmark, startup and memory figures, developer guide | done: target met on the scenario corpus; on real Plutus transactions `phase2: full` is far slower (Phase E results) | this commit |

## Date

2026-09-28

## Related decisions and evidence

- [ADR-056](056-conway-java-ledger-rules-with-state-overlays.md) defines the
  validation API (`TxValidationRequest`, `LedgerView`, `OverlayLedgerView`,
  `TxEffects`), engine selection and shadowing, and the conformance gates. This
  ADR plugs Amaru into all of them.
- Pragma's Amaru (`https://github.com/pragma-org/amaru`, Apache-2.0). All
  Amaru file and symbol references in this ADR are at commit `d72e9b5`
  (2026-09-25). Older checkouts differ: the April 2026 tree has no
  `prepare_transaction` and a different CI file. The pinned tag
  `v10.11.20260925` is commit `eaf8ac3f`, which is `d72e9b5` plus one
  Debian-packaging commit. That commit adds two systemd-unit lines to
  `crates/amaru/Cargo.toml`, the node binary's Debian metadata. Nothing
  under `amaru-ledger`, `amaru-kernel`, `amaru-plutus`, `amaru-uplc` or
  `crates/vendor` changed, and neither did the root `Cargo.toml`,
  `Cargo.lock` or `rust-toolchain.toml` (Phase A).
  - Amaru's CI builds `amaru-ledger` for `wasm32-unknown-unknown` on every push
    (`.github/workflows/ci-build-and-test.yml`, job `build-wasm32`).
  - Its mempool path `state.rs validate_tx` calls
    `rules::block::validate_transaction(...)`, which runs phase-1, then phase-2
    through Amaru's own UPLC machine.
- The 2026-09-25 spike (at Amaru commit `d72e9b5`) found:
  - **Build.** A `cdylib` wrapper exporting `alloc`/`dealloc`/`validate`
    compiled to **`wasm32-wasip1`**: 4.0 MB after `wasm-opt -O3`, and its only
    imports were 7 `wasi_snapshot_preview1` functions. The module needs no
    threads, atomics, SIMD, exception handling or tail calls.
  - **Correctness.** Run in **Chicory 1.7.5** (runtime compiler), it returned
    the correct verdict on 5 of Amaru's Haskell-cross-checked scenarios,
    including Plutus V2 reference-script and V3 phase-2 cases and one expected
    phase-2 failure.
  - **Speed.** Warm median was 0.8–1.3 ms per transaction, against 57–72 µs for
    the same code natively (about 15–20× slower). The interpreter took about
    26 ms. Parse plus runtime compilation took about 2 s at startup.
  - **Compiler limits.** Three functions exceed the JVM method-size limit and
    fall back to the interpreter.
  - **Target choice.** `wasm32-unknown-unknown` builds but can't run. The
    validation path calls `Instant::now()` for timing spans, which panics on
    that target. It also picks up `wasm-bindgen` imports.
  - **Toolchain.** Amaru requires a pinned **nightly** toolchain (unstable
    `try_trait_v2`). Its C dependencies (`blst`, `secp256k1-sys`) need a clang
    that can target wasm (zig or wasi-sdk). Apple clang can't.
  - **Scope.** Amaru validates Conway only, from protocol version 10.
  - **Churn.** `validate_transaction` changed signature on 2026-08-25 and
    `ValidationContext` on 2026-08-20.
- **Chicory has moved.** Chicory's last Dylibso release is 1.7.5. It continues
  at the Bytecode Alliance as **Endive** (`run.endive:*`, 1.1.0 on 2026-09-03):
  same maintainers, same API, new package name. Phase B uses Endive 1.1.0 from
  Maven Central (`run.endive:runtime`, `run.endive:wasm`, and
  `run.endive:build-time-compiler` at build time only); no Chicory fallback
  was needed.
  - Build-time (AOT) compilation and the interpreter work in a GraalVM 25 native
    image. The runtime compiler does not.
  - A wasm trap surfaces as a catchable exception. By contrast, a Rust panic
    through Panama FFM aborted the JVM.

## Decision summary

1. **Yano owns a small Rust crate, `amaru-validator-wasm/`**, at the repository
   root, outside the Gradle build. It depends on Amaru by **git tag**, initially
   `v10.11.20260925`, with our `Cargo.lock` pinning the exact commit. The crate
   defines a **versioned interface**: exports plus a CBOR request/response
   schema.
2. **Optional Java module `amaru-validator`.** It loads the module with **Endive**
   (build-time AOT) and implements ADR-056's `LedgerValidationEngine`.
   - It is excluded from default builds and distributions.
   - Developers opt in at build time, and select it at run time with
     `yano.validation.engine=amaru-scalus` or `amaru` (decision 6).
   - It can also run as a shadow engine and as a test oracle.
3. **Overlays are handled on the Java side.** Yano builds each request from the
   ADR-056 `OverlayLedgerView` over the ticked base view. Earlier mempool
   transactions, earlier transactions in the same block, and a pending epoch
   boundary are therefore visible to Amaru exactly as they are to the Java
   engine. Effects are derived by the shared `TxEffectsDeriver` from the verdict,
   not by Amaru. The ADR-056 `Origin` policy for `isValid=false` applies
   unchanged.
4. **Phase-2 can stay with Scalus.** Under engine `amaru-scalus`, Amaru judges
   phase-1 and Scalus runs the Plutus scripts. Engine `amaru` runs Amaru's full
   validation instead, which is the engine used for oracle runs. ExUnits
   evaluation (`TransactionEvaluator`) is unchanged.
5. **Yano's CI builds the wasm** in a separate, path-filtered workflow, gates it
   on Amaru's scenarios, and publishes it (workflow artifact and a Yano release
   asset with sha256). The normal Gradle build never needs Rust.

## Context

Yano's submit path uses Scalus, which misses the whole Conway GOV family and
several certificate and LEDGER checks (ADR-056). ADR-056 fixes that in Java, but
that takes time. Amaru already implements the complete Conway rule set and
cross-checks it against the Haskell ledger with 276 scenarios plus the
cardano-blueprint vectors.

Running Amaru inside the JVM gives Yano two things:

- **An engine** with near-complete Conway coverage now, for developers who opt
  in.
- **An independent oracle** for ADR-056. Every Java verdict can be compared with
  a Haskell-checked implementation, on fixtures, on mutated transactions and on
  real synced traffic.

Amaru's design fits a sandboxed call with no callbacks:
- `prepare_transaction` computes the set of required keys.
- `into_validation_context` materialises those keys into a
  `DefaultValidationContext` (plain in-memory maps with a public constructor).
- `validate_transaction` then runs against that context.

Rules never make open-ended lookups: the one non-key lookup (committee member
by hot credential) is resolved during preparation. So one call can carry
everything the transaction needs.

## Decision drivers

- **Optional and isolated.** No Rust, nightly or wasm toolchain for anyone who
  doesn't opt in, and no effect on default images.
- **Same semantics as the Java engine** for mempool and same-block state, so
  differential results are meaningful.
- **Pinned, reproducible dependency** on a released Amaru tag that Yano chooses
  to move.
- **Sandboxing.** A panic in Amaru must reject one transaction, not crash the
  node.
- **Native-image support** when opted in.

## Invariants

1. `amaru-validator` is never on the default runtime classpath. Its absence
   leaves every other engine unaffected.
2. The wasm module is pure: no file system, network or environment access. The
   WASI imports are satisfied by a minimal host (clock, random, stdout/stderr
   sink, `proc_exit` → trap).
3. **Every request is self-contained**, built from one ADR-056 canonical
   snapshot plus overlay. Each required key is resolved with the three-outcome
   `Lookup` contract (ADR-056 invariant 2):
   - **Present:** the entry is included in the request.
   - **Confirmed absent:** the entry is left out of the corresponding slice. That
     is exactly how Amaru represents absence: its preparation step returns only
     the keys that exist, because, for example, a pool may be registering for the
     first time (`context/default/preparation.rs`, `resolve_pools`, at
     `d72e9b5`). The rules then report any typed ledger failure themselves.
   - **Unavailable:** the request is not sent. The transaction is rejected with
     `LedgerStateUnavailable` (an engine failure, not a ledger failure).

   Amaru never sees partial state, and a legitimately absent record is never
   turned into a host-side rejection.
4. **A trap, a timeout or an undecodable response rejects the transaction as
   phase-1** (`AmaruEngineFailure`), and the instance is discarded and
   re-created.
5. **The interface is versioned.** The Java side refuses a module whose
   `abi_version` it doesn't support.
6. **Conway only.** The request builder refuses protocol versions below 10 and
   non-Conway bodies (ADR-056 invariant 7).

## Detailed decision

### 1. Rust crate `amaru-validator-wasm/`

```
amaru-validator-wasm/
  Cargo.toml            # cdylib + rlib; git deps on amaru-ledger/-kernel/-plutus at the pinned tag
  Cargo.lock            # seeded from Amaru's lockfile at that tag; pins exact commits
  rust-toolchain.toml   # the nightly pinned by that Amaru tag (nightly-2026-09-04 today) + wasm32-wasip1
  .cargo/config.toml    # wasm stack size
  AMARU_VERSION         # tag, commit, toolchain — read by CI and embedded in the module
  INTERFACE.md          # interface v1: exports, CDDL, absence and byte-exactness rules
  src/lib.rs            # the exports
  src/interface.rs      # v1 request/response codec (the reference encoder)
  src/engine.rs         # required_keys and validate over Amaru's public API
  src/failure.rs        # Amaru errors -> Haskell rule and constructor at ADR-056's pinned revision
  tests/amaru_scenarios.rs  # scenario gate: 276 scenarios, both modes, required_keys sufficiency
  scripts/              # build-wasm.sh, wasm_check.py, run_wasm_scenarios.py, bundle.sh, zig shims
  deny.toml, about.toml # cargo deny licence/source policy; cargo-about third-party licence file
  NOTICE, LICENSE-AMARU # Apache-2.0 attribution for Amaru and bundled crates
```

- Cargo does not apply `[patch]` sections from a dependency's workspace. If
  Amaru's root `Cargo.toml` at the pinned tag has `[patch.crates-io]` entries,
  they are copied into ours. Amaru's `crates/vendor/*` path dependencies resolve
  inside the git checkout and need no copying. The upgrade runbook re-checks
  both on every bump.
- **Lockfile procedure.**
  1. Copy Amaru's `Cargo.lock` at the tag.
  2. Run one `cargo build` without `--locked`, so Cargo adds our crate and the
     git sources.
  3. Commit the lockfile.
  4. Build with `--locked` in CI.
- **Licences.** `cargo deny check licenses` (with a committed `deny.toml`) runs
  in CI. It replaces the spike's manual licence audit.
- Build flags: `--target wasm32-wasip1 --release`, `lto = true`,
  `codegen-units = 1`, `panic = "abort"`, `-C link-arg=-zstack-size=8388608`.
  Deep PlutusData nesting can't grow the stack in wasm, so it is sized
  generously.
- Exports (interface version 1):

| Export | Purpose |
|---|---|
| `abi_version() -> u32` | Interface version check |
| `amaru_version() -> resp_ptr` | Embedded tag, commit and toolchain, for logs and bug reports. Phase A made this return a `[u32 LE len][UTF-8]` buffer, the same convention as the other exports |
| `alloc(len) -> ptr`, `dealloc(ptr, len)` | Guest memory management |
| `required_keys(tx_ptr, tx_len, env_ptr, env_len) -> resp_ptr` | Runs `prepare_transaction`; returns the key set the host must resolve |
| `validate(req_ptr, req_len) -> resp_ptr` | Builds a `DefaultValidationContext` from the request and validates (mode selects phase-1 only, or full) |

Responses are `[u32 LE length][CBOR]`, freed by the host with `dealloc`.

- **Request (CBOR):**
  - `abi_version`, `mode` (`phase_one` | `full`);
  - tx bytes;
  - network magic, era history and global parameters (always explicit, so
    custom devnets work);
  - protocol parameters (Amaru's CBOR `Decode`, which is always available,
    unlike its test-only serde);
  - governance activity (dormant epochs), guardrail script hash, enacted roots,
    treasury;
  - transaction pointer (slot);
  - the resolved state slices: UTxO entries, accounts, pools, DReps, committee
    members, proposals.
- **Response (CBOR):** `ok`, or `invalid { phase, rule, constructor, detail }`
  using Haskell constructor names wherever Amaru exposes them, or
  `error { message }`.
- **Two calls per transaction.** `required_keys` first (Amaru's
  `prepare_transaction`: inputs, reference inputs, collateral, accounts,
  pools, DReps), then `validate`. For robustness against changes in Amaru's
  preparation API, the request **always** carries the full current committee
  (cold and hot credentials, expiry, resignations, pending `UpdateCommittee`
  candidates) and **all active proposals with enacted roots**, whatever
  `required_keys` returned. Both are small on every network.
- **Parameter encoding.** Protocol parameters use Amaru's own CBOR layout
  (`protocol_parameters.rs` `Decode`), not the CDDL `ProtocolParamUpdate`
  layout. Amaru's layout omits four values that Haskell hardcodes: the
  per-transaction and per-block reference-script size limits, and the
  reference-script cost stride and multiplier. An optional `ledger_constants`
  map overrides them (Phase A; Amaru's scenarios move them). Golden interface
  tests pin the encoding, so an Amaru bump that changes
  it fails CI instead of mis-decoding.

### 2. Java module `amaru-validator` (optional)

- **Contents.**
  - Endive runtime and WASI host.
  - The AOT classes generated from the pinned `.wasm` at build time, through the
    Endive build-time compiler or a Gradle task calling its `Generator`, with
    `interpreterFallback=WARN` until the three oversized functions are split
    upstream or in the wrapper.
  - `AmaruTransactionValidator implements LedgerValidationEngine`.
  - All Java code lives in `org.yanoproject.ledger.amaru`, next to ADR-056's
    `org.yanoproject.ledger.rules`. The Endive-generated AOT classes go under
    `org.yanoproject.ledger.amaru.generated`.
- **Request flow.**
  0. For rule `MEMPOOL`, run ADR-056's Java `MEMPOOL` step against the incoming
     state first (all-inputs-spent, then the pre-PV11 unelected-committee
     check). Amaru's `validate_transaction` implements `LEDGER` only, so failure
     precedence stays identical across engines.
  1. Decode the tx.
  2. Call `required_keys`.
  3. Resolve each key through the request's `LedgerView` (the overlay).
  4. Encode the request and call `validate`.
  5. Map the response to `TxValidationOutcome`.
  6. Under `amaru-scalus`, run ADR-056's `ScriptPhaseEvaluator` after a
     phase-1 pass.
  7. On success, compute effects with `TxEffectsDeriver`.
- **Instances.** One instance per validation thread, pooled. Instances aren't
  thread-safe, and a pool scales linearly (measured). Memory is `ByteArrayMemory`
  with a page limit. `WasmModule` and the AOT machine are shared.
- **Timeouts.** Each instance runs on its own dedicated worker thread, and the
  caller waits with `yano.validation.amaru.timeout-ms` (default 2000).
  - On timeout the transaction is rejected (`AmaruEngineFailure`), and the
    instance and its thread are poisoned and replaced.
  - Endive checks the thread's interrupt flag on every call and backward
    branch of compiled code (it has no fuel metering), so the interrupted
    guest stops and its thread is reclaimed. Only a worker stuck outside wasm
    code can keep running; it counts as abandoned.
  - To stop a hostile or looping input from exhausting the node, a hard cap of
    `yano.validation.amaru.max-abandoned` (default 2) applies. When it is
    reached, the engine is marked **unhealthy**: admission through Amaru
    fails closed, and a health check and metric alert fire until restart.
  - Endive's interrupt and fuel support was verified in Phase B (open
    question 3).
  - Normal work is bounded: phase-1 by transaction size, and Amaru phase-2
    (`full` mode) by ExUnits.
- **Configuration:**

```yaml
yano:
  validation:
    engine: amaru-scalus       # or amaru; or keep java-julc/java-scalus/scalus and list it under shadow-engines
    amaru:                     # settings of both Amaru engines
      pool-size: 0             # 0 = validation threads + 2
      timeout-ms: 2000
      max-abandoned: 2         # stuck calls tolerated before the engine turns unhealthy
      max-memory-pages: 2048   # 128 MiB per instance
```

If an Amaru engine is configured but the module isn't on the classpath, startup fails
with a clear message. There is no silent fallback.

### 3. Build and distribution

- **CI workflow `amaru-wasm.yml`.** Triggers: changes under
  `amaru-validator-wasm/**`, `workflow_dispatch`, and release tags. Ubuntu
  runner. Steps:
  1. Install the toolchain from `rust-toolchain.toml` and
     `rustup target add wasm32-wasip1`.
  2. Install wasi-sdk (pinned version, checksum-verified) and set
     `CC_wasm32_wasip1`/`AR_wasm32_wasip1`.
  3. Run `cargo deny check licenses sources` (pinned, checksum-verified).
  4. Shallow-clone Amaru at the pinned tag to get
     `crates/amaru-ledger/tests/data/transaction/`, checking the commit against
     `AMARU_VERSION`.
  5. Run `cargo test --locked` natively. It covers all 276 scenarios in both
     modes (phase, rule and Haskell constructor), `required_keys` sufficiency,
     and arena growth.
  6. `cargo build`, then `wasm-opt -O3` (pinned binaryen). `wasm_check.py`
     checks the imports, exports and features, and the sha256 is recorded in
     `amaru_validator.wasm.sha256`.
  7. Replay every `validate` and `required_keys` call from step 5 through the
     built module under wasmtime (hash-pinned). The responses must be
     byte-identical to native. The Endive run of the scenarios is Phase B.
  8. Bundle `amaru-validator-wasm-<version>.tar.gz`: the module, its sha256,
     `AMARU_VERSION`, `NOTICE`, `LICENSE-AMARU`, `INTERFACE.md`, and
     `THIRD-PARTY-LICENSES.txt` generated by cargo-about (pinned,
     checksum-verified). Upload the bundle and the standalone module, each with
     a `.sha256`, as a workflow artifact. On a Yano release tag, wait for
     `release-dist.yml` to create the release, then attach them to it.
- **Gradle.**
  - `amaru-validator` is included in `settings.gradle` only when
    `-PwithAmaru=true`.
  - Its `prepareWasm` task either runs `cargo build` when `-PamaruBuild=local`
    (the developer has the toolchains) or downloads the release asset for the
    pinned version and verifies its sha256.
  - Default builds, the uber-jar, Docker images and native images don't include
    it unless built with `-PwithAmaru=true`.
  - It is added to `centralDeploymentExclusions` (`build.gradle:684`) and kept
    out of the BOM, so it is never published to Maven Central from a default
    release.
- **Native image.** Only the build-time AOT classes plus the `.meta` resource
  are used, with resources registered in the module's
  `META-INF/native-image`. Runtime compilation is disabled in native builds.
- **Licensing.** The module and the Yano distribution built with it ship Amaru's
  Apache-2.0 license and NOTICE and the third-party notices of the compiled
  crates. There is no copyleft in the graph (the spike's audit found only
  MIT/Apache/BSD/Zlib/CC0/Unicode/BlueOak).

### 4. Upgrading Amaru

1. Bump the tag in `Cargo.toml` and `AMARU_VERSION`.
2. Copy `rust-toolchain.toml`, re-check the `[patch]` and `vendor/` overrides,
   and re-seed `Cargo.lock` from Amaru's lockfile.
3. Adapt the wrapper if `validate_transaction`, `ValidationContext` or kernel
   types changed. Bump `abi_version` only if the interface changes.
4. CI: the scenario gate, then the ADR-056 differential suite. Record any new
   divergences.
5. Release notes name the Amaru tag and commit.

### 5. Relationship to ADR-056 conformance

- **Oracle.** In oracle runs, `amaru-validator` runs as engine `amaru`. It
  provides:
  - the JUnit differential for ADR-056's scenarios, mutation matrix and
    shadow-dump bundles;
  - a shadow engine for live preprod/preview/mainnet traffic.
- **Resolving disagreements.** A Java-vs-Amaru disagreement is resolved against
  Haskell. If Amaru is wrong, Yano records the divergence and reports it
  upstream. Yano does not copy Amaru's behaviour.

## Implementation plan

Shipped in the same PR as ADR-056 (#155), phase by phase (ADR-056 "Implementation plan").

### Phase A — Rust crate, interface, CI build

- Set up the crate, the git dependency on `v10.11.20260925` (confirm its commit
  against `d72e9b5`), the toolchain pin, and the `[patch]` copy.
- Implement exports, CBOR request/response and both modes.
- Add the `amaru-wasm.yml` workflow and a Linux build with wasi-sdk (the spike
  used macOS with zig; the Linux build is unproven).
- Resolve the timing-span issue: `wasm32-wasip1` provides `clock_time_get`.
  Separately, ask upstream to gate `Instant::now()` for wasm.
- Gate (as implemented):
  - CI produces the module and its bundle.
  - Its imports are exactly the WASI p1 set, plus its own exported memory.
  - All 276 scenarios pass natively in both modes, with Haskell rule and
    constructor names.
  - Every `validate` and `required_keys` response replays byte-identically
    through the `.wasm` under wasmtime.

  Running the scenarios through a thin Endive harness moves to Phase B, together
  with the WASI host it needs (see `amaru-validator-wasm/README.md`).

### Phase B — Java module and engine

- Build `amaru-validator` with Endive AOT, the WASI host, the instance pool and
  watchdog, the request builder from `LedgerView`, response mapping,
  `phase2: scalus|amaru`, and the `-PwithAmaru`/`prepareWasm` plumbing.
- Wire `yano.validation.engine=amaru` and shadow-engine registration (moved
  to ADR-056 step 1d; see "What step 1d must provide" below).
- Gates:
  - unit tests, including golden interface tests for the request encoding;
  - absence handling: a fresh pool registration, deregistration followed by
    registration, and a first-time DRep registration pass through Amaru with
    their expected verdicts. An injected unavailable read rejects with
    `LedgerStateUnavailable` without calling the module;
  - scenarios pass through the full Java engine path;
  - a trap and a timeout each reject and recover;
  - the abandoned-thread cap turns the engine unhealthy and fails closed;
  - Endive interrupt and fuel support is determined and documented;
  - absence of the module leaves default builds unchanged.

#### Phase B results (2026-09-29)

**Module and build.**
- `amaru-validator/` (package `org.yanoproject.ledger.amaru`, with `.runtime`
  for the Endive instance, WASI host and pool, `.wire` for the v1 codec, and
  `.generated` for the AOT classes). `settings.gradle` includes it only with
  `-PwithAmaru=true`; `./gradlew projects` without the flag does not list it.
  It is in `centralDeploymentExclusions`, removes its own Maven publication
  (so `verifyMavenReleasePublicationScope` still reports the default 24
  projects with the flag), and is not in the BOM.
- `prepareWasm`: `-PamaruBuild=local` runs `amaru-validator-wasm/scripts/build-wasm.sh`
  and checks the result against the `.sha256` it writes; `-PamaruWasm=<file>`
  uses a given module (checked against `<file>.sha256` when present);
  otherwise it downloads
  `https://github.com/bloxbean/yano/releases/download/<amaruWasmReleaseTag>/amaru_validator.wasm`
  and verifies it against `amaruWasmSha256`. No release carries the module
  yet, so both properties are empty and the task fails with a message naming
  the two local options.
- `generateAmaruAot` runs Endive's `GeneratorMain` (build-time compiler) over
  the module: 3.4 MB of classes plus a 1.7 MB stripped `.meta`, generated
  under `build/generated/endive` and packaged in the jar. With
  `interpreterFallback=WARN` (property `amaruInterpreterFallback`) **four**
  functions (indices 21, 188, 241, 392) exceed the JVM method-size limit and
  run in the interpreter; the spike saw three. The AOT classes reference only
  `run.endive:runtime` and `run.endive:wasm`: the runtime compiler is never
  on the classpath. `META-INF/native-image/.../resource-config.json`
  registers the `.meta` resource; the native image itself is Phase E.
- The build is reproducible: a second local build gives the same sha256
  (`90f1c154…`), so the AOT task stays up to date.

**Engine** (`AmaruTransactionValidator implements LedgerValidationEngine`,
name `amaru`):
- Minimal WASI host (`clock_time_get`, `random_get`, `environ_*`/`args_*`
  empty, `fd_write` to SLF4J debug, `proc_exit` → trap, `sched_yield`),
  `_initialize`, `abi_version` check at construction (a mismatch fails
  startup), `amaru_version` logged.
- Instance pool: one instance per dedicated platform worker (256 MiB stack by
  default, since AOT wasm calls are Java calls), `ByteArrayMemory` limited to
  `max-memory-pages`, caller waits `timeout` in total. A trap or a malformed
  response discards the instance; a timeout interrupts and replaces the worker;
  `max-abandoned` stuck workers make the engine unhealthy until restart
  (`isHealthy()`, `ENGINE.AmaruEngineUnhealthy`, no module call).
- Request flow as in §2: MEMPOOL step (`org.yanoproject.ledger.rules.conway.mempool.MempoolRule`,
  shared with the Java engine), `required_keys`, three-outcome resolution
  (Unavailable → `ENGINE.LedgerStateUnavailable` with no `validate` call),
  full committee and candidates, all proposals, roots, treasury, dormant
  epochs, guardrail, parameters, era history and global parameters.
- `phase2`: `full` sends `mode = full`; `scalus` sends `mode = phase_one` and
  then calls the new SPI `org.yanoproject.ledger.rules.phase2.ScriptPhaseEvaluator`,
  comparing its result with `is_valid` (`UTXOS.ValidationTagMismatch`). Without
  an evaluator, a transaction with redeemers fails closed.
- On `ok`, effects come from `TxEffectsDeriver`, and the ADR-056 origin
  policy applies (`is_valid = false` only from `SYNC`).
- `AmaruNetworkParameters` carries the network magic, era history and
  global parameters; its Javadoc says how step 1d fills it from the genesis
  files and hard-fork history.

**Gates.**
- Scenario gate: **276 of 276** scenarios through the full Java path (the
  shared loader → `InMemoryLedgerView` → engine in `full` mode → module):
  275 with the expected verdict, rule and Haskell constructor, and scenario
  00203 (a hard-fork proposal at protocol version 9) refused with
  `ENGINE.EraNotSupported` by invariant 6, as intended. UTxO outputs are
  re-encoded from CCL on this path; no verdict changed.
- Golden interface tests: the Java encoder, fed each scenario through the
  loader, reproduces **all 549** reference requests the Rust gate dumps
  (276 `full` + 273 `phase_one`), byte for byte, including Amaru's
  31-element parameter layout and the test-only constants. Eleven of them are
  committed under `amaru-validator/src/test/resources/golden`, and the
  `amaru-wasm.yml` workflow uploads the full dump as an artifact
  (`amaru-validator-scenario-requests`).
- Engine request equivalence: for **272 of 272** scenarios that send state
  (all but the 3 decoding failures and the PV 9 one) the request the engine
  builds from the view (key resolution, canonical order, re-encoded outputs)
  carries the same state as the reference request restricted to the keys
  `required_keys` named. Slice order, certificate pointers and the output
  envelope are ignored; address bytes, value, datum (hash or exact inline
  bytes) and exact reference-script bytes must match.
- Absence: a first-time DRep registration, a deregistration followed by a
  registration, and a fresh pool registration (00111, with the minimum pool
  cost lowered to its cost) pass with the key left out of the request; an
  absent pool gives `DELEG.DelegateeStakePoolNotRegisteredDELEG`; injected
  Unavailable reads of a UTxO, an account and a pool reject with
  `LedgerStateUnavailable` and never call `validate`.
- A real wasm trap (out-of-bounds `validate` buffer) and a timeout each reject
  with `AmaruEngineFailure` and the next call succeeds on a fresh instance;
  two workers stuck outside wasm turn the engine unhealthy and it fails
  closed. A replacement instance costs about 0.3–0.9 ms (instantiation and
  `_initialize`, warm; `EndiveRuntimeTest.instantiationCost`), so trap
  recovery needs no rate limit or cooldown.
- The pool charges `timeout` to the call itself (waiting for a free worker
  has its own bound and fails as busy), hands a call over with a deadline,
  and stops workers that finish a call after `close()`.
- `prepareWasm` tracks `-PamaruWasm=<file>` and its `.sha256` sidecar as file
  inputs: changing the file's content at the same path re-runs it (checked
  by hand: unchanged → UP-TO-DATE; one byte appended → re-run; sidecar
  mismatch → failure). Downloads use connect and read timeouts and delete the
  partial file on failure.
- MEMPOOL: an all-spent duplicate reports only `LEDGER.ConwayMempoolFailure`
  without calling the module (rule `LEDGER` gives `UTXO.BadInputsUTxO`);
  scenario 00154 and 00167 (hot key authorised by the transaction's own
  certificate) are rejected under MEMPOOL at PV 10 and pass under LEDGER; at
  PV 11 (00171) the rejection is `GOV.VotersDoNotExist`.
- Security group: on scenario 00182 (an SPO votes on a pending parameter
  change) the vote passes with keys `{30}` (`govActionDeposit` only), fails
  `GOV.DisallowedVoters` with `{23}`, and unknown keys reject with
  `LedgerStateUnavailable` without calling `validate`.
- `phase2: scalus`: a malformed Plutus witness script (00256) reaches the
  evaluator and its `Rejected` failure is returned; witness scripts without
  redeemers and inputs with reference scripts also require the evaluator.
- Default builds: `./gradlew :ledger-rules:test --offline` is green (the
  scenario tests skip without `AMARU_SCENARIOS_DIR`).

**Latency** (OpenJDK 25, Apple M4 Max, warm, 5 × 276 calls, `full` mode, from `AmaruScenarioGateTest`):
the whole engine path (request building, two module calls, CCL decode,
effects) p50 **0.48 ms**, p90 1.2 ms, p99 1.8 ms; the module's `validate`
alone p50 0.31 ms, p99 1.9 ms. That is inside the Phase E target (p50 ≤ 2 ms,
p99 ≤ 10 ms) and faster than the spike's Chicory runtime-compiler figures.

**Deviations and findings.**
1. **MEMPOOL continues into LEDGER after the unelected-voter check.** Haskell's
   `failOnNonEmpty` records the failure and the transition still runs
   `LEDGER`, whose failures are appended; only the all-spent check
   short-circuits (`whenFailureFreeDefault`). `MempoolRule` follows Haskell.
2. **`ScriptPhaseEvaluator` signature.** It takes the original transaction
   bytes and `Map<Outpoint, UtxoEntry>` instead of ADR-056 §5's
   `Map<Outpoint, Utxo>`: CCL's `Utxo` keeps only a reference script's hash,
   and re-serialising the transaction can change the id in the script context.
3. **Undecodable transactions** are `ENGINE.DecodingFailure` (Haskell has no
   ledger constructor; the node rejects them when deserialising).
4. **Security group of pending parameter changes.** CCL's
   `ProtocolParamUpdate` has no fields for the Conway keys, so a decoded action
   cannot say whether a proposal touches Haskell's security group (keys 0, 1,
   2, 3, 4, 17, 21, 22, 30, 33; `Conway/PParams.hs`; 30 `govActionDeposit` and
   33 `minFeeRefScriptCostPerByte` are invisible to CCL). `ProposalState` now
   carries `paramUpdateKeys` (the keys of the `protocol_param_update` map, read
   from the original CBOR by `ProposalParamUpdateKeys`), and
   `any_in_security_group` is computed from them. `TxEffectsDeriver` fills them
   for proposals submitted through an overlay (mempool, same block), and the
   scenario loader fills them. **When they are unknown for a pending
   parameter change, the engine does not guess**: it rejects with
   `ENGINE.LedgerStateUnavailable` and never calls the module, because a wrong
   flag would reject a legitimate SPO vote (`GOV.DisallowedVoters`) or accept
   a disallowed one. **Consequence:** the canonical view does not populate the
   keys yet (TODO for step 1d in `CanonicalLedgerView.toProposalState`), so
   until it does, under `engine: amaru` every transaction validated while a
   parameter-change proposal is pending in canonical state fails closed.
5. **Rationals** are sent as the shortest decimal fraction of Yano's
   `BigDecimal` (`0.0577` → `577/10000`). Amaru compares parameters by value
   and never hashes them, so verdicts do not depend on the representation.
6. **Certificate pointers** (account delegations, DRep registration) are sent
   as `null`; Yano's view does not track them and Amaru's rules do not read
   them at the pinned tag.
7. **Slices are canonically ordered** (inputs by id and index, credentials by
   type then hash, pools and proposals by id), so a request depends only on
   the state.
8. **CCL decoding** is still needed next to Amaru (MEMPOOL inputs and voters,
   effects). A transaction Amaru accepts but CCL cannot decode fails closed.
9. **Scalus-mode gaps.** Phase-one mode does not report
   `MalformedScriptWitnesses` or `CollectErrors` (`NoCostModel`,
   `BadTranslation`, V3 non-disjoint inputs); it does report
   `MalformedReferenceScripts` for the transaction's own outputs (00151). The
   engine therefore calls the `ScriptPhaseEvaluator` whenever the transaction
   has redeemers **or** Plutus witness scripts, or a resolved input carries a
   reference script, not only for redeemers. The SPI's `Rejected` result
   carries these failures; the Scalus evaluator (step 1d) must produce them,
   and the scenario gate has to be re-run in `scalus` mode then.
10. **Input buffers are freed only after a successful call.** After a trap or
    an interrupt the instance is discarded; freeing in a `finally` would call
    into it again and hide the original exception.

**What step 1d must provide.**
- Configuration wiring: `yano.validation.engine=amaru`, shadow-engine
  registration, and `yano.validation.amaru.{phase2, pool-size, timeout-ms,
  max-abandoned, max-memory-pages}` mapped to `AmaruEngineConfig`
  (`pool-size: 0` resolved to the validation-thread count), with a clear
  startup failure when the module is not on the classpath.
- `AmaruNetworkParameters` from the loaded genesis files and the network's
  hard-fork history (see its Javadoc), and the health check and metric for
  `isHealthy()`.
- The Scalus `ScriptPhaseEvaluator` in `scalus-bridge`, including the
  phase-1 failures listed in deviation 9.
- The canonical view's `paramUpdateKeys` for pending parameter changes, from
  the stored action payload (`ProposalParamUpdateKeys.fromGovAction`;
  deviation 4). Until then `engine: amaru` fails closed whenever such a
  proposal is pending.

#### Step 1d results (2026-09-29)

- `AmaruEngineFactory` (`META-INF/services`) maps `yano.validation.amaru.*` to
  `AmaruEngineConfig` (`pool-size: 0` = the validation threads, plus two since
  Phase C for the rebuild worker and block selection: one admission
  lane plus the shadow workers); `phase2: scalus` uses the Scalus
  `ScriptPhaseEvaluator`, which now reports the deviation-9 checks and the
  forecast horizon.
- `AmaruNetworks` builds `AmaruNetworkParameters` from the genesis: Amaru's
  public-network era histories (mainnet, preprod, preview) and a single Conway
  era (after a Byron era when present) on devnets; resolved on first use.
- `yano.validation.engine=amaru` without the module stops startup with a clear
  message. Readiness (`validation-engine`) goes down when the Amaru admission
  engine turns unhealthy; `yano_validation_engine_healthy{engine}` reports every
  engine.
- The canonical view fills `ProposalState.paramUpdateKeys` from the stored
  action bytes (yaci keeps the Conway keys), so pending parameter changes no
  longer fail closed.
- A devnet test admits and rejects payments through the real module on a fresh
  devnet before its first boundary (genesis fallback); the scenario gate passes
  276/276 in `scalus` mode.

### Phase C — Overlays and runtime parity

- Run the ADR-056 Phase 6 devnet matrix with `engine: amaru`: dependent chains
  in the mempool and in one block, an epoch crossing with chains pending, and
  rollback while chains are pending.
- Gate: the same verdicts and effects as `engine: java-julc` (`java` before ADR-056 Phase 7c), and the Haskell
  follower stays in lock-step.

#### Phase C results (2026-09-29)

- **Matrix.** `AmaruDevnetParityTest` (amaru-validator, `-PwithAmaru=true`, module rebuilt with
  `scripts/build-wasm.sh`, sha256 `c43eeb37…`, passed as `-PamaruWasm=<absolute path>`) runs the ADR-056 Phase 6
  devnet matrix (`LedgerRulesDevnetMatrix`, see ADR-056 "Phase 6b results") twice on fresh in-process devnets:
  `engine: amaru` (`phase2: scalus`, pool size 2), then `engine: java` (today `java-julc`); both runs need
  `-PledgerRulesGate=true` (CI: the `amaru-wasm.yml` conformance job). Block selection uses the admission engine
  (rule `LEDGER`, origin `BLOCK_BUILD`), so under `engine: amaru` both admission and forging go through the module.
- **Same verdicts and effects.** The two runs' observation lists (53 lines: every admission verdict with its
  Haskell constructor, the mempool contents before forging, after the rollback and before the boundary, block
  placement, what was dropped at the boundary and why, the refund and the withdrawal) are identical except one
  recorded divergence (below). Every block the Amaru devnet produced (12–13 blocks, 423 transactions) was re-validated
  in `SYNC` mode (full validation, no `previous`) against the pre-block snapshot by a separate java engine instance,
  which is independent of the node, and by Amaru through the node's own admission engine instance (the same
  module instances that admitted and selected, with a different request): all valid, and the two engines derived
  equal `TxEffects` for every transaction. Drop reasons agree: `GOV.VotingOnExpiredGovAction` for the vote
  held across the boundary, `LEDGER.ConwayTreasuryValueMismatch` for the stale treasury value,
  `LEDGER.ConwayMempoolFailure` for the delegation whose parent was rolled back.
- **Recorded divergence.** A pool delegation from an unregistered credential: Haskell and the java engine reject
  with `DELEG.StakeKeyNotRegisteredDELEG` (`Conway/Rules/Deleg.hs`). Amaru's
  `DefaultValidationContext::delegate_pool` (eaf8ac3) only rejects a source unregistered earlier in the same
  transaction (`DiffBind::bind_left`), so the module accepts; the adapter cannot derive the effects of the absent
  account and fails closed with `ENGINE.AmaruEngineFailure`. The transaction is rejected either way, so no invalid
  transaction is admitted or forged. The Amaru scenario corpus has no such case. `AmaruKnownDivergencesTest` pins
  it; the parity test's allow-list maps that one line. Candidate fix for Phase D: check the delegator against the
  accounts slice in the wasm crate's context (or upstream), then re-run the scenario gate.
- **Pool sizing.** `pool-size: 0` used to resolve to the validation threads, but the mempool rebuild worker (6a)
  and the producer's block selection (6b) also call the admission engine; with every instance busy a selection
  candidate could wait up to the 2 s call timeout. `0` now resolves to the validation threads plus two
  (`AmaruEngineFactory.EXTRA_CALLERS`); an explicit size is used as given.
- **Budget under `engine: amaru`** with block production running (400 chained payments, three runs): admission
  p50 1.2–1.4 ms, p99 2.1–2.9 ms, max 2.5–138 ms; last block selection 45–278 ms and last rebuild up to 331 ms at
  a few hundred mempool transactions (the module call dominates, as recorded for Phase 6a).
- **Haskell follower** with `engine: amaru`: **PASS** (2026-09-29 14:18–14:26). The same harness and workload as
  ADR-056 "Phase 6b results", with a `-PwithAmaru=true -PamaruWasm=<module>` JVM distribution and
  `-Dyano.validation.engine=amaru`: the dependent chains, the `currentTreasuryValue` transaction and the refund
  withdrawal were admitted and forged by the Amaru engine (the workload built the same 16 transactions, byte for
  byte, as in the java run; block placement differs only with submission timing), and the Haskell node followed for 4 epochs, 2,362 blocks, tip hash
  matching at every checkpoint, no Haskell error line. The run shared the machine with a regression build, so Yano
  missed more slots (202 of 2,564 against 69 in the java run); no block was rejected.

### Phase D — Oracle integration

- Add the differential test harness used by ADR-056 Phases 2–7, the shadow
  engine with disagreement dumps, and a CI job that runs the differential
  whenever the wasm artifact is available.
- Gate: every divergence is either fixed or recorded with its Haskell reference.
- **Status (2026-09-30): done.** The differential is ADR-056's conformance harness, with
  this module as the reference engine (`BaselineEngines.amaru()`: scenarios, mutation
  matrix, blueprint vectors), and `amaru` as an admission shadow or shadow-sync engine,
  with dumps (`ShadowValidationRunner`, ADR-056 Phase 7a). The `amaru-wasm.yml`
  `conformance` job runs it on every build of the module. The recorded divergences, none
  fixed (full list in `amaru-validator/README.md`, "Known divergences and limits"):
  - **Accepted by Amaru, rejected by Haskell**, both on the conformance mutants: a PlutusV3
    script followed by one extra byte (`UTXOW.MalformedScriptWitnesses`), and a VRF key hash
    reused by another pool at PV 11 (`POOL.VRFKeyHashAlreadyRegistered`).
  - **Rejected by both, under a different name or as an engine failure:**
    - scenario 00280 (ADR-056 Phase 3a);
    - a delegation from an unregistered credential (Phase C, `AmaruKnownDivergencesTest`);
    - an update of an unregistered DRep;
    - a pool metadata hash that is not 32 bytes;
    - two constructor-name differences.
  - One difference in how failures are reported: Amaru stops at the first failure,
    while Haskell lists all of them.

  The two false acceptances make `amaru` unsuitable as the only admission engine for those
  inputs. As a shadow engine or oracle they only show up as recorded disagreements.

### Phase E — Native image, performance, docs

- Build and run the native image with `-PwithAmaru=true`.
- Benchmark per-transaction latency (target: p50 ≤ 2 ms, p99 ≤ 10 ms on the
  scenario corpus, JVM, warm), plus startup and memory per instance.
- Write the developer guide (build, select, shadow, upgrade runbook).

#### Phase E results: native image (2026-09-29, ADR-056 Phase 7c)

§3 promises native support, and the acceptance criteria require "the native image works". It does:

- **Build.** `./gradlew :app:yanoNativeDistZip -Dquarkus.native.enabled=true -Dquarkus.package.jar.enabled=false
  -PskipSigning=true -PwithAmaru=true -PamaruWasm=<module>` from the merge candidate `b02ba6fe1` (module
  `c43eeb37…`, the Phase C build; native binary `9482c3f8…`, jar `f29e2b7d…`). Oracle GraalVM
  25.3.4.1, G1, macOS arm64. The image-build report shows 7.48 MiB of code in `org.yanoproject.ledger.amaru.generated`,
  the build-time AOT classes. The runtime compiler is not on the classpath (Phase B), so nothing compiles wasm at run
  time.
- **One fix was needed**, shared with the other engines: `AmaruEngineFactory` had no reflection registration, so
  the native `ServiceLoader` could not construct it. `amaru-validator`'s `META-INF/native-image` now has a
  `reflect-config.json` for it, and its `resource-config.json` names the service file next to the `.meta` resource.
  In the same step, a provider that cannot be loaded stops startup instead of leaving the node without validation
  (ADR-056 "Phase 7c results", gap 2).
- **Verified in native**, against the JVM build of the same sources, by `JAR=… NATIVE=…
  qa/harness/ledger-rules-native-parity.sh amaru "11 10"`:
  - `engine: amaru` admission (`phase2: scalus`) and block selection;
  - `java-julc` as an admission shadow, with dumps;
  - shadow sync with `java-julc,amaru` on the producer and on a follower;
  - the Amaru instance pool (WASI host, `.meta` resource, AOT machine);
  - health and metrics;
  - a clean shutdown.

  Every verdict and Amaru failure message was identical between JVM and native, at PV 10 and PV 11
  (40 observations per run: 22 accepted, 18 rejected; `amaru` and `java-julc` shadow sync 21 of 21 agreed each on
  the producer and the follower). This includes the Phase C divergence (`ENGINE` 1 against the `java-julc`
  shadow), which is identical in both. Amaru validates Conway from PV 10 only, so PV 9 is not run.
- The latency benchmark and the startup and per-instance memory figures are in "Phase E results: benchmark" below.

#### Phase E results: developer guide (2026-09-30)

[`amaru-validator/README.md`](../amaru-validator/README.md) covers:

- the four roles: admission engine, admission shadow, shadow-sync engine, test oracle;
- building the module: toolchains, `AMARU_VERSION`, and the CI artifact;
- building Yano with it: `-PwithAmaru=true` and the three `prepareWasm` sources, plus the JVM, native and test
  commands;
- the runtime properties (`yano.validation.engine=amaru`, `yano.validation.amaru.*`), `shadow-engines` and
  `shadow-sync-engines`, pool sizing, health and failures, rollback, and native-image notes;
- the known divergences and limits;
- the upgrade steps on the Yano side: the crate version check (`AmaruReferenceEngine.verifyModule`), `abi_version`,
  the gates to re-run, and the release pin.

The Rust detail and the full upgrade runbook stay in [`amaru-validator-wasm/README.md`](../amaru-validator-wasm/README.md),
and the guide links to it. Every property named in the guide was checked against `YanoPropertyKeys.Validation`,
`application.yml` or the module code, and every Gradle property against the build files.

#### Phase E: benchmark procedure

What existed before:

- `AmaruScenarioGateTest` prints `amaru`-only latency (the Phase B figures).
- `EndiveRuntimeTest.instantiationCost` gives warm instantiation.
- The conformance baseline's "ms / scenario (one pass)" is a single cold pass. For the Java engines it includes
  creating an engine per case.

None of these compares the engines warm on one corpus, and none measures cold start or memory. Phase E therefore
adds two opt-in tests, both skipped unless `-PengineBenchmark=true` is passed. The `amaruLatency` Gradle property,
which nothing read, is removed.

| Test | Measures |
|---|---|
| `ledger-conformance` `EngineLatencyBenchmarkTest` | Warm per-transaction latency of `java-julc`, `java-scalus` and `amaru` on two corpora. The first is the 275 Amaru scenarios at PV ≥ 10 (00203, PV 9, is left out because Amaru refuses it without validating). The second is the real preprod and preview transactions vendored in `PublicNetworkTransactions` (the 14 `PHASE2_CASES` and `PREPROD_INDEFINITE_ASSET_MAP_OUTPUT`), replayed with `bundle.replayRequest()` against the ledger state each was validated on. `amaru` runs only their PV ≥ 10 bundles, once with `phase2 = full` and once with `phase2 = scalus` (the node default, `yano.validation.amaru.phase2`). A bundle holds the Java engine's reads only, so for `amaru` the whole slices it always ships and these transactions never touch (committee, proposals, guardrail, treasury) are answered as empty when not recorded; the valid counts show every verdict is still the chain's. Each engine is created once per network and constants and then reused, as the node does. One sample is one `validate` call with rule `LEDGER` and origin `SYNC`. The Java engines run on the calling thread. `amaru` runs through its pool's worker thread with `phase2 = full`, which covers request building, both module calls, Amaru's Plutus and the effects. By default the scenarios get 2 warm-up passes (the first is reported as the cold pass) and 5 sampled passes, so 1,375 samples per engine; the public-network corpus gets 20 times as many passes of each (40 and 100), as it has only 15 transactions. It reports mean, p50, p90, p99 and max (nearest rank), and the valid and crash counts as a sanity check (114 valid). |
| `amaru-validator` `AmaruFootprintBenchmarkTest` | In a fresh test JVM: the cold parse of the `.meta` module, the first instance (AOT machine classes, instantiation, `_initialize`), the first `validate`, and the mean of the first corpus pass. Then the metaspace and heap retained after the first instance (paid once per JVM), warm instantiation p50 and max (30 samples), and the retained heap per instance, fresh and after one corpus pass, with 8 instances held. The 256 MiB worker stacks are reserved address space and are not counted. |

Preconditions:

- The machine is otherwise idle: no Yano node, no mainnet sync, no other build.
- The module is the current one: crate `0.1.1`, `c43eeb37…` today. Record its sha256.
- The corpus is Amaru at the pinned tag (`v10.11.20260925`, commit `eaf8ac3`). Cargo's own checkout works.

The command runs both tests in one Gradle invocation. Each test task forks its own JVM, so the Amaru module
loads cold:

```sh
W=$PWD/amaru-validator-wasm/target/wasm-out/amaru_validator.wasm       # or the amaru-wasm.yml artifact
S=$HOME/.cargo/git/checkouts/amaru-fe100b3fda4676be/eaf8ac3            # or a clone of pragma-org/amaru at the tag
shasum -a 256 "$W"
./gradlew --offline -PwithAmaru=true -PamaruWasm="$W" -PamaruScenariosDir="$S" -PengineBenchmark=true \
  :amaru-validator:test --tests '*AmaruFootprintBenchmarkTest' \
  :ledger-conformance:test --tests '*EngineLatencyBenchmarkTest'
```

- **Results.** The tables are written to `amaru-validator/build/benchmark/amaru-footprint.md` and
  `ledger-conformance/build/conformance/engine-latency.md`, with the JVM, OS and processor count in each header.
- **Options.** `-PengineBenchmarkPasses=<n>` (default 5), `-PengineBenchmarkWarmup=<n>` (default 2),
  `-PengineBenchmarkNetworkRepeat=<n>` (default 20, the pass multiplier of the public-network corpus) and
  `-PengineBenchmarkInstances=<n>` (default 8). Both test tasks always re-run with `-PengineBenchmark=true`.
- **Scope.** Admission runs `amaru` with `phase2: scalus`; the benchmark measures that mode on the public-network
  corpus only. Under block production it is covered by the Phase C budget figures. Native-image startup is not
  covered; the node logs `Amaru validator ready` when the engine has been created.

To record, as "Phase E results: benchmark":

- the machine, the JDK, the module sha256 and the commit;
- the two tables;
- `amaru` p50 and p99 against the target (p50 ≤ 2 ms, p99 ≤ 10 ms).

On 2026-09-30 both tests were run once, with 1 pass and 1 instance, only to check that they work. The mainnet sync
was loading the machine, so those numbers are not recorded.

#### Phase E results: benchmark (2026-09-30)

**Setup.** Apple M4 Max, 16 cores, 128 GiB, macOS 26.0.1; OpenJDK 25.0.2+12-LTS (the test JVM: G1, max heap 4 GiB).
Module `amaru_validator.wasm` sha256 `c43eeb3738cdce05caf3a897243485b4bca534a03272102cfab3579010c2c3e3`, crate
`0.1.1`, Amaru `v10.11.20260925` (`eaf8ac3`), scenarios from the same tag. Julc: the combined local build
`0.1.0-pre18-yano-local2` (released `0.1.0-pre17` plus bloxbean/julc PRs #219, #221, #227 and #228), the build that
passed the ADR-056 Phase 7c public-network gates, applied with a Gradle init script (`-I`); one more run on the
released `0.1.0-pre17`. Sources: this commit (parent `1ddedb6b3`). Default passes (scenarios 2 warm-up + 5 sampled;
public networks 40 + 100). No Yano node and no other build ran; an IDE and a browser were open (1-minute load average
4–7 on 16 cores). All engines are single-threaded per call, so this is one validation at a time, JVM, warm.

**Latency** (ms per `validate` call; run 3 of three runs with the local Julc, the recorded table):

| Corpus | Engine | cases | samples | p50 | p90 | p99 | max | valid |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| scenarios | `java-julc` | 275 | 1,375 | 0.238 | 0.367 | 0.698 | 4.7 | 114/275 |
| scenarios | `java-scalus` | 275 | 1,375 | 0.220 | 0.482 | 1.874 | 11.7 | 114/275 |
| scenarios | `amaru` | 275 | 1,375 | **0.711** | 1.619 | **3.349** | 36.8 | 114/275 |
| preprod | `java-julc` | 9 | 900 | 1.201 | 2.499 | 3.206 | 5.8 | 9/9 |
| preprod | `java-scalus` | 9 | 900 | 1.672 | 4.248 | 4.722 | 5.1 | 9/9 |
| preprod | `amaru` | 6 | 600 | **19.5** | 285.3 | **296.6** | 309.6 | 6/6 |
| preprod | `amaru`, `phase2: scalus` | 6 | 600 | 3.039 | 6.110 | 6.611 | 7.3 | 6/6 |
| preview | `java-julc` | 6 | 600 | 1.010 | 2.270 | 2.550 | 3.4 | 6/6 |
| preview | `java-scalus` | 6 | 600 | 2.285 | 4.736 | 5.761 | 7.3 | 6/6 |
| preview | `amaru` | 5 | 500 | **47.0** | 186.2 | **188.0** | 189.8 | 5/5 |
| preview | `amaru`, `phase2: scalus` | 5 | 500 | 2.842 | 6.115 | 6.893 | 8.8 | 5/5 |

- **Run to run.** Across the three local-Julc runs, scenario `amaru` p50 was 0.56–0.71 ms and p99 2.3–3.3 ms;
  `java-julc` p50 0.22–0.24 ms and p99 0.54–0.70 ms. The public-network rows of runs 2 and 3 (run 1 had no
  `phase2: scalus` row) differ by 15 % or less, except single-sample maxima.
- **The public-network corpus** is 15 real transactions: 9 preprod and 6 preview, of which 6 and 5 are PV ≥ 10.
  Every one was once an engine finding, and 13 run Plutus scripts (1 to 4 redeemers). It is a real-network
  Plutus-heavy sample, not typical traffic, and with this few distinct transactions p90 and p99 are simply the
  slowest one or two.
- **Where `amaru`'s time goes.** Per-transaction medians of `phase2: full` (a separate diagnostic run): preprod
  `031e36a7…` 284 ms, `b35f5500…` 121 ms, `2edd684f…` 68 ms, `22434324…` 16 ms, `96ae78f7…` 4.0 ms,
  `aee75c1c…` 1.4 ms; preview `f896a7ee…` 187 ms, `aec876ad…` 62 ms, `89d3a627…` and `2c3657d0…` 46 ms,
  `b0e24e31…` 5.3 ms. The two without redeemers (`96ae78f7…`, `aee75c1c…`) are the 4.0 and 1.4 ms ones. The same
  transactions with `phase2: scalus` take 1.2–7.3 ms, so the difference is Amaru's own script evaluation inside the
  module. The scenario scripts are small test scripts, which is why the scenario corpus did not show it. Why
  Amaru's evaluator is this slow under Endive has not been investigated.
- **Released Julc `0.1.0-pre17`.** The timings are the same within run-to-run noise (scenario `java-julc` p50
  0.224 ms, p99 0.559 ms; preprod p50 1.17, p99 2.66; preview p50 0.88, p99 2.45). The verdicts differ as
  expected: `java-julc` accepts 8/9 preprod and 3/6 preview transactions, the four known Julc deviations of
  `JulcPublicNetworkTest` that the local build fixes.

**Amaru cold start and memory** (`AmaruFootprintBenchmarkTest`, fresh test JVM, 8 instances held; three runs, the
spread is under 5 % except warm instantiation):

| Figure | Value |
|---|---:|
| Parse the `.meta` module | 150–153 ms |
| First instance (AOT machine classes, instantiation, `_initialize`) | 82–85 ms |
| First `validate` on it | 14.1–14.7 ms |
| First pass over the 276 scenarios, mean per request | 1.7 ms |
| Metaspace after the first instance, once per JVM | 11.7 MiB |
| Retained heap after the first instance, once per JVM | 35.7 MiB |
| Warm instantiation p50 / max (30 samples) | 0.79–1.09 / 2.0 ms |
| Retained heap per instance, fresh | 9.4 MiB |
| Retained heap per instance, after one corpus pass | 14.6 MiB |

So the engine is ready about 250 ms after the module is first touched, plus about 15 ms for the first call. The
once-per-JVM figures include the first instance itself (which had run the corpus); each further instance adds
9.4 MiB fresh and 14.6 MiB once it has run. The 256 MiB worker stacks are reserved address space and are not
counted.

**Against the target** (p50 ≤ 2 ms, p99 ≤ 10 ms on the scenario corpus, JVM, warm):

- **Met on the scenario corpus**, the corpus the target was set on: `amaru` p50 0.56–0.71 ms and p99 2.3–3.3 ms.
  `amaru` is 2.5–3 times slower than the Java engines there, and has the largest maximum (37–42 ms, a single call).
- **Not met on real Plutus transactions with `phase2: full`**: p50 17–19 ms (preprod) and 46–47 ms (preview), p99
  287–297 ms and 188 ms. These are single-transaction costs; a block with several such transactions takes seconds in
  `amaru`, against a few milliseconds per transaction in the Java engines.
- **`phase2: scalus`**, the node default, stays at p50 2.8–3.4 ms and p99 6.5–7.5 ms on the same sample: the p99
  is inside the target, the p50 is 0.8–1.4 ms over it and 0.6–1.5 ms above `java-scalus`.
- **Consequences.** In the node, `amaru` (admission engine, admission shadow or shadow-sync engine) should keep the
  default `phase2: scalus`. `phase2: amaru` (full) is the test-oracle mode: fine on devnets and on the scenario
  corpus, but on public networks with Plutus-heavy blocks it would cost tens to hundreds of milliseconds per script
  transaction and fall behind the Java engines. Speeding up Amaru's script evaluation in the module would be the fix;
  it is not part of this ADR.

## Acceptance criteria

Status on 2026-09-30.

| Criterion | Status | Evidence |
|---|---|---|
| The default Yano build, tests and distributions are byte-for-byte unaffected when `-PwithAmaru` is not set. | **Met by exclusion** | Without the flag, `settings.gradle` leaves the module out. It is not in the BOM, it is excluded from central deployment, and the publication scope check still reports the default 24 projects (Phase B). No byte comparison of the distributions was run. |
| With the module, all 276 Amaru scenarios pass through the Java engine path in `full` mode. | **Met** | 275 with the expected verdict, rule and constructor; 00203 (PV 9) refused with `ENGINE.EraNotSupported` by invariant 6 (Phase B). |
| With the module, the Phase C devnet matrix passes with `engine: amaru`. | **Met** | `AmaruDevnetParityTest`, with one recorded divergence; Haskell follower in lock-step for 2,362 blocks (Phase C). |
| With the module, a trap or timeout rejects one transaction and never destabilises the node. | **Met** | A real trap and a timeout each reject with `AmaruEngineFailure`, and the next call succeeds; stuck workers turn the engine unhealthy and it fails closed (Phase B). |
| With the module, the native image works. | **Met** | JVM = native at PV 10 and 11 for admission, block selection and shadow sync (Phase E results). |
| The CI workflow reproduces the module from the pinned tag and toolchain, and publishes it with its sha256. | **Met; first publication pending** | `amaru-wasm.yml` builds the module and its bundle with the sha256 on every relevant push. Its `release` job attaches them to a Yano release tag; no release carries the module yet. |
| ADR-056's differential gate runs against this module. | **Met** | Phase D status above. |
| Phase E: latency benchmark on the scenario corpus, startup and per-instance memory, developer guide. | **Met on the scenario corpus**; slower on real Plutus transactions with `phase2: full` | Scenarios: `amaru` p50 0.56–0.71 ms, p99 2.3–3.3 ms (target 2 and 10). Real preprod/preview Plutus sample: `phase2: full` p50 17–47 ms, p99 188–297 ms; `phase2: scalus` p50 2.8–3.4 ms, p99 6.5–7.5 ms. Cold start about 250 ms, 9.4–14.6 MiB heap per instance. Developer guide written. ("Phase E results: benchmark".) |

## Alternatives considered

- **Panama FFM to a native Amaru library.** Native speed, but it needs one
  library per platform, and a Rust `panic=abort` or segfault kills the JVM
  (measured). It would need `panic=unwind` plus `catch_unwind` on every export.
  It is kept as a fallback if wasm speed ever becomes a constraint.
- **Endive runtime compiler instead of AOT.** It is simpler to wire, but costs
  about 2 s of compilation at startup and doesn't work in native images.
- **Wait for Pragma to publish a wasm artifact.** That would be ideal long-term,
  and Yano will propose it (the same interface, owned upstream). But it would
  block this work on another team's schedule. If upstream publishes one, Phase A
  shrinks to downloading and verifying their asset.
- **GraalWasm.** It is optimised only on the GraalVM JDK, and runs as an
  interpreter on OpenJDK.
- **wasmtime-java and wasmer-java.** Both are unmaintained.

## Consequences

### Positive

- Developers can opt into near-complete, Haskell-checked Conway admission rules
  now.
- ADR-056 gets an independent, executable oracle on fixtures and on real
  traffic.
- It is sandboxed and pure JVM, and works in native images.

### Negative

- Yano carries a Rust crate, a nightly toolchain pin and a wasi-sdk CI step, and
  must track Amaru's API changes on each bump.
- It runs about 15–20× slower than native Amaru, which is fine for admission
  (about 1 ms per transaction) but not for bulk sync validation.
- Differences in state mapping between Yano's model and Amaru's (DRep
  `valid_until`, committee candidates, enacted roots) are a new class of bugs.
  The scenario and differential gates catch them only on the paths they
  exercise.

## Risks and mitigations

| Risk | Mitigation |
|---|---|
| Amaru API churn breaks the wrapper on a bump | Pinned tag and lockfile; wrapper-only adaptation; interface version separates our contract from theirs; CI scenario gate |
| Yano-to-Amaru state mapping mismatch (DRep expiry, committee, roots) | Full committee and proposals always shipped; scenario gate; ADR-056 differential; shadow-dump bundles |
| Looping guest pins CPU | Dedicated threads, abandoned-thread cap, engine goes unhealthy and fails closed |
| Linux/wasi-sdk build differs from the macOS/zig spike | Phase A gate is a Linux CI build that runs all scenarios |
| Optional module leaks into default artifacts | `-PwithAmaru` inclusion, central-deployment exclusion, BOM exclusion, acceptance check |

## Rollback plan

Set `yano.validation.engine` to `java-julc`, `java-scalus` or `scalus` and remove the Amaru engines from
`shadow-engines`. Nothing else depends on the module. Removing
`amaru-validator-wasm/`, the `amaru-validator` module and `amaru-wasm.yml`
reverts this ADR completely. No persisted state is involved.

## Open questions

1. *Resolved in Phase A: yes, `phase_one::execute` is public at
   `v10.11.20260925`, and `mode = phase_one` calls it. However, Amaru runs
   some phase-1 checks in its phase-two preparation: it decodes script
   witnesses (`MalformedScriptWitnesses`), checks cost models and builds the
   script context (`CollectErrors`: NoCostModel, BadTranslation, including the
   Plutus V3 disjoint-reference-inputs check). Phase-one mode misses these, so
   the `phase2: scalus` path must report them
   (`amaru-validator-wasm/INTERFACE.md`).*
   Is Amaru's phase-one entry point (`rules::transaction::phase_one::execute`)
   public at the pinned tag? If not, `phase2: scalus` needs a small upstream
   visibility change. Until then it runs `full` and discards Amaru's phase-2
   verdict, which duplicates Plutus work.
2. *Resolved in Phase A at the interface level. The request carries the era
   history and global parameters explicitly, and a test validates a Plutus V3
   spend under magic 42, 500-slot epochs and 1 s slots. A live devnet bundle
   remains Phase C.*
   Custom devnet eras: `EraHistory::new` is public at `d72e9b5`, so arbitrary
   era histories for custom devnets (short epochs, 1000 ms slots) are likely
   supported. Phase A confirms it with a devnet bundle.
3. *Resolved in Phase B: interruption yes, fuel metering no.* Endive 1.1.0
   emits a `Thread.isInterrupted()` check before every call and on every
   backward branch of AOT-compiled code (`Compiler`/`Emitters`,
   `Shaded.checkInterruption`), and the interpreter checks it too
   (`InterpreterMachine.checkInterruption`); an interrupted guest throws
   `WasmInterruptedException`. There is no fuel or instruction metering. The
   engine therefore interrupts a timed-out worker and counts it as abandoned
   only if it has not stopped 250 ms later (possible only outside wasm code).
   `EndiveRuntimeTest.anInterruptStopsAGuestMidCall` proves it on the real
   module: an interrupt raised 0.05–2 ms into `validate` of the heaviest
   Plutus scenario stops the guest inside the export, from deeper compiled
   frames than the entry check.
4. Should the `.wasm` also be published as a Maven artifact
   (`org.yanoproject:yano-amaru-validator-wasm`) as well as a GitHub release
   asset?

## Decisions (accepted 2026-09-28)

1. Yano owns `amaru-validator-wasm/`, with a git dependency on a pinned Amaru
   tag and a pinned nightly toolchain.
2. **Endive build-time AOT** is the only execution mode. The runtime compiler
   is disabled.
3. `engine: amaru` defaults to `phase2: scalus`. Oracle runs use `full`. Superseded by decision 6: these
   are now the engines `amaru-scalus` and `amaru`.
4. Timeouts and abandoned threads: the engine fails closed and turns unhealthy
   once the cap is reached.
5. Yano proposes the interface upstream to Pragma as a published wasm artifact.
   Yano's own build does not depend on that.

## Decision (2026-10-01): engine ids name the rules and the evaluator

6. Amaru engine ids follow `<rules>-<evaluator>`, as `java-julc` and `java-scalus` do. A single name means one
   implementation runs both phases (like the legacy `scalus`). `amaru-scalus` is Amaru phase one with the node's
   Scalus `ScriptPhaseEvaluator` (formerly `engine: amaru` with `phase2: scalus`); `amaru` is Amaru for both phases
   (formerly `phase2: amaru` or `full`). The `yano.validation.amaru.phase2` key is removed; the other
   `yano.validation.amaru.*` settings apply to both engines. Phase results above use the names of their time. A
   future `amaru-julc` is another factory on the same `ScriptPhaseEvaluator` SPI.
