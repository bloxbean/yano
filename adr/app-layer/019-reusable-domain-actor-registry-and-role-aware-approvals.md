# ADR-019: Reusable Domain Actor Registry and Role-Aware Approvals

## Status

Proposed — architecture and phased delivery plan

The number is local to the `adr/app-layer` series. Root-level ADR-019, if one
exists, is unrelated.

## Date

2026-07-18

## Parent and related decisions

- [ADR-005](005-yano-app-chain-framework.md) owns member-authenticated app
  messages, deterministic execution, finality, and authenticated state.
- [ADR-006](006-appchain-enterprise-extensions-and-zk.md) introduced the
  standard approval machine and the broader enterprise identity roadmap.
- [ADR-011](011-plugin-architecture.md) owns manifested application plugins.
- [ADR-013.2](013.2-deterministic-composite-state-machine.md) owns reusable
  deterministic components and committed composite profiles.
- [ADR-015](015-governed-composite-profile-evolution.md) owns governed changes
  to a composite profile after genesis.
- [ADR-018](018-evidence-demo-iteration-2-publish-republish-verify.md) owns the
  current member-approved evidence lifecycle and its no-code demonstration.
- [DPP possible design](dpp-possible-design.md) identifies the need for
  durable non-member business actors, roles, rotation, and revocation.
- [App-layer open items](open_item.md) tracks delivery as `IAM-001`.

## 0. In plain words

An app-chain member key answers a consensus question:

> Which consortium node submitted, voted for, or finalized this message?

A domain actor key answers a business question:

> Which auditor, regulator, manufacturer, employee, service, or device
> authorized this action, and in what role?

Those identities sometimes belong to the same organization, but they are not
the same concept. Making every auditor a consensus node does not scale, while
treating a node REST API key as an auditor signature does not produce a
portable business authorization.

This ADR adds two reusable application-layer capabilities:

```text
Domain Actor Registry
  organization -> actors -> public-key epochs -> roles/status

Role-Aware Approvals
  policy -> proposal payload hash -> actor-signed decisions -> outcome
```

An auditor signs a domain approval outside Yano. Any app-chain member node may
relay it. The deterministic component verifies the auditor signature, active
key, organization, role, policy, and duplicate rules. The outer app message
still identifies the relaying member, so consensus authentication is not
weakened.

The components are reusable in evidence release, DPP, payment authorization,
credential issuance, oracle publication, and custom composites. They do not
claim that an approved document or observation is true; they prove who
authorized which exact bytes under which authenticated policy.

## 1. Current behavior and gap

### 1.1 Existing member approvals

The standard `ApprovalsStateMachine` currently supports:

```text
PROPOSE [itemId, payload, required, deadline]
APPROVE [itemId]
REJECT  [itemId]
```

It records `AppMessage.sender` as the proposer, approver, or rejecter. The
sender is a 32-byte Ed25519 public key from the app chain's active member set.
For normal REST submission, the receiving node signs the message with its own
member key. Consequently:

- repeated approvals through one member node count once;
- approvals through two differently keyed member nodes count twice;
- the REST API key controls access but is not the recorded approver identity;
- every member is equally eligible to approve or reject; and
- roles, organizations, non-member actors, and actor-key rotation are absent.

This is correct and useful for small trusted-member workflows. It remains
supported as the lightweight member-approval component.

### 1.2 Why member keys must not become the domain identity system

Consensus membership and business authorization have different lifecycles:

| Concern | App-chain member identity | Domain actor identity |
|---|---|---|
| Primary purpose | Propose, relay, vote, finalize | Authorize a business action |
| Typical owner | Consortium node operator | Human, service, device, department |
| Cardinality | Small, bounded validator group | Potentially many actors per organization |
| Rotation reason | Node/operator membership | Employment, device, credential, role change |
| Availability | Expected to operate node infrastructure | May sign only occasionally |
| Authorization | Consensus-wide member capability | Application policy and role |
| Secret location | Node signer/KMS/HSM | Actor wallet, service signer, device/KMS |

