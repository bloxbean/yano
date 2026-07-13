---
name: test-app-chain-rotation-governance
description: Regression test - Two-node Yano devnet app chain with sequencer.mode=rotating and membership.mode=governed (ADR 008.2/008.3) - verify proposership rotates across L1-slot windows and a governed member-add activates identically on both nodes, then clean up.
---

# Test: App-Chain Rotation + Governed Membership (ADR 008.2 / 008.3)

Companion to `test-app-chain-cluster` (which covers the S1/static defaults).
This skill covers the Iteration-2 modes on a live devnet: Node A = devnet L1
block producer, Node B = L1 follower; BOTH are app-chain members of a chain
with **no fixed proposer** (`sequencer.mode: rotating`, L1-slot-clocked
windows) and **chain-governed membership** (`membership.mode: governed`).

First validated live 2026-07-10 as the Iteration-2 merge gate (merge
`d875975`): blocks 1–2 were proposed by the FOLLOWER node B, block 3 by A —
real rotation with 2-of-2 certs — and a governed add-member activated
identically on both nodes purely from finalized chain history.

## Prerequisites
- Uber-jar built from the integration branch: `./gradlew :app:quarkusBuild`
  (output `app/build/yano.jar`); run the jar with **cwd = `app/`**
- `jq`, `python3` on PATH; ports 7070, 7071, 13337, 13338 free

## Fixed identities (Ed25519 seeds → pubkeys)
- Member A: seed `01`×32 → pub `8a88e3dd7409f195fd52db2d3cba5d72ca6709bf1d94121bf3748801b40f6f5c`
- Member B: seed `02`×32 → pub `8139770ea87d175f56a35466c34c7ecccb8d8a91b4ee37a25df60f5b8fc9b394`
- Governed-in member C (never runs a node): pub `ca93ac1705187071d67b83c7ff0efe8108e8ec4530575d7726879333dbdabe7c` (seed `04`×32)

## Steps

1. **Cleanup + genesis** (repo genesis NOT modified):
   ```bash
   lsof -ti:7070,7071,13337,13338 | xargs kill -9 2>/dev/null || true
   rm -rf /tmp/appchain-rotgov && mkdir -p /tmp/appchain-rotgov
   jq '.epochLength = 500' app/config/network/devnet/shelley-genesis.json \
     > /tmp/appchain-rotgov/genesis-a.json
   ```
2. **Start Node A** (devnet BP; app chain rotating + governed — note: NO
   `sequencer.proposer`; `sequencer.mode` alone enables sequencing):
   ```bash
   cd app && PUB_A=8a88e3dd7409f195fd52db2d3cba5d72ca6709bf1d94121bf3748801b40f6f5c \
   PUB_B=8139770ea87d175f56a35466c34c7ecccb8d8a91b4ee37a25df60f5b8fc9b394 && \
   java -Dquarkus.profile=devnet -Dquarkus.http.port=7070 \
     -Dyano.genesis.shelley-genesis-file=/tmp/appchain-rotgov/genesis-a.json \
     -Dyano.storage.path=/tmp/appchain-rotgov/chainstate-a \
     -Dyano.app-chain.enabled=true \
     -Dyano.app-chain.chain-id=rotgov-chain \
     -Dyano.app-chain.signing-key=0101010101010101010101010101010101010101010101010101010101010101 \
     -Dyano.app-chain.members="$PUB_A,$PUB_B" \
     -Dyano.app-chain.peers=localhost:13338 \
     "-Dyano.app-chain.sequencer.mode=rotating" \
     "-Dyano.app-chain.sequencer.window-slots=10" \
     "-Dyano.app-chain.membership.mode=governed" \
     -Dyano.app-chain.threshold=2 \
     -Dyano.app-chain.block.interval-ms=1000 \
     -jar build/yano.jar > /tmp/appchain-rotgov/node-a.log 2>&1 &
   ```
   Wait for `curl -sf http://localhost:7070/q/health/ready`, then ~3s.
