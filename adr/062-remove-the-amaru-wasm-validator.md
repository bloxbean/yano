# ADR-062: Remove the Amaru WebAssembly Validator

**Status:** Proposed. Supersedes ADR-057.
**Date:** 2026-11-07
**Authors:** Claude Code (Opus 5.5)
**Related:** ADR-056 (Conway Java ledger rules, engine selection, `ledger-conformance`), ADR-057 (the Amaru validator),
ADR-060 (node-diversity reports), PR #240 (the last Amaru bump, which this change builds on)

## Context

ADR-057 added Amaru's ledger rules, compiled to WebAssembly and run on Endive 1.1.0, as an optional validation engine:
`amaru` (Amaru for both phases) and `amaru-scalus` (Amaru phase one, Plutus on Scalus). Only builds run with
`-PwithAmaru=true` contain it: the default build, distributions, Docker images and Maven publications never did,
while an opt-in application build includes it and the `amaru-wasm.yml` workflow attaches the module to a GitHub
release. It had four roles: admission engine, admission shadow, shadow-sync engine and test oracle (ADR-057 Phase D).

Since then:

- **The conformance evidence does not need the engine.** Two Haskell-cross-checked corpora in Amaru's fixture format
  test the Java engines, through `ledger-conformance`, without the module:
  - the 368 Amaru scenarios, read from an Amaru checkout (`-PamaruScenariosDir` / `AMARU_SCENARIOS_DIR`) by
    `AmaruScenarioLoader` and `ScenarioCases`, and gated by `JavaEnginePhase3/4/5GateTest`;
  - the 290 ledger-rules fixtures of the node-diversity report (ADR-060), read from pragma's repository
    (`-PnodeDiversityCorpora`, pinned by `node-diversity/corpora.env`), which only ever ran the Java engine
    (`LedgerRulesDiversityReportTest`).
- **As an oracle it found nothing in the Java rules.** Every disagreement ADR-057 Phase D recorded (2026-09-30) was
  Amaru's or a naming difference: two false acceptances that Haskell rejects (a Plutus V3 script followed by an extra
  byte, and a VRF key hash reused at PV 11), and six cases rejected by both under another name or as an engine failure.
  A disagreement still had to be settled against Haskell, the source of truth.
- **It cannot be the only admission engine,** because of those false acceptances (ADR-057).
- **It does not help with Dijkstra.** Amaru validates Conway from PV 10 only.
- **It costs on every bump.** A Rust and WebAssembly build with its own CI workflow, the Endive runtime, a binary
  interface, failure-name mapping, native-image setup and workaround tests. The bump to `v10.11.20261105` (PR #240)
  changed 62 files over several review rounds. It also made `amaru-scalus` about 5–16 times slower at p50 and 26–37
  times at p99 on real Plutus transactions (ADR-057 Phase E re-run), because Amaru made its phase-one entry point
  crate-private.

The project's rule is the simplest correct design, with no extension points for needs that do not exist (`AGENTS.md`,
"Simplicity and reuse").

## Decision

### D1. Remove the engine

Delete:

- **Modules and CI:** `amaru-validator`, `amaru-validator-wasm` and `.github/workflows/amaru-wasm.yml`, after D5 moves
  the corpus job out of it.
- **Build:** the `withAmaru` property and every use: `settings.gradle`; root `build.gradle` (the
  `centralDeploymentExclusions` entry); `app/build.gradle`; `ledger-conformance/build.gradle` (the source set, the
  unconditional read of `amaru-validator-wasm/Cargo.toml`, the `conformance.withAmaru` and `amaru.wasm.*` system
  properties, the 4 GB heap); the `endive` version and its three aliases (`endive-runtime`, `endive-wasm`,
  `endive-build-time-compiler`) in `gradle/libs.versions.toml`.
- **Engine names and settings:** `amaru` and `amaru-scalus` in `LedgerValidationEngines` (with their
  `unavailableMessage` case and the Amaru advice in the `java-julc` message); the `yano.validation.amaru.*` keys
  (`pool-size`, `timeout-ms`, `max-abandoned`, `max-memory-pages`) in `YanoPropertyKeys`, `YanoProducer` and
  `application.yml`.
- **Conformance harness, engine parts only:** `src/amaruTest` and the Amaru reference engine
  (`BaselineEngines.amaru()`), `ConformanceSettings`' module accessors (`withAmaru`, crate version, wasm file and
  digest), `Mutation.amaruReports` and `AMARU_REFUSES_PV9`, and the Amaru rows of `EngineLatencyBenchmarkTest` with
  any helper only they use.
