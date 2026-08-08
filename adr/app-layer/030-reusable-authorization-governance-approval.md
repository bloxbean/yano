# ADR-0030: Extract Reusable Authorization, Governance, and Approval Capabilities from AuthenticatedMap

**Status:** Proposed
**Target:** Yano App Layer
**Scope:** AuthenticatedMap, state-machine runtime, stdlib state machines
**Decision type:** Architectural refactoring
**Compatibility goal:** Preserve current AuthenticatedMap behavior while extracting reusable capabilities

## 1. Context

The AuthenticatedMap state machine currently provides more than authenticated key/value state.

In addition to authenticated state and proofs, it supports update-control mechanisms such as:

* owner-controlled updates
* role-based updates
* governed updates
* approval-based operations

These capabilities are useful for AuthenticatedMap, but they are not inherently properties of authenticated storage.

They answer a different question.

Authenticated state answers:

> What is the current state, and can that state be cryptographically proven?

Authorization and governance answer:

> Who may cause a particular state transition, and under what conditions?

Approval answers:

> Does a proposed state transition have sufficient authorization to be executed?

As Yano adds additional state machines such as OrderLog, document-oriented state machines, oracle registries, workflows, eUTXO applications, registries, and custom application state machines, the same authorization and approval requirements are expected to recur.

Keeping these capabilities implemented specifically inside AuthenticatedMap would therefore lead to duplicated and potentially inconsistent governance implementations.

This ADR proposes extracting these concepts into reusable app-layer capabilities while keeping AuthenticatedMap's existing user-facing functionality.

---

# 2. Decision

Yano will separate the following four app-layer concepts:

1. **State**
2. **Authorization Policy**
3. **Approval / Proposal Lifecycle**
4. **Effects**

Authenticated state will remain concerned exclusively with deterministic state storage, commitments, and proofs.

Authorization policies will determine whether an actor is authorized to perform an operation.

The approval subsystem will manage operations that require one or more approvals before they may execute.

Effects will remain responsible for deterministic requests for interaction with systems outside the state machine.

The resulting conceptual architecture is:

```text
                    State Machine
                         |
       +-----------------+-----------------+
       |                 |                 |
      State          Authorization       Effects
       |                 |
Authenticated State    Policies
                         |
                       Approval
                       Lifecycle
```

AuthenticatedMap will continue exposing convenient owner-, role-, governed-, and approval-based behavior, but these features will internally use the reusable authorization and approval infrastructure.

---

# 3. Goals

The primary goals of this refactoring are:

* allow authorization policies to be reused by any state machine;
* avoid duplicating owner, role, governance, and approval implementations;
* keep authenticated state independent from access-control semantics;
* allow policies to apply to individual operations rather than only an entire state machine;
* make authorization deterministic and therefore safe for replicated state-machine execution;
* support both immediate authorization and deferred multi-party approval;
* preserve existing AuthenticatedMap functionality;
* provide a simple API for application developers;
* make future policies extensible through a controlled plugin/SPI mechanism;
* allow policy configuration itself to be governed;
* make authorization decisions auditable through deterministic state.

---

# 4. Non-goals

This ADR does not attempt to:

* create a general-purpose identity system;
* implement OAuth, OIDC, or web authentication;
* define network transport authentication;
* replace transaction/message signature verification;
* make authorization decisions using external nondeterministic systems;
* redesign authenticated-state commitments such as MPF or JMT;
* redesign the deterministic effects subsystem;
* implement a full DAO/governance framework.

The capability described here is specifically application-state-machine authorization and approval.

---

# 5. Architectural Principle

The most important rule introduced by this ADR is:

> **Authenticated state must not determine who is allowed to modify it.**

The lower-level authenticated-state abstraction should remain conceptually equivalent to:

```java
interface AuthenticatedState<K, V> {

    Optional<V> get(K key);

    void put(K key, V value);

    void remove(K key);

    StateRoot root();

    Proof proof(K key);
}
```

It should not evolve into:

```java
put(actor, role, approval, governance, key, value)
```

Authorization belongs above this layer.

Conceptually:

```text
State-machine action
       |
       v
Authorization
       |
       v
State transition
       |
       v
Authenticated state
       |
       v
MPF / JMT commitment
```

---

# 6. Authorization and Approval Are Different Concepts

Authorization and approval must be modeled separately.

## 6.1 Authorization

Authorization answers:

> Can this actor execute this action now?

Examples:

```text
owner may delete entry

EDITOR role may update document

ORACLE_REPORTER may publish observation

ADMIN may update configuration
```

Authorization normally produces an immediate deterministic result:

```text
ALLOW
DENY
```

## 6.2 Approval

Approval answers:

> Has this proposed operation received enough authorization to execute?

Example:

```text
Proposal:
    change oracle configuration

Required:
    3 of 5 governors

Current approvals:
    Alice
    Bob

Result:
    PENDING
```

After another valid approval:

```text
Alice
Bob
Carol

=> policy satisfied
=> operation becomes executable
```

The lifecycle is therefore:

```text
propose
   |
   v
PENDING
   |
 approve / reject
   |
   v
policy satisfied?
   |
   +---- no ----> PENDING
   |
  yes
   |
   v
EXECUTABLE
   |
   v
EXECUTED
```

Authorization policies may be used by the Approval Engine, but approval should not be embedded inside the basic policy interface.

---

# 7. Proposed Core Model

## 7.1 Actor

All authorization decisions should operate on a normalized application actor.

Conceptually:

```java
public interface Actor {

    ActorId id();

    ActorType type();
}
```

Possible actor types could include:

```text
KEY
ADDRESS
ACCOUNT
ROLE_PRINCIPAL
SYSTEM
```

The first implementation should reuse Yano's existing authenticated identity/message model wherever possible rather than introducing another identity format.

An actor must be derived deterministically from the submitted state-machine message.

State machines must not trust a caller-supplied string such as:

```json
{
  "actor": "alice"
}
```

without cryptographic authentication.

---

# 8. Action Context

Authorization should evaluate an action rather than merely a state machine.

Introduce a deterministic context similar to:

```java
public record AuthorizationContext(
        String stateMachine,
        String action,
        Actor actor,
        Object resource,
        Object command
) {}
```

The exact API may differ, but the important concept is:

```text
WHO
is attempting

WHAT ACTION
against

WHICH RESOURCE
with

WHICH COMMAND
```

This allows different policies for different operations.

For example:

```text
AuthenticatedMap.put        -> WRITER
AuthenticatedMap.remove     -> OWNER
AuthenticatedMap.changeRole -> GOVERNANCE
```

rather than:

```text
AuthenticatedMap -> GOVERNED
```

---

# 9. AuthorizationPolicy SPI

Introduce a small deterministic policy abstraction.

For example:

```java
public interface AuthorizationPolicy {

    PolicyDecision evaluate(
        AuthorizationContext context,
        PolicyState state
    );
}
```

Where:

```java
public enum PolicyDecision {
    ALLOW,
    DENY
}
```

Policy evaluation MUST:

* be deterministic;
* perform no external I/O;
* perform no system-clock access;
* perform no random operations;
* produce the same result on every Yano replica;
* depend only on committed application state and deterministic message context.

---

# 10. Initial Built-in Policies

The first extraction should support the capabilities already available through AuthenticatedMap rather than introducing unnecessary new policy types.

Recommended initial policies:

```text
OwnerPolicy
RolePolicy
GovernedPolicy
```

Potentially also:

```text
AllowListPolicy
ThresholdPolicy
```

if equivalent concepts already exist in the implementation.

## OwnerPolicy

Example:

```text
resource.owner == actor
```

Suitable for:

* personal records;
* user-owned maps;
* configuration objects;
* document ownership;
* asset ownership-related administrative actions.

---

## RolePolicy

Example:

```text
actor has role WRITER
```

Roles should themselves live in deterministic state.

Examples:

```text
ADMIN
WRITER
EDITOR
APPROVER
ORACLE_REPORTER
BRIDGE_OPERATOR
```

Role names should be application-defined rather than requiring every possible role to be part of Yano core.

---

## GovernedPolicy

