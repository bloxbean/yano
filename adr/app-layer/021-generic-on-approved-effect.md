# ADR-021: Generic On-Approved Effects for the Standard Approvals State Machine

## Status

Accepted and implemented — version 3

Version 3 records the completed cross-component implementation and validation
review. It adds shared runtime/tooling parsing, generic devtool capability
expansion, effect-framework payload-bound admission, exact result correlation,
and packaged demo/tutorial updates. Version 2 incorporated the decision that
appchain and the approvals effect extension have not been released or
deployed. The pre-release payment configuration and state branch are therefore removed
rather than retained as compatibility behavior. Runtime and tooling reject the
obsolete keys with a migration hint.

The number is local to the `adr/app-layer` series. Root-level ADR-021, if one
exists, is unrelated.

## Date

2026-07-20

## Parent and related decisions

- [ADR-005](005-yano-app-chain-framework.md) owns deterministic state-machine
  execution, authenticated state, finality, and replay.
- [ADR-006](006-appchain-enterprise-extensions-and-zk.md) introduced the
  standard member-approval state machine.
- [ADR-010](010-deterministic-effect-system.md) owns effect intents, gates,
  execution, result incorporation, expiry, and proofs.
- [ADR-010.1](010.1-emission-versioning.md) requires height-gated,
  replay-stable transition and emission changes.
- [ADR-013](013-first-party-integration-connectors-and-effect-demo.md) owns the
  first-party executor types and maintained effect demonstrations.

---

## 0. In plain words

The standard `approvals` machine currently has an optional feature named
`payments`. When an item receives enough approvals, that feature emits one
effect using the proposal payload. The configured effect type is technically
arbitrary, so the local demonstration routes it as `demo.webhook`, and the
production tutorial routes it as `webhook.post`.

That works mechanically but exposes payment-specific concepts everywhere:

```text
machines.approvals.payments
machines.approvals.payment-type
STATUS_PAID
STATUS_PAY_FAILED
paymentKey(...)
paymentRefKey(...)
```

A successful webhook notification is not a payment, and a failed ERP call does
not undo the approval decision. Reusing payment names for every external action
creates a misleading public model and makes future integrations harder to
extend safely.

This ADR replaces the payment-shaped extension for new configurations with one
generic, deterministic **on-approved effect**:

```yaml
machines:
  approvals:
    on-approved-effect:
      enabled: true
      type: demo.webhook
      gate: app-final
      expiry-blocks: 100
    activations:
      on-approved-effect: 1
```

The approval decision and the attached effect execution are committed as
separate state dimensions:

```text
decision = APPROVED
effect   = PENDING | CONFIRMED | FAILED
```

This keeps the state truthful: an external action may fail while the approval
remains approved.

## 1. Context and problem

### 1.1 Current behavior

`ApprovalsStateMachine` accepts:

```text
PROPOSE [itemId, payload, requiredApprovals, deadlineMillis]
APPROVE [itemId]
REJECT  [itemId]
```

Without the optional extension, it records a terminal decision of `APPROVED`,
`REJECTED`, or `EXPIRED`. With `machines.approvals.payments=true` and the
`payments` activation present, it additionally:

1. parks the proposal payload under a payment-specific state key;
2. emits one `ResultPolicy.CHAIN` effect on final approval;
3. changes the item from `APPROVED` to `PAID` or `PAY_FAILED`; and
4. stores an executor-supplied external reference.

The implementation is correct for an expense-approval-to-payment scenario.
The problem is treating it as the general mechanism for webhooks, Kafka,
object storage, ERP calls, issuance, and custom plugin actions.

### 1.2 An effect type is routing, not domain semantics

An effect type such as:

```text
cardano.payment
webhook.post
kafka.publish
object.put
demo.webhook
com.acme.erp.create-order
```

selects an executor contract. It does not imply that the approval item's
business state is `PAID`. The standard approvals machine should express the
shared invariant—"run one action after final approval"—without inventing the
action's domain-specific terminal status.

### 1.3 Configuration cannot invent transition logic

