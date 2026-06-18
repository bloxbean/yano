# ADR-027: Yano Product And Architecture Vision Beyond A Data Node

**Status:** Proposed
**Date:** 2026-06-13
**Related:** ADR-021 (Snapshot Restore Coordinator), ADR-025 (Bootstrap Partial State), ADR-028 (Runtime Decomposition), adr/network/002 (Peer Management), `ledger-state/docs/design.md` (TODO / Known Issues), `app/ARCHITECTURE.md`

## Context

Yano started as a Cardano data node: it syncs from one upstream node, persists chain
and ledger state in RocksDB, serves downstream peers over n2n, and acts as a devnet
block producer for local development. This ADR records an architecture assessment of
the current codebase (0.1.0-pre6) and captures a phased product and architecture
vision for possible node roles beyond the data-node use case.

### Assets already in place

The following capabilities are the foundation any future role builds on:

1. **Praos block-production machinery.** Real VRF slot-leader eligibility checks,
   KES block signing, operational certificates, and epoch nonce evolution — verified
   against Haskell node behavior in compatibility tests. This exists for devnet and
   public-network producer modes (`SlotLeaderBlockProducer`,
   `SlotLeaderTimeTravelBlockProducer`).
2. **Conway-era ledger state.** Rewards calculation, epoch snapshots, governance
   ratification/enactment, DRep distribution, and committee semantics, cross-checked
   against db-sync on mainnet epochs.
3. **Event and plugin architecture.** Type-safe event bus with vetoable events
   (`BlockConsensusEvent`, `TransactionValidateEvent`) and ordered listeners;
   `NodePlugin` SPI discovered via `ServiceLoader`; and a wired `StorageFilter`
   extension point. Additional advertised capabilities such as `STORAGE_ADAPTER`
   and `POLICY` still need concrete registration and typed contracts before they
   are product extension points.
4. **Ordered ledger apply boundary.** `LedgerApplyProcessor` provides
   generation-based, single-threaded ordered apply with conservative recovery
   barriers, isolating Netty event-loop threads from ledger work.
5. **Transaction validation stack.** Phase-1 ledger rules over a ledger-slice
   abstraction, plus phase-2 Plutus evaluation via the Scalus bridge; exposed through
   Ogmios-compatible evaluate and Blockfrost-compatible submit endpoints.
6. **Developer-network tooling.** In-memory genesis, snapshot/restore, faucet, time
   advance, and past-time-travel mode with deterministic block production from slot 0.
7. **Bootstrap providers and analytics seeds.** Partial-state bootstrap from external
   APIs (ADR-025) and epoch-boundary export to Parquet via DuckDB (`epoch-export`).

### Gaps that constrain new roles

1. **`Yano.java` is a ~5,000-line orchestration class.** It combines node lifecycle,
   devnet tooling, snapshot management, nonce APIs, and time-travel control. Every
   new role is a different assembly of the same components; today that assembly is
   hardcoded. ADR-021 addresses one slice (snapshot restore); a broader decomposition
   is a prerequisite for this ADR.
2. **No header verification on the sync path.** VRF/KES cryptography is used only for
   block *production*, never for verifying *received* headers. Yano fully trusts its
   upstream peer. This is the defining boundary between a data node and a verifying
   node — and the required primitives already exist in the codebase.
3. **Single upstream peer, no chain selection.** adr/network/002 plans failover, but
   there is no multi-peer chain comparison, density-based selection, or topology
   management.
4. **Epoch-boundary crash semantics need a fresh audit.** Some historical risks in
   `ledger-state/docs/design.md` are stale: current code has boundary-delta evidence
   for reward/governance phases, recovery of committed-but-unmarked phases, and
   governance failures are rethrown. Remaining risks are narrower but still
   important: delegation-snapshot boundary writes and markers need explicit
   idempotence or delta evidence, standalone boundary-step markers need crash tests,
   AdaPot bootstrap uses direct writes, and the design document must be synchronized
   with the implementation.
5. **Incomplete policy/storage adapter SPI.** `NodePolicy` and `StorageAdapter` use
   weak `Object`-typed contracts, and the plugin context currently exposes filters
   and generic services rather than a complete policy/adapter registry.
6. **Test-coverage gaps.** The standalone `e2e-tests/` directory contains manual
   smoke/functional scripts but is not a Gradle module. App-level e2e and
   integration tests exist, but coverage is still thin for role assemblies,
   multi-process scenarios, crash recovery, and long-running network behavior.

## Decision Framework

Use this phased roadmap to guide detailed follow-up ADRs. This document does not
commit Yano to every role below; it records plausible directions, dependencies, and
sequencing so later ADRs can make concrete implementation decisions. Each accepted
phase should produce an independently useful node role, and later phases depend on
hardening done in earlier phases where the trust model or extension surface requires
it. Roles ship as alternative *assemblies* of shared modules, not forks.

### Phase 0 — Prerequisites (cross-cutting)

These block everything else and are scheduled first:

- **P0.1 Decompose `Yano.java`** into composable services per ADR-028 — node kernel
  (lifecycle, sync wiring), devnet tools, snapshot coordinator (per ADR-021), and
  producer manager — behind a smaller role-oriented API surface rather than one
  ever-growing `NodeAPI`.
- **P0.2 Audit and harden epoch-boundary crash behavior**: update
  `ledger-state/docs/design.md` to match current code, add crash/restart tests for
  each boundary phase, prove delegation-snapshot idempotence or add delta evidence,
  and verify that standalone step markers cannot hide partially applied state.
