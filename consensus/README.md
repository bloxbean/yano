# Yano Consensus

Consensus-facing chain-selection policy and candidate-header state. Headers advertised by upstream peers
are collected here before they become canonical, and a selection strategy decides whether a candidate chain
can be adopted. The module holds policy and data only: it owns no sockets, storage engine or sync loop.

## Artifact

`org.yanoproject:yano-consensus`

## Key contents

All types are in `org.yanoproject.consensus.selection`:

| Type | Purpose |
|---|---|
| `HeaderFanIn` | Receives peer-scoped headers without mutating canonical chain state. |
| `CandidateHeaderStore`, `InMemoryCandidateHeaderStore` | Non-canonical candidate header store. |
| `CandidateFragmentStore`, `InMemoryCandidateFragmentStore`, `CandidateFragment` | Bounded per-peer candidate fragments (oldest to tip). |
| `ChainSelectionStrategy`, `ChainSelectionContext`, `ChainSelectionDecision` | Selection policy contract with its inputs and result. |
| `TrustedOrQuorumCandidateWithinRollbackWindow` | The conservative first selection policy. |
| `CardanoOrientedChainComparator`, `ChainCandidate`, `ChainComparison` | Deterministic Cardano-oriented chain comparison. |
| `CanonicalChainView`, `CanonicalHeaderPoint`, `CandidateHeader`, `HeaderValidationEvidence` | Read-only views and records the policy works on. |

## Dependencies and boundaries

- No sibling Yano module dependency (only the SLF4J API added to every library).
- Must not depend on `runtime` or `p2p`; `ConsensusArchitectureTest` (ArchUnit) enforces this.
- `runtime` depends on it (`api`) and uses it from the multi-peer sync path (`runtime.sync.multipeer`).

Design: [ADR-CONSENSUS-001](../adr/consensus/001-candidate-header-chain-selection-extraction.md),
[ADR-CONSENSUS-002](../adr/consensus/002-validation-aware-chain-selection-and-praos-readiness.md).

## Build and test

```bash
./gradlew :consensus:build -PskipSigning=true
./gradlew :consensus:test
```
