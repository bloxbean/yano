# App-chain composite state machine

This module runs several deterministic application components behind Yano's
single `AppStateMachine` boundary. It owns exact message routing, binary state
namespaces, query dispatch, effect quotas and result ownership, and declared
atomic cross-component workflows. Consensus, MPF roots, finality, snapshots,
effects, and Cardano anchoring remain framework-owned.

## Stock `evidence-v1` preset

The application distribution includes a manifested provider named
`composite`. Select its no-code preset per chain:

```yaml
yano:
  app-chain:
    effects:
      enabled: true
      max-per-block: 128
    chains:
      - id: evidence-chain
        state-machine: composite
        machines:
          composite:
            preset: evidence-v1
```

The preset commits, in order, `registry`, `approvals`, `doc-trail`, and
`evidence` components plus the `evidence-release` workflow. Its manifested
bundle depends on the first-party stdlib and evidence-registry bundles. Kafka,
S3-compatible object storage, and IPFS are separate effect-executor bundles;
enable only the connectors the selected workflow needs.

For compatibility the stock preset exposes both `evidence.release.v1` and the
direct `evidence.command.v1` route. The release workflow is optional
coordination, not an authorization gate: an accepted direct command does not
need registry/approval prerequisites. A domain bundle that requires mandatory
DPP or regulatory policy must remove/reject the direct route or enforce the
equivalent authorization itself.

The effective canonical profile is written under
`~composite/profile/v1` at app height 1. Retained startup and every transition
require exact equality. Treat its domain-separated SHA-256 digest as a
deployment trust root: proof clients for the generic `composite` provider must
pin the intended digest and verify the authenticated marker.

## Routing, state, and queries

Each component descriptor declares exact versioned topics and local query
paths. The composite maps local keys through
`CompositeCommitmentV1.componentKey(componentId, localKey)` and never exposes
the root writer. Routed blocks contain only the component's messages, have a
recomputed `messagesRoot`, and deliberately carry no finality certificate;
result callbacks receive a zero-message projection.

Direct queries use:

```text
components/<componentId>/<localPath>
```

The stock preset also retains the `evidence/get` compatibility alias.
`composite/aggregate-v1` uses `AggregateQueryCodecV1` from
`appchain-composite-contracts`, so clients and runtime share one canonical,
bounded wire implementation and one committed root-fixed context.

## Building a custom composition

Create `ComponentDescriptor` values, matching `CompositeComponent` products,
and optional `WorkflowDescriptor`/`CompositeWorkflow` products. Then construct
the machine through the mandatory factory:

```java
CompositeStateMachine machine = CompositeStateMachine.create(
        "my-domain-composite", context, profile, components, workflows);
```

The custom `AppStateMachineProvider.id()` and its manifest contribution name
must both be `my-domain-composite`; do not publish an arbitrary profile under
the stock `composite` identity. The factory reads the real
`effects.max-per-block` and rejects profiles whose reserved component and
workflow quotas exceed it.

Package the provider as one ADR-011 manifested bundle. Declare an explicit
dependency on `com.bloxbean.cardano.yano.appchain.composite` and on every
first-party bundle whose component implementation is used. Native deployments
must include the bundle at application build time; JVM deployments may use the
cataloged directory bundle flow described in the app-chain user guide.

Important compatibility rules:

- descriptors, routes, activation intervals, configuration identities, state
  compatibility identities, workflows, aliases, and limits are consensus data;
- generations sharing a `componentId` also share one physical namespace and
  must keep the same `stateAndResultCompatibilityId`;
- a schema or late-result callback change uses a new component id plus a
  deterministic migration/workflow, or a fresh chain;
- a changed admission contract uses a new versioned topic/query path; and
- expected business precondition failures, duplicates, and conflicts are
  deterministic per-message no-ops, not block-aborting exceptions.

Run deterministic replay/restart/snapshot coverage with
`StateMachineConformance` and state probes before deploying a profile.

## Build

```bash
./gradlew :appchain-composite:check
```

See [ADR-013.2](../../adr/app-layer/013.2-deterministic-composite-state-machine.md)
for the full contract and upgrade model.
