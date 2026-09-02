# ADR-053: In-repo SDK Compatibility and Load Suite

- Status: Accepted — implemented on `feat/sdk-compat-tests`
- Date: 2026-09-01 (implemented 2026-09-02)
- Supersedes: nothing
- Related: ADR-018 (Blockfrost-compatible REST APIs), `e2e-tests/` endpoint smoke runner,
  `testkit` / `testkit-ccl` / `app-e2e-testkit`

## Context

Since 2026-08-15 an out-of-tree rig at `~/Downloads/yano-ccl-test` has been the only
thing that answers the question *"does a real Cardano off-chain SDK still work against
this build of Yano?"*. It drives a running Yano devnet through three independent
client stacks:

| Stack | Version pinned | Scripts |
| --- | --- | --- |
| cardano-client-lib (Java) | `0.8.0-pre5` | `LoadTest`, `Scenarios`, `PlutusProbe`, `QueryOverlayProbe`, `BlsProbe` |
| MeshJS | `@meshsdk/core` 1.9.1 | `compat.mjs`, `load.mjs`, `vesting.mjs` |
| Evolution SDK (Lucid) | `@evolution-sdk/lucid` 2.0.1 | `compat.mjs`, `load.mjs`, `vesting.mjs` |

It has already paid for itself three times, each time as a *comparative* run of the
identical suite against two node builds:

- **JVM vs native** (`results-jvm*` / `results-native*`) — found the `JAVA_OPTS`
  regression and produced `JVM-VS-NATIVE-REPORT.md`.
- **BLS12-381 evaluate→submit** (`BLS-SMOKE-TEST-REPORT.md`) — proved pre8 native
  fails (`blstJNI` clinit NPE surfacing as a bare HTTP 500) and pre12 native passes.
- **issue-106 baseline vs candidate** (2026-08-31) — `results-issue106-baseline-*`
  against `results-issue106-candidate-*`.

It also holds one standing interoperability finding: **MeshJS cannot build chained
transactions against Yano**, because `MeshTxBuilder.complete()` re-resolves inputs
through the provider even when they are supplied explicitly, and Yano's
`/txs/{hash}` and `/txs/{hash}/utxos` are canonical-only. CCL and Evolution chain
fine. That finding lives only in a markdown file on one laptop.

The problem is custody, not capability. The rig is on one machine, outside version
control, with absolute paths baked in, and its results are not tied to a commit. When
Yano's REST surface or mempool overlay changes, nothing tells us an SDK broke.

### What already exists in-repo, and why it is not enough

- `e2e-tests/yano_endpoint_smoke.py` — dependency-free Python; checks that endpoints
  *exist* and return plausible shapes. It does not build, sign, or submit a
  transaction, and it never exercises an SDK's own provider/serialisation code.
