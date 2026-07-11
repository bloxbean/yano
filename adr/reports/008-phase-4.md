# ADR-NET-008 Phase 4 Implementation Report

## Scope

- Added `HeaderFanIn`.
- Added `CandidateHeader`, `CandidateHeaderStore`, and `InMemoryCandidateHeaderStore`.
- Candidate headers are stored outside canonical `ChainState`.

## Review

- This phase is implemented as safe scaffolding.
- Live ChainSync still uses the existing trusted single-active path.
- Candidate storage is ephemeral and separately namespaced from canonical storage.

## Verification

- `MultiPeerScaffoldingTest.headerFanInStoresCandidatesWithoutCanonicalState`
