# Concrete API ownership cleanup validation

This is incremental development validation for Yano #245, not the final coordinated release qualification.

## Scope and inputs

Yano production/build cleanup: `dc82788aa`; fixture-only corrections: `7c8a3674e` and `a23af715f`.
X cleanup: `7b806ed9`. Package qualification uses Yaci `.3`; the subsequent
wire-shape fix `3d675058` and separate `.4` checks are recorded below.

Immutable local inputs: Yano/foundation `0.1.0-245.8`, X `0.1.0-245.11`, Yaci core
`0.5.0-pre15-app-only-245.3`, and unchanged Host API `1.0.0-245.4`.
X still compiles against node/Cardano API `0.1.0-245.4`. The oldest supported
host/foundation remains `0.1.0-245.5`; these candidates are unpublished artifacts.

The empty `yano-core-api` aggregate is removed from the module graph, BOM, starter
and consumer dependencies. This is an intentional preview Maven-coordinate change.
`UtxoFilterContext`, `StorageFilter`, `PluginContext` and configuration contracts
retain their node-api ownership; Cardano APIs keep their existing module/name.
The ownership cleanup changes no ledger runtime logic, public Java API signatures,
consensus rules or persisted encodings.
All 69 former Java test/support files have one owner (7 node, 9 Cardano, 53 X).
Six CDDL files and both original resource fixtures retain their bytes. The topic
generator follows its X-owned vectors; its output still matches the golden bytes.

## Latest passing gate executions

| Gate | Cases | Skipped | Result |
| --- | ---: | ---: | --- |
| host | 82 | 0 | PASS |
| standalone | 10 | 0 | PASS |
| adapter | 24 | 0 | PASS |
| consensus | 573 | 2 | PASS |
| configuration | 196 | 0 | PASS |
| hybrid | 61 | 0 | PASS |
| plugins | 258 | 1 | PASS |

Counts are per gate and overlap. These are the latest passing executions, not a
claim that the initial all-gates run passed. The original six passing gates use
`dc82788aa`; host was repeated at `7c8a3674e`, and consensus at `a23af715f` after
the test-only corrections described below. The serial consensus sweep completed
in 9m 37s with no failures or errors. Source fingerprints remained unchanged
through every run. Both checksum-pinned pre19 API/runtime ABI comparisons and
the precompiled plugin/runtime client checks pass.

Final consensus source SHA-256: `84b78d11a7f401763565850c8b64f5ca89fba140cb76d27e68146ac26e8c31d2`.

Focused observation regression: six cases, five passed and one existing live-HTTPS skip. The isolated classic-JMT case also passed unchanged.

## Fixture correction discovered by the wider gate

The initial seven-gate run retained unchanged source throughout. Six gates passed;
the consensus gate ran 573 cases with one failure and two existing skips. The
failure was the frozen pre19 client's immediate restart after an asynchronous stop.
The earlier host gate had passed the same bytecode.

Inspection of the exact checksum-pinned pre19 runtime confirmed the same drain
refusal and asynchronous engine shutdown already existed. A one-time fixture
revision retries only that exact chain-specific refusal after the two explicit
stops, for at most five seconds each. Initial startup, all persistence/API assertions
and every other failure remain unchanged. It was compiled only against the eight
original SHA-verified pre19 inputs, with no current project classes or sources.
Two review agents checked the semantics, hashes and retained legacy method references.

The original source/JAR and provenance remain in Git at `dc82788aa`; revision 2
records both original hashes and the reason in `fixtures/pre19/runtime-client-provenance.json`.
New fixture SHA-256: `af4366481b80107aba275a02d25fb16b32b8e0258714fa6f951aedc0f81e47e7`.
Ordinary gate runs still never compile this fixture. The precompiled plugin and
oldest-supported-host matrix fixtures remain unchanged. The original failed report
is retained under `/private/tmp/yano-245-cleanup8-gates`; it is not relabeled as a pass.

The corrected full run passed the revised pre19 client but exposed another test
precondition: the five-node omission fixture forced a two-second consensus round
while requiring its initially configured proposer to remain leader through setup.
Logs show a legitimate view change and a different proposer before the injected
omission assertion. The first full run had passed that case. Two reviewers
confirmed this was an artificial fixture timeout, not a production change.
Commit `a23af715f` removes that override and uses the existing ten-second production
default. All proposer, certificate, omission and persistence assertions, plus the
sixty-second recovery bound, remain unchanged. The failed concurrent-run evidence
is retained under `/private/tmp/yano-245-cleanup8-corrected-consensus`.

