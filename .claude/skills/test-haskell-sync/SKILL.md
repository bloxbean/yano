---
name: test-haskell-sync
description: Regression test - Start Yano devnet in regular BP mode, start Haskell cardano-node, verify sync stays in lock-step for 2+ epochs
---

# Test: Haskell Node Sync (Regular BP Mode)

Run this end-to-end regression test to verify that a Haskell cardano-node can connect to and stay in sync with Yano devnet in regular block producer mode.

## Prerequisites
- Yano app uber-jar built: `./gradlew :app:quarkusBuild` (output: `app/build/yano.jar`)
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

## Genesis source: `app/config/network/devnet/pv10/`
cardano-node 11.0.x enforces strict cost-model length validation: alonzo
expects exactly 166 PlutusV1 entries and conway expects exactly 251 PlutusV3
entries. The PV11-flavoured devnet folder at `app/config/network/devnet/`
ships larger cost models (alonzo 332, conway 297) which 11.0.x rejects with
errors like `"Number of parameters supplied 332 does not match the expected
number of 166"`.

A Haskell-compatible PV10 drop-in lives at `app/config/network/devnet/pv10/`
with `alonzo-genesis.json` (166 entries) and `conway-genesis.json` (251
entries). The kes/opcert/vrf signing keys in `pv10/` are byte-identical to the
ones in `devnet/`. Yano is started with `-Dyano.genesis.*-file=…/pv10/…` so
the same files reach the Haskell peer via
`copy-devnet-genesis-to-haskell.sh app/config/network/devnet/pv10`.

`setup-haskell-test-node.sh` still downloads `cardano-node`, the
dijkstra-genesis stub, and the topology/config scaffolding. The
`test-data-dir/genesis-overrides/conway-genesis.json` it writes is no longer
the source of truth for these tests — it remains only as a fallback for
callers that point at the PV11 devnet folder directly.

## Test Steps

1. **Kill any existing test nodes** on ports 7070/13337/3002
2. **Clean chainstate**: `rm -rf app/chainstate`
3. **Build Yano** (if not already built): `./gradlew :app:quarkusBuild`
4. **Set up Haskell test node** (downloads cardano-node + conway override on first run, idempotent thereafter):
   ```bash
   bash scripts/haskell-compatibility/setup-haskell-test-node.sh
   ```
   Verify:
   - `test-data-dir/haskell-node/bin/cardano-node` exists and is executable
   - `test-data-dir/genesis-overrides/conway-genesis.json` exists
5. **Start Yano devnet** pointed at the `pv10/` genesis set:
   ```bash
   cd app && java -Dquarkus.profile=devnet -Dquarkus.http.port=7070 \
     -Dyano.genesis.shelley-genesis-file=$(pwd)/config/network/devnet/pv10/shelley-genesis.json \
     -Dyano.genesis.byron-genesis-file=$(pwd)/config/network/devnet/pv10/byron-genesis.json \
     -Dyano.genesis.alonzo-genesis-file=$(pwd)/config/network/devnet/pv10/alonzo-genesis.json \
     -Dyano.genesis.conway-genesis-file=$(pwd)/config/network/devnet/pv10/conway-genesis.json \
     -Dyano.genesis.protocol-parameters-file=$(pwd)/config/network/devnet/pv10/protocol-param.json \
     -jar build/yano.jar > /tmp/yano-test-sync.log 2>&1 &
   ```
6. **Wait ~5s** for Yano to start, verify genesis block produced at slot 0 and blocks incrementing
7. **Copy genesis files** to the Haskell node from the same `pv10/` folder:
   ```bash
   bash scripts/haskell-compatibility/copy-devnet-genesis-to-haskell.sh app/config/network/devnet/pv10
   ```
   The script copies shelley/byron/alonzo/conway from `pv10/` so both nodes
   load identical bytes.
8. **Verify systemStart** in `test-data-dir/haskell-node/files/shelley-genesis.json` matches Yano's
   (Yano rewrites the `systemStart` field inside `pv10/shelley-genesis.json` at startup).
9. **Start Haskell node** clean:
   ```bash
   cd test-data-dir/haskell-node
   rm -rf db && mkdir -p db
   ./bin/cardano-node run --topology files/topology.json --database-path db \
     --socket-path db/node.socket --host-addr 0.0.0.0 --port 3002 \
     --config configuration.json > /tmp/haskell-test-sync.log 2>&1 &
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
- Genesis at slot 0
- Haskell syncs from slot 0
- Both nodes stay in sync for 2+ epochs (past slot 1200)
- Block hashes match
- No Haskell errors

## Cleanup
Kill both test nodes (by PID) after verification.

## Log files
- Yano:    `/tmp/yano-test-sync.log`
- Haskell: `/tmp/haskell-test-sync.log`

## Notes
- Yano renamed from Yaci: jar is `app/build/yano.jar` and any block-producer system
  properties use the `yano.block-producer.*` prefix (NOT `yaci.node.block-producer.*`).
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
