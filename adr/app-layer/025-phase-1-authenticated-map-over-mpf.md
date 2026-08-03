# ADR-025 Phase 1 — authenticated-map over MPF

Status: implemented and qualified for the existing MPF runtime path.

## 1. Product semantics

`authenticated-map` is a new versioned state machine. `kv-registry` is
unchanged. The implementation uses the Phase 0 canonical collection/key,
command, entry, genesis, value-hash, and batch-commitment contract.

Phase 1 adds these frozen details:

- framework-owned genesis and receipt leaves use namespace kind `0`; map
  collection leaves use namespace kind `1`;
- the canonical genesis id is written at height 1 and must match on every
  later transition and restart;
- initial entries are written before height-1 messages, with revision 1 and
  genesis height 0;
- `open`, `owner`, and height-versioned `member` authorization are enforced;
- active values and included revoked tombstones are never confused with
  absence;
- each successful mutation advances the affected entry exactly one revision;
- a command batch is evaluated completely before any map leaf is written;
- a failed authorization, lifecycle rule, or precondition leaves every map
  entry unchanged and writes one deterministic `REJECTED` receipt; and
- successful commands write an `APPLIED` receipt with ordered mutation results
  and a domain-separated result commitment.

Receipts are retained in consensus state in Phase 1. A later bounded retention
policy must be consensus-versioned; a node-local cleanup job may not delete
them.

## 2. Genesis and configuration

The provider accepts only the exact canonical setting:

```text
machines.authenticated-map.genesis-cbor-hex
```

`AuthenticatedMapGenesisFactory.mpf(...)` derives the framework-consensus
digest and initial-membership commitment from the same `AppChainConfig` used by
the node. The provider fails before participation when the chain id, MPF
profile/fingerprint, framework digest, membership commitment, resource limit,
or retained genesis marker differs. Aliases and node-local collection defaults
are not accepted.

## 3. Query and proof boundary

The contract artifact now defines canonical, profile-neutral point and receipt
request/result DTOs. Point results bind their committed height and state root
and classify `ABSENT`, `ACTIVE`, and `REVOKED` explicitly. Historical point
requests carry their requested finalized height and can only execute against a
context fixed to that height.

The current runtime already exposes retained MPF value/proof snapshots by app
height through `stateProofSnapshotAtHeight`. Phase 5 will expose the
backend-neutral historical query/proof envelope consistently through REST and
CLI; Phase 1 does not present a current-root query as historical.

`StdlibAppChainClient` submits typed single/batch commands, decodes the query
DTOs while cross-checking their outer root/height envelope, and offers the
existing MPF proof verifier for canonical map leaves. Examples warn that a
proof checked only against a root supplied by the same untrusted node is
circular.

## 4. Qualification evidence

The Phase 1 suite covers:

- owner creation/update, unauthorized mutation, compare-and-set, transfer,
  revoke, restore, and revision behavior;
- height-versioned member authorization;
- bounded batch all-or-nothing behavior and rejected receipts;
- canonical DTO and receipt golden vectors, independently checked in Python;
- service-loaded execution through the production MPF/RocksDB commit path;
- current and historical proof snapshots, committed queries, receipt lookup,
  snapshot manifest creation, close/reopen, and retained genesis checks; and
- three independent full replays plus restart, checkpoint continuation, state
  probes, and catch-up-style replay through `StateMachineConformance`.

Primary verification commands:

```bash
./gradlew :appchain-stdlib-contracts:check
./gradlew :appchain-stdlib:test :appchain-client:test
```

Phase 1 does not introduce a second persistence backend or claim production
qualification for JMT. Those changes begin with the prepared commitment
runtime in Phase 2.
