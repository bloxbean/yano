# Application-ledger regression harness (#245)

Run `python3 qa/app-ledger/run.py` for all incremental development gates, or use
`--gate host|standalone|configuration|hybrid|plugins|adapter|consensus` for a focused
gate. Java 25 and the normal Gradle prerequisites are required. Each run uses a
fresh output directory (`--out DIR`, or an ignored directory under `qa/results`).

The runner executes existing JUnit suites with real sockets and temporary stores,
bounds each process group, and archives logs, JUnit XML and verification reports
before the next gate overwrites them. Compilation errors, timeouts, failed tests,
zero executed tests, missing named suites/reports and source changes during a run
fail the gate. Gates run sequentially to avoid sharing transport/storage resources.
`results.json` and `report.md` retain the outcome; artifact reports record the exact
resolved production coordinates and SHA-256 of every JAR.

| Gate | Breaking changes it targets |
| --- | --- |
| `host` | Public ABI, fixed older plugin bytecode, class/resource ownership, N2N registration, retention leases, plugin lifecycle. |
| `standalone` | Three-node gossip/finality, late protocol-103 catch-up, restart, canonical commitment agreement, fresh-store/profile boundaries and absence of L1 state. |
| `configuration` | Existing flat/indexed property parsing, node/plugin contracts, Cardano API utilities and semantic validation. |
| `consensus` | Existing complete runtime app-chain unit/integration suite, including storage, snapshots, observations and effects. |
| `hybrid` | Existing shared transport, Cardano observer/anchor and subsystem lifecycle tests. |
| `plugins` | Existing catalog, provider, lifecycle and plugin operations tests. |
| `adapter` | Application startup configuration and unavailable Cardano HTTP operations. |

