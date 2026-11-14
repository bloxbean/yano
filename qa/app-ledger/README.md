# Application-ledger regression harness (#245)

Run `python3 qa/app-ledger/run.py` for the implemented Phase 1 development gates,
`--gate standalone|configuration|hybrid|plugins|adapter|consensus` for a focused gate. Each run
requires a fresh output directory (`--out DIR` is optional). Java 25 and the
normal Gradle prerequisites are required.

The runner executes existing JUnit suites with real sockets and temporary stores,
uses a bounded process group per gate, and saves the Gradle log and JUnit XML
before the next gate can overwrite them. It writes machine-readable `results.json`
and a Markdown report. Compilation failures, timeouts, test failures and zero
executed tests all fail the gate and the runner. Gates run sequentially to avoid
sharing RocksDB/transport resources across concurrent test invocations.

The standalone gate exercises application submission through a follower,
protocol-100 gossip, independent consensus, and a late join through protocol 103.
It asserts no Cardano runtime stages or chainstate directory. Configuration tests
cover old flat/indexed parsing and new explicit profile validation. Hybrid and
plugin gates reuse the established shared-transport, Cardano observer/anchor,
lifecycle and catalog tests.

During development this branch requires the app-only Yaci API from
[bloxbean/yaci#245](https://github.com/bloxbean/yaci/pull/245). Consume an exact
staged/published artifact, never overwrite a released Maven coordinate. Additional
Gradle repository/version arguments can be passed with repeated `--gradle-arg`:

```sh
python3 qa/app-ledger/run.py --gate standalone \
  --gradle-arg=-I --gradle-arg=qa/app-ledger/artifact-inputs.gradle \
  --gradle-arg=-PappLedgerYaciRepository=file:///absolute/staged/maven-repository \
  --gradle-arg=-PappLedgerYaciVersion=EXACT_VERSION
```

No sibling checkout is required by the harness. The development repository is not a release input. Pin the final Yaci artifact before
marking the coordinated PR ready.

Phase 2 must add independent X-core dependency checks, a prior compiled plugin
fixture, packaged standalone/integrated runs and the oldest frozen Host API/host
matrix. Those are **not covered or claimed passing** by the current gates. The
final release gate remains `qa/release-qa.sh` plus the packaged X compatibility
matrix after both phases are integrated; do not run two full qualification cycles.

The packaged three-process check builds on the existing release-QA shell helpers:

```sh
./gradlew :app:quarkusBuild
bash qa/app-ledger/packaged-cluster.sh \
  "$PWD/app/build/yano.jar" /absolute/new/cluster-output
```

Use the actual runner filename emitted by Gradle. Pass the same pinned artifact
inputs used for unit tests to the build. This check uses ports 17771–17773 and
23441–23443 (overridable via `HTTP_A/B/C` and `N2N_A/B/C`), refuses occupied ports,
and stops only its own tracked processes. It verifies follower submission,
three-node committed-block identity/root agreement, protocol-103 late joining,
graceful restart/catch-up, HTTP 503 for unavailable Cardano operations, and no L1
chainstate. Logs, proofs, block responses and the tested JAR digest are retained.
The JUnit evidence test additionally verifies certificate signatures and proofs;
the HTTP test does not substitute a signature count for cryptographic validation.

Named JUnit suites must actually execute for their gate to pass. Reports include
a digest of tracked and untracked source inputs, so local implementation files
are covered before the milestone commit. Outputs belong outside the source tree
(or in the ignored default `qa/results` directory).

The `host` gate now also loads a committed pre19 plugin JAR without recompiling
it and checks that moved API classes/wire resources each have exactly one owner.
Fixture source and artifact/compiler hashes are in `fixtures/pre19/`; test input
bytes live in `runtime/src/test/resources/app-ledger/compat/`. Only deliberately
refresh this baseline when support for that API is intentionally dropped.

During extraction, add these artifact inputs to every Yano build/gate:

```sh
--gradle-arg=-PappLedgerFrameworkRepository=file:///absolute/framework-maven-repository \
--gradle-arg=-PappLedgerFrameworkVersion=EXACT_FRAMEWORK_VERSION
```

X owns `frameworkCheck` and `qa/framework/README.md`, which validate its API,
configuration and core without a Yano source checkout. Both repositories retain
consumer-side compatibility tests. The core dependency report and fixed plugin
fixture cover part of Phase 2; the independently packaged host matrix is still
outstanding. Default draft artifact versions must be replaced by final published
pins before this coordinated release can be qualified.
