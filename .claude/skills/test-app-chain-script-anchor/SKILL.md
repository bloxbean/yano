---
name: test-app-chain-script-anchor
description: Regression test - Two-node Yano devnet app chain with SCRIPT anchors (ADR 008.4 A2) and L1 observers - bootstrap the thread NFT, verify threshold co-signed anchor advances on the devnet L1, follower identity adoption, and stability-gated ~l1 deposit observations, then clean up.
---

# Test: App-Chain Script Anchors + L1 Observations (ADR 008.4)

Companion to `test-app-chain-cluster` / `test-app-chain-rotation-governance`.
Covers Iteration 3 on a live devnet: Node A = devnet L1 block producer +
anchor leader (`anchor.mode: script`), Node B = L1 follower + co-signing
member with **zero anchor config**. Both watch an L1 address via the
`address-deposit` observer.

First validated live 2026-07-10 as the Iteration-3 merge gate: bootstrap
mint confirmed first try; two threshold co-signed advances confirmed on L1
(2/2 member witnesses; the on-chain validator enforced the monotonic datum
chain); B adopted the anchor identity from a verified sign request; a
faucet deposit was observed, stability-gated, injected and finalized
identically on both nodes.

## Prerequisites
- Uber-jar from the branch: `./gradlew :app:quarkusBuild` (`app/build/yano.jar`);
  run with **cwd = `app/`**
- `jq`, `python3` on PATH; ports 7070, 7071, 13337, 13338 free

## Fixed identities
- Member A: seed `01`×32 → pub `8a88e3dd7409f195fd52db2d3cba5d72ca6709bf1d94121bf3748801b40f6f5c`
- Member B: seed `02`×32 → pub `8139770ea87d175f56a35466c34c7ecccb8d8a91b4ee37a25df60f5b8fc9b394`
- Anchor wallet: seed `03`×32 → address (devnet magic 42, network 0):
  `addr_test1vz9ftj8dtzpsd65gsc94f6cvvhnhm74en9ufe30xegqg0xgkplq9s`
  (deterministic; also used as the WATCHED observer address — see gotchas)

## Steps

1. **Cleanup + genesis**:
   ```bash
   lsof -ti:7070,7071,13337,13338 | xargs kill -9 2>/dev/null || true
   rm -rf /tmp/appchain-anchor && mkdir -p /tmp/appchain-anchor
   jq '.epochLength = 500' app/config/network/devnet/shelley-genesis.json \
     > /tmp/appchain-anchor/genesis-a.json
   cp /tmp/appchain-anchor/genesis-a.json /tmp/appchain-anchor/genesis-b.json
   ```
2. **Start Node A** (devnet BP; script-anchor leader + observer). Save the
   PID (`echo "A_PID=$!" > /tmp/appchain-anchor/pids`) — restarts must kill
   by PID, NOT by port (see gotchas):
   ```bash
   cd app && java -Dquarkus.profile=devnet -Dquarkus.http.port=7070 \
     -Dyano.genesis.shelley-genesis-file=/tmp/appchain-anchor/genesis-a.json \
     -Dyano.storage.path=/tmp/appchain-anchor/chainstate-a \
     -Dyano.app-chain.enabled=true -Dyano.app-chain.chain-id=anchor-chain \
     -Dyano.app-chain.signing-key=01...(×32) \
     -Dyano.app-chain.members="$PUB_A,$PUB_B" -Dyano.app-chain.peers=localhost:13338 \
     -Dyano.app-chain.sequencer.proposer=$PUB_A -Dyano.app-chain.threshold=2 \
     -Dyano.app-chain.block.interval-ms=1000 \
     -Dyano.app-chain.anchor.enabled=true \
     -Dyano.app-chain.anchor.signing-key=03...(×32) \
     -Dyano.app-chain.anchor.mode=script \
     -Dyano.app-chain.anchor.every-blocks=2 \
     -Dyano.app-chain.l1.stability-depth=8 \
     "-Dyano.app-chain.observers.deposits.type=address-deposit" \
     "-Dyano.app-chain.observers.deposits.address=$WALLET" \
     -jar build/yano.jar > /tmp/appchain-anchor/node-a.log 2>&1 &
   ```
   Wait for ready. Log must show `L1 SCRIPT anchoring enabled (008.4)` and
   `L1 observers configured: [deposits, ...]`.
3. **Start Node B** (L1 follower, member, **NO anchor config**, SAME
   observers config — observers must be identical on every member; observers
   also require `l1.stability-depth > 0` or start fails):
   `-Dyano.server.port=13338 -Dyano.block-producer.enabled=false
   -Dyano.dev-mode=false -Dyano.client.enabled=true -Dyano.remote.host=localhost
   -Dyano.remote.port=13337`, signing key `02`×32, peers `localhost:13337`,
   port 7071, storage `chainstate-b`, same `l1.stability-depth` + observers.
   Wait until `GET :7071/api/v1/node/status` shows `localTipSlot` within ~20
   of `remoteTipSlot` (if it wedges at a low slot with `remote: None`, that's
   the known first-boot chain-sync gap — restart B **by PID** and re-wait).