The `host` gate uses [japicmp](https://siom79.github.io/japicmp/CliTool.html) 0.26.5
to compare the old core API JAR against the combined extracted API artifacts.
The five current owners are `yano-node-api`, `yano-host-api`, `yano-cardano-api`,
`yano-x-appchain-api` and `yano-x-appchain-cardano-api`. The retired
`yano-core-api` aggregate is not published by the current build. Its historical
JAR remains the frozen compatibility baseline; removing its current Maven
coordinate does not retire the existing public Java signatures or plugin fixtures.
Binary and source incompatibilities fail the build; missing classes are not ignored. The
baseline is `org.yanoproject:yano-core-api:0.1.0-pre19`, SHA-256
`e8cc29db9c9e36dd743c9ae81bd42e806a4035e87d090d392af017c5f12554e9`.
An explicit `-PappLedgerApiBaseline=/path/to/the/same.jar` avoids repository
resolution, but must match that hash. This baseline came from the local Maven
artifact; remote publication provenance has not been established. Do not silently
refresh it with each release. Baseline/comparator/current-API hashes accompany
the XML compatibility report.

The committed pre19 plugin fixture is **not recompiled** by tests. Source and
compiler/artifact hashes live in `fixtures/pre19/`; its JAR is under
`runtime/src/test/resources/app-ledger/compat/`. It exercises old config and
service calls through two actual host lifecycle cycles. Duplicate API definitions
or moved wire resources also fail the host gate.

Every gate also rejects the retired aggregate in the current runtime dependency
closure or any freshly generated Maven publication POM, including the starter
and BOM. Artifact reports include the generated POM hashes. Baseline-only
configurations are excluded from this current-dependency check.

The seven generic configuration/plugin/crypto suites run in `node-api`; the nine
Cardano role/model/utility suites run in `cardano-api`. The configuration gate
requires every relocated suite by name. Application-ledger operating-profile,
codec, wire-vector and domain API model tests belong to X's `framework:api` and
`framework:cardano-api` modules and run through `frameworkCheck`. Yano's
standalone gate retains the real host assembly, consensus and restart checks.

## Exact coordinated inputs

The catalogs pin published Yaci `0.5.0-pre16-f57fc88-SNAPSHOT`, containing
the merged [Yaci #245](https://github.com/bloxbean/yaci/pull/245). The draft also requires the extracted
framework from [Yano-X #102](https://github.com/bloxbean/yano-x/pull/102). Consume
exact staged/published artifacts. No sibling checkout or build invocation is
required. Never overwrite a released coordinate.

```sh
python3 qa/app-ledger/run.py --gate standalone \
  --gradle-arg=-PappLedgerFrameworkRepository=file:///absolute/framework-maven-repository \
  --gradle-arg=-PappLedgerFrameworkVersion=EXACT_FRAMEWORK_VERSION
```

Yaci resolves from the configured public BloxBean snapshot repository. The
optional `artifact-inputs.gradle` override remains available for deliberate
upstream-candidate testing; it is not needed for the pinned snapshot. Maven Local
is disabled by default and, when enabled with `-PuseMavenLocal=true`, is limited
to Yano project artifacts.

Replace local draft candidate pins with final published pins before release
qualification. X's own `frameworkCheck` and `qa/framework/README.md` test its
API/config/core with external host artifacts. Its resolved dependency guard
rejects Yano implementation and settlement SDK dependencies, including transitive
ones. Deliberate transitive-runtime and removed-plugin-API probes have verified
that these guards reject violations.

## Packaged standalone check

Build with the same exact artifact inputs, then run the three-process gate:

```sh
./gradlew :app:quarkusBuild # include the same -I/-P artifact inputs
bash qa/app-ledger/packaged-cluster.sh \
  "$PWD/app/build/yano.jar" /absolute/new/cluster-output
# The same gate accepts a packaged native executable; JVM remains the default.
bash qa/app-ledger/packaged-cluster.sh \
  /absolute/native-distribution/yano /absolute/new/native-cluster-output native
```

Use the actual runner filename emitted by Gradle. This check uses ports
17771–17773 and 23441–23443 (overridable via `HTTP_A/B/C` and `N2N_A/B/C`), refuses
occupied ports, and stops only its own tracked processes. It verifies follower
submission, three-node block identity/root agreement, protocol-103 late joining,
graceful restart/catch-up, HTTP 503 for unavailable Cardano operations, and no L1
chainstate. Logs, proofs, block responses, execution mode and the tested artifact digest are retained.
JUnit additionally verifies certificate signatures/evidence; the HTTP check does
not substitute a signature count for cryptographic validation.

X's `qa/framework/host-matrix` checks independently packaged oldest/current hosts
with unchanged compiled fixture bytecode, exact artifact hashes, and separate
standalone and Cardano-construction profiles. These focused gates are not a
complete release qualification. Run one final `qa/release-qa.sh --build` cycle
plus that compatibility matrix after both phases are integrated, covering both
standalone and Cardano-integrated deployments.

For a fresh environment, follow [the coordinated artifact-staging recipe](COORDINATED_BUILD.md)
before running the draft gates. It breaks the bootstrap into independent builds
and records which unpublished candidate versions were actually exercised.

The host gate also compares public `runtime.appchain` signatures against frozen
`yano-runtime:0.1.0-pre19` (SHA-256
`d99de754c54816a01b263bf02a4555befeb6623dc3cc633ac19b4299480293ed`).
Use `-PappLedgerRuntimeBaseline=/absolute/yano-runtime-0.1.0-pre19.jar` when
supplying that baseline directly. The harness archives both runtime ABI XML and
artifact provenance JSON and fails if either is absent. Lifecycle failure tests
and ownership checks include the newly separated Cardano API and kernel contracts.


The host gate also executes the pre19 runtime-client fixture. Revision 2 corrects
an assumption in the original fixture: the checksum-pinned pre19 runtime already
allows `stop()` to return before engine cleanup completes, so an immediate restart
can report that the previous callback is still draining. Only the two post-stop
starts retry that exact chain-specific refusal, for at most five seconds using a
monotonic deadline; other failures, causes, suppressed failures and interruption
still propagate. Initial startup and all compatibility assertions are unchanged.
The original fixture failed once in the 573-test consensus gate after passing the
focused host gate; this correction does not change production shutdown semantics.

Revision 2 was compiled once against only the same eight checksum-verified pre19
inputs. Its source and old/new hashes are recorded in
`fixtures/pre19/runtime-client-provenance.json`; the original source and JAR remain
in Git at `dc82788aa`. Ordinary test runs never recompile the fixture. The separate
oldest-supported-host matrix and pre19 plugin fixture remain unchanged.
The ownership guard checks both
runtime/manager pairs and the two checked validator resources. Required embedded
activation tests cover disabled optional plugins, restrictive policy, library
fallback, malformed optional providers and packaged/native index requirements.

Deferred assembly tests cover both runtime modes: every plugin finishes `init()`
before framework factories run, and any later initialization failure prevents
factory construction. They guard the ordering contract alongside lifecycle and
configuration compatibility, rather than relying only on successful startup.

The [Phase 2 validation record](PHASE2_VALIDATION.md) records final candidate inputs,
passed gates, packaged checks, review fixes and the remaining coordinated release gate.
