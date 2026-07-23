# App-Chain Capability Catalog

This is the human-readable view of the release-pinned app-chain product
catalog. Use it to distinguish what is already in a Yano distribution from
what must be installed, built into a native image, or treated as a reference
or experiment.

The authoritative data is shipped with each release as
`config/schema/appchain-capability-catalog.json`. The build verifies every row
below against that catalog. From a release or the source `app/` directory, ask
the public CLI for the exact current data:

```bash
./yano.sh appchain capabilities
./yano.sh appchain capabilities --format json
```

The default output is a wrapped ASCII table for terminal use. Use
`--format json` for scripts, automation, or a future HTML user interface; the
JSON catalog remains the canonical structured representation.

## Availability vocabulary

| Availability | Meaning |
|---|---|
| `BUNDLED` | Included, indexed, and tested in the named distribution |
| `FIRST_PARTY_OPTIONAL` | Maintained by Yano, but installed separately for JVM or included while building a native image |
| `REFERENCE` | A supported extension pattern or example, not bundled application behavior |
| `EXPERIMENTAL` | Explicit opt-in with unstable API, semantics, or operational posture |

Maturity is independent of packaging. For example, a preview state machine can
be bundled, while an optional connector can still be maintained by Yano.
`chain` settings affect shared application semantics, `node` settings belong
to one operator, and `distribution` entries describe packaged infrastructure.
“Derived” means the distribution supplies the capability; a blueprint cannot
select it explicitly.

## Capabilities

