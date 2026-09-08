# Pinned versions

Every SDK here is pinned **independently of Yano's own `gradle/libs.versions.toml`**.
That is deliberate: the suite exists to answer "does the version an external
integrator resolves still work against Yano?". Pinning to Yano's catalog would make
a Yano dependency bump silently redefine what "compatible" means, and would test a
snapshot no dApp author has.

| Stack | Artifact | Version | Where pinned |
| --- | --- | --- | --- |
| CCL | `com.bloxbean.cardano:cardano-client-lib` | `0.8.0-pre5` | `ccl/build.gradle` (`cclVersion`) |
| CCL | `com.bloxbean.cardano:cardano-client-backend-blockfrost` | `0.8.0-pre5` | same |
| MeshJS | `@meshsdk/core`, `@meshsdk/core-csl` | `1.9.1` | `mesh/package.json` + lockfile |
| Evolution | `@evolution-sdk/lucid` | `2.0.1` | `evolution/package.json` + lockfile |
| BLS contract | `com.bloxbean.cardano:julc-*` | `0.1.0-pre16` | `contracts/bls/build.gradle` |

Notes:

- The repo catalog currently carries `cardano-client-lib = 0.8.0-pre5-dev1`. The
  trailing `-dev1` is a snapshot Yano compiles against; this suite deliberately uses
  the released `0.8.0-pre5`.
- Both npm lockfiles are committed. Without them "SDK compatibility" would mean
  something different on every run as transitive dependencies drift.
- Java toolchain is 25, matching the repo. `contracts/bls` requires it regardless:
  julc `0.1.0-pre16` is Java-25-only.

## Bumping a version

Bump one stack at a time and re-run the full suite, so a new failure is attributable.
If the bump fixes a row in `KNOWN-FAILS.md`, the runner reports `UNEXPECTED-PASS` —
delete the row in the same commit as the bump.
