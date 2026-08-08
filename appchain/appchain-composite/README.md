# App-chain composite framework

This module is the generic deterministic composition engine behind Yano's
single `AppStateMachine` boundary. It owns exact message routing, binary state
namespaces, query dispatch, effect quotas and result ownership, atomic
cross-component workflows, and fixed or governed profile evolution.

It intentionally contains no evidence classes, stock product preset,
ServiceLoader provider, or plugin manifest. First-party evidence assemblies
live in [`appchain-evidence-profile`](../products/appchain-evidence-profile/README.md).
Custom products may use this framework without inheriting evidence behavior.

## Routing, state, and queries

Each `ComponentDescriptor` declares exact versioned topics and local query
paths. The composite maps local keys through
`CompositeCommitmentV1.componentKey(componentId, localKey)` and never exposes
the root writer. Routed execution preserves the finalized block identity and
finality certificate while exposing only the component's messages and their
original global indexes. Result callbacks receive the same block-bound context
with an empty routed message view.

Direct queries use:

```text
components/<componentId>/<localPath>
```

`composite/aggregate-v1` uses `AggregateQueryCodecV1` from
`appchain-composite-contracts`, so clients and runtime share one canonical,
bounded wire implementation and one committed root-fixed context.

## Building a product composition

Register ordinary `AppStateMachine` instances with explicit
`ComponentDescriptor` values and optional declared workflows. The builder
generates the single canonical profile committed by the resulting application:

```java
AppStateMachine machine = ComposableAppStateMachine.builder(
                "my-domain-composite", context, "my-profile", "1.0.0")
        .machine(documentDescriptor, new DocTrailStateMachine())
        .machine(logDescriptor, new OrderedLogStateMachine())
        .workflow(releaseWorkflow)
        .build();
```

There is no separate component lifecycle API or whole-machine adapter. Stock
machines use the same `AppStateMachine` contract standalone and when composed;
the explicit descriptor commits their namespace, routes, activation, queries,
configuration identity, compatibility identity, and effect quota.

For a long-lived governed chain, package an immutable digest-keyed catalog and
select its genesis entry explicitly:

```java
CompositeProfileCatalog catalog = new CompositeProfileCatalog(
        List.of(currentEntry, dormantTargetEntry),
        context.consensusProfile().orElseThrow().effectsMaxPerBlock(),
        Math.toIntExact(context.consensusProfile().orElseThrow()
                .effectsResultWindowBlocks()));
CompositeStateMachine machine = CompositeStateMachine.create(
        "my-domain-composite", context, catalog, currentProfile.digest());
```

The product owns its `AppStateMachineProvider`, plugin manifest, presets, and
product-specific contracts. Add this module as a normal build dependency;
declare manifest dependencies only for executable plugin bundles used by the
product. Native deployments include the product at application build time.

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
for composition and
[ADR-015](../../adr/app-layer/015-governed-composite-profile-evolution.md) for
governed evolution.
