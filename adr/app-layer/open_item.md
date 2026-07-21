# App-Layer Open Items

**Last updated:** 2026-07-18

**Purpose:** canonical live index of app-layer work that is open, in progress,
blocked, or intentionally deferred.

This file tracks execution; ADRs remain the authority for architecture and
acceptance criteria. [pending-tasks.md](pending-tasks.md) retains the detailed
ADR-008-era backlog and historical delivery notes. Every still-open item from
that file is indexed below.

## 1. Maintenance rules

Update this file in the same branch/PR whenever app-layer development:

- accepts a new ADR or external-review finding;
- starts, blocks, defers, reprioritizes, or completes an item;
- discovers a new correctness, security, operations, documentation, or testing
  gap; or
- changes the owning ADR, branch, or acceptance gate.

Use these states:

| State | Meaning |
|---|---|
| `Proposed` | Architecture/design exists but implementation has not started. |
| `Ready` | Scope and acceptance gate are clear; implementation can start. |
| `In progress` | Active development or review is underway. |
| `Blocked` | Cannot proceed until the named dependency is resolved. |
| `Deferred` | Deliberately not scheduled; the trigger for revival is recorded. |

Priorities are `P0` (release/correctness blocker), `P1` (next production-shape
work), `P2` (important hardening/DX), and `P3` (demand/research driven).

When an item completes, remove it from the open tables and add the completion
evidence to its owning ADR. Do not keep checked-off rows here. If an old ADR's
headline status is stale, record that in the ADR inventory and fix it in a
dedicated documentation pass rather than treating it as current truth.

## 2. Current priority queue

These are the recommended next items after ADR-013 release closure.

| ID | Priority | State | Item | Owner/source | Done when |
|---|---:|---|---|---|---|
| APP-009 | P0 | Ready | Certify the corrected tree and wire release/durability evidence into CI: manual `scope=all`, final retargeted-PR run, four Phase-1.6 cluster durability suites, and CDDL cross-validation. | ADR-014 §2.4/disposition §7.1 | Both P0 fix branches land first; required workflows pass and retain evidence, or an unreliable CI gate has an explicit reviewed manual exception. |
| APP-010 | P2 | Ready | Run a documented load/soak envelope and optional preview/preprod smoke for the packaged app-chain profiles. | ADR-014 readiness table; ADR-017 | Results record topology, rates, payloads, duration, lag, resource use, failures, and supported/non-supported envelope. |
| APP-006 | P2 | Proposed | Design effect attestation-policy epochs: result-signer rotation/total-loss recovery, executor HA/fencing, and optional effect-type-scoped k-of-n outcome attestation without claiming external truth. | ADR-014 design finding 1/disposition §7.3; ADR-010/010.1 | A separate accepted ADR defines governance, activation, replay, fencing, trust claims, recovery, and E2E gates. Escalates to P1 before any semi-trusted-member deployment. |
| APP-011 | P2 | Proposed | Add a continuous off-consensus independent outcome auditor, seeded from the demo's S3/IPFS/Kafka/anchor re-read verification. | ADR-014 disposition §7.3 | Bounded reconciliation, discrepancy evidence, alerting, restart/lag behavior, and trust wording are tested. Escalates to P1 before any semi-trusted-member deployment. |
| APP-008 | P3 | Deferred | Close the residual retained-composite attachment case where a nonzero-height ledger has exactly the empty state root. | ADR-014 C2 reviewer follow-up | Revive on a reproducible deployment path; current startup checks already reject retained non-empty state without/mismatching the marker. |

## 3. External-review hardening backlog

