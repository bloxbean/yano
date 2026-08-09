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
