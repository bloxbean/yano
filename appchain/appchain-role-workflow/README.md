# Generic domain actors and role-aware approvals

This module implements the reusable parts of ADR-019 without changing
app-chain consensus core. It provides deterministic components for:

- governed organization and actor identities with key revisions;
- bounded, versioned role policies;
- signed actor proposals and decisions relayed by any member; and
- clause evaluation, including distinct-actor and distinct-organization rules.

It intentionally contains no evidence keys, routes, workflows, state-machine
provider, domain API, ServiceLoader entry, or plugin manifest. The
`role-evidence` assembly and its evidence-to-proposal index live in
[`appchain-evidence-profile`](../products/appchain-evidence-profile/README.md).

## Generic flow

```text
member-threshold governance
  -> organization + actor + key revisions
  -> bounded approval-policy revisions

business actor signs exact proposal/decision bytes
  -> any member may relay them
  -> RoleApprovalWorkflow verifies identity, key, role, clause and signature
  -> proposal becomes APPROVED only when every policy clause is satisfied
```

The reusable component queries are:

| Owner | Command/query |
|---|---|
| Domain actor component | `actors.command.v1` |
| Role approval workflow | `role-approvals.command.v1` |
| Organization current pointer | `components/domain-actors/organization-current`, params `id` |
| Organization | `components/domain-actors/organization`, params `id` or `id@revision` |
| Actor current pointer | `components/domain-actors/actor-current`, params `id` |
| Actor | `components/domain-actors/actor`, params `id` or `id@revision` |
| Policy current pointer | `components/role-approvals/policy-current`, params `id` |
| Policy | `components/role-approvals/policy`, params `id` or `id@revision` |
| Proposal | `components/role-approvals/proposal`, params `id` |
| Authenticated proposal counts | `components/role-approvals/stats`, empty params |

The `stats` record contains created, pending, approved, rejected, cancelled,
and expired proposal counts. Expiration is materialized when the first later
command names a pending proposal after its block-height deadline; v1 does not
perform an unbounded per-block scan.

## Product reuse

A product plugin depends on this module, instantiates
`DomainActorRegistryComponent`, `RoleAwareApprovalsComponent`, and
`RoleApprovalWorkflow`, and declares their descriptors, participant order,
routes, configuration identity, and quotas in its committed composite profile.
The product owns its provider, domain API, extra indexes, and plugin manifest;
node-local YAML cannot reorder consensus components dynamically.

Actor private keys never belong in Yano node configuration, app-chain state,
logs, metrics, or receipts. Applications sign the frozen preimage contracts
from `appchain-role-workflow-contracts` with their KMS, HSM, vault, or client
signer.

## Build

```bash
./gradlew :appchain-role-workflow-contracts:check \
          :appchain-role-workflow:check
```
