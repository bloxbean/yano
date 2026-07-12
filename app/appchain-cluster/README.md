# Yano app-chain cluster — quick demo

Spin up an *N*-node [app-chain](../../adr/app-layer/) cluster in one command and
watch messages get sequenced, voted, and finalized across every node — with
their state roots agreeing. Great for a demo, a manual test, or kicking the
tires on multi-chain / sequencer / anchoring behaviour.

By default it runs a **self-contained devnet**: node 0 produces L1 blocks and
the other nodes follow it. No external network, no funds, no setup. You can
also point every node at a **public network** as a relay.

> A Docker/compose version will come later; this launcher is the scriptable
> foundation. It runs against **whichever Yano build you have** — the uber-jar
> or the native binary — auto-detected.

## Prerequisites

- A Yano binary (jar or native) — either a local build **or** a released tree
  (see below).
  - uber-jar: `./gradlew :app:quarkusBuild` → `app/build/yano.jar`, **or**
  - native: `./gradlew :app:build -Dquarkus.native.enabled=true` → `app/build/yano`
- `jq`, `python3`, `curl` on `PATH` (all standard).
- For clusters **larger than 16 nodes**, python's `cryptography` package (used to
  derive extra member keys); 1–16 nodes need nothing extra.

### Running a released build (no local compile)

Nothing needs to be built if you have a released Yano tree. Point `YANO_HOME` at
the tree that holds `config/` (the app resolves `config/*` — chain defs + network
genesis — relative to it), and the jar/native is auto-detected under it (at the
root or `build/`), or set an explicit path:

```bash
# a released tree:  /opt/yano/{yano.jar, config/...}
YANO_HOME=/opt/yano ./cluster.sh start 3

# binary and config in different places
YANO_HOME=/data/yano YANO_JAR=/downloads/yano.jar ./cluster.sh start 3
YANO_NATIVE=/downloads/yano YANO_HOME=/data/yano ./cluster.sh start 3
```

| Env var | Meaning | Default |
|---|---|---|
| `YANO_HOME` | tree holding `config/` (nodes launch with cwd = here) | the repo's `app/` |
| `YANO_JAR` | explicit uber-jar path (any location) | auto-detect under `YANO_HOME` |
| `YANO_NATIVE` | explicit native-binary path | auto-detect under `YANO_HOME` |

`loadtest.sh` and `soaktest.sh` need **no binary at all** — they only hit the
running cluster's HTTP ports, so they work against any running Yano regardless of
how it was started.

## Quick start

```bash
cd app/appchain-cluster

./cluster.sh start 3                 # 3-node devnet with the chains below
./cluster.sh status                  # tips + roots + cross-node agreement
./cluster.sh submit orders-chain orders '{"id":1}'
./cluster.sh status                  # tip advanced on every node
./cluster.sh stop                    # stop, keep data   (clean = stop + wipe)
```

That's the whole loop. `status` shows each node's per-chain tip and state root
and a **consistency** line per chain — `AGREED` means every node finalized the
same history.

## The chains

Chains are defined **once**, shared by every node, in
[`app/config/application-appchain.yml`](../config/application-appchain.yml)
(external — not baked into the jar). Out of the box:

| chain | state machine | sequencer | note |
|-------|---------------|-----------|------|
| `orders-chain` | `ordered-log` | fixed (node 0) | append-only log |
| `registry-chain` | `kv-registry` | rotating | owner-guarded key/value store |

Edit that file to add, remove, or reshape chains — change the state machine,
block cadence, sequencer mode, or add a third chain. The launcher
**auto-discovers** however many `chains[i]` you define and injects the
per-cluster values for you:

- **In the YAML (shared):** `chain-id`, `state-machine`, `block.interval-ms`,
  `sequencer.mode`.
- **Injected by the launcher (per node / per cluster size):** `members`,
  `threshold`, this node's `signing-key`, `peers`, and — for fixed chains — the
  `proposer`. So the same YAML runs unchanged on a 3-node or a 9-node cluster;
  you never hand-edit member lists.

## Submitting payloads

**Any-bytes chains (`ordered-log`)** — send an arbitrary payload on a topic:

