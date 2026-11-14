# Reviewer1 re-review: published Yaci and restart/CI boundaries

Follow-up to [Yano review](https://github.com/bloxbean/yano/pull/246#issuecomment-6878653349)
and [Yano-X review](https://github.com/bloxbean/yano-x/pull/102#issuecomment-6878653710).
Earlier qualification records retain their original inputs and results.

## Published Yaci input

Both version catalogs select `0.5.0-pre16-f57fc88-SNAPSHOT` from
`https://repo.bloxbean.org/maven/snapshots`. Successful publication
[43602913332](https://github.com/bloxbean/yaci/actions/runs/43602913332) used
`f57fc883852d81089340741abaf1e5dd99da6f26` on `next`, which contains merged Yaci
PR #245 including the strict malformed-wire fix `3d675058`.

The repository resolves timestamped core artifact
`0.5.0-pre16-f57fc88-20261114.141754-1`. Its SHA-256 is
`46474bf493e6efefa812cb06ce8d4ceb9a6449270fb535eebe2ed661e523d767`:
**the entire JAR**, not only its 794 class files, is identical to the previously
validated local `0.5.0-pre15-app-only-245.4` candidate. This establishes byte
identity; it does not relabel older Yaci `.3` package runs as tests of the new
snapshot. No local Yaci override is needed for current builds.

X's core declares its direct Yaci dependency because it calls `appOnly`. Merely
changing the version catalog was insufficient when compiling against the frozen
older node API, which still requests pre15. The published core POM now carries
the new minimum explicitly, with the same Cardano transaction-library exclusion
as the node API. No global dependency override is needed.

Yano now disables Maven Local by default, matching X's explicit opt-in policy.
`-PuseMavenLocal=true` permits only Yano project groups, so a locally republished
Yaci coordinate cannot mask the public dependency. Fresh API and P2P compilation
with `--rerun-tasks --no-build-cache` resolved the public snapshot successfully.

## Review dispositions

- Restart readiness is checked inside the kernel's serialized transition before
  any subsystem/plugin starts. A bounded pending-cleanup rejection restores
  `STOPPED` and retains ownership for retry. X exposes the existing cleanup
  completion through `AppLedgerManager.awaitStopped(Duration)`; it does not
  introduce another runtime or change direct runtime stop/start semantics.
  Initial startup failures remain terminal, as before.
- Standalone host construction validates effective per-chain transport before
  plugin discovery/initialization, using the existing configuration parser.
  Both host and adapter apply blank/global fallback and case-insensitive mode
  matching. Standalone never decorates shared Cardano sessions.
- The host reuses its validated network magic. The independent framework
  validates numeric range before invoking factories. Known Cardano identities
  remain a host-specific check; no new Host API capability is introduced.
- Cardano HTTP availability resolves deployment mode lazily once, retaining
  native-image runtime initialization behavior.
- CI checks node, Cardano, runtime and independently versioned Host API artifacts
  rather than the retired aggregate. Foundation and framework staging break the
  build bootstrap cycle through verified artifacts, not sibling sources.
  Public publication workflows do not consume temporary qualification pins.
- The isolated plugin template reports missing version inputs explicitly and
  is built through the ordinary CI path. Evidence contracts reject all current
  host/framework API owners in their published POM, not only the retired name.
- The removed `core-api` coordinate is an intentional, owner-approved preview
  migration. It will not be restored as a relocation or aggregate. ADR-063 now
  explicitly states that the new BOM does not evict a separately pinned pre19
  JAR. Consumers must replace direct and transitive references with actual API
  owners; keeping the historical JAR alongside a new host is unsupported.
- Rejecting known Cardano network identities is a configuration guard, not a
  universal registry of private networks. Arbitrary private Cardano magics
  cannot all be known. Exclusive V101 negotiation provides the protocol
  separation even when an unrelated private network chooses the same number.
- The long line in the pre19 fixture is retained. Its source is part of a frozen
  compiled-client provenance record; reformatting it would invalidate that
  record without improving runtime behavior.
- The two pairs of X evidence-client exclusions refer to different coordinates:
  Gradle project names and published Maven artifact names. Both are required;
  they are not duplicate exclusions. The source and published dependency guards
  exercise their respective forms.

## Incremental validation

Local source/runtime inputs are Yano/foundation `0.1.0-245.9`, X framework
`0.1.0-245.12` compiled against unchanged node API `0.1.0-245.4` and Host API
`1.0.0-245.4`, and the public Yaci snapshot. No local Yaci override is used.

- Focused X adapter/cleanup tests: 38 passed. Focused Yano kernel, assembly and
  REST tests: 33 passed. The first new host-query fixture attempt failed because
  its reused setup disabled sequencing; the corrected fixture enables it and
  waits for finality before holding the callback. No runtime behavior was altered
  to accommodate that setup failure.
- Permanent harness: host 102, standalone 12, adapter 25, hybrid 61; no failures
  or skips. Counts overlap with focused tests. Both pre19 bytecode comparisons
  and compiled clients pass. `NodeKernelTest` is now a required host-gate suite.
- X evidence contract/client guards pass. Injecting a forbidden node API into
  the generated contracts POM fails; the original POM is restored byte-for-byte.
- The isolated plugin template passes six tests and its real catalog probe with
  host `.8` and selected X `.12`. A deliberate catalog-only `.11` resolution is
  rejected. This checks the actual chosen runtime pair, not only compilation.
- CI script tests cover 55 importer/property checks, including scope/commit/version
  mismatch, missing split publications, tampering and unlisted required files.
  Actual Gradle probes confirm file defaults < prepared project-property
  environment < explicit `-P`; no custom override mechanism is introduced.
  Producer contract tests verify foundation-only tasks, no staging overwrite,
  original Host API metadata retention and rejection of changed Host API bytes.
- Fresh JVM distribution build passed (99 tasks), and its real three-node
  standalone package passed protocol 100/103, finality, roots, late join,
  stop/restart, unavailable-Cardano HTTP responses and absence of L1 state.
  JAR SHA-256: `7f958da8266eb998f7faec10d00ae72d239cfa646f02317f72498095f80cc513`.
  ZIP SHA-256: `9c3559f05a47c5888b2152475b72f1696d2881adca9048f8c82c20e06051d371`.
- Oldest host `.5` and current `.9` pass all four standalone/Cardano-construction
  matrix cells with X `.12`. The fixture remains
  `fa6b31460535985d388734c2c04036d27f5a6644aa81c3984ddb472b952f1770`.
  The immutable matrix selects public timestamped Yaci
  `0.5.0-pre16-f57fc88-20261114.141754-1`, rather than relaxing its rejection of
  moving `-SNAPSHOT` inputs. Its JAR is the catalog snapshot's verified byte match.
- Clean GitHub foundation staging passed at
  [43605316740](https://github.com/bloxbean/yano/actions/runs/43605316740), commit
  `272157da2cb368fc2b7fc4fd0f8b9d68a8f23340`. It publishes only qualification
  artifacts, with node/Cardano `0.1.0-pre20-272157da2` and independent Host API
  `1.0.0-272157da2`; these are distinct from the frozen local compatibility floor.

Local detailed logs and reports are under `/private/tmp/yano-245-review2`,
`/private/tmp/yano-245-reviewer1-runtime-focused2-evidence`, and
`/private/tmp/yano-x-245-review12-evidence-template-results.json`.

- X `distributionCheck` passes all 199 tasks against the matching new Yano `.9`
  ZIP. The full packaged smoke passes all 16 steps, none skipped (566 summed
  step-seconds), including live Cardano anchoring/observations, stock outcomes,
  showcase root agreement, full restart, persisted tips and topics.
  X ZIP SHA-256:
  `ff5022846aa3c91eabf27bd35669fbabe4692a2ec4b2a5892bea28c4073fd2e6`.
- Clean GitHub framework staging passes at
  [43607120836](https://github.com/bloxbean/yano-x/actions/runs/43607120836),
  commit `2fc43e75944ba9c52b7a8a763155ea851801c23a`, framework version
  `0.1.0-pre6-2fc43e759`. The Yano PR pins this successful run and its full
  commit; scattered local-only framework defaults are replaced by one central
  property matching that verified coordinate.
- The first framework staging attempt failed because realizing the SDK test task
  eagerly resolved an unrelated full-host proof-verifier dependency. Its
  classpath is now resolved when the SDK test executes. The unchanged JVM-only
  guard passes with deliberately nonexistent host coordinates, and all four SDK
  journal/forked-process tests pass. Both normal CI and framework staging retain
  the configuration-isolation probe. A local full `frameworkCheck` also passes
  with only the downloaded foundation repository, without full-host artifacts.

The full X smoke is on macOS 26.0.1 arm64 / Java 25.0.2. It does not claim Docker,
eUTXO, preprod, remote deployment, or a rerun of the native/Haskell qualification.
Those retain their earlier recorded inputs and the final coordinated release gate.

## Integrated catalog qualification

The first clean full-host staging run, [43607629809](https://github.com/bloxbean/yano/actions/runs/43607629809),
compiled and built the distribution, then exposed a stale logging-only catalog
assertion. The integrated deployment requires both logging and app-ledger bundles.
The existing catalog helper now enforces the exact declared bundle IDs, counts
and independent versions in the generated index, packaged index and every
retained manifest. The external conformance fixture remains excluded.

The real packaged JVM catalog smoke passes with the verified remote framework
and public Yaci snapshot. Negative probes reject an extra fixture bundle and a
wrong retained framework version; the original JAR was restored byte-for-byte.
All 10 focused `PluginCatalogPackagingTest` cases pass. That suite is now also
required by the permanent adapter gate. Two independent review agents approved
the production guard and test changes.

## Direct runtime construction follow-up

Clean normal build [43609043058](https://github.com/bloxbean/yano/actions/runs/43609043058)
progressed through 2,089 runtime tests and exposed 32 failures with one cause:
the direct `RuntimeNode` constructor created its owned plugin environment, then
registered the Host API using the shadowing nullable constructor argument.
Packaged assembly supplies an environment and did not exercise this path.
The fix reads `this.pluginEnvironment.providers()`; it changes no lifecycle or
plugin ownership contract. All nine affected suites pass after the fix: 47 tests,
zero failures/errors/skips. Two existing direct-constructor suites are now
required by the host regression gate. Independent review confirmed the cause and
checked constructor ownership for similar mistakes.

The updated adapter gate passes all 35 tests on clean commit `7823bfe21` with
source unchanged. The separate [integration workflow](https://github.com/bloxbean/yano/actions/runs/43609043044)
passes runtime integration, distribution and native-core on that commit; those
passes do not hide the normal-build constructor failure. Successful full-host
staging [43608923020](https://github.com/bloxbean/yano/actions/runs/43608923020)
used the same pre-fix commit; subsequent X input pins must consume a newly staged
host containing the constructor correction.

## Release boundary

The Yaci blocker is resolved by the public snapshot. Yano foundation and X
framework inputs remain coordinated artifacts until their PRs are integrated.
They must be exchanged by exact, verified artifact inputs, never by silently
using another checkout or overwriting a released Maven coordinate. The final
comprehensive Yano release-qualification cycle remains a separate gate after
both phases and final dependency pins are integrated; this work creates no
intermediate release.
