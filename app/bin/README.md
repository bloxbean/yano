# Yano

A Cardano node implementation in Java — relay sync, local devnet, and REST API.

## Quick Start

### Relay Mode (Public Networks)

Sync from a public Cardano network and re-serve blocks on port 13337.

```bash
# Preprod (default)
./yano.sh start

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
- Built-in faucet: `POST http://localhost:8080/api/v1/devnet/fund`
- Snapshot: `POST http://localhost:8080/api/v1/devnet/snapshot`
- Restore: `POST http://localhost:8080/api/v1/devnet/restore/{name}`
- Time advance: `POST http://localhost:8080/api/v1/devnet/time/advance`
- Rollback: `POST http://localhost:8080/api/v1/devnet/rollback`

## Key Features

- **REST API** (port 8080) — blocks, transactions, UTXOs, epochs, protocol params
- **Swagger UI** — `http://localhost:8080/q/swagger-ui`
- **Transaction submission** — `POST /api/v1/tx/submit` (CBOR or hex-encoded)
- **Plutus script evaluation** — `POST /api/v1/utils/txs/evaluate` (Ogmios-compatible)
- **Health check** — `http://localhost:8080/q/health/ready`
- **Cardano N2N server** on port 13337
- **Plugin system** — drop plugin JARs in the `plugins/` directory
- **Custom profiles** — `./yano.sh start:<name>`, `./yano.sh --profile=<name>`, or `-Dquarkus.profile=<name>`

## Configuration

### Environment Variables

Override any config property via environment variables:

```bash
YANO_SERVER_PORT=3001 ./yano.sh start
YANO_REMOTE_HOST=localhost YANO_REMOTE_PORT=3001 ./yano.sh start
```

### JVM Options (JAR mode only)

```bash
JAVA_OPTS="-Xmx4g -Xms2g" ./yano.sh start
```

### Extra Runtime Arguments

`YANO_EXTRA_ARGS` is passed to both jar and native distributions. For native-image runtime memory settings:

```bash
YANO_EXTRA_ARGS="-Xmx4g" ./yano.sh start
```

The startup script prints the effective `JAVA_OPTS` and `YANO_EXTRA_ARGS` values before launching Yano.

### Config Files

The `config/` directory contains genesis files and protocol parameters for each network:

```
config/
  protocol-param.json
  network/
    devnet/
    mainnet/
    preprod/
    preview/
    sanchonet/
```

## Directory Structure

```
yano.sh                Start script
yano.jar               Uber-jar (JVM distribution)
yano                   Native binary (native distribution)
config/                Genesis and protocol parameter files
plugins/               Drop plugin JARs here
```

## More Information

- Custom profiles: `CUSTOM_PROFILE.md`
- Build distributions: `docs/BUILD_DISTRIBUTIONS.md` in the source repository
- GitHub: https://github.com/bloxbean/yaci
- License: MIT