3. **Start Node B** (L1 follower, same app-chain config, signing key seed
   `02`×32, `-Dyano.server.port=13338 -Dyano.block-producer.enabled=false
   -Dyano.dev-mode=false -Dyano.client.enabled=true -Dyano.remote.host=localhost
   -Dyano.remote.port=13337`, peers `localhost:13337`, port 7071, storage
   `chainstate-b`). Copy A's genesis AFTER A starts
   (`cp genesis-a.json genesis-b.json` — A rewrites `systemStart`).
   Wait for ready, then ~10s (L1 sync + app peer connect).
4. **Verify rotating mode is live** on `GET :7070/api/v1/app-chain/status`:
   `sequencer.mode == "rotating"`, `sequencer.currentWindow` > 0,
   `sequencer.currentProposer` ∈ {PUB_A, PUB_B}, `splitVotesObserved == 0`,
   peers connected on both nodes.
5. **Rotation across windows** — submit 3 messages ~12s apart (window =
   10 devnet slots ≈ 10s):
   ```bash
   for i in 1 2 3; do
     curl -s -X POST http://localhost:7070/api/v1/app-chain/messages \
       -H 'Content-Type: application/json' -d "{\"topic\":\"t\",\"body\":\"rot-$i\"}"
     sleep 12
   done
   ```
   Assert: tips equal with identical `stateRoot` on both; every
   `GET /blocks/{h}` has `certSignatures >= 2`; and the `proposer` field
   differs across blocks 1..3 (at least two distinct proposers — that IS the
   rotation; with 2 members and the hash-shuffle this holds for 3+ windows).
6. **Governed member-add** (endpoints are **chain-scoped ONLY** —
   `/app-chain/chains/rotgov-chain/admin/...`; a chain-less
   `/app-chain/admin/members/...` path does not exist and 404s silently):
   ```bash
   PUB_C=ca93ac1705187071d67b83c7ff0efe8108e8ec4530575d7726879333dbdabe7c
   # 1st approval (node A) — must change NOTHING (1 of 2)
   curl -s -X POST http://localhost:7070/api/v1/app-chain/chains/rotgov-chain/admin/members/add \
     -H 'Content-Type: application/json' -d "{\"publicKey\":\"$PUB_C\"}"
   sleep 8   # command finalizes; status.members must still be 2 on both
   # 2nd approval (node B) — identical command reaches threshold 2
   curl -s -X POST http://localhost:7071/api/v1/app-chain/chains/rotgov-chain/admin/members/add \
     -H 'Content-Type: application/json' -d "{\"publicKey\":\"$PUB_C\"}"
   sleep 12
   ```
   Assert: `status.members == 3` on BOTH nodes; both logs contain
   `Governance ACTIVATED: add member ca93...` with the SAME `from height N`;
   tips still equal with identical state roots; ordinary submissions still
   finalize afterwards.
7. **Error scan**: no app-chain ERRORs in either log (B's startup
   "empty chain state" ERRORs are expected and unrelated).
8. **Cleanup**:
   ```bash
   lsof -ti:7070,7071,13337,13338 | xargs kill -9 2>/dev/null || true
   rm -rf /tmp/appchain-rotgov
   ```

## Pass criteria
- Rotating mode reported in status with a live window/proposer
- ≥ 2 distinct proposers across the first 3 blocks; all blocks 2-of-2
  certified; identical state roots on both nodes throughout
- One approval changes nothing; the second identical approval activates the
  member-add **identically on both nodes** (same activation height in logs)
- Chain keeps finalizing ordinary messages after the governance change
- No unexpected app-chain errors; clean shutdown

## Notes / gotchas
- `sequencer.window-slots=10` with ~1s devnet slots ⇒ ~10s windows; slower
  hardware may need a larger window (rotation needs the L1 clock feed, which
  the devnet BP provides on node A and L1 sync provides on node B).
- Prefer thresholds like 2-of-3 in real deployments (ADR 008.2 §0 residual
  split-vote case); 2-of-2 here is fine for the smoke's purposes.
- Member admin endpoints are chain-scoped only (found the hard way during the
  first gate run — the chain-less call 404s and nothing is submitted).

## Related
- adr/app-layer/008.2-rotating-sequencer.md (design + delivery notes)
- adr/app-layer/008.3-chain-governed-membership.md (design + delivery notes)
- `.claude/skills/test-app-chain-cluster` (S1/static defaults regression)
- `.claude/skills/test-app-chain-extensions` (ADR-006 extensions regression)