| ID | Priority | State | Item | Source / trigger |
|---|---:|---|---|---|
| REV-002 | P2 | Ready | Reclassify unversioned object-store provider drift as operator-actionable `TARGET_CHANGED`, not definitive destination conflict. | ADR-014 D2; ADR-017 |
| REV-003 | P2 | Ready | Make IPFS detail documents byte-stable for the same successful effect when out-of-band pin state changes. | ADR-014 D5; ADR-017 |
| REV-004 | P2 | Ready | Specify and test detail-archive replication, backup/restore, disk-full behavior, and disaster recovery. | ADR-014 design finding 6/test gaps; ADR-017 |
| REV-005 | P2 | Ready | Add expiry-versus-outage sizing guidance for each first-party connector/profile. | ADR-014 §4.2; ADR-017 |
| REV-006 | P2 | Ready | Strengthen demo evidence: optional independent public-L1 verification, qualify `require-anchor=false`, replace the synthetic proof flag, and include retry evidence in the report. | ADR-014 §2.3; ADR-017 |
| REV-007 | P2 | Deferred | Extract the jointly normative v1 wire contracts into standalone versioned specifications and record named review/finding ledgers. | ADR-014 recommendations 8; ADR-017; revive before a stable public protocol release |
| REV-008 | P2 | Deferred | Converge future contracts on one documented canonical encoding/domain-hash policy; do not rewrite released encodings merely for uniformity. | ADR-014 design finding 6; ADR-017; apply to the next new wire contract |
| REV-009 | P2 | Ready | Clarify support status or ownership for composite dependencies on example modules. | ADR-014 C3; ADR-017 |
| REV-010 | P2 | Deferred | Split oversized `AppChainSubsystem` and `EffectRuntime` along existing tested seams. | ADR-014 C4; ADR-017; revive when feature work next touches each area |
| REV-011 | P2 | Ready | Keep the test-only fault seam out of production artifacts or document/verify its unreachability. | ADR-014 C7; ADR-017 |
| REV-012 | P2 | Deferred | Provide an encrypt-before-staging profile and explicit erasure-regime guidance; immutable object/IPFS/on-chain digests are not a deletion system. | ADR-014 readiness assessment; ADR-017; required before regulated personal-data claims |

## 4. Effect-system and Cardano action work

| ID | Priority | State | Item | Owner/source / trigger |
|---|---:|---|---|---|
| FX-001 | P1 | Proposed | Framework effect-setting/codec epochs: height-index result-signer rotation, result window/caps, commitment modes, and future effect record/root versions. | ADR-010.1 D5/D8; required before changing v1 effect consensus settings |
| FX-002 | P0 before funds | Proposed | Production Cardano transaction/action safety (`ADR-010.2`): reconciliation, network/address checks, UTxO/fee policy, custody, limits, native assets, and safe datum/script publication. | ADR-010 follow-up; prerequisite for material funds and ADR-012 O-M0 |
| FX-003 | P2 | Deferred | Bounded, sanitized, node-local append-only execution-attempt diagnostics. | ADR-010 follow-up; revive when structured logs are insufficient |
| FX-004 | P3 | Deferred | Alternative effect dispatch transport (Kafka/gRPC/WS) while keeping executors source-agnostic. | ADR-010/011; revive only with a concrete second transport |
| FX-005 | P3 | Deferred | On-chain claims, L1-slot leases, k-way assignment, and optional effects-root block-field promotion. | ADR-010; revive for expensive duplicate attempts/HA or next block wire version |
| FX-006 | P3 | Deferred | Retry hints as data and per-type outcome commitment modes. | ADR-010; revive after operational evidence |
| FX-007 | P2 | Deferred | Optional Kafka transactions/external dedupe ledger for consumers that cannot tolerate the acknowledged at-least-once duplicate window. | ADR-013 §21.3 |
| FX-008 | P3 | Deferred | Deprecate/remove legacy predicate-based executor routing after all supported products declare exact effect types. | ADR-013.1 §3.3; requires a separate compatibility/deprecation decision |

## 5. First-party connectors, demo, and composition

| ID | Priority | State | Item | Owner/source / trigger |
|---|---:|---|---|---|
| INT-001 | P2 | Deferred | Multiple `object.put` providers on one chain with explicit selection/type partition/routing; never classpath first-match. | ADR-013 §21.1; revive for GCS/Azure implementation |
| INT-002 | P3 | Deferred | Shared Kafka connection profiles between sink and effect executor. | ADR-013 §21.2; revive when duplication causes real operations cost |
| INT-003 | P3 | Deferred | `ipfs.add-and-pin` with frozen DAG/chunking/codec/hash and ingress/security rules. | ADR-013 §21.4 |
| INT-004 | P3 | Deferred | IPFS unpin with retention, authorization, reference counting, and legal/evidence policy. | ADR-013 §21.5 |
| INT-005 | P3 | Deferred | Object deletion/overwrite semantics. | ADR-013 §21.6; outside immutable `object.put` |
| INT-006 | P2 | Deferred | Kubernetes/Helm/cloud provisioning for the packaged demo/runtime contracts. | ADR-013 §21.7; revive for managed deployment work |
| INT-007 | P2 | Deferred | Shared connector-support library to reduce repeated lifecycle/config boilerplate before a fourth connector. | ADR-014 §4.3 |
| INT-008 | P2 | Proposed | Complete DPP product profile: actor/credential schemas, gated workflow, portal, policy, and Cardano publication. | [DPP possible design](dpp-possible-design.md); consumes ADR-013/015/019 and FX-002 |
| INT-009 | P2 | Deferred | A generic migration-precondition/workflow contract for state-incompatible composite upgrades. | ADR-015 §9.2; revive with the first concrete migration consumer |