GovernedPolicy represents operations controlled by an application's governance configuration.

Its implementation should be based on reusable policy/approval primitives rather than special AuthenticatedMap logic.

Example:

```text
change schema
    -> governance

change role assignment
    -> governance

change policy
    -> governance
```

Governance should ideally become composition over authorization and approval rather than an independent hard-coded execution model.

---

# 11. Policy Binding

Policies should be bound to **actions**.

For example:

```yaml
authorization:

  put:
    policy: writers

  remove:
    policy: owner

  change-schema:
    policy: governance

  change-policy:
    policy: governance
```

or programmatically:

```java
policies.bind("put", "writers");
policies.bind("remove", "owner");
policies.bind("change-schema", "governance");
```

The exact configuration syntax is an implementation decision.

The architectural requirement is that policies may differ between operations.

---

# 12. Policy Composition

The architecture should allow future composition without making the first implementation overly complex.

Eventually policies may support:

```text
AND
OR
THRESHOLD
```

For example:

```text
OWNER OR ADMIN
```

or:

```text
ROLE_EDITOR AND ACTIVE_ACCOUNT
```

or:

```text
3 OF 5 GOVERNORS
```

A possible internal representation:

```text
Policy
├── owner
├── role:EDITOR
├── anyOf
│   ├── role:ADMIN
│   └── owner
└── threshold
    ├── required: 3
    └── members: governors
```

This does not need to be implemented fully in the first refactoring phase.

The abstraction should simply avoid preventing it.

---

# 13. Approval Engine

Introduce an application-level reusable Approval Engine.

The Approval Engine manages proposals independently from the business state machine.

A proposal should conceptually contain:

```java
Proposal {
    ProposalId id;

    String targetStateMachine;

    String targetAction;

    byte[] commandHash;

    Actor proposer;

    PolicyId approvalPolicy;

    ProposalStatus status;

    List<Approval> approvals;
}
```

The original command or canonical command representation must be bound cryptographically to the proposal.

Approvers must not be able to approve:

```text
transfer 10 ADA
```

and later have that approval reused for:

```text
transfer 100 ADA
```

Therefore approvals must be tied to something equivalent to:

```text
hash(
    stateMachineId
    || action
    || canonicalCommand
    || proposalNonce
)
```

---

# 14. Proposal Lifecycle

Recommended lifecycle:

```text
PROPOSED
   |
   +---- approve ----+
   |                 |
   v                 |
PENDING              |
   |                 |
   +---- threshold satisfied
   |
   v
APPROVED
   |
   v
EXECUTED
```

Additional terminal states may include:

```text
REJECTED
CANCELLED
EXPIRED
```

Expiration should initially be expressed in deterministic chain concepts such as:

```text
block height
slot
logical state-machine height
```

rather than wall-clock timestamps if wall-clock use could introduce nondeterminism.

---

# 15. Who Executes an Approved Proposal?

Approval satisfaction and business execution should remain separate concepts.

The Approval Engine should not directly modify arbitrary application state.

Instead:

```text
Proposal approved
      |
      v
Approval Engine marks APPROVED
      |
      v
target action becomes executable
      |
      v
State-machine runtime executes command
```

This keeps business semantics inside the target state machine.

An optional future capability could automatically schedule execution once approval is complete.

That should use deterministic internal messaging rather than direct cross-module mutation.

---

# 16. Policy State

Authorization configuration must itself be deterministic application state.

Examples include:

```text
owners
roles
role membership
governance members
thresholds
policy bindings
pending proposals
approvals
```

Policy configuration should therefore participate in normal Yano state persistence and replication.

Where useful, policy state may itself use AuthenticatedState.

For example:

```text
roles[address] -> [EDITOR, APPROVER]
```

may be stored in an authenticated map.

However, this is implementation reuse, not architectural coupling.

The dependency direction should remain:

```text
Policy Engine
       |
       v
may use AuthenticatedState
```

and not:

```text
AuthenticatedState
       |
       v
depends on Policy Engine
```

---

# 17. Bootstrapping and the Governance Paradox

Special care is required for policy administration.

