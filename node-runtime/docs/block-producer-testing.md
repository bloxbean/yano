# Block Producer Mode - Integration Testing with Yaci Store

## Overview

This document describes how to run Yano in block producer (devnet) mode and verify end-to-end sync with yaci-store.

## Prerequisites

- Java 21
- Yaci project built: `./gradlew clean build`
- yaci-store v2.0.0+ downloaded from https://github.com/bloxbean/yaci-store/releases
- Genesis files from yaci-devkit: `~/.yaci-cli/local-clusters/default/node/genesis/`

## Architecture

```
Genesis Server (port 10000)       Yano (n2n: 13337, REST: 9000)
  serves genesis.zip                 block producer (devnet mode)
        |                                    |
        +--- downloaded at startup ---+      |
                                      |      |
                              Yaci Store (port 7070)
                                connects via n2n ChainSync
                                indexes blocks into H2 DB
```

## Genesis Files

yaci-store with `devkit-node=true` fetches genesis files from a hardcoded URL:
```
GET http://localhost:10000/local-cluster/api/admin/devnet/genesis/download
```

This returns a ZIP containing 4 genesis JSON files (byron, shelley, alonzo, conway).

**Source**: Use genesis files from yaci-devkit's local devnet config:
```
~/.yaci-cli/local-clusters/default/node/genesis/
```

These genesis files use **protocol magic 42** and contain proper Plutus cost models (PlutusV1/V2) that yaci-store requires. Using incomplete genesis files (e.g., empty `costModels: {}` in alonzo-genesis.json) will cause a NullPointerException in yaci-store's `EraGenesisProtocolParamsUtil`.

**Important**: The protocol magic in genesis files (42) must match the yaci-node's `yaci.node.remote.protocol-magic` config property. Mismatch causes silent handshake failure.

## Step 1: Create Genesis ZIP and Server

```bash
# Copy genesis files
mkdir -p /tmp/yaci-test/genesis
cp ~/.yaci-cli/local-clusters/default/node/genesis/*.json /tmp/yaci-test/genesis/

# Create ZIP
cd /tmp/yaci-test/genesis
zip /tmp/yaci-test/genesis.zip byron-genesis.json shelley-genesis.json alonzo-genesis.json conway-genesis.json
```

Start a simple HTTP server to serve the ZIP at the expected URL:

```python
#!/usr/bin/env python3
# genesis_server.py
import http.server

GENESIS_ZIP = "/tmp/yaci-test/genesis.zip"
EXPECTED_PATH = "/local-cluster/api/admin/devnet/genesis/download"

class GenesisHandler(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path == EXPECTED_PATH:
            with open(GENESIS_ZIP, "rb") as f:
                data = f.read()
            self.send_response(200)
            self.send_header("Content-Type", "application/zip")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)
        else:
            self.send_response(404)
            self.end_headers()

if __name__ == "__main__":
    server = http.server.HTTPServer(("0.0.0.0", 10000), GenesisHandler)
    print("Genesis server on port 10000")
    server.serve_forever()
```

```bash
python3 genesis_server.py &
```

## Step 2: Build and Start Yano

```bash
# Build uber-jar (add type: uber-jar under quarkus.package.jar in application.yml)
./gradlew :node-app:quarkusBuild -x test

# Start in block producer mode
java \
  -Dyaci.node.server.port=13337 \
  -Dyaci.node.server.enabled=true \
  -Dyaci.node.client.enabled=false \
  -Dyaci.node.remote.protocol-magic=42 \
  -Dyaci.node.block-producer.enabled=true \
  -Dyaci.node.block-producer.block-time-millis=2000 \
  -Dyaci.node.block-producer.lazy=false \
  -Dyaci.node.block-producer.genesis-timestamp=0 \
  -Dyaci.node.block-producer.slot-length-millis=1000 \
  -Dyaci.node.storage.rocksdb=false \
  -jar node-app/build/yaci-node.jar
```

Verify: `curl http://localhost:9000/api/v1/node/tip`

## Step 3: Configure and Start Yaci Store

Create `config/application.properties` in the yaci-store directory:

```properties
store.cardano.host=localhost
store.cardano.port=13337
store.cardano.protocol-magic=42
store.cardano.devkit-node=true

spring.datasource.url=jdbc:h2:mem:mydb
spring.datasource.username=sa
spring.datasource.password=password

server.port=7070
```

**Key config notes**:
- `devkit-node=true` triggers genesis file download from port 10000
- Do NOT set genesis file paths when `devkit-node=true` (mutually exclusive)
- Protocol magic must match the yaci-node config (42)

```bash
cd yaci-store-2.0.0
java -jar yaci-store.jar
```

## Step 4: Verify Sync

```bash
# yaci-store latest block
curl http://localhost:7070/api/v1/blocks/latest

# yaci-node tip
curl http://localhost:9000/api/v1/node/tip

# Both should show the same block number and slot
```

## Troubleshooting

| Issue | Cause | Fix |
|-------|-------|-----|
| `NullPointerException: plutusCostModelNode is null` | alonzo-genesis.json has empty `costModels: {}` | Use devkit genesis files with PlutusV1/V2 cost models |
| Handshake timeout (no "Handshake Ok" in store logs) | Protocol magic mismatch between node and store | Ensure both use same magic (42 for devkit genesis) |
| `Genesis points not found` | Node not returning blocks via ChainSync | Check that InMemoryChainState has the hex-key fix (byte[] keys don't work with ConcurrentHashMap) |
| `No block header bytes found for point` | InMemoryChainState byte[] key equality bug | Fixed: InMemoryChainState now uses hex string keys |
| `ClassNotFoundException: QuarkusEntryPoint` | Using quarkus-run.jar without lib/ directory | Build with `jar.type: uber-jar` or run from quarkus-app/ dir |

## Known Limitations

- Blocks have dummy crypto (zero-filled vkeys, signatures) - not valid for real consensus
- Single-node only - no peer discovery or replication
- InMemoryChainState loses all data on restart (use RocksDB for persistence)
- yaci-store's `devkit-node=true` admin URL (`http://localhost:10000`) is hardcoded in v2.0.0