Conflating them creates undesirable outcomes: every employee must operate a
node, five employees from one firm can masquerade as five independent firms,
and rotating an auditor credential becomes a consensus-membership operation.

### 1.3 Identity boundaries

The completed design must keep these credentials distinct:

| Identity or credential | Meaning | Used as a role approval? |
|---|---|---|
| App-chain member signing key | Authenticated outer message sender and consensus participant | Only in the legacy member-approval mode |
| Domain actor key | Business actor and role authorization | Yes |
| REST API key/OIDC identity | Permission to call a node endpoint | No |
| Cardano anchor/payment wallet | L1 transaction authorization | No |
| Effect executor credential | Access to Kafka, S3, IPFS, or another target | No |

## 2. Goals and non-goals

### 2.1 Goals

- Provide a reusable, deterministic registry for organizations, actors, roles,
  public-key epochs, suspension, revocation, and bounded metadata commitments.
- Authenticate business decisions independently of the member node that
  relays them.
- Express bounded policies such as two auditors from distinct organizations
  plus one regulator.
- Bind every decision to the exact chain, policy revision, proposal, payload
  hash, decision, actor, key, and policy clause.
- Make accepted decisions and registry facts provable against app-chain state.
- Preserve exact replay, catch-up, restart, and cross-member determinism.
- Package the capability so stock and custom composite plugins can reuse it
  without modifying app-chain consensus core.
- Retain the existing member-based `approvals` component for simple workflows.
- Provide client encoders/signers and a no-code reference scenario; users must
  not hand-author CBOR or signature preimages.

### 2.2 Non-goals

- Replacing app-chain membership, finality voting, or member-key governance.
- Treating API keys, TLS identities, OAuth/OIDC tokens, or UI sessions as
  consensus-portable business signatures.
- Proving that a real-world claim, sensor reading, document, or price is true.
- Building a general BPMN/workflow language or an unbounded policy DSL.
- Storing actor private keys, personal profiles, certificates, or large
  identity documents in authenticated state.
- Requiring DIDs or verifiable credentials in v1. Adapters may resolve those
  into the bounded registry contract later.
- Retrospectively invalidating a terminal decision when a key or role changes.
- Dynamically inserting independent component JARs into a committed composite
  through YAML. ADR-013.2 deliberately requires explicit composite assembly.

## 3. Decision summary

1. Introduce a reusable `DomainActorRegistry` deterministic component.
2. Introduce a reusable `RoleAwareApprovals` deterministic component.
3. Keep node membership and domain actors as separate namespaces and keys.
4. Carry actor authorization as a domain-signed statement inside an ordinary
   member-authenticated `AppMessage` body.
5. Validate proposals and actor decisions through an ADR-013.2 declared
   workflow with explicit registry and approval views. Neither component may
   read the other's namespace directly.
6. Make approval proposals reference a committed policy ID and revision;
   callers cannot choose a weaker threshold in an individual proposal.
7. Evaluate actor key, status, organization, and role when a decision is
   accepted, then snapshot the facts used by that decision.
8. Support policy clauses with role, count, and distinctness by actor or
   organization. All clauses in a v1 policy are conjunctive.
9. Govern organizations, actors, keys, roles, and policies through a bounded
   registry-administrator policy committed by the composite profile. A
   mutation never succeeds because one REST caller possesses an API key.
10. Preserve the existing `ApprovalsStateMachine` and its wire contract.
11. Package contracts separately from runtime implementation, and expose a
    complete state-machine/composite plugin rather than bypassing ADR-011 or
    ADR-013.2 lifecycle rules.
12. Require stable command/result codes and deterministic no-ops for malformed,
    unauthorized, duplicate, expired, or terminal commands during `apply()`.
13. Treat a role-aware approval as evidence of key authorization, not evidence
    of external truth or legal identity beyond the registry's governance.