Consider:

```text
change governance policy
requires governance approval
```

This creates the question:

> How is the initial governance policy created?

Every policy-enabled state machine must therefore have deterministic genesis/bootstrap configuration.

For example:

```yaml
authorization:
  genesis:
    owner: addr1...
```

or:

```yaml
governance:
  initial-members:
    - key1
    - key2
    - key3
  threshold: 2
```

After genesis, normal governance rules protect modifications.

There must be no undocumented administrator bypass.

---

# 18. Protecting Policy Changes

Policy configuration is often more sensitive than normal application state.

For example:

```text
put value
```

may require:

```text
WRITER
```

while:

```text
grant WRITER
```

must require:

```text
GOVERNANCE
```

Otherwise a writer could simply grant itself administrator rights.

Recommended built-in administrative actions include:

```text
policy.assign
policy.update
role.grant
role.revoke
governance.update
owner.transfer
```

Each must itself have an explicit policy binding.

---

# 19. AuthenticatedMap After Refactoring

From the user's perspective, AuthenticatedMap should remain simple.

Existing usage should continue to support concepts such as:

```text
OWNER
ROLE
GOVERNED
APPROVAL
```

The implementation changes from:

```text
AuthenticatedMap
 ├── MPF/JMT
 ├── owner implementation
 ├── role implementation
 ├── governance implementation
 └── approval implementation
```

to:

```text
AuthenticatedMap State Machine
       |
       +---- AuthenticatedState
       |       |
       |       +---- MPF
       |       +---- JMT
       |
       +---- AuthorizationService
       |
       +---- ApprovalService
```

This is primarily an internal architecture refactor.

---

# 20. Example: AuthenticatedMap

A map could declare:

```yaml
state-machine:
  type: authenticated-map

authorization:

  put:
    policy:
      role: WRITER

  remove:
    policy:
      owner: true

  change-schema:
    policy:
      governance: true
```

The developer does not need to understand the internal Policy Engine.

---

# 21. Example: OrderLog

OrderLog could reuse the same capability.

```yaml
authorization:

  append:
    policy:
      role: PUBLISHER

  change-publishers:
    policy:
      governance: true
```

This would allow Yano to provide authenticated ordered data where only approved publishers may append records.

Potential uses include:

```text
oracle feeds
audit logs
supply-chain events
regulated business events
```

---

# 22. Example: Document Registry

```text
create document
    -> AUTHOR

update draft
    -> AUTHOR or EDITOR

publish
    -> approval from REVIEWER

change reviewers
    -> GOVERNANCE
```

The document state machine does not implement its own role or approval infrastructure.

---

# 23. Example: Oracle

An oracle state machine could declare:

```text
submit observation
    -> ORACLE_REPORTER

publish aggregate
    -> deterministic oracle rules

change reporters
    -> 3/5 governance approval
```

This is an important use case because it demonstrates authorization + approval independently of AuthenticatedMap.

---

# 24. Example: eUTXO / Bridge

Normal eUTXO spending should continue using transaction ownership/script validation rather than generic RolePolicy.

However generic policies remain useful for administrative operations:

```text
change bridge parameters
    -> federation governance

rotate federation member
    -> threshold approval

pause fast settlement
    -> emergency governance policy
```

The generic Policy Engine therefore complements specialized state-machine authorization rather than attempting to replace it.

---

# 25. Specialized Authorization Must Remain Possible

Not every state-machine rule should be forced through generic policy definitions.

For example:

```text
UTxO may be spent only if transaction witnesses satisfy locking condition
```

is business/state-machine logic.

Likewise:

```text
account balance must be >= transfer amount
```

is state transition validation, not authorization policy.

A useful distinction is:

```text
Policy Engine
    WHO may invoke something?

State-machine validation
    IS this state transition valid?
```

Both must succeed.

Conceptually:

```text
message
   |
   v
authenticate actor
   |
   v
authorize action
   |
   v
validate business rules
   |
   v
execute transition
```

---

# 26. Runtime Integration

The state-machine runtime should provide a standard authorization hook.

Conceptually:

