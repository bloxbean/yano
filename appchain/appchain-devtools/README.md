# Yano app-chain developer tools

This module provides the offline `yano-appchain` CLI used by
`yano.sh appchain`. It supports project initialization and deterministic
rendering, template validation, SmallRye-backed resolved validation, redacted
effective configuration, and property explanation.

```bash
# See the release-pinned recipes.
./yano.sh appchain recipes

# Reproducible non-interactive project generation.
./yano.sh appchain init --non-interactive \
  --recipe owned-registry --network preprod --members 3 \
  --node-host node-a.example --node-host node-b.example \
  --node-host node-c.example \
  --deployment host --output product-registry

# Edit appchain.yaml, then safely regenerate derived output. Rendering stops
# if a generated file contains an unaccounted manual edit.
./yano.sh appchain render product-registry
```

`init` without `--non-interactive` prompts for missing core intent. The
`v1alpha1` blueprint is the user-owned source; `appchain.lock` pins catalog
digests, capability expansion, consensus values, artifacts, and generated
file digests. Private values never enter either file. If public member keys
are not supplied with repeated `--member-key`, the lock records a bootstrap
acknowledgement and generated secret examples remain intentionally unusable.

Host and Compose targets isolate shared consensus configuration, per-node
configuration, and secret references. Consensus-shared runtime defaults are
always explicit, so a later runtime default change cannot silently alter a
regenerated chain. Omit `--node-host` for a same-machine host project; provide
exactly one host per member to render portable per-machine overlays.

```bash
# Intentionally incomplete shared template
./yano.sh appchain config validate --mode template \
  --template-contract builtin:cluster application-appchain.yml

# Startable node-specific source stack; later --config files have higher
# default ordinals unless a source declares config_ordinal.
./yano.sh appchain config validate --mode resolved \
  --config application-appchain.yml \
  --config node0.properties \
  --config private.properties

# Values are always redacted when their property is secret.
./yano.sh appchain config effective --mode resolved --format yaml \
  --show-sources --config application-appchain.yml --config private.properties
```

Environment and system properties are excluded by default for reproducible
offline results. Add `--include-environment` and/or
`--include-system-properties` when runtime-parity inspection needs those
ambient sources. `--profile <name>` activates SmallRye profile resolution.

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
- `appchain-blueprint.schema.json`
- `appchain-lock.schema.json`
- `appchain-capability-catalog.json`
- `appchain-recipe-catalog.json`

The files are generated from the same registry used by validation, packaged in
the CLI, and copied beside release configuration under `config/schema`.
Golden SHA-256 snapshots make metadata changes explicit during review.
