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
| `YANO_CLUSTER_API_KEY` | full key for privileged local-cluster operations | `yano-local-cluster-full-key` |
| `YANO_CLUSTER_NODE_CONFIG_DIR` | optional directory of private per-node configuration overlays | unset |
| `YANO_CLUSTER_DEVNET_GENESIS_FILE` | optional pre-generated shared devnet Shelley genesis | unset |
| `YANO_CLUSTER_APPCHAIN_IDENTITY_MARKER` | orchestrator-owned app-chain identity marker when app-chain state is stored separately from L1 | unset |

`loadtest.sh` and `soaktest.sh` need **no binary at all** — they only hit a
running cluster's HTTP ports. They target the public READ/SUBMIT policy used by
this launcher; a separately started node with broad API authentication enabled
needs an authenticated client instead.

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

### Local API key

`cluster.sh` keeps reads, submissions, status and live streams public on its
loopback-only HTTP API, while privileged admin, effect and plugin operations
require the known local-demo full key `yano-local-cluster-full-key`. This
mirrors the launcher's known demo member keys. Do not use the default as a
production credential. Override it for a shared machine or public-network test
and keep the variable exported for later launcher commands:

```bash
export YANO_CLUSTER_API_KEY="$(openssl rand -hex 32)"
./cluster.sh start 3 --network preprod
./cluster.sh status
```

Launcher commands add `X-API-Key` only for privileged operations such as
`anchor-bootstrap`. Direct privileged HTTP calls must add it themselves.
Outside this demo launcher, configure every real node through a secret source with
`YANO_APP_CHAIN_API_KEYS=<unscoped-full-key>`. Also set
`YANO_APP_CHAIN_API_AUTH_ENABLED=true` when reads and submissions must require
keys too; no production default exists.

### Private per-node configuration overlays

Connector endpoints and credentials can differ by executor node without being
expanded into process arguments. Put one Java-properties file per node in a
private directory and opt in with `YANO_CLUSTER_NODE_CONFIG_DIR`:

```bash
install -d -m 700 /tmp/yano-node-config
install -m 600 config/node0.properties /tmp/yano-node-config/node0.properties
install -m 600 config/node1.properties /tmp/yano-node-config/node1.properties
install -m 600 config/node2.properties /tmp/yano-node-config/node2.properties

YANO_CLUSTER_NODE_CONFIG_DIR=/tmp/yano-node-config ./cluster.sh start 3
```

For an `N`-node start, the directory's `node*.properties` set must be exactly
`node0.properties` through `node<N-1>.properties`; stale or malformed node
overlay names are rejected. Unrelated support files such as trust stores are
permitted. Each overlay must be a readable regular file, not a symlink, remain
inside the canonical configured directory, be owned by the launcher account,
and grant no group or world permissions (`chmod 600` is recommended). The
canonical directory must likewise be launcher-owned and not group or world
writable (`chmod 700` is recommended). Its canonical ancestors must be owned by
root or the launcher account; group/world-writable ancestors are rejected unless
they are sticky directories such as `/tmp`. All files are checked before any
cluster state is created, and the selected file's path identity, owner, mode,
grammar, and ancestor chain are checked again immediately before each node is
launched. Root and processes running as the launcher account remain trusted.

Each file must contain exactly one literal `config_ordinal=275` line. The
overlay otherwise uses a deliberately small properties grammar: bounded UTF-8,
one ASCII key and `=` per physical line, with no key escapes or continuations.
The launcher validates keys but never interpolates or prints values. It rejects
every other ordinal spelling/value and configuration-location selector keys;
otherwise a file could raise its own precedence or recursively select another
source. The launcher selects the corresponding file through the child process's
`QUARKUS_CONFIG_LOCATIONS=file:///.../node<N>.properties` environment value.
The fixed overlay ordinal is above packaged/filesystem application config but
below direct environment values and system properties. Therefore launcher-owned
API-authentication environment values and system properties (ports, storage,
membership, and signing keys) remain authoritative even if an overlay repeats
one. Selecting the file with `-Dquarkus.config.locations`, or omitting the fixed
ordinal, would incorrectly let it tie a higher-precedence source. When
`YANO_CLUSTER_NODE_CONFIG_DIR` is unset, startup arguments and behavior are
unchanged.

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

For operator-provisioned identities, set `YANO_CLUSTER_MEMBER_KEY_DIR` to a
launcher-owned `chmod 700` directory containing `node0.seed`, `node0.public`,
... through the requested node count. Each file must be a regular non-symlink
`chmod 400`/`600` file containing exactly one 64-hex-character value. The
launcher validates and reads the complete set before creating cluster state,
proves each public key derives from its paired Ed25519 seed, and rejects
duplicate members. Supplied private seeds are never placed in the Java/native
argument vector: the launcher writes per-node `chmod 600` config overlays and
passes only their file URIs. Set `YANO_CLUSTER_PRIVATE_CONFIG_DIR` to an
owner-only directory outside the cluster data tree for those generated files.
Standalone use defaults to `<data-dir>/private-config`, which `clean` removes.
The `keys` command intentionally continues to print only the known demo keys,
so it cannot accidentally disclose keys from that directory.

