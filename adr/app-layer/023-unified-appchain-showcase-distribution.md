# ADR-023: Unified App-Chain Showcase Distribution and Product/Demo Boundary

## Status

Accepted and implemented — 2026-07-31

The number is local to the `adr/app-layer` series. A root-level ADR-023, if one
exists, is unrelated.

## Date

2026-07-31

## Decision owners

BloxBean Team

## Parent and related decisions

- [ADR-005](005-yano-app-chain-framework.md) owns deterministic app-chain
  execution, consensus, authenticated state, proofs, finality, and Cardano
  anchoring.
- [ADR-008.4](008.4-script-anchors-l1view.md) owns Cardano script anchors and
  L1 observation.
- [ADR-010](010-deterministic-effect-system.md) owns deterministic effect
  intent, execution, result incorporation, and proof boundaries.
- [ADR-011](011-plugin-architecture.md) owns manifested plugin contributions
  and loading.
- [ADR-013](013-first-party-integration-connectors-and-effect-demo.md) owns the
  first-party Kafka, S3-compatible object-store, and IPFS integrations and the
  evidence demo.
- [ADR-013.2](013.2-deterministic-composite-state-machine.md) owns explicit,
  deterministic component composition.
- [ADR-015](015-governed-composite-profile-evolution.md) owns governed
  composite-profile evolution.
- [ADR-019](019-reusable-domain-actor-registry-and-role-aware-approvals.md)
  owns reusable organizations, actors, roles, policies, and role-aware
  approvals.
- [ADR-021](021-generic-on-approved-effect.md) owns the stock approval
  machine's generic post-approval effect.
- [ADR-022](022-out-of-box-appchain-capabilities-and-extensible-product-catalog.md)
  owns the product catalog and the boundary between generic capabilities and
  application products.
- [ADR-DX-0001](dx/0001-unified-appchain-onboarding-configuration-and-lifecycle.md)
  owns app-chain configuration, validation, lifecycle, locks, and drift
  detection.
- [EUTxO ADR-002](utxo/002-unified-eutxo-demo-experience.md) owns the unified
  EUTxO demo experience.
- Root [ADR-028](../028-unified-console-ui-module.md) owns the runtime console
  UI.

---

## 0. In plain words

Yano should ship one demo-ready ZIP with one command surface from which a user
can discover, start, inspect, exercise, restart, and verify the important
app-chain capabilities.

The ZIP exposes three deployment profiles:

1. `light` for a three-node, dependency-free local cluster and the stock state
   machines;
2. `evidence` for the product-level evidence workflow with Kafka, object
   storage, and IPFS; and
3. `eutxo` for the ledger, bridge, and ZK EUTxO demonstrations.

The light profile also contains one deliberately small showcase plugin. It
demonstrates an order approval flow assembled with the public composite and
effect SPIs and writes an idempotent local outbox receipt. It is a demo
artifact, not a new built-in Yano state machine or production connector.

The evidence terminology is important:

- the **evidence product** is the reusable product assembly in
  `appchain/products/appchain-evidence-*`;
- `app/appchain-effects-demo` is the maintained deployment and demonstration
  harness for that product; and
- `appchain/examples/appchain-evidence-demo-runner` is the external scenario
  runner used to drive and verify it.

Therefore, the evidence capability is a product-level assembly. The
**evidence demo itself is not the product**; it is the runnable presentation
of the evidence product.

This ADR does not change app-chain consensus or move demo behavior into the
core runtime. It packages and orchestrates existing capabilities and adds only
a separately identified demo plugin.

## 1. Context

Yano already has several useful app-chain demonstrations, scripts, and product
modules, but they are entered through different directories and command
surfaces. A presenter has to remember which runtime to start, which YAML to
select, which external services are required, when to bootstrap a Cardano
script anchor, how to submit each message, and where retained state lives.

That creates avoidable demo risk:

- a useful capability can appear absent because its demo is hard to discover;
- state-machine, product, runner, and deployment-harness terms are mixed;
- a retained L1 identity can be restarted with incompatible configuration;
- a public-network anchor can be attempted before the node observes the
  funding or bootstrap UTxO;