This ADR does not turn YAML into a workflow language. Configuration selects one
implemented transition with bounded parameters. Multiple actions, conditional
branches, action-specific payload transformations, compensation, and
cross-component workflows remain custom/composite state-machine concerns.

### 1.4 Consensus and replay consequences

The extension affects:

- whether a proposal payload is retained;
- whether and which `EffectIntent` is emitted;
- effect scope and ordered effect roots;
- application state keys and values;
- result-driven state transitions; and
- the final state root.

It is therefore consensus-critical. Renaming configuration or changing state
semantics without a replay plan can prevent a node from catching up to an
existing certified history.

## 2. Goals and non-goals

### 2.1 Goals

- Give the stock approvals machine one accurately named, generic
  approval-to-effect capability.
- Route to any exact effect type without payment-specific state names.
- Preserve the approval decision independently of effect execution outcome.
- Emit at most one idempotently scoped effect per approval item.
- Keep effect payloads opaque to the approvals machine.
- Require an explicit activation height and deterministic replay behavior.
- Make the maintained `demo.webhook`, production `webhook.post`, and
  `cardano.payment` examples use the same neutral configuration model.
- Keep the implementation small enough to remain an understandable stock
  state machine.

### 2.2 Non-goals

- A general YAML workflow or rules engine.
- An arbitrary list of actions after approval.
- Parallel, sequential, conditional, or compensating action graphs.
- Payload templating or transformation inside the approvals machine.
- Discovering executor credentials or endpoints from replicated payloads.
- Guaranteeing exactly-once external side effects; executors remain
  at-least-once and receivers must honor the effect idempotency key.
- Redefining approval identities or adding domain roles; ADR-019 owns that
  separate concern.
- Supporting or migrating the unreleased payment-specific configuration and
  state model.

## 3. Decision summary

1. Add one canonical configuration group:

   ```text
   machines.approvals.on-approved-effect.enabled
   machines.approvals.on-approved-effect.type
   machines.approvals.on-approved-effect.gate
   machines.approvals.on-approved-effect.expiry-blocks
   machines.approvals.activations.on-approved-effect
   ```

2. The feature emits exactly one `ResultPolicy.CHAIN` effect when an item first
   reaches its required approval count.
3. The effect payload is the exact opaque payload from `PROPOSE`.
4. The effect uses scope `approvals/on-approved/<itemId>` and links to the
   approval message that reached the threshold.
5. The approval item remains `APPROVED`. Effect lifecycle is stored in one
   separate, provable neutral record: `PENDING`, `CONFIRMED`, or `FAILED`.
6. `FAILED`, `CANCELLED`, and `EXPIRED` effect outcomes all map to neutral
   effect state `FAILED`; the exact outcome remains in the effect-state record
   and framework effect record.
7. Proposal payloads are staged only for proposals finalized at or after the
   activation height. Pre-activation proposals never emit retroactively.
8. The outer chain must also have `effects.enabled=true`. Execution remains a
   separate node-local choice; an emitted effect may remain pending without an
   executor.
9. The unreleased `payments` keys and payment-specific state/status branch are
   removed. Runtime and tooling reject those keys; they are never aliases.
10. Multiple configurable post-approval effects remain out of scope; use a
    committed composite or custom state machine.

## 4. Configuration contract

### 4.1 Canonical YAML

```yaml
yano:
  app-chain:
    chains[0]:
      chain-id: approval-effects-v1
      state-machine: approvals

      effects:
        enabled: true
        default-gate: app-final

      machines:
        approvals:
          on-approved-effect:
            enabled: true
            type: webhook.post
            gate: app-final
            expiry-blocks: 1000
          activations:
            on-approved-effect: 1
```

The flattened keys supplied through `AppStateMachineContext.settings()` are:

```properties
machines.approvals.on-approved-effect.enabled=true
machines.approvals.on-approved-effect.type=webhook.post
machines.approvals.on-approved-effect.gate=app-final
machines.approvals.on-approved-effect.expiry-blocks=1000
machines.approvals.activations.on-approved-effect=1
```

### 4.2 Field semantics

