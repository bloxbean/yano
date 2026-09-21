# Yano

A Cardano node implementation in Java — relay sync, local devnet, and REST API.

## Quick Start

### Relay Mode (Public Networks)

Sync from a public Cardano network and re-serve blocks on port 13337.

```bash
# Preprod trusted-single/indexer style (default)
./yano.sh start
# or
./yano.sh start:preprod

# Preprod relay-style upstream
./yano.sh start:preprod,relay

# Preprod relay-style upstream with Praos-lite header validation
./yano.sh start:preprod,relay,praos-lite

# Mainnet
./yano.sh start:mainnet

# Preview
./yano.sh start:preview

# SanchoNet
./yano.sh start:sanchonet

# Custom profile
./yano.sh start:mydevnet
```

Running `./yano.sh` without an action prints usage.

Chain state is stored in `./chainstate/` (RocksDB).

### Devnet Mode (Local Block Producer)

Run a standalone local blockchain with automatic block production.

```bash
./yano.sh start:devnet
```

- Protocol magic: 42
- Automatic block production (configurable interval)
- Built-in faucet: `POST http://localhost:7070/api/v1/devnet/fund`
- Snapshot: `POST http://localhost:7070/api/v1/devnet/snapshot`
- Restore: `POST http://localhost:7070/api/v1/devnet/restore/{name}`
- Time advance: `POST http://localhost:7070/api/v1/devnet/time/advance`
- Rollback: `POST http://localhost:7070/api/v1/devnet/rollback`

### Standalone App-Chain Demo

Add the `appchain` profile to start the built-in `orders-chain` as a
single-member ordered log:

```bash
# Local devnet block producer plus the standalone ordered log
./yano.sh start:devnet,appchain

# Public-network sync plus the standalone ordered log
./yano.sh start:preprod,appchain
```

The demo uses threshold `1` and a deterministic identity from
`config/application-appchain.yml`. That identity is for local testing only.
Use the Yano X showcase for multi-node examples and additional state machines.

## Key Features

- **REST API** (port 7070) — blocks, transactions, UTXOs, epochs, protocol params
- **Swagger UI** — `http://localhost:7070/q/swagger-ui`
- **Transaction submission** — `POST /api/v1/tx/submit` (CBOR or hex-encoded)
- **Plutus script evaluation** — `POST /api/v1/utils/txs/evaluate` (Ogmios-compatible)
- **Health check** — `http://localhost:7070/q/health/ready`
- **Cardano N2N server** on port 13337
- **JVM plugin system** — load extension JARs from the `plugins/` directory
- **GraalVM native runtime** — closed-world Yano core image with the built-in ordered log
- **Composable profiles** — `./yano.sh start:<network>,<behavior>[,<validation>]`, `./yano.sh --profile=<profiles>`, or `-Dquarkus.profile=<profiles>`

## Configuration

### Environment Variables

Override any config property via environment variables:

```bash
QUARKUS_HTTP_PORT=7071 YANO_SERVER_PORT=13338 \
  YANO_STORAGE_PATH=./chainstate-node-2 \
  QUARKUS_LOG_FILE_PATH=./yano-node-2.log \
  ./yano.sh start
YANO_REMOTE_HOST=localhost YANO_REMOTE_PORT=3001 ./yano.sh start
```

Every concurrently running node must have a distinct REST port, N2N port,
chainstate path and log path. App-chain and projection nodes must also use
distinct `YANO_APP_CHAIN_STORAGE_PATH` and `YANO_HISTORY_DIR` values.

### Runtime memory options

```bash
JAVA_OPTS="-Xmx4g -Xms2g" ./yano.sh start
```

`JAVA_OPTS` is honored by both distributions. Native launches use a mainnet-
validated 1536 MiB maximum heap by default; set `YANO_NATIVE_MAX_HEAP`, or put an explicit
`-Xmx` in `JAVA_OPTS`, to override it:

```bash
YANO_NATIVE_MAX_HEAP=2g ./yano.sh start
JAVA_OPTS="-Xmx2g" ./yano.sh start
```

### Extra Runtime Arguments

`YANO_EXTRA_ARGS` is passed to both jar and native distributions. For native-image runtime memory settings:

```bash
YANO_EXTRA_ARGS="-Xmx4g" ./yano.sh start
```

The startup script prints the configured `JAVA_OPTS` and `YANO_EXTRA_ARGS`
values before launching Yano.

### Config Files

The `config/` directory contains genesis files and protocol parameters for each network:

```
config/
  application.yml
  application-appchain.yml
  application-bootstrap.yml
  application-devnet.yml
  application-pruned.yml
  application-projection.yml
  application-preprod.yml
  application-relay.yml
  application-praos-lite.yml
  application-selective-utxo.yml
  network/
    devnet/
    mainnet/
    preprod/
    preview/
    sanchonet/
```

The base `application.yml` is conservative single-upstream/indexer style. Use
comma-separated Quarkus profiles to layer a network, an upstream behavior, and
optional validation:

```bash
./yano.sh start:preprod,relay
./yano.sh start:mainnet,trusted-peers
./yano.sh start:preview,relay,praos-lite
./yano.sh start:preprod,relay,praos-ledger
./yano.sh start:preprod,projection
./yano.sh start:preprod,pruned
BLOCKFROST_API_KEY=... ./yano.sh start:preprod,bootstrap
```

The `projection` profile enables the optional JVM-only history archive, written by
the canonical projection outbox into DuckLake.

The archive is fresh-sync only: it is built from genesis, and there is no
partial-coverage mode. The `history` profile that offered one was removed with
the replay-worker pipeline it configured.

What can be chosen is the set of block sections, through
`yano.history.projection.sections` — `transaction:v1`, `utxo-history:v1`,
`account-events:v1` and `address-transaction:v1`, all four by default. The choice
is part of the archive identity, so it is made once at first sync and cannot be
changed afterwards without resyncing; the bundled `wallet` profile uses it to
carry address transactions alone.

Epoch artifacts are not selectable. Rewards, epoch stake, ada pots, DRep
distribution and governance proposal status always ship.

The archive defaults to `./history`; override it with `YANO_HISTORY_DIR`.

### Storage and partial-state profiles

The `bootstrap` profile starts from a recent provider block instead of replaying
from genesis. It requires a new/empty chainstate and produces partial history.
It cannot be combined with `devnet`, `devnet-slotleader`, or `projection`.

The `pruned` profile retains a bounded window of block bodies. Pruned bodies can
only be recovered by restoring another database or resyncing, and the profile
cannot be combined with `wallet`.

The `selective-utxo` profile retains only configured addresses, payment
credentials, or plugin-selected outputs. Edit the profile or set the
comma-separated `YANO_FILTERS_UTXO_ADDRESSES` or
`YANO_FILTERS_UTXO_PAYMENT_CREDENTIALS` override before starting: an enabled
filter with no effective selector fails startup.
The resulting UTXO state is intentionally partial and cannot be combined with
`wallet`; disabling the filter later requires a resync to recover omitted UTXOs.

## Directory Structure

```
yano.sh                Start script
yano.jar               Uber-jar (JVM distribution)
yano                   Native binary (native distribution)
config/                Genesis and protocol parameter files
plugins/               JVM-only extension JARs
tools/yano-plugins/    JVM-only offline plugin catalog validator/inspector
```

## More Information

- Custom profiles: `CUSTOM_PROFILE.md`
- Build distributions: `docs/BUILD_DISTRIBUTIONS.md` in the source repository
- Plugin operations: `docs/PLUGIN_OPERATIONS.md` in the source repository
- GitHub: https://github.com/bloxbean/yano
- License: MIT