- scripts can depend on paths in a source checkout rather than a released ZIP;
- a demo effect can be mistaken for a production connector; and
- every presenter can end up maintaining a slightly different collection of
  commands.

The repository needs one showcase facade, not another competing implementation
of the underlying products.

## 2. Terminology and ownership boundary

| Term | Meaning | Current owner |
|---|---|---|
| App-chain framework | Consensus, sequencing, MPF finality, state machines, proofs, anchoring, plugins, and effects | Generic `appchain/*` runtime modules and their ADRs |
| Stock capability | A supported reusable state machine or runtime capability available through the product catalog | ADR-022 catalog and distribution |
| Evidence product | Contracts, registry, client, and evidence profiles forming a reusable application product | `appchain/products/appchain-evidence-*` |
| Evidence demo | A deployable environment that runs the evidence product with its first-party integrations | `app/appchain-effects-demo` |
| Evidence runner | An external client that drives and verifies the evidence scenario | `appchain/examples/appchain-evidence-demo-runner` |
| Showcase facade | One distribution and CLI that delegates to the light cluster, evidence demo, and EUTxO demos | New `appchain-showcase` module |
| Showcase plugin | Demo-only composite profile and local outbox executor used by the light profile | New module-owned plugin bundle |

The showcase facade must preserve these ownership boundaries. In particular,
it must not copy the evidence state machine, connectors, or EUTxO logic into
the showcase module.

## 3. Decision drivers

- A new user can run a meaningful demo from an extracted release without a
  source checkout.
- Presenters have one predictable command surface and one concise runbook.
- Lightweight demonstrations do not require Docker, Kafka, S3, or IPFS.
- Integration-rich demonstrations continue to use the maintained first-party
  implementations.
- The difference between a generic capability, a product, and a demo artifact
  is visible in packaging and documentation.
- L1 anchoring is demonstrable on local devnet and Cardano preprod without
  putting a funding mnemonic in configuration or shell history.
- Retained app-chain and L1 identity state cannot be silently reused with
  incompatible configuration.
- The demo proves restart, idempotence, root convergence, finality, and
  anchoring instead of only submitting HTTP requests.
- The design remains extensible through the public plugin and product catalog
  mechanisms.

## 4. Goals

1. Produce one self-contained `yano-showcase-<version>.zip`.
2. Provide one executable entry point, `./showcase.sh`.
3. Expose exactly three top-level deployment profiles: `light`, `evidence`,
   and `eutxo`.
4. Give each supported scenario a submit command, verification command, and
   short demo flow.
5. Include a deterministic, effect-producing composite example that requires
   no external service.
6. Support safe stop/start restart with data beneath the extracted
   distribution by default.
7. Make devnet the default and make preprod an explicit operator choice.
8. Reuse the runtime console UI rather than creating a showcase-specific UI.

## 5. Non-goals

- A fourth app-chain runtime or a second consensus implementation.
- A general YAML workflow language or arbitrary YAML reordering of composite
  components.
- Moving showcase state machines or local-file effects into Yano core.
- Reimplementing the evidence or EUTxO products.
- Treating the local outbox executor as a production message broker.
- Automatically funding a Cardano address or accepting a funding mnemonic.
- Supporting a mainnet demo profile.
- Dynamically loading a JVM plugin into an already-built native executable.
- Replacing production deployment, secrets, monitoring, or key-management
  tooling.

## 6. Decision

### 6.1 One showcase distribution

A new Gradle module named `appchain-showcase` will assemble a release ZIP. Its
recommended source location is:

```text
appchain/examples/appchain-showcase/
```

The module owns only:

- the unified `showcase.sh` facade;
- configuration overlays and demo payload generators;
- demo scripts and verification tools;
- the light-profile showcase plugin;
- adapters that invoke the maintained evidence and EUTxO demos;
- the showcase README and presenter cheat sheet; and
- distribution and smoke-test wiring.