```bash
./cluster.sh submit <chain-id> <topic> <payload> [--node i] [--count n]
# or the convenience wrapper:
./submit.sh <chain-id> <topic> <payload> [--node i] [--count n]

./submit.sh orders-chain orders '{"item":"widget","qty":3}'
./submit.sh orders-chain load   "burst" --count 100      # load test
./submit.sh orders-chain orders "from-node-2" --node 2   # submit at any node
```

**Key/value chains (`kv-registry`)** — use the `kv` subcommand (it builds the
CBOR command the registry expects):

```bash
./cluster.sh kv <chain-id> set <key> <value> [--node i]
./cluster.sh kv <chain-id> del <key> [--node i]

./cluster.sh kv registry-chain set color blue
./cluster.sh kv registry-chain set size  large --node 1
./cluster.sh kv registry-chain del color
```

Either way the message is gossiped to every member and finalized into a block
on all nodes. Submit to **any** node — it reaches the proposer over the
app-chain gossip network. (Under the hood, `submit` posts `{topic, body}` and
`kv` posts `{topic, bodyHex}` to
`POST /api/v1/app-chain/chains/<chain-id>/messages`.)

Read it back:

```bash
curl -s localhost:7070/api/v1/app-chain/chains/orders-chain/blocks | jq .
# kv membership proof for key "color" (hex 636f6c6f72) against the state root:
curl -s localhost:7070/api/v1/app-chain/chains/registry-chain/proof/636f6c6f72 | jq .
```

## Load / throughput testing

Fire many messages concurrently and measure both **submit** throughput (how
fast the API accepts) and **finalization** throughput (how fast messages land
in certified blocks — the real chain throughput), plus backpressure drops:

```bash
./cluster.sh loadtest orders-chain                  # 1000 msgs, 20 concurrent
./cluster.sh loadtest orders-chain -n 5000 -c 50    # heavier
./cluster.sh loadtest orders-chain -n 5000 --spread # spread submits across all nodes
./cluster.sh loadtest orders-chain -n 2000 -s 512   # 512-byte payloads
# (or run ./loadtest.sh directly with the same args)
```

Example report:

```
==================== throughput ====================
  submitted attempts : 5000
  accepted (2xx)     : 5000
  dropped  (429 pool): 0
  errors             : 0

  submit time        : 3.4 s
  SUBMIT rate        : 1,470.6 msg/s   (accepted / submit-time)

  finalized msgs     : 5000   in 7 block(s)
  msgs / block       : 714.3
  block-window span  : 6.1 s   (first→last finalized block)
  FINALIZE rate      : 819.7 msg/s   (finalized / block-window)
  end-to-end rate    : 588.2 msg/s   (finalized / total-time 8.5s)
====================================================
```

- **dropped (429)** is real backpressure: the pending pool filled faster than
  blocks drained. Raise it with `yano.app-chain.chains[i].pool.max-messages`,
  or lower `block.interval-ms` / raise `block.max-messages` to drain faster.
- Point load at any-bytes (`ordered-log`) chains; a `kv-registry` chain rejects
  unstructured payloads at admission.

## Node identities & ports

Deterministic demo identities: node *i* uses the 32-byte seed `(i+1)` repeated,
and its member public key is derived from it (`./cluster.sh keys N` prints them).
These are **demo keys** — do not use them for anything real.

| node | HTTP | n2n (server) | role (devnet) |
|------|------|--------------|----------------|
| 0 | 7070 | 13337 | L1 block producer + member (fixed proposer) |
| i | 7070+i | 13337+i | L1 follower + member |

Override bases with `--http-base` / `--server-base`, or the data dir with
`--data-dir` (default `/tmp/yano-appchain-cluster`).

## Relay to a public network

```bash
./cluster.sh start 3 --network preprod
```

Instead of a devnet, **every node relays the chosen network** (`preprod`,
`preview`, `mainnet`, `sanchonet`) and the app chains run on top of that L1 view.

Caveats:

- Each node independently syncs the public chain, so first start is **not**
  instant and uses real bandwidth/disk. For a fast demo, prefer devnet.
- **L1 anchoring** on a public network works, but needs a wallet YOU fund —
  see below. The demo's deterministic anchor keys hold nothing on preprod.

## L1 anchoring