- **QA and fixtures:** the Amaru cases and the two health-gauge patterns of `qa/harness/ledger-rules-native-parity.sh`,
  `qa/harness/haskell-sync.sh`,
  `qa/README.md`, the `tx-services` devnet fixtures (`DevnetGateNode`, `LedgerRulesDevnetMatrix`), and the module in
  `ValidationEngineNativeMetadataTest`.

**Plumbing that only Amaru used goes too** (it would otherwise be dead code):

- `LedgerValidationEngine.isHealthy()` and everything built on it: the engine-health parts of `ValidationEngines`
  status, `TxSubsystem`'s `validationEngineHealthy`, and the gauges `yano.validation.engine.healthy` and
  `yano.validation.shadow.sync.engine.healthy`. Only the Amaru engine ever turned unhealthy.
- `EngineContext`'s configuration and thread accessors and their forwarding in `ValidationEngineBootstrap`
  (`VALIDATION_THREADS`), and `ValidationEngines.ObserveOnlyContext`, which existed to answer
  `yano.validation.amaru.pool-size`.
- `ValidationEngines.uses()` and `ValidationEngineSettings.uses()`, whose only caller was the Amaru health gate, and
  `LedgerValidationEngines.isKnownName`, which has no caller.

Each is removed only after `git grep` shows no remaining caller in main code.

The engine SPI itself (`LedgerValidationEngineFactory` and `ServiceLoader` discovery) stays: `scalus-bridge` supplies
the `scalus` engine through it, because `ledger-rules` cannot depend on that module.

### D2. Engines after removal

| Engine | Phase one | Phase two | Role |
|---|---|---|---|
| `java-julc` | Java Conway rules (ADR-056) | julc | default admission engine |
| `java-scalus` | Java Conway rules | Scalus | optional |
| `scalus` | the legacy Scalus validator | Scalus | optional, legacy |

Shadow and shadow-sync engines are chosen from the same three. Validation behaviour of all three is unchanged.

### D3. A selected `amaru` engine refuses to start

With transaction validation enabled (`yano.block-producer.tx-evaluation`), naming `amaru` or `amaru-scalus` as the
admission engine, as an admission shadow, or as a shadow-sync engine with shadow sync on, takes the existing
unknown-engine path: the node refuses to start with "Unknown validation engine '…'. Available: …". No special message
is kept. Names that are not resolved today stay unresolved, with no new check: every engine name when transaction
validation is disabled (`YanoAssembly` does not call the transaction-services factory, which logs a warning), and a
shadow-sync name with shadow sync off (`ValidationEngines`, `ValidationEngineSettings.usesEngineApi`).

- **Opt-in builds** (`-PwithAmaru=true`) configured with an Amaru engine and transaction validation enabled must
  change their configuration
  (`docs/UPGRADING.md`: use `java-julc`, or drop the shadow entry). `-PwithAmaru=true` is then silently ignored.
- **Ordinary builds** already refused these names; only the message changes.
- Leftover `yano.validation.amaru.*` keys are ignored like any other unused key.

Tests cover both names in each of the three roles, extending the existing bootstrap tests.

### D4. Readiness and metrics lose engine health

`ValidationEngineHealthCheck` reported shadow-sync engine health always, and admission and admission-shadow health
only when an Amaru engine was configured. With `isHealthy()` gone (D1), it reports the configured engine names and the shadow-sync statistics, and is always `UP` (as every node
without Amaru already was). The two health gauges are removed; `docs/UPGRADING.md` lists them. Every other validation
metric is unchanged.

### D5. The scenario data stays, with a pin and a CI job of its own

Kept unchanged, as data infrastructure:

- `AmaruScenarioLoader`, `AmaruCorpusNames`, `ScenarioCases` and the `-PamaruScenariosDir` / `AMARU_SCENARIOS_DIR`
  input;
- `AmaruKnownFailures` and `amaru-known-failures.txt`: the blueprint vectors Amaru's own harness fails at the pinned
  tag, which set the ADR-056 Phase 7 gate ("the Java engine passes every vector Amaru passes",
  `BlueprintVectorGateTest`), and the "Comparison with Amaru" section of the blueprint report;
- the "Amaru scenarios" column of the coverage matrix;
- the node-diversity ledger-rules report and its corpus.

**Pin.** `node-diversity/corpora.env` already pins the externally fetched node-diversity corpora; it now pins the
Amaru corpus too. It gains `AMARU_REPOSITORY`, `AMARU_TAG` (`v10.11.20261105`) and `AMARU_REVISION` (the commit the tag
points to), moved from `amaru-validator-wasm/AMARU_VERSION`. The vendored blueprint vectors keep their own pin
(`BlueprintVectorLoader`).