### 5.1 Identity and role-aware workflow

| ID | Priority | State | Item | Owner/source / trigger |
|---|---:|---|---|---|
| IAM-001 | P2 | Proposed | Implement a reusable domain actor/organization registry and role-aware approval component with governed policies, actor-signed decisions, key rotation/revocation, and organization-distinct quorum rules. | [ADR-019](019-reusable-domain-actor-registry-and-role-aware-approvals.md); required before claiming application-role enforcement or non-member business approvals |

### 5.2 Performance and capacity validation

ADR-020 completed `PERF-001` on 2026-07-18. The authenticated stock capacity,
bounded pipeline, schema-v2 reports, UI, focused tests, and real explicit/direct
three-member acceptance runs are recorded in ADR-020 §8.1. Broader packaged
profile load/soak characterization remains `APP-010`.

## 6. Plugin architecture v2 backlog

ADR-011 v1 is accepted and implemented. These are deliberate extensions, not
missing v1 acceptance criteria.

| ID | Priority | State | Item | Owner/source / trigger |
|---|---:|---|---|---|
| PLG-001 | P2 | Deferred | Signed manifests/JARs or external deployment attestations and provenance policy. | ADR-011 §18; before untrusted artifact distribution |
| PLG-002 | P2 | Deferred | Proof-carrying query-response conventions. | ADR-011.3 §9 |
| PLG-003 | P2 | Deferred | Bounded JSON Schema/OpenAPI contribution metadata. | ADR-011.3 §9 |
| PLG-004 | P2 | Deferred | Materialized cross-machine read models/product-passport aggregation. | ADR-011.3 §9; revive with concrete query demand |
| PLG-005 | P3 | Deferred | Streaming, WebSocket, and subscription domain routes. | ADR-011.3 §9 |
| PLG-006 | P3 | Deferred | Out-of-process domain handlers and telemetry sources for hard isolation. | ADR-011.3/011.4 |
| PLG-007 | P2 | Deferred | Configurable per-plugin quotas using operational evidence. | ADR-011.3 §9 |
| PLG-008 | P3 | Deferred | Plugin-defined CLI commands, write-capable management actions, and hot reload. | ADR-011.4 §11 |
| PLG-009 | P2 | Deferred | Per-chain lifecycle attribution for typed app-chain factory contributions. | ADR-011.4 §11 |
| PLG-010 | P3 | Deferred | Extract one pure catalog-policy kernel shared by offline inspection and runtime construction. | ADR-011.4 §11; parity tests are the v1 guard |

## 7. Oracle and ZK roadmap

| ID | Priority | State | Item | Owner/source / dependency |
|---|---:|---|---|---|
| ORA-001 | P2 | Proposed | Implement the deterministic multi-source oracle and Cardano publication program O-M0 through O-M5. | [ADR-012](012-multi-source-oracle-and-cardano-publication.md); blocked on FX-002 and acceptance/review of ADR-012 |
| ZK-001 | P3 | Blocked | Private balances with a production transfer circuit, client proving, nullifiers, and dual commitments. | ADR-006 E7.4; blocked on ZeroJ production criteria |
| ZK-002 | P3 | Blocked | ZK-verified script anchor / threshold-certificate proof, later batch validity. | ADR-006 E7.5; blocked on ZeroJ maturity and production proof design |
| ZK-003 | P2 | Deferred | Split proving/disclosure helpers into `yano-appchain-client-zk`. | ADR-006 follow-up |
| ZK-004 | P3 | Blocked | Fully anonymous app transport `authScheme=2`. | ADR-006; requires yaci core auth-path work |
| ZK-005 | P2 | Deferred | Move VK/membership-root distribution from config into governed parameter epochs. | ADR-006; coordinate with FX-001/governance mileage |

## 8. Security, SDK, and developer experience