## 4. Reusable component model

### 4.1 Domain Actor Registry

The registry owns three versioned record families.

```text
OrganizationRecord
  organizationId
  status: ACTIVE | SUSPENDED | REVOKED
  metadataCommitment (optional 32-byte hash)
  revision

ActorRecord
  actorId
  organizationId
  sorted roles
  status: ACTIVE | SUSPENDED | REVOKED
  active key epochs
  metadataCommitment (optional 32-byte hash)
  revision

ActorKeyEpoch
  keyId
  algorithm: ED25519_V1
  32-byte public key
  validFromHeight
  validUntilHeight (zero means no scheduled end)
  status
```

Only the minimum public authorization facts enter authenticated state. Display
names, contacts, employment files, certificates, and other potentially
personal or regulated data remain off chain; an optional digest may bind an
externally governed document.

V1 role and identifier syntax is bounded and canonical. Records use sorted
sets, explicit integer/status encodings, and fixed maximum sizes. The contract
phase must publish CDDL and test vectors before implementation is accepted.

Updates append an immutable revision record and advance a small current-revision
pointer. They do not overwrite the facts used by earlier decisions. Key epochs
are likewise retained. This makes the registry at the current root able to
prove both the active authorization and the exact historical revision cited by
an accepted decision; state-growth and archival policy must be measured before
stable release.

### 4.2 Role-Aware Approvals

The approval component owns versioned policy and proposal records.

```text
ApprovalPolicy
  policyId + revision
  optional proposer roles
  one or more required clauses
  rejection mode
  maximum lifetime

RequiredClause
  clauseId
  role
  minimum count
  distinctBy: ACTOR | ORGANIZATION

ApprovalProposal
  proposalId
  policyId + revision
  payloadDomain
  payloadHash
  deadline
  status
  accepted decisions
```

All required clauses are ANDed. An actor decision names exactly one clause;
one actor may contribute at most one affirmative decision to a proposal. This
avoids ambiguous allocation when an actor holds multiple roles. Each clause
deduplicates by its configured dimension.

Example:

```yaml
policy: evidence-release
revision: 1
proposer-roles: [manufacturer]
requirements:
  - clause: independent-auditors
    role: auditor
    count: 2
    distinct-by: organization
  - clause: regulatory-release
    role: regulator
    count: 1
    distinct-by: actor
rejection: any-eligible
max-lifetime-blocks: 1000
```

Five auditor keys belonging to one audit firm satisfy only one slot in the
first clause. An actor with both auditor and regulator roles chooses the clause
for that decision and cannot count twice on the same proposal.

The component owns these records, but it does not reach into the actor-registry
namespace. An explicitly declared `role-approval-v1` workflow receives bounded
read-only views of both participant namespaces, validates actor eligibility,
and stages the accepted decision in the approval namespace atomically. This is
the ADR-013.2 cross-component coordinator model, not arbitrary sibling access.

### 4.3 Domain-signed decision

The outer app message remains signed by a current app-chain member. Its body
contains an actor-signed statement conceptually equivalent to:

```text
chainId
contract = yano-role-approval-v1
proposalId
policyId + policyRevision
payloadDomain + payloadHash
decision = APPROVE | REJECT
clauseId
actorId + actorRecordRevision
keyId
```

The signature preimage uses a fixed domain separator, canonical length-delimited
encoding, and all fields above. It must not use JSON serialization. Binding the
chain and policy prevents cross-chain and cross-policy replay; binding the
decision prevents changing APPROVE into REJECT; binding the payload hash
prevents approving different bytes under the same business identifier.

The member node may reject malformed input early, but every follower repeats
the complete deterministic verification during application. Invalid finalized
input is a deterministic no-op with a stable reason for diagnostics; it cannot
escape `apply()` and stall the block.

## 5. Authorization and governance semantics

### 5.1 Registry administration

