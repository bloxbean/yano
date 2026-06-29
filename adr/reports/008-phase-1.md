# ADR-NET-008 Phase 1 Implementation Report

## Scope

- Added upstream config model in `core-api`:
  - `UpstreamConfig`
  - `UpstreamPeerConfig`
  - `UpstreamPreset`
  - `ChainSelectionConfig`
  - validation, sync, failover, tx, governor, and discovery config objects.
- Added `YanoConfig.effectiveUpstream()` so legacy `yano.remote.*` maps to `trusted-single`.
- Relaxed `YanoConfig.validate()` so explicit upstream peers satisfy the client remote requirement.
- Added `YanoPropertyKeys.Upstream`.
- Added optional app parsing for `yano.upstream.*` in `YanoProducer`.

## Review

- Existing configs do not need an upstream block.
- Server-only and devnet modes remain valid.
- Unsupported validation level `full`/`consensus-lite` fails fast until implemented.

## Verification

- `UpstreamConfigTest` covers legacy remote mapping, explicit upstream peers without `remoteHost`, and invalid structural+validated policy.
- `./gradlew :app:compileJava`
