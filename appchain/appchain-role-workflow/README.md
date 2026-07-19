# Domain actors and role-aware approvals

This module implements ADR-019 without changing app-chain consensus core. It
provides reusable deterministic components and the complete manifested
`role-evidence` stock state-machine provider.

## What it does

```text
member-threshold governance
  -> organization + actor + key revisions
  -> bounded approval-policy revisions

business actor signs exact proposal/decision bytes
  -> any member may relay them
  -> role-approval workflow verifies registry, key, role, clause and signature
  -> proposal becomes APPROVED only when every policy clause is satisfied
```

The legacy member-based `approvals` machine remains unchanged. Select
`role-evidence` only for a new chain, or activate its different committed
profile through ADR-015 governance.

## Stock evidence policy

The stock release workflow consumes an approved `evidence-release` policy
proposal whose payload domain is `evidence.release.v1` and whose 32-byte
payload hash equals the full canonical release command's BLAKE2b-256 hash.
That binds the registry prerequisite, approval and release IDs, document
entity/hash/reference, and nested evidence-storage command. A representative
governed policy is:

```yaml
proposer-roles: [manufacturer]
clauses:
  - clause: auditors
    role: auditor
    count: 2
    distinct-by: organization
  - clause: regulator
    role: regulator
    count: 1
    distinct-by: actor
rejection: any-eligible
max-lifetime-blocks: 1000
```

Five auditor actors from one organization still fill one `auditors` slot.
Different actors relayed through the same member remain different business
signers; one actor relayed through different members still counts once.

## Routes and exact queries

| Owner | Command/query |
|---|---|
| Domain actor component | `actors.command.v1` |
| Role workflow | `role-approvals.command.v1` |
| Organization current pointer | `components/domain-actors/organization-current`, params `id` |
| Organization | `components/domain-actors/organization`, params `id` or `id@revision` |
| Actor current pointer | `components/domain-actors/actor-current`, params `id` |
| Actor | `components/domain-actors/actor`, params `id` or `id@revision` |
| Policy current pointer | `components/role-approvals/policy-current`, params `id` |
| Policy | `components/role-approvals/policy`, params `id` or `id@revision` |
| Proposal | `components/role-approvals/proposal`, params `id` |
| Evidence-to-proposal link | `components/role-approvals/evidence-approval`, params `evidenceId@version` |
| Authenticated proposal counts | `components/role-approvals/stats`, empty params |

The domain API contribution is served below
`/api/v1/plugins/com.bloxbean.cardano.yano.appchain.role-workflow/` and exposes
read-only JSON projections at `organizations/{id}`, `actors/{id}`,
`policies/{id}`, `proposals/{id}`,
`evidence/{evidence_id}/versions/{version}/approval`, and `stats`. Every
successful record response includes the exact composite
`proofKey` and encoded `recordValue`; those physical bytes, not the convenient
JSON projection, are the MPF proof inputs authenticated by the state root.
For a current organization, actor, or policy, the response also includes
`currentPointerProofKey` and `currentPointerValue`. Verify both the revision
record and the pointer under the response's same height/root; an old revision
proof alone establishes existence, not currency. Explicit `?revision=N`
history responses intentionally contain only the immutable revision proof.

The `stats` record contains created, pending, approved, rejected, cancelled,
and expired proposal counts. It is consensus state, so every member and replay
sees the same values. Expiration is materialized when the first later command
names a pending proposal after its block-height deadline; v1 never performs an
unbounded per-block scan, so the count remains pending until then.
Invalid/unauthorized no-ops are intentionally not written to this record:
operational rejection telemetry belongs to the host's bounded admission/runtime
metrics rather than attacker-controlled consensus state.
New pending proposals stop at the frozen 10,000-record budget. Pending
administrator mutations stop at 1,024; expiry is targeted/lazy, so operators
must activate, cancel, or touch abandoned mutations rather than expecting an
unbounded background sweep.

The stock role profile does not route public `doc-trail.command.v1` messages.
Its document trail is workflow-only, so every entry in that profile is written
atomically by an approved role-evidence release. Applications needing public
free-form trails should use a different explicit profile.

## Governance and recovery

V1 administrators and their threshold are the chain's genesis membership
epoch, committed into the role component configuration identity. A mutation
uses explicit `PROPOSE -> APPROVE -> ACTIVATE`; the proposing administrator
counts once and duplicate member approvals do not count. Ordinary mutations
cannot change that administrator policy—doing so is an ADR-015 profile change.

New key IDs require an actor key proof-of-possession. Rotation appends a new
actor revision and key epoch. Revocation prevents future decisions but never
rewrites retained terminal decisions. V1 retains at most 16 epochs per actor;
an update cannot drop an epoch, reactivate a revoked key, or extend an already
finite validity interval. A pending proposal can be cancelled by
its current proposer actor, or through a separately threshold-approved
`CancelProposal` policy mutation when a key is compromised.

Actor private keys never belong in production Yano node configuration,
app-chain state, logs, metrics, or receipts. The packaged local demo references
five owner-only seed files from its isolated runner configuration so it can
perform a no-code scenario; those files are not mounted into the Yano member
containers. Production signers should implement the same frozen preimage
contract in KMS/HSM/Vault-backed client code.

## No-code role and recovery demo

From `app/appchain-effects-demo`:

```bash
./demo.sh up --instance roles --machine role --continuation direct

./demo.sh publish --instance roles --machine role --continuation direct \
  --evidence-id inspection-001 \
  --sample-file samples/inspection-certificate.json

./demo.sh verify --instance roles --machine role --continuation direct \
  --evidence-id inspection-001

./demo.sh role-lifecycle --instance roles --machine role --continuation direct
```

The publish command demonstrates manufacturer proposal, wrong-role and
same-organization negative controls, two distinct auditor organizations, one
regulator, evidence release, connector effects, finality, optional anchoring,
and proof verification. `role-lifecycle` uses a separate `recovery-probe`
actor to govern onboarding, rotate to a new key, prove the old revision is
rejected and the new revision accepted, revoke the actor, prove later use is
rejected, close pending probes, and verify every historical revision and
proposal with root-matched MPF proofs. The command is idempotent on retained
state.

## Custom composite reuse

A custom app plugin depends on this module, instantiates
`DomainActorRegistryComponent`, `RoleAwareApprovalsComponent`, and
`RoleApprovalWorkflow`, then declares their exact descriptors, participant
order, routes, configuration identity, and quotas in its committed composite
profile. The plugin JAR installs the complete preset; node-local YAML cannot
reorder consensus components dynamically.
