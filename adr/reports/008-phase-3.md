# ADR-NET-008 Phase 3 Implementation Report

## Scope

- Added trusted-failover behavior in `SyncSubsystem`.
- When startup or recovery fails and more than one upstream peer is configured, the active peer rotates by configured priority order.
- `trusted-single` does not rotate.

## Review

- Kept failover based on durable local cursors by reusing existing peer-session recovery flow.
- Did not introduce parallel writers or standby sync sessions.
- First implementation uses deterministic priority rotation; cooldown/scoring can layer on top.

## Verification

- `SyncSubsystemTest.trustedFailoverAdvancesActivePeerAfterStartupFailure`
- Focused runtime test command from Phase 0.
