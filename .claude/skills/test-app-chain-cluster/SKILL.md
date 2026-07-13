---
name: test-app-chain-cluster
description: App-chain smoke test — start a two-node Yano app-chain cluster on devnet (sequencer + member, L1 anchoring on), submit app messages, verify sequenced blocks/state roots/finality certs/MPF proofs/L1 anchor across both nodes, then clean up.
---

# Test: Yano App-Chain Cluster (two nodes, devnet L1)

End-to-end regression for the app-chain framework (adr/app-layer/005):
Node A = devnet L1 block producer **and** app-chain sequencer (proposer) with
L1 anchoring; Node B = L1 follower and app-chain member. Verifies the full
stack: authenticated diffusion (protocol 100), S1 sequencing with 2-of-2
finality certs, identical MPF state roots, inclusion proofs, catch-up
(protocol 103 runs implicitly), metadata anchor tx confirmed on L1, and that
L1 sync stays in lock-step throughout.

## Prerequisites
- Yano app uber-jar built: `./gradlew :app:quarkusBuild` (output: `app/build/yano.jar`)
- Local yaci `0.5.0-app-layer-local` published (`cd ../yaci && ./gradlew :core:publishToMavenLocal :helper:publishToMavenLocal -PskipSigning -x test`) if the jar build complains
- `jq`, `python3` on PATH
- Ports 7070, 7071, 13337, 13338 free

## Fixed test identities (Ed25519 seeds → pubkeys)
- Member A (proposer): seed `01`×32 → pub `8a88e3dd7409f195fd52db2d3cba5d72ca6709bf1d94121bf3748801b40f6f5c`
- Member B: seed `02`×32 → pub `8139770ea87d175f56a35466c34c7ecccb8d8a91b4ee37a25df60f5b8fc9b394`
- Anchor wallet: seed `03`×32 (address derived at runtime, printed in node A log)

## Test Steps

1. **Cleanup + genesis prep** (repo genesis file is NOT modified):
   ```bash
   lsof -ti:7070,7071,13337,13338 | xargs kill -9 2>/dev/null || true
   rm -rf /tmp/appchain-test && mkdir -p /tmp/appchain-test
   jq '.epochLength = 500' app/config/network/devnet/shelley-genesis.json \
     > /tmp/appchain-test/shelley-genesis-a.json
   ```
2. **Start Node A** (devnet BP + app-chain proposer + anchoring):
   ```bash
   cd app && PUB_A=8a88e3dd7409f195fd52db2d3cba5d72ca6709bf1d94121bf3748801b40f6f5c \
   PUB_B=8139770ea87d175f56a35466c34c7ecccb8d8a91b4ee37a25df60f5b8fc9b394 && \
   java -Dquarkus.profile=devnet -Dquarkus.http.port=7070 \
     -Dyano.genesis.shelley-genesis-file=/tmp/appchain-test/shelley-genesis-a.json \
     -Dyano.storage.path=/tmp/appchain-test/chainstate-a \
     -Dyano.app-chain.enabled=true \
     -Dyano.app-chain.chain-id=test-chain \
     -Dyano.app-chain.signing-key=0101010101010101010101010101010101010101010101010101010101010101 \
     -Dyano.app-chain.members="$PUB_A,$PUB_B" \
     -Dyano.app-chain.peers=localhost:13338 \
     -Dyano.app-chain.sequencer.proposer=$PUB_A \
     -Dyano.app-chain.threshold=2 \
     -Dyano.app-chain.block.interval-ms=1000 \
     -Dyano.app-chain.l1.stability-depth=5 \
     -Dyano.app-chain.anchor.enabled=true \
     -Dyano.app-chain.anchor.signing-key=0303030303030303030303030303030303030303030303030303030303030303 \
     -Dyano.app-chain.anchor.every-blocks=2 \
     -jar build/yano.jar > /tmp/appchain-test/node-a.log 2>&1 &
   ```
   Wait for `curl -sf http://localhost:7070/q/health/ready` (up to 30s), then ~3s.
