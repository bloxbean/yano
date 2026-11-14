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
