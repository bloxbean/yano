# Yano App-Chain Showcase

This distribution is the single demo entry point for Yano app chains. Start
with [MASTER_DEMO.md](docs/MASTER_DEMO.md), use the
[curl/API version](docs/MASTER_DEMO_CURL.md), or run:

```bash
./showcase.sh doctor
./showcase.sh quickstart --profile light --nodes 3 --instance demo
```

The light profile runs eight app chains on one local multi-node Yano cluster,
with no Kafka, object store, IPFS, or separate effect service. It includes one
clearly demo-only plugin, `showcase-composite` / `showcase-outbox`, to make the
deterministic-intent → finality-gate → external-execution → on-chain-result
flow visible. It is not a production connector or a new Yano core feature.

`showcase-composite`, its `order-approval-outbox-v1` preset, and the local
`showcase-outbox` executor are demo-only showcase artifacts. They are not
built-in Yano state machines, production connectors, or core changes. They use
Yano's public composite, plugin, and effect SPIs.

The `evidence` profile delegates to the maintained evidence product demo with
Kafka, S3-compatible storage, and IPFS. The evidence product is reusable; the
showcase and effects-demo directories are presentation harnesses, not the
product itself. The `eutxo` profile delegates to the maintained ledger,
bridge, and ZK EUTxO demos.

## Requirements

- Java 25
- Python 3 standard library only (no `pip`, virtual environment, or
  `cryptography` package for the supported 1–16-node showcase)
- `curl` and `jq`
- Docker only for the `evidence` profile

Run `./showcase.sh doctor --profile <profile>` before presenting.

The `demos/` directory contains one-command wrappers for each light scenario,
separate proposal/approval and composite workflow steps, functional load,
burst load, soak load, and the complete interactive master run.

Governed changes are intentionally height-delayed. After `member join` or
`threshold set`, use `./showcase.sh governance activate --instance demo` to
advance labeled real messages on every light-profile chain and finalize a
proof block under the newly active profile. A join also refreshes the local
peer topology one process at a time so the new member can receive proposal and
script-anchor co-sign requests; it does not resubmit governance.

## Data, restart, and configuration

State stays below `data/showcase/<instance>/` by default. `stop` preserves it;
`restart` validates the immutable deployment marker and reuses it. Identity
drift fails closed. `reset --instance <name> --yes` is the only showcase
command that deletes the named instance.

```bash
./showcase.sh config show  --instance demo
./showcase.sh config paths --instance demo
./showcase.sh config export ./demo-config.json --instance demo
```

`show` combines the immutable deployment description with live chain
identities when the cluster is running, including active and scheduled
governance values and activation heights. It never prints signing seeds, API
keys, or anchor-key contents. `paths` points to the shared YAML, node overlays,
plugin, state, logs, and outbox. `export` writes a redacted shareable snapshot.

The HTTP API is bound to loopback. The launcher uses its documented local-demo
admin key internally. Do not expose these defaults on a public interface.

## Status UI

```bash
./showcase.sh ui --instance demo
```

Open the printed URL. A chain with no effects legitimately has an empty Effect
Executors section. On `workflow-chain`, only node 0 owns `showcase-outbox`;
other nodes still validate the effect intent and its incorporated result.

## Profiles and docs

- [LIGHT_PROFILE.md](docs/LIGHT_PROFILE.md)
- [EVIDENCE_PROFILE.md](docs/EVIDENCE_PROFILE.md)
- [EUTXO_PROFILE.md](docs/EUTXO_PROFILE.md)
- [GOVERNANCE_DEMO.md](docs/GOVERNANCE_DEMO.md)
- [MESSAGE_SUBMISSION.md](docs/MESSAGE_SUBMISSION.md)
- [LOAD_AND_SOAK.md](docs/LOAD_AND_SOAK.md)
- [ANCHORING_DEMO.md](docs/ANCHORING_DEMO.md)
- [PREPROD_ANCHORING.md](docs/PREPROD_ANCHORING.md)
- [MASTER_DEMO_CURL.md](docs/MASTER_DEMO_CURL.md)
- [JAVA_CLI_ROADMAP.md](docs/JAVA_CLI_ROADMAP.md)
- [TROUBLESHOOTING.md](docs/TROUBLESHOOTING.md)
- [PRESENTERS.md](docs/PRESENTERS.md)

Architecture and product/demo boundaries are recorded in ADR-023 in the Yano
source repository. No Yano consensus/core code is changed by this module.
