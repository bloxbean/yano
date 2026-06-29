# ADR-NET-008 Phase 2 Implementation Report

## Scope

- `SyncSubsystem` now builds an ordered upstream peer list from `YanoConfig.effectiveUpstream()`.
- Current live behavior remains single-active.
- `SyncSubsystem.upstreamStatus()` reports:
  - mode;
  - configured peer count;
  - hot peer count;
  - active peer;
  - tx forwarding policy.
- `RuntimeNode.getStatus()` publishes the new upstream status fields.

## Review

- Current `HeaderSyncManager` and `BodyFetchManager` direct canonical path is unchanged.
- Existing constructor usage remains compatible.
- Multi-peer presets are represented in status but do not activate unsafe live multi-peer mutation.

## Verification

- Existing `SyncSubsystemTest` still passes.
- `./gradlew :app:compileJava`