A later full run passed both corrected fixtures but timed out while the existing
classic-JMT test waited for initial members to connect. Logs show distinct ports,
successful binds, one successful peer handshake and the reverse TCP connection
without a corresponding server-side channel initialization. This occurred before
handshake dispatch. No production or timeout change was made: the identical focused
classic-JMT test subsequently passed (24 seconds including Gradle). The earlier
failure remains in `/private/tmp/yano-245-cleanup8-final-consensus`, with additional
binary/text diagnostics under `/private/tmp/yano-245-classic-jmt-*`. The subsequent serial sweep completed that case normally in about fourteen
seconds; thread/socket diagnostics are retained in
`/private/tmp/yano-245-cleanup8-serial-classic-diagnostics`. A later pass does not establish the cause
of this intermittent connection failure or retroactively turn that run green.

## Dependency and tooling checks

The current runtime resolves 118 artifacts and the gate checks 28 generated POMs,
including the BOM and starter, for retired aggregate references. An intentional
POM-only injection failed with the expected obsolete-dependency error; regenerating
without it passed. No published staging artifact was modified by that probe.
Independent review checked all 23 staged publication POM/module pairs: their
dependency sets match and none references `yano-core-api`.

X framework/boundary checks passed: 469 cases, 409 passed and 60 existing opt-in
compiler skips. Devtools (209), CLI (49), evidence client (15) and contracts (39)
passed. These counts are per suite and may overlap other gates.
Scaffolding selects host and X versions independently while preserving `--yano-version`.
An isolated template build using published host/X artifacts passed six tests plus
real catalog loading. It exposed and corrected stale template-only API-level,
execution-context and digest-mode expectations; no runtime behavior was changed.

Yano documentation generation, Astro checks and static-site build/link verification
passed with the unchanged lockfile. Review agents checked dependency direction,
fixture/specification ownership and the final harness correction.

## Packaged and independent-host validation

Fresh Yano native/JVM builds passed in 4m 50s / 47s. Both actual packages passed
the three-process standalone gate: follower submission over protocol 100,
protocol-103 late joining, matching block/root identity, threshold certificates,
graceful restart and new finality, HTTP 503 for unavailable Cardano APIs, and no
L1 chainstate. All owned processes were stopped.

| Artifact | SHA-256 |
| --- | --- |
| Yano .8 JVM JAR | `7c6e0bcc86bfd44481a1581bd72dcba2e91dc00794249b630c34a89f94f94d38` |
| Yano .8 JVM ZIP | `e8671edd3dd5f05285abee96b44a54ef04df287fdb9c445f8d8f7977ff802f2a` |
| Yano .8 native ZIP | `fdae9ce704faac562a3a7a8b6e2db90bbd13987797a76f2c6a3e38da1151bc64` |
| Yano .8 native executable | `b6536148b20732ef906a5ee598fd1e2d9dfd4224b1fd5eb515c839724883d3ee` |
| X .11 JVM ZIP | `31e25144684d3f471a96de2134eb142ec89a09c6d2a737cfdf5dc0f76b8ddcf6` |

X `distributionCheck` passed all 199 tasks (3m 16s). The full packaged smoke
passed all 16 steps with no skips (832 summed step seconds), including live
Cardano anchoring, stock applications, the 13-chain showcase, cross-node roots,
restart/persisted-tip checks, unchanged distribution configuration and topics.
All 24 reserved ports were released. These packages use exact Yaci `.3`.

The unchanged oldest-host fixture passed standalone and Cardano-construction
profiles on both host `.5` and `.8` with X `.11` / Yaci `.3`: four passing cells.
The former retains historical core-api; both current host profiles omit it.
Fixture SHA-256 remains
`fa6b31460535985d388734c2c04036d27f5a6644aa81c3984ddb472b952f1770`.
The construction profile is not a claim of live L1 synchronization; the X smoke
separately covers live local Cardano anchoring.