For an orchestrated devnet that must share one network identity with companion
processes, set `YANO_CLUSTER_DEVNET_GENESIS_FILE` to the pre-generated Shelley
genesis. The launcher accepts only a launcher-owned, readable regular
non-symlink file containing at most 1 MiB of valid UTF-8 JSON with unique keys;
neither the file nor its launcher-owned parent may be writable by other users.
On a fresh cluster it copies those exact bytes to node 0, and followers receive
the same identity. The supplied `systemStart` is passed to the producer as an
explicit timestamp, so the runtime does not rewrite this orchestrator-owned
genesis. A retained copy must remain byte-identical or startup fails closed.
This option is valid only for `devnet`; when it is unset, the standalone
launcher's existing `epochLength = 500` and persisted `systemStart` behavior
remain unchanged.

### L1 and app-chain identity markers

The launcher treats L1 and app-chain state as separate identities:

- `<data-dir>/cluster-identity.json` binds only the Cardano network and, for an
  orchestrated devnet, the supplied genesis digest. It can remain unchanged
  while different app-chain instances attach sequentially to the same stopped
  host L1 state.
- Standalone use also writes
  `<data-dir>/cluster-appchain-identity.json`, binding chain IDs, membership,
  threshold, proposer, and anchor signer. A restart with different keys or
  profile is rejected before a node starts.
- An orchestrator that stores app-chain state outside the L1 tree may set
  `YANO_CLUSTER_APPCHAIN_IDENTITY_MARKER` to its canonical
  `yano.demo.appchain-identity` JSON marker. The file must be launcher-owned,
  non-symlink, single-linked, bounded, and mode `0400` or `0600`; its chain,
  membership, quorum, proposer, network, and anchor identity must match the
  selected cluster profile. In this mode the launcher validates the external
  marker and does not create a standalone app-chain marker in the shared L1
  directory.

An external marker cannot replace a standalone marker that already exists.
Use the original standalone profile or a different data directory instead.
Before attaching another orchestrated instance to shared L1 state, stop the
current instance and rebind its separately managed app-chain storage; concurrent
attachments are not supported.

| node | preferred HTTP | preferred n2n (server) | role (devnet) |
|------|------|--------------|----------------|
| 0 | 7070 | 13337 | L1 block producer + member (fixed proposer) |
| i | 7070+i | 13337+i | L1 follower + member |

The defaults are preferred ranges. Before touching state, `cluster.sh start`
checks all ports needed by the requested node count. If a default range is
occupied, it moves to the first free contiguous range, prints the selected
ports, and records them in `<data-dir>/cluster.env`. Later `cluster.sh`
commands read that file automatically. Explicit `--http-base` /
`--server-base` values (and their `YANO_CLUSTER_*` environment equivalents)
are strict and fail early when busy or overlapping.

The data directory defaults to `/tmp/yano-appchain-cluster`. When using a
custom `--data-dir`, pass it to later commands so they can locate that
cluster's saved ports.

`cluster.env` and PID metadata are strictly parsed data files; they are never
sourced as shell code. `status` and `stop` accept a PID only when its record is
launcher-owned and bounded and the live process has the recorded start token,
owner UID, storage path, and node ports. Stale, malformed, reused, or unrelated
PIDs are reported but never signalled. Shutdown escalation targets that same
validated process only; the launcher never kills an arbitrary port listener.

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
export YANO_CLUSTER_API_KEY="$(openssl rand -hex 32)"  # recommended API key override
./cluster.sh start 3 --network preprod --anchor-mode metadata --anchor-key <that-hex>
# start prints the anchor wallet's enterprise address -> send tADA to it
# metadata mode: anchors start automatically once funded
# script mode:   ./cluster.sh anchor-bootstrap <chain>   (one-time, per chain)
```

To keep the anchor seed out of shell history, place the single 64-hex value in
a launcher-owned `chmod 400`/`600` regular non-symlink file under an owner-only
directory, then use:

```bash
YANO_CLUSTER_ANCHOR_KEY_FILE=/private/yano/anchor.seed \
  ./cluster.sh start 3 --network preprod --anchor-mode metadata
```

The file replaces `--anchor-key`; specifying both fails closed. Merely setting
the file does not enable anchoring—`--anchor` or `--anchor-mode` is still
required. Like member seeds, a file-supplied anchor seed is carried through the
generated private overlay rather than a process argument.

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
`--transport <shared|dedicated>`,
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
- **Ports busy** — default ports are relocated automatically. An explicitly
  requested range fails instead, because silently changing an operator choice
  would make automation target the wrong endpoint.
- **Retained devnet restart** — in standalone mode, `stop` keeps both RocksDB
  and each node's shifted `shelley-genesis.json`. With
  `YANO_CLUSTER_DEVNET_GENESIS_FILE`, it instead keeps the exact supplied bytes
  and explicit `systemStart`. A later `start` preserves the selected behavior.
  If retained state is missing that file, a supplied source differs, or a
  follower's copy differs from node 0, the launcher refuses to overwrite it:
  restore the original file or use `clean` for disposable state.
- Everything lives under `--data-dir` (logs, per-node chainstate, genesis
  copies); `./cluster.sh clean` wipes it. Stop the cluster before manually
  removing its directory so live processes do not become orphaned.
