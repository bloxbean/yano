# App-chain release acceptance

This page records the ADR-022 M6 release decision and the checks behind the
out-of-box app-chain catalog. It deliberately separates a capability being
packaged from its business outcome being exercised.

## Current decision

The blueprint, lock, component-catalog, and GitOps schemas remain
`v1alpha1`. Internal clean-room exercises cover the supported release matrix,
but no independently maintained third-party catalog has yet supplied field
compatibility evidence. Renaming an alpha schema to stable without that usage
would create a compatibility promise that has not been tested.

The machine-readable source of this decision is
`appchain-release-acceptance-index.json`. It is shipped with the CLI, Studio,
final distribution, and every generated project. Every evidence reference in
the index names a repository file and a concrete test, script, or task marker;
missing references fail the build.

## Recipe support

| Recipe | Maturity | Acceptance level | What is exercised |
|---|---|---|---|
| `audit-log` | stable | packaged runtime | Finalized record, proof, restart, root parity, and drift |
| `owned-registry` | stable | packaged runtime | Owner-controlled value and state proof |
| `approval-workflow` | stable | packaged runtime | Terminal approval plus generic effect completion |
| `role-approval` | preview | module outcome + packaged provider | Eligible/ineligible actor decisions and provider discovery |
| `evidence-ledger` | preview | module outcome + packaged provider | Role-authorized release workflow and provider discovery |
| `custom-plugin` | experimental | operator-owned reference | Signed catalog, digest pinning, JVM loading, and native diagnostic |

Preview recipes remain preview because their full business outcome has not
yet been run by an external operator from the final archive. The custom-plugin
recipe remains experimental because its implementation and lifecycle are
owned by the plugin operator.

No catalog capability was removed in M6: each has a concrete owner,
documentation, packaging declaration, and at least one resolvable behavior or
conformance reference. This does not upgrade preview or experimental entries
to stable.

## Deployment matrix

The packaged CLI generates and validates every advertised recipe across its
declared runtime and deployment combinations:

- JVM and native project layouts where advertised;
- host and Docker Compose deployment layouts;
- Helm and Kustomize exports;
- deterministic re-rendering with an unchanged lock;
- generated offline CI verification for JVM projects.

Portable CI verifies native generation, catalog declaration, and packaging.
Actual native execution is a platform-specific release gate because native
images cannot be treated as portable JVM artifacts.

## Release-candidate commands

Run the static and packaged JVM gates with:

```bash
./gradlew :appchain-devtools:appChainReleaseCandidateStaticCheck
./gradlew :appchain-devtools:appChainReleaseCandidateJvmAcceptance
```

On a supported GraalVM host, also run:

```bash
source "$HOME/.sdkman/bin/sdkman-init.sh"
sdk use java 25.0.2-graal

./gradlew :app:quarkusBuild \
  -PincludeFirstPartyPluginBundles=true \
  -PincludeNativePluginConformanceFixture=true \
  -Dquarkus.native.enabled=true \
  -Dquarkus.package.jar.enabled=false \
  -PskipSigning=true --no-daemon

./gradlew :appchain-devtools:appChainReleaseCandidateNativeAcceptance \
  -PincludeFirstPartyPluginBundles=true \
  -PincludeNativePluginConformanceFixture=true \
  -PskipSigning=true --no-daemon
```

The acceptance task exercises `app/build/yano`; it does not create that
platform executable. Build it first with the same provider-selection flags so
the JVM provenance expectation and native catalog describe the same release
flavor. A missing or stale binary must fail the smoke test.

The M6 live devnet regressions additionally verified two-node finality,
state-root parity, proofs, L1 anchoring, multi-chain behavior, query/SSE
surfaces, webhooks, authenticated administration, member rotation, evidence,
snapshots, and metrics. The tracked packaged-runtime scripts remain the
repeatable release gate; platform release jobs should repeat the live devnet
exercise before publishing.

## What is still needed before schema stabilization

Before declaring a v1 schema, collect at least one independently maintained
plugin/catalog exercise, record any compatibility changes it exposes, and run
the same project through upgrade and regeneration review. Until then, additive
alpha evolution is allowed and generated locks continue to pin exact schema
and catalog digests.
