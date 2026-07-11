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

- A Yano build in `app/build/`:
  - uber-jar: `./gradlew :app:quarkusBuild` → `app/build/yano.jar`, **or**
  - native: `./gradlew :app:build -Dquarkus.native.enabled=true` → `app/build/yano`
- `jq`, `python3`, `curl` on `PATH` (all standard).
- For clusters **larger than 16 nodes**, python's `cryptography` package (used to
  derive extra member keys); 1–16 nodes need nothing extra.

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
- **L1 anchoring** to a public network needs funded anchor keys — the demo's
  deterministic keys are unfunded. Anchoring is left off in relay mode.

## L1 anchoring (devnet)

```bash
./cluster.sh start 3 --anchor
./cluster.sh anchor-bootstrap orders-chain     # funds + mints the thread NFT
```

With `--anchor`, every chain runs [script anchors](../../adr/app-layer/008.4-script-anchors-l1view.md)
(ADR 008.4). `anchor-bootstrap` funds the anchor wallet from the devnet faucet
and mints the one-shot thread NFT; from then on the members co-sign periodic
anchor transactions onto the devnet L1. Watch it:

```bash
./cluster.sh status
curl -s localhost:7070/api/v1/app-chain/chains/orders-chain/status | jq .anchor
```

## Commands

```
./cluster.sh start [N] [options]   start an N-node cluster (default 3)
./cluster.sh status                health + per-chain tips/roots + consistency
./cluster.sh submit ...            submit a payload to an ordered-log chain
./cluster.sh kv ...                set/del on a kv-registry chain
./cluster.sh anchor-bootstrap C    fund + bootstrap a script anchor (devnet)
./cluster.sh logs <node> [-f]      show/tail a node log
./cluster.sh keys [N]              print member seeds + pubkeys
./cluster.sh chains                list chains from the config
./cluster.sh stop                  stop nodes (keep data)
./cluster.sh clean                 stop nodes + wipe data
```

Start options: `--network <net>`, `--jar` | `--native`, `--threshold <t>`,
`--anchor`, `--data-dir <dir>`, `--http-base <p>`, `--server-base <p>`.

## Troubleshooting

- **A node won't become ready** — check its log: `./cluster.sh logs <i>`.
  First-boot followers can briefly wedge on L1 header continuity; `./cluster.sh
  stop` then `start` again recovers.
- **`no Yano build found`** — build the jar (`./gradlew :app:quarkusBuild`) or
  native binary first.
- **Consistency shows MISMATCH** — give it a few seconds (`status` again); a
  fresh cluster needs a moment for all nodes to connect and catch up.
- **Ports busy** — another cluster or process holds 7070+/13337+. `./cluster.sh
  stop`, or start with `--http-base` / `--server-base`.
- Everything lives under `--data-dir` (logs, per-node chainstate, genesis
  copies); `./cluster.sh clean` wipes it.
