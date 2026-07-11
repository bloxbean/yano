# ADR-NET-008 Phase 0 Implementation Report

## Scope

- Added explicit upstream runtime status fields to `NodeStatus`.
- Added `UpstreamStatus` in runtime sync.
- Added focused tests for canonical multi-peer safety invariants through the new scaffolding:
  - unselected candidate headers stay outside canonical state;
  - single untrusted longer candidates are observe-only;
  - trusted or quorum-backed candidates can be selected.

## Review

- Checked that existing `PeerSession` remains the only live single-peer worker.
- Kept current `SyncSubsystem` public methods compatible for existing tests and runtime callers.
- Avoided changing current canonical header/body apply behavior.

## Verification

- `./gradlew :runtime:test --tests "com.bloxbean.cardano.yano.runtime.config.UpstreamConfigTest" --tests "com.bloxbean.cardano.yano.runtime.sync.multipeer.MultiPeerScaffoldingTest" --tests "com.bloxbean.cardano.yano.runtime.sync.SyncSubsystemTest"`
- `./gradlew :app:compileJava`
