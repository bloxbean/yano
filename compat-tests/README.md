# Yano SDK compatibility and load suite

Drives a running Yano **devnet** through three independent Cardano off-chain SDKs and
reports whether each still works. This is the only thing in the repo that builds,
signs, and submits transactions through real SDK code — `e2e-tests/` checks that
endpoints exist and return plausible shapes, which is a different question.

Run it when you change the REST surface, the mempool overlay, transaction validation,
or script evaluation; when comparing two builds (JVM vs native, baseline vs
candidate); and before a release.

It is **adhoc**. It is not part of `./gradlew build`, `extendedTest`, or any CI job
that runs on a pull request. See
[`adr/053-sdk-compatibility-and-load-suite.md`](../adr/053-sdk-compatibility-and-load-suite.md)
for why, and for the design in full.

## Layout

```
compat-tests/
  run-suite.sh          the runner - start here
  bin/with-devnet.sh    optional: start a throwaway devnet, run, tear down
  ccl/                  cardano-client-lib (Java, standalone Gradle build)
  mesh/                 MeshJS (npm)
  evolution/            Evolution SDK / Lucid (npm)
  shared/               vesting validator, recording proxy, endpoint baselines
  contracts/bls/        source for the BLS validator used by ccl:blsProbe
  results/              run output (gitignored)
  KNOWN-FAILS.md        expected failures, consumed by the runner
  VERSIONS.md           what is pinned, and why it is pinned here
```

`ccl/` has its own Gradle wrapper and is deliberately **not** in the repo's
`settings.gradle`. Nothing here is published, and nothing here is swept into the root
build.

## Prerequisites

- **A running Yano devnet.** The suite never starts or stops a node.
- Java 25 (the `ccl/` toolchain) and Node 20+ with npm.
- The node must be in **devnet mode**: test wallets are funded through
  `POST /api/v1/devnet/fund`, which is not available on a public network. The runner
  checks this before doing anything and exits with a clear message if it is missing.

### Starting a devnet

From the repository root:

```bash
./gradlew :app:quarkusBuild -PskipSigning=true
cd app
java -Dquarkus.profile=devnet \
     -Dyano.block-producer.block-time-millis=20000 \
     -jar build/yano.jar
```

The devnet profile resolves genesis and key files relative to `app/`, so run it from
there. It listens on `http://localhost:7070`.

> **Set `block-time-millis` to something slow (20000 is what the suite is tuned for).**
> The chaining cases submit a child transaction while its parent is still
> unconfirmed. At the devnet default the parent confirms mid-test, the child succeeds
> for the wrong reason, and the chaining result is a false positive.
>
> The runner measures block cadence during preflight and refuses to guess: below
> `CHAIN_MIN_BLOCK_MS` (default 5000) it warns and records the `*:chained` cases as
> `SKIPPED` instead of asserting a verdict it cannot trust. Set `CHAIN_MIN_BLOCK_MS=0`
> to assert them anyway.

`bin/with-devnet.sh` does all of the above and tears the node down afterwards, if you
would rather not manage it yourself.

## Running

```bash
# everything, against the default http://localhost:7070/api/v1
compat-tests/run-suite.sh

# against another node, labelled so two runs can be compared
compat-tests/run-suite.sh --url http://localhost:7071/api/v1 --label pre13-native

# fast, deterministic pass/fail only (no load phase)
compat-tests/run-suite.sh --compat-only

# one stack, or one case
compat-tests/run-suite.sh --only mesh
compat-tests/run-suite.sh --only ccl:blsProbe,evolution:compat

# what would run?
compat-tests/run-suite.sh --list
```

Stacks run **sequentially** — CCL, then MeshJS, then Evolution. They share one node
and one mempool, so running them concurrently would make the load numbers meaningless
and the mempool-capacity outcomes irreproducible.

The base URL defaults to `http://localhost:7070/api/v1` and is overridable at three
levels, in increasing precedence: `--url`, then `$YANO_URL`, then a per-stack setting
(`-Dyano.url=` for CCL, `YANO_URL=` for the JS stacks). `--url` reaches all three
stacks, so it is normally the only one you need.

npm dependencies are installed on first use (`npm ci`). Pass `--skip-install` to skip.

## Reading the result

Output goes to `results/<label>/`: one log per case, the load reports, before/after
`yano_node_mempool_*` metric snapshots, and `SUMMARY.md`.

