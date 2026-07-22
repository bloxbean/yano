# Yano app-chain developer tools

This module provides the internal offline `yano-appchain` engine used by
`yano.sh appchain`; users should treat `yano.sh` as the public CLI. It supports
project initialization and deterministic
rendering, template validation, SmallRye-backed resolved validation, redacted
effective configuration, property explanation, and project lifecycle checks.

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

# Verify the project, inspect a final JVM/native release, and classify changes.
./yano.sh appchain config validate --mode project product-registry
./yano.sh appchain doctor product-registry --distribution yano-0.1.0.zip
./yano.sh appchain diff previous.lock product-registry/appchain.lock
./yano.sh appchain migrate product-registry --dry-run

# Export reviewed, deterministic deployment derivatives.
./yano.sh appchain gitops product-registry --target helm --output deploy/helm
./yano.sh appchain gitops product-registry --target kustomize --output deploy/kustomize
```

## Signed custom component catalogs

Custom JVM plugins can extend project generation without extending CLI code.
The CLI reads bounded declarative resources only; it does not load provider
classes or install artifacts.

```bash
./yano.sh appchain plugin scaffold --mode state-machine --id shipment \
  --package com.example.shipment --yano-version 0.1.0-pre9 --output shipment-plugin

./yano.sh appchain plugin inspect shipment-plugin.jar \
  --trust-key vendor-2026=<64-hex-ed25519-public-key>

./yano.sh appchain plugin validate shipment-plugin.jar \
  --trust-key vendor-2026=<64-hex-ed25519-public-key> \
  --output shipment-catalog.json
```

`plugin sign` accepts the publisher seed only through `--seed-file` and writes
the data-only trust envelope. `init`, `render`, and `doctor` accept explicit
`--plugin-jar`/`--component-catalog` plus `--trust-key` inputs. Initialized
projects retain the signed snapshot and public trust key, while
`appchain.lock` pins every descriptor and full artifact digest. JVM operators
still copy the exact JAR into each distribution's `plugins/` directory;
native executables require a separately reviewed build-time flavor.

`init` without `--non-interactive` prompts for missing core intent. The
`v1alpha1` blueprint is the user-owned source; `appchain.lock` pins catalog
digests, capability expansion, consensus values, artifacts, and generated
file digests. Private values never enter either file. If public member keys
are not supplied with repeated `--member-key`, the lock records a bootstrap
acknowledgement and generated secret examples remain intentionally unusable.

Host and Compose targets isolate shared consensus configuration, per-node
configuration, and secret references. Consensus-shared runtime defaults are
always explicit, so a later runtime default change cannot silently alter a
regenerated chain. Generated runtime configuration uses deterministic nested
YAML (`config/shared-consensus.yaml` and `config/nodes/nodeN.yaml`) while the
lock retains the exact flattened values. Omit `--node-host` for a same-machine
host project; provide exactly one host per member to render portable
per-machine overlays.

Approval projects can attach one generic effect without teaching the CLI
about a particular integration. Select `effects:on-approved` with
`state:approval-workflow` and supply the exact executor or plugin routing type
as the `effectType` blueprint answer. Generated consensus configuration
enables effects and activates the transition at height 1. Executor endpoints
and credentials remain node-local and never enter consensus configuration.

```bash
# Intentionally incomplete shared template
./yano.sh appchain config validate --mode template \
  --template-contract builtin:cluster application-appchain.yml

# Startable node-specific source stack; later --config files have higher
# default ordinals unless a YAML source declares config_ordinal.
./yano.sh appchain config validate --mode resolved \
  --config product-registry/config/shared-consensus.yaml \
  --config product-registry/config/nodes/node0.yaml \
  --config private.yaml

# Values are always redacted when their property is secret.
./yano.sh appchain config effective --mode resolved --format yaml \
  --show-sources --config application-appchain.yml --config private.yaml
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

For distribution or CI use, bind the descriptor to the plugin runtime manifest
with `META-INF/yano/appchain-config-metadata-v1.sig.json`, following
`appchain-metadata-trust.schema.json`. Verify it against a vendor key pinned by
the operator:

```bash
./yano.sh appchain metadata verify plugins/my-component.jar \
  --trust-key vendor-release-2026=<64-hex-ed25519-public-key>
```

Verification authenticates the descriptor/runtime-manifest binding. It does
not execute the plugin, grant namespace ownership, or upgrade third-party
validation above `PARTIAL`.

The publisher signs these UTF-8 bytes with Ed25519 (every line, including the
last, ends in `\n`):

```text
yano-appchain-config-metadata-trust-v1
<descriptor id>
<runtime bundle id>
<key id>
<lowercase descriptor SHA-256>
<lowercase runtime-manifest SHA-256>
```

The envelope carries the base64 signature. `--trust-key` accepts the raw
32-byte Ed25519 public key as exactly 64 hexadecimal characters, not a private
key or certificate.

## Generated release metadata

The build deterministically exports:

- `appchain-runtime.schema.json`
- `appchain-property-catalog.json`
- `appchain-blueprint.schema.json`
- `appchain-lock.schema.json`
- `appchain-capability-catalog.json`
- `appchain-recipe-catalog.json`
- `appchain-release-capability-index.json`
- `appchain-first-party-metadata.json`
- `appchain-metadata-trust.schema.json`
- `appchain-gitops-lock.schema.json`
- `appchain-component-catalog.schema.json`
- `appchain-component-catalog-snapshot.schema.json`

The files are generated from the same registry used by validation, packaged in
the CLI, and copied beside release configuration under `config/schema`.
Golden SHA-256 snapshots make metadata changes explicit during review.

The distribution and every generated project also include the release-pinned
`configure-yano-appchain` AI skill. Generated projects include a checksum-pinned
CI workflow and offline `ci/verify` script.