The composite profile commits a bounded registry-administration policy:

```text
sorted administrator member public keys
required distinct administrator approvals
maximum pending mutation lifetime
```

Registry and policy changes follow propose, approve, and activate semantics.
The exact mutation bytes are hash-bound to the proposal. Only configured
administrator member senders count, duplicates are no-ops, and the required
threshold is profile-owned rather than selected by the proposer.

This member-governed bootstrap does not make domain actors consensus members.
It says that the consortium currently governs who is recognized as a business
actor. A later credential/governance adapter may impose stronger issuance
rules without changing the actor or approval query contracts.

Changing the administrator policy itself is a composite-profile change and
uses ADR-015. It is not a node-local configuration toggle.

### 5.2 Key registration and proof of possession

Registering or rotating to a domain key requires both:

- the governed registry mutation; and
- proof of possession signed by the proposed actor key over the exact chain,
  actor, key ID, public key, and validity interval.

This prevents an administrator typo from registering a key that nobody
controls. Private actor keys never enter Yano configuration or state.

### 5.3 Temporal rules

- Actor and organization status, role, and key validity are checked at the
  block height where the decision is accepted.
- The accepted decision snapshots actor ID, organization ID, role, record
  revision, key ID, and decision signature/hash.
- Later role removal, organization reassignment, suspension, or key revocation
  prevents future decisions but does not rewrite finalized history.
- A suspected compromised key requires revocation plus explicit cancellation
  or rejection of affected still-pending proposals. Terminal outcomes remain
  immutable and auditable.
- Proposal deadlines use deterministic block height or app-block time as
  frozen by the v1 contract; wall-clock checks in a gateway are advisory only.
- Policy changes create a new revision. A proposal remains bound to the exact
  revision selected at creation.

### 5.4 Rejection

V1 supports a small explicit set of rejection modes rather than arbitrary
expressions:

- `DISABLED`: the policy has no terminal reject command; or
- `ANY_ELIGIBLE`: any active actor eligible for a named policy clause may
  reject and make the proposal terminally rejected.

Additional reject thresholds require a new policy-contract version. A caller
cannot choose the rejection mode per proposal.

## 6. Composition and plugin packaging

The reusable implementation should be split by responsibility:

```text
appchain-role-workflow-contracts
  dependency-light command/result records
  canonical codecs and signature preimages
  client encoders/signers/verifiers
  CDDL and golden vectors

appchain-role-workflow
  DomainActorRegistry component
  RoleAwareApprovals component
  deterministic policy and crypto verification
  exact queries and testkit fixtures
```

Final module names may be adjusted to repository naming conventions, but the
contract/runtime dependency boundary is mandatory.

ADR-013.2 does not dynamically discover independent component plugins and
insert them into another composite through YAML. Therefore reuse works in two
supported forms:

1. a custom manifested app-state-machine plugin depends on the role-workflow
   library, assembles the components in an explicit committed order, and
   exports its composite provider; or
2. Yano ships a stock composite preset containing the components and its
   declared cross-component workflow.

The small Java composite assembly is consensus-critical. Component order,
topics, policy/configuration identity, workflow routes, and quotas are part of
the committed profile. Copying a plugin JAR into the plugin directory installs
the complete preset; operators do not dynamically reorder its components with
local YAML.

Suggested normal topics are:

```text
actors.command.v1
role-approvals.command.v1
```

`actors.command.v1` routes registry-governance commands to the registry
component. `role-approvals.command.v1` is owned by the declared
`role-approval-v1` workflow, whose participants are the registry and approval
component generations. The contract phase may refine names before freezing
v1. Reserved `~` topics remain framework-only.

No role-aware approval component calls a root reader or constructs a sibling
state key. Profile validation rejects missing/ambiguous participants and
undeclared namespace access, and the workflow runs in the committed descriptor
order with bounded input and output.

## 7. Domain workflow integration

