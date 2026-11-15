# Issue 245 external review follow-up

This is incremental review and local integration validation. The historical
[Phase 2 results](PHASE2_VALIDATION.md) remain tied to their original inputs;
they do not claim to cover these later changes. Final coordinated release
qualification still runs once after both phases and production pins are ready.

## Review disposition

- [Yano review](https://github.com/bloxbean/yano/pull/246#issuecomment-6875815179):
  reject all known Cardano network identities using the existing Yaci network
  inventory plus Yano's devnet default; reuse unavailable Cardano facades per
  assembly; validate standalone transport using effective per-chain settings;
  wrap the identified long lines.
- The reported retry-after-failed-start regression is not a change introduced by
  extraction. The previous plugin manager and kernel already made a failed start
  terminal. Regression tests preserve that behavior and demonstrate recovery by
  constructing a fresh node over the same stores in both modes. Clean stop/start
  remains supported. Review did uncover a separate integrated-mode defect:
  required framework plugins must start even when optional plugins are disabled.
  A real Cardano assembly test checks start/stop/restart and exactly one managed
  L1 block/rollback subscription per running cycle, with no optional callbacks.
- [X review](https://github.com/bloxbean/yano-x/pull/102#issuecomment-6875815480):
  remove the unused second protocol-registration loop. The existing host adapter
  remains the single owner, including cancellation checks.
- [X codec review](https://github.com/bloxbean/yano-x/pull/102#issuecomment-6872251025):
  derive malformed-length/UTF-8 mutation offsets from the canonical domain bytes
  and assert the intended rejection reason, avoiding false-positive tests.
- [Yaci review](https://github.com/bloxbean/yaci/pull/245#issuecomment-6875815754):
  retain the legacy Cardano constructor's list ownership/null-element semantics;
  the new app-only factory alone snapshots its factory list.
- [Yaci handshake review](https://github.com/bloxbean/yaci/pull/245#issuecomment-6872028970):
  validate app-only negotiated version and magic, gate protocol 100/103 traffic
  until negotiation succeeds, and close rejected sessions. Unsigned 32-bit magic
  and large integer decoding must not truncate or alias a valid version.
  Independent review added client-side gating and reconnect reset tests.
  The tests also exposed missing refusal serialization; Yaci now writes the
  standard `[2, reason]` message using its existing reason serializers. Golden
  bytes and wire decoding cover all three refusal reasons.
- [Publication durability review](https://github.com/bloxbean/yano/pull/246#issuecomment-6871976438):
  ADR 063 D7 now explicitly requires future adapters to replay durable finalized
  history with destination-specific progress/outbox state. This change adds no
  generic publisher; existing Cardano anchor reconciliation remains unchanged.

The unavailable upstream Yaci release/pin is deferred at the owner's request.
Local verification uses distinct immutable candidate coordinates in file-backed
Maven repositories; no released version is overwritten in Maven Local.

## Validation

The review candidate uses Yaci `0.5.0-pre15-app-only-245.3` (commit
`39901ea8`), X framework `0.1.0-245.10` (implementation commit `7edfc187`),
and the unchanged Host API `1.0.0-245.4`. Final Yano host/foundation artifacts
are `0.1.0-245.7`, built from clean worktree commit `36b161490`. X remains
compiled against node API `0.1.0-245.4`. These are unpublished candidates staged
in isolated file-backed Maven repositories, not public release baselines. The final
package SBOM also records Yaci `.3` and X core/Cardano/host-adapter `.10`,
confirming these local artifacts were selected in the packaged build.

- Yaci: 111 passed, four existing skips; independent cross-review approved the
  client/server gates and refusal wire encoding.
- X: 409 framework/proof tests passed, 60 existing opt-in compiler skips;
  dependency boundaries and artifact inventory passed.
- Frozen oldest host `0.1.0-245.5` / Host API `.4`: both standalone and Cardano
  construction profiles passed with X `.10` / Yaci `.3`, using unchanged client
  bytecode and host bytes. Evidence is retained in X's supported-host fixture as
  `independent-framework-10.json`.

Yano gates before the final native filter correction (overlapping suites, not
an additive total; the test column includes skips):

| Gate | Result | Tests | Skipped |
| --- | --- | ---: | ---: |
| host | PASS | 82 | 0 |
| standalone | PASS | 13 | 0 |
| hybrid | PASS | 61 | 0 |
| plugins | PASS | 258 | 1 |
| adapter | PASS | 24 | 0 |
| configuration | PASS | 419 | 0 |

Both pre19 ABI comparisons and unchanged compiled compatibility clients pass.
Source fingerprints were unchanged during every gate. Full local evidence is in
`/private/tmp/yano-245-review-gates-2`.

An initial new lifecycle test incorrectly expected a subsystem-only status field
on the public engine gateway and did not enable sequencing. The corrected fixture
uses the public running status, enables sequencing, and preserves exact managed
L1 subscription counts across two start/stop cycles. Production behavior was not
changed to accommodate the fixture. Failed attempts remain in separate logs.

## Final native correction and packages

The first native standalone run on candidate `.6` failed before readiness:
Quarkus detected that `CardanoAvailabilityFilter.mode` had been injected as
`cardano` during image initialization although runtime configuration requested
`app-ledger`. Commit `36b161490` injects `jakarta.inject.Provider<String>` and
reads it only during a request, preserving the `cardano` default and the existing
503 response. This follows the standard
[MicroProfile Config dynamic-injection contract](https://download.eclipse.org/microprofile/microprofile-config-3.1/apidocs/org/eclipse/microprofile/config/inject/ConfigProperty.html);
it does not suppress the check with `StaticInitSafe`.

After that correction, the 24 focused adapter tests (22 producer and two filter
tests) passed with no skips in 30 seconds. The six gates above were not rerun
wholesale after this isolated filter change. Instead, fresh native and JVM
packages from the clean final commit were built and exercised below.

| Final build/check | Result | Evidence |
| --- | --- | --- |
| Yano native distribution | PASS, 3m 39s | `yano-245-review7-native-build.log` |
| Yano JVM distribution | PASS, 36s | `yano-245-review7-jvm-build.log` |
| Native packaged standalone cluster | PASS | `yano-245-review7-native-packaged-cluster/results.json` |
| JVM packaged standalone cluster, default invocation | PASS | `yano-245-review7-packaged-cluster/results.json` |
| X host `.7` distribution check | PASS, 1m 18s, 199 tasks | `yano-x-245-review-host7-distribution-check.log` |
| Haskell sync, JVM and native | PASS, 8m 40s / 8m 38s | `yano-245-review7-haskell-sync/report.md` |
| Full X distribution smoke | PASS, 16 steps, none skipped | `yano-x-245-review-host7-release-smoke.log` |

All evidence paths in this section are under `/private/tmp/`. Both packaged
cluster runs used the same committed harness and assertions: follower submission,
three-node block/root agreement, protocol-103 late join, graceful restart and
subsequent finality, HTTP 503 `cardano_unavailable`, absent L1 chainstate, and no
runtime/reachability errors. Each exited zero and stopped its owned processes.
These HTTP checks supplement the existing cryptographic unit/integration tests;
they do not replace signature verification.

The final packaged compatibility matrix also passed all four cells with X `.10`
and Yaci `.3`: frozen host/foundation `.5` and current host/foundation `.7`, each
in standalone and Cardano-construction profiles, all using Host API `.4` and the
unchanged compiled client. Elapsed cell times were 42.23s, 9.75s, 38.64s and
8.11s respectively. The frozen baseline is the first supported unpublished
candidate, not an older public release. Cardano construction does not claim L1
network synchronization coverage. Inputs and full results are
`yano-245-review-host5-host7-x10-inputs.json` and
`yano-245-review-host5-host7-x10-matrix/results.json`.

Final artifact SHA-256 values:

| Artifact | SHA-256 |
| --- | --- |
| Yano `.7` JVM JAR | `74fe7bcab25e6734747270826843270b1faa19bef5e1e74e47ed3bec52f706a0` |
| Yano `.7` JVM ZIP | `f380d4fec59175fa383f89e924dc8ea0e13f1e4b965ed86a7bfc5d583e5d6238` |
| Yano `.7` macOS arm64 native ZIP | `69c1582a48564cfd36080e5f3dd6d6ae11ab25d6286e560758f45819113a74de` |
| Yano `.7` native executable | `c9e3f59699cf7d6f0a1de1be40e6e7bfcf1cdee0d85d0b71b129f778f64f3adf` |
| X `.10` JVM ZIP containing host `.7` | `200912a80af378f5cf858da9a7df4ace9d5010ba97e38a32b39bbebfbfdaa62e` |

The X ZIP is 506,251,659 bytes. Yano package hashes are retained in
`/private/tmp/yano-245-review7-build-sha256.txt`; each cluster also retains its
actual executable/JAR checksum in `artifact.sha256`.

## Final X smoke detail

The final X `.10` ZIP containing Yano `.7` passed all 16 smoke steps on macOS
26.0.1 arm64 with Java 25.0.2. No steps were skipped; their recorded durations sum
to 553 seconds. This covers the packaged local JVM distribution, not Docker,
EUTxO, preprod, or remote deployment. The compact report is retained at
`/private/tmp/yano-x-245-review-host7-qualification.json`; it records no listeners
remaining on the 24 reserved smoke ports.

| Step | Result | Seconds |
| --- | --- | ---: |
| archive | PASS | 7 |
| layout | PASS | 1 |
| runtime-acceptance | PASS | 98 |
| stock-outcomes | PASS | 32 |
| showcase-doctor | PASS | 0 |
| showcase-quickstart | PASS | 99 |
| showcase-projection-off | PASS | 0 |
| showcase-run-all | PASS | 130 |
| showcase-verify | PASS | 4 |
| showcase-record-tips | PASS | 0 |
| showcase-restart | PASS | 33 |
| showcase-verify-after-restart | PASS | 4 |
| showcase-persisted-tips | PASS | 0 |
| distribution-config-unchanged | PASS | 0 |
| showcase-stop | PASS | 2 |
| topics-acceptance | PASS | 143 |

## Haskell sync on final binaries

Both existing skill tests passed with Haskell `cardano-node 11.0.1`, no known
issues, failures, timeouts or blocked tests. Total test duration was 17m 18s.
Each sampled block hash matched Yano at the same height; both reached epoch 2.

| Mode | Duration | Final Yano/Haskell slot | Final block | Matching hash samples | Tip delta | Epoch transitions |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| JVM | 8m 40s | 2564 / 2564 | 2455 | 10 / 10 | 0 | 2 |
| Native | 8m 38s | 2563 / 2563 | 2439 | 10 / 10 | 0 | 2 |

Both runs reported zero Haskell error-like lines, zero Yano `ERROR` lines and
zero native initialization errors. Missed producer slots were 109/2564 (JVM)
and 124/2563 (native); the followers still matched the exact produced chain.
All four owned node processes exited and their PID files were removed.

The unchanged harness recorded clean commit
`36b16149028e7a0b7721cab4e304c58942d053da`. Its `version` metadata reads the
checked-in Gradle property (`0.1.0-pre20-SNAPSHOT`); the actual tested binaries
are the explicit `.7` builds identified by the hashes above and the native
artifact path in `meta.json`. No report metadata was rewritten to hide that
distinction. Full report, logs and raw results are in
`/private/tmp/yano-245-review7-haskell-sync/`.

## Reproduction and superseded attempts

`/private/tmp/yano-245-review7-build.sh` records the exact native/JVM Gradle
commands and all `-I`/`-P` artifact inputs. It uses GraalVM
`/Users/satya/.sdkman/candidates/java/25.0.3.r25-nik`, then copies the final JAR
and extracted native distribution into `/private/tmp/yano-245-review7-qa-bin`.
`/private/tmp/yano-245-review7-publish-host.sh` records isolated foundation/host
publication; it deliberately refuses existing candidate directories. Do not
rerun publication over those immutable versions. The earlier six-gate command
is retained in `/private/tmp/yano-245-review-gates-2.sh`; the final filter test
log is `/private/tmp/yano-245-runtime-mode-filter-tests.log`.

The Haskell checks use `--no-build` because the ordinary release harness does not
supply these unpublished artifact overrides. Here it selects the fresh manual
builds above, not arbitrary existing binaries:

```sh
QA_BIN=/private/tmp/yano-245-review7-qa-bin \
HASKELL_NODE_DIR=/Users/satya/work/bloxbean/yano/test-data-dir/haskell-node \
  bash qa/release-qa.sh --no-build \
  --only haskell-sync-jvm,haskell-sync-native --out /absolute/new/haskell-results
```

The packaged cluster commands are documented in [README.md](README.md). X's
isolated resolver inputs are in the matrix JSON above; its distribution override
script is `/private/tmp/yano-x-245-review-distribution-inputs.gradle`. Final X
logs are `/private/tmp/yano-x-245-review-host7-distribution-check.log` and
`/private/tmp/yano-x-245-review-host7-release-smoke.log`. Final Haskell logs and
metadata are in `/private/tmp/yano-245-review7-haskell-sync/`, with console output
at `/private/tmp/yano-245-review7-haskell-sync-console.log`.

From the X checkout, the final distribution/smoke commands were:

```sh
./gradlew --no-daemon --no-watch-fs --no-parallel --console=plain distributionCheck \
  -Pversion=0.1.0-245.10 -PyanoVersion=0.1.0-245.7 -PhostApiVersion=1.0.0-245.4 \
  -PyanoRepository=/private/tmp/yano-245-host-artifacts \
  -PinternalRepository=/private/tmp/yano-x-245-review-host7-distribution-stage \
  -PyanoJvmDist=/private/tmp/yano-245-phase2/app/build/distributions/yano-0.1.0-245.7.zip \
  -PskipSigning=true -I /private/tmp/yano-x-245-review-distribution-inputs.gradle
.agents/skills/smoke-yano-x-release/scripts/smoke_release_zip.sh \
  /private/tmp/yano-x-245-review-host7-smoke-artifacts/yano-x-jvm-0.1.0-245.10.zip \
  --http-base 29770 --server-base 29370 \
  --work /private/tmp/yano-x-245-review-host7-release-smoke --keep
```

The `.6` native failure remains at
`/private/tmp/yano-245-review-native-packaged-cluster/a.log`. The obsolete `.6`
Haskell run was interrupted and has **no qualification verdict**; its native
case never started. Its disposition is retained in
`/private/tmp/yano-245-review-haskell-sync-0.6-aborted/ABORTED.md`. The earlier X
smoke targeting `.6` was likewise aborted/superseded, not a pass; its partial
logs remain at `/private/tmp/yano-x-245-review-release-smoke.log`. Only the final
`.7` outcomes above may qualify the corrected binaries. None of this replaces
the single comprehensive coordinated release-qualification cycle after both
phases and final production dependency pins are integrated.