It does not own duplicated runtime, evidence, connector, or EUTxO business
logic.

### 6.2 Exactly three deployment profiles

In this ADR, **deployment profile** means a showcase environment. It is not a
Quarkus profile and is not a composite state-machine profile.

| Profile | Runtime shape | External dependencies | Variants | Intended use |
|---|---|---|---|---|
| `light` | Three local Yano processes | None | One curated catalog | Fast framework, consensus, state, proof, effect, restart, and anchor demo |
| `evidence` | Existing effects-demo topology | Docker Compose by default; host services remain supported | `composite`, `role` | Product evidence lifecycle with Kafka, S3-compatible storage, and IPFS |
| `eutxo` | Existing EUTxO demo topology | Depends on selected variant | `ledger`, `bridge`, `zk` | Virtual ledger and advanced EUTxO demonstrations |

Profiles are intentionally few. Individual state machines are scenarios within
`light`, not separate deployment profiles.

### 6.3 Light-profile chain catalog

The initial light profile starts these named app-chain instances:

| Instance | State-machine capability | Demo purpose |
|---|---|---|
| `orders-chain` | `ordered-log` | Ordered immutable application messages |
| `registry-chain` | `kv-registry` | Deterministic key/value registration and queries |
| `approvals-chain` | `approvals` | Separate proposal and member approval messages |
| `balances-chain` | `balances` | Deterministic balance transfers and rejection behavior |
| `documents-chain` | `doc-trail` | Document registration and audit history |
| `workflow-chain` | `showcase-composite` | Bound order approval followed by a local outbox effect |
| `roles-chain` | `role-approvals` | Application-specific actors, roles, and approval policy |
| `payments-chain` | `eutxo-ledger` | Lightweight virtual EUTxO operations when present in the distribution |

The catalog must be generated or validated against the release-pinned product
catalog. `doctor` must report a clear unavailable capability instead of
starting a partial topology.

### 6.4 Demo-only composite plugin

The showcase distribution includes one self-contained manifested bundle:

```text
plugins/yano-appchain-showcase-<version>-bundle.jar
```

It contributes:

- `app-state-machine: showcase-composite`; and
- `effect-executor: showcase-outbox`.

It exposes one committed composite preset:

```text
order-approval-outbox-v1
```

The preset is implemented with the existing deterministic composite and
effect SPIs. Its logical flow is:

1. register an order and its canonical content hash;
2. submit a proposal bound to that exact order hash;
3. submit application-voter approvals as separate signed messages;
4. reach the configured application approval threshold;
5. submit a release command that verifies the proposal-to-order binding;
6. atomically commit the release and audit state;
7. emit an immutable `showcase.outbox.write` effect intent;
8. allow the designated executor node to write the outbox receipt; and
9. incorporate the result so the effect becomes `CONFIRMED`.

The deterministic state machine performs no filesystem or network I/O. The
executor performs the external write only after the effect runtime makes the
intent eligible.

The application approval threshold and the app-chain MPF finality threshold
are separate controls, even if the demo configures both to the number two.
Application voters authorize the business release. App-chain members attest
to and finalize the sequenced state transition. A demo may reuse member keys
as convenient voter identities, but this does not merge the two protocols.

The executor writes:

```text
data/showcase/<instance>/outbox/<effect-id>.json
```

Writes must be atomic and idempotent. A retry with the same effect identity
must return the same logical receipt and must not create a second business
event. In a three-node demo, all nodes validate and finalize the intent, but
only the configured executor owner performs the external write.

The same plugin bundle and digest must be installed on all voting nodes.
Dynamic loading is supported only by the JVM distribution. A native showcase,
if added later, must include the contribution at native build time.

### 6.5 Unified command surface

The facade uses the following command families:

```text
./showcase.sh profiles
./showcase.sh describe <profile> [--variant <variant>]
./showcase.sh doctor --profile <profile> [options]

./showcase.sh prepare --profile <profile> [options]
./showcase.sh up --profile <profile> [options]
./showcase.sh quickstart --profile <profile> [options]
./showcase.sh status [--instance <name>]
./showcase.sh ui [--instance <name>]
./showcase.sh logs [--node <n>] [--follow]
./showcase.sh restart [--instance <name>]
./showcase.sh stop [--instance <name>]
./showcase.sh reset --instance <name> --yes

./showcase.sh run <scenario> [options]
./showcase.sh load <scenario> --count <n> [options]
./showcase.sh load-test <orders|registry> --count <n> [options]
./showcase.sh soak-test orders --duration <seconds> --rate <messages-per-second>
./showcase.sh verify <scenario> [options]
```

`run` initially accepts:

```text
orders | registry | approvals | balances | documents | composite |
roles | eutxo | anchor | all
```

Flows that benefit from visible separation also expose granular commands. At
minimum:

```text
./showcase.sh approvals propose ...
./showcase.sh approvals approve --member <member> ...
./showcase.sh composite register-order ...
./showcase.sh composite propose ...
./showcase.sh composite approve --member <member> ...
./showcase.sh composite release ...
./showcase.sh composite verify ...
```

The granular commands make it clear that an approval is a separate app-chain
message. They must print the proposal or order identifier required by the next
step.

`quickstart --profile light` defaults to a three-node devnet cluster with an
MPF finality threshold of two and a separate two-voter application approval
policy. It starts the cluster, verifies plugin parity, runs the
composite/outbox scenario, verifies converged state roots and finality,
verifies the effect receipt, demonstrates the configured anchor, and prints
the status UI URL.

`evidence` commands delegate to `app/appchain-effects-demo`; they do not fork
its orchestration. The showcase exposes the `composite` and `role` variants.

`eutxo` commands delegate to the maintained EUTxO demos. The advanced `bridge`
and `zk` variants are devnet-only unless their owning ADR and implementation
later establish safe public-network support.

### 6.6 Status UI

`./showcase.sh ui` resolves the active instance marker and prints or opens the
runtime console URL for the selected node. The showcase does not implement a
new UI.

The runtime console should expose the capabilities that the selected product
already commits: chain status, messages, roots, finality, proofs, anchors,
approvals, and effect intents/results where applicable. An empty Effect
Executors panel is expected for chains that emit no effects or nodes that do
not own an executor. Showcase documentation must say this directly.

### 6.7 Network and L1 anchoring policy

Devnet is the default. Preprod is opt-in. Mainnet is not exposed by the
showcase facade.

The light quickstart demonstrates a script anchor for `workflow-chain` on
devnet. On preprod, an operator must explicitly supply:

```text
--network preprod
--anchor-mode script
--anchor-key-file /absolute/path/to/raw-ed25519-seed
--confirm-public-anchor preprod
```

The key file is the anchor signing seed expected by the existing cluster
tooling; it is not a Cardano mnemonic. The showcase must never accept, derive,
persist, or print a funding mnemonic. It prints the derived anchor address so
the operator can fund it externally.

For a script anchor, bootstrap is a one-time identity operation for a new
chain/data directory. It occurs only after:

1. the preprod nodes have synchronized far enough to observe the wallet UTxO;
2. the funding transaction is stable enough for the configured L1 view; and
3. the operator has verified that the intended anchor address and network are
   selected.

The command is exposed as:

```text
./showcase.sh anchor bootstrap workflow-chain
```

The bootstrap command records the resulting L1 identity marker. Normal
restart reuses it and does not bootstrap again. If retained L1 state exists
without a valid identity marker, startup fails with an explicit restore,
migrate, new-instance, or reset instruction. It must not invent a new
identity.

The verification command must show the anchor address, anchor mode, current
L2 state root, finality certificate, Cardano transaction identifier, output
reference, and the nodes' observed L1 status.

### 6.8 Data location and restart safety

The default retained state is deliberately inside the extracted distribution:

```text
data/showcase/<instance>/
```

Operators may override it with an explicit absolute data directory. Each
instance contains an immutable deployment marker that records at least:

