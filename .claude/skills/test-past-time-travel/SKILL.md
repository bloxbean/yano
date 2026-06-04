---
name: test-past-time-travel
description: Regression test - Start Yano devnet in past-time-travel mode, shift epochs, catch up to wall-clock, start Haskell node, verify full sync from slot 0
---

# Test: Past Time Travel Mode + Haskell Sync

Run this end-to-end regression test to verify the past-time-travel workflow:
genesis at slot 0, sequential block production, epoch catch-up, then Haskell node sync.

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
ships larger cost models (alonzo 332, conway 297) which 11.0.x rejects.

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
5. **Start Yano devnet** in past-time-travel mode, pointed at the `pv10/` genesis set:
   ```bash
   cd app && java -Dquarkus.profile=devnet -Dquarkus.http.port=7070 \
     -Dyano.block-producer.past-time-travel-mode=true \
     -Dyano.genesis.shelley-genesis-file=$(pwd)/config/network/devnet/pv10/shelley-genesis.json \
     -Dyano.genesis.byron-genesis-file=$(pwd)/config/network/devnet/pv10/byron-genesis.json \
     -Dyano.genesis.alonzo-genesis-file=$(pwd)/config/network/devnet/pv10/alonzo-genesis.json \
     -Dyano.genesis.conway-genesis-file=$(pwd)/config/network/devnet/pv10/conway-genesis.json \
     -Dyano.genesis.protocol-parameters-file=$(pwd)/config/network/devnet/pv10/protocol-param.json \
     -jar build/yano.jar > /tmp/yano-test-ptt.log 2>&1 &
   ```
6. **Wait ~5s**, verify log shows "Past time travel mode: block production deferred"
7. **Shift genesis back 4 epochs**:
   ```bash
   curl -s -X POST http://localhost:7070/api/v1/devnet/epochs/shift \
     -H 'Content-Type: application/json' -d '{"epochs": 4}'
   ```
8. **Verify** response shows `genesis_slot: 0` and `shift_millis: 480000`
9. **Verify sequential slot production** in logs: genesis at slot=0, Block #1 at slot=1, Block #2 at slot=2, etc. (NOT jumping to wall-clock slot ~2400)
10. **Let scheduler run ~5 seconds** to accumulate some blocks (should be at slot ~25)
11. **Catch up to wall-clock**:
    ```bash
    curl -s -X POST http://localhost:7070/api/v1/devnet/epochs/catch-up
    ```
12. **Verify** catch-up response shows ~2400 blocks produced, new slot near wall-clock
13. **Verify wall-clock mode active**: check tip a few seconds later — slot numbers should now jump based on real time (slot > blockNumber), not be sequential
14. **Copy genesis files** to the Haskell node from the same `pv10/` folder:
    ```bash
    bash scripts/haskell-compatibility/copy-devnet-genesis-to-haskell.sh app/config/network/devnet/pv10
    ```
    The script copies shelley/byron/alonzo/conway from `pv10/` so both nodes
    load identical bytes. Yano rewrites `systemStart` inside
    `pv10/shelley-genesis.json` after `/epochs/shift` runs, so this copy must
    happen after the shift.
15. **Verify systemStart** in `test-data-dir/haskell-node/files/shelley-genesis.json` is shifted back ~8 minutes from now
16. **Start Haskell node** clean:
    ```bash
    cd test-data-dir/haskell-node
    rm -rf db && mkdir -p db
    ./bin/cardano-node run --topology files/topology.json --database-path db \
      --socket-path db/node.socket --host-addr 0.0.0.0 --port 3002 \
      --config configuration.json > /tmp/haskell-test-ptt.log 2>&1 &
    ```
17. **Wait ~15-20s**, verify:
    - Haskell starts syncing from slot 0 ("Chain extended" at slot 0)
    - Haskell catches up through all ~2400+ past blocks quickly
    - Haskell reaches Yano's current tip
    - Live tip query (any time): `bash test-data-dir/haskell-node/tip.sh`
18. **Wait ~10s more**, verify both tips match (same slot, same block hash)
19. **Check for errors**: no real error/invalid/reject messages in Haskell log (ignore the verbose `Node configuration: …` dump line; the harmless `EKGView … Address already in use` line can be ignored)

## Pass Criteria
- Genesis produced at slot 0 (not wall-clock slot ~2400)
- Blocks produced sequentially: slot 0, 1, 2, 3... (during sequential mode)
- Catch-up produces ~2400 blocks to reach wall-clock
- After catch-up, scheduler uses wall-clock slots (slot > blockNumber)
- Haskell node syncs from slot 0 through entire chain
- Haskell catches up to Yano tip within ~20 seconds
- Block hashes match at tip
- No Haskell errors

## Cleanup
Kill both test nodes (by PID) after verification.

## Log files
- Yano:    `/tmp/yano-test-ptt.log`
- Haskell: `/tmp/haskell-test-ptt.log`

## Genesis config reference
- epochLength=600 slots, slotLength=0.2s → epoch = 120s
- 4 epochs back = 480,000ms → wall-clock slot ~2400 at genesis time
- activeSlotsCoeff=1.0 → every slot gets a block

## Notes
- Yano renamed from Yaci: jar is `app/build/yano.jar` and the past-time-travel
  property is `yano.block-producer.past-time-travel-mode` (NOT
  `yaci.node.block-producer.past-time-travel-mode`).
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
