# ADR-028 implementation report

This report records milestone evidence, review findings, and qualification results for
[ADR-028](028-l1-historical-ledger-state-attestation-chain.md). It is updated on the ADR integration
branch after each milestone.

## M1 — epoch-boundary observation wire

Status: complete

Implemented:

- replaced the transaction-only preview observation pointer with a closed, tagged anchor family;
- defined canonical transaction (`tag = 0`) and epoch-boundary (`tag = 1`) CBOR forms;
- added CDDL, public golden vectors, defensive value semantics, canonical decode checks, and malformed-input tests;
- migrated transaction observers and consumers to require a transaction anchor explicitly;
- made the clean break visible by advancing app-block format `1 -> 2` and plugin API `2/3 -> 3/4`;
- advanced every in-tree plugin manifest and the plugin scaffolder, and added rejection coverage for old app blocks,
  old observation bytes, and old plugin manifests.

Review and iteration:

- Java record equality is reference-based for array components, so the first golden-vector test exposed that
  decoded observations were not value objects. Explicit content equality/hash implementations now cover the
  observation and transaction anchor.
- A bridge regression asserted the old untagged observation key. The stable key now includes `tx:` or `epoch:`
  so anchors from the two domains cannot collide.
- Certified-proof and catalog tests contained literal preview API/block versions. They now use the current block
  marker where appropriate, while compatibility tests explicitly exercise the rejected old marker.

Verification:

- all main and test sources compile (`compileJava compileTestJava`);
- `:core-api:test` and `:runtime:test` pass;
- `:plugin-catalog:test` passes, including old plugin API rejection;
- the certified-proof profile test passes with app-block v2;
- EUTxO ledger, Cardano bridge, ZK testkit, devtools, and plugin-conformance suites pass.

One unrelated HTTP direct-connection test could not acquire a local socket while the workstation's ephemeral
port range was exhausted by pre-existing long-running Gradle jobs. Its neighboring proof suite passed and this
environmental test will be rerun during later milestone/full-build qualification after the sockets age out.