- showcase and Yano release versions;
- deployment profile and variant;
- network and protocol magic;
- node count and MPF threshold;
- chain identities;
- plugin bundle SHA-256 values;
- rendered configuration and composite-profile digests;
- port allocation;
- runtime flavor; and
- anchor mode and L1 identity reference.

Before touching retained state, `up` and `restart` compare requested settings
with this marker. Identity-affecting drift is rejected. The user must select a
new instance or explicitly reset the old one. `reset` remains destructive and
requires the instance name plus `--yes`.

`stop` preserves data. `restart` means stop and start with the same immutable
marker, never render a subtly different deployment.

### 6.9 Distribution layout and build

The ZIP has this logical layout:

```text
yano-showcase-<version>/
  showcase.sh
  README.md
  docs/                         master/curl demos, runbooks, presenter notes
  demos/
  tools/
  plugins/
  yano/                         packaged JVM distribution and light config
  profiles/evidence/
    demo/                       maintained effects-demo harness
    artifacts/                  runner and connector bundles
    config/network/             packaged genesis/network files
  data/showcase/                retained instance state (created on use)
```

It is built and checked with:

```bash
./gradlew :appchain-showcase:check :appchain-showcase:distZip
```

The resulting archive must be runnable after being copied and extracted to a
different directory. Scripts must resolve their own installation directory;
they must not refer back to the source tree.

### 6.10 Operator dependencies and scale

The supported showcase launcher requires Java 25, Python 3, `curl`, and `jq`.
Its Python helpers use only the standard library; no `pip`, virtual
environment, `cryptography`, or other Python package is required. Docker is
required only by the evidence profile. `doctor` reports the detected Java and
Python versions and validates the profile-specific artifacts.

The light launcher accepts 1–16 local members and defaults to three. The
documented presentation sizes are 3, 5, and 7. This bounded range lets the
distribution generate all disposable Ed25519 demo identities with its
standard-library helper while keeping local process and port use explicit.

Python is a packaging dependency only for the current small operational
helpers. A later showcase/devtools Java CLI may replace identity, canonical
codec, submit/wait, and proof-verification helpers while preserving the shell
facade and curl protocol examples. This is explicitly deferred and does not
justify a core runtime change.

After a governed join, the launcher starts and catches up the new member and
then refreshes existing processes one at a time with the expanded peer
endpoint set. This is a local topology refresh, not another membership vote;
the height-versioned governed member set remains the source of truth. A
scheduled member or threshold epoch is advanced with valid commands tailored
to each configured state machine, followed by a proof block under the newly
active profile.

## 7. Configuration policy

The showcase may render existing application YAML from release-pinned
templates, but it must not introduce a second configuration schema for Yano.
Rendered files remain inspectable beneath the instance directory.

The light profile keeps the full out-of-box state-machine catalog available,
even when quickstart exercises only a subset. A presenter can therefore run
another stock scenario without rebuilding the distribution.

HTTP app-chain API keys are not required by default for a loopback-only local
demo if the existing runtime permits that configuration. If a runtime mode
requires authentication, `prepare` generates scoped local demo credentials,
stores them with restrictive permissions below the instance secrets
directory, and never prints the secret value in normal output. Public or
non-loopback deployment requires explicit authentication.

## 8. Security and trust boundaries

- Demo defaults bind management and submission endpoints to loopback.
- Public-network selection requires an exact confirmation token.
- Secrets and signing seeds are referenced by absolute files, not embedded in
  YAML, sample commands, or the distribution.
- The showcase does not use or document a well-known funded mnemonic as an
  automated funding source.
- Plugin and rendered-profile digests are checked before a voting node starts.
- Only the designated executor node receives the local outbox path and
  executor ownership configuration.
- The outbox executor accepts only its fixed operation schema and writes only
  beneath its resolved instance outbox directory.
- Verification is read-only and must not replay or mutate the business flow.

## 9. Options considered

### 9.1 Documentation and loose scripts only