```java
handle(command) {

    actor = actorResolver.resolve(command);

    authorization.requireAllowed(
        stateMachineId,
        action,
        actor,
        command
    );

    validate(command);

    transition(command);
}
```

Individual state machines should not repeatedly implement:

```java
if (!roles.contains(...)) ...
```

unless authorization is genuinely domain-specific.

---

# 27. Possible API Shape

One possible Java API is:

```java
public interface AuthorizationService {

    PolicyDecision evaluate(
        String stateMachineId,
        String action,
        Actor actor,
        Object command
    );

    void requireAllowed(
        String stateMachineId,
        String action,
        Actor actor,
        Object command
    );
}
```

Policies:

```java
public interface AuthorizationPolicy {

    PolicyDecision evaluate(
        AuthorizationContext context
    );
}
```

Policy providers:

```java
public interface PolicyProvider {

    String type();

    AuthorizationPolicy create(
        PolicyDefinition definition
    );
}
```

This last abstraction allows future extension without requiring arbitrary runtime code execution.

---

# 28. Deterministic Plugin Model

If policy extensions are exposed through an SPI, policy plugins MUST satisfy Yano deterministic-execution restrictions.

Forbidden:

```text
HTTP calls
database calls outside deterministic state
wall-clock decisions
randomness
filesystem dependencies
environment-variable-dependent decisions
```

Allowed:

```text
command content
authenticated actor
current deterministic state
block/chain context explicitly supplied by runtime
configured deterministic parameters
```

A plugin violating deterministic requirements must not be eligible for replicated consensus execution.

---

# 29. Policy Versioning

Policies are consensus-critical state-machine behavior.

Changing:

```text
role: WRITER
```

to:

```text
role: ADMIN
```

changes which transitions are accepted.

Policy definitions therefore require explicit versioning and deterministic updates.

Recommended identity:

```text
policy-id
policy-version
```

A proposal should reference the exact policy version under which it was created.

Otherwise governance changes occurring while a proposal is pending could produce ambiguous behavior.

Recommended initial rule:

> A proposal is evaluated using the approval policy/version captured when the proposal was created.

Alternative behavior may be introduced later explicitly.

---

# 30. Canonicalization

Approval signatures and proposal hashes require canonical action representation.

Never hash arbitrary JSON serialization directly unless Yano guarantees canonical encoding.

Create or reuse a canonical command representation.

For example:

```text
proposalHash =
    hash(
        protocolVersion,
        appChainId,
        stateMachineId,
        action,
        canonicalCommand,
        nonce
    )
```

Include sufficient domain separation to prevent approval replay between:

```text
different chains
different state machines
different actions
different environments
```

---

# 31. Replay Protection

Approvals must not be replayable.

A proposal requires a unique deterministic identifier or nonce.

After execution:

```text
ProposalId -> EXECUTED
```

must prevent the same authorization from executing the operation again.

This is particularly important for operations such as:

```text
transfer
withdraw
change owner
grant role
update bridge configuration
```

---

# 32. Auditability

Authorization decisions and governance actions should be observable.

At minimum Yano should expose deterministic records for:

```text
proposal created
approval recorded
approval revoked, if supported
proposal approved
proposal rejected
proposal executed
role granted
role revoked
owner transferred
policy changed
```

Whether these are stored as state-machine history, events, OrderLog entries, or runtime events can be decided separately.

The important requirement is that governance changes are explainable after the fact.

---

# 33. Read Authorization

The initial scope should focus on **state transitions**, not read authorization.

Authenticated state and proofs are most useful when independently verifiable, and network/API access control is a separate concern.

Therefore:

```text
authorization policy
```

initially controls mutations/actions.

API-level private read access may be implemented separately if needed.

Do not mix HTTP/API authentication with consensus state-machine authorization.

---

# 34. Failure Model

Authorization failures should have standardized deterministic error categories.

For example:

```text
AUTHENTICATION_REQUIRED
POLICY_NOT_FOUND
NOT_AUTHORIZED
ROLE_REQUIRED
OWNER_REQUIRED
APPROVAL_REQUIRED
APPROVAL_PENDING
PROPOSAL_ALREADY_EXECUTED
INVALID_APPROVAL
POLICY_VERSION_MISMATCH
```

