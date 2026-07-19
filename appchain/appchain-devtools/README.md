# Yano app-chain developer tools

This module provides the offline `yano-appchain` CLI used by
`yano.sh appchain config`. M0a supports template validation and property
explanation. It deliberately reports `PARTIAL` coverage until M0b adds
runtime-resolution and validator parity.

## Extending configuration metadata

Framework, first-party, and custom component properties share the typed model
in `appchain-config`. A component can place a data-only descriptor at:

```text
META-INF/yano/appchain-config-metadata-v1.json
```

The descriptor uses `AppChainMetadataDescriptor` schema version 1 and contains
an owner ID plus exact `AppChainPropertyDefinition` entries. A state machine,
effect executor, sink, observer, or other integration normally contributes
exact keys below an existing runtime-forwarded namespace, for example:

```text
yano.app-chain.machines.my-machine.*
yano.app-chain.effects.executors.my-executor.*
yano.app-chain.sinks.my-sink.*
```

Pass the component JAR, unpacked component directory, or descriptor file with
`--metadata`. The CLI reads only the descriptor bytes; it does not load or
execute plugin classes.

```bash
./yano.sh appchain config validate --mode template \
  --metadata plugins/my-component.jar application-appchain.yml
```

Exact property and namespace ownership collisions fail closed. External
descriptors must mark hand-authored constraints as `DOCUMENTED_UNVERIFIED`, so
their bounds remain warnings. Runtime-enforced errors require public runtime
definitions or repository-owned parser parity tests.

## Generated release metadata

The build deterministically exports:

- `appchain-runtime.schema.json`
- `appchain-property-catalog.json`

The files are generated from the same registry used by validation, packaged in
the CLI, and copied beside release configuration under `config/schema`.
Golden SHA-256 snapshots make metadata changes explicit during review.