Rejected. This is inexpensive initially but cannot reliably pin artifacts,
validate retained identity, verify plugin parity, or prove that a copied ZIP
is self-contained.

### 9.2 One always-on topology containing every dependency

Rejected. Requiring Kafka, object storage, IPFS, bridge, and ZK services for
every demo makes the common framework demonstration slow and fragile. The
three-profile split keeps the first experience lightweight while retaining
product-rich demonstrations.

### 9.3 One deployment profile per state machine

Rejected. State machines are scenarios within a runtime profile. Treating
each as a deployment profile multiplies lifecycle commands and obscures their
shared cluster.

### 9.4 Add the showcase composite and outbox executor to core

Rejected. The flow is intentionally opinionated and the filesystem effect is
for demonstration. A plugin proves the extension mechanism without expanding
the supported core contract.

### 9.5 Make arbitrary composite order configurable in YAML

Rejected. Component order, command routing, state namespaces, and invariants
are consensus-visible behavior. They belong in a versioned, tested composite
profile supplied by code, not an unconstrained runtime YAML workflow.

### 9.6 Treat `appchain-effects-demo` as the evidence product

Rejected. The deployable harness is one way to operate the reusable evidence
product. Collapsing those concepts makes it difficult to embed the product in
another deployment and encourages business logic to leak into demo scripts.

## 10. Consequences

### Positive

- A presenter has one archive, one CLI, and one data convention.
- The fast path needs no external infrastructure.
- The evidence and EUTxO demonstrations retain their specialist ownership.
- The demo visibly exercises the same plugin and effect boundaries available
  to applications.
- Restart and public-network anchoring become deliberate and reproducible.
- Separate proposal and approval commands make the MPF flow understandable.

### Costs and trade-offs

- The distribution is larger because it carries runtime and demo artifacts.
- The facade must track compatible commands in the delegated demos.
- Extracted-ZIP smoke tests and public-network-safe configuration tests become
  release responsibilities.
- The JVM showcase plugin cannot be dynamically added to a native image.
- A local outbox is meaningful for demonstrating effects but is not a
  substitute for Kafka, a webhook, or another production integration.

## 11. Implementation record

### M1 — Distribution skeleton (complete)

- Add the `appchain-showcase` Gradle module and `distZip` wiring.
- Package the existing Yano runtime, catalog, scripts, samples, and docs.
- Implement `profiles`, `describe`, and `doctor`.

### M2 — Showcase plugin (complete)

- Implement `showcase-composite`, `order-approval-outbox-v1`, and the
  `showcase-outbox` executor using public SPIs.
- Add deterministic replay, component-order, invalid-binding, threshold,
  executor idempotence, retry, and manifest tests.
- Verify the exact bundle digest on every voting node.

### M3 — Light profile and scenarios (complete)

- Render and launch a three-node cluster with the chain catalog in §6.3.
- Add individual submit, load, and verify commands.
- Add granular proposal/approval and composite-flow commands.
- Print the status UI and useful next commands after startup.

### M4 — Lifecycle and identity safety (complete)

- Add instance markers, configuration and plugin digests, port allocation,
  safe restart, and explicit reset.
- Add devnet script-anchor bootstrap and verification.
- Add guarded preprod configuration and the synchronization/stability checks.

### M5 — Existing-demo adapters (complete)

- Wrap the evidence `composite` and `role` variants without copying them.
- Wrap the EUTxO `ledger`, `bridge`, and `zk` variants without copying them.
- Normalize status, logs, stop, and verification output at the facade.

### M6 — Demo documentation and release gates (complete)

- Add the README, two-page presenter cheat sheet, troubleshooting, and preprod
  anchor runbook, public curl/API walkthrough, and load/soak guide under the
  module-level `docs/` directory.
- Smoke-test from a freshly extracted ZIP in a different directory.
- Add restart, root-parity, effect-idempotence, proof, and devnet-anchor gates.

The implementation is the JVM distribution. A native distribution remains a
separate follow-up and must package contributions at build time.

## 12. Acceptance criteria

The showcase implementation is complete when:

1. `distZip` creates a self-contained archive that runs outside the checkout.
2. `quickstart --profile light` starts three nodes with threshold two.
3. every voting node reports the same plugin/profile digest and converged
   state root.
4. proposal and approval are visibly separate messages, and the output
   distinguishes application approval threshold from MPF finality threshold.
5. the composite release emits one effect and exactly one idempotent outbox
   business record.
6. verification proves finality, state, effect result, and the configured L1
   anchor without mutating state.
7. stop/start preserves chain and L1 identity; incompatible retained state is
   rejected before startup.
8. the stock state-machine scenario and load commands work from the ZIP.
9. the evidence facade runs both supported evidence variants through the
   existing demo.
10. the EUTxO facade runs its supported variants through the existing demos.
11. `ui` resolves the active console URL and documentation explains when
    effect panels are expected to be empty.
12. preprod requires explicit network confirmation and an external key file,
    never a mnemonic.
13. a curl-first master demo shows the real chain-scoped HTTP submission,
    finality lookup, proof, query, and effect endpoints plus API-key behavior.
14. functional repetition, finite parallel burst, and sustained soak commands
    are separately named and report the semantics they actually measure.
15. source documentation lives at the showcase module root and is included in
    the copied ZIP without source-tree references.

All fifteen criteria are satisfied by the implementation. The following
release-candidate evidence was collected from a freshly copied and extracted
ZIP, rather than from source-tree launch paths:

- the light profile completed three-node quickstart with a devnet script
  anchor, governed node-3 join, delayed membership activation, a threshold
  change from 2-of-3 to 3-of-4, rolling peer refresh, signed virtual-EUTxO
  acceptance, four-node root/finality/anchor verification, clean restart, and
  governed node resume without a second membership command;
- the composite evidence variant completed its full inspection workflow,
  connector effects, three-member anchor adoption, and verification;
- the role evidence variant completed actor revision, key rotation,
  revocation, and proposal lifecycle checks using the profile calculator
  packaged inside the ZIP rather than Gradle;
- the EUTxO ledger variant completed a signed three-node round trip with
  converged indexes and an MPF proof root; the bridge and ZK variants produced
  valid packaged workspaces through the same facade;
- distribution, script, plugin-boundary, unit, cluster lifecycle, state
  safety, private-key, overlay, governance, and evidence-compose contracts
  passed; and
- a disposable, copy-on-write preprod smoke test reused the supplied synced L1
  chainstate, proved the public-network confirmation/key-file guards, started
  one node without bootstrapping or submitting an anchor transaction, and
  stopped cleanly. No mnemonic was accepted, persisted, or printed.

The evidence devnet uses a deliberately short, generated epoch schedule. A
prepared-but-never-started disposable evidence instance should be launched
promptly. If it is left idle for multiple epochs before its first block, use a
fresh instance name; the launcher intentionally does not rewrite a retained
network identity behind the operator's back.

## 13. Required documentation statement

The showcase README must contain an unambiguous statement equivalent to:

> `showcase-composite`, `order-approval-outbox-v1`, and the local outbox
> executor are demo-only showcase artifacts. They are not built-in Yano state
> machines, production connectors, or core changes. They use Yano's public
> composite, plugin, and effect SPIs. The evidence and EUTxO profiles delegate
> to maintained first-party products and demos.

It must also describe the evidence boundary as:

> The evidence modules are a reusable product-level assembly. The effects
> demo is the maintained runnable deployment of that product; the demo harness
> itself is not the product.

## 14. Deferred decisions

- Whether a later release publishes the showcase ZIP as a separate download
  or inside the main distribution channel.
- Whether native showcase archives are useful enough to justify build-time
  plugin inclusion and separate release testing.
- Whether additional opinionated composite products graduate from showcase
  examples into independently versioned product modules.
- Whether the current standard-library Python helpers are incrementally
  replaced by a packaged Java showcase/devtools CLI.
- Whether the facade later consumes all delegated-demo operations from a
  machine-readable catalog instead of a small compatibility adapter.