| ID | Priority | State | Item | Owner/source / trigger |
|---|---:|---|---|---|
| DX-001 | P1 | Ready | mTLS/OIDC recipes/integration, API-key scopes, and privileged-operation audit logging. | ADR-006 E4.1; ADR-008 DX track |
| DX-002 | P1 | Ready | Reference KMS/HSM/Vault `SignerProvider` bundle, starting with one production-shaped provider. | ADR-006 E4.3 |
| DX-003 | P2 | Ready | Grafana dashboard template for app-chain/runtime/plugin metrics. | ADR-006 E5.1 |
| DX-004 | P2 | Deferred | Optional per-record MPF proofs on finalized Kafka sink records. | ADR-006 E3.2 |
| DX-005 | P2 | Deferred | Quarkus client extension and Maven archetype matching the existing SDK/Spring/Gradle path. | ADR-006 E1.4/E1.5 |
| DX-006 | P1 | Ready | `AppStateMachine.stateVersion()` or successor fail-fast state-format marker with migration diagnostics. | ADR-008 I4.5 |
| DX-007 | P2 | Deferred | SDK/public-source anchor verification loop rather than relying only on the cluster's own L1 view. | ADR-008 DX; ADR-014 demo finding |
| DX-008 | P2 | Ready | Operator/developer handbooks for upgrade activation, effect operation, governed membership/profile changes, anchors, and failure recovery. | Cross-ADR discussion; required before beta positioning |
| DX-009 | P2 | Deferred | Container-native generic app-chain cluster topology, distinct from the ADR-013 scenario Compose demo. | `pending-tasks.md` DX track; revive for quick-network/product evaluation |

## 9. Protocol, consensus, and L1 backlog

These rows index every still-open item in
[pending-tasks.md](pending-tasks.md); that file holds the detailed rationale and
revival conditions.

| ID | Priority | State | Item | Trigger/source |
|---|---:|---|---|---|
| CON-001 | P2 | Deferred | Per-member missed-proposer-window accountability metric. | ADR-008.2; next attribution/observability pass |
| CON-002 | P2 | Deferred | Re-gossip or proposer hint for follower-submitted pool stragglers. | Observed ~1/4000 under spread load; revive when operationally visible |
| CON-003 | P3 | Deferred | Full BFT view-change mode. | Deployment cannot accept split-vote runbook |
| CON-004 | P3 | Deferred | Earliest-window proposal preference heuristic. | Split-vote observations occur in practice |
| CON-005 | P3 | Deferred | Public `MembershipGovernance` SPI. | A second governance model is required |
| CON-006 | P1 | Ready | Operator runbook for split-vote, governed break-glass, and anchor sub-threshold pause. | Before recommending rotating/governed defaults |
| CON-007 | P3 | Deferred | Register or formally reserve the metadata anchor label currently defaulted to 7014. | ADR-005 §12.1; before treating the label as a stable public interoperability contract |
| CON-008 | P2 | Deferred | Benchmark and define per-chain versus shared executor/scheduler resource isolation for many chains on one node. | ADR-005 §12.4; revive for multi-chain capacity work |
| CON-009 | P3 | Deferred | Wire-conformant CIP-137/DMQ interoperability mode. | ADR-005 §12.5; revive only with ecosystem demand |
| CON-010 | P2 | Deferred | App-chain message/body pruning and archival policy while preserving header/root/proof guarantees. | ADR-005 §12.6; revive when retained history becomes operationally expensive |
| CON-011 | P2 | Deferred | First-class read-only observer role that verifies/serves but never votes. | ADR-005 §12.7; revive with a concrete observer deployment |
| L1-001 | P3 | Blocked | Historical `L1View` via commitment history and archival JMT proofs. | Benchmark + CCL JMT maturity + archival-role design |
| L1-002 | P3 | Deferred | Complete checkpoint observations at anchor cadence. | Delta-window feasibility work |
| L1-003 | P2 | Deferred | Anchor leader election/failover under sequencer rotation. | Anchor-leader liveness becomes material |
| L1-004 | P2 | Deferred | Governed anchor identity pinning. | Semi-trusted membership requires stronger identity control |
| L1-005 | P2 | Deferred | Retry stable observation injection when the scheduled proposer is offline. | Skipped observations become operationally visible |
| L1-006 | P2 | Deferred | Reorg-safe durable pending-observation queue across restarts. | Long stability windows/frequent deploys; must reverify block hashes |
| L1-007 | P3 | Deferred | BLS aggregate/threshold signatures for large anchor committees. | Roughly 50+ members or single-submitter requirement |
| NODE-001 | P2 | Ready | Do not seed configured upstream into peer store when client sync is disabled. | ADR-008.1 delivery note |
| NODE-002 | P1 | Ready | Fix first-boot L1 chain-sync/header-continuity wedge. | `adr/todo_header_continuity_recovery.md` |

