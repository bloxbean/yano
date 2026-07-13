---
name: test-app-chain-extensions
description: Full regression of the ADR-006 app-chain extensions on a two-node devnet cluster — multi-chain (ordered-log + kv-registry), query surface, SSE, webhook sink, admin API (pause/drain/force-anchor), key rotation, evidence bundle, snapshot, metrics — then clean up.
---

# Test: App-Chain Extensions (ADR-006 Waves 1–5) on a devnet cluster

Extends `test-app-chain-cluster` (which validates the ADR-005 core) to the
enterprise extensions. Node A = devnet L1 BP + sequencer of BOTH chains with
anchoring on chain 1; Node B = follower/member. Uses multi-chain
`chains[i]` config — also regression-tests the flat→indexed config path.

## Prerequisites
- `./gradlew :app:quarkusBuild` → `app/build/yano.jar` (integration branch)
- `jq`, `python3`, `jshell` on PATH; ports 7070, 7071, 13337, 13338, 9099 free
- **Run the jar with cwd = `app/`** — the devnet profile resolves
  `config/network/devnet/*.json` relative to the working directory.

## Identities (fixed test seeds)
- A (proposer): seed `01`×32 → pub `8a88e3dd7409f195fd52db2d3cba5d72ca6709bf1d94121bf3748801b40f6f5c`
- B: seed `02`×32 → pub `8139770ea87d175f56a35466c34c7ecccb8d8a91b4ee37a25df60f5b8fc9b394`
- Rotated-in member C: seed `04`×32 (pub printed by step 9)
- Anchor wallet: seed `03`×32

## Steps

1. **Cleanup + genesis prep** — same as test-app-chain-cluster step 1 (`/tmp/appchain-ext`).
2. **Webhook sink stub**: `python3` HTTP server on :9099 appending request bodies
   to `/tmp/appchain-ext/webhook.log`, one JSON per line.
3. **Start Node A** — devnet flags as in test-app-chain-cluster, but app-chain
   config via `yano.app-chain.chains[i].*`:
   - `chains[0]`: chain-id `test-chain`, ordered-log, threshold 2, proposer A,
     peers localhost:13338, `anchor.enabled=true` (seed 03, every-blocks 2),
     `webhooks=http://localhost:9099/hook`, block interval 1000ms
   - `chains[1]`: chain-id `kv-chain`, `state-machine=kv-registry`, threshold 2,
     proposer A, peers localhost:13338
   (signing-key/members per chain; `yano.app-chain.enabled` NOT set — presence
   of chains[] auto-enables.)
4. **Start Node B** — follower flags as in test-app-chain-cluster (dev-mode off,
   BP off, remote=A) + the mirrored `chains[0..1]` config (signing-key seed 02,
   peers localhost:13337). Copy A's genesis AFTER A starts.
5. **Fund anchor wallet** from A's log address via `/api/v1/devnet/fund`.
6. **Multi-chain checks**: `GET /app-chain/chains` on both → 2 chains; chain-less
   legacy paths return 400 (ambiguous); scoped paths work:
   `POST /app-chain/chains/test-chain/messages` on A; read it from B.
7. **kv-registry (stdlib)**: CBOR PUT command via python
   (`[0, key, value]` → hex) submitted to `kv-chain` on A; on B verify
   `GET /app-chain/chains/kv-chain/proof/<hex(key)>` present and
   state roots equal on both.
8. **Query surface**: on B `GET .../test-chain/messages/by-topic/orders`,
   `GET .../messages/{messageId}` (position+content), `GET .../blocks?limit=5`.
9. **SSE**: `curl -N .../test-chain/stream?fromHeight=1` for ~8s captures an
   `app-message` event for an earlier message (replay) — run in background,
   submit one more message, expect the live event too.
10. **Webhook**: `/tmp/appchain-ext/webhook.log` contains blocks in ascending
    height order with the submitted messages; A's status shows `sinks` cursor > 0.
11. **Admin (E5.4)**: `POST .../test-chain/admin/pause` → submit returns 500
    "paused"; `admin/resume` → submit OK; `admin/force-anchor` → `anchorTriggered`
    true|false (true if unanchored blocks exist); anchor confirms in log.
12. **Rotation (E4.5)**: derive pub C from seed 04 (jshell or python+jar not
    needed — use `POST admin/members/add` with C's pub on BOTH nodes;
    `GET admin/members` → 3 members, threshold 2 on both; submit another
    message → still finalizes (A+B votes suffice). Then `admin/members/remove`
    C on both; members back to 2.
13. **Evidence (E3.4)**: after an anchor confirms,
    `GET .../test-chain/evidence/<messageId>` → JSON with `blocksCbor`,
    `members`, `threshold: 2` and (if the anchor covers it) `anchor.txHash`.
14. **Snapshot (E5.3)**: `POST .../test-chain/snapshot {"path":"/tmp/appchain-ext/snap"}`
    → height returned; directory non-empty.
15. **Metrics (E5.1)**: `GET :7070/q/metrics` contains
    `yano_appchain_tip_height` with `chain="test-chain"` and `chain="kv-chain"`.
16. **Core invariants** (from the base skill): tips equal + identical state
    roots per chain on A and B; certSignatures >= 2; anchor confirmed
    (`anchoredCount >= 1`); L1 lock-step advancing; no unexpected app-chain
    ERRORs in logs.
17. **Cleanup**: kill ports 7070/7071/13337/13338/9099, `rm -rf /tmp/appchain-ext`.

## Pass criteria
Every step's assertion holds; both chains sequence independently with
identical roots across nodes; extensions behave per ADR-006 delivery notes.

## Run notes (2026-07-08, first execution — PASSED)
- Two shipped-config bugs found and fixed during this run:
  (1) `application.yml` carried an explicit `yano.app-chain.enabled: false`,
  which suppresses `chains[i]` auto-enable (now commented out — absence means
  disabled); (2) `AppChainMetrics` gated only on the flat enabled flag, so
  multi-chain nodes exposed no metrics (now also checks `chains[0].chain-id`).
- Evidence for a message finalized above the last confirmed anchor is
  correctly finality-only (`anchor: null`); the bundle's `members` reflects
  the epoch at the message height (rotation-aware).
- kv proof for a state key returns `finalizedAtHeight: null` (that field is
  message-id-specific) — presence of `proofWireHex` is the assertion.

## Related
- `.claude/skills/test-app-chain-cluster/SKILL.md` (ADR-005 core regression)
- adr/app-layer/006-appchain-enterprise-extensions-and-zk.md