[Retained X qualification and matrix records](https://github.com/bloxbean/yano-x/tree/feat/245-app-ledger-framework/qa/framework/host-matrix/fixtures/supported-host)
include exact artifact hashes, loaded origins, step results and platform details.

## Subsequent Yaci wire-shape review

[The new review comment](https://github.com/bloxbean/yaci/pull/245#issuecomment-6877562379)
identified two malformed V101 acceptances normalized into successful handshakes:
a missing query field and an oversized tag that truncated to 1. Both failures were
reproduced through the actual streaming/client pipeline before fixing Yaci.
Commit `3d675058` checks exact tags and V101 envelope/data lengths and field types
before defaults/narrowing, shared by acceptance and proposal decoding. Valid
legacy decoding and public signatures are unchanged. Raw-wire regressions also
ensure malformed/coalesced traffic cannot release protocols 100/103.

Full Yaci build: 1,071 cases, 1,046 passed and 25 existing skips, no failures.
Independent review approved the boundary and coverage. New core candidate
`0.5.0-pre15-app-only-245.4` has SHA-256
`46474bf493e6efefa812cb06ce8d4ceb9a6449270fb535eebe2ed661e523d767`.
Previous immutable candidate files were unchanged.

All four oldest/current host matrix profiles pass with Yaci `.4`, unchanged
host/foundation `.5`/`.8`, X `.11`, Host API `1.0.0-245.4` and frozen fixture bytes.
No framework or host rebuild was required for this artifact-only matrix.
[Retained .4 matrix evidence](https://github.com/bloxbean/yano-x/blob/feat/245-app-ledger-framework/qa/framework/host-matrix/fixtures/supported-host/host-5-and-8-framework-11-yaci-4.json)
records all inputs and loaded origins. The earlier integrated packages/full smoke
recorded here still use Yaci `.3`; they are not relabeled as packaged qualification of `.4`.
Final coordinated release qualification must use the final available dependency pins.

## Qualification boundary

The preceding JVM/native Haskell-sync results remain recorded in `REVIEW_FOLLOWUP.md`
for their original `.7` inputs. Haskell sync was not rerun for this aggregate-only
cleanup. Fresh `.8` package and X smoke results recorded here cover the changed dependency
assembly. Live public-network sync and Docker qualification are not claimed here.

Available coordinated dependency pins and one comprehensive Yano release-QA cycle
remain release gates after both phases are integrated. No intermediate release,
public Maven publication or tag was created.

## Local evidence and reproduction

- `/private/tmp/yano-245-cleanup8-gates`: original all-gate reports, including the first failed fixture.
- `/private/tmp/yano-245-cleanup8-corrected-host`: final host/ABI checks.
- `/private/tmp/yano-245-cleanup8-corrected-consensus`: retained short-round fixture failure.
- `/private/tmp/yano-245-cleanup8-final-consensus`: retained connection timeout.
- `/private/tmp/yano-245-cleanup8-serial-consensus`: final passing full consensus gate.
- `/private/tmp/yano-245-cleanup8-observation-focused.{log,xml}` and `classic-jmt-isolated.{log,xml}` (same prefix): focused regressions.
- `/private/tmp/yano-245-cleanup8-{jvm,native}-cluster`: packaged evidence and hashes.
- `/private/tmp/yano-x-245-core-api-cleanup-release-smoke`: complete packaged logs and responses.
- `/private/tmp/yano-245-wire-shape-host5-host8-x11-matrix`: additional Yaci `.4` matrix.

Use the existing [staging recipe](COORDINATED_BUILD.md), [Yano harness](README.md)
and X matrix/smoke instructions with the exact versions above. Example focused
gate invocation (change `consensus` to another named gate or `all`):

```sh
python3 qa/app-ledger/run.py --gate consensus --out /absolute/new-report \
  --gradle-arg=-I --gradle-arg=qa/app-ledger/artifact-inputs.gradle \
  --gradle-arg=-PappLedgerYaciRepository=file:///absolute/yaci-stage \
  --gradle-arg=-PappLedgerYaciVersion=0.5.0-pre15-app-only-245.3 \
  --gradle-arg=-PappLedgerFrameworkRepository=file:///absolute/framework-stage \
  --gradle-arg=-PappLedgerFrameworkVersion=0.1.0-245.11 \
  --gradle-arg=-PhostApiVersion=1.0.0-245.4 \
  --gradle-arg=-Pversion=0.1.0-245.8
```

The example reproduces the package/consensus qualification's `.3` input; use the
separate retained matrix manifest to reproduce the subsequent `.4` wire fix.