Role approval is intentionally separated from the domain transition. An
evidence composite, for example, performs:

```text
manufacturer actor proposes exact evidence-release bytes
        |
        v
two distinct auditor organizations approve
        |
        v
one regulator approves
        |
        v
external application submits evidence.release.v1
        |
        v
composite verifies terminal role-approval outcome and exact payload hash
        |
        v
evidence/doc-trail state changes atomically and effects are emitted
```

Reaching an approval threshold does not synthesize an ordinary app message.
As in the current gated evidence flow, an external application or orchestrator
submits the final release command. From accepted release onward, deterministic
state application and configured effects are automatic.

Other composites reuse the same outcome but define their own terminal action:

| Composite | Example policy | Domain action after approval |
|---|---|---|
| Evidence/DPP | two auditor organizations + regulator | Release immutable evidence and publish connectors |
| Payment | requester + two treasury approvers | Emit a bounded Cardano payment effect |
| Credential | issuer + compliance approver | Add credential-status record |
| Oracle | source quorum + publisher role | Finalize an observation for publication |
| Asset workflow | custodian + owner organization | Authorize transfer intent |

An approval outcome is consumed once according to a domain-specific scope or
transition key. Exact replay remains a no-op.

### 7.1 Application usage after ADR-019

Domain actors plus role-aware policies form reusable authorization
infrastructure for a broad class of approval-driven applications. The policy
changes who must authorize exact business bytes; the selected stock or custom
composite defines what deterministic action follows that authorization.

Representative flows are:

```text
Evidence release
  manufacturer proposes
  + two auditor organizations
  + one regulator
  -> release immutable evidence

Payment authorization
  requester proposes
  + two treasury approvers
  -> emit the approved payment action

Credential issuance
  issuer proposes
  + one compliance officer
  -> add the credential/status record

Oracle publication
  required reporter quorum
  + one authorized publisher
  -> finalize and publish the datum

DPP lifecycle update
  manufacturer proposes
  + one certification body
  -> append the governed lifecycle event
```

These examples become configuration-only only when Yano already ships the
corresponding terminal-action component and stock composite preset. ADR-019
delivers reusable identity and authorization; it does not by itself implement
every payment, credential, oracle, or DPP state model. Production Cardano
payment/datum actions also remain gated by the safety work referenced by
ADR-010/012 and `FX-002`.

### 7.2 Three application-extensibility levels

#### Level 1 — configuration and governed data only

When the required components, policy semantics, and terminal action already
exist in a stock preset, an application team selects that preset and manages:

- organizations and actors;
- actor keys, roles, suspension, rotation, and revocation;
- approval policy IDs and revisions;
- counts and distinctness by actor or organization;
- deadlines and rejection behavior; and
- logical connector/effect targets supported by the preset.

Actors and approval policies are authenticated, governed app-chain state. They
are not independent node-local YAML values. Selecting a preset for a new chain
commits its profile identity; changing the preset of an existing governed chain
uses ADR-015 rather than an operator editing configuration on one node.

This is the no-code path for approval-centric applications whose business
transition is already in the first-party catalog.

#### Level 2 — small composite plugin

When the deterministic components already exist but the application needs a
new combination, ordering, or terminal transition, a Java team writes a small
composite plugin, for example:

```text
role-aware proposal reaches APPROVED
        |
        v
declared workflow calls existing registry/doc-trail/evidence component
        |
        v
existing effect component publishes the approved outcome
```

The plugin depends on the reusable component libraries, declares exact routes,
participants, order, quotas, and compatibility identities, and exports a
manifested `AppStateMachineProvider`. It must use the declared coordinator
contract for cross-component work; it cannot read arbitrary sibling state.

The resulting JAR and every required dependency bundle are installed on all
participating nodes and included in their executable catalogs. Yano itself is
not recompiled. New chains select the committed preset; existing governed
chains use ADR-015 readiness and height activation.

#### Level 3 — custom state-machine component/plugin

