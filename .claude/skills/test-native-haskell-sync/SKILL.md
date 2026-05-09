---
name: test-native-haskell-sync
description: Regression test - Build Yano native image with GraalVM Java 24, start native devnet in regular BP mode, start Haskell cardano-node, verify sync stays in lock-step for 2+ epochs
---

# Test: Haskell Node Sync — Native Image (Regular BP Mode)

Run this end-to-end regression test to verify that a **GraalVM native image** of Yano devnet works correctly with a Haskell cardano-node in regular block producer mode.

## Prerequisites
- GraalVM Java 24 set as `JAVA_HOME`
- `curl` and `tar` available on PATH (used to fetch the Haskell node release)
- No other test nodes running on ports 7070, 13337, 3002

The Haskell `cardano-node` binary is downloaded automatically into
`test-data-dir/haskell-node/` on first run by the setup script below.

## Configurable Haskell node version
Default: **11.0.1**.
Override with the `HASKELL_NODE_VERSION` env var, e.g.:
```bash
export HASKELL_NODE_VERSION=11.1.0
```
Releases come from <https://github.com/IntersectMBO/cardano-node>. The setup
script keeps the existing binary if its version is `>=` the requested one;
otherwise it downloads and replaces it. macOS uses the `macos-amd64` asset
(arm64 build has known compatibility issues for these tests).

## Conway genesis override (required for cardano-node 11.0.x)
cardano-node 11.0.x rejects Yano's bundled devnet `conway-genesis.json` because
its `plutusV3CostModel` has 297 entries while 11.0.x expects exactly 251:

```
"Number of parameters supplied 297 does not match the expected number of 251"
```

`scripts/haskell-compatibility/setup-haskell-test-node.sh` downloads the canonical preprod
`conway-genesis.json` (251 entries) into
`test-data-dir/genesis-overrides/conway-genesis.json`. Yano is then started
with `-Dyano.genesis.conway-genesis-file=…` so it loads the same file Yano's
peers will receive — keeping the conway-genesis hash consistent on both sides.
The `copy-devnet-genesis-to-haskell.sh` helper copies shelley/byron/alonzo from
Yano's devnet folder and overlays the override conway-genesis on top.

## Test Steps

1. **Kill any existing test nodes** on ports 7070/13337/3002
2. **Clean chainstate**: `rm -rf app/chainstate`
3. **Build Yano native image** (if not already built):
   ```bash
   ./gradlew :app:build -Dquarkus.profile=native
   ```
   This produces the native binary at `app/build/yano`.
   Verify the binary exists and is executable.
4. **Set up Haskell test node** (downloads cardano-node + conway override on first run, idempotent thereafter):
   ```bash
   bash scripts/haskell-compatibility/setup-haskell-test-node.sh
   ```
   Verify:
   - `test-data-dir/haskell-node/bin/cardano-node` exists and is executable
   - `test-data-dir/genesis-overrides/conway-genesis.json` exists
5. **Start Yano devnet** using the native binary, with the conway-genesis override:
   ```bash
   cd app && ./build/yano -Dquarkus.profile=devnet -Dquarkus.http.port=7070 \
     -Dyano.genesis.conway-genesis-file=$(pwd)/../test-data-dir/genesis-overrides/conway-genesis.json \
     > /tmp/yano-test-sync-native.log 2>&1 &
   ```
6. **Wait ~5s** for Yano to start, verify genesis block produced at slot 0 and blocks incrementing
7. **Copy genesis files** to the Haskell node:
   ```bash
   bash scripts/haskell-compatibility/copy-devnet-genesis-to-haskell.sh
   ```
   This copies `shelley/byron/alonzo` from Yano's devnet folder and overlays
   the override `conway-genesis.json` so both nodes load the same bytes.
8. **Verify systemStart** in `test-data-dir/haskell-node/files/shelley-genesis.json` matches Yano's
9. **Start Haskell node** clean:
   ```bash
   cd test-data-dir/haskell-node
   rm -rf db && mkdir -p db
   ./bin/cardano-node run --topology files/topology.json --database-path db \
     --socket-path db/node.socket --host-addr 0.0.0.0 --port 3002 \
     --config configuration.json > /tmp/haskell-test-sync-native.log 2>&1 &
   ```
10. **Wait ~15s**, verify Haskell node shows "Chain extended" messages and is syncing.
    You can also query the live tip with the bundled helper:
    ```bash
    bash test-data-dir/haskell-node/tip.sh
    ```
11. **Wait for 2 full epochs** (~4 minutes with epochLength=600, slotLength=0.2s):
    - After ~2 min: check both tips match (should be past slot 600 = epoch 1)
    - After ~4 min: check both tips match (should be past slot 1200 = epoch 2)
12. **Verify**:
    - Yano tip slot and Haskell tip slot are within 1-2 slots of each other
    - Block hashes match at same slot
    - No errors/invalid/reject messages in Haskell log (ignore the verbose `Node configuration: …` dump line; the harmless `EKGView … Address already in use` line can be ignored)

## Pass Criteria
- Native binary starts successfully (no reflection errors, no missing class errors)
- Genesis at slot 0
- Haskell syncs from slot 0
- Both nodes stay in sync for 2+ epochs (past slot 1200)
- Block hashes match
- No Haskell errors

## Cleanup
Kill both test nodes (by PID) after verification.

## Log files
- Yano:    `/tmp/yano-test-sync-native.log`
- Haskell: `/tmp/haskell-test-sync-native.log`

## Notes
- Yano renamed from Yaci: native binary is `app/build/yano` (NOT `yaci-node`); any
  block-producer system properties use the `yano.block-producer.*` prefix
  (NOT `yaci.node.block-producer.*`).
- The downloaded Haskell node and any test scratch state live under `test-data-dir/`,
  which is gitignored.
- **Dijkstra genesis file**: cardano-node 11.0.x requires a `DijkstraGenesisFile`
  entry in `configuration.json` plus a matching `files/dijkstra-genesis.json`.
  The setup script writes both automatically (using the upstream
  `testnet-template-dijkstra.json` parameters), so no manual action is required.
  Without it cardano-node 11.0.x exits with
  `AesonException "Error in $: key \"DijkstraGenesisFile\" not found"`.
- **Tip helper**: `test-data-dir/haskell-node/tip.sh` queries the running Haskell
  node's tip via the bundled `cardano-cli` and the local node socket. It returns
  JSON like `{"slot": 1246, "epoch": 2, "hash": "…", "syncProgress": "100.00"}`.