Do not expose policy implementations through arbitrary exception strings.

This also helps client SDKs.

---

# 35. Refactoring Plan

The refactor should be incremental.

## Phase 1 — Identify Existing AuthenticatedMap Capabilities

Document the current behavior of:

```text
owner
role
governed
approval
```

including:

* persisted data;
* commands/actions;
* APIs;
* configuration;
* state transition rules;
* serialization formats;
* tests.

Before extracting anything, create behavioral tests describing current semantics.

These tests become compatibility tests during refactoring.

---

## Phase 2 — Introduce Authorization Core

Create a new reusable module/package.

Possible structure:

```text
yano-app-layer-auth
```

or within the existing app-layer module:

```text
app/
  auth/
    Actor
    AuthorizationContext
    AuthorizationPolicy
    AuthorizationService
    PolicyDefinition
    PolicyRegistry
```

Do not modify AuthenticatedMap behavior yet.

---

## Phase 3 — Extract Owner Policy

Owner is the simplest policy and therefore the best first extraction.

Move ownership evaluation from AuthenticatedMap-specific code into:

```text
OwnerPolicy
```

AuthenticatedMap delegates to the new policy service.

Run all compatibility tests.

This validates the basic architecture before tackling roles/governance.

---

## Phase 4 — Extract Role Management

Move role evaluation and deterministic role state into reusable components.

Suggested separation:

```text
RoleRegistry
RolePolicy
```

Where:

```text
RoleRegistry
    manages membership

RolePolicy
    evaluates membership
```

Avoid coupling role membership directly to AuthenticatedMap keys.

Run existing AuthenticatedMap role tests unchanged where possible.

---

## Phase 5 — Extract Governance Policy

Move governed-operation authorization to the common policy infrastructure.

At this point:

```text
AuthenticatedMap
```

should no longer own generic governance semantics.

It merely declares:

```text
this action requires governance policy X
```

---

## Phase 6 — Extract Approval Engine

Extract:

```text
proposal
approval
threshold evaluation
execution state
replay prevention
```

into a generic app-layer service.

Ensure proposal hashes bind:

```text
chain
state machine
action
command
policy version
nonce
```

AuthenticatedMap then uses the generic Approval Engine.

---

## Phase 7 — Action-Level Policy Binding

Replace any AuthenticatedMap-wide authorization mode with action-level bindings internally.

Provide backward compatibility mapping.

For example an existing configuration such as:

```text
mode = OWNER
```

could internally become:

```text
put    -> owner
remove -> owner
admin  -> owner
```

without changing existing configuration immediately.

---

## Phase 8 — Reuse in a Second State Machine

Do not declare the extraction successful until another state machine uses it.

Recommended first candidate:

```text
OrderLog
```

because its semantics are simple.

Example:

```text
append -> PUBLISHER role
configuration change -> governance
```

This validates that the abstraction is genuinely reusable rather than merely AuthenticatedMap code moved into another package.

---

## Phase 9 — Reuse in a More Complex State Machine

Next validate using one richer scenario.

Recommended candidates:

```text
Oracle
```

or:

```text
Document Registry
```

because they exercise both:

```text
roles
+
approval/governance
```

---

## Phase 10 — Deprecate AuthenticatedMap-Specific Implementations

Only after compatibility has been proven:

* remove duplicated owner code;
* remove duplicated role code;
* remove duplicated governance code;
* remove duplicated approval code.

AuthenticatedMap keeps only adapters/configuration conveniences.

---

# 36. Suggested Module Dependency Direction

Recommended dependency direction:

```text
                    app-runtime
                        |
           +------------+-------------+
           |                          |
      authorization                 effects
           |
      approvals
           |
      deterministic-state
           |
     authenticated-state
       /          \
     MPF          JMT
```

Avoid dependencies such as:

```text
authenticated-state
      ↓
authorization
```

or:

```text
authorization
      ↓
authenticated-map state machine
```

The generic layers should not depend on individual stdlib state machines.

---