- `ConformanceSettings.amaruTag()` (formerly the constant `AMARU_TAG`) reads `AMARU_TAG` through the build, and
  fails if the build did not pass it. The read is unconditional, since the coverage,
  blueprint and baseline reports use the tag whether or not the node-diversity corpora are given (the build's
  `CBOR_DATASET_REVISION` read is conditional).
- Text that names the tag or the deleted `AMARU_VERSION` points to `corpora.env` instead: the `AmaruScenarioLoader`
  Javadoc and skip message, the `amaru-known-failures.txt` header, `vectors/cardano-blueprint/README.md` and
  `ledger-rules/README.md`. The loader's code is unchanged.
- `fetch-corpora.sh` also checks out the Amaru corpus, so one command fetches every external corpus at its pin.
- Moving the pin is a deliberate corpus upgrade: rerun the conformance and compare.

**CI.** A new workflow, `ledger-conformance.yml`, takes over the corpus job of `amaru-wasm.yml`. Without it, the
368-scenario gate would only skip.

- Triggers: the old path filters (`ledger-rules/**`, `ledger-conformance/**`, `scalus-bridge/**`), plus
  `script-evaluators/**` and the workflow file itself, for both push and pull request.
- It fetches only the Amaru corpus, not the larger node-diversity corpora.
- It checks that `AMARU_TAG` resolves to `AMARU_REVISION` upstream (peeled, for an annotated tag) and that the checkout
  is at `AMARU_REVISION`, as the old job did, so a changed tag cannot relabel old results.
- It runs `:ledger-conformance:test` with `-Pconformance.strict=true` and the corpus, and uploads the baseline,
  blueprint and coverage reports even on failure, as the old job did.

**Comments.** Comments citing Amaru's Rust sources or corpus as a reference for a rule stay. Comments citing the
module, its files, its configuration keys, its engine names or ADR-057's steps are updated.

### D6. Records

- ADR-057: status "Superseded by ADR-062". Its text stays as the record of what was built and measured. Its Phase D
  points to `amaru-validator/README.md` for the full divergence list, which this change deletes, so the list is copied
  into ADR-057.
- ADR-056: the engine list and the oracle paragraphs gain a note pointing here. The Amaru phase-one exception PR #240
  added is replaced by a one-line note that the engine was removed.
- ADR-060: its reference to ADR-057 becomes a reference to the fixture format only.
- Generated reports:
  - `baseline-2026-09.md` loses the Amaru engine row and the module line of its header;
  - `blueprint-vectors-2026-09.md` keeps its "Comparison with Amaru" section (D5);
  - `conway-rule-coverage.md` keeps its "Amaru scenarios" column.

## What is not lost

| ADR-057 role | After this ADR |
|---|---|
| Admission engine | `java-julc` (default), `java-scalus`, `scalus` |
| Admission shadow, shadow sync | the same three, side by side (`shadow-sync-engines: java-julc,java-scalus`) |
| Test oracle on scenarios and fixtures | the fixtures' own expected results, which are Haskell-checked |
| Test oracle on mutants | the Haskell failures each mutant pins (`Mutation.covers`, `haskellFailures`), each cited from the Haskell source; see open question 1 |
| Test oracle on blueprint vectors | the vectors' expected results, and Amaru's recorded pass set (`AmaruKnownFailures`, kept) |

## Alternatives considered

- **Keep it, and stop bumping it.** Rejected: a pinned engine falls behind Amaru and the ledger, and still costs a
  build, a CI workflow and native-image upkeep for an oracle that has not caught a Java bug.
- **Keep only the test-oracle role.** Rejected for the same reason: the WebAssembly build is the cost, whatever the
  role.
- **Keep engine health, reported for every engine.** Rejected: no remaining engine can turn unhealthy, so the gate,
  the fields and the gauges would be constant. An engine that needs health reporting can add it with its own need.
- **Keep a dedicated "engine removed" startup message.** Rejected as a shim: the unknown-engine message already
  names the available engines, and `UPGRADING.md` covers the upgrade.

## Consequences

- One Rust build, one CI workflow and the Endive runtime go: about 9,900 lines, 8,236 of them Java and Rust
  (`amaru-validator` 5,942, `amaru-validator-wasm` 3,851 and `src/amaruTest` 129, counted over `.java`, `.rs`, `.py`,
  `.gradle`, `.toml`, `.md` and `.yml` files), plus the plumbing in D1.