<!-- capability-catalog:start -->
| ID | Category | Availability | Maturity | Scope | Selectable | Runtimes | Native posture | Required artifacts | Reference |
|---|---|---|---|---|---:|---|---|---|---|
| `state:ordered-log` | `state` | `BUNDLED` | `stable` | `chain` | yes | JVM, native | `bundled` | `yano-runtime` | [Ordered log](state-machines/ordered-log.md) |
| `state:kv-registry` | `state` | `BUNDLED` | `stable` | `chain` | yes | JVM, native | `bundled` | `yano-runtime`, `appchain-stdlib` | [KV registry](state-machines/kv-registry.md) |
| `state:approval-workflow` | `state` | `BUNDLED` | `stable` | `chain` | yes | JVM, native | `bundled` | `yano-runtime`, `appchain-stdlib` | [Approvals](state-machines/approvals.md) |
| `state:balances` | `state` | `BUNDLED` | `stable` | `chain` | yes | JVM, native | `bundled` | `yano-runtime`, `appchain-stdlib` | [Balances](state-machines/balances.md) |
| `state:doc-trail` | `state` | `BUNDLED` | `stable` | `chain` | yes | JVM, native | `bundled` | `yano-runtime`, `appchain-stdlib` | [Document trail](state-machines/doc-trail.md) |
| `state:evidence-registry` | `state` | `BUNDLED` | `preview` | `chain` | yes | JVM, native | `bundled` | `yano-runtime`, `appchain-evidence-registry` | [Evidence](tutorials/04-evidence-publication.md) |
| `state:role-approvals` | `state` | `BUNDLED` | `preview` | `chain` | yes | JVM, native | `bundled` | `yano-runtime`, `appchain-composite`, `appchain-role-workflow` | [Generic role approvals](state-machines/role-approvals.md) |
| `state:role-evidence` | `state` | `BUNDLED` | `preview` | `chain` | yes | JVM, native | `bundled` | `yano-runtime`, `appchain-stdlib`, `appchain-evidence-registry`, `appchain-composite`, `appchain-role-workflow`, `appchain-evidence-profile` | [Role evidence](tutorials/04-evidence-publication.md) |
| `state:custom-plugin` | `custom-plugin` | `REFERENCE` | `experimental` | `chain` | yes | JVM | `unsupported` | `yano-runtime` | [Custom plugins](tutorials/08-plugins-and-composites.md) |
| `state:zk-gate` | `state` | `EXPERIMENTAL` | `experimental` | `chain` | yes | JVM, native | `build-time-only` | `yano-runtime`, `appchain-zk` | [ZK extension](../../appchain/extensions/appchain-zk/README.md) |
| `state:zk-membership` | `state` | `EXPERIMENTAL` | `experimental` | `chain` | yes | JVM, native | `build-time-only` | `yano-runtime`, `appchain-zk` | [ZK extension](../../appchain/extensions/appchain-zk/README.md) |
| `state:credential-registry` | `state` | `EXPERIMENTAL` | `experimental` | `chain` | yes | JVM, native | `build-time-only` | `yano-runtime`, `appchain-zk` | [ZK extension](../../appchain/extensions/appchain-zk/README.md) |
| `sequencer:fixed` | `sequencer` | `BUNDLED` | `stable` | `chain` | yes | JVM, native | `bundled` | `yano-runtime` | [App-chain guide](README.md) |
| `sequencer:rotating` | `sequencer` | `BUNDLED` | `experimental` | `chain` | yes | JVM, native | `bundled` | `yano-runtime` | [App-chain guide](README.md) |
| `membership:static` | `membership` | `BUNDLED` | `stable` | `chain` | yes | JVM, native | `bundled` | `yano-runtime` | [App-chain guide](README.md) |
| `membership:governed` | `membership` | `BUNDLED` | `preview` | `chain` | yes | JVM, native | `bundled` | `yano-runtime` | [App-chain guide](README.md) |
| `l1:slot-feed` | `l1` | `BUNDLED` | `stable` | `chain` | yes | JVM, native | `bundled` | `yano-runtime` | [App-chain guide](README.md) |
| `anchor:metadata` | `anchor` | `BUNDLED` | `stable` | `chain` | yes | JVM, native | `bundled` | `yano-runtime` | [Anchors](tutorials/07-anchors-and-verification.md) |
| `anchor:script` | `anchor` | `BUNDLED` | `preview` | `chain` | yes | JVM, native | `bundled` | `yano-runtime` | [Anchors](tutorials/07-anchors-and-verification.md) |
| `observer:address-deposit` | `l1-observer` | `BUNDLED` | `preview` | `chain` | yes | JVM, native | `bundled` | `yano-runtime` | [App-chain guide](README.md) |
| `observer:metadata-label` | `l1-observer` | `BUNDLED` | `preview` | `chain` | yes | JVM, native | `bundled` | `yano-runtime` | [App-chain guide](README.md) |
| `effects:runtime` | `effects-runtime` | `BUNDLED` | `preview` | `chain` | yes | JVM, native | `bundled` | `yano-runtime` | [Effects](tutorials/06-webhook-effects.md) |
| `effects:on-approved` | `effect-emission` | `BUNDLED` | `preview` | `chain` | yes | JVM, native | `bundled` | `yano-runtime`, `appchain-stdlib` | [Approval effect](state-machines/approvals.md) |
| `executor:webhook` | `effect-executor` | `BUNDLED` | `preview` | `node` | yes | JVM, native | `bundled` | `yano-runtime` | [Webhook effects](tutorials/06-webhook-effects.md) |
| `sink:webhook` | `finalized-sink` | `BUNDLED` | `stable` | `node` | yes | JVM, native | `bundled` | `yano-runtime` | [App-chain guide](README.md) |
| `executor:kafka` | `effect-executor` | `FIRST_PARTY_OPTIONAL` | `preview` | `node` | yes | JVM, native | `build-time-only` | `yano-runtime`, `appchain-kafka` | [Optional connectors](OPTIONAL_CONNECTORS.md#kafka) |
| `sink:kafka` | `finalized-sink` | `FIRST_PARTY_OPTIONAL` | `preview` | `node` | yes | JVM, native | `build-time-only` | `yano-runtime`, `appchain-kafka` | [Optional connectors](OPTIONAL_CONNECTORS.md#kafka) |
| `executor:objectstore-s3` | `effect-executor` | `FIRST_PARTY_OPTIONAL` | `preview` | `node` | yes | JVM, native | `build-time-only` | `yano-runtime`, `appchain-objectstore-s3` | [Optional connectors](OPTIONAL_CONNECTORS.md#s3-compatible-object-storage) |
| `executor:ipfs` | `effect-executor` | `FIRST_PARTY_OPTIONAL` | `preview` | `node` | yes | JVM, native | `build-time-only` | `yano-runtime`, `appchain-ipfs` | [Optional connectors](OPTIONAL_CONNECTORS.md#ipfskubo) |
| `executor:cardano-payment` | `effect-executor` | `FIRST_PARTY_OPTIONAL` | `preview` | `node` | yes | JVM, native | `build-time-only` | `yano-runtime`, `appchain-effects-cardano` | [Optional connectors](OPTIONAL_CONNECTORS.md#cardano-payments) |
| `runtime:composite` | `distribution` | `BUNDLED` | `preview` | `distribution` | derived | JVM, native | `bundled` | `appchain-composite` | [Composites](tutorials/08-plugins-and-composites.md) |
| `ui:console` | `distribution` | `BUNDLED` | `preview` | `node` | derived | JVM, native | `bundled` | `yano-runtime` | [ADR-028](../../adr/028-unified-console-ui-module.md) |
| `observability:prometheus` | `distribution` | `FIRST_PARTY_OPTIONAL` | `preview` | `distribution` | derived | JVM, native | `bundled` | `yano-runtime` | [Console observability](../console-ui.md#historical-metrics) |
<!-- capability-catalog:end -->

## Recipes

Recipes are reviewed starting points. A recipe resolves its required
capabilities first; users may then add only compatible selectable capabilities.

<!-- recipe-catalog:start -->
| Recipe | Availability | Maturity | Runtimes | Native posture | Required artifacts | Reference |
|---|---|---|---|---|---|---|
| `audit-log` | `BUNDLED` | `stable` | JVM, native | `bundled` | `yano-runtime` | [Ordered log](state-machines/ordered-log.md) |
| `owned-registry` | `BUNDLED` | `stable` | JVM, native | `bundled` | `yano-runtime`, `appchain-stdlib` | [KV registry](state-machines/kv-registry.md) |
| `approval-workflow` | `BUNDLED` | `stable` | JVM, native | `bundled` | `yano-runtime`, `appchain-stdlib` | [Approvals](state-machines/approvals.md) |
| `role-approval` | `BUNDLED` | `preview` | JVM, native | `bundled` | `yano-runtime`, `appchain-composite`, `appchain-role-workflow` | [Generic role approvals](state-machines/role-approvals.md) |
| `evidence-ledger` | `BUNDLED` | `preview` | JVM, native | `bundled` | `yano-runtime`, `appchain-stdlib`, `appchain-evidence-registry`, `appchain-composite`, `appchain-role-workflow`, `appchain-evidence-profile` | [Evidence](tutorials/04-evidence-publication.md) |
| `custom-plugin` | `REFERENCE` | `experimental` | JVM | `unsupported` | `yano-runtime` | [Custom plugins](tutorials/08-plugins-and-composites.md) |
<!-- recipe-catalog:end -->

## Selection and deployment rules

- Start with `./yano.sh appchain recipes`, then inspect
  `./yano.sh appchain capabilities`.
- A bundled capability needs no additional runtime JAR. It may still need
  public identities, application bootstrap, or node-local configuration.
- An optional JVM capability requires the exact release-matched plugin bundle
  in every applicable node's `plugins/` directory.
- A native executable cannot load a new directory JAR. Optional native
  capabilities must be selected before native-image generation.
- Secrets, actor private keys, connector credentials, and wallet keys remain
  node- or actor-local. They never belong in the blueprint, lock, Studio URL,
  or shared consensus YAML.
- `doctor` reports configuration, artifact, identity, bootstrap, executor,
  external-target, and outcome readiness independently.

See [Optional first-party connectors](OPTIONAL_CONNECTORS.md) for installation
and security ownership, and [release acceptance](RELEASE_ACCEPTANCE.md) for the
tests behind these claims.