## 10. ADR inventory

This table prevents old proposal headers from being mistaken for current
implementation status.

| ADR | Current program interpretation | Open work location |
|---|---|---|
| 001 | Historical vision, correctly numbered and explicitly superseded by ADR-005. | None specific |
| 002 | Historical draft; multi-peer foundation was implemented before ADR-003. | None specific |
| 003 | Accepted/implemented transport foundation, refined by ADR-005. | None specific |
| 004 | Historical draft; sequenced ledger work is implemented/superseded by ADR-005. | None specific |
| 005 | Accepted/implemented core framework; later production work moved to ADR-008+. | Sections 8-9 |
| 006 | Accepted/implemented scheduled waves, with explicit ZK/security/DX backlog. | Sections 7-8 |
| 007 | Historical implementation review, explicitly marked as a point-in-time record. | Superseded findings tracked through later ADRs |
| 008 | Accepted plan; iterations 1-3 were delivered, with residual backlog retained. | Section 9 and `pending-tasks.md` |
| 008.1 | Accepted/implemented correctness and operator-safety iteration. | None specific |
| 008.2 | Accepted/implemented rotating sequencer. | `CON-*` residuals |
| 008.3 | Accepted/implemented governed membership. | `CON-005/006` |
| 008.4 | Accepted/implemented script anchors and L1 observations. | `L1-*` |
| 009 | Historical production-readiness review, explicitly marked as a point-in-time record. | Subsequent accepted ADRs and this tracker |
| 010 | Accepted/implemented effect system. | `FX-*` |
| 010.1 | Accepted; machine activation implemented, framework effect epochs deferred. | `FX-001` |
| 011/011.1-.4 | Accepted/implemented plugin v1. | `PLG-*` |
| 012 | Proposed oracle design. | `ORA-001` |
| 013/013.1-.2 | Accepted/implemented; three milestones complete. | `INT-*` and ADR-017 follow-ups |
| 014 | Informational external review; local P0/P1 engineering is complete. | `APP-009`, `APP-006/010/011`, and `REV-*` |
| 015 | Accepted/implemented governed composite-profile evolution; independent closure review has no unresolved Critical/High/Medium implementation finding. | `INT-009` for future incompatible-state migration; external release evidence remains `APP-009` |
| 016 | Accepted/implemented authenticated app-chain consensus profile and typed runtime limits. | `FX-001` for future framework-profile epochs |
| 017 | Placeholder for the post-P0/P1 Fable review-closure pass. | `APP-008/010`, `REV-*` |
| 018 | Accepted/implemented evidence lifecycle for multi-item publish, immutable republish, mutation-free verification, explicit replay, and bounded full-workflow load. | `INT-010` complete |
| 019 | Proposed reusable domain actor registry and role-aware approval design. | `IAM-001` |
| 020 | Accepted/implemented pipelined evidence load and committed workflow capacity. | `PERF-001` complete; broader load/soak envelope remains `APP-010` |

## 11. Tracker/documentation hygiene

`DOC-001` was completed on 2026-07-17: historical ADRs now distinguish their
original rationale from current delivery status without rewriting the decision
record. Completed rows are removed from the live table by policy.

| ID | Priority | State | Item | Done when |
|---|---:|---|---|---|
| DOC-002 | P2 | Ready | Retire duplicate active rows from `pending-tasks.md` as they complete and link new deferrals here. | The two trackers do not contradict one another. |

## 12. Standing positioning gates

- Keep **developer preview / permissioned pilot** language until the applicable
  P0/P1 correctness, security, operations, and soak gates for the intended
  deployment are closed.
- Metadata anchors are audit anchors, not settlement. Script-anchor guarantees
  apply only to chains that explicitly adopt and verify that mode.
- No production Cardano value movement or oracle datum publication before
  `FX-002`/ADR-010.2 is accepted and implemented.
- No regulated-personal-data or erasure claim before `REV-012` has an accepted
  policy and deployment profile.
- Effects prove what finalized members authorized and what an executor attested;
  they do not, by themselves, prove an external action or real-world fact true.
- `APP-006` and `APP-011` become P1 before planning any deployment with
  semi-trusted members. Until both close, receipts must not be marketed as
  independently verified external outcomes.
