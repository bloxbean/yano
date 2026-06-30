# ADR-NET-008B Phase 3 Implementation Report

Date: 2026-06-30

## Scope Completed

- Routed Yaci TxSubmission `ReplyTxIds` and `ReplyTxs` through `TxDiffusion`
  when diffusion is enabled.
- Preserved the legacy handler path when no diffusion instance is supplied or
  when the supplied diffusion instance is disabled.
- Admitted accepted peer transaction bodies through the existing
  `TransactionAdmission` path.
- Used peer-aware admission origin metadata: `tx-diffusion:<peer>`.
- Added policy counters for accepted, rejected, and ignored inbound bodies.

## Review Notes

- Yaci `TxSubmissionServerAgent` currently owns the actual request message
  scheduling. Yano records/plans requested txs and rejects or ignores bodies
  that are not planned or are not allowed by mode/policy.
- This keeps poisoning protection at the admission boundary while preserving
  disabled-mode compatibility with the old `txsubmission` origin path.

## Verification

- `./gradlew :runtime:test --tests com.bloxbean.cardano.yano.runtime.handlers.YaciTxSubmissionHandlerTest`
