# ADR-030 Phase B plugin seam evidence

**Status:** Merged into the integration branch as `03100b34`.
**Captured:** 2026-08-11
**Integration branch:** `feat/030_yano-x_reafactoring`

## Implemented boundary

Phase B makes optional app-chain runtime behavior load through the manifested plugin catalog while
keeping the repository physically intact for history-preserving extraction:

- `settings.gradle -PleanYanoBuild=true` configures only the 22 retained or temporarily retained
  projects from the allocation manifest; the full monorepo currently contains 74 projects.
- The lean `app` runtime graph contains no project allocated to Yano X. Its packaged plugin catalog
  contains the retained logging lifecycle plugin and the built-in OrderedLog engine only.
- Every downstream runtime extension is a dependency-complete JVM bundle assembled by the shared
  bundle convention. Bundle dependencies are declared in manifests and staged as an explicit
  closure; host API classes are not duplicated inside extension bundles.
- The checked conformance matrix covers 18 runtime bundles. Each bundle is catalogued and activated
  from an isolated plugin directory through its real provider types.
- Canonical host configuration is `yano.plugins.*`. The unreleased `yaci.plugins.*` spelling is a
  bounded migration alias with conflict detection and warnings, not a second configuration model.
- Product-owned CDI resources and construction paths were removed from the host. Local read models,
  privileged system messages, L1 transaction building, domain APIs, health, and metrics use typed,
  bounded host SPIs.
- Product-specific raw `ServiceLoader` activation was removed. Catalog ownership now governs
  selection, contribution provenance, lifecycle, and reverse-order cleanup.
- `appchain-client` was split from the retained proof verifier, product effect fixtures moved out of
  the retained core testkit, and the runtime test suite no longer depends on appchain stdlib.
- Reusable host E2E support is published through `app` test fixtures. The downstream eUTxO E2E
  module owns its product profiles/probes and consumes the fixture artifact through its Quarkus
  application model rather than appending another project's test output to the runtime classpath.

The metrics SPI intentionally exposes plugin metrics through the host's bounded generic metric
surface instead of retaining unreleased product-specific host bindings. This is an application-seam
cleanup under ADR-030 §1.2, not a compatibility shim.

## Regression evidence

The final command results for this feature branch are recorded here before maintainer review:

| Gate | Result | Evidence |
|---|---:|---|
| Runtime plugin conformance matrix | PASS | 18 checked bundles and their dependency closures activated from isolated plugin directories |
| Lean configured project inventory | PASS | 22 retained/temporary projects; allocation manifest verified |
| Lean core API, plugin catalog, app, and runtime tests | PASS | 69 tasks; full retained-host unit regression completed in 5m56s |
| Lean JVM ZIP and packaged JVM catalog smoke | PASS | Existing Yano JVM ZIP mechanism; external directory fixture activated through packaged catalog |
| Lean-host dependency boundary | PASS | 18 resolved `app` runtime projects, all retained/temporary |
| GraalVM native package/catalog smoke | PASS | Oracle GraalVM 25.0.2; core-only catalog and external-directory rejection |
| Native devnet epoch crossing | PASS | Isolated ports/state; genesis slot 0 through slot 104, two epoch boundaries, zero production/AOT errors |
| Full monorepo unit regression | PASS | 373 tasks in 9m03s with project parallelism disabled; all tests plus conformance, allocation, and lean-boundary gates |
| Retained cluster timeout reproduction | PASS | The single parallel-run Classic JMT connection timeout passed immediately in isolation and in the serialized full gate; no production/test change was made for the flake |
| Retained two-node app-chain cluster | PASS | Isolated JVM nodes; 2-of-2 finality, equal height-2 root, finalized-message proof, and one confirmed L1 anchor; evidence `/tmp/yano-phaseb-core.7t7cPA` |
| External extension bundle cluster | PASS | Two isolated JVM nodes loaded role-workflow + stdlib from `yano.plugins.directory`; built-in OrderedLog and external KV registry produced equal cross-node roots, 2-signature certificates, and a present KV proof; evidence `/tmp/yano-phaseb-extension.QwEQIg` |

The final full acceptance command was:

```text
./gradlew test verifyRuntimePluginConformanceMatrix \
  verifyRepositorySplitAllocation verifyLeanHostDependencyBoundary \
  -PskipSigning=true --no-parallel
```

The repository-allocation and lean-boundary tasks in this historical command were cutover guards.
They were retired after the Yano/Yano-X split completed; the remaining build and plugin-contract
checks continue in the current build.

Project parallelism is disabled for the combined acceptance run because multiple networking test
tasks reserve ephemeral ports. A parallel attempt completed 1,303 of 1,304 retained runtime tests
and timed out waiting for one Classic JMT peer connection; that test passed immediately in
isolation and again in the serialized full run. This is recorded as test-orchestration evidence,
not hidden by weakening or retrying the test.

The final lean JVM package smoke was rerun after all test-seam corrections and passed in 33s. The
previous GraalVM package/catalog and isolated native epoch-crossing results remain valid because the
subsequent changes are test-fixture/test-only wiring and do not change native production inputs.

The cluster runs also corrected the operator examples used for validation. A chain now fails closed
unless `state.commitment-profile`, `state.format-fingerprint`, and `state.genesis-id` are all
explicit and identical on every member; app-chain storage must be distinct from L1 storage. The
current finalized-message proof route is `messages/{messageId}/proof`. Plugin operations are
privileged and require a configured unscoped full API key. These requirements are reflected in the
tracked tutorial instead of being left as stale runbook debt.

## Removed prerelease debt and deferrals

P1-P8, P10, P11, and P13 are addressed by this phase's lean graph, typed SPIs, bundle convention,
conformance matrix, client/testkit split, and canonical configuration. No Phase B item is deferred.
P9 remains assigned to Phase C because it requires a clean Yano X build against locally published
Yano artifacts. P12 remains assigned to Phase E because the temporarily retained UI is deliberately
outside the current extraction.

No files have been copied to Yano X in Phase B. Physical movement starts only after this feature
branch is reviewed, committed, and merged into the integration branch.