Genuinely new business state or transition rules require a custom deterministic
component. Examples include insurance-claim calculation, complex supply-chain
ownership, a domain-specific credential model, or a specialized oracle
aggregation rule.

The application team may still reuse actor registry, role approvals, document
trail, effects, queries, proof helpers, and connector bundles. Only the new
domain rule is custom. Its deterministic path must remain bounded and cannot
perform network, filesystem, wall-clock, random, or other external I/O;
external work is represented by an effect and executed outside consensus.

### 7.3 Configuration boundaries

“Configuration-only” does not mean every setting has the same trust or upgrade
semantics:

| Configuration domain | Examples | Authority and commitment |
|---|---|---|
| Governed application state | organizations, actors, keys, roles, policies, proposals | Changed by finalized governed commands and authenticated by the state root |
| Committed composite profile | component order, routes, workflows, versions, quotas, logical action types | Identical chain identity/profile on all members; evolves through ADR-015 |
| Executor/operator configuration | Kafka brokers, buckets, IPFS endpoints, credentials, retries | Node/executor-local operational configuration; secrets never enter consensus state |
| Gateway configuration | API keys, OIDC/mTLS, rate limits | Controls endpoint access; never substitutes for actor authorization |

The authenticated policy may select a bounded logical action or target ID that
the committed preset permits. Operators map that logical ID to deployment
endpoints and secrets. A proposal cannot inject a broker, bucket credential,
arbitrary URL, effect type, or uncommitted component route.

### 7.4 Resulting product boundary

After ADR-019 and the corresponding stock-action implementations:

- many approval-centric applications use no-code/governed configuration;
- new combinations of existing capabilities use a small composite plugin;
- new domain state and rules use a custom state-machine plugin; and
- core framework changes are generally unnecessary.

This is functional extensibility, not an unlimited performance claim. Every
consensus member still verifies the selected deterministic code, profile caps
remain effective, and each supported deployment needs load, resource, and
failure-envelope evidence.

ADR-019 is deliberately not a general workflow engine. Configuration cannot
invent terminal actions, dynamically reorder components, or create arbitrary
branching logic. Those choices affect consensus and therefore remain explicit
in a reviewed stock preset or manifested application plugin.

## 8. Query, proof, and audit surface

The components provide bounded exact queries for:

- organization by ID and revision;
- actor by ID and revision;
- actor-key epoch and status;
- approval policy by ID and revision;
- proposal status and satisfied/remaining clauses;
- accepted decision trail; and
- the exact payload hash and policy digest that reached a terminal outcome.

Authenticated state keys must support MPF proofs for these exact records. A
domain API plugin may aggregate them for UI convenience, but an aggregate JSON
response is not itself a proof unless it returns and verifies the underlying
root-fixed records.

Audit output distinguishes:

```text
relay member       outer AppMessage.sender
business actor     actorId/keyId in the signed decision
organization/role  registry facts snapshotted at acceptance
authorization      policy ID/revision and clause
business bytes     payload domain and hash
consensus evidence block height/root/finality certificate
```

This distinction must appear in UI, metrics, logs, SDK records, and operator
documentation. Showing only the relay node as “auditor” would be misleading.

## 9. Security and trust model

### 9.1 Guarantees

Subject to the normal app-chain finality assumptions, the design proves that:

- the actor decision signature verified under a registry key active at the
  accepted height;
- the actor and organization held the required active role/status;
- the exact policy revision and distinctness rules were satisfied;
- the decision was bound to the exact chain, proposal, and payload hash; and
- all honest members deterministically committed the same outcome and audit
  facts under the state root.

### 9.2 Explicit limitations

It does not independently prove:

- that the registry maps a key to the claimed real-world person or firm;
- that a signer understood the payload;
- that an auditor performed competent work;
- that an observation or document is factually true; or
- that a compromised but not-yet-revoked key reflected its owner's intent.

