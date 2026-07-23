# Tutorial 8 — Extend Yano Without Forking It

[Open the custom-plugin path in App-Chain Studio](../../../appchain/appchain-studio/src/main/web/index.html#recipe=custom-plugin&network=devnet&members=3&finality=two-thirds&sequencing=fixed&runtime=jvm&deployment=host&name=custom-appchain&chainId=custom-appchain&stateMachine=com.example.my-machine)

- **Level:** Java application developer
- **Time:** 30–60 minutes for a first plugin
- **Outcome:** choose the correct extension level and deploy versioned business
  logic as a plugin JAR on the standard JVM distribution.

## Start from a bounded scaffold

Use the public launcher to create one small, buildable starting point. The
four modes share the same runtime manifest, signed product-catalog, and
ServiceLoader conventions:

```bash
./yano.sh appchain plugin scaffold \
  --mode state-machine \
  --id shipment \
  --package com.example.shipment \
  --yano-version 0.1.0-pre9 \
  --output shipment-plugin
```

Other modes are `composite-role`, `effect-executor`, and `sink`. The generated
provider deliberately performs no business work: state-machine admission is
closed and executor/sink factories return no instances until implemented.
The tool refuses a non-empty output directory.

After implementation and tests, sign the exact catalog, runtime manifest, and
optional configuration metadata. Keep the 32-byte seed outside the repository
and pass it by file only:

```bash
./yano.sh appchain plugin sign \
  --catalog shipment-plugin/src/main/resources/META-INF/yano/appchain-component-catalog-v1.json \
  --runtime-manifest shipment-plugin/src/main/resources/META-INF/yano/plugins/plugin-bundle.shipment.json \
  --seed-file /secure/publisher.seed \
  --key-id example-release-2026 \
  --output shipment-plugin/src/main/resources/META-INF/yano/appchain-component-catalog-v1.sig.json

cd shipment-plugin
gradle jar
cd ..

./yano.sh appchain plugin validate shipment-plugin/build/libs/shipment-yano-plugin.jar \
  --trust-key example-release-2026=<64-hex-public-key> \
  --output shipment-catalog.json
```

`inspect` prints the same verified catalog and its capabilities. Neither
command loads provider classes, runs plugin code, fetches a registry, or
installs the JAR. The exported snapshot is the safe local-import format used
by Studio and generated projects.

Create a project by selecting the custom capability declared by the catalog:

```bash
./yano.sh appchain init --non-interactive \
  --recipe custom-plugin --network devnet --members 3 --runtime jvm \
  --capability state:shipment \
  --plugin-jar shipment-plugin/build/libs/shipment-yano-plugin.jar \
  --trust-key example-release-2026=<64-hex-public-key> \
  --output shipment-chain

./yano.sh appchain config validate --mode project shipment-chain
```

The project stores the signed data-only snapshot under
`component-catalogs/`; its lock pins the snapshot, catalog, runtime manifest,
configuration metadata, and complete plugin-JAR digests. Rendering and doctor
reverify the project snapshot automatically. The public key is not secret.
Copy the exact pinned JAR into `plugins/` in every JVM distribution before
starting nodes, then verify it:

```bash
./yano.sh appchain doctor shipment-chain --distribution /opt/yano
```

A missing or different JAR fails artifact readiness. A native distribution
reports a direct incompatibility: a JVM plugin directory cannot modify an
already-built native executable. Native support needs a separately produced,
version-matched build-time release flavor.

Yano's core provides ordering, threshold finality, deterministic state,
proofs, anchoring, effects, plugin lifecycle, health, and metrics. Application
teams normally extend the application layer, not the consensus runtime.

## Choose the smallest extension

```text
Does a stock machine/profile already model the outcome?
  ├─ yes → configuration only
  └─ no
      Are all required components already available?
        ├─ yes → small composite plugin
        └─ no  → custom state-machine component/plugin
```

Other independent plugin SPIs cover effect executors, finalized stream sinks,
domain APIs, signers, sequencer mode, and L1 observers.

## Path A — configuration only

Select one built-in id or profile identically on every member:

```yaml
yano.app-chain.state-machine: kv-registry
```

For stock composite/role profiles, the profile identifier and configuration
digest become part of chain identity. Select them only for a fresh chain or a
governed activation.

## Path B — a small composite plugin

A composite explicitly defines:

- component ids and versions;
- deterministic application order;
- routed public topics;
- per-component quotas;
- workflow transitions between components; and
- one committed profile identity/digest.

This Java class is intentionally small but consensus-critical. YAML cannot
dynamically insert arbitrary component plugins into a frozen profile, because
two members discovering a different order would derive different roots.

Reuse the effect-gated evidence and role-evidence presets as reference
implementations. Package the provider, manifest, service entry, and components
in one reviewed bundle.

## Path C — a custom state-machine plugin

The complete hands-on implementation is in the existing
[default-distribution tutorial, Part 2](../../APP_CHAIN_TUTORIAL.md). The core
shape is:

```java
public final class ShipmentStateMachine implements AppStateMachine {
    @Override
    public String id() {
        return "shipment-v1";
    }

    @Override
    public AdmissionResult validate(AppMessage message) {
        // Bounded structural validation only; never perform I/O here.
        return decodeSafely(message.getBody())
                ? AdmissionResult.accept()
                : AdmissionResult.reject("invalid shipment command");
    }

    @Override
    public void apply(AppBlock block, AppStateWriter state) {
        // Deterministic bounded transitions only.
    }
}
```

Contribute it through `AppStateMachineProvider`, add the service entry and
plugin manifest, then copy the bundle JAR into the configured plugin directory.
Yano itself does not need recompilation for a JVM deployment.

Native images cannot discover a new directory JAR after build. Include the
plugin when producing the native image or use the JVM distribution for dynamic
plugin installation.

## Custom component catalog contract

The JAR carries three independent, bounded contracts:

- `META-INF/yano/plugins/<bundle-id>.json` describes executable runtime
  contributions;
- `META-INF/yano/appchain-config-metadata-v1.json`, when present, owns typed
  configuration definitions; and
- `META-INF/yano/appchain-component-catalog-v1.json` describes selectable
  product capabilities and required artifacts.

The Ed25519 trust envelope binds the catalog, runtime manifest, optional
configuration metadata, bundle identity/version, and publisher key ID. It
authenticates those exact bytes; it does not approve code or elevate a custom
component to bundled/stable/native status. Custom entries remain JVM-only
`REFERENCE` or `EXPERIMENTAL`, and all release ID, namespace, and artifact
collisions fail closed.

## Consensus rules for application plugins

- The same bundle, machine/profile id, and committed settings run on every
  voting member.
- `apply()` must not use wall clock, randomness, DNS, filesystem, database, or
  network I/O.
- Invalid finalized bytes become deterministic no-ops, not escaping
  exceptions.
- Bound message bytes, decode depth/items, collections, state growth, and work
  per block.
- Never silently change semantics behind an existing machine/component id.
- Use activation heights/profile governance for compatible evolution and a
  new namespace/migration plan for incompatible state.
- Every state write belongs to the authenticated writer; do not maintain
  hidden consensus state in static fields or node-local storage.

## Add external actions correctly

Do not call Kafka, S3, IPFS, Cardano, or an ERP from `apply()`. Emit an
`EffectIntent`; let an executor act after its finality gate; incorporate the
result through `onEffectResult` when the outcome affects business state.

Create a custom executor plugin when the action needs typed target aliases,
authentication, polling, reconciliation, or a domain-specific receipt. Keep
endpoints and secrets in node-local executor configuration, never replicated
effect payloads.

## Testing ladder

1. Unit-test codecs and deterministic transitions.
2. Run the state-machine conformance and replay matrix.
3. Test malformed and hostile finalized input through `apply()`.
4. Start an embedded multi-member cluster with `appchain-testkit`.
5. Verify root parity, proof keys, restart, catch-up, rollback/reapply, and
   plugin packaging.
6. For effects, test crash-before-send, send-before-ack, retry, reconciliation,
   parking, requeue, and duplicate idempotency.
7. Run a packaged JVM cluster; add native coverage when native is supported.

## Deployment and operations

- Give every bundle a stable plugin id and semantic version.
- Verify catalog state and health on every member before admitting traffic.
- Treat plugin removal or drift as a deployment error, not automatic fallback.
- Namespace configuration and metrics by plugin/contribution.
- Keep plugin domain APIs read-only unless commands still enter through the
  authenticated app-chain submission path.

## Go deeper

- [Plugin query and domain APIs](../../APP_CHAIN_PLUGIN_QUERY_AND_DOMAIN_API.md)
- [Plugin operations](../../PLUGIN_OPERATIONS.md)
- [Composite implementation guide](../../../appchain/appchain-composite/README.md)
- [Plugin template scaffold](../../../scaffolds/plugin-template/)
- [Testkit](../../../appchain/appchain-testkit/README.md)