3. **Start Node B** (L1 follower + app-chain member) with **A's effective genesis**
   (A rewrites `systemStart` into the file it loaded — copy AFTER A starts):
   ```bash
   cp /tmp/appchain-test/shelley-genesis-a.json /tmp/appchain-test/shelley-genesis-b.json
   java -Dquarkus.profile=devnet -Dquarkus.http.port=7071 \
     -Dyano.genesis.shelley-genesis-file=/tmp/appchain-test/shelley-genesis-b.json \
     -Dyano.storage.path=/tmp/appchain-test/chainstate-b \
     -Dyano.server.port=13338 \
     -Dyano.block-producer.enabled=false \
     -Dyano.dev-mode=false \
     -Dyano.client.enabled=true \
     -Dyano.remote.host=localhost -Dyano.remote.port=13337 \
     -Dyano.app-chain.enabled=true \
     -Dyano.app-chain.chain-id=test-chain \
     -Dyano.app-chain.signing-key=0202020202020202020202020202020202020202020202020202020202020202 \
     -Dyano.app-chain.members="$PUB_A,$PUB_B" \
     -Dyano.app-chain.peers=localhost:13337 \
     -Dyano.app-chain.sequencer.proposer=$PUB_A \
     -Dyano.app-chain.threshold=2 \
     -Dyano.app-chain.block.interval-ms=1000 \
     -Dyano.app-chain.l1.stability-depth=5 \
     -jar build/yano.jar > /tmp/appchain-test/node-b.log 2>&1 &
   ```
   Wait for `curl -sf http://localhost:7071/q/health/ready`, then ~10s for
   L1 sync + app-peer connections.
4. **Fund the anchor wallet** via the devnet faucet (address from A's log):
   ```bash
   ANCHOR_ADDR=$(grep -oE 'anchor wallet address: (addr[a-z0-9_]+)' /tmp/appchain-test/node-a.log | head -1 | sed 's/.*: //')
   curl -s -X POST http://localhost:7070/api/v1/devnet/fund \
     -H 'Content-Type: application/json' -d "{\"address\":\"$ANCHOR_ADDR\",\"ada\":100}"
   ```
5. **Verify peer connectivity** on both `/api/v1/app-chain/status`:
   `peers` map values all `true`, `sequencing: true`, A `role: proposer`, B `role: member`.
6. **Submit app messages** (both directions):
   ```bash
   curl -s -X POST http://localhost:7070/api/v1/app-chain/messages \
     -H 'Content-Type: application/json' -d '{"topic":"orders","body":"hello from node A"}'
   curl -s -X POST http://localhost:7071/api/v1/app-chain/messages \
     -H 'Content-Type: application/json' -d '{"topic":"orders","body":"hello from node B"}'
   sleep 5
   ```
7. **Verify the sequenced ledger** (the core assertions):
   - `GET :7070/api/v1/app-chain/tip` and `:7071/.../tip` → same `height` (>= 1)
     and **identical `stateRoot`**
   - `GET :7070/api/v1/app-chain/blocks/1` vs `:7071/.../blocks/1` → identical
     `stateRoot`/`messagesRoot`, `certSignatures >= 2`, proposer = PUB_A
   - Message visible on the *other* node (`GET /messages`, `source: "PEER"`)
   - `GET :7071/api/v1/app-chain/proof/<messageId>` → `proofWireHex` present,
     `finalizedAtHeight` set (MPF inclusion proof against the shared root)
8. **Verify the L1 anchor** (up to 90s):
   - `grep "Anchor CONFIRMED on L1" /tmp/appchain-test/node-a.log`
   - A's `/app-chain/status` → `anchor.anchoredCount >= 1`, `anchor.lastAnchorTx` set
9. **Verify L1 lock-step**: `/api/v1/status` on both — `utxo.lastAppliedBlock`
   equal (±1) and increasing between two polls 5s apart.
10. **Error scan**: no app-chain related ERRORs in either log
    (B's startup "empty chain state" ERRORs are expected and unrelated).
11. **Cleanup**:
    ```bash
    lsof -ti:7070,7071,13337,13338 | xargs kill -9 2>/dev/null || true
    rm -rf /tmp/appchain-test
    ```

## Pass Criteria
- Both nodes ready; app peers connected both ways
- App tips equal with identical state roots; blocks carry 2-of-2 finality certs
- Messages submitted on either node appear on the other (PEER source) and are
  finalized (`finalizedAtHeight`) with a retrievable MPF proof
- Anchor tx confirmed on L1 (`anchoredCount >= 1`)
- L1 sync in lock-step and still advancing at the end

## Related
- adr/app-layer/005-yano-app-chain-framework.md (design + status)
- CDDL specs: yaci `core/src/main/cddl/appmsg/`, yano `core-api/src/main/cddl/appchain/`
- For Haskell-downstream L1 compatibility use the existing `test-haskell-sync` skill
  (the app layer is invisible to Haskell peers: V100 is simply not negotiated).
