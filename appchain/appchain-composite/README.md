# App-chain composite state machine

This module runs several deterministic application components behind Yano's
single `AppStateMachine` boundary. It owns exact message routing, binary state
namespaces, query dispatch, effect quotas and result ownership, and declared
atomic cross-component workflows. Consensus, MPF roots, finality, snapshots,
effects, and Cardano anchoring remain framework-owned.

## Stock evidence presets

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
            preset: evidence-v1-gated
            profile-mode: governed
        membership:
          mode: governed
```

Both presets commit, in order, `registry`, `approvals`, `doc-trail`, and
`evidence` components plus the `evidence-release` workflow. Its manifested
bundle depends on the first-party stdlib and evidence-registry bundles. Kafka,
S3-compatible object storage, and IPFS are separate effect-executor bundles;
enable only the connectors the selected workflow needs.

`evidence-v1-gated` is the default and exposes only the coordinated
`evidence.release.v1` creation/republish path. Its command-granular
`evidence.command.v1` route accepts canonical post-publication notifications
but rejects direct submit and republish commands. `evidence-v1` is a
compatibility preset that exposes every direct evidence command; in that preset
the release workflow is optional coordination, not an authorization gate.

The effective canonical profile is written under
`~composite/profile/v1` at app height 1. Retained startup and every transition
require exact equality. Treat its domain-separated SHA-256 digest as a
deployment trust root: proof clients for the generic `composite` provider must
pin the intended digest and verify the authenticated marker.

`profile-mode: governed` also commits an epoch-0 record and enables ADR-015's
deploy-first/activate-second protocol. The stock preset is a one-entry catalog,
so it demonstrates governed genesis and operations but cannot select an
unpackaged profile. A custom composite bundle supplies a catalog containing the
active and dormant target entries. V1 permits 1-64 distinct profile entries and
requires every historical profile to remain packaged; `max-epochs` separately
bounds authenticated transitions, including reuse of a packaged profile. See the
[operator runbook](../../docs/APP_CHAIN_PROFILE_GOVERNANCE.md).

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
and optional `WorkflowDescriptor`/`CompositeWorkflow` products. For a fixed
one-profile chain, construct the machine through the mandatory factory:

```java
CompositeStateMachine machine = CompositeStateMachine.create(
        "my-domain-composite", context, profile, components, workflows);
```

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
for composition and
[ADR-015](../../adr/app-layer/015-governed-composite-profile-evolution.md) for
governed evolution.