- **P0.3 Type and wire the policy/adapter SPIs** with real event/point/storage
  types, explicit registration APIs, and documented listener-order conventions.

### Phase 1 — Short term: packaging existing capability into new roles

- **R1 Test-network kit.** Publish a `yano-testkit` artifact (e.g. a JUnit 5
  extension) wrapping in-memory genesis, snapshot/restore, faucet, time advance, and
  past-time-travel for JVM integration testing. Mostly packaging work; broadens
  adoption and strengthens the local-development story.
- **R2 Programmable edge / indexer node.** Position Yano explicitly as a filtering,
  forwarding chain data source: wired `StorageFilter` plugins first, followed by
  typed `StorageAdapter` plugins from P0.3 for external stores or message queues.
  This remains complementary to dedicated indexer projects (Yano as the chain-facing
  data plane; indexers as the query/serving plane). Grow the `epoch-export`
  Parquet/DuckDB module into a first-class analytics output.
- **R3 Transaction submission gateway.** Productize the ordered, vetoable
  transaction-validation pipeline (pre-checks → ledger rules → policy) plus the
  Ogmios-compatible evaluate and Blockfrost-compatible submit endpoints as a
  standalone "policy mempool" role: custom admission rules, fee policies, and
  allow/deny lists as plugins. Before positioning this as a compatibility surface,
  normalize endpoint semantics, especially raw `application/cbor` versus hex-text
  handling across submit and evaluate APIs.

### Phase 2 — Medium term: changing the trust model

- **R4 Verifying relay (the strategic unlock).** Add header verification to the sync
  path: VRF proof verification against registered pool keys, KES signature and
  operational-certificate validation, using epoch nonces and stake distributions
  Yano maintains. This is real consensus-validation work, not just wiring existing
  producer code into the consumer path: the follow-up ADR must define pool-key
  lookup, active-stake snapshot timing, nonce selection, KES period and opcert
  counter rules, Byron/EBB handling, invalid-header quarantine/recovery, and
  reference-vector tests. Optionally re-validate block bodies (phase-1 rules +
  phase-2 Scalus evaluation). This upgrades Yano from "trust the upstream" to
  "verify everything" without yet requiring multi-peer chain selection, and is the
  prerequisite for R5, R7, and R8.
- **R5 App-specific rollup node framework.** Reuse the block producer as a
  sequencer, the vetoable validation pipeline for rollup transaction admission, and
  ledger-state plus the delta journal for off-chain state. New generic modules
  required: state-commitment (e.g. authenticated data structures such as MPF/JMT),
  L1 anchoring, data-availability strategy, and dispute/fraud-proof hooks. Treat
  these as exploratory until one concrete rollup assembly proves the boundaries;
  then extract SPIs so Yano is not coupled to any single rollup specification.
- **R6 Sidechain / partner-chain node framework.** Generalize genesis, block
  production, and ledger rules behind pluggable consensus and ledger SPIs, offering
  a JVM-native foundation for application-specific chains. Depends on P0.1 and P0.3,
  and should remain behind concrete use cases to avoid premature abstraction.

### Phase 3 — Long term: full validation and production block production

- **R7 Full validating node (client diversity).** Multi-peer chain selection,
  peer-topology management, bootstrapping from certified snapshots, and conformance
  testing against the formal ledger specification and reference test vectors. Yano's
  Conway governance-state depth is a differentiator relative to other non-Haskell
  node implementations, but this phase is a multi-year, sustained-funding commitment.
- **R8 Public-network block producer.** The Praos machinery already exists; the gap
  is that a block producer must never extend an invalid chain, so R8 strictly
  depends on R4/R7 validation work, plus KES rotation and operational hardening.
  Target test networks first.

### Sequencing summary

```
P0.1  P0.2  P0.3        (prerequisites)
  │     │     │
  ▼     ▼     ▼
 R1    R2    R3          short term — packaging
  \     |     /
   \    |    /
       R4                medium term — header/body verification
      /  \
    R5    R7             R5 exploratory; R7 full validation
           |
          R8             production block producer

P0.1 + P0.3
    |
    R6                   exploratory — concrete partner-chain assembly first
```

## Consequences

**Positive**

- Each phase ships an independently useful artifact; no phase bets the project on a
  distant outcome.
- The verifying-relay work (R4) reuses existing producer-side cryptographic
  primitives and ledger state, and changes Yano's category from data node to
  verifying node — the highest strategic-value-per-effort item on the list.
- Roles as assemblies keep one codebase and one module set; downstream users depend
  only on the slices they need, consistent with the existing publishing model.
- Phase boundaries map cleanly onto fundable, verifiable milestones.

**Negative / risks**

- P0 work (decomposition, crash-semantics hardening, SPI typing) is invisible to end
  users but consumes significant effort up front.
- R4 correctness is unforgiving: header verification bugs cause chain stalls or,
  worse, silent acceptance of invalid chains. Requires extensive cross-validation
  against the reference implementation.
- R5/R6 SPI design risks over-abstraction if built before a concrete consumer exists;
  mitigate by extracting SPIs from at least one working assembly.
- R7/R8 carry long-horizon maintenance and conformance costs that exceed what a
  small team can absorb without dedicated funding.

**Out of scope for this ADR**

Concrete designs for header verification (R4), state-commitment SPIs (R5), and
pluggable consensus (R6) will be covered in follow-up ADRs.