| Field | Default | Rule |
|---|---:|---|
| `enabled` | `false` | Enables the generic branch, subject to activation |
| `type` | none | Required, non-blank exact effect-routing type when enabled |
| `gate` | `chain-default` | `chain-default`, `app-final`, `l1-anchored`, or `zk-settled` |
| `expiry-blocks` | `0` | Non-negative; `0` uses the chain's bounded CHAIN-effect default |
| `activations.on-approved-effect` | absent/inactive | Required positive app-chain height before emission can occur |

When the generic feature is enabled:

- `effects.enabled` must also be true;
- every voting member must resolve identical machine and effect consensus
  settings;
- the configured type and payload must fit framework effect bounds; and
- invalid, contradictory, or partially specified configuration fails startup
  before networking begins.

At and after the activation height, height-aware approval `PROPOSE` admission
rejects an opaque payload larger than the resolved
`effects.max-payload-bytes`. This prevents an effect-eligible item from
reaching approval with a payload the deterministic kernel cannot emit.
Pre-activation proposals retain plain-approvals admission because they never
emit retroactively. The parser derives the bound from the shared effects
configuration definitions used by runtime and tooling.

`effects.executor.*`, `effects.executors.*`, endpoints, secrets, retry timing,
and external-worker configuration remain node-local execution-plane settings.
They do not belong under `machines.approvals`.

### 4.3 Why result policy is not configurable

The generic extension promises committed effect lifecycle state. It therefore
always uses `ResultPolicy.CHAIN`. Allowing `NONE` would make
`CONFIRMED`/`FAILED` impossible to derive and would create two substantially
different state models behind one switch.

Applications needing fire-and-forget behavior can consume finalized approval
messages through a sink or implement a custom machine.

### 4.4 Why only one action is configurable

One exact action has simple deterministic semantics:

```text
approval threshold reached -> emit once -> incorporate one terminal result
```

An action list introduces ordering, per-action identity, partial success,
retry independence, aggregate status, migration, and proof-schema decisions.
Those belong in a committed composite profile rather than an expanding stock
machine configuration surface.

## 5. Committed state model

### 5.1 Separate decision and execution state

The existing item decision remains:

```text
i/<itemId> -> approval Item
decision status: PENDING | APPROVED | REJECTED | EXPIRED
```

The generic action has its own record:

```text
ae/s/<itemId> -> ApprovalEffectStateV1
effect status: PENDING | CONFIRMED | FAILED
```

This avoids the misleading transition:

```text
APPROVED -> PAY_FAILED
```

and represents the truth instead:

```text
decision = APPROVED
effect   = FAILED (exact outcome: EXPIRED)
```

A UI may project these two fields as `EFFECT_PENDING`, `EFFECT_CONFIRMED`, or
`EFFECT_FAILED`, but the authenticated records keep the dimensions separate.

### 5.2 Generic state keys

The generic extension owns a new namespace and does not reinterpret the
removed pre-release payment keys:

```text
ae/p/<itemId>  staged proposal payload, CBOR-wrapped so empty bytes are representable
ae/s/<itemId>  effect lifecycle, effect id, exact outcome, and bounded references
```

`ApprovalEffectStateV1` is canonically encoded as:

```text
[
  version,             // 1
  status,              // 0 PENDING, 1 CONFIRMED, 2 FAILED
  effectId,            // canonical text
  outcome,             // 0 while pending; otherwise EffectOutcome wire code
  externalRef,         // bounded bytes; may be empty
  detailHash           // 32 bytes or empty
]
```

The effect-state key is independently provable against the chain state root.
The framework effect record remains the canonical detailed execution evidence;
the machine record provides the application-facing projection and link.

### 5.3 Removed pre-release payment state

The unreleased payment-specific status codes and keys are removed:

```text
STATUS_PAID
STATUS_PAY_FAILED
p/<itemId>
f/<itemId>
t/<itemId>
```

No deployed approval history depends on them. They are not reassigned or
silently interpreted as generic state. New generic status codes are
unnecessary in the item record because effect status is stored separately.

## 6. Deterministic transition rules

### 6.1 Proposal