- The default build, distributions, Docker and native images, and Maven publications lose nothing they contained.
- The readiness response and the metrics lose the engine-health fields and two gauges (D4).
- A node built with `-PwithAmaru=true` and configured with an Amaru engine must change its configuration (D3).
- Disagreements found later by the Java engines are settled against Haskell directly, as they already are.

## Verification

- `./gradlew build` passes, and the `ledger-rules`, `ledger-conformance`, `app`, `runtime`, `tx-services` and
  `scalus-bridge` suites pass.
- **Before and after, at the same corpus pins** (Amaru `v10.11.20261105`; the node-diversity corpora):
  - `ledger-conformance` test results are identical test by test, except tests that existed only for the engine,
    and the JUnit reports show that the corpus tests ran rather than skipped;
  - the pinned tallies are unchanged: `JavaEnginePhase5GateTest.PER_VERSION`, `MutationWorldMatrixTest.PINNED`,
    `BlueprintVectorGateTest.PINNED` and `RECORDED`, and the `ConformanceBaselineTest` counts;
  - the baseline report's rows for every remaining engine, `scalus-legacy` included, are identical apart from dates
    and timings;
  - the four node-diversity reports have identical outcomes, corpus identities and execution budgets; generated
    metadata (dates, versions, `implementation_commit`, machine) and measured figures (`cpu_us`, memory and allocation
    bytes) are left out of the comparison.
- Startup tests: `amaru` and `amaru-scalus` in each of the three roles fail with the unknown-engine message when
  transaction validation is enabled; with it disabled, startup is unchanged (`transactionBootstrapDisabledDoesNotCallFactory`).
- A residue search, `git grep -i -E "amaru-scalus|withAmaru|amaru-validator|amaru_validator|AMARU_(SCALUS|POOL|TIMEOUT|MAX)|yano\.validation\.amaru|endive|engine\.healthy"`,
  finds only ADR history and the `UPGRADING.md` entry; no `LedgerValidationEngine.isHealthy` remains. Citations of
  ADR-057 decisions still in force stay as history: Phase B deviations 4 and 9 (`CanonicalLedgerView`,
  `ScalusScriptPhaseEvaluatorTest`), the Phase B and C fixtures (`ledger-rules/build.gradle`, `tx-services/build.gradle`)
  and the §2 fail-closed startup rule (`YanoAssembly`). Other `isHealthy` methods (account history) are unrelated.
- PR checks that must pass: the build workflow (including the publication-scope check), the new
  `ledger-conformance.yml`, and the existing integration workflow (devnet, distribution and native checks).
- Not run locally: the new workflow, native-image builds, and the devnet gates. Native engine parity
  (`qa/harness/ledger-rules-native-parity.sh`) stays unverified unless run.

## Open questions

1. **A Haskell oracle for mutants.** Mutants are compared with the Haskell failures they pin, each cited from the
   Haskell source. Running new mutants through Haskell itself (Amaru's `tests/conformance/run-haskell.sh` drives the
   Haskell ledger on this fixture format) would replace the second opinion Amaru gave. Lean: a follow-up issue, not
   this change.

## Revision history

- r1 (2026-11-07): initial version.
- r4 (2026-11-07, responds to Codex's review of r3): D3 and its startup test are qualified by transaction validation
  being enabled (Codex F16).
- r3 (2026-11-07, responds to the reviews of r2): D3's precondition (Fable F9); the corpus job's fetch, tag check,
  report upload and own trigger (Fable F10, Codex F11, F15); the pin's scope, the unconditional tag read and the
  retargeted citations (Fable F11, Codex F14); all Endive aliases (Fable F12, Codex F10); the QA gauge patterns and
  D4's history (Codex F12); the residue search, normalised report comparison and required PR checks (Fable F13,
  Codex F13). Fable approved r2; Codex accepted removing engine health.
- r2 (2026-11-07, responds to the Fable and Codex reviews of r1):
  - The scenario corpus gets its own CI workflow and a pin in `corpora.env` (D5, both reviews).
  - `AmaruKnownFailures`, the blueprint comparison and the coverage column are kept as data (D5, both).
  - Plumbing only Amaru used is removed, including engine health (D1, D4; Fable). Codex asked to keep health
    reporting for every engine, which is rejected under Alternatives.
  - The removal list is completed: build, QA, fixtures, `ObserveOnlyContext`, the `Cargo.toml` read (D1, both).
  - D3 covers all three roles and both kinds of build, with tests (Codex).
  - D6 is set per report, and ADR-057's divergence list is preserved (both).
  - Verification gains the pinned tallies, every remaining engine, and a wider residue search (both).
  - Facts corrected: `isHealthy()`, Endive, the slowdown figures, the Java and Rust line count, and which builds
    contain the module.
