# Yano App-Chain Showcase

This distribution is the single demo entry point for Yano app chains. Start
with [MASTER_DEMO.md](docs/MASTER_DEMO.md), use the
[curl/API version](docs/MASTER_DEMO_CURL.md), or run:

```bash
./showcase.sh doctor
./showcase.sh quickstart --profile light --nodes 3 --instance demo
```

The light profile runs thirteen app chains on one local multi-node Yano cluster,
including `payment-chain-settlement`, with no Kafka, object store, IPFS, or
separate effect service. Seven chains present standalone foundations; the
remaining reference chains demonstrate how ADR-031 composes those foundations
with cross-cutting concerns instead of creating another application SPI:

| Reference application | Reused capabilities |
|---|---|
| `workflow-chain` | orders + basic approval + audit + finality-gated outbox effect |
| `document-review-chain` | document trail + domain actors/roles + actor approval + one-use receipt |
| `authenticated-map-chain` | authenticated map + direct actor/role authorization + multi-organization approval |
| `payment-chain-settlement` | EUTxO + L1 observers + settlement effects + rebuildable lifecycle index |
| `cardano-history-chain` | protocol parameters + epoch stake + governance, with optional per-epoch authenticated snapshots |

`authenticated-map-jmt-chain` uses the same collections, policies, actors,
commands, and receipts as the MPF chain; only its commitment backend and
off-chain-only verification target differ.

Cardano History defaults to the low-cost `params-only-v1` preset. Select
`--cardano-history-profile params-stake|params-governance|full` on a fresh
instance to enable the corresponding L1 scans. Enable reusable authenticated snapshots selectively
with `--enable-authenticated-snapshots=cardano-history-chain`. The default MPF
secondary roots support nested off-chain/on-chain proof verification; the
optional `--authenticated-snapshot-profile jmt-blake2b256-v1` profile is an
off-chain comparison. The console shows the capability only when enabled.
MPF deployments can evaluate archive-time reachable-node pruning with
`--enable-authenticated-snapshot-mpf-pruning=cardano-history-chain`. It is a
node-local, retained instance setting and remains disabled by default while retained-root
qualification expands.

The built-in `authenticated-map` scenario demonstrates multiple collections,
opaque and canonical-CBOR values, a declarative schema, the first-party GS1
validator SPI example, and the ADR-025.2 governed flows: a direct-role
collection written with externally signed actor evidence and an approval
collection executed only after two auditors from distinct organizations
approve. The packaged console renders every governed record at
`/ui/app-chain/authenticated-map/`. The light profile also includes one clearly
demo-only plugin, `showcase-composite` / `showcase-outbox`, to make the
deterministic-intent → finality-gate → external-execution → on-chain-result
flow visible.

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
`restart` validates the retained deployment marker and reuses it. The marker
also binds the generated authenticated-map settings and genesis to the actual
bootstrap members and release validator digest. Identity drift fails closed;
the finalized-message-index scope is also retained and changes the state
generation identity. The only supported marker evolution is explicit,
additive `anchor enable`. `reset --instance <name> --yes` is the only showcase command that
deletes the named instance.

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

Configuration safety has two layers. Launcher-owned identity files bind the
L1 network/genesis, configured chain IDs, bootstrap members/threshold,
proposer, config/plugin/authenticated-map digests, and anchor signer/scope.
Per-chain RocksDB stores finalized blocks and MPF state, the committed
consensus-profile marker, and every governed membership epoch. A normal
restart therefore rejects YAML or bootstrap identity drift before starting,
while governed member/threshold changes survive restart and win over the
original static values.

Do not bypass the launcher with a one-node system-property or YAML edit. A
consensus-profile mismatch fails that node's startup; other ungoverned drift
can split votes or stall availability even though a lone node cannot create a
threshold finality certificate. Use `member`, `threshold`, or the additive
`anchor enable <chain-id|all>` migration instead.

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
- [CAPABILITY_CATALOG.md](docs/CAPABILITY_CATALOG.md)
- [CARDANO_HISTORY.md](docs/CARDANO_HISTORY.md)
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

Architecture and product/demo boundaries are recorded in ADR-023. ADR-033
defines the catalog, manifest discovery contract, generic finalized-message
index wrapper, console presentation, and qualification requirements.
