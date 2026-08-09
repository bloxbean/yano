# Composable State Machines and Portable Proofs

This guide is the supported application-development model introduced by ADR-031. A deployable
application is always an `AppStateMachine`, whether it runs one stock machine, assembles several
machines, or directly uses reusable transition capabilities.

## Choose the smallest reusable layer

| Need | Use | Why |
|---|---|---|
| One stock data model | Standalone stock `AppStateMachine` | Minimal configuration and audit surface |
| Several stock models with routed topics | `ComposableAppStateMachine` | One normal state machine with isolated namespaces |
| Custom rules around one stock mutation | A `TransitionCapability` inside a custom `AppStateMachine` | Reuses deterministic business logic without inheriting a lifecycle |
| Reusable actor/role authorization | `RoleAuthorizationCapability` | One verifier and one-use consumption plan shared by applications |
| Independent message finalization | `MessageInclusionProof` / `PortableProofBundle` | Proves the message id against a certified block's messages root |
| Independent application fact | `StateProofSubject` plus a state proof | Derives and decodes the exact authenticated physical key |
| Cardano validator consumption | MPF plus `MpfProofConverter` / `MpfOnChainVerifier` | Bounded normalized inclusion proof tied to an anchor reference input |

## Standalone application

Select a stock machine ID in the chain configuration. The runtime calls the single deterministic
entry point:

```java
void apply(AppBlockExecutionContext context,
           AppStateWriter writer,
           AppEffectEmitter effects)
```

The context contains the finalized app block and accepted, position-preserving L1 observations.
It never exposes a live ledger database, clock, network, filesystem, or historical-state handle.
Stock ordered-log, document-trail, key/value, and other machines delegate their mutations to the
same transition capabilities available to custom applications.

## Composed application

`ComposableAppStateMachine.builder(...)` registers ordinary `AppStateMachine` instances. The
result is itself an `AppStateMachine`; there is no second component lifecycle SPI. Each descriptor
commits the component generation, namespace, routes, query surface, effect ownership, limits, and
observer subscriptions into the composite profile.

```java
AppStateMachine application = ComposableAppStateMachine
        .builder("shipment-application", context, "shipment-profile", "1.0.0")
        .machine(orderDescriptor, orderLog)
        .machine(documentDescriptor, documentTrail)
        .workflow(shipmentReleaseWorkflow)
        .build();
```

The builder routes restricted execution contexts to children while preserving original global
message indexes. Component writers/readers are namespaced. Cross-component behavior belongs in an
explicit workflow or in a custom machine using transition capabilities; a child must not derive a
sibling's physical key and access it directly.

## Capability-level reuse

A `TransitionCapability<C, F>` is a pure decision function over a decoded command, explicit facts,
and a bounded `TransitionContext`. It returns either a rejection or a `TransitionPlan`. Planning
does not mutate state. The owning application validates all plans first, then applies their ordered
state mutations and effect intents atomically.

Use this layer when a custom application needs stock semantics but a different message schema or
workflow. Available kernels include ordered-log message recording, document-trail, key/value, and
the consolidated actor/role authorization decision path. Application bindings still own semantic
coverage, replay keys, and receipt meaning.

## Off-chain proof verification

The proof endpoint returns only the certified, profile-tagged `StateProofEnvelope` baseline. A
proof includes the exact commitment identity, finalized height/root, block hash, native proof,
block header, and finality certificate. Historical lookup succeeds only at retained heights; use
`oldestProvableHeight` to distinguish an unknown fact from pruned proof material.

Verification has an explicit trust boundary:

1. `ProofVerifier.verifyInternalConsistency(proof)` checks only proof math against the root carried
   by the same response. It does not authenticate that root.
2. `ProofVerifier.verify(proof, TrustedStateRoot)` additionally pins chain, genesis, profile,
   fingerprint, height, and root obtained independently.
3. `ProofVerifier.verifyCertified(proof, FinalityTrustContext)` authenticates the finalized block
   with a pinned member/threshold policy.
4. `PortableProofBundle` can combine a messages-root proof, optional supplied signed message, and
   an optional typed state fact. It deliberately reports that data availability is not proven.

Use `ProofSubjects` instead of manually constructing storage keys. It provides release-matched
subjects for finalized messages, document heads, authenticated-map entries, approval outcomes,
epoch protocol parameters/stake records, and composite profiles.

Classic `jmt-blake2b256-v1` is an off-chain proof profile. Its versioned proofs and pruning
watermark are supported by the client, but configuration rejects it when
`state.l1-proof-consumption-required=true`.

MPF reachability pruning is available as an experimental node-local policy. It is disabled by
default and does not change the commitment identity:

```yaml
state:
  proof-pruning:
    enabled: false
    retain-heights: 10000
    interval-seconds: 3600
```

When enabled, the node retains every root in the configured contiguous height horizon, advances
`oldestProvableHeight` before destructive work, verifies each retained root after the sweep, and
compacts the MPF column family. A failure disables further pruning for that runtime generation and
is reported under `stateCommitment.proofPruning`; consensus continues. Cardano History is
append-only, so a recent retained root still proves facts from older epochs even when the node no
longer generates a proof against an older anchor height. Already-issued proofs remain verifiable.

## MPF proof verification in a Cardano validator

The on-chain path is intentionally MPF-specific:

1. Configure `mpf-blake2b256-v1`, its exact `state.format-fingerprint`, a fresh 32-byte
   `state.genesis-id`, and `state.l1-proof-consumption-required=true` at chain genesis.
2. Fetch the typed state proof at the anchored height and convert it with `MpfProofConverter`.
   Conversion verifies the native `mpf-proof-wire-v1` proof and enforces on-chain bounds.
3. Supply the normalized proof and semantic claim to the application validator.
4. The validator uses `MpfOnChainVerifier` and reads exactly one configured state-thread reference
   input at the anchor script address.
5. It matches chain genesis, application ID, commitment profile, format fingerprint, physical key,
   and reconstructed root before interpreting the value.

The release anchor datum also binds chain ID, height, block hash, members, and threshold. A proof
against a root supplied only by the redeemer is not authenticated and must be rejected.

## Historical L1 epoch facts

ADR-031 supplies the replay-safe execution boundary required by proposed ADR-028: an epoch observer
will read an ephemeral epoch-pinned L1 state during observation, emit canonical claims, and the
application will consume only those claims after they are sequenced into an app block. The live
ledger handle is never available inside `apply`. Protocol-parameter and per-credential stake facts
can then use `ProofSubjects.epochProtocolParameters(...)` and `epochStake(...)`. ADR-028 remains a
separate proposed implementation; these subject helpers reserve the portable proof contract.

## Testing checklist

- Run the same command standalone and composed; compare decisions, writes, effects, and roots.
- Test multi-write rejection to prove no partial state/effect application.
- Test namespace, route, query, observer, and effect-ownership isolation.
- Test restart, rollback/replay, snapshot restore, follower catch-up, and root agreement.
- Test proof inclusion, exclusion/tombstone, profile substitution, historical retention boundary,
  finality substitution, and anchor identity substitution.
- For on-chain MPF consumers, mutate every claim/key/value/fold/root/profile/anchor field and keep a
  published execution-budget vector.