Anchoring runs on **node 0 only** (the anchor leader). Script-mode followers
co-sign and adopt the on-chain identity with zero anchor config; metadata-mode
followers need nothing at all.

Two modes (`--anchor-mode metadata|script`):

| Mode | `metadata` | `script` (ADR [008.4](../../adr/app-layer/008.4-script-anchors-l1view.md)) |
|------|------------|------------|
| Anchor tx | plain tx, anchor payload in tx metadata | Plutus V3 thread-NFT UTxO, validator-enforced datum chain |
| Signing | anchor wallet only | wallet + threshold co-signed member witnesses |
| Setup | fund the wallet — that's it | fund + one-time `anchor-bootstrap <chain>` per chain |

### Devnet (fast demo)

```bash
./cluster.sh start 3 --anchor                  # script mode (default)
./cluster.sh anchor-bootstrap orders-chain     # funds via faucet + mints the thread NFT
# or, simplest possible anchoring:
./cluster.sh start 3 --anchor-mode metadata    # fund via faucet, anchors just start
```

### Public network (e.g. preprod)

```bash
openssl rand -hex 32                           # generate an anchor wallet seed
./cluster.sh start 3 --network preprod --anchor-mode metadata --anchor-key <that-hex>
# start prints the anchor wallet's enterprise address -> send tADA to it
# metadata mode: anchors start automatically once funded
# script mode:   ./cluster.sh anchor-bootstrap <chain>   (one-time, per chain)
```

The anchor wallet is a raw 32-byte Ed25519 seed and its **enterprise address**
(printed at start). A CIP-1852 wallet mnemonic (Eternl/Lace/devkit) can NOT be
converted into this seed — HD payment keys are extended keys, not seeds.
Generate a fresh seed and send funds to its address from any wallet instead.
Cadence: `--anchor-every N` app blocks (default 2 on devnet, 30 on public —
every anchor is a fee-paying L1 tx). Watch it:

```bash
./cluster.sh status
curl -s localhost:7070/api/v1/app-chain/chains/orders-chain/status | jq .anchor
```

Each chain that bootstraps a script anchor gets its **own on-chain identity**:
the bootstrap consumes a unique seed UTxO, so each chain mints a distinct
thread policy id, and the validator (parameterized by that policy id) lands at
a distinct script address.

## Commands

```
./cluster.sh start [N] [options]   start an N-node cluster (default 3)
./cluster.sh status                health + per-chain tips/roots + consistency
./cluster.sh submit ...            submit a payload to an ordered-log chain
./cluster.sh kv ...                set/del on a kv-registry chain
./cluster.sh anchor-bootstrap C    bootstrap a script anchor (devnet: auto-funds)
./cluster.sh logs <node> [-f]      show/tail a node log
./cluster.sh keys [N]              print member seeds + pubkeys
./cluster.sh chains                list chains from the config
./cluster.sh stop                  stop nodes (keep data)
./cluster.sh clean                 stop nodes + wipe data
```

Start options: `--network <net>`, `--jar` | `--native`, `--threshold <t>`,
`--anchor`, `--anchor-mode <metadata|script>`, `--anchor-key <hex>`,
`--anchor-every <n>`, `--data-dir <dir>`, `--http-base <p>`, `--server-base <p>`.

## Troubleshooting

- **A node won't become ready** — check its log: `./cluster.sh logs <i>`.
  First-boot followers can briefly wedge on L1 header continuity; `./cluster.sh
  stop` then `start` again recovers.
- **`no Yano binary found under …`** — build the jar (`./gradlew
  :app:quarkusBuild`) or native binary, **or** point `YANO_JAR` / `YANO_NATIVE`
  at a released binary and `YANO_HOME` at its config tree (see *Running a
  released build* above).
- **`config not found …`** — `YANO_HOME` must contain
  `config/application-appchain.yml` and `config/network/<net>/…` genesis.
- **Consistency shows MISMATCH** — give it a few seconds (`status` again); a
  fresh cluster needs a moment for all nodes to connect and catch up.
- **Ports busy** — another cluster or process holds 7070+/13337+. `./cluster.sh
  stop`, or start with `--http-base` / `--server-base`.
- Everything lives under `--data-dir` (logs, per-node chainstate, genesis
  copies); `./cluster.sh clean` wipes it.