# 37. Testing Strategy

This refactoring affects consensus-critical behavior and requires strong tests.

## Unit tests

Each policy:

```text
owner accepted
non-owner rejected

role member accepted
non-member rejected

governance threshold accepted
insufficient approval rejected
```

## Determinism tests

Run identical authorization decisions across independently initialized runtime instances.

Expected:

```text
same input + same state
=
same decision + same resulting root
```

## Compatibility tests

Before and after refactoring:

```text
AuthenticatedMap command stream
        |
        v
same state root
same accepted/rejected commands
same observable state
```

where serialization changes are not intentionally introduced.

## Replay tests

Verify that:

```text
same approval
same proposal
same command
```

cannot execute twice.

## Policy mutation tests

Test:

```text
grant role
revoke role
change owner
update governance threshold
change policy binding
```

and ensure authorization changes exactly when expected.

## Cross-state-machine tests

Use the same `RolePolicy` against both:

```text
AuthenticatedMap
OrderLog
```

to prove reuse.

---

# 38. Security Invariants

The following invariants should be explicitly tested.

### Invariant 1

No protected state transition may execute without successful authorization.

### Invariant 2

An approval may authorize only the exact proposal it was created for.

### Invariant 3

An executed proposal cannot execute again.

### Invariant 4

Role membership changes themselves require their configured administrative authorization.

### Invariant 5

Policy changes cannot bypass the policy protecting policy changes.

### Invariant 6

Policy evaluation is deterministic across replicas.

### Invariant 7

An authenticated actor cannot be replaced by an unauthenticated actor value supplied in command payload.

### Invariant 8

Policy/version changes cannot silently reinterpret existing approvals.

---

# 39. Developer Experience

The internal architecture should become more generic while the user experience remains simple.

A developer creating a state machine should ideally be able to express:

```java
action("update")
    .authorize("editors");

action("delete")
    .authorize("owner");

action("change-policy")
    .authorize("governance");
```

or equivalent declarative configuration.

Advanced users may define custom policies through an SPI.

Simple applications should not need to interact directly with:

```text
ProposalStore
RoleRegistry
PolicyRegistry
ApprovalEngine
```

unless they need custom behavior.

---

# 40. Backward Compatibility

Because Yano is still evolving rapidly, a clean architecture is more important than preserving every preview API indefinitely.

However, this refactoring should avoid unnecessary user-facing breakage.

Recommended strategy:

```text
current AuthenticatedMap configuration
             |
             v
compatibility adapter
             |
             v
new Policy / Approval infrastructure
```

Once the new APIs stabilize, old configuration forms may be deprecated.

If serialized consensus state changes, provide explicit migration/version handling rather than silently changing representation.

---

# 41. Naming Recommendation

Avoid naming the reusable subsystem:

```text
AuthenticatedMapApproval
AuthenticatedMapGovernance
MapRole
```

Prefer generic names:

```text
AuthorizationPolicy
PolicyRegistry
RoleRegistry
ApprovalEngine
Proposal
GovernancePolicy
OwnerPolicy
RolePolicy
```

Likewise, avoid calling everything "governance."

Governance is one way of satisfying authorization requirements.

The broader abstraction is:

```text
Authorization
```

with:

```text
Approval
```

providing deferred/multi-party authorization.

---

# 42. Expected Result

After this refactoring, Yano should support:

```text
                        Yano App Layer

     ┌──────────────────────────────────────────┐
     │              State Machines              │
     │                                          │
     │ AuthMap  OrderLog  Oracle  Docs  eUTXO  │
     └───────────────┬──────────────────────────┘
                     │
          ┌──────────┼───────────┐
          │          │           │
          ▼          ▼           ▼
     Authorization  State      Effects
          │          │
     ┌────┴────┐ Authenticated
     │         │     State
   Policies Approval   │
                       │
                    MPF/JMT
```

This gives Yano four reusable application primitives:

```text
1. Deterministic state
2. Authenticated state
3. Authorization + approvals
4. Deterministic effects
```

State machines compose these primitives rather than reimplementing them.

---

# 43. Acceptance Criteria