```
CASE                   KIND     RESULT  VERDICT
ccl:compat             compat   PASS    OK
ccl:blsProbe           compat   PASS    OK
ccl:load               load     PASS    RECORDED
mesh:compat            compat   PASS    OK
evolution:awaitTx      compat   FAIL    KNOWN-FAIL
mesh:chained           compat   FAIL    KNOWN-FAIL
evolution:chained      compat   PASS    OK
```

| Verdict | Meaning |
| --- | --- |
| `OK` | passed, as expected |
| `KNOWN-FAIL` | failed, and `KNOWN-FAILS.md` says it should |
| `REGRESSION` | failed and should not have — **this is the signal** |
| `UNEXPECTED-PASS` | a known failure was fixed upstream; update `KNOWN-FAILS.md` |
| `RECORDED` | load or exploratory case; never affects the exit code |
| `SKIPPED` | prerequisite missing (e.g. no load report to derive from) |

Exit code is 0 only when every asserting case matched its expectation.

### What actually asserts

- **Compat cases assert.** The SDK provider walks, the vesting datum/redeemer cases,
  and `ccl:blsProbe`. Each vesting run includes a **negative control** — a beneficiary
  collecting before unlock must be *rejected*. That negative case is what proves the
  datum is enforced rather than merely stored; a vesting suite whose negative control
  passes is a broken suite.
- **Load cases never assert.** They emit `REPORT.md` / JSON for a human to compare
  across two runs. There are deliberately no performance thresholds: the numbers
  depend on the host machine, so a threshold would either be loose enough to catch
  nothing or tight enough to fail on a warm laptop.
- **Known failures are scoped as narrowly as possible.** `evolution:awaitTx` is its
  own case rather than a marker on the whole Evolution provider walk, because marking
  the walk expected-fail would hide a regression in any of the five steps that pass
  before it. See `KNOWN-FAILS.md`.
- **`*:chained` is derived** from `chains.fullDepth` in the load report — greater than
  zero means the SDK completed at least one unconfirmed-parent → child chain against
  Yano's mempool overlay. This is the check that catches a mempool-overlay regression,
  and the one that will tell us the day MeshJS fixes its builder.

## Comparing two builds

The suite's main use is comparative. Run it twice with different labels against the
two nodes, then diff:

```bash
compat-tests/run-suite.sh --url http://localhost:7070/api/v1 --label baseline
compat-tests/run-suite.sh --url http://localhost:7071/api/v1 --label candidate
diff compat-tests/results/{baseline,candidate}/SUMMARY.md
```

Keep the load knobs identical between the two runs or the comparison means nothing;
the defaults are fixed for exactly this reason. `--node-pid <pid>` additionally
samples the node's RSS and CPU around each phase into `process.txt`.

## Endpoint coverage

```bash
compat-tests/run-suite.sh --check-endpoints
```

Re-runs each JS compat probe behind `shared/proxy.mjs`, a recording reverse proxy,
capturing exactly which endpoints that SDK calls and with what status codes, then
diffs the capture against `shared/endpoint-baseline-<stack>.json`.

This answers the question `e2e-tests/yano_endpoint_smoke.py` cannot: **which parts of
Yano's REST surface would break a real dApp if removed or reshaped.** A captured
endpoint answering 4xx/5xx fails the case. Added or removed endpoints are reported
but do not fail — an SDK upgrade legitimately changes which paths it touches. If a
baseline file does not exist yet, the first run writes it for review.

## Regenerating the BLS validator

`ccl:blsProbe` embeds the compiled script hex, so the normal run needs no julc
toolchain. To change the validator:

```bash
cd compat-tests/contracts/bls && ../../ccl/gradlew build
# then paste cborHex from
# build/classes/java/main/META-INF/plutus/BlsDoublingValidator.plutus.json
# into BlsProbe.SCRIPT_CBOR_HEX
```

## Troubleshooting

| Symptom | Cause |
| --- | --- |
| `PREFLIGHT FAILED: no Yano REST API` | no node at that URL; start a devnet or pass `--url` |
| `POST /devnet/fund returned 403/404` | node is not in devnet mode; the suite cannot fund wallets |
| `*:chained` always `SKIPPED` | block time too fast; restart the node with `block-time-millis=20000` |
| Mesh fails at module resolution | `npm ci` did not run the `fix-libsodium` postinstall; run `npm ci` in `mesh/` |
| `npm ci` complains about the lockfile | run `npm install` once to regenerate, and commit the result |
