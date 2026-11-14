# Phase 2 development validation

This records incremental verification of both implementation phases, not the final release qualification.

Yano source: `74ae523cd62f2c44b7bb258d3a052afb13743c36`; source SHA-256: `42f9055cd4baca6509af0b4f50a924eea6de4cdddfc17406a33e5a5c5a9b188f`. The harness verified unchanged source throughout all seven gates. The integrated JAR/ZIP were built from that same commit in an isolated worktree.

| Gate | Result | Tests | Skipped |
| --- | --- | ---: | ---: |
| host | PASS | 80 | 0 |
| standalone | PASS | 9 | 0 |
| adapter | PASS | 24 | 0 |
| consensus | PASS | 573 | 2 |
| configuration | PASS | 419 | 0 |
| hybrid | PASS | 61 | 0 |
| plugins | PASS | 258 | 1 |

These suites overlap; their counts are not a count of unique tests. Both core/API and runtime ABI checks passed against checksum-pinned pre19 artifacts. Unchanged precompiled plugin and runtime-client fixtures passed. Required named suites and reports were present.

## Independently built framework and host

- X `0.1.0-245.9`: 400 framework/proof tests passed; 60 existing opt-in Julc source-compiler checks were skipped. Artifact inventory, JVM-only and resolved core dependency-boundary checks passed.
- X was compiled against node API `0.1.0-245.4` and Host API `1.0.0-245.4`; packaged hosts use node/Cardano foundation and host `0.1.0-245.5`. No Host API republishing or matched X/Yano version is required.
- Frozen packaged client: `fa6b31460535985d388734c2c04036d27f5a6644aa81c3984ddb472b952f1770`. Both standalone and optional Cardano-construction profiles passed with X `.9` and the preceding compatible `.8`, using identical host artifacts and unchanged client bytecode. The client was compiled with `.9`; this is not a claim that it was compiled against `.8`.
- Standalone profile: three real TCP nodes, protocols 100/103, common roots/certificates/commitments, late join, restart/reopen, no Cardano state. Cardano profile: real callback record linkage, existing subsystem/gateway and exactly one manager lifecycle hook, constructed and closed without starting a network or persistent stores. This profile does not claim live L1 synchronization or anchoring coverage.
- Baseline and current host are the same first supported candidate and are truthfully deduplicated. Candidate artifacts are unpublished; pre19 is a separate backwards-compatibility fixture, not a Host API host.