Those properties depend on registry onboarding, credential verification, key
custody, operational controls, and—where necessary—independent outcome or
real-world-source auditing.

### 9.3 Required hardening

- Bounded CBOR preflight and decoding in both admission and apply paths.
- Domain-separated signatures with golden vectors and a second independent
  verifier implementation before declaring the wire stable.
- Constant/bounded work per command, bounded roles/keys/clauses/decisions, and
  deterministic quota enforcement.
- No remote identity resolution, certificate fetching, DNS, wall clock, or
  node-local IAM lookup during deterministic execution.
- Sanitized rejection details; no actor secrets or full sensitive metadata in
  logs, metrics, state, or receipts.
- KMS/HSM/Vault-compatible client signing interfaces and documented recovery.
- Governance tests for malicious administrator proposals, duplicate votes,
  threshold drift, and partial activation.

## 10. Compatibility and evolution

- Existing chains and `approvals.command.v1` behavior do not change.
- A new role-aware stock preset has a different committed composite-profile
  digest and is for new chains or an ADR-015 governed activation.
- Component state/result compatibility IDs, exact routes, quotas, and policy
  configuration are committed under ADR-013.2/015.
- Actor, policy, command, signature, and state encodings are independently
  versioned. A v2 implementation cannot reinterpret v1 bytes.
- Policy revisions are data within the frozen v1 semantics; changing the
  semantics or available expression operators requires a contract/component
  version and governed profile activation.
- Registry governance-authority changes use the governed composite profile;
  ordinary role/key changes use the registry's own governed mutation flow.
- Prototype databases remain disposable until the v1 wire and state contracts
  pass the acceptance gates below. Once released, incompatible changes require
  explicit migration or a new component namespace.

## 11. Implementation plan

### Phase 19.1 — contracts and threat model

- Freeze bounded identifiers, record schemas, commands, result codes, CDDL,
  state keys, hashes, and signature preimages.
- Build dependency-light Java client encoders/signers/verifiers.
- Publish golden vectors and validate them with an independent implementation.
- Complete a focused threat model for registry governance, role escalation,
  relay substitution, cross-chain replay, key compromise, and organization
  distinctness.

### Phase 19.2 — actor registry component

- Implement organization, actor, role, key-epoch, and status records.
- Implement threshold-governed register/update/rotate/suspend/revoke commands.
- Add proof-of-possession and exact authenticated queries.
- Test deterministic replay, catch-up, rollback/reapply, key rotation,
  revocation, bounds, and hostile encodings.

### Phase 19.3 — role-aware approval component

- Implement versioned policy records and governed policy changes.
- Implement proposals, actor-signed approve/reject decisions, clause
  satisfaction, actor/organization deduplication, deadlines, and terminality.
- Add proof-oriented decision-trail queries and client verification.
- Retain the legacy member approvals as a separate compatibility component.

### Phase 19.4 — stock composite and evidence scenario

- Add a role-gated evidence preset without silently changing an existing
  released profile digest.
- Register a manufacturer, two auditors from distinct organizations, a second
  actor in one auditor organization, and a regulator.
- Demonstrate approvals relayed through arbitrary member nodes, including two
  different actor decisions through the same relay node.
- Show same-organization deduplication, wrong-role rejection, key revocation,
  exact payload binding, final release, connector effects, and proof queries.
- Extend the demo UI to distinguish relay member, actor, organization, role,
  and satisfied policy clauses.

### Phase 19.5 — plugin, SDK, operations, and documentation

- Package a complete manifested preset/plugin and document custom composite
  reuse.
- Provide Java SDK examples plus CLI/no-code signing and submission commands.
- Document actor-key custody, registry onboarding, rotation, compromise,
  revocation, pending-proposal cancellation, and disaster recovery.
- Add metrics for pending/terminal proposals and sanitized rejection classes;
  never label a relay member as the business approver.

## 12. Acceptance criteria

### 12.1 Deterministic component tests