For a `PROPOSE` finalized at height `H`:

1. apply the existing approval-item creation rules;
2. if generic configuration is enabled and
   `activations.on-approved-effect <= H`, store the exact proposal payload
   under `ae/p/<itemId>`;
3. otherwise store no generic payload; and
4. never inspect or transform the payload.

Empty payloads are valid opaque effect payloads and are staged using a
CBOR-wrapped byte string rather than an ambiguous empty state value.

This proposal-height rule deliberately prevents a proposal created before the
activation from gaining a new side effect merely because it is approved after
the activation.

### 6.2 Approval threshold reached

When an `APPROVE` first reaches the required count:

1. write the approval item as `APPROVED`;
2. look up `ae/p/<itemId>`;
3. if absent, emit nothing and retain plain `APPROVED` behavior;
4. if present, emit exactly one intent:

   ```text
   type            = configured type
   payload         = exact staged proposal payload
   scope           = approvals/on-approved/<itemId>
   gate            = configured/resolved gate
   result          = CHAIN
   expiryBlocks    = configured bounded value
   sourceMessageId = threshold-reaching APPROVE message id
   ```

5. write `ae/s/<itemId>` with status `PENDING` and the emitted effect id; and
6. delete `ae/p/<itemId>` in the same atomic state transition.

The terminal approval rule and one state key per item make duplicate approval
messages deterministic no-ops. Scope provides executor-level idempotency and
per-item ordering.

### 6.3 Rejection or approval expiry

When an item becomes `REJECTED` or `EXPIRED`, delete any staged generic payload.
No effect is emitted.

### 6.4 Effect result

`onEffectResult` handles a result only when all of these match:

- exact configured effect type;
- scope prefix `approvals/on-approved/`;
- item exists and its decision is `APPROVED`;
- `ae/s/<itemId>` exists with status `PENDING`; and
- the stored canonical effect id equals `result.effectId()`.

Then:

| Framework outcome | Generic effect status |
|---|---|
| `CONFIRMED` | `CONFIRMED` |
| `FAILED` | `FAILED` |
| `CANCELLED` | `FAILED` |
| `EXPIRED` | `FAILED` |

The record also stores the exact framework outcome, external reference, and
optional detail hash. The first incorporated terminal result wins; later
duplicates are deterministic no-ops.

The approval decision remains `APPROVED` for every row in this table.

## 7. Emission, execution, and proof boundaries

The following layers stay distinct:

| Layer | Owner | Required for |
|---|---|---|
| Effect framework | `effects.enabled` and consensus caps | Recording/proving effect intents |
| Emitter | `machines.approvals.on-approved-effect.*` | Deciding when and what to emit |
| Execution plane | embedded executor or external claim/report worker | Performing the external action |
| Result incorporation | `ResultPolicy.CHAIN` and sequenced `~fx/result` | Updating generic effect state |

An effect can be emitted, finalized, and proven without any executor. It stays
`PENDING` until a terminal result is incorporated or deterministic expiry
closes it.

`demo.webhook` remains a demonstration routing string handled by the launcher
through the external claim/report API; it has no Java executor class. The
built-in production HTTP executor remains `webhook.post`. This ADR does not
change executor payload schemas.

An effect proof proves that members committed the intent. A successful
external action additionally depends on the executor identity, idempotency,
external reference, and independently verifiable receipt where the integration
supports one.

## 8. Pre-release compatibility decision

### 8.1 Removed configuration is rejected

The following unreleased keys are not supported:

```text
machines.approvals.payments
machines.approvals.payment-type
machines.approvals.payment-gate
machines.approvals.payment-expiry-blocks
machines.approvals.activations.payments
```

Runtime startup, resolved-mode validation, project generation, and first-party
metadata use the shared `AppChainApprovalsConfig` parser. Any obsolete key
fails with a migration hint. Silent aliases are prohibited because generic
semantics use different state keys, scope, and decision behavior.

### 8.2 Existing development data

No released or deployed approvals-payment chain exists. Development/demo data
created with the pre-release payment branch must be deleted and bootstrapped
again with the generic configuration. No in-place conversion is provided.