The refactoring is complete when all of the following are true:

* AuthenticatedState contains no owner/role/governance-specific logic.
* Generic authorization policies are available at the app-layer level.
* OwnerPolicy is reusable outside AuthenticatedMap.
* RolePolicy and role membership are reusable outside AuthenticatedMap.
* Governance/approval functionality is reusable outside AuthenticatedMap.
* Policies can be assigned per state-machine action.
* Existing AuthenticatedMap owner/role/governed behavior continues to work.
* AuthenticatedMap delegates authorization to the common subsystem.
* At least one additional stdlib state machine uses the common authorization subsystem.
* Approval hashes are domain-separated and replay-safe.
* Policy evaluation is deterministic.
* Policy state is replicated/persisted as deterministic application state.
* Policy administration is itself protected by explicit policies.
* Compatibility and determinism tests pass.
* No state-machine implementation is required to duplicate generic role/governance logic.

---

# 44. Recommended Implementation Order

The recommended implementation sequence is:

```text
1. Characterize existing behavior with tests

2. AuthorizationPolicy + AuthorizationContext

3. OwnerPolicy extraction

4. RoleRegistry + RolePolicy extraction

5. GovernancePolicy extraction

6. Generic Proposal / ApprovalEngine

7. Action-level policy bindings

8. Migrate AuthenticatedMap fully

9. Add authorization to OrderLog

10. Validate approval reuse with Oracle/Document example

11. Remove remaining AuthenticatedMap-specific duplicates

12. Document policy SPI and developer-facing DSL/configuration
```

Do not attempt to redesign all stdlib state machines at once.

AuthenticatedMap should be the migration source, OrderLog the first reuse proof, and a richer application should validate the approval model.

---

# 45. Consequences

## Positive

Yano gains a reusable authorization model across all application state machines.

AuthenticatedMap becomes simpler conceptually.

New state machines can obtain owner, role, governance, and approval semantics without writing security-sensitive infrastructure.

Authorization behavior becomes consistent and testable.

Governance becomes composable with arbitrary application state rather than being tied to one storage abstraction.

It becomes possible to build higher-level applications such as:

```text
oracle networks
document approval systems
controlled registries
treasuries
supply-chain applications
multi-party workflows
bridge administration
```

using common infrastructure.

## Negative

The state-machine runtime becomes slightly more sophisticated.

Policy state and versioning become consensus-critical concepts.

Approval lifecycle semantics must be carefully defined.

Care must be taken not to turn the policy subsystem into an overly general authorization language prematurely.

There is an additional migration burden for AuthenticatedMap.

---

# 46. Risks

The largest architectural risk is over-engineering.

The first implementation SHOULD NOT attempt to create a universal policy language.

Start with the semantics Yano already needs:

```text
owner
role
governed
approval
```

Extract those cleanly.

Generalize only after another state machine demonstrates the requirement.

The second major risk is confusing authorization with business validation.

Keep the boundary explicit:

```text
Authorization:
    Is this actor allowed to request this operation?

Validation:
    Is the requested state transition valid?
```

The third major risk is making ApprovalEngine capable of directly mutating arbitrary state. Avoid this. It should authorize execution; the target state machine should own the resulting transition.

---

# 47. Final Decision Summary

The current AuthenticatedMap owner, role, governed, and approval capabilities are valuable and should remain available to AuthenticatedMap users.

However, they should no longer be architecturally owned by authenticated state.

Yano will extract them into reusable app-layer **Authorization Policy** and **Approval** capabilities.

The final dependency model should be:

```text
State Machine
    |
    +---- Authorization Policies
    |
    +---- Approval Engine
    |
    +---- State
    |       |
    |       +---- Authenticated State
    |                |
    |                +---- MPF/JMT
    |
    +---- Effects
```

AuthenticatedMap becomes one consumer of these capabilities rather than their implementation boundary.

This refactoring should be performed incrementally, beginning with OwnerPolicy, followed by roles, governance, approvals, and finally reuse by another state machine.

The key design principle is:

> **Proof of state, permission to change state, and approval to execute a change are separate concerns and should remain composable.**