4. **Fund the anchor wallet** (this also fires the deposit observer):
   ```bash
   curl -s -X POST http://localhost:7070/api/v1/devnet/fund \
     -H 'Content-Type: application/json' -d "{\"address\":\"$WALLET\",\"ada\":500}"
   ```
5. **Bootstrap the script anchor** (admin, leader only; retry until the
   wallet UTxO is visible):
   ```bash
   curl -s -X POST http://localhost:7070/api/v1/app-chain/admin/anchor/bootstrap
   ```
   → 202 with `txHash`, `threadPolicyId`, `scriptHash`, `scriptAddress`.
   Wait for A's log: `Script-anchor bootstrap CONFIRMED on L1`; status
   `anchor.bootstrapped == true` with the identity fields.
6. **Co-signed advances** — submit 3 messages (`POST /app-chain/messages`,
   ~2s apart), then wait (~60–90s; co-sign rounds tick every 10s):
   - A log: `co-sign round started ... signers=2`, then
     `Script-anchor tx submitted ... 2 member witnesses`, then
     `Script-anchor CONFIRMED on L1: tx=..., app blocks 1..N`
   - B log: `Script-anchor: verified advance body ... witness sent` and
     (first time) `Script-anchor identity adopted from member sign request`
   - Assert `status.anchor.anchoredCount >= 1` on A; submit 3 more messages
     and wait for `anchoredCount >= 2` — the SECOND advance proves the
     on-chain validator accepted a monotonic datum progression (real Plutus
     V3 spend validated by the devnet ledger).
   - Assert B `status.anchor.bootstrapped == true` with the SAME
     `threadPolicyId` (zero-config identity adoption).
7. **L1 observation e2e** — make a fresh deposit:
   ```bash
   curl -s -X POST http://localhost:7070/api/v1/devnet/fund \
     -H 'Content-Type: application/json' -d "{\"address\":\"$WALLET\",\"ada\":42}"
   ```
   Wait ≥ stability-depth L1 blocks (~10–30s), then assert BOTH nodes return
   observation messages: `GET /api/v1/app-chain/messages/by-topic/~l1%2Fdeposits`
   (URL-encoded topic). Followers finalized them only after recomputing the
   claims from their OWN L1 stream — the consensus-critical path.
8. **Consistency + error scan**: identical `stateRoot` on both nodes; no
   app-chain ERRORs in either log.
9. **Cleanup**:
   ```bash
   lsof -ti:7070,7071,13337,13338 | xargs kill -9 2>/dev/null || true
   rm -rf /tmp/appchain-anchor
   ```

## Pass criteria
- Bootstrap mint (Plutus V3, one-shot thread NFT) confirmed on the devnet L1
- ≥ 2 co-signed advances confirmed: each with 2/2 member witnesses; the
  validator-enforced monotonic datum chain progressed on-chain
- Follower adopted the anchor identity with ZERO anchor config
- Deposit observations finalized identically on both nodes (stability-gated,
  follower-verified)
- Identical state roots; no unexpected app-chain errors; clean shutdown

## Notes / gotchas
- **Never kill/restart one node via `lsof -ti:<port>`** — established
  connections match the port too, so killing "B's port 13338" also kills A
  (its outbound app-peer connection). Kill by saved PID. (Full-cluster
  cleanup by ports is fine — everything dies together, which is intended.)
- Watching the ANCHOR WALLET address means anchor-tx change outputs also
  produce deposit observations — deterministic and harmless here (it makes
  one faucet call exercise both features), but real deployments should watch
  a distinct address.
- A leader restart drops in-memory state by design: pool messages and
  pending (pre-stability) observations are lost; persisted things (anchor
  identity, ledger, vote locks) survive. Known engine edge (pending-tasks):
  a vote-locked height whose proposal expired during the restart wedges that
  height permanently — if you see `Locked proposal ... has expired` forever,
  wipe and rerun rather than debugging the run.
- The under-fee failure mode this gate originally caught (`fee ... is less
  than minimum required`) is fixed by pricing pending co-sign witnesses into
  the fee; if it reappears, check `VKEY_WITNESS_BYTES` in ScriptAnchorService.
- Faucet requires dev-mode (node A under the devnet profile has it; B runs
  `-Dyano.dev-mode=false`).

## Related
- adr/app-layer/008.4-script-anchors-l1view.md (design + delivery notes)
- core-api/src/main/cddl/appchain/anchor-v1.cddl, l1-observation-v1.cddl
- appchain/onchain/ (julc module + Aiken twin, shared conformance vectors)
- `.claude/skills/test-app-chain-rotation-governance` (Iteration-2 modes)