If a deployed chain exists in the future, configuration or schema evolution
requires a separately reviewed activation/migration decision; this pre-release
exception must not be used as precedent for rewriting certified history.

### 8.3 New chains

New generic chains normally set:

```yaml
activations:
  on-approved-effect: 1
```

Height 1 means the behavior is enabled from the first non-genesis app block.
The explicit marker remains valuable: missing activation fails safe as
inactive, and replay always selects the same branch by block height.

## 9. Validation and tooling

The implementation updates the shared `appchain-config` parser, configuration
registry, devtool metadata, effective configuration, Studio capability model,
doctor checks, and generated examples. The additive
`effects:on-approved` capability accepts an `effectType` answer, so generated
projects can route to packaged or custom plugin executors without CLI code
changes.

Validation rules include:

- strict boolean parsing for `enabled`;
- required valid effect type when enabled;
- known gate values only;
- non-negative, framework-bounded expiry;
- positive activation height;
- `effects.enabled=true` when the emitter is configured;
- obsolete payment-specific keys fail with a migration hint; and
- no unknown keys under the built-in generic namespace in strict mode.

Consensus-shared generic settings must be explicit in generated resolved
configuration and lock identity. Node-local executor endpoints and credentials
remain excluded/redacted as already defined by ADR-010 and ADR-DX-0001.

## 10. Testing and acceptance gates

Implementation is not complete until all of these pass:

### 10.1 State-machine tests

- Missing activation emits nothing and writes no generic state.
- Activation occurs exactly at the declared height.
- A proposal finalized before activation never emits retroactively.
- A proposal finalized after activation stages even an empty payload safely.
- Rejection and deadline expiry delete staged payloads.
- Threshold approval emits exactly one effect with exact type, payload, scope,
  gate, expiry, and source message id.
- Duplicate approvals do not emit twice.
- The item decision remains `APPROVED` while effect state moves through
  `PENDING` to `CONFIRMED` or `FAILED`.
- Every framework terminal outcome maps as specified while preserving its exact
  outcome code and references.
- Wrong type, scope, item, or effect id is a deterministic no-op.

### 10.2 Replay and consensus tests

- `StateMachineConformance` repeated-run determinism.
- ADR-010.1 upgrade replay matrix around the activation height.
- Kill/reopen before and after emission and result incorporation.
- Multi-member test deriving identical state/effect roots.
- Catch-up from before activation through confirmed and failed outcomes.
- Removed payment-specific configuration fails identically in runtime and
  resolved-mode tooling.

### 10.3 Configuration and packaged-demo tests

- New YAML and flat-property parsing produce identical settings.
- Invalid partial/mixed configuration fails before startup.
- Metadata, config validation, Studio, lock, and runtime agree on defaults and
  bounds.
- `./yano.sh appchain cluster effect demo` uses generic keys and still confirms
  and proves `demo.webhook`.
- The production webhook tutorial uses `type: webhook.post` and reaches its
  receiver.
- Cardano payment documentation uses `type: cardano.payment` without exposing
  payment-specific approvals configuration.

## 11. Security and operational rules

- Effect payloads are replicated consensus data. They must not contain secrets
  or unnecessary personal data.
- Targets and credentials remain executor-local configuration, never proposal
  fields by default.
- `app-final` permits execution after threshold app-chain finality;
  `l1-anchored` waits for the configured stable Cardano anchor;
  `zk-settled` waits for the corresponding settlement high-water mark.
- External claim/report endpoints require an unscoped privileged API key and
  network restriction.
- Executor overlap can cause duplicate attempts. Receivers must deduplicate by
  the deterministic idempotency key.
- `expiry-blocks` is measured in app-chain block heights, not wall-clock time.
- All state-machine, activation, type, gate, and expiry settings must agree
  across voting members.

## 12. Alternatives considered

### 12.1 Keep payment names and document that type is arbitrary

Rejected. Documentation cannot make `PAID` an accurate status for a webhook,
Kafka publication, or custom ERP action.

### 12.2 Rename only the configuration properties