The retained client, exact input hashes and compact matrix evidence are in [X supported-host fixtures](https://github.com/bloxbean/yano-x/tree/feat/245-app-ledger-framework/qa/framework/host-matrix/fixtures/supported-host).

## Integrated package

The final Quarkus build and ordinary JVM ZIP passed. Its three-process smoke test passed follower submission, common block/root identity, protocol-103 late joining, graceful restart/catch-up, explicit HTTP 503 for unavailable Cardano APIs, and absence of L1 chainstate.

| Artifact | SHA-256 |
| --- | --- |
| Yano JVM JAR | `91bbb080671e2bb3c1a5b25caffa5445e285de064120c46444b3030c3c282cc3` |
| Yano JVM ZIP | `dbdbe3f79608e5473b110e5ca26998c0ffa172b075e485adcdeea8344e4ae466` |
| X JVM ZIP | `caa12ec303f3b6bf642fe7c1c5f64bb1f72d72d811b22bd1a4926c7be0369356` |
| X plugin pack | `0bbfe17151e6f175db89eb7ba7fd53aaf4d4bc8b55f7c1753b225d6d346303fd` |

## X tooling and distribution

The affected devtools (209), authenticated-map validator (4) and app-chain CLI
(49) tests pass: 262 total, no skips or failures. Tooling consumes the actual X
API/configuration/core owners rather than empty, non-transitive compatibility
aggregates. The source build resolves inventory-owned framework coordinates to
its own projects for every requested version; it never substitutes a Yano host
module or a sibling checkout. The independent artifact matrix is a separate
build and does not use source substitution.

`distributionCheck` passes (199 tasks). It verifies the exact Yano Maven/ZIP
identity, independently versioned Host API, plugin and JVM ZIP packaging, and
showcase distribution contracts. The new resolution gate requests older
framework coordinates, checks actual CLI class ownership, and verifies generated
POMs still contain ordinary Maven coordinates. Duplicate JAR handling remains
strict. Review agents checked both build changes.

## Review and remaining release gate

Independent review agents checked extraction/isolation, lifecycle/initialization ordering, required catalog activation, Maven cycles and distribution version validation. Review fixes preserve all plugin initialization before factories, cancel partial assembly with exactly-once cleanup, and retain strict version alignment while allowing the separately versioned Host API.

The exact Yaci transport input is `0.5.0-pre15-app-only-245`. The staging/QA override does not change publication POMs: final production metadata must name the available upstream Yaci transport release. Replace unpublished X/node/Host API candidate pins with available coordinated artifacts before merging/releasing.

Run **one** final `qa/release-qa.sh --build` cycle after both phases are integrated with final pins, together with standalone-package and oldest/current-host matrix checks. Cover JVM/native L1, app-chain observers/anchors, endpoints, SDK compatibility and Docker. No intermediate Yano release or comprehensive release qualification was performed.

## Local evidence

- `/private/tmp/yano-245-final-phase2-gates`: complete gate results, copied JUnit/ABI reports and artifact hashes.
- `/private/tmp/yano-245-final-package-build.log` and `/private/tmp/yano-245-final-packaged-cluster`: package build, node logs, responses and artifact digest.
- `/private/tmp/yano-x-245-framework9-tests.log`: independent framework suite.
- `/private/tmp/yano-x-245-tools-extraction-tests.log` and `/private/tmp/yano-x-245-final-distribution-check-3.log`: tooling and complete X distribution checks.
- `/private/tmp/yano-245-thin-host-matrix-final-2` and `/private/tmp/yano-245-host5-framework8-probe`: full packaged compatibility reports.

Earlier failed runs are retained separately. In particular, the initial Cardano matrix fixture incorrectly checked the concrete class of a lifecycle wrapper; the corrected fixture checks the stable subsystem name plus actual Cardano gateway and artifact ownership. The runtime was unchanged by that fixture correction.

## Published Yaci snapshot qualification

Both repositories select public `0.5.0-pre16-f57fc88-SNAPSHOT` from the BloxBean
snapshot repository, published by [Yaci run 43602913332](https://github.com/bloxbean/yaci/actions/runs/43602913332).
[Snapshot provenance](yaci-snapshot-provenance.json) records the timestamped
artifact and SHA-256. Its entire core JAR is byte-identical to the previously
validated local Yaci `.4`; older `.3` package tests retain their original inputs.
X core declares the required Yaci API version directly, preserving the lean
Cardano transaction-library exclusion. Both builds disable Maven Local by
default; no local Yaci override is needed.

Incremental runtime qualification used immutable local Yano/foundation `.9`,
X `.12`, unchanged minimum Host API `.4`, and the public snapshot:

| Check | Result |
| --- | --- |
| Host gate, before direct-constructor additions | 102 passed; pre19 ABI and compiled-client checks passed |
| Standalone / hybrid gates | 12 / 61 passed |
| Final adapter gate at `7823bfe21` | 35 passed; required catalog suite included |
| Direct-constructor suites at `ad281a8de` | 47 passed across nine suites |
| Final host gate at `ad281a8de` | 111 passed; source unchanged; pre19 checks passed |
| JVM package, local host `.9` | Three-node protocol 100/103, finality/roots, late join/restart, unavailable Cardano APIs and no L1 state passed |
| Oldest/current matrix, host `.5` / `.9`, X `.12` | All four profiles passed with unchanged compiled fixture and Host API |
| X distribution / packaged smoke, host `.9`, X `.12` | 199 tasks and all 16 smoke steps passed; no smoke skips |

Counts overlap. X smoke includes live Cardano anchoring/observations, cross-node
roots and restart/persistence. [Retained X qualification](https://github.com/bloxbean/yano-x/tree/feat/245-app-ledger-framework/qa/framework/host-matrix/fixtures/supported-host)
contains exact artifact hashes and input identities. These runs do not relabel
earlier native/Haskell qualification or constitute the final release cycle.

Clean CI bootstrap passed [foundation staging](https://github.com/bloxbean/yano/actions/runs/43605316740),
[framework staging](https://github.com/bloxbean/yano-x/actions/runs/43607120836),
and [full host staging at `7823bfe21`](https://github.com/bloxbean/yano/actions/runs/43608923020).
The last includes actual packaged catalog smoke and exact Host API byte/metadata
retention. The [integration workflow at that commit](https://github.com/bloxbean/yano/actions/runs/43609043044)
passed integration, distribution and native-core. Its separate normal build
exposed the direct-constructor nullable-argument bug; `ad281a8de` corrects that
reference and the focused/permanent gates above verify it. The staged `7823bfe21`
host predates that correction and must be superseded by a newly verified host input
pin. See [the coordinated input sequence](../../.github/COORDINATED_INPUTS.md)
for current pins and reproducible checkout/build preparation.

Publicly available foundation/framework coordinates must replace temporary
Actions inputs before merging into a branch expected to publish snapshots or
releases. Actions inputs expire after 90 days. The mutable Yaci snapshot is for
qualification; final release POMs require a released Yaci version. No intermediate
Yano release is required. One comprehensive coordinated release cycle remains
mandatory after both phases and final dependency pins are integrated.

The subsequent pending-versus-failed cleanup distinction passes 42 focused tests
across kernel, both assemblies and direct construction, with no failures/skips.
Ordinary and fatal cleanup failures retain close ownership and reject restart;
a real interrupted pending query remains retryable. Committed input/default
consistency guards pass 27 checks in Yano and 39 in X, including actual Gradle
precedence probes. Current X documentation illustration checks pass all nine
cases with source anchors and ownership zones matching the extracted modules.