- `testkit` / `testkit-ccl` — `YanoDevnetTestKit`, `YanoAppProcess`,
  `YanoBackendService` (a CCL `BackendService` over Yano's REST). Published
  libraries aimed at *downstream users writing tests*, not at us load-testing Yano.
- `app-e2e-testkit` — Quarkus/external-process E2E support for distributions.

None of them answer "does MeshJS 1.9.1 still work", which is precisely the question a
wallet or dApp integrator asks first.

## Decision

Bring the **source** of the rig into the Yano repository as an adhoc,
manually-invoked suite at `compat-tests/`, alongside the existing unwired
`e2e-tests/`. Leave every run artifact behind.

### D1 — Standalone nested build, not a Gradle subproject

`compat-tests/ccl` gets **its own Gradle wrapper** and is **not** added to
`settings.gradle`.

This is the load-bearing decision. Wiring it in as a subproject would silently do
three unwanted things, because `build.gradle:429` applies conventions to every
subproject not named in `nonLibraryModules = ['app', 'console-ui', 'yano-bom', 'archive-modules']`:

1. `apply plugin: 'org.unbroken-dome.test-sets'` + `testSets { integrationTest }`
   (`build.gradle:500`) means any `integrationTest` source set is swept into the
   `extendedTest` aggregate task (`build.gradle:637`), which `integration.yml` runs
   **on every push and pull request**. That is the exact opposite of "adhoc".
2. `apply plugin: 'maven-publish'` + `'signing'` would publish a
   `yano-compat-tests` artifact to Sonatype on release.
3. The CycloneDX aggregate SBOM would absorb MeshJS-adjacent Java deps and the
   pinned *released* CCL line, muddying the released-product bill of materials.

Adding the module to `nonLibraryModules` would suppress all three, but only by
opting out of the very conventions that make a subproject worth having. A standalone
build avoids the traps by construction.

It also buys the thing this suite most needs: **independent version pins**. The suite
must test the CCL line an external integrator actually resolves from Maven Central
(`0.8.0-pre5`), not the repo catalog's `0.8.0-pre5-dev1` snapshot. Pinning it to
`gradle/libs.versions.toml` would make Yano's own dependency bump silently change
what "compatible" means.

*Alternative considered:* wire it as a subproject listed in `nonLibraryModules`, with
a hand-rolled `compatTest` task name that `extendedTest` does not match. Rejected:
same isolation, more coupling, and a future edit to `nonLibraryModules` or the
`extendedTest` filter re-arms every trap.

### D2 — Layout

```
compat-tests/
  README.md                    # how to run, both modes; known-fail table
  VERSIONS.md                  # every SDK pin + why, one line each
  run-suite.sh                 # the runner, ROOT derived from $0
  bin/
    with-devnet.sh             # start a devnet, run a command, tear down
  ccl/                         # standalone Gradle build (own wrapper)
    settings.gradle build.gradle gradle/wrapper/
    src/main/java/com/bloxbean/cardano/yano/compat/ccl/
  mesh/                        # package.json + package-lock.json + src/*.mjs
  evolution/                   # package.json + package-lock.json + src/*.mjs
  shared/                      # vesting.ak, vesting-plutus.json, proxy.mjs,
                               # endpoint-baseline.json
  contracts/bls/               # julc BlsDoublingValidator source
  results/                     # gitignored
```

`shared/` must stay a sibling of `mesh/` and `evolution/`: both vesting scripts
resolve the compiled validator via `new URL('../../shared/vesting-plutus.json',
import.meta.url)`.

### D3 — Externally started devnet is the contract

The suite **does not manage a node**. It assumes a Yano devnet is already running and
talks to it over REST. This is both how every historical comparison was actually done
(point the suite at an unpacked release zip, at a baseline build, at a candidate build)
and the simplest possible contract: one less thing that can fail, and the same command
works against a local build, a colleague's machine, or a container.

The base URL defaults to **`http://localhost:7070/api/v1`**, matching the repo's
own default and `e2e-tests/README.md`. It is overridable at three levels, in
increasing precedence:

| Level | Mechanism |
| --- | --- |
| runner | `run-suite.sh --url http://host:7071/api/v1` |
| environment | `YANO_URL=...` |
| per stack | `-Dyano.url=...` (CCL), `YANO_URL=...` (Mesh, Evolution) |

The runner exports `YANO_URL` and passes `-Dyano.url` so a single `--url` reaches all
three stacks identically. The historical rig defaulted to `7071` in some scripts and
`7070` in others; normalising to `7070` everywhere is part of the port.

`bin/with-devnet.sh` is offered as a **convenience wrapper only** — it starts a devnet,
runs the suite against it, and tears it down. It is not on the critical path, and no
suite code depends on it.

Whichever way the node is started, chained-transaction workloads need
`yano.block-producer.block-time-millis=20000`. With default block times the parent
confirms mid-test and the chain test passes for the wrong reason.

Because the suite does not start the node, it cannot *set* that — so it **measures**
it instead. Preflight samples block cadence over six seconds; below
`CHAIN_MIN_BLOCK_MS` (default 5000) the runner prints a warning and records the
`*:chained` cases as `SKIPPED` rather than asserting a verdict it cannot trust.
Declining to answer is the honest outcome; `CHAIN_MIN_BLOCK_MS=0` overrides it.
`bin/with-devnet.sh` sets the slow block time directly. This is the lesson that cost
two debugging sessions in the out-of-tree rig, moved out of the README and into the
tool.

The suite targets a **devnet-mode** node only — it funds wallets through
`POST /api/v1/devnet/fund`, which is `403` off devnet. Public-network compatibility
stays out of scope.

### D4 — Compat asserts, load reports

Two categories with different contracts, and they must not be blurred:

- **Compat / regression** — the SDK provider walks (`compat.mjs`), the vesting
  datum/redeemer cases, and `BlsProbe`. Deterministic, exit-code pass/fail, safe to
  gate on. Each vesting run includes a **negative control** — beneficiary collecting
  before unlock must be *rejected* — and that negative case is what proves the datum
  is enforced rather than merely stored. A vesting suite whose negative control passes
  is a broken suite.
- **Load** — `LoadTest`, `Scenarios`, `load.mjs`. Emits `REPORT.md` / JSON plus
  before/after `yano_node_mempool_*` metric snapshots and RSS/CPU samples for a human
  to compare across two runs. **No asserted thresholds.** Numbers depend on the host
  machine; a threshold would either be so loose it catches nothing or so tight it
  fails on a warm laptop.

Do not JUnit-ify the load harness. A verbatim port keeps new results directly
comparable against the historical `results-*` sets, which is the whole reason the rig
has value.

### D5 — Known-fail registry

`compat-tests/KNOWN-FAILS.md`, machine-readable enough for the runner to consume:

| id | Stack | Case | Expected | Evidence |
| --- | --- | --- | --- | --- |
| `mesh:chained` | MeshJS 1.9.1 | chained submit reaches full depth | FAIL | `complete()` re-resolves inputs via the provider; `/txs/{hash}` is canonical-only |
| `evolution:awaitTx` | Evolution 2.0.1 | `lucid.awaitTx()` | FAIL | polls `GET /txs/{hash}/cbor`, which Yano does not implement; the HTML 404 then hard-crashes the SDK's polling interval |

Granularity matters here. `evolution:awaitTx` is a *separate* case rather than a
known-fail marker on the whole Evolution provider walk: marking the walk as expected-fail
would have hidden regressions in the five steps that pass before it. `evolution:compat`
polls `/utxos/{hash}/{index}` instead and keeps asserting.

The runner exits non-zero if a known-fail **passes** (fixed upstream — update the
table) as well as if an unlisted case fails. Without this, the very first in-repo run
reads as a fresh regression and the suite loses credibility on day one.

### D6 — Endpoint coverage baseline

`shared/proxy.mjs` is a recording reverse proxy: point an SDK at it and it captures
exactly which endpoints that SDK calls and with what status codes. The existing
`shared/mesh-calls.json` is one such capture. Promote it to
`shared/endpoint-baseline.json` — a checked-in record of *which parts of Yano's REST
surface each SDK actually depends on*.

This complements `e2e-tests/yano_endpoint_smoke.py`, which warns when OpenAPI exposes
a path with no smoke case. The baseline answers the inverse and more urgent question:
which paths would break a real dApp if removed or reshaped.

### D7 — No consolidation with `testkit-ccl` in phase 1

`YanoClient` (286 lines) overlaps `testkit-ccl`'s `YanoBackendService`. Leave the
duplication. A verbatim port keeps the diff reviewable and the results comparable;
folding the two together in the same change mixes a file move with a behavioural
refactor and makes any resulting discrepancy impossible to attribute. Revisit as a
separate, optional phase once the suite has run green in-tree at least twice.

## Build wiring and entry points

This section is normative: it defines exactly which tasks do and do not run the suite.

### At the repository level, Gradle does not know `compat-tests/` exists

`compat-tests/` is a plain directory, exactly like the existing `e2e-tests/`. It is
**not** listed in `settings.gradle`, and `settings.gradle` declares no `includeBuild`,
so there is no composite build either. Every root convention block iterates over
*included* projects only — `allprojects` (`build.gradle:33`, `build.gradle:615`) and
`subprojects` (`build.gradle:431`) — so an unlisted directory is invisible to all of
them, including the CycloneDX `cyclonedxDirectBom` configuration at `build.gradle:34`.

| Command | Where it runs today | Reaches `compat-tests/`? |
| --- | --- | --- |
| `./gradlew build` | `build.yml`, every push + PR | No |
| `./gradlew extendedTest` | `integration.yml`, every push + PR | No |
| `./gradlew distributionCheck` | `integration.yml` | No |
| `./gradlew fullBuild` | manual / release rehearsal | No |
| `./gradlew publish*`, `signing` | release | No |
| `./gradlew cyclonedxBom` (aggregate SBOM) | release | No |
| `compat-tests/run-suite.sh` | manual only | **Yes — the only entry point** |

The suite therefore never runs "with the build". It runs when a human runs it. That is
the requirement, not an accident of phasing.

### Inside `compat-tests/` there is no aggregate module

It is three independently runnable units plus fixtures, glued by one shell script.
There is no parent build that owns all three, and there is deliberately no attempt to
drive npm from Gradle.

| Unit | Build system | Own entry point |
| --- | --- | --- |
| `ccl/` | standalone Gradle build, own wrapper, `rootProject.name = 'yano-compat-ccl'` | `./gradlew <task>` |
| `mesh/` | npm package | `npm run <script>` |
| `evolution/` | npm package | `npm run <script>` |
| `shared/`, `contracts/bls/` | fixtures; `contracts/bls` has an optional julc build | n/a |

The existing rig's wrapper is already `gradle-9.4.1`, matching the repo's
`gradle/wrapper/gradle-wrapper.properties`, so the nested build reuses the same cached
Gradle distribution and costs nothing extra to run.

### Task names inside `compat-tests/ccl`

```
./gradlew compat      # SDK provider walk, deterministic pass/fail
./gradlew blsProbe    # BLS12-381 evaluate -> submit -> confirm, with negative control
./gradlew load        # -Dload.* knobs; emits REPORT.md, no assertions
./gradlew scenario -Pmode=plutus|exunits|rollback|interval|heap
```

All are `JavaExec` tasks. None is named `test`, `check`, or `integrationTest`, for two
reasons. First, every one of them requires a live node, so binding them to `check`
would make a bare `./gradlew build` inside that directory fail with no node running.
Second, `extendedTest` (`build.gradle:637`) aggregates by the literal task name
`integrationTest`; avoiding that name means that even if someone later adds the
directory to `settings.gradle` by mistake, nothing is silently pulled into CI.

### The runner

`run-suite.sh` runs the three stacks **sequentially**, in a fixed order (CCL, then
Mesh, then Evolution), against one node. Sequential is deliberate: the stacks share a
single devnet and a single mempool, so running them concurrently would make load
numbers meaningless and mempool-capacity outcomes non-reproducible.

```bash
# whole suite against a devnet on the default http://localhost:7070/api/v1
compat-tests/run-suite.sh

# against another node, labelled for comparison
compat-tests/run-suite.sh --url http://localhost:7071/api/v1 --label pre13-native

# compat only (fast, deterministic) or load only (slow, reporting)
compat-tests/run-suite.sh --compat-only
compat-tests/run-suite.sh --load-only

# one stack, or one case
compat-tests/run-suite.sh --only mesh
compat-tests/run-suite.sh --only ccl:blsProbe
```

Results land in `compat-tests/results/<label>/` (gitignored), keeping the existing
`results-<label>/` shape so new runs stay diffable against the archived history.

### CI

C-M5 adds `.github/workflows/compat-suite.yml` with `workflow_dispatch` **only** —
inputs for a target URL or a ref to build from. No `push` trigger, no `pull_request`
trigger, and no entry added to `extendedTest` or `fullBuild`.

### If we later decide to wire it in anyway

Adding `'compat-tests'` to `yanoModules` in `settings.gradle` would additionally
require adding its name to `nonLibraryModules` (`build.gradle:429`) to suppress
`java-library`, `maven-publish`, `signing`, and `test-sets`. But `nonLibraryModules`
suppresses *all* conventions, so the module would be hand-rolled anyway — identical to
the standalone build, with the added exposure of the two `allprojects` blocks and of
any future edit to the `extendedTest` filter. There is no version of "wire it in" that
is simpler than leaving it out.

## Port hygiene

**Bring** (~200 KB source + ~250 KB lockfiles):

- `ccl-client/src/**` (9 files, 2,384 lines), `mesh-client/src/**`,
  `evolution-client/src/**`, `shared/**`, `bls-contract/src/**`, `run-suite.sh`.
- Both `package-lock.json` files, committed. Unpinned transitive deps would make
  "SDK compatibility" mean something different on every run.

**Drop:**

- `results-*/`, `*-run*/`, `issue106-*/`, `report*/`, `*.log`, `mesh-load-report.json`,
  `evolution-load-report.json` — run artifacts, superseded by `results/` (gitignored).
- `bls-run/` entirely: bundled native zips, unpacked distributions, and live RocksDB
  `chainstate/` directories.
- `ccl-client/gradle/libs.versions.toml` and `ccl-client/gradle/runtime-plugin-bundle.gradle`
  — stale copies of Yano's own build files. Verified unreferenced: `ccl-client/settings.gradle`
  declares no version catalog and `build.gradle` uses a literal `cclVersion` string. Carrying
  them in would create a second, silently-diverging copy of the dependency catalog.
- The six top-level `*-REPORT.md` files as-is. Their **findings** move into
  `KNOWN-FAILS.md` and the README; the raw reports do not belong in the repo.

**One addition beyond the port.** CCL had no counterpart to the JS stacks'
`compat.mjs` — its probes (`PlutusProbe`, `QueryOverlayProbe`) are exploratory and
carry no exit code. `CompatProbe.java` was written to fill that gap, walking the CCL
`BackendService` surface (protocol params → faucet → `getUtxos` → QuickTx build/sign/
submit → canonical confirmation → `getTransaction` → `getTxOutput`) with a real exit
code. It deliberately goes through CCL's provider APIs rather than raw REST: a payment
that only works when the harness calls `/api/v1` by hand proves nothing about the SDK.

**Fix during the port** (the only other edits allowed in phase 1):

- `run-suite.sh:9` hardcodes `ROOT="/Users/satya/Downloads/yano-ccl-test"` — derive
  from `$(cd "$(dirname "$0")" && pwd)`.
- Java package `com.bloxbean.yanoload` → `com.bloxbean.cardano.yano.compat.ccl`.
  Mechanical; keeps the repo's package convention intact.
- Java toolchain 21 → 25, matching the repo. Required anyway if the BLS contract is
  ever recompiled: julc `0.1.0-pre16` is Java-25-only.
- `libsodium-wrappers-sumo@0.7.16` ships a broken ESM path — it imports
  `./libsodium-sumo.mjs`, which actually lives in the sibling `libsodium-sumo`
  package. Encode the copy-across as an npm `postinstall` script. Left as a README
  note it breaks every fresh clone.
- `shared/vesting.ak` is derived from `cardano-foundation/cardano-templates` and
  currently carries no provenance header. Confirm that repository's licence permits
  inclusion and add a source-attribution header before committing — Yano is MIT, and
  unattributed third-party contract source is the first thing a reviewer will flag.
- Keep `node --experimental-wasm-modules` (MeshJS's whisky-evaluator WASM) inside the
  `package.json` scripts rather than in the runner, so `npm run compat` works standalone.
- `.gitignore`: `compat-tests/results/`, `compat-tests/*/node_modules/`,
  `compat-tests/ccl/.gradle/`, `compat-tests/ccl/build/`.

## Milestones

All five are implemented on `feat/sdk-compat-tests`.

**C-M1 — Port and green. DONE.**
Sources moved under `compat-tests/`, hygiene fixes applied, `README.md`,
`VERSIONS.md`, `KNOWN-FAILS.md` written. Verified against a live devnet: the full
compat set reports 6 `OK`, 1 `KNOWN-FAIL`, and the load phase produces reports for
all three stacks.

**C-M2 — `bin/with-devnet.sh` (convenience only). DONE.**
Builds `app/build/yano.jar` if needed, launches with `-Dquarkus.profile=devnet` from
`app/` (the profile resolves genesis relative to it), forces a 20 s block time, waits
on `/q/health/ready`, runs the suite, tears down. Not on the critical path.

**C-M3 — Known-fail enforcement and exit codes. DONE.**
The runner parses `KNOWN-FAILS.md` and fails on `REGRESSION` *and* on
`UNEXPECTED-PASS`. Both directions were tested with synthetic reports before the
first real run.

**C-M4 — Endpoint baseline. DONE.**
`--check-endpoints` re-runs each JS compat probe behind `shared/proxy.mjs` and diffs
against `shared/endpoint-baseline-<stack>.json` via `shared/diff-endpoints.mjs`. Both
baselines are captured and verified stable across two runs. An endpoint fails only
when it *never* answered successfully — a 404 later followed by a 200 is the
canonical-polling loop, not a broken dependency.

**C-M5 — `workflow_dispatch`-only CI. DONE.**
`.github/workflows/compat-suite.yml`. No `push` or `pull_request` trigger; not added
to `extendedTest` or `fullBuild`. Takes either a target URL or builds the ref and
starts a devnet in the job.

## Consequences

**Positive**

- A regression check that exercises real SDK code paths — serialisation, provider
  contracts, fee estimation, script evaluation — which no current in-repo test does.
- Comparative runs (`baseline` vs `candidate`, JVM vs native, release vs release)
  become a documented, repeatable procedure rather than tribal knowledge.
- The MeshJS chaining finding gets a permanent home and an owner, and will announce
  itself the day upstream fixes it.
- The endpoint baseline turns "which REST paths can we safely change?" from a guess
  into a lookup.

**Negative**

- Three more dependency ecosystems (Maven, two npm trees) to keep current. Mitigated
  by pinning everything and by the suite being adhoc: a stale pin degrades signal, it
  never breaks the build.
- ~450 KB added to the repo, including two large lockfiles.
- The suite is macOS/Linux shell. Windows contributors run it in WSL. Acceptable — it
  is a manual verification tool, not part of `./gradlew build`.

**Neutral**

- The out-of-tree rig at `~/Downloads/yano-ccl-test` stays until C-M1 is verified
  green, then becomes redundant. Its `results-*` history is worth archiving somewhere
  durable before the directory is reclaimed; it is the baseline every future
  comparison measures against.

## Non-goals

- Public-network (preprod/mainnet) compatibility runs. The suite depends on
  `/devnet/fund`.
- Asserted performance thresholds. See D4.
- Replacing `e2e-tests/yano_endpoint_smoke.py`. The two are complementary: one checks
  the surface exists, the other checks real clients can use it.
- Publishing any part of this as a Maven or npm artifact.