Rejected. Persisted `PAID`/`PAY_FAILED` states and payment-prefixed keys would
remain misleading, and silently interpreting old keys as new behavior would be
replay-unsafe.

### 12.3 Put effect status directly in the approval decision field

Rejected. Approval and execution are orthogonal. A failed action does not make
the underlying decision unapproved, and one overloaded status loses useful
truth.

### 12.4 Support an arbitrary list of `on-approved-effects`

Deferred. It requires committed ordering, per-action state/proofs, partial
failure policy, and migration rules. A composite state machine already provides
the correct abstraction.

### 12.5 Remove all effect behavior from stock approvals

Rejected. "Approve, then perform one typed action" is a common, reusable
workflow small enough for a stock capability. Requiring a plugin for every
single-action approval would harm onboarding without improving safety.

### 12.6 Turn approvals into a generic YAML workflow engine

Rejected. It conflicts with KISS, expands the consensus attack surface, and
makes deterministic upgrade/replay behavior substantially harder to audit.

## 13. Consequences

### Positive

- Configuration and state describe webhook, payment, publication, and custom
  actions honestly.
- Approval truth survives executor failure.
- One stock workflow supports many executor types without type-specific Java
  branches.
- New state is independently provable and namespaced.
- The demo stops presenting a routing string as if it were a payment.
- Future multi-action workflows have a clean boundary: use composites rather
  than growing ad hoc properties.

### Costs

- A new committed state record and query/proof documentation are required.
- Examples, metadata, Studio recipes, configuration generators, tests, and
  tutorials must migrate together.
- Consumers that previously expected one overloaded item status must read both
  decision and effect state for generic chains.

### Risks

- Reintroducing removed keys as aliases would change generic state and replay.
- An executor may accept the configured type but interpret an incompatible
  payload schema; type/schema compatibility remains an integration contract.
- Operators may mistake effect confirmation for proof of real-world truth;
  receipts and executor trust must remain explicit.
- A future action-list feature could erode the intended simple boundary if
  added without a composite-profile decision.

## 14. Implementation sequence after acceptance

1. Freeze state keys, CBOR schema, status codes, scope, and configuration names
   in config/stdlib tests.
2. Add one shared generic config parser and reject obsolete payment keys.
3. Implement proposal staging, one-time emission, neutral state, and strict
   result correlation.
4. Add deterministic, restart, multi-member, and removed-key rejection suites.
5. Update configuration metadata, devtools, Studio, distribution recipes, and
   doctor/parity gates.
6. Migrate the default `effects-chain`, CLI demo, webhook tutorial, Java client
   examples, user guide, and connector documentation.
7. Run packaged JVM distribution acceptance and keep the shared parser/state
   implementation on the native-compatible, reflection-free compile surface;
   dynamic directory plugins remain JVM-only as already documented.

This sequence is implemented in version 3. The configuration/state schema and
pre-release removal decision are accepted by this revision.

## 15. Implementation verification

The version 3 implementation was reviewed and verified on 2026-07-20 with:

- full `appchain-config`, `appchain-stdlib`, `appchain-composite`,
  `appchain-devtools`, `appchain-effects-cardano`, and `app` test suites;
- targeted runtime activation, generic result-incorporation, and repeated-run
  conformance tests;
- final distribution generation and packaged CLI acceptance;
- the maintained cluster-launcher regression, including default/custom effect
  payloads, retained restart, governed join, and root agreement; and
- a live two-member devnet smoke test proving equal state roots, a 2-of-2
  finality certificate, cross-peer message propagation, MPF proof retrieval,
  confirmed L1 anchoring, and advancing lock-step L1 state.

## 16. Review history

| Version | Date | Summary |
|---|---|---|
| v1 | 2026-07-20 | Proposed the neutral on-approved effect model and compatibility options. |
| v2 | 2026-07-20 | Chose pre-release removal: no aliases or state migration; obsolete keys fail. |
| v3 | 2026-07-20 | Recorded implementation across config, stdlib, runtime, devtools, Studio metadata, launcher, demos, and documentation; added framework payload-bound admission and final verification gates. |
