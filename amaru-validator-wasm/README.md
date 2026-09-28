# amaru-validator-wasm

This crate compiles Pragma's [Amaru](https://github.com/pragma-org/amaru) Conway transaction
validator into a sandboxed `wasm32-wasip1` module. The module has a small, versioned interface
owned by Yano (ADR-057, `adr/057-optional-amaru-wasm-transaction-validator.md`).

The crate sits outside the Gradle build, and nothing in the default Yano build needs Rust. The
optional Java module that loads the `.wasm` with Endive is `../amaru-validator` (ADR-057 Phase B), built
only with `-PwithAmaru=true`:

```sh
# from the repository root: build the module, compile it to JVM bytecode, run the engine on the scenarios
AMARU_SCENARIOS_DIR=<amaru clone at the pinned tag> \
  ./gradlew -PwithAmaru=true -PamaruBuild=local :amaru-validator:test
# or use an already built module
./gradlew -PwithAmaru=true -PamaruWasm=/path/to/amaru_validator.wasm :amaru-validator:test
```

Its golden tests compare the Java request encoder with the requests `cargo test` dumps into
`target/scenario-requests` (see "Replay every scenario" below).

| File | What it is |
|---|---|
| `Cargo.toml`, `Cargo.lock` | Git dependencies on `amaru-kernel`, `amaru-ledger` and `amaru-plutus` at the pinned tag. The lockfile pins the exact commit. |
| `rust-toolchain.toml` | The nightly that Amaru pins at that tag, plus the `wasm32-wasip1` target. |
| `AMARU_VERSION` | `tag=`, `commit=` and `toolchain=` lines. Embedded in the module and returned by `amaru_version`. |
| `INTERFACE.md` | Interface v1: exports, CBOR request and response schemas (CDDL), and absence semantics. |
| `src/interface.rs` | The reference codec for the v1 documents. |
| `src/engine.rs` | `required_keys` and `validate`, built on Amaru's public API. |
| `src/failure.rs` | Amaru errors mapped to the Haskell rule and constructor at ADR-056's pinned `cardano-ledger` revision. Every variant is mapped explicitly. |
| `tests/amaru_scenarios.rs` | Fixture-equivalence gate: all 276 Amaru scenarios in both modes (phase, rule and constructor), `required_keys` sufficiency, and arena growth. |
| `scripts/build-wasm.sh` | Release build, `wasm-opt -O3`, the import and feature check, and sha256. |
| `scripts/wasm_check.py` | Enforces WASI p1 imports only, the v1 exports, and no threads/atomics/SIMD/EH/tail-call/GC. |
| `scripts/run_wasm_scenarios.py` | Replays every `validate` and `required_keys` call through the built `.wasm` (wasmtime). Its responses must equal the native ones byte for byte. |
| `scripts/requirements-replay.txt` | Hash-pinned `wasmtime` for the replay. |
| `scripts/bundle.sh` | Builds the publishable `amaru-validator-wasm-<version>.tar.gz` plus its `.sha256`. |
| `deny.toml` | `cargo deny` licence and source policy for the wasm dependency graph. |
| `about.toml`, `about.hbs` | `cargo-about` configuration and template for `THIRD-PARTY-LICENSES.txt`. |
| `NOTICE`, `LICENSE-AMARU` | Attribution (Apache-2.0 for Amaru). |

## Build

Requirements:

- **rustup.** The toolchain and target install automatically from `rust-toolchain.toml`.
- **A C compiler that targets wasm32.** `blst` and `secp256k1-sys` contain C code, and Apple clang
  cannot target wasm. Use one of:
  - [wasi-sdk](https://github.com/WebAssembly/wasi-sdk) (CI uses `wasi-sdk-34`), selected with
    `WASI_SDK_PATH`;
  - [zig](https://ziglang.org) (tested with 0.13.0), found on `PATH` or through `ZIG`.
- **`wasm-opt`** from binaryen (CI uses `version_132`), and `python3`.
- For bundling only: [cargo-about](https://github.com/EmbarkStudios/cargo-about) 0.9.2.

```sh
cd amaru-validator-wasm
cargo test --locked                  # native: unit tests + all Amaru scenarios (see below)
WASI_SDK_PATH=/opt/wasi-sdk scripts/build-wasm.sh
#   or: ZIG=/path/to/zig scripts/build-wasm.sh
# -> target/wasm-out/amaru_validator.wasm and amaru_validator.wasm.sha256
```

Replay every scenario through the built module:

```sh
AMARU_DUMP_REQUESTS_DIR=target/scenario-requests cargo test --locked --test amaru_scenarios
pip install --require-hashes -r scripts/requirements-replay.txt
python3 scripts/run_wasm_scenarios.py target/wasm-out/amaru_validator.wasm target/scenario-requests
```

Package it for publication. The bundle holds the module, its sha256, `AMARU_VERSION`,
`NOTICE`, `LICENSE-AMARU`, `INTERFACE.md` and a generated `THIRD-PARTY-LICENSES.txt`:

```sh
CARGO_ABOUT=/path/to/cargo-about scripts/bundle.sh 0.1.0
# -> target/publish/amaru-validator-wasm-0.1.0.tar.gz (+ .sha256), amaru_validator.wasm (+ .sha256)
```

The scenarios come from Amaru's repository, in `crates/amaru-ledger/tests/data/transaction`. The
test looks for them in `$AMARU_FIXTURES_DIR` first (CI sets it to a shallow clone of the pinned
tag), then in Cargo's own git checkout of the pinned commit. If neither has them, the test fails;
it never skips.

CI runs all of this in `.github/workflows/amaru-wasm.yml`, on Ubuntu with wasi-sdk. It publishes the
bundle and the standalone `.wasm`, each with a `.sha256`, as a workflow artifact. On `v*` tags it
waits for `release-dist.yml` to create the GitHub release, then attaches them to it.

## Interface

`INTERFACE.md` is the contract. In short:
1. `required_keys(tx, env)` returns what Amaru's `prepare_transaction` needs.
2. The host resolves those keys through its `LedgerView`. A key confirmed absent is omitted, and
   unavailable state means `validate` is not called.
3. The host sends one self-contained `validate` request. The request always carries the full
   committee and all active proposals.
4. The response is `ok`, `invalid {phase, rule, constructor, amaru_error, detail}`, or
   `error {message}`.

`mode = phase_one` runs Amaru's public `phase_one::execute` only. `mode = full` runs
`validate_transaction`, which adds Amaru's Plutus evaluation.

## Upgrading Amaru

This is the ADR-057 §4 runbook, made concrete:

1. **Pick the tag.** Run `git ls-remote https://github.com/pragma-org/amaru 'refs/tags/<tag>'` and
   record the commit.
2. **Bump the version files.** Update the tag in `Cargo.toml` (every Amaru dependency, including
   the dev-dependency) and in `AMARU_VERSION` (`tag`, `commit`, `toolchain`).
3. **Re-check the workspace overrides.**
   - Copy the tag's `rust-toolchain.toml` channel.
   - Check its root `Cargo.toml` for `[patch.*]` sections. Cargo ignores a dependency's patches,
     so copy any it has into ours. There are none at `v10.11.20260925`.
   - `crates/vendor/*` path dependencies resolve inside the git checkout and need nothing.
4. **Re-seed the lockfile.** Copy Amaru's `Cargo.lock` from the tag over ours. Run one
   `cargo build` without `--locked`, then use `--locked` for everything else. Diff the lockfile:
   no crate should change version from Amaru's lock.
5. **Adapt the wrapper.** Build, and follow the compile errors:
   - `validate_transaction`, `phase_one::execute`, `DefaultValidationContext::new` and
     `prepare_transaction` in `src/engine.rs`;
   - the kernel types in `src/interface.rs`;
   - the error enums in `src/failure.rs`. These matches have no catch-all, so a new Amaru variant
     is a compile error until it is mapped.

   Bump `ABI_VERSION`, and update `INTERFACE.md`, only if the documents change.
6. **Run the gates.**
   - `cargo test --locked`. The scenario count is pinned (`EXPECTED_SCENARIOS`), so a changed
     corpus is noticed.
   - `cargo deny --locked check licenses sources`. If the licence set changes, update `about.toml`
     to match.
   - `scripts/build-wasm.sh`, then the wasmtime replay, then `scripts/bundle.sh`.
   - The corpus-to-Haskell alias table in the test and `src/failure.rs`: check them against
     ADR-056's pinned revision, and against any new corpus predicates.
   - After that, ADR-056's differential suite (Phase D).
7. **Release.** The release notes name the Amaru tag and commit.