- Identical commands produce identical state roots across independent members,
  replay, catch-up, and retained restart.
- Malformed CBOR, unknown operations, invalid bounds, and invalid signatures
  become deterministic rejection/no-op behavior and never escape `apply()`.
- Cross-chain, cross-proposal, cross-policy, cross-payload, and decision-flip
  replay attempts fail.
- Unknown, inactive, suspended, expired, and revoked organizations/actors/keys
  cannot create a decision.
- A role mismatch cannot satisfy a clause.
- Duplicate actor decisions do not count twice.
- Two actors in one organization count once for organization-distinct clauses.
- Two actors relayed through one member node still count independently when
  policy permits; one actor relayed through two nodes counts once.
- Policy and actor-record revisions used by accepted decisions remain stable
  after later registry changes.
- Deadline, terminality, cancellation, and exact replay are deterministic.
- Registry and policy mutations cannot lower their own authorization threshold.

### 12.2 Cluster end-to-end tests

On a three-member cluster:

1. Govern and prove the organization, actor, role, and policy records.
2. Submit a proposal through member A on behalf of a manufacturer actor.
3. Submit auditor A's decision through member B.
4. Submit a second actor from auditor A's organization through member C and
   prove that it does not satisfy an independent-organization slot.
5. Submit auditor B's decision through member A and regulator's decision
   through any member; observe the exact terminal policy outcome.
6. Submit the bound evidence release; verify state, effect identities,
   connector data, app finality, optional L1 anchor coverage, and MPF proofs.
7. Restart one member, catch it up, and prove root/query parity.
8. Rotate and revoke an actor key, then prove old/new-key behavior and retained
   historical auditability.

### 12.3 Review gates

- Consensus/state-machine review finds no unbounded or nondeterministic path.
- Security review covers key substitution, policy weakening, role escalation,
  signature malleability, replay, compromise, and governance recovery.
- Plugin review verifies class-loader ownership, native-image behavior,
  profile identity, lifecycle, and custom-composite reuse.
- Documentation makes consensus members, REST callers, relay members, domain
  actors, organizations, and L1/effect credentials unambiguous.

## 13. Consequences

### 13.1 Benefits

- Business identities no longer need to operate consensus nodes.
- Multiple employees, services, or devices can belong to one organization
  without inflating an independent-organization quorum.
- The same authorization building blocks work across many domain composites.
- Actor decisions remain portable, independently verifiable, and bound to
  exact bytes even when relayed by another organization.
- Consensus membership can rotate independently of business credentials.

### 13.2 Costs

- The registry becomes security-critical application state and needs governed
  onboarding and recovery procedures.
- Actor applications must manage domain signing keys and canonical client
  encodings.
- Policies and identity history increase state, proof, UI, and operational
  complexity.
- Revocation cannot erase or retroactively invalidate finalized authorization;
  remediation must be represented as new state and business actions.

## 14. Deferred extensions

- DID/VC credential adapters and selective disclosure.
- OIDC/mTLS-to-actor gateway policies that still require a portable domain
  signature before consensus submission.
- Hardware/device attestation profiles.
- Delegation, power of attorney, temporary role grants, and scoped subkeys.
- Threshold or aggregate domain signatures.
- Privacy-preserving role membership proofs and nullifier-based approvals.
- OR/NOT/nested policy expressions or weighted voting; v1 remains bounded AND
  clauses.
- Read-model indexes by role/organization when exact scans become operationally
  insufficient.
- Cross-chain actor-registry federation and credential trust frameworks.

## 15. Recommendation

Accept this ADR as the application-layer direction, but keep it `Proposed`
until Phase 19.1 freezes and independently validates the v1 contracts.

Implement it as reusable role-workflow contracts and deterministic components,
then expose complete stock/custom composite plugins. Do not retrofit domain
roles into app-chain membership, treat REST API keys as approver identities,
or weaken outer member authentication.
