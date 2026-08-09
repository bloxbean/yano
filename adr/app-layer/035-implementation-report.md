# ADR-035 implementation report

This report records phase evidence for
[ADR-035](035-cardano-history-product-console-and-distribution.md). Each phase is implemented and
reviewed on a feature branch before it is merged into the ADR-028 integration branch.

## P0 — characterization

Status: complete

The v1 contract is frozen before runtime wiring:

| Contract | Frozen value |
|---|---|
| Bundle ID | `com.bloxbean.cardano.yano.appchain.cardano-history` |
| State machine/application | `cardano-history` |
| Presets | `params-only-v1`, `params-stake-v1`, `params-governance-v1`, `full-v1` |
| UI API/mount | `1` / `app-chain` |
| UI asset budget | 256 files, 4 MiB each, 16 MiB total |
| UI sandbox | scripts only, opaque origin, no direct network |
| Proof bundle | canonical ADR-028 authenticated-snapshot bundle plus history predicates |
| CLI success classes | unavailable/incomplete, invalid, root-only valid, L1-anchored valid |

The core API now supplies immutable descriptor, permission, asset-manifest, and provider contracts.
Contract tests accept the Cardano History descriptor and reject traversal, unknown media, duplicate
paths, undeclared permissions, non-canonical digests, and budget violations. These are generic
plugin contracts; no Cardano-specific type was added to `core-api`.

## P1 — plugin UI foundation

Status: complete

Implemented:

- `ui-extension` is a manifest-required, auxiliary-local contribution kind with native-image and
  offline/runtime catalog parity;
- the runtime eagerly reads every declared asset through the plugin callback fence, checks its
  exact size and SHA-256 digest, derives a deterministic content-set digest, and publishes only a
  host-owned immutable byte snapshot;
- stop/unload atomically removes the catalog and invalidates the content-addressed namespace;
- the REST adapter exposes a no-store catalog and immutable assets with `nosniff`, ETag,
  restrictive CSP, referrer, and same-origin resource headers;
- the console adds a generic capability-gated extension route and an opaque-origin iframe with
  only `allow-scripts`;
- the v1 `postMessage` bridge binds source window, opaque origin, API version, random session
  nonce, selected chain, request ID, permission, input/response size, concurrency, and timeout;
  it offers only bounded read operations and never passes the API key or a raw-fetch primitive.

Conformance covers invalid paths/media/digests, duplicate assets, unsupported permissions and API
versions, wrong-chain capability requirements, forged nonces, undeclared bridge methods,
cross-namespace asset access, and lifecycle cleanup. The production console contains no
Cardano-History-specific source or asset.

## P2 — product assembly

Status: complete

The optional `appchain-cardano-history` module contributes the stable `cardano-history` provider.
Its four immutable presets construct the existing `EpochParamsStateMachine`,
`EpochStakeStateMachine`, and `EpochGovernanceStateMachine` through `ComposableAppStateMachine`.
The product contains no observation, transition, key, value, or snapshot implementation.

Startup validates exact preset/observer agreement, bounded chunk sizes, authenticated-state
identity, and MPF whenever L1 proof consumption is required. `params-only-v1` activates neither
stake/governance traversal nor a secondary authenticated-snapshot series. Artifact checks reject
host API, composition, or stdlib classes in the deterministic bundle jar.
